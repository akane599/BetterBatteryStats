package com.asksven.betterbatterystats.modern;

import android.net.Uri;
import androidx.lifecycle.Observer;
import androidx.lifecycle.ViewModelProvider;
import androidx.test.core.app.ActivityScenario;
import androidx.test.core.app.ApplicationProvider;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import org.junit.Test;
import org.junit.runner.RunWith;
import java.io.File;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.function.Predicate;
import static androidx.test.espresso.Espresso.onView;
import static androidx.test.espresso.assertion.ViewAssertions.matches;
import static androidx.test.espresso.matcher.ViewMatchers.*;
import static org.junit.Assert.*;
import com.asksven.betterbatterystats.R;

/** Runs without root or Shizuku: validates the permissionless import and activity lifecycle. */
@RunWith(AndroidJUnit4.class)
public class ModernStatsActivityTest {
    private static final String REPORT = "9,0,i,uid,10011,com.example.watch\n"
            + "9,0,l,bt,3,3600000,600000,4000000,800000,1700000000000,3000000,180000,4000,3900000,4100000,20000\n"
            + "9,0,l,m,600000,2000\n"
            + "9,10011,l,wl,wear_transport,0,f,0,0,0,0,120000,p,12,0,30000,180000,18000,bp,3,0,6000,24000,0,w,0,0,0,0\n";

    private void awaitState(ActivityScenario<ModernStatsActivity> scenario,
                            Predicate<ModernStatsViewModel.State> condition) throws Exception {
        CountDownLatch ready = new CountDownLatch(1);
        scenario.onActivity(activity -> {
            ModernStatsViewModel model = new ViewModelProvider(activity).get(ModernStatsViewModel.class);
            model.state().observe(activity, new Observer<ModernStatsViewModel.State>() {
                @Override public void onChanged(ModernStatsViewModel.State state) {
                    if (!state.loading && condition.test(state)) {
                        model.state().removeObserver(this);
                        ready.countDown();
                    }
                }
            });
        });
        assertTrue("Timed out waiting for snapshot", ready.await(45, TimeUnit.SECONDS));
    }

    @Test public void importsAndRetainsSnapshotAcrossRecreation() throws Exception {
        File input = new File(ApplicationProvider.getApplicationContext().getCacheDir(), "fixture.txt");
        try (FileOutputStream output = new FileOutputStream(input)) { output.write(REPORT.getBytes(StandardCharsets.UTF_8)); }
        try (ActivityScenario<ModernStatsActivity> scenario = ActivityScenario.launch(ModernStatsActivity.class)) {
            awaitState(scenario, state -> true);
            onView(withId(R.id.modern_refresh)).check(matches(isDisplayed()));
            scenario.onActivity(activity -> new ViewModelProvider(activity).get(ModernStatsViewModel.class).importReport(Uri.fromFile(input)));
            awaitState(scenario, state -> state.current != null && state.current.metrics.size() == 1);
            onView(withId(R.id.modern_summary)).check(matches(withSubstring("3 m 00 s")));
            scenario.recreate();
            awaitState(scenario, state -> state.current != null && state.current.metrics.size() == 1);
            onView(withId(R.id.modern_summary)).check(matches(withSubstring("3 m 00 s")));
            // Capture the actual API 36 layout for visual inspection in CI artifacts.
            android.graphics.Bitmap screenshot = androidx.test.platform.app.InstrumentationRegistry
                    .getInstrumentation().getUiAutomation().takeScreenshot();
            if (screenshot != null) {
                File image = new File(ApplicationProvider.getApplicationContext().getExternalFilesDir(null), "modern-dashboard.png");
                try (FileOutputStream output = new FileOutputStream(image)) {
                    screenshot.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, output);
                }
                screenshot.recycle();
            }
        } finally { input.delete(); }
    }

    @Test public void collectsRealAndroid16DumpWithAdbGrantedAccess() throws Exception {
        try (ActivityScenario<ModernStatsActivity> scenario = ActivityScenario.launch(ModernStatsActivity.class)) {
            awaitState(scenario, state -> true);
            scenario.onActivity(activity -> new ViewModelProvider(activity).get(ModernStatsViewModel.class)
                    .refresh(BatteryCollector.Access.ADB));
            awaitState(scenario, state -> state.current != null && "ADB".equals(state.current.source)
                    && state.message.startsWith("Snapshot refreshed"));
            scenario.onActivity(activity -> {
                ModernStatsViewModel.State state = new ViewModelProvider(activity).get(ModernStatsViewModel.class).state().getValue();
                assertNotNull(state.current);
                assertEquals(0, state.current.malformedRows);
                assertTrue(state.current.startClockTime > 0);
                assertTrue(state.current.batteryRealtimeMs >= state.current.batteryUptimeMs);
            });
        }
    }

    @Test public void missingShizukuShowsRecoverableError() throws Exception {
        try (ActivityScenario<ModernStatsActivity> scenario = ActivityScenario.launch(ModernStatsActivity.class)) {
            awaitState(scenario, state -> true);
            scenario.onActivity(activity -> new ViewModelProvider(activity).get(ModernStatsViewModel.class)
                    .refresh(BatteryCollector.Access.SHIZUKU));
            awaitState(scenario, state -> state.message.contains("Start Shizuku"));
            onView(withId(R.id.modern_refresh)).check(matches(isEnabled()));
        }
    }
}
