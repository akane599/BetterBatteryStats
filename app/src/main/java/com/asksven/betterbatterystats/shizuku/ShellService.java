/*
 * Copyright (C) 2011-2018 asksven
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.asksven.betterbatterystats.shizuku;

import android.os.Process;
import android.util.Log;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/**
 * The other half of {@link ShizukuShell}: this runs inside the Shizuku user-service process, which
 * Shizuku starts as the shell user (uid 2000) or as root.
 *
 * <p>Nothing here is called on the app side — Shizuku instantiates this class in a separate process
 * and hands the app a binder to it. It therefore must not reference anything from the app's own
 * Context, resources, or singletons: none of that exists on the other side.</p>
 */
public class ShellService extends IShellService.Stub
{
	private static final String TAG = "BbsShellService";

	/**
	 * Shizuku looks for either a no-argument constructor or one taking a Context. The no-argument
	 * one is used here because this service needs nothing from the app.
	 */
	public ShellService()
	{
	}

	@Override
	public int getUid()
	{
		return Process.myUid();
	}

	@Override
	public void destroy()
	{
		Log.i(TAG, "destroy() requested, exiting");
		System.exit(0);
	}

	@Override
	public List<String> exec(String command, long timeoutMillis)
	{
		List<String> output = new ArrayList<>();

		if (command == null || command.isEmpty())
		{
			return output;
		}

		java.lang.Process process = null;
		Thread watchdog = null;

		try
		{
			process = Runtime.getRuntime().exec(new String[]{"sh", "-c", command});

			// Process.waitFor(long, TimeUnit) is API 26, and this class runs on whatever release the
			// device is on, so the timeout is enforced by a watchdog instead: destroying the process
			// closes its stdout, which unblocks the read below.
			watchdog = startWatchdog(process, command, timeoutMillis);

			// stderr is redirected into stdout by the caller where it matters, rather than read on a
			// second thread: leaving it unread risks filling its pipe buffer and blocking.
			try (BufferedReader reader = new BufferedReader(
					new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8)))
			{
				String line;
				while ((line = reader.readLine()) != null)
				{
					output.add(line);
				}
			}

			process.waitFor();
		}
		catch (Exception e)
		{
			Log.e(TAG, "Command failed: " + command, e);
		}
		finally
		{
			if (watchdog != null)
			{
				watchdog.interrupt();
			}

			if (process != null)
			{
				process.destroy();
			}
		}

		return output;
	}

	/**
	 * @return a daemon thread that destroys {@code process} once {@code timeoutMillis} has elapsed,
	 *         unless it is interrupted first.
	 */
	private static Thread startWatchdog(final java.lang.Process process, final String command,
			final long timeoutMillis)
	{
		Thread watchdog = new Thread(() ->
		{
			try
			{
				Thread.sleep(timeoutMillis);
				Log.w(TAG, "Command timed out after " + timeoutMillis + "ms: " + command);
				process.destroy();
			}
			catch (InterruptedException e)
			{
				// the command finished in time
			}
		}, "bbs-shell-watchdog");

		watchdog.setDaemon(true);
		watchdog.start();

		return watchdog;
	}
}
