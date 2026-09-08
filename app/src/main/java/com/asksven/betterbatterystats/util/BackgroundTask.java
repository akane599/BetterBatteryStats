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
package com.asksven.betterbatterystats.util;

import android.app.Activity;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import androidx.annotation.MainThread;
import androidx.annotation.Nullable;
import androidx.annotation.WorkerThread;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Runs a piece of work off the main thread and delivers the result back to it.
 *
 * <p>Replaces the {@code AsyncTask} subclasses the screens used to declare. Beyond
 * {@code AsyncTask} being deprecated since API 30, each of those was a non-static inner class of an
 * activity or fragment, so an in-flight refresh held the whole screen alive — and, worse, delivered
 * its result to a destroyed one. Reading battery statistics is slow enough for a rotation or a back
 * press to land in the middle of it.</p>
 *
 * <p>Results are dropped when the host activity is gone by the time the work finishes, so callers
 * do not have to null-check their views.</p>
 */
public final class BackgroundTask
{
	private static final String TAG = "BackgroundTask";

	/** The work to perform off the main thread. */
	public interface Work<T>
	{
		@WorkerThread
		T run() throws Exception;
	}

	/** Called on the main thread once the work is done, unless the host is gone. */
	public interface Completion<T>
	{
		/**
		 * @param result the value produced, or null when {@code error} is set
		 * @param error  what the work threw, or null on success
		 */
		@MainThread
		void onComplete(@Nullable T result, @Nullable Exception error);
	}

	/**
	 * A small pool: the screens each run one refresh at a time, and the work is IO- and
	 * IPC-bound rather than CPU-bound.
	 */
	private static final ExecutorService EXECUTOR = Executors.newFixedThreadPool(2, new ThreadFactory()
	{
		private final AtomicInteger count = new AtomicInteger(1);

		@Override
		public Thread newThread(Runnable r)
		{
			Thread t = new Thread(r, "bbs-background-" + count.getAndIncrement());
			t.setPriority(Thread.NORM_PRIORITY - 1);
			return t;
		}
	});

	private static final Handler MAIN = new Handler(Looper.getMainLooper());

	private BackgroundTask()
	{
		// static helpers only
	}

	/**
	 * Runs {@code work} in the background and hands the outcome to {@code completion} on the main
	 * thread, unless {@code host} has finished or been destroyed in the meantime.
	 */
	public static <T> void run(final Activity host, final Work<T> work, final Completion<T> completion)
	{
		EXECUTOR.execute(() ->
		{
			T result = null;
			Exception error = null;

			try
			{
				result = work.run();
			}
			catch (Exception e)
			{
				Log.e(TAG, "Background work failed: " + e.getMessage(), e);
				error = e;
			}

			final T finalResult = result;
			final Exception finalError = error;

			MAIN.post(() ->
			{
				if (isGone(host))
				{
					return;
				}

				completion.onComplete(finalResult, finalError);
			});
		});
	}

	private static boolean isGone(Activity activity)
	{
		return activity == null || activity.isFinishing() || activity.isDestroyed();
	}
}
