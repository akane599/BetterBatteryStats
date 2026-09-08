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
package com.asksven.android.common.privateapiproxies;

import android.content.Context;
import android.os.Build;

import com.asksven.android.common.utils.SysUtils;

/**
 * Answers the one question the whole app depends on: can the detailed battery statistics be read on
 * this device, and if not, why not?
 *
 * <p>Historically the app read {@code com.android.internal.os.BatteryStatsImpl} out of a parcel
 * obtained from the {@code batterystats} system service, through reflection. Every reason that can
 * fail — hidden-API restrictions, missing privileged permissions, and, from Android 14, the class
 * simply not being in the app's classpath any more — produced the same empty list and the same
 * generic "no stats" message. Distinguishing them is the difference between a user granting one
 * permission and a user uninstalling the app.</p>
 */
public final class BatteryStatsAvailability
{
	/** Why the detailed statistics are or are not available. */
	public enum Status
	{
		/** Everything needed is in place. */
		AVAILABLE,

		/**
		 * {@code BatteryStatsImpl} is not reachable from an app process on this release.
		 *
		 * <p>In Android 14 the class moved from {@code com.android.internal.os} in
		 * {@code framework.jar} to {@code com.android.server.power.stats} in {@code services.jar},
		 * and {@code IBatteryStats} dropped {@code getStatistics()} and
		 * {@code getStatisticsStream()} in favour of {@code getBatteryUsageStats()}. Neither root
		 * nor any permission brings the old path back — the code is not in the process.</p>
		 */
		REMOVED_FROM_PLATFORM,

		/**
		 * Non-SDK interface restrictions are blocking the reflective calls. Rooted users can lift
		 * them with {@code settings put global hidden_api_policy 1}.
		 */
		HIDDEN_API_BLOCKED,

		/** BATTERY_STATS / DUMP / PACKAGE_USAGE_STATS have not been granted. */
		MISSING_PERMISSIONS,

		/** The path is theoretically open but the service did not return usable data. */
		UNAVAILABLE
	}

	/**
	 * The first release in which {@code BatteryStatsImpl} is no longer part of the app-visible
	 * framework. Verified against the AOSP {@code android14-release} .. {@code android16-release}
	 * branches: {@code core/java/com/android/internal/os/BatteryStatsImpl.java} exists up to and
	 * including {@code android13-release} and is gone from Android 14 on.
	 */
	public static final int FIRST_SDK_WITHOUT_BATTERY_STATS_IMPL = Build.VERSION_CODES.UPSIDE_DOWN_CAKE;

	private BatteryStatsAvailability()
	{
		// static helpers only
	}

	/**
	 * @return whether this release still ships {@code BatteryStatsImpl} where an app can reach it.
	 */
	public static boolean isPlatformSupported()
	{
		return Build.VERSION.SDK_INT < FIRST_SDK_WITHOUT_BATTERY_STATS_IMPL;
	}

	/**
	 * Diagnoses the current state. Cheap enough to call before each refresh; the only potentially
	 * slow part, resolving the implementation class, is cached by {@link BatteryStatsProxy}.
	 */
	public static Status getStatus(Context context)
	{
		if (!BatteryStatsProxy.isImplementationClassAvailable(context))
		{
			// On Android 14+ this is expected and permanent. On older releases the class is there,
			// so a failure to load it means the non-SDK API restrictions blocked the lookup.
			return isPlatformSupported() ? Status.HIDDEN_API_BLOCKED : Status.REMOVED_FROM_PLATFORM;
		}

		if (!SysUtils.hasPermissionToCallHiddenApis(context))
		{
			return Status.HIDDEN_API_BLOCKED;
		}

		if (!SysUtils.hasBatteryStatsPermission(context) || !SysUtils.hasDumpsysPermission(context))
		{
			return Status.MISSING_PERMISSIONS;
		}

		BatteryStatsProxy proxy = BatteryStatsProxy.getInstance(context);
		return (proxy != null && proxy.isInitialised()) ? Status.AVAILABLE : Status.UNAVAILABLE;
	}

	/**
	 * @return a message explaining {@code status} to the user, and what — if anything — they can do
	 *         about it.
	 */
	public static String describe(Context context, Status status)
	{
		int resId;

		switch (status)
		{
			case REMOVED_FROM_PLATFORM:
				resId = com.asksven.betterbatterystats.R.string.STATS_REMOVED_FROM_PLATFORM;
				break;
			case HIDDEN_API_BLOCKED:
				resId = com.asksven.betterbatterystats.R.string.STATS_HIDDEN_API_BLOCKED;
				break;
			case MISSING_PERMISSIONS:
				resId = com.asksven.betterbatterystats.R.string.NO_PERM_ERR;
				break;
			case UNAVAILABLE:
				resId = com.asksven.betterbatterystats.R.string.NO_STATS;
				break;
			case AVAILABLE:
			default:
				return "";
		}

		return context.getString(resId);
	}
}
