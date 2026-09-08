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
package com.asksven.betterbatterystats;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.Bundle;
import android.view.View;
import android.view.ViewGroup;

import androidx.activity.EdgeToEdge;
import androidx.annotation.Nullable;
import androidx.annotation.StyleRes;
import androidx.appcompat.app.AppCompatActivity;
import androidx.appcompat.app.AppCompatDelegate;
import androidx.core.graphics.Insets;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowInsetsCompat;
import androidx.preference.PreferenceManager;

/**
 * Common behaviour for every screen of the app: theme selection and edge-to-edge insets.
 */
public class BaseActivity extends AppCompatActivity
{
	@Override
	protected void onCreate(@Nullable Bundle savedInstanceState)
	{
		// The theme has to be applied before the window is created; the previous implementation did
		// it in onResume(), by which time the decor view had already been inflated with the old one,
		// so the light/dark preference only ever took effect after an activity restart.
		setTheme(resolveThemeResId(this));

		// Android 15 (targetSdk 35) and later draw every app edge-to-edge whether it opts in or not.
		// Opting in explicitly keeps the behaviour identical across releases, and gives the compat
		// library a chance to keep the system bars legible on older ones.
		EdgeToEdge.enable(this);

		super.onCreate(savedInstanceState);
	}

	@Override
	public void setContentView(int layoutResID)
	{
		super.setContentView(layoutResID);
		applyWindowInsetsToContentRoot();
	}

	@Override
	public void setContentView(View view)
	{
		super.setContentView(view);
		applyWindowInsetsToContentRoot();
	}

	@Override
	public void setContentView(View view, ViewGroup.LayoutParams params)
	{
		super.setContentView(view, params);
		applyWindowInsetsToContentRoot();
	}

	/**
	 * Insets the activity's own layout so that it is not drawn under the status bar, the navigation
	 * bar or a display cutout.
	 *
	 * <p>Doing it here rather than per-layout keeps every screen consistent, and means a layout does
	 * not have to opt in to be laid out correctly on Android 15+, where the app no longer gets to
	 * decide whether it is drawn behind the system bars.</p>
	 */
	protected void applyWindowInsetsToContentRoot()
	{
		View contentRoot = findViewById(android.R.id.content);
		if (contentRoot instanceof ViewGroup && ((ViewGroup) contentRoot).getChildCount() > 0)
		{
			// Inset the activity's own root rather than the framework's content frame, so that a
			// background declared on the layout still paints the whole window.
			contentRoot = ((ViewGroup) contentRoot).getChildAt(0);
		}

		if (contentRoot == null)
		{
			return;
		}

		ViewCompat.setOnApplyWindowInsetsListener(contentRoot, (view, windowInsets) ->
		{
			Insets insets = windowInsets.getInsets(
					WindowInsetsCompat.Type.systemBars() | WindowInsetsCompat.Type.displayCutout());

			view.setPadding(insets.left, insets.top, insets.right, insets.bottom);

			return WindowInsetsCompat.CONSUMED;
		});

		ViewCompat.requestApplyInsets(contentRoot);
	}

	/**
	 * @return the theme matching the user's light/dark preference, after aligning the AppCompat
	 *         night mode with it.
	 */
	@StyleRes
	public static int resolveThemeResId(Context ctx)
	{
		SharedPreferences sharedPrefs = PreferenceManager.getDefaultSharedPreferences(ctx);
		String theme = sharedPrefs.getString("theme", "2");

		if ("0".equals(theme))
		{
			AppCompatDelegate.setDefaultNightMode(AppCompatDelegate.MODE_NIGHT_NO);
			return R.style.Theme_Bbs_Light;
		}

		if ("1".equals(theme))
		{
			AppCompatDelegate.setDefaultNightMode(AppCompatDelegate.MODE_NIGHT_YES);
			return R.style.Theme_Bbs_Dark;
		}

		AppCompatDelegate.setDefaultNightMode(AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM);
		return R.style.Theme_Bbs_Auto;
	}
}
