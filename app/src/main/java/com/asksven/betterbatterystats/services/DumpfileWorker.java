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
import android.text.TextUtils;
import android.util.Log;

import androidx.annotation.NonNull;
import androidx.work.Data;
import androidx.work.ExistingWorkPolicy;
import androidx.work.OneTimeWorkRequest;
import androidx.work.WorkManager;
import androidx.work.Worker;
import androidx.work.WorkerParameters;

import com.asksven.android.common.utils.DateUtils;
import com.asksven.betterbatterystats.data.Reading;
import com.asksven.betterbatterystats.data.Reference;
import com.asksven.betterbatterystats.data.ReferenceStore;
import com.asksven.betterbatterystats.data.StatsProvider;

/**
 * Writes a dumpfile for the range between two references.
 *
 * <p>Replaces {@code WriteDumpfileService}, which was an {@code IntentService} started from a
 * broadcast receiver — a background service start, and therefore an {@code IllegalStateException}
 * on Android 8 and later.</p>
 *
 * @author sven
 */
public class DumpfileWorker extends Worker
{
    private static final String TAG = "DumpfileWorker";

    public static final String STAT_TYPE_FROM = "StatTypeFrom";
    public static final String STAT_TYPE_TO = "StatTypeTo";

    public DumpfileWorker(@NonNull Context context, @NonNull WorkerParameters params)
    {
        super(context, params);
    }

    public static void enqueue(Context context, String refFrom, String refTo)
    {
        if (context == null)
        {
            return;
        }

        Data input = new Data.Builder()
                .putString(STAT_TYPE_FROM, refFrom)
                .putString(STAT_TYPE_TO, refTo)
                .build();

        OneTimeWorkRequest request = new OneTimeWorkRequest.Builder(DumpfileWorker.class)
                .setInputData(input)
                .addTag(TAG)
                .build();

        WorkManager.getInstance(context.getApplicationContext())
                .enqueueUniqueWork(TAG, ExistingWorkPolicy.APPEND_OR_REPLACE, request);
    }

    @NonNull
    @Override
    public Result doWork()
    {
        String refFrom = getInputData().getString(STAT_TYPE_FROM);
        String refTo = getInputData().getString(STAT_TYPE_TO);

        if (TextUtils.isEmpty(refTo))
        {
            refTo = Reference.CURRENT_REF_FILENAME;
        }

        if (TextUtils.isEmpty(refFrom))
        {
            Log.i(TAG, "No dumpfile written: no 'from' reference was given");
            return Result.failure();
        }

        Log.i(TAG, "Writing dumpfile from " + refFrom + " to " + refTo + " at " + DateUtils.now());

        Context context = getApplicationContext();

        try
        {
            // If the reading runs up to "current", that reference has to be refreshed first.
            // This used to compare the reference names with `==` and so never fired, leaving the
            // dumpfile to be generated against a stale "current" snapshot.
            if (Reference.CURRENT_REF_FILENAME.equals(refTo))
            {
                StatsProvider.getInstance().setCurrentReference(0);
            }

            Reference referenceFrom = ReferenceStore.getReferenceByName(refFrom, context);
            Reference referenceTo = ReferenceStore.getReferenceByName(refTo, context);

            if (referenceFrom == null || referenceTo == null)
            {
                Log.i(TAG, "No dumpfile written: " + refFrom + " and/or " + refTo + " could not be read");
                return Result.failure();
            }

            new Reading(context, referenceFrom, referenceTo).writeDumpfile(context, "");
        }
        catch (Exception e)
        {
            Log.e(TAG, "An error occurred writing the dumpfile: " + e.getMessage(), e);
            return Result.failure();
        }

        return Result.success();
    }
}
