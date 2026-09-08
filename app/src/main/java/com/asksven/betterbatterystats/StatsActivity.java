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

/*
  @author sven

 */

import android.Manifest;
import android.app.AlertDialog;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.preference.PreferenceManager;
import com.google.android.material.snackbar.Snackbar;
import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.core.content.ContextCompat;
import androidx.core.content.FileProvider;
import androidx.swiperefreshlayout.widget.SwipeRefreshLayout;
import androidx.appcompat.widget.Toolbar;
import android.util.Log;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.Menu;
import android.view.MenuInflater;
import android.view.MenuItem;
import android.view.View;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.Spinner;
import android.widget.TextView;
import android.widget.Toast;

import com.asksven.android.common.CommonLogSettings;
import com.asksven.android.common.NonRootShell;
import com.asksven.android.common.RootShell;
import com.asksven.android.common.privateapiproxies.BatteryInfoUnavailableException;
import com.asksven.android.common.privateapiproxies.Notification;
import com.asksven.android.common.privateapiproxies.StatElement;
import com.asksven.android.common.utils.DataStorage;
import com.asksven.android.common.utils.DateUtils;
import com.asksven.android.common.utils.SysUtils;
import com.asksven.betterbatterystats.adapters.ReferencesAdapter;
import com.asksven.betterbatterystats.adapters.StatsAdapter;
import com.asksven.betterbatterystats.data.Reading;
import com.asksven.betterbatterystats.data.Reference;
import com.asksven.betterbatterystats.data.ReferenceStore;
import com.asksven.betterbatterystats.data.StatsProvider;
import com.asksven.betterbatterystats.features.FeatureFlags;
import com.asksven.betterbatterystats.util.BackgroundTask;
import com.asksven.betterbatterystats.handlers.OnBootHandler;
import com.asksven.betterbatterystats.services.EventWatcherService;
import com.asksven.betterbatterystats.services.ReferenceWorker;
import com.asksven.betterbatterystats.services.WidgetUpdateWorker;
import com.asksven.betterbatterystats.services.WriteTimeSeriesService;
import com.asksven.betterbatterystats.widgetproviders.AppWidget;

import java.io.File;
import java.util.ArrayList;
import java.util.List;

public class StatsActivity extends ActionBarListActivity 
		implements AdapterView.OnItemSelectedListener
{
	public static String STAT 				= "STAT";
	public static String STAT_TYPE_FROM		= "STAT_TYPE_FROM";
	public static String STAT_TYPE_TO		= "STAT_TYPE_TO";
	public static String FROM_NOTIFICATION 	= "FROM_NOTIFICATION";
	
	private static final int STATE_ONSCREEN = 0;
    private static final int STATE_OFFSCREEN = 1;
    private static final int STATE_RETURNING = 2;
    
    private final int mState = STATE_ONSCREEN;
	/**
	 * The logging TAG
	 */
	private static final String TAG = "StatsActivity";

	/**
	 * The logfile TAG
	 */
	private static final String LOGFILE = "BetterBatteryStats_Dump.log";

	/**
	 * The ArrayAdpater for rendering the ListView
	 */
	private StatsAdapter m_listViewAdapter;
	private ReferencesAdapter m_spinnerFromAdapter;
	private ReferencesAdapter m_spinnerToAdapter;
	/**
	 * The Type of Stat to be displayed (default is "Since charged")
	 */
//	private int m_iStatType = 0; 
	private String m_refFromName = "";
	private String m_refToName = Reference.CURRENT_REF_FILENAME;
	/**
	 * The Stat to be displayed (default is "Process")
	 */
	private int m_iStat = 0; 
	
	/**
	 * the selected sorting
	 */
	private int m_iSorting = 0;
	
	private BroadcastReceiver m_referenceSavedReceiver = null;

	private SwipeRefreshLayout swipeLayout = null;

	/**
	 * Android 13 turned notifications into a runtime permission. POST_NOTIFICATIONS was declared in
	 * the manifest but never requested, so on 13 and later the foreground service ran with its
	 * notification silently suppressed — leaving no sign the app was collecting anything.
	 */
	private final ActivityResultLauncher<String> m_notificationPermissionLauncher =
			registerForActivityResult(new ActivityResultContracts.RequestPermission(), granted ->
			{
				if (!granted)
				{
					Log.i(TAG, "POST_NOTIFICATIONS was denied; the collector runs without a notification");
				}
			});

	@Override
	protected void onCreate(Bundle savedInstanceState)
	{
		SharedPreferences sharedPrefs = PreferenceManager.getDefaultSharedPreferences(this);

		super.onCreate(savedInstanceState);

		requestNotificationPermissionIfNeeded();

		setContentView(R.layout.stats);
		
		Toolbar toolbar = (Toolbar) findViewById(R.id.toolbar);
		toolbar.setTitle(getString(R.string.app_name));

	    setSupportActionBar(toolbar);
	    getSupportActionBar().setDisplayUseLogoEnabled(false);
	   		
		// set debugging
		if (sharedPrefs.getBoolean("debug_logging", false))
		{
			LogSettings.DEBUG=true;
			CommonLogSettings.DEBUG=true;
		}
		else
		{
			LogSettings.DEBUG=false;
			CommonLogSettings.DEBUG=false;
		}

		swipeLayout = (SwipeRefreshLayout) findViewById(R.id.swiperefresh);

		swipeLayout.setOnRefreshListener(new SwipeRefreshLayout.OnRefreshListener()
        {
			@Override
			public void onRefresh()
            {
                doRefresh(true);
			}
		});

        ///////////////////////////////////////////////
		// check if we have a new release
		///////////////////////////////////////////////
		// if yes do some migration (if required) and show release notes
		String strLastRelease	= sharedPrefs.getString("last_release", "0");
		
		String strCurrentRelease = "";
		try
		{
			PackageInfo pinfo = getPackageManager().getPackageInfo(getPackageName(), 0);
			
	    	strCurrentRelease = Integer.toString(pinfo.versionCode);
		}
		catch (Exception e)
		{
			// nop strCurrentRelease is set to ""
		}
		
		// Grant permissions if they are missing and root is available
		if (!SysUtils.hasBatteryStatsPermission(this) || !SysUtils.hasDumpsysPermission(this) || !SysUtils.hasPackageUsageStatsPermission(this) || !SysUtils.hasPermissionToCallHiddenApis(this))
		{
		    if (( RootShell.getInstance().isRooted()))
            {

                // attempt to set perms using pm-comand
                Log.i(TAG, "attempting to grant perms with 'pm grant'");
				List<String> results = null;
                String pkg = this.getPackageName();
                RootShell.getInstance().run("pm grant " + pkg + " android.permission.BATTERY_STATS");
                RootShell.getInstance().run("pm grant " + pkg + " android.permission.DUMP");
                RootShell.getInstance().run("pm grant " + pkg + " android.permission.PACKAGE_USAGE_STATS");
				//RootShell.getInstance().run("pm grant " + pkg + " android.permission.INTERACT_ACROSS_USERS");
				RootShell.getInstance().run("settings put global hidden_api_policy 1");



				if ((SysUtils.hasBatteryStatsPermission(this) || SysUtils.hasDumpsysPermission(this) || SysUtils.hasPackageUsageStatsPermission(this) || SysUtils.hasPermissionToCallHiddenApis(this)))
                {
                    Log.i(TAG, "succeeded");
                } else
                {
                    Log.i(TAG, "failed");
                }
            }
		}

		// On Pie and upward we disable private API checks

//		if (Build.VERSION.SDK_INT >= 28)
//		{
//			NonRootShell.getInstance().run("settings put global hidden_api_policy_pre_p_apps 1");
//			NonRootShell.getInstance().run("settings put global hidden_api_policy_p_apps 1");
//			NonRootShell.getInstance().run("settings put global hidden_api_policy 1");
//		}

		// show install as system app screen if root available but perms missing
		if (!SystemAppActivity.hasAllPermissions(this))
//		if (!SysUtils.hasBatteryStatsPermission(this) || !SysUtils.hasDumpsysPermission(this) || !SysUtils.hasPackageUsageStatsPermission(this))
		{
			Intent intentSystemApp = new Intent(this, SystemAppActivity.class);
			intentSystemApp.setPackage(SysUtils.getPackageName(this));
			this.startActivity(intentSystemApp);
		}

        final SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(this);


		// first start
		if (strLastRelease.equals("0"))
		{

			boolean firstLaunch = !prefs.getBoolean("launched", false);


			if (firstLaunch)
			{
				// Save that the app has been launched
				SharedPreferences.Editor editor = prefs.edit();
				editor.putBoolean("launched", true);
				editor.apply();

				// persist the "since unplugged" reference and refresh the widgets
				ReferenceWorker.enqueue(this, ReferenceWorker.Kind.UNPLUGGED);
				WidgetUpdateWorker.refreshNow(this);

			}

	        SharedPreferences.Editor updater = sharedPrefs.edit();
	        updater.putString("last_release", strCurrentRelease);
	        updater.apply();
		}
		else if (!strLastRelease.equals(strCurrentRelease))
    	{
	        // save the current release to properties so that the dialog won't be shown till next version
	        SharedPreferences.Editor updater = sharedPrefs.edit();
	        updater.putString("last_release", strCurrentRelease);
	        updater.apply();
    	}

		///////////////////////////////////////////////
    	// retrieve default selections for spinners
    	// if none were passed
		///////////////////////////////////////////////
    	
    	m_iStat		= Integer.valueOf(sharedPrefs.getString("default_stat", "0"));
		m_refFromName	= sharedPrefs.getString("default_stat_type", Reference.UNPLUGGED_REF_FILENAME);

		if (!ReferenceStore.hasReferenceByName(m_refFromName, this))
		{

			m_refFromName = Reference.BOOT_REF_FILENAME;
    		Toast.makeText(this, getString(R.string.info_fallback_to_boot), Toast.LENGTH_SHORT).show();
		}
		
		if (LogSettings.DEBUG)
			Log.i(TAG, "onCreate state from preferences: refFrom=" + m_refFromName + " refTo=" + m_refToName);
		
		try
		{
			// recover any saved state
			if ( (savedInstanceState != null) && (!savedInstanceState.isEmpty()))
			{
				m_iStat 				= (Integer) savedInstanceState.getSerializable("stat");
				m_refFromName 			= (String) savedInstanceState.getSerializable("stattypeFrom");
				m_refToName 			= (String) savedInstanceState.getSerializable("stattypeTo");
				
				if (LogSettings.DEBUG)
					Log.i(TAG, "onCreate retrieved saved state: refFrom=" + m_refFromName + " refTo=" + m_refToName);
	 			
			}			
		}
		catch (Exception e)
		{
			m_iStat		= Integer.valueOf(sharedPrefs.getString("default_stat", "0"));
			m_refFromName	= sharedPrefs.getString("default_stat_type", Reference.UNPLUGGED_REF_FILENAME);

			Log.e(TAG, "Exception: " + e.getMessage());
			DataStorage.LogToFile(LOGFILE, "Exception in onCreate restoring Bundle");
			DataStorage.LogToFile(LOGFILE, e.getMessage());
			DataStorage.LogToFile(LOGFILE, e.getStackTrace());

			Toast.makeText(this, getString(R.string.info_state_recovery_error), Toast.LENGTH_SHORT).show();
		}

		// Handle the case the Activity was called from an intent with paramaters
		Bundle extras = getIntent().getExtras();
		if ((extras != null) && !extras.isEmpty())
		{
			// Override if some values were passed to the intent
			if (extras.containsKey(StatsActivity.STAT)) m_iStat = extras.getInt(StatsActivity.STAT);
			if (extras.containsKey(StatsActivity.STAT_TYPE_FROM)) m_refFromName = extras.getString(StatsActivity.STAT_TYPE_FROM);
			if (extras.containsKey(StatsActivity.STAT_TYPE_TO)) m_refToName = extras.getString(StatsActivity.STAT_TYPE_TO);
			
			if (LogSettings.DEBUG)
				Log.i(TAG, "onCreate state from extra: refFrom=" + m_refFromName + " refTo=" + m_refToName);
		}
        
		// Spinner for selecting the stat
		Spinner spinnerStat = (Spinner) findViewById(R.id.spinnerStat);
		
		ArrayAdapter spinnerStatAdapter = ArrayAdapter.createFromResource(
	            this, R.array.stats, R.layout.bbs_spinner_layout); //android.R.layout.simple_spinner_item);
		spinnerStatAdapter.setDropDownViewResource(R.layout.bbs_spinner_dropdown_item); // android.R.layout.simple_spinner_dropdown_item);
	    
		spinnerStat.setAdapter(spinnerStatAdapter);
		// setSelection MUST be called after setAdapter
		spinnerStat.setSelection(m_iStat);
		spinnerStat.setOnItemSelectedListener(this);
		
		///////////////////////////////////////////////
		// Spinner for Selecting the Stat type
		///////////////////////////////////////////////
		Spinner spinnerStatType = (Spinner) findViewById(R.id.spinnerStatType);
		m_spinnerFromAdapter = new ReferencesAdapter(this, R.layout.bbs_spinner_layout); //android.R.layout.simple_spinner_item);
		m_spinnerFromAdapter.setDropDownViewResource(R.layout.bbs_spinner_dropdown_item); //android.R.layout.simple_spinner_dropdown_item);
		spinnerStatType.setAdapter(m_spinnerFromAdapter);

		try
		{
			this.setListViewAdapter();
		}
		catch (BatteryInfoUnavailableException e)
		{
			Log.e(TAG, "Exception: "+Log.getStackTraceString(e));
			Snackbar
			  .make(findViewById(android.R.id.content), R.string.info_service_connection_error, Snackbar.LENGTH_LONG)
			  .show();
//			Toast.makeText(this,
//					getString(R.string.info_service_connection_error),
//					Toast.LENGTH_LONG).show();
			
		}
		catch (Exception e)
		{
			//Log.e(TAG, e.getMessage(), e.fillInStackTrace());
			Log.e(TAG, "Exception: "+Log.getStackTraceString(e));
			Toast.makeText(this,
					getString(R.string.info_unknown_stat_error),
					Toast.LENGTH_LONG).show();
		}
		// setSelection MUST be called after setAdapter
		spinnerStatType.setSelection(m_spinnerFromAdapter.getPosition(m_refFromName));
		spinnerStatType.setOnItemSelectedListener(this);
		
		///////////////////////////////////////////////
		// Spinner for Selecting the end sample
		///////////////////////////////////////////////
		Spinner spinnerStatSampleEnd = (Spinner) findViewById(R.id.spinnerStatSampleEnd);
		m_spinnerToAdapter = new ReferencesAdapter(this, R.layout.bbs_spinner_layout); //android.R.layout.simple_spinner_item);
		m_spinnerToAdapter.setDropDownViewResource(R.layout.bbs_spinner_dropdown_item); //android.R.layout.simple_spinner_dropdown_item);

    	spinnerStatSampleEnd.setVisibility(View.VISIBLE);
		spinnerStatSampleEnd.setAdapter(m_spinnerToAdapter);
		// setSelection must be called after setAdapter
		if ((m_refToName != null) && !m_refToName.equals("") )
		{
			int pos = m_spinnerToAdapter.getPosition(m_refToName);
			spinnerStatSampleEnd.setSelection(pos);
			
		}
		else
		{
			spinnerStatSampleEnd.setSelection(m_spinnerToAdapter.getPosition(Reference.CURRENT_REF_FILENAME));
		}

		spinnerStatSampleEnd.setOnItemSelectedListener(this);

		///////////////////////////////////////////////
		// sorting
		///////////////////////////////////////////////
		m_iSorting = 0;
		
    	// log reference store
    	ReferenceStore.logReferences(this);
    	
    	if (LogSettings.DEBUG)
    	{
    		Log.i(TAG, "onCreate final state: refFrom=" + m_refFromName + " refTo=" + m_refToName);
    		Log.i(TAG, "OnCreated end");
    	}
		
	}
    
	/* Request updates at startup */
	@Override
	protected void onResume()
	{
		super.onResume();
		Log.i(TAG, "OnResume called");


        Log.i(TAG, "OnResume called");
		
		Log.i(TAG, "onResume references state: refFrom=" + m_refFromName + " refTo=" + m_refToName);
		// register the broadcast receiver
		IntentFilter intentFilter = new IntentFilter(ReferenceStore.REF_UPDATED);
        m_referenceSavedReceiver = new BroadcastReceiver()
        {
            @Override
            public void onReceive(Context context, Intent intent)
            {
                //extract our message from intent
                String refName = intent.getStringExtra(Reference.EXTRA_REF_NAME);
                //log our message value
                
                if (LogSettings.DEBUG)
                	Log.i(TAG, "Received broadcast, reference was updated:" + refName);
                
                // reload the spinners to make sure all refs are in the right sequence when current gets refreshed
//                if (refName.equals(Reference.CURRENT_REF_FILENAME))
//                {
                	refreshSpinners();
//                }
            }
        };
        
		// registering our receiver.
		//
		// The three-argument registerReceiver(receiver, filter, int) overload was only added in
		// API 33, so guarding it with SDK_INT >= 26 meant a NoSuchMethodError on Android 8 through
		// 12. ContextCompat picks the right overload for the running platform. The broadcast is the
		// app's own reference-updated signal, so the receiver is not exported.
		ContextCompat.registerReceiver(this, m_referenceSavedReceiver, intentFilter,
				ContextCompat.RECEIVER_NOT_EXPORTED);

		// the service is always started as it handles the widget updates too
		SharedPreferences sharedPrefs = PreferenceManager.getDefaultSharedPreferences(this);
		
		// show/hide spinners
		boolean showSpinners = sharedPrefs.getBoolean("show_from_to_ref", true);
		if (!showSpinners)
		{
			LinearLayout spinnerLayout = (LinearLayout) this.findViewById(R.id.LayoutSpinners);
			if (spinnerLayout != null)
			{
				spinnerLayout.setVisibility(View.GONE);
			}
	        
		}


		EventWatcherService.start(this);

		// keep the widgets refreshed in the background
		WidgetUpdateWorker.schedulePeriodicRefresh(this);

		// make sure to create a valid "current" stat if none exists
		// or if prefs re set to auto refresh
		boolean bAutoRefresh = sharedPrefs.getBoolean("auto_refresh", true);

		if ((bAutoRefresh) || (!ReferenceStore.hasReferenceByName(Reference.CURRENT_REF_FILENAME, this)))
		{
			ReferenceWorker.enqueue(this, ReferenceWorker.Kind.CURRENT);
			doRefresh(true);

		}
		else
		{	
			refreshSpinners();
			doRefresh(false);
			
		}
		
		// check if active monitoring is on: if yes make sure the alarm is scheduled
		if (sharedPrefs.getBoolean("active_mon_enabled", false))
		{
			if (!StatsProvider.isActiveMonAlarmScheduled(this))
			{
				StatsProvider.scheduleActiveMonAlarm(this);
			}
		}
		//Log.i(TAG, "OnResume end");

		// we do some stuff here to handle settings about font size
		String fontSize = sharedPrefs.getString("medium_font_size", "16");
		int mediumFontSize = Integer.parseInt(fontSize);

		//we need to change "since" fontsize
		TextView tvSince = (TextView) findViewById(R.id.TextViewSince);
		tvSince.setTextSize(TypedValue.COMPLEX_UNIT_SP, mediumFontSize);

		
		
	}


	/* Remove the locationlistener updates when Activity is paused */
	@Override
	protected void onPause()
	{
		super.onPause();

		// unregister boradcast receiver for saved references
		this.unregisterReceiver(this.m_referenceSavedReceiver);
		
//		this.unregisterReceiver(m_batteryHandler);

	}

	@Override
	public void onDestroy() {
		super.onDestroy();

	}

	/**
	 * Save state, the application is going to get moved out of memory
	 * see http://stackoverflow.com/questions/151777/how-do-i-save-an-android-applications-state
	 */
	@Override
	public void onSaveInstanceState(Bundle savedInstanceState)
    {
    	super.onSaveInstanceState(savedInstanceState);
        
    	//Log.i(TAG, "onSaveInstanceState references: refFrom=" + m_refFromName + " refTo=" + m_refToName);
    	savedInstanceState.putSerializable("stattypeFrom", m_refFromName);
    	savedInstanceState.putSerializable("stattypeTo", m_refToName); 

    	savedInstanceState.putSerializable("stat", m_iStat);
		
    	//StatsProvider.getInstance(this).writeToBundle(savedInstanceState);
    }
        
	/** 
     * Add menu items
     * 
     * @see android.app.Activity#onCreateOptionsMenu(android.view.Menu)
     */
    public boolean onCreateOptionsMenu(Menu menu)
    {  
    	MenuInflater inflater = getMenuInflater();
        inflater.inflate(R.menu.mainmenu, menu);

        return true;
    }

    @Override
    public boolean onPrepareOptionsMenu(Menu menu)
    {
        if (!FeatureFlags.getInstance(StatsActivity.this).isTimeSeriesEnabled())
        {
            if (menu.findItem(R.id.test) != null)
            {
                menu.removeItem(R.id.test);
            }
        }
        super.onPrepareOptionsMenu(menu);
        return true;
    }
    /** 
     * Define menu action
     * 
     * @see android.app.Activity#onOptionsItemSelected(android.view.MenuItem)
     */
    public boolean onOptionsItemSelected(MenuItem item)
    {
        // Resource ids are not compile-time constants any more (non-final R class), so this
        // has to be an if/else chain rather than a switch.
        final int itemId = item.getItemId();

        if (itemId == R.id.preferences)
        {
            Intent intentPrefs = new Intent(this, PreferencesFragmentActivity.class);
            intentPrefs.setPackage(SysUtils.getPackageName(this));
            this.startActivity(intentPrefs);
        }
        else if (itemId == R.id.graph)
        {
            Intent intentGraph = new Intent(this, GraphActivity.class);
            intentGraph.setPackage(SysUtils.getPackageName(this));
            this.startActivity(intentGraph);
        }
        else if (itemId == R.id.rawstats)
        {
            Intent intentRaw = new Intent(this, RawStatsActivity.class);
            intentRaw.setPackage(SysUtils.getPackageName(this));
            this.startActivity(intentRaw);
        }
        else if (itemId == R.id.refresh)
        {
            doRefresh(true);
        }
        else if (itemId == R.id.custom_ref)
        {
            // Set custom reference: enqueue the write on the shared worker
            ReferenceWorker.enqueue(this, ReferenceWorker.Kind.CUSTOM);
        }
        else if (itemId == R.id.test)
        {
            // save time-series if selected
            WriteTimeSeriesService.scheduleJob(StatsActivity.this);
        }
        else if (itemId == R.id.about)
        {
            Intent intentAbout = new Intent(this, AboutActivity.class);
            intentAbout.setPackage(SysUtils.getPackageName(this));
            this.startActivity(intentAbout);
        }
        else if (itemId == R.id.help)
        {
            Intent i = new Intent(Intent.ACTION_VIEW);
            i.setData(Uri.parse("https://better.asksven.io/betterbatterystats/help/"));
            startActivity(i);
        }
        else if (itemId == R.id.share)
        {
            getShareDialog().show();
        }
        else
        {
            return super.onOptionsItemSelected(item);
        }

        return true;
    }
    

	/**
	 * Asks for POST_NOTIFICATIONS once on Android 13 and later. The permission is not essential —
	 * collection works without it — so a denial is not re-prompted here.
	 */
	private void requestNotificationPermissionIfNeeded()
	{
		if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU)
		{
			return;
		}

		if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
				!= PackageManager.PERMISSION_GRANTED)
		{
			m_notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS);
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
		if (parent == (Spinner) findViewById(R.id.spinnerStatType))
		{
			// detect if something changed
			String newStat = (String) ( (ReferencesAdapter) parent.getAdapter()).getItemName(position);
			if ((m_refFromName != null) && ( !m_refFromName.equals(newStat) ))
			{
				if (LogSettings.DEBUG)
					Log.i(TAG, "Spinner from changed from " + m_refFromName + " to " + newStat);
				
				m_refFromName = newStat;
				bChanged = true;
				// we need to update the second spinner
				m_spinnerToAdapter.filterToSpinner(newStat, this);
				m_spinnerToAdapter.notifyDataSetChanged();
				
				// select the right element
				Spinner spinnerStatSampleEnd = (Spinner) findViewById(R.id.spinnerStatSampleEnd);
				if (spinnerStatSampleEnd.isShown())
				{
					spinnerStatSampleEnd.setSelection(m_spinnerToAdapter.getPosition(m_refToName));
				}
				else
				{
					spinnerStatSampleEnd.setSelection(m_spinnerToAdapter.getPosition(Reference.CURRENT_REF_FILENAME));
				}

			}
			else
			{
				return;
			}

		}
		else if (parent == (Spinner) findViewById(R.id.spinnerStatSampleEnd))
		{
			String newStat = (String) ( (ReferencesAdapter) parent.getAdapter()).getItemName(position);
			if ((m_refFromName != null) && ( !m_refToName.equals(newStat) ))
			{
				if (LogSettings.DEBUG)
					Log.i(TAG, "Spinner to changed from " + m_refToName + " to " + newStat);
				
				m_refToName = newStat;
				bChanged = true;
			}
			else
			{
				return;
			}
			
		}
		else if (parent == (Spinner) findViewById(R.id.spinnerStat))
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
    		Log.e(TAG, "ProcessStatsActivity.onItemSelected error. ID could not be resolved");
    		Toast.makeText(this, getString(R.string.info_unknown_state), Toast.LENGTH_SHORT).show();

		}

    	Reference myReferenceFrom 	= ReferenceStore.getReferenceByName(m_refFromName, this);
		Reference myReferenceTo	 	= ReferenceStore.getReferenceByName(m_refToName, this);

        TextView tvSince = (TextView) findViewById(R.id.TextViewSince);

        long sinceMs = StatsProvider.getInstance().getSince(myReferenceFrom, myReferenceTo);

        if (sinceMs != -1)
        {
	        String sinceText =  DateUtils.formatDuration(sinceMs);
        	sinceText += " " + StatsProvider.getInstance().getBatteryLevelFromTo(myReferenceFrom, myReferenceTo, true);
	        
	        tvSince.setText(sinceText);
	        if (LogSettings.DEBUG) Log.i(TAG, "Since " + sinceText);
        }
        else
        {
	        tvSince.setText("n/a ");
	        if (LogSettings.DEBUG) Log.i(TAG, "Since: n/a ");
        	
        }
		// @todo fix this: this method is called twice
		//m_listViewAdapter.notifyDataSetChanged();
        if (bChanged)
        {
        	// as the source changed fetch the data
        	doRefresh(false);
        }
	}

	public void onNothingSelected(AdapterView<?> parent)
	{
//		Log.i(TAG, "OnNothingSelected called");
		// do nothing
	}

	private void refreshSpinners()
	{
		SharedPreferences sharedPrefs = PreferenceManager.getDefaultSharedPreferences(this);
		
		if ((m_refFromName == null) && (m_refToName == null))
		{
			Toast.makeText(this, getString(R.string.info_fallback_to_default), Toast.LENGTH_SHORT).show();
			m_refFromName	= sharedPrefs.getString("default_stat_type", Reference.UNPLUGGED_REF_FILENAME);
			m_refToName	= Reference.CURRENT_REF_FILENAME;
			

			if (!ReferenceStore.hasReferenceByName(m_refFromName, this))
			{
				m_refFromName = Reference.BOOT_REF_FILENAME;
			}
			Log.e(TAG, "refreshSpinners: reset null references: from='" + m_refFromName + "', to='" + m_refToName + "'");
					
		}
		// reload the spinners to make sure all refs are in the right sequence
		m_spinnerFromAdapter.refreshFromSpinner(this);
		m_spinnerToAdapter.filterToSpinner(m_refFromName, this);
		// after we reloaded the spinners we need to reset the selections
		Spinner spinnerStatTypeFrom = (Spinner) findViewById(R.id.spinnerStatType);
		Spinner spinnerStatTypeTo = (Spinner) findViewById(R.id.spinnerStatSampleEnd);
		if (LogSettings.DEBUG)
		{
			Log.i(TAG, "refreshSpinners: reset spinner selections: from='" + m_refFromName + "', to='" + m_refToName + "'");
			Log.i(TAG, "refreshSpinners Spinner values: SpinnerFrom=" + m_spinnerFromAdapter.getNames() + " SpinnerTo=" + m_spinnerToAdapter.getNames());
			Log.i(TAG, "refreshSpinners: request selections: from='" + m_spinnerFromAdapter.getPosition(m_refFromName) + "', to='" + m_spinnerToAdapter.getPosition(m_refToName) + "'");
		}
		
		// restore positions
		spinnerStatTypeFrom.setSelection(m_spinnerFromAdapter.getPosition(m_refFromName), true);
		if (spinnerStatTypeTo.isShown())
		{
			spinnerStatTypeTo.setSelection(m_spinnerToAdapter.getPosition(m_refToName), true);
		}
		else
		{
			spinnerStatTypeTo.setSelection(m_spinnerToAdapter.getPosition(Reference.CURRENT_REF_FILENAME), true);
		}
		
		if (LogSettings.DEBUG)
			Log.i(TAG, "refreshSpinners result positions: from='" + spinnerStatTypeFrom.getSelectedItemPosition() + "', to='" + spinnerStatTypeTo.getSelectedItemPosition() + "'");
		
		if ((spinnerStatTypeTo.isShown()) 
				&& ((spinnerStatTypeFrom.getSelectedItemPosition() == -1)||(spinnerStatTypeTo.getSelectedItemPosition() == -1)))
		{
			Toast.makeText(StatsActivity.this,
					getString(R.string.info_loading_refs_error),
					Toast.LENGTH_LONG).show();
		}
			
	}
	
    /**
	 * In order to refresh the ListView we need to re-create the Adapter
	 * (should be the case but notifyDataSetChanged doesn't work so
	 * we recreate and set a new one)
	 */
	private void setListViewAdapter() throws Exception
	{
		LinearLayout notificationPanel = (LinearLayout) findViewById(R.id.Notification);
		ListView listView = (ListView) findViewById(android.R.id.list);
		
		ArrayList<StatElement> myStats = StatsProvider.getInstance().getStatList(m_iStat, m_refFromName, m_iSorting, m_refToName);
		if ((myStats != null) && (!myStats.isEmpty()))
		{
			// check if notification
			if (myStats.get(0) instanceof Notification)
			{
				// Show Panel
				notificationPanel.setVisibility(View.VISIBLE);
				// Hide list
				listView.setVisibility(View.GONE);
				
				// set Text
				TextView tvNotification = (TextView) findViewById(R.id.TextViewNotification);
				tvNotification.setText(myStats.get(0).getName());
			}
			else
			{
				// hide Panel
				notificationPanel.setVisibility(View.GONE);
				// Show list
				listView.setVisibility(View.VISIBLE);
			}
		}
		
		// make sure we only instanciate when the reference does not exist
		if (m_listViewAdapter == null)
		{
			m_listViewAdapter = new StatsAdapter(this, myStats, StatsActivity.this);
    		Reference myReferenceFrom 	= ReferenceStore.getReferenceByName(m_refFromName, StatsActivity.this);
    		Reference myReferenceTo	 	= ReferenceStore.getReferenceByName(m_refToName, StatsActivity.this);

        	long sinceMs = StatsProvider.getInstance().getSince(myReferenceFrom, myReferenceTo);
        	m_listViewAdapter.setTotalTime(sinceMs);
		
			setListAdapter(m_listViewAdapter);
		}
	}

	private void doRefresh(boolean updateCurrent)
	{

		// do not hammer on the service
		// if (SysUtils.hasBatteryStatsPermission(this) ) BatteryStatsProxy.getInstance(this).invalidate();
		
		refreshSpinners();

		// debug only
//        SimpleDateFormat date =
//                new SimpleDateFormat("dd_MM_yyyy_hh_mm_ss");
//        String logDate = date.format(new Date());
//        Debug.startMethodTracing(
//                "doRefreshTrace-" + logDate);

		loadStats(updateCurrent);

		// Debug only
//		Debug.stopMethodTracing();
	}

	/**
	 * Loads the selected stat off the main thread and binds it to the list.
	 *
	 * <p>Only the read runs in the background. The adapter used to be built there too, holding the
	 * Activity from a non-static inner AsyncTask — so a rotation during a refresh leaked the screen
	 * and then delivered its result to the dead one.</p>
	 */
	private void loadStats(final boolean updateCurrent)
	{
		swipeLayout.setRefreshing(true);

		final int stat = m_iStat;
		final int sorting = m_iSorting;
		final String refFromName = m_refFromName;
		final String refToName = m_refToName;

		BackgroundTask.run(this,
				() ->
				{
					if (updateCurrent)
					{
						// make sure to create a valid "current" stat
						StatsProvider.getInstance().setCurrentReference(sorting);
					}

					if (LogSettings.DEBUG)
					{
						Log.i(TAG, "loadStats: refreshing display for stats " + refFromName + " to " + refToName);
					}

					return StatsProvider.getInstance().getStatList(stat, refFromName, sorting, refToName);
				},
				(stats, error) -> onStatsLoaded(stats, error));
	}

	private void onStatsLoaded(List<StatElement> stats, Exception error)
	{
		swipeLayout.setRefreshing(false);

		if (error != null)
		{
			int message = (error instanceof BatteryInfoUnavailableException)
					? R.string.info_service_connection_error
					: R.string.info_unknown_stat_error;

			Snackbar.make(findViewById(android.R.id.content), message, Snackbar.LENGTH_LONG).show();
		}

		Reference myReferenceFrom = ReferenceStore.getReferenceByName(m_refFromName, this);
		Reference myReferenceTo = ReferenceStore.getReferenceByName(m_refToName, this);

		SharedPreferences sharedPrefs = PreferenceManager.getDefaultSharedPreferences(this);

		if (FeatureFlags.getInstance(this).isTimeSeriesEnabled())
		{
			// schedule time series upload
			WriteTimeSeriesService.scheduleJob(this);
		}

		long sinceMs = StatsProvider.getInstance().getSince(myReferenceFrom, myReferenceTo);

		TextView tvSince = (TextView) findViewById(R.id.TextViewSince);
		if (sinceMs != -1)
		{
			String sinceText = DateUtils.formatDuration(sinceMs) + " "
					+ StatsProvider.getInstance().getBatteryLevelFromTo(myReferenceFrom, myReferenceTo,
							!sharedPrefs.getBoolean("show_bat_details", false));

			tvSince.setText(sinceText);
			if (LogSettings.DEBUG) Log.i(TAG, "Since " + sinceText);
		}
		else
		{
			tvSince.setText(R.string.label_not_available);
			if (LogSettings.DEBUG) Log.i(TAG, "Since: n/a ");
		}

		// A leading Notification is a message about the stats rather than a stat: it goes to the
		// banner. Whatever follows it is real data and is still listed — previously any notification
		// hid the list wholesale, which since Android 14 would have hidden the dumpsys-derived rows
		// along with the explanation of why the rest is missing.
		List<StatElement> rows = (stats != null) ? stats : new ArrayList<StatElement>();
		String noticeText = null;

		if (!rows.isEmpty() && rows.get(0) instanceof Notification)
		{
			noticeText = rows.get(0).getName();
			rows = rows.subList(1, rows.size());
		}

		LinearLayout notificationPanel = (LinearLayout) findViewById(R.id.Notification);
		ListView listView = (ListView) findViewById(android.R.id.list);

		if (noticeText != null)
		{
			notificationPanel.setVisibility(View.VISIBLE);
			((TextView) findViewById(R.id.TextViewNotification)).setText(noticeText);
		}
		else
		{
			notificationPanel.setVisibility(View.GONE);
		}

		listView.setVisibility(rows.isEmpty() ? View.GONE : View.VISIBLE);

		m_listViewAdapter = new StatsAdapter(this, new ArrayList<StatElement>(rows), this);
		m_listViewAdapter.setTotalTime(sinceMs);
		setListAdapter(m_listViewAdapter);
	}

	public AlertDialog getShareDialog()
	{
	
		final ArrayList<Integer> selectedSaveActions = new ArrayList<Integer>();

		
		AlertDialog.Builder dialog = new AlertDialog.Builder(StatsActivity.this);

/*
		dialog.setTitle("Alert");
		dialog.setMessage("Alert message to be shown");
		dialog.setButton(AlertDialog.BUTTON_NEUTRAL, "OK",
				new DialogInterface.OnClickListener() {
					public void onClick(DialogInterface dialog, int which) {
						dialog.dismiss();
					}
				});
		return dialog;
*/

		SharedPreferences sharedPrefs = PreferenceManager.getDefaultSharedPreferences(this);
		boolean saveDumpfile = sharedPrefs.getBoolean("save_dumpfile", true);
		boolean saveLogcat = sharedPrefs.getBoolean("save_logcat", false);
		boolean saveDmesg = sharedPrefs.getBoolean("save_dmesg", false);

		if (saveDumpfile)
		{
			selectedSaveActions.add(0);
		}
		if (saveLogcat)
		{
			selectedSaveActions.add(1);
		}
		if (saveDmesg)
		{
			selectedSaveActions.add(2);
		}

		//----
        LinearLayout layout = new LinearLayout(this);
        LinearLayout.LayoutParams parms = new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        layout.setOrientation(LinearLayout.VERTICAL);
        layout.setLayoutParams(parms);

        layout.setGravity(Gravity.CLIP_VERTICAL);
        layout.setPadding(2, 2, 2, 2);

		final TextView editTitle = new TextView(StatsActivity.this);
		editTitle.setText(R.string.share_dialog_edit_title);
		editTitle.setPadding(40, 40, 40, 40);
		editTitle.setGravity(Gravity.LEFT);
		editTitle.setTextSize(20);

		final EditText editDescription = new EditText(StatsActivity.this);

        LinearLayout.LayoutParams tv1Params = new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        tv1Params.bottomMargin = 5;
        layout.addView(editTitle,tv1Params);
        layout.addView(editDescription, new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT));
		
		//----

		// Set the dialog title
		dialog.setTitle(R.string.title_share_dialog)
				.setMultiChoiceItems(R.array.saveAsLabels, new boolean[]{saveDumpfile, saveLogcat, saveDmesg}, new DialogInterface.OnMultiChoiceClickListener()
				{
					@Override
					public void onClick(DialogInterface dialog, int which, boolean isChecked)
					{
						if (isChecked)
						{
							// If the user checked the item, add it to the
							// selected items
							selectedSaveActions.add(which);
						} else if (selectedSaveActions.contains(which))
						{
							// Else, if the item is already in the array,
							// remove it
							selectedSaveActions.remove(Integer.valueOf(which));
						}
					}
				})
				.setView(layout)
				// Set the action buttons
				.setPositiveButton(R.string.label_button_share, new DialogInterface.OnClickListener()
				{
					@Override
					public void onClick(DialogInterface dialog, int id)
					{
		            	ArrayList<Uri> attachements = new ArrayList<Uri>();

		            	Reference myReferenceFrom 	= ReferenceStore.getReferenceByName(m_refFromName, StatsActivity.this);
			    		Reference myReferenceTo	 	= ReferenceStore.getReferenceByName(m_refToName, StatsActivity.this);

			    		Reading reading = new Reading(StatsActivity.this, myReferenceFrom, myReferenceTo);

						// save as text is selected
						if (selectedSaveActions.contains(0))
						{
							Uri fileUri = reading.writeDumpfile(StatsActivity.this, editDescription.getText().toString());
							File file = new File(fileUri.getPath());
							Uri shareableUri = FileProvider.getUriForFile(
									StatsActivity.this,
									StatsActivity.this.getPackageName() + ".provider",
									file);
							attachements.add(shareableUri);
						}
						// save logcat if selected
						if (selectedSaveActions.contains(1))
						{
							Uri fileUri = StatsProvider.getInstance().writeLogcatToFile();
							File file = new File(fileUri.getPath());
							Uri shareableUri = FileProvider.getUriForFile(
									StatsActivity.this,
									StatsActivity.this.getPackageName() + ".provider",
									file);
							attachements.add(shareableUri);

						}
						// save dmesg if selected
						if (selectedSaveActions.contains(2))
						{
//							attachements.add();
							Uri fileUri = StatsProvider.getInstance().writeDmesgToFile();
							File file = new File(fileUri.getPath());
							Uri shareableUri = FileProvider.getUriForFile(
									StatsActivity.this,
									StatsActivity.this.getPackageName() + ".provider",
									file);
							attachements.add(shareableUri);

						}


						if (!attachements.isEmpty())
						{
							Intent shareIntent = new Intent();
							shareIntent.setAction(Intent.ACTION_SEND_MULTIPLE);

							shareIntent.putParcelableArrayListExtra(Intent.EXTRA_STREAM, attachements);
							shareIntent.setType("text/plain");
							shareIntent.setPackage(SysUtils.getPackageName(StatsActivity.this));
							startActivity(Intent.createChooser(shareIntent, "Share info to.."));
						}
					}
				})
				.setNeutralButton(R.string.label_button_save, new DialogInterface.OnClickListener()
				{
					@Override
					public void onClick(DialogInterface dialog, int id)
					{

						try
						{
			            	Reference myReferenceFrom 	= ReferenceStore.getReferenceByName(m_refFromName, StatsActivity.this);
				    		Reference myReferenceTo	 	= ReferenceStore.getReferenceByName(m_refToName, StatsActivity.this);
	
				    		Reading reading = new Reading(StatsActivity.this, myReferenceFrom, myReferenceTo);

							// save as text is selected
							if (selectedSaveActions.contains(0))
							{
								reading.writeDumpfile(StatsActivity.this, editDescription.getText().toString());
							}
							// save logcat if selected
							if (selectedSaveActions.contains(1))
							{
								StatsProvider.getInstance().writeLogcatToFile();
							}
							// save dmesg if selected
							if (selectedSaveActions.contains(2))
							{
								StatsProvider.getInstance().writeDmesgToFile();
							}

							Snackbar
							  .make(findViewById(android.R.id.content), getString(R.string.info_files_written) + ": " + StatsProvider.getWritableFilePath(), Snackbar.LENGTH_LONG)
							  .show();
						}
						catch (Exception e)
						{
							Log.e(TAG, "an error occured writing files: " + e.getMessage());
							Snackbar
							  .make(findViewById(android.R.id.content), R.string.info_files_write_error, Snackbar.LENGTH_LONG)
							  .show();
						}
						
					}
				}).setNegativeButton(R.string.label_button_cancel, new DialogInterface.OnClickListener()
					{
						@Override
						public void onClick(DialogInterface dialog, int id)
						{
							// do nothing
						}
					});

		return dialog.create();
	}
	
}
