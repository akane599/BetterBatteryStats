/*
 * Copyright (C) 2011-14 asksven
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

import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.util.Log;

import androidx.annotation.NonNull;
import androidx.preference.PreferenceManager;
import androidx.work.Data;
import androidx.work.ExistingWorkPolicy;
import androidx.work.OneTimeWorkRequest;
import androidx.work.WorkManager;
import androidx.work.Worker;
import androidx.work.WorkerParameters;

import com.asksven.android.common.utils.DateUtils;
import com.asksven.android.common.utils.SysUtils;
import com.asksven.betterbatterystats.data.Reference;
import com.asksven.betterbatterystats.data.ReferenceStore;
import com.asksven.betterbatterystats.data.StatsProvider;
import com.asksven.betterbatterystats.widgetproviders.AppWidget;

/**
 * Persists a battery-stats reference in the background.
 *
 * <p>This replaces the former family of {@code Write*ReferenceService} {@code IntentService}s. Those
 * were started with {@code Context.startService()} from broadcast receivers and alarms, which throws
 * {@code IllegalStateException} on Android 8+ whenever the app is not already in the foreground —
 * so, in practice, exactly when the references most needed writing. WorkManager is allowed to run
 * from the background, holds a wakelock for the duration of the work, and survives process death.</p>
 *
 * @author sven
 */
public class ReferenceWorker extends Worker
{
    private static final String TAG = "ReferenceWorker";

    /** Key of the {@link Kind} to persist, as work input data. */
    public static final String KEY_KIND = "kind";

    /** The kind of reference to write. */
    public enum Kind
    {
        BOOT,
        CURRENT,
        CUSTOM,
        SCREEN_OFF,
        SCREEN_ON,
        TIMER,
        UNPLUGGED
    }

    public ReferenceWorker(@NonNull Context context, @NonNull WorkerParameters params)
    {
        super(context, params);
    }

    /**
     * Enqueues the write of a reference.
     *
     * <p>Work is unique per {@link Kind} and replaces any pending request of the same kind: when the
     * screen is toggled twice in a row only the most recent reference is of interest.</p>
     */
    public static void enqueue(Context context, Kind kind)
    {
        if (android.os.Build.VERSION.SDK_INT >= 34) return;
        if (context == null || kind == null)
        {
            return;
        }

        Data input = new Data.Builder().putString(KEY_KIND, kind.name()).build();

        OneTimeWorkRequest request = new OneTimeWorkRequest.Builder(ReferenceWorker.class)
                .setInputData(input)
                .addTag(TAG)
                .build();

        WorkManager.getInstance(context.getApplicationContext())
                .enqueueUniqueWork(TAG + "." + kind.name(), ExistingWorkPolicy.REPLACE, request);
    }

    @NonNull
    @Override
    public Result doWork()
    {
        if (android.os.Build.VERSION.SDK_INT >= 34) return Result.success();
        String rawKind = getInputData().getString(KEY_KIND);
        Kind kind;
        try
        {
            kind = Kind.valueOf(rawKind);
        }
        catch (IllegalArgumentException | NullPointerException e)
        {
            Log.e(TAG, "Dropping work request with unknown reference kind: " + rawKind);
            return Result.failure();
        }

        Log.i(TAG, "Writing " + kind + " reference at " + DateUtils.now());

        try
        {
            switch (kind)
            {
                case BOOT:
                    StatsProvider.getInstance().setReferenceSinceBoot(0);
                    notifyReferenceUpdated(Reference.BOOT_REF_FILENAME);
                    break;

                case CURRENT:
                    StatsProvider.getInstance().setCurrentReference(0);
                    notifyReferenceUpdated(Reference.CURRENT_REF_FILENAME);
                    refreshWidgets();
                    break;

                case CUSTOM:
                    StatsProvider.getInstance().setCustomReference(0);
                    notifyReferenceUpdated(Reference.CUSTOM_REF_FILENAME);
                    refreshWidgets();
                    break;

                case SCREEN_OFF:
                    writeScreenOffReference();
                    break;

                case SCREEN_ON:
                    writeScreenOnReference();
                    break;

                case TIMER:
                    notifyReferenceUpdated(StatsProvider.getInstance().setTimedReference(0));
                    break;

                case UNPLUGGED:
                    writeUnpluggedReference();
                    break;
            }
        }
        catch (Exception e)
        {
            Log.e(TAG, "An error occurred writing the " + kind + " reference: " + e.getMessage(), e);
            return Result.failure();
        }

        return Result.success();
    }

    private void writeScreenOffReference()
    {
        StatsProvider.getInstance().setReferenceSinceScreenOff(0);
        notifyReferenceUpdated(Reference.SCREEN_OFF_REF_FILENAME);

        SharedPreferences sharedPrefs = PreferenceManager.getDefaultSharedPreferences(getApplicationContext());
        sharedPrefs.edit().putLong("screen_went_off_at", System.currentTimeMillis()).apply();

        refreshWidgets();
    }

    private void writeScreenOnReference()
    {
        StatsProvider.getInstance().setReferenceScreenOn(0);
        notifyReferenceUpdated(Reference.SCREEN_ON_REF_FILENAME);

        StatsProvider.getInstance().setCurrentReference(0);
        notifyReferenceUpdated(Reference.CURRENT_REF_FILENAME);

        WidgetUpdateWorker.refreshNow(getApplicationContext());
    }

    private void writeUnpluggedReference()
    {
        StatsProvider.getInstance().setReferenceSinceUnplugged(0);
        notifyReferenceUpdated(Reference.UNPLUGGED_REF_FILENAME);

        // If the battery was (near) full when unplugged, this doubles as the "since charged" ref.
        double level = readBatteryLevel();
        SharedPreferences sharedPrefs = PreferenceManager.getDefaultSharedPreferences(getApplicationContext());
        double threshold = sharedPrefs.getInt("battery_charged_minimum_threshold", 100) / 100.0;

        Log.i(TAG, "Battery level on unplug is " + level + " (threshold " + threshold + ")");

        if (level >= 0 && level >= threshold)
        {
            Log.i(TAG, "Level was above the charged threshold at unplug, serializing 'since charged'");
            StatsProvider.getInstance().setReferenceSinceCharged(0);
            notifyReferenceUpdated(Reference.CHARGED_REF_FILENAME);

            StatsProvider.getInstance().setCurrentReference(0);
            notifyReferenceUpdated(Reference.CURRENT_REF_FILENAME);
        }

        refreshWidgets();
    }

    /**
     * @return the battery level normalised to [0..1], or -1 when it could not be determined.
     */
    private double readBatteryLevel()
    {
        Intent batteryIntent = getApplicationContext()
                .registerReceiver(null, new IntentFilter(Intent.ACTION_BATTERY_CHANGED));

        if (batteryIntent == null)
        {
            return -1;
        }

        int rawLevel = batteryIntent.getIntExtra("level", -1);
        double scale = batteryIntent.getIntExtra("scale", -1);

        return (rawLevel >= 0 && scale > 0) ? rawLevel / scale : -1;
    }

    private void notifyReferenceUpdated(String refName)
    {
        if (refName == null)
        {
            return;
        }

        Context context = getApplicationContext();
        Intent i = new Intent(ReferenceStore.REF_UPDATED)
                .putExtra(Reference.EXTRA_REF_NAME, refName)
                .setPackage(SysUtils.getPackageName(context));
        context.sendBroadcast(i);
    }

    private void refreshWidgets()
    {
        Context context = getApplicationContext();
        Intent intentRefreshWidgets = new Intent(AppWidget.WIDGET_UPDATE)
                .setPackage(SysUtils.getPackageName(context));
        context.sendBroadcast(intentRefreshWidgets);
    }
}
