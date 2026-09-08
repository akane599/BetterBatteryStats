/*
 * Copyright (C) 2011-2015 asksven
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *et
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.asksven.betterbatterystats;

/**
 * Shows alarms in a list
 * @author sven
 */

import java.util.ArrayList;

import android.content.SharedPreferences;
import android.os.Bundle;
import android.os.SystemClock;
import android.preference.PreferenceManager;
import com.google.android.material.snackbar.Snackbar;
import androidx.swiperefreshlayout.widget.SwipeRefreshLayout;
import androidx.appcompat.widget.Toolbar;
import android.util.Log;
import android.view.View;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.Spinner;
import android.widget.TextView;
import android.widget.Toast;

import com.asksven.android.common.privateapiproxies.BatteryInfoUnavailableException;
import com.asksven.android.common.privateapiproxies.BatteryStatsProxy;
import com.asksven.android.common.privateapiproxies.StatElement;
import com.asksven.android.common.utils.DateUtils;
import com.asksven.betterbatterystats.adapters.StatsAdapter;
import com.asksven.betterbatterystats.data.StatsProvider;
import com.asksven.betterbatterystats.util.BackgroundTask;

public class RawStatsActivity extends ActionBarListActivity implements AdapterView.OnItemSelectedListener
{
	/**
	 * The logging TAG
	 */
	private static final String TAG = "RawStatsActivity";
	
	/**
	 * The Stat to be displayed
	 */
	private int m_iStat = 0; 

    /**
	 * The ArrayAdpater for rendering the ListView
	 */
	private StatsAdapter m_listViewAdapter;

	private SwipeRefreshLayout swipeLayout = null;

	@Override
	protected void onCreate(Bundle savedInstanceState)
	{
		super.onCreate(savedInstanceState);
		
		setContentView(R.layout.raw_stats);
		
		Toolbar toolbar = (Toolbar) findViewById(R.id.toolbar);
		toolbar.setTitle(getString(R.string.label_raw_stats));

	    setSupportActionBar(toolbar);
	    getSupportActionBar().setDisplayHomeAsUpEnabled(true);
	    getSupportActionBar().setDisplayUseLogoEnabled(false);

		swipeLayout = (SwipeRefreshLayout) findViewById(R.id.swiperefresh);

		swipeLayout.setOnRefreshListener(new SwipeRefreshLayout.OnRefreshListener()
		{
			@Override
			public void onRefresh()
			{
				doRefresh();
			}
		});
		
		// Spinner for selecting the stat
		Spinner spinnerStat = (Spinner) findViewById(R.id.spinnerStat);
		
		ArrayAdapter spinnerStatAdapter = ArrayAdapter.createFromResource(
	            this, R.array.stats, R.layout.bbs_spinner_layout);
		spinnerStatAdapter.setDropDownViewResource(R.layout.bbs_spinner_dropdown_item);
	    
		spinnerStat.setAdapter(spinnerStatAdapter);
		// setSelection MUST be called after setAdapter
		spinnerStat.setSelection(m_iStat);
		spinnerStat.setOnItemSelectedListener(this);
		
		TextView tvSince = (TextView) findViewById(R.id.TextViewSince);

        long sinceMs = SystemClock.elapsedRealtime();

        if (sinceMs != -1)
        {
	        String sinceText = DateUtils.formatDuration(sinceMs);
	        
	        tvSince.setText(sinceText);
	    	Log.i(TAG, "Since " + sinceText);
        }
        else
        {
	        tvSince.setText("n/a ");
	    	Log.i(TAG, "Since: n/a ");
        	
        }

	}
	
	/* Request updates at startup */
	@Override
	protected void onResume()
	{
		super.onResume();
		loadStats();
	}

	private void doRefresh()
	{
		BatteryStatsProxy.getInstance(this).invalidate();
		loadStats();
		if (m_listViewAdapter != null)
		{
			m_listViewAdapter.notifyDataSetChanged();
		}
	}
	
	/**
	 * Take the change of selection from the spinners into account and refresh the ListView
	 * with the right data
	 */
	public void onItemSelected(AdapterView<?> parent, View v, int position, long id)
	{
		// this method is fired even if nothing has changed so we nee to find that out
		SharedPreferences sharedPrefs = PreferenceManager.getDefaultSharedPreferences(this);

		boolean bChanged = false;
		
		// id is in the order of the spinners, 0 is stat, 1 is stat_type
		if (parent == (Spinner) findViewById(R.id.spinnerStat))
		{
			int iNewStat = position;
			if ( m_iStat != iNewStat )
			{
				m_iStat = iNewStat;
				bChanged = true;
			}
			else
			{
				return;
			}
		}
		else
		{
    		Log.e(TAG, "RawStatsActivity.onItemSelected error. ID could not be resolved");
    		Toast.makeText(this, getString(R.string.info_unknown_state), Toast.LENGTH_SHORT).show();

		}

        if (bChanged)
        {
        	doRefresh();
        }
	}

	public void onNothingSelected(AdapterView<?> parent)
	{
		// do nothing
	}


	/**
	 * Reads the selected raw stat off the main thread and binds it to the list.
	 *
	 * <p>Only the read happens in the background: the adapter is constructed on the main thread,
	 * where the Activity it holds is safe to touch and cannot already have been destroyed.</p>
	 */
	private void loadStats()
	{
		swipeLayout.setRefreshing(true);

		final int statType = m_iStat;

		BackgroundTask.run(this,
				() ->
				{
					Log.i(TAG, "loadStats: refreshing display for raw stats");
					StatsProvider provider = StatsProvider.getInstance();

					// constants are related to arrays.xml string-array name="stats"
					switch (statType)
					{
						case 0:
							return provider.getCurrentOtherUsageStatList(true, false, false);
						case 1:
							return provider.getCurrentKernelWakelockStatList(false, 0, 0);
						case 2:
							return provider.getCurrentWakelockStatList(false, 0, 0);
						case 3:
							return provider.getCurrentAlarmsStatList(false);
						case 4:
							return provider.getCurrentNetworkUsageStatList(false);
						case 5:
							return provider.getCurrentCpuStateList(false);
						case 6:
							return provider.getCurrentProcessStatList(false, 0);
						case 7:
							return provider.getCurrentSensorStatList(false);
						default:
							return new ArrayList<StatElement>();
					}
				},
				(stats, error) ->
				{
					swipeLayout.setRefreshing(false);

					if (error != null)
					{
						int message = (error instanceof BatteryInfoUnavailableException)
								? R.string.info_service_connection_error
								: R.string.info_unknown_stat_error;

						Snackbar.make(findViewById(android.R.id.content), message, Snackbar.LENGTH_LONG).show();
						return;
					}

					m_listViewAdapter = new StatsAdapter(RawStatsActivity.this, stats, RawStatsActivity.this);
					m_listViewAdapter.setTotalTime(SystemClock.elapsedRealtime());
					setListAdapter(m_listViewAdapter);
				});
	}
}
