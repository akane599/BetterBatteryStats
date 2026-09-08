/*
 * Copyright (C) 2011 asksven
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
import androidx.preference.PreferenceManager;
import android.util.Log;

import com.asksven.betterbatterystats.services.ReferenceWorker;
import com.asksven.betterbatterystats.services.WidgetUpdateWorker;

/**
 * @author sven
 *
 */
public class ScreenEventHandler extends BroadcastReceiver
{

	private static final String TAG = "ScreenEventHandler";

    @Override
    public void onReceive(Context context, Intent intent)
    {
		SharedPreferences sharedPrefs = PreferenceManager.getDefaultSharedPreferences(context);

        if (intent.getAction().equals(Intent.ACTION_SCREEN_OFF))
		{
			Log.i(TAG, "Received Broadcast ACTION_SCREEN_OFF");
			ReferenceWorker.enqueue(context, ReferenceWorker.Kind.SCREEN_OFF);

		}

        if (intent.getAction().equals(Intent.ACTION_SCREEN_ON))
		{
			Log.i(TAG, "Received Broadcast ACTION_SCREEN_ON");
			boolean bRunOnUnlock = sharedPrefs.getBoolean("watchdog_on_unlock", false);

			if (!bRunOnUnlock)
			{
				ReferenceWorker.enqueue(context, ReferenceWorker.Kind.SCREEN_ON);
			}

			WidgetUpdateWorker.refreshNow(context);
			
		}
        
        if (intent.getAction().equals(Intent.ACTION_USER_PRESENT))
		{
			Log.i(TAG, "Received Broadcast ACTION_USER_PRESENT");
			boolean bRunOnUnlock = sharedPrefs.getBoolean("watchdog_on_unlock", false);

			if (bRunOnUnlock)
			{
				ReferenceWorker.enqueue(context, ReferenceWorker.Kind.SCREEN_ON);
			}
			

		}

    }
    
}