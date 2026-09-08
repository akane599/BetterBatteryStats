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
package com.asksven.betterbatterystats.services;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.ServiceInfo;
import android.os.Binder;
import android.os.Build;
import android.os.IBinder;
import android.util.Log;

import androidx.core.app.NotificationCompat;
import androidx.core.content.ContextCompat;

import com.asksven.betterbatterystats.R;
import com.asksven.betterbatterystats.StatsActivity;
import com.asksven.betterbatterystats.handlers.OnUnplugHandler;
import com.asksven.betterbatterystats.handlers.ScreenEventHandler;

/**
 * Keeps the screen on/off and power-disconnected receivers alive.
 *
 * <p>Those broadcasts cannot be declared in the manifest — the platform only delivers them to
 * receivers registered at runtime — so a foreground service is what holds the registration. From
 * Android 14 on, a foreground service must declare a type both in the manifest and in the
 * {@code startForeground()} call, or the platform throws
 * {@code MissingForegroundServiceTypeException}.</p>
 *
 * @author sven
 */
public class EventWatcherService extends Service
{
	static final String TAG = "EventWatcherService";
	public static final int FOREGROUND_ID = 1003;

	static final String CHANNEL_ID = "bbs_channel_event_processing";

	/**
	 * Whether the service is up. {@code ActivityManager.getRunningServices()} has only returned the
	 * caller's own services since Android O and is deprecated, and the old implementation of this
	 * check overwrote its result with {@code false} before returning, so it always claimed the
	 * service was down.
	 */
	private static volatile boolean sRunning = false;

	private BroadcastReceiver mScreenEventReceiver = null;
	private BroadcastReceiver mUnplugReceiver = null;

	private final IBinder mBinder = new LocalBinder();

	/**
	 * Class for clients to access. Because we know this service always runs in the same process as
	 * its clients, we don't need to deal with IPC.
	 */
	public class LocalBinder extends Binder
	{
		public EventWatcherService getService()
		{
			return EventWatcherService.this;
		}
	}

	/**
	 * Starts the service as a foreground service, if it is not already running.
	 *
	 * <p>Since Android 12 an app in the background is not allowed to start a foreground service;
	 * the platform answers with {@code ForegroundServiceStartNotAllowedException}. That is a normal
	 * outcome here (the boot receiver races the "app is in the background" rule on some OEM builds),
	 * not a crash: the service is started again the next time the user opens the app.</p>
	 */
	public static void start(Context context)
	{
		if (context == null || sRunning)
		{
			return;
		}

		try
		{
			ContextCompat.startForegroundService(context, new Intent(context, EventWatcherService.class));
		}
		catch (Exception e)
		{
			// ForegroundServiceStartNotAllowedException on API 31+, IllegalStateException before it
			Log.w(TAG, "Could not start the event watcher from the background: " + e.getMessage());
		}
	}

	public static boolean isServiceRunning()
	{
		return sRunning;
	}

	@Override
	public IBinder onBind(Intent intent)
	{
		return mBinder;
	}

	@Override
	public void onCreate()
	{
		super.onCreate();

		createNotificationChannel();

		// Going foreground has to happen promptly after the start request, before any other work,
		// or the platform kills the service with a ForegroundServiceDidNotStartInTimeException.
		startInForeground();

		// register the receiver that handles screen on and screen off logic. These broadcasts are
		// only ever delivered to receivers registered at runtime.
		IntentFilter screenFilter = new IntentFilter(Intent.ACTION_USER_PRESENT);
		screenFilter.addAction(Intent.ACTION_SCREEN_ON);
		screenFilter.addAction(Intent.ACTION_SCREEN_OFF);
		mScreenEventReceiver = new ScreenEventHandler();

		IntentFilter unplugFilter = new IntentFilter(Intent.ACTION_POWER_DISCONNECTED);
		mUnplugReceiver = new OnUnplugHandler();

		// From Android 14 (targetSdk 34) a runtime-registered receiver must state whether it is
		// exported. These only ever listen to system broadcasts, so they are not exported.
		ContextCompat.registerReceiver(this, mScreenEventReceiver, screenFilter,
				ContextCompat.RECEIVER_NOT_EXPORTED);
		ContextCompat.registerReceiver(this, mUnplugReceiver, unplugFilter,
				ContextCompat.RECEIVER_NOT_EXPORTED);

		sRunning = true;
	}

	private void createNotificationChannel()
	{
		if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O)
		{
			return;
		}

		NotificationManager notificationManager = getSystemService(NotificationManager.class);
		if (notificationManager == null)
		{
			return;
		}

		NotificationChannel channel = new NotificationChannel(
				CHANNEL_ID,
				getString(R.string.event_processing_channel_name),
				NotificationManager.IMPORTANCE_LOW);

		channel.setDescription(getString(R.string.event_processing_channel_description));
		channel.enableLights(false);
		channel.enableVibration(false);

		notificationManager.createNotificationChannel(channel);
	}

	private void startInForeground()
	{
		Intent notificationIntent = new Intent(this, StatsActivity.class);

		// PendingIntents handed to the system must declare their mutability from API 31 on.
		PendingIntent pendingIntent = PendingIntent.getActivity(
				this, 0, notificationIntent, PendingIntent.FLAG_IMMUTABLE);

		Notification notification = new NotificationCompat.Builder(this, CHANNEL_ID)
				.setSmallIcon(R.drawable.ic_stat_notification)
				.setContentTitle(getString(R.string.plugin_name))
				.setContentText(getString(R.string.foreground_service_text))
				.setPriority(NotificationCompat.PRIORITY_MIN)
				.setOngoing(true)
				.setContentIntent(pendingIntent)
				.build();

		// The type is only required — and `specialUse` only exists — from Android 14 on. Passing it
		// on an older release would fail, since the platform validates the type against what the
		// manifest declares and would not recognise `specialUse` there either.
		if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE)
		{
			startForeground(FOREGROUND_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE);
		}
		else
		{
			startForeground(FOREGROUND_ID, notification);
		}
	}

	@Override
	public void onDestroy()
	{
		sRunning = false;

		if (mScreenEventReceiver != null)
		{
			unregisterReceiver(mScreenEventReceiver);
			mScreenEventReceiver = null;
		}

		if (mUnplugReceiver != null)
		{
			unregisterReceiver(mUnplugReceiver);
			mUnplugReceiver = null;
		}

		super.onDestroy();
	}

	/**
	 * Called when service is started
	 */
	@Override
	public int onStartCommand(Intent intent, int flags, int startId)
	{
		Log.i(TAG, "Received start id " + startId + ": " + intent);

		// We want this service to continue running until it is explicitly stopped, so return sticky.
		return Service.START_STICKY;
	}
}
