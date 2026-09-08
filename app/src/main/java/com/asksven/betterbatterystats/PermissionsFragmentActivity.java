/*
 * Copyright (C) 2012-2014 asksven
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

import android.app.Activity;

import java.util.Map;

import android.content.Context;
import android.os.Bundle;
import androidx.fragment.app.FragmentManager;
import androidx.fragment.app.ListFragment;
import androidx.cursoradapter.widget.SimpleCursorAdapter;
import android.util.Log;

import com.asksven.betterbatterystats.adapters.PermissionsAdapter;
import com.asksven.betterbatterystats.data.Permission;
import com.asksven.betterbatterystats.data.StatsProvider;
import com.asksven.betterbatterystats.util.BackgroundTask;

/**
 * Demonstration of the use of a CursorLoader to load and display contacts data
 * in a fragment.
 */
public class PermissionsFragmentActivity extends BaseActivity
{
	

	@Override
	protected void onCreate(Bundle savedInstanceState)
	{
		super.onCreate(savedInstanceState);
		// we need a layout to inflate the fragment into
	    
		FragmentManager fm = getSupportFragmentManager();

		// Create the list fragment and add it as our sole content.
		if (fm.findFragmentById(android.R.id.content) == null)
		{
			PermissionsListFragment list = new PermissionsListFragment();
			fm.beginTransaction().add(android.R.id.content, list).commit();
		}
	}

	public static class PermissionsListFragment extends ListFragment
	{

		/**
		 * The logging TAG
		 */
		private static final String TAG = "PermissionsListFragment";

		private PermissionsAdapter m_listViewAdapter;
		private Map<String, Permission> m_permDictionary;
		private String m_packageName;

		// This is the Adapter being used to display the list's data.
		SimpleCursorAdapter mAdapter;

		// If non-null, this is the current filter the user has provided.
		String mCurFilter;

		@Override
		public void onActivityCreated(Bundle savedInstanceState)
		{
			super.onActivityCreated(savedInstanceState);
			setHasOptionsMenu(true);
			
			Bundle b = getActivity().getIntent().getExtras();
			m_packageName = b.getString("package");

			if (m_permDictionary == null)
			{
				m_permDictionary = StatsProvider.getInstance().getPermissionMap(getActivity());
			}

			loadPermissions();

		}

	    /** 
	     * Add menu items
	     * 
	     * @see android.app.Activity#onCreateOptionsMenu(android.view.Menu)
	     */

		/**
		 * Reads the package's requested permissions off the main thread; the adapter is built on the
		 * main thread, where the Activity it binds to is safe to touch.
		 */
		private void loadPermissions()
		{
			final Activity host = getActivity();
			final String packageName = m_packageName;

			BackgroundTask.run(host,
					() -> StatsProvider.getInstance().getRequestedPermissionListForPackage(host, packageName),
					(permissions, error) ->
					{
						if (error != null || permissions == null)
						{
							Log.e(TAG, "Loading of the permission list failed");
							return;
						}

						m_listViewAdapter = new PermissionsAdapter(host, permissions, m_permDictionary);
						setListAdapter(m_listViewAdapter);
					});
		}
	}
}
