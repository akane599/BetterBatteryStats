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
package com.asksven.android.common;

import android.util.Log;

import com.asksven.betterbatterystats.shizuku.ShizukuShell;

import java.util.List;

/**
 * Runs a command through whichever shell has the most privilege on this device.
 *
 * <p>The commands that matter here — {@code dumpsys batterystats}, {@code dumpsys alarm} — need
 * {@code android.permission.DUMP}, which an ordinary app process does not have. Before Shizuku the
 * only options were root, or a user granting the permission over adb by hand. The order of
 * preference is:</p>
 *
 * <ol>
 *   <li><b>root</b> — if the device is rooted, this is the least surprising choice for a user who
 *       already has it, and needs no second app;</li>
 *   <li><b>Shizuku</b> — adb-shell privileges without root, once the user has started Shizuku;</li>
 *   <li><b>the app's own shell</b> — works only if DUMP was granted some other way, and is what the
 *       app used unconditionally before.</li>
 * </ol>
 *
 * <p>All of these block, so none may be called from the main thread.</p>
 */
public final class PrivilegedShell
{
	private static final String TAG = "BbsPrivilegedShell";

	/** Which shell actually served the last command; for diagnostics. */
	public enum Backend
	{
		ROOT,
		SHIZUKU,
		UNPRIVILEGED
	}

	private PrivilegedShell()
	{
		// static helpers only
	}

	/**
	 * @return the backend that {@link #run(String)} would use right now.
	 */
	public static Backend getBackend()
	{
		if (isRootAvailable())
		{
			return Backend.ROOT;
		}

		if (ShizukuShell.getState() == ShizukuShell.State.READY)
		{
			return Backend.SHIZUKU;
		}

		return Backend.UNPRIVILEGED;
	}

	/**
	 * Runs a command through the most privileged shell available.
	 *
	 * @return the command's standard output, one entry per line; empty, never null, on failure
	 */
	public static List<String> run(String command)
	{
		Backend backend = getBackend();

		switch (backend)
		{
			case ROOT:
			{
				List<String> output = RootShell.getInstance().run(command);
				if (!output.isEmpty())
				{
					return output;
				}

				// A rooted device whose su prompt was declined still leaves Shizuku as an option,
				// so an empty result is worth a second attempt rather than an immediate give-up.
				if (ShizukuShell.getState() == ShizukuShell.State.READY)
				{
					Log.i(TAG, "Root produced no output, retrying through Shizuku: " + command);
					return ShizukuShell.getInstance().run(command);
				}

				return output;
			}

			case SHIZUKU:
				return ShizukuShell.getInstance().run(command);

			case UNPRIVILEGED:
			default:
				return NonRootShell.getInstance().run(command);
		}
	}

	/**
	 * @return whether an su binary is present.
	 *
	 * <p>Deliberately the static check rather than {@code RootShell.getInstance().isRooted()}: the
	 * latter opens a root shell as a side effect, which raises the superuser prompt. This is called
	 * on every stat refresh to decide where the data can come from, so it has to be cheap and
	 * silent. Whether root is actually *granted* is settled when a command is run.</p>
	 */
	private static boolean isRootAvailable()
	{
		try
		{
			return com.stericson.RootTools.RootTools.isRootAvailable();
		}
		catch (Throwable t)
		{
			return false;
		}
	}
}
