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

import android.content.ComponentName;
import android.content.Context;
import android.content.ServiceConnection;
import android.content.pm.PackageManager;
import android.os.IBinder;
import android.util.Log;

import com.asksven.betterbatterystats.BuildConfig;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import rikka.shizuku.Shizuku;

/**
 * Runs shell commands with adb-shell privileges through Shizuku.
 *
 * <p>Android 14 moved {@code BatteryStatsImpl} out of the app-visible framework, so the reflective
 * path the app was built on is gone for good. What remains is {@code dumpsys batterystats}, which
 * needs {@code android.permission.DUMP} — a permission no ordinary app can hold. Shizuku closes
 * exactly that gap: the user starts it once over adb (or from a rooted device), and apps it has
 * authorised can then execute code as the shell user.</p>
 *
 * <p>The command is executed by {@link ShellService}, which Shizuku starts in its own privileged
 * process. Binding is asynchronous, so {@link #run(String)} waits for the connection — it must
 * therefore never be called on the main thread.</p>
 */
public final class ShizukuShell
{
	private static final String TAG = "BbsShizukuShell";

	/** Distinguishes this permission request from the app's runtime-permission requests. */
	public static final int PERMISSION_REQUEST_CODE = 4919;

	private static final long BIND_TIMEOUT_MS = 10_000;
	private static final long COMMAND_TIMEOUT_MS = 30_000;

	/** The state Shizuku is in, from this app's point of view. */
	public enum State
	{
		/** Shizuku is not installed, or its service is not running. */
		UNAVAILABLE,
		/** Shizuku is running but has not authorised this app. */
		PERMISSION_DENIED,
		/** Shizuku is running and this app may use it. */
		READY
	}

	private static final ShizukuShell INSTANCE = new ShizukuShell();

	private final Object lock = new Object();

	private volatile IShellService service = null;
	private CountDownLatch bindLatch = null;

	private final ServiceConnection connection = new ServiceConnection()
	{
		@Override
		public void onServiceConnected(ComponentName name, IBinder binder)
		{
			synchronized (lock)
			{
				service = (binder != null && binder.pingBinder())
						? IShellService.Stub.asInterface(binder)
						: null;

				Log.i(TAG, "Shizuku user service connected"
						+ (service != null ? " (uid " + safeGetUid() + ")" : " but the binder was dead"));

				if (bindLatch != null)
				{
					bindLatch.countDown();
				}
			}
		}

		@Override
		public void onServiceDisconnected(ComponentName name)
		{
			synchronized (lock)
			{
				Log.i(TAG, "Shizuku user service disconnected");
				service = null;
			}
		}
	};

	private final Shizuku.UserServiceArgs serviceArgs = new Shizuku.UserServiceArgs(
			new ComponentName(BuildConfig.APPLICATION_ID, ShellService.class.getName()))
			.daemon(false)
			.processNameSuffix("shell")
			.debuggable(BuildConfig.DEBUG)
			.version(BuildConfig.VERSION_CODE);

	private ShizukuShell()
	{
		// Drop the cached binder when Shizuku itself goes away, so the next call rebinds instead of
		// failing against a dead process.
		Shizuku.addBinderDeadListener(() ->
		{
			synchronized (lock)
			{
				Log.i(TAG, "Shizuku binder died");
				service = null;
			}
		});
	}

	public static ShizukuShell getInstance()
	{
		return INSTANCE;
	}

	/**
	 * @return whether Shizuku is installed and its service is currently running.
	 */
	public static boolean isShizukuRunning()
	{
		try
		{
			return Shizuku.pingBinder();
		}
		catch (Throwable t)
		{
			// Shizuku is not installed, or its API threw before it was initialised
			return false;
		}
	}

	/**
	 * @return whether this app has been authorised to use Shizuku.
	 */
	public static boolean hasPermission()
	{
		try
		{
			if (!Shizuku.pingBinder())
			{
				return false;
			}

			// Pre-v11 builds had no permission model of their own; being connected was enough.
			return Shizuku.isPreV11()
					|| Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED;
		}
		catch (Throwable t)
		{
			return false;
		}
	}

	/** @return where this app stands with Shizuku right now. */
	public static State getState()
	{
		if (!isShizukuRunning())
		{
			return State.UNAVAILABLE;
		}

		return hasPermission() ? State.READY : State.PERMISSION_DENIED;
	}

	/**
	 * Asks Shizuku to authorise this app. The answer arrives asynchronously; register a listener
	 * with {@code Shizuku.addRequestPermissionResultListener} to observe it.
	 */
	public static void requestPermission()
	{
		try
		{
			if (Shizuku.pingBinder() && !Shizuku.isPreV11())
			{
				Shizuku.requestPermission(PERMISSION_REQUEST_CODE);
			}
		}
		catch (Throwable t)
		{
			Log.w(TAG, "Could not request the Shizuku permission: " + t.getMessage());
		}
	}

	/**
	 * Runs a command as the shell user and returns its standard output, one entry per line.
	 *
	 * <p>Blocks while the user service is bound, so it must be called off the main thread. Returns
	 * an empty list — never null — when Shizuku is unavailable or the command fails, matching what
	 * the other shells in this app do so callers need no extra branch.</p>
	 */
	public List<String> run(String command)
	{
		if (getState() != State.READY)
		{
			return Collections.emptyList();
		}

		IShellService boundService = awaitService();
		if (boundService == null)
		{
			Log.w(TAG, "Could not bind the Shizuku user service");
			return Collections.emptyList();
		}

		try
		{
			List<String> output = boundService.exec(command, COMMAND_TIMEOUT_MS);
			return (output != null) ? output : Collections.<String>emptyList();
		}
		catch (Exception e)
		{
			Log.e(TAG, "Shizuku command failed: " + command, e);

			synchronized (lock)
			{
				// The remote process is likely gone; force a rebind next time.
				service = null;
			}

			return new ArrayList<>();
		}
	}

	/**
	 * @return the connected service, binding first if necessary, or null if it does not come up
	 *         within {@link #BIND_TIMEOUT_MS}.
	 */
	private IShellService awaitService()
	{
		CountDownLatch latch;

		synchronized (lock)
		{
			if (service != null)
			{
				return service;
			}

			if (bindLatch == null || bindLatch.getCount() == 0)
			{
				bindLatch = new CountDownLatch(1);

				try
				{
					Shizuku.bindUserService(serviceArgs, connection);
				}
				catch (Throwable t)
				{
					Log.e(TAG, "bindUserService failed: " + t.getMessage(), t);
					bindLatch.countDown();
					return null;
				}
			}

			latch = bindLatch;
		}

		try
		{
			if (!latch.await(BIND_TIMEOUT_MS, TimeUnit.MILLISECONDS))
			{
				Log.w(TAG, "Timed out waiting for the Shizuku user service");
			}
		}
		catch (InterruptedException e)
		{
			Thread.currentThread().interrupt();
			return null;
		}

		return service;
	}

	/**
	 * Grants the app the permissions the dumpsys-based collection needs.
	 *
	 * <p>DUMP, PACKAGE_USAGE_STATS and BATTERY_STATS are signature/privileged permissions with the
	 * {@code development} flag, which is what makes them grantable by {@code pm grant} from a
	 * sufficiently privileged shell — the same thing a user would otherwise do over adb by hand.</p>
	 *
	 * @return true if every grant reported no error
	 */
	public boolean grantStatsPermissions(Context context)
	{
		if (getState() != State.READY)
		{
			return false;
		}

		String packageName = context.getPackageName();
		String[] permissions = {
				"android.permission.DUMP",
				"android.permission.PACKAGE_USAGE_STATS",
				"android.permission.BATTERY_STATS",
		};

		boolean allSucceeded = true;

		for (String permission : permissions)
		{
			List<String> result = run("pm grant " + packageName + " " + permission + " 2>&1");

			// `pm grant` is silent on success and prints to stderr (merged in above) on failure.
			if (!result.isEmpty())
			{
				Log.w(TAG, "pm grant " + permission + " said: " + result);
				allSucceeded = false;
			}
		}

		return allSucceeded;
	}

	/**
	 * @return the uid the privileged process runs as (2000 for shell, 0 for root), or -1 if it is
	 *         not connected.
	 */
	public int getServiceUid()
	{
		return (service != null) ? safeGetUid() : -1;
	}

	private int safeGetUid()
	{
		try
		{
			IShellService current = service;
			return (current != null) ? current.getUid() : -1;
		}
		catch (Exception e)
		{
			return -1;
		}
	}
}
