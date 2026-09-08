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

import android.appwidget.AppWidgetManager;
import android.content.ComponentName;
import android.content.Context;
import android.util.Log;

import androidx.annotation.NonNull;
import androidx.work.ExistingPeriodicWorkPolicy;
import androidx.work.ExistingWorkPolicy;
import androidx.work.OneTimeWorkRequest;
import androidx.work.PeriodicWorkRequest;
import androidx.work.WorkManager;
import androidx.work.Worker;
import androidx.work.WorkerParameters;

import com.asksven.betterbatterystats.widgetproviders.AppWidget;
import com.asksven.betterbatterystats.widgetproviders.TextAppWidget;

import java.util.concurrent.TimeUnit;

/**
 * Keeps the home-screen widgets up to date.
 *
 * <p>This replaces {@code AppWidgetJobService}, which re-scheduled itself through the
 * {@code JobScheduler} every five minutes. Android 15 and 16 tightened the JobScheduler quotas
 * considerably and actively deprioritise self-rescheduling chains like that one, so the refresh
 * became unreliable — and burnt battery in an app whose whole point is saving it. A single periodic
 * work request expresses the same intent in a way the platform schedules predictably, and it is
 * restored across reboots by WorkManager itself, so no {@code BOOT_COMPLETED} plumbing is needed.</p>
 */
public class WidgetUpdateWorker extends Worker
{
    private static final String TAG = "WidgetUpdateWorker";

    private static final String PERIODIC_WORK_NAME = "bbs.widget.periodic";
    private static final String ONESHOT_WORK_NAME = "bbs.widget.oneshot";

    /**
     * WorkManager clamps periodic work to a 15 minute minimum. The widgets show cumulative
     * durations, so a coarser refresh is not a loss of information — just of immediacy — and tapping
     * the refresh icon on the widget still updates it on the spot.
     */
    private static final long REFRESH_INTERVAL_MINUTES = 15;

    public WidgetUpdateWorker(@NonNull Context context, @NonNull WorkerParameters params)
    {
        super(context, params);
    }

    /** Installs (or keeps) the periodic widget refresh. */
    public static void schedulePeriodicRefresh(Context context)
    {
        if (android.os.Build.VERSION.SDK_INT >= 34) return;
        if (context == null)
        {
            return;
        }

        PeriodicWorkRequest request = new PeriodicWorkRequest.Builder(
                WidgetUpdateWorker.class, REFRESH_INTERVAL_MINUTES, TimeUnit.MINUTES)
                .addTag(TAG)
                .build();

        WorkManager.getInstance(context.getApplicationContext()).enqueueUniquePeriodicWork(
                PERIODIC_WORK_NAME, ExistingPeriodicWorkPolicy.KEEP, request);
    }

    /** Stops the periodic widget refresh (used when the last widget is removed). */
    public static void cancelPeriodicRefresh(Context context)
    {
        if (context == null)
        {
            return;
        }

        WorkManager.getInstance(context.getApplicationContext()).cancelUniqueWork(PERIODIC_WORK_NAME);
    }

    /** Refreshes the widgets as soon as the system allows. */
    public static void refreshNow(Context context)
    {
        if (android.os.Build.VERSION.SDK_INT >= 34) return;
        if (context == null)
        {
            return;
        }

        OneTimeWorkRequest request = new OneTimeWorkRequest.Builder(WidgetUpdateWorker.class)
                .addTag(TAG)
                .build();

        WorkManager.getInstance(context.getApplicationContext()).enqueueUniqueWork(
                ONESHOT_WORK_NAME, ExistingWorkPolicy.REPLACE, request);
    }

    @NonNull
    @Override
    public Result doWork()
    {
        if (android.os.Build.VERSION.SDK_INT >= 34) return Result.success();
        Context context = getApplicationContext();
        AppWidgetManager manager = AppWidgetManager.getInstance(context);

        try
        {
            int[] widgetIds = manager.getAppWidgetIds(new ComponentName(context, AppWidget.class));
            if (widgetIds.length > 0)
            {
                Log.i(TAG, "Updating " + widgetIds.length + " responsive widget(s)");
                UpdateWidgetService.updateWidgets(context, widgetIds);
            }

            int[] textWidgetIds = manager.getAppWidgetIds(new ComponentName(context, TextAppWidget.class));
            if (textWidgetIds.length > 0)
            {
                Log.i(TAG, "Updating " + textWidgetIds.length + " text widget(s)");
                UpdateTextWidgetService.updateWidgets(context, textWidgetIds);
            }
        }
        catch (Exception e)
        {
            Log.e(TAG, "An error occurred updating the widgets: " + e.getMessage(), e);
            return Result.retry();
        }

        return Result.success();
    }
}
