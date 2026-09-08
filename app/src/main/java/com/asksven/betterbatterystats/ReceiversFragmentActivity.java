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

import android.app.ProgressDialog;
import android.content.Context;
import android.os.Bundle;
import androidx.fragment.app.FragmentManager;
import androidx.fragment.app.ListFragment;
import android.util.Log;

import com.asksven.betterbatterystats.adapters.ServicesAdapter;
import com.asksven.betterbatterystats.data.StatsProvider;
import com.asksven.betterbatterystats.util.BackgroundTask;

/**
 * Demonstration of the use of a CursorLoader to load and display contacts data
 * in a fragment.
 */
public class ReceiversFragmentActivity extends BaseActivity
{
	

	@Override
	protected void onCreate(Bundle savedInstanceState)
	{
		super.onCreate(savedInstanceState);

		FragmentManager fm = getSupportFragmentManager();

		// Create the list fragment and add it as our sole content.
		if (fm.findFragmentById(android.R.id.content) == null)
		{
			ReceiversListFragment list = new ReceiversListFragment();
			fm.beginTransaction().add(android.R.id.content, list).commit();
		}
	}

	public static class ReceiversListFragment extends ListFragment
	{

		/**
		 * The logging TAG
		 */
		private static final String TAG = "ReceiversListFragment";

		private ServicesAdapter m_listViewAdapter;
		private String m_packageName;
		ProgressDialog m_progressDialog;
		



		@Override
		public void onActivityCreated(Bundle savedInstanceState)
		{
			super.onActivityCreated(savedInstanceState);

			Bundle b = getActivity().getIntent().getExtras();
			m_packageName = b.getString("package");

			loadReceivers();

		}


		/**
		 * Reads the package's receivers off the main thread; the adapter is built on the main
		 * thread, where the Activity it binds to is safe to touch.
		 */
		private void loadReceivers()
		{
			final Activity host = getActivity();
			final String packageName = m_packageName;

			if (m_progressDialog == null)
			{
				m_progressDialog = new ProgressDialog(host);
				m_progressDialog.setMessage(getString(R.string.message_computing));
				m_progressDialog.setIndeterminate(true);
				m_progressDialog.setCancelable(false);
				m_progressDialog.show();
			}

			BackgroundTask.run(host,
					() -> StatsProvider.getInstance().getReceiverListForPackage(host, packageName),
					(receivers, error) ->
					{
						if (m_progressDialog != null)
						{
							m_progressDialog.dismiss();
							m_progressDialog = null;
						}

						if (error != null || receivers == null)
						{
							Log.e(TAG, "Loading of the receiver list failed");
							return;
						}

						m_listViewAdapter = new ServicesAdapter(host, receivers);
						setListAdapter(m_listViewAdapter);
					});
		}		
	}
}
