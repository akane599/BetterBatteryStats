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

package com.asksven.betterbatterystats.handlers;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.util.Log;

import androidx.preference.PreferenceManager;

import com.asksven.betterbatterystats.data.ReferenceStore;
import com.asksven.betterbatterystats.data.StatsProvider;
import com.asksven.betterbatterystats.services.EventWatcherService;
import com.asksven.betterbatterystats.services.ReferenceWorker;
import com.asksven.betterbatterystats.services.WidgetUpdateWorker;

/**
 * General broadcast handler: handles event as registered on Manifest
 * @author sven
 *
 */
public class OnBootHandler extends BroadcastReceiver
{
	private static final String TAG = "OnBootHandler";

	/* (non-Javadoc)
	 * @see android.content.BroadcastReceiver#onReceive(android.content.Context, android.content.Intent)
	 */
	@Override
	public void onReceive(Context context, Intent intent)
	{
		SharedPreferences sharedPrefs = PreferenceManager.getDefaultSharedPreferences(context);

		Log.i(TAG, "Received Broadcast " + intent.getAction());

		// delete whatever references we have saved here
		ReferenceStore.deleteAllRefs(context);

		// persist the boot reference
		ReferenceWorker.enqueue(context, ReferenceWorker.Kind.BOOT);

		// start the event watcher, which is what turns screen on/off and unplug events into references
		EventWatcherService.start(context);

		// if active monitoring enabled schedule the next alarm
		if (sharedPrefs.getBoolean("active_mon_enabled", false))
		{
			// reschedule next timer
			StatsProvider.scheduleActiveMonAlarm(context);
		}

		// WorkManager restores periodic work across reboots by itself, but re-declaring it here is
		// cheap (the request is unique and KEEP) and covers upgrades from versions that scheduled
		// the widget refresh through the JobScheduler instead.
		WidgetUpdateWorker.schedulePeriodicRefresh(context);
	}
}
