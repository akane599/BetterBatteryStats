/*
 * Copyright (C) 2014-2015 asksven
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

import java.util.ArrayList;

import android.app.ProgressDialog;
import android.os.Bundle;
import com.google.android.material.snackbar.Snackbar;
import androidx.appcompat.widget.Toolbar;
import android.util.Log;

import com.asksven.android.common.privateapiproxies.BatteryStatsProxy;
import com.asksven.android.common.privateapiproxies.HistoryItem;
import com.asksven.betterbatterystats.adapters.GraphsAdapter;
import com.asksven.betterbatterystats.data.GraphSerie;
import com.asksven.betterbatterystats.data.GraphSeriesFactory;
import com.asksven.betterbatterystats.util.BackgroundTask;
import com.asksven.betterbatterystats.widgets.GraphableBarsPlot;

public class GraphActivity extends ActionBarListActivity
{

	private static final String TAG = "GraphActivity";
	//protected static ArrayList<HistoryItem> m_histList;
	GraphsAdapter m_adapter = null;
	//GraphSeriesFactory m_series = null;
	ProgressDialog m_progressDialog;

	/** Called when the activity is first created. */
	@Override
	public void onCreate(Bundle savedInstanceState)
	{
		super.onCreate(savedInstanceState);
		setContentView(R.layout.graphs);

		Toolbar toolbar = (Toolbar) findViewById(R.id.toolbar);
		toolbar.setTitle(getString(R.string.label_graphs));

		setSupportActionBar(toolbar);
		getSupportActionBar().setDisplayHomeAsUpEnabled(true);
		getSupportActionBar().setDisplayUseLogoEnabled(false);

		//m_histList = null; //getHistList();

		m_adapter = new GraphsAdapter(this, null);
		setListAdapter(m_adapter);


		loadSeries();

	}
	/**
	 * Get the Stat to be displayed
	 *
	 * @return a List of StatElements sorted (descending)
	 */
	protected ArrayList<HistoryItem> getHistList()
	{
		ArrayList<HistoryItem> myRet = new ArrayList<HistoryItem>();

		BatteryStatsProxy mStats = BatteryStatsProxy.getInstance(this);
		try
		{
			myRet = mStats.getHistory(this);
		}
		catch (Exception e)
		{
			Log.e(TAG, "An error occured while retrieving history. No result");
		}
		return myRet;
	}


	/**
	 * Builds the graph series off the main thread.
	 *
	 * <p>Reading the battery history is slow, and the old AsyncTask handed its result straight to
	 * {@code list.getValues(...)} — so a failed read (which is the normal outcome from Android 14
	 * on) arrived as null and crashed the screen rather than reporting the failure.</p>
	 */
	private void loadSeries()
	{
		if (m_progressDialog == null)
		{
			try
			{
				m_progressDialog = new ProgressDialog(this);
				m_progressDialog.setMessage(getString(R.string.message_computing));
				m_progressDialog.setIndeterminate(true);
				m_progressDialog.setCancelable(false);
				m_progressDialog.show();
			}
			catch (Exception e)
			{
				m_progressDialog = null;
			}
		}

		BackgroundTask.run(this,
				() ->
				{
					Log.i(TAG, "loadSeries: refreshing series");
					return new GraphSeriesFactory(getHistList());
				},
				(series, error) ->
				{
					dismissProgressDialog();

					if (error != null || series == null)
					{
						Snackbar.make(findViewById(android.R.id.content),
								R.string.info_unknown_stat_error, Snackbar.LENGTH_LONG).show();
						return;
					}

					m_adapter.setSeries(series);
					m_adapter.notifyDataSetChanged();

					GraphSerie batterySerie = new GraphSerie(
							getString(R.string.label_graph_battery),
							series.getValues(GraphSeriesFactory.SERIE_CHARGE));

					GraphableBarsPlot bars = (GraphableBarsPlot) findViewById(R.id.Battery);
					bars.setValues(batterySerie.getValues());
				});
	}

	private void dismissProgressDialog()
	{
		try
		{
			if (m_progressDialog != null)
			{
				m_progressDialog.dismiss();
			}
		}
		catch (Exception e)
		{
			// the dialog's window may already be gone; nothing to do
		}
		finally
		{
			m_progressDialog = null;
		}
	}

}
