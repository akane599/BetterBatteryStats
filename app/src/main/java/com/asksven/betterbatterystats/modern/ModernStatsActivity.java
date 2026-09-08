package com.asksven.betterbatterystats.modern;

import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.pm.PackageManager;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.Menu;
import android.view.MenuItem;
import android.view.View;
import android.view.ViewGroup;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.ProgressBar;
import android.widget.Spinner;
import android.widget.TextView;
import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.appcompat.widget.SearchView;
import androidx.appcompat.widget.Toolbar;
import androidx.core.graphics.Insets;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowCompat;
import androidx.core.view.WindowInsetsCompat;
import androidx.lifecycle.ViewModelProvider;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;
import com.asksven.betterbatterystats.R;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import java.text.DateFormat;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import rikka.shizuku.Shizuku;

public final class ModernStatsActivity extends AppCompatActivity {
    private static final int ACCESS = 10, BASELINE = 11, COMPARE = 12, CLEAR = 13,
            IMPORT = 14, EXPORT = 15, RAW = 16, HELP = 17;
    private ModernStatsViewModel model;
    private ModernStatsViewModel.State state;
    private BatterySnapshot displayed;
    private Menu optionsMenu;
    private boolean compare;
    private boolean exportRaw;
    private boolean exportCompare;
    private String query = "";
    private String comparisonError = "";
    private BatterySnapshot.Kind category = BatterySnapshot.Kind.WAKELOCK;
    private BatteryCollector.Access access = BatteryCollector.Access.SHIZUKU;
    private final StatsAdapter adapter = new StatsAdapter();
    private final Shizuku.OnRequestPermissionResultListener permissionListener = (code, result) -> {
        if (code != 100) return;
        if (result == PackageManager.PERMISSION_GRANTED) { access = BatteryCollector.Access.SHIZUKU; getPreferences(MODE_PRIVATE).edit().putString("access", access.name()).apply(); model.refresh(access); }
        else showMessage("Shizuku access was denied. Allow BBS in Shizuku, or choose ADB / root access from the menu.");
    };
    private final Shizuku.OnBinderDeadListener deadListener = () -> runOnUiThread(() -> {
        if (!isFinishing()) showMessage("Shizuku stopped. Saved data is still available; restart Shizuku before refreshing.");
    });

    private final ActivityResultLauncher<String[]> importLauncher = registerForActivityResult(
            new ActivityResultContracts.OpenDocument(), uri -> { if (uri != null) { compare = false; model.importReport(uri); } });
    private final ActivityResultLauncher<String> exportLauncher = registerForActivityResult(
            new ActivityResultContracts.CreateDocument("text/plain"), uri -> {
                if (uri != null) model.export(uri, exportRaw, exportCompare);
            });

    @Override protected void onCreate(Bundle savedState) {
        setTheme(R.style.Theme_Bbs_Modern);
        super.onCreate(savedState);
        WindowCompat.setDecorFitsSystemWindows(getWindow(), false);
        setContentView(R.layout.modern_stats);
        View root = findViewById(R.id.modern_root);
        ViewCompat.setOnApplyWindowInsetsListener(root, (view, windowInsets) -> {
            Insets insets = windowInsets.getInsets(WindowInsetsCompat.Type.systemBars()
                    | WindowInsetsCompat.Type.displayCutout() | WindowInsetsCompat.Type.ime());
            view.setPadding(insets.left, insets.top, insets.right, insets.bottom);
            return WindowInsetsCompat.CONSUMED;
        });
        ViewCompat.requestApplyInsets(root);
        Toolbar toolbar = findViewById(R.id.modern_toolbar);
        toolbar.setTitle(R.string.modern_title);
        setSupportActionBar(toolbar);
        if (savedState != null) {
            compare = savedState.getBoolean("compare");
            query = savedState.getString("query", "");
            category = BatterySnapshot.Kind.valueOf(savedState.getString("category", "WAKELOCK"));
            exportRaw = savedState.getBoolean("exportRaw");
            exportCompare = savedState.getBoolean("exportCompare");
        }
        String savedAccess = getPreferences(MODE_PRIVATE).getString("access", "SHIZUKU");
        try { access = BatteryCollector.Access.valueOf(savedAccess); }
        catch (IllegalArgumentException ignored) { }
        RecyclerView list = findViewById(R.id.modern_list);
        list.setLayoutManager(new LinearLayoutManager(this));
        list.setAdapter(adapter);
        Spinner spinner = findViewById(R.id.modern_category);
        List<String> categories = new ArrayList<>();
        for (BatterySnapshot.Kind kind : BatterySnapshot.Kind.values()) categories.add(StatsFormat.kind(kind));
        ArrayAdapter<String> choices = new ArrayAdapter<>(this, android.R.layout.simple_spinner_item, categories);
        choices.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        spinner.setAdapter(choices);
        spinner.setSelection(category.ordinal());
        spinner.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override public void onItemSelected(AdapterView<?> parent, View view, int position, long id) {
                category = BatterySnapshot.Kind.values()[position]; render();
            }
            @Override public void onNothingSelected(AdapterView<?> parent) { }
        });
        model = new ViewModelProvider(this).get(ModernStatsViewModel.class);
        model.state().observe(this, value -> { state = value; render(); });
        Shizuku.addRequestPermissionResultListener(permissionListener);
        Shizuku.addBinderDeadListener(deadListener);
    }

    private void connectShizuku() {
        try {
            if (!Shizuku.pingBinder()) { showMessage("Install and start Shizuku, then return here and tap Connect Shizuku. On Android 11+, Shizuku can be started using wireless debugging."); return; }
            if (Shizuku.isPreV11()) { showMessage("Update Shizuku before connecting."); return; }
            if (BatteryCollector.shizukuReady()) {
                access = BatteryCollector.Access.SHIZUKU;
                getPreferences(MODE_PRIVATE).edit().putString("access", access.name()).apply();
                model.refresh(access);
            } else if (Shizuku.shouldShowRequestPermissionRationale()) {
                showMessage("Allow BBS in Shizuku’s authorized applications, then reconnect.");
            } else Shizuku.requestPermission(100);
        } catch (RuntimeException e) { showMessage("Shizuku disconnected. Restart it and try again."); }
    }

    private void showMessage(String message) {
        new MaterialAlertDialogBuilder(this).setMessage(message).setPositiveButton(android.R.string.ok, null).show();
    }

    private void render() {
        displayed = state == null ? null : state.current;
        comparisonError = "";
        if (compare && displayed != null) {
            if (state.baseline == null) { comparisonError = "No baseline is saved. Showing cumulative statistics."; compare = false; }
            else {
                try { displayed = SnapshotComparison.between(state.baseline, state.current); }
                catch (IllegalArgumentException e) { comparisonError = e.getMessage(); displayed = null; }
            }
        }
        adapter.entries.clear();
        if (displayed != null) {
            String search = query.toLowerCase(Locale.ROOT);
            for (BatterySnapshot.Metric metric : displayed.metrics.values()) {
                String label = displayed.packageFor(metric.uid) + " " + metric.uid + " " + metric.name;
                if (metric.kind == category && label.toLowerCase(Locale.ROOT).contains(search)
                        && (metric.rank() > 0 || metric.count > 0)) adapter.entries.add(metric);
            }
            adapter.entries.sort(Comparator.comparingLong(BatterySnapshot.Metric::rank).reversed()
                    .thenComparingInt(metric -> metric.uid).thenComparing(metric -> metric.name));
        }
        adapter.notifyDataSetChanged();
        if (optionsMenu != null) onPrepareOptionsMenu(optionsMenu);
    }

    @Override public boolean onCreateOptionsMenu(Menu menu) {
        optionsMenu = menu;
        MenuItem searchItem = menu.add(getString(R.string.modern_search));
        SearchView search = new SearchView(this);
        search.setQueryHint(getString(R.string.modern_search));
        searchItem.setActionView(search).setShowAsAction(MenuItem.SHOW_AS_ACTION_IF_ROOM | MenuItem.SHOW_AS_ACTION_COLLAPSE_ACTION_VIEW);
        search.setQuery(query, false);
        search.setOnQueryTextListener(new SearchView.OnQueryTextListener() {
            @Override public boolean onQueryTextSubmit(String text) { search.clearFocus(); return true; }
            @Override public boolean onQueryTextChange(String text) { query = text; render(); return true; }
        });
        menu.add(0, ACCESS, 0, "Data access: " + access);
        menu.add(0, BASELINE, 0, "Use snapshot as baseline");
        menu.add(0, COMPARE, 0, "Compare with baseline").setCheckable(true);
        menu.add(0, CLEAR, 0, "Clear baseline");
        menu.add(0, IMPORT, 0, "Import checkin report");
        menu.add(0, EXPORT, 0, "Export displayed statistics");
        menu.add(0, RAW, 0, "Export raw snapshot");
        menu.add(0, HELP, 0, "Setup and data limitations");
        return true;
    }

    @Override public boolean onPrepareOptionsMenu(Menu menu) {
        boolean idle = state != null && !state.loading;
        menu.findItem(ACCESS).setTitle("Data access: " + access).setEnabled(idle);
        menu.findItem(BASELINE).setEnabled(idle && state.current != null && !"Imported report".equals(state.current.source));
        menu.findItem(COMPARE).setChecked(compare).setEnabled(idle && state.baseline != null && state.current != null);
        menu.findItem(CLEAR).setEnabled(idle && state.baseline != null);
        menu.findItem(IMPORT).setEnabled(idle);
        menu.findItem(EXPORT).setEnabled(idle && displayed != null);
        menu.findItem(RAW).setEnabled(idle && state.current != null);
        return super.onPrepareOptionsMenu(menu);
    }

    @Override public boolean onOptionsItemSelected(@NonNull MenuItem item) {
        switch (item.getItemId()) {
            case ACCESS:
                new MaterialAlertDialogBuilder(this).setTitle("Battery data access")
                        .setSingleChoiceItems(new String[]{"Shizuku", "ADB-granted DUMP + usage access", "Root (su)"}, access.ordinal(), (dialog, which) -> {
                            access = BatteryCollector.Access.values()[which];
                            getPreferences(MODE_PRIVATE).edit().putString("access", access.name()).apply();
                            dialog.dismiss(); render();
                            if (access == BatteryCollector.Access.ADB) showHelp();
                        }).show(); return true;
            case BASELINE: model.saveBaseline(); return true;
            case COMPARE: compare = !compare; render(); return true;
            case CLEAR: compare = false; model.clearBaseline(); return true;
            case IMPORT: importLauncher.launch(new String[]{"text/*", "application/octet-stream"}); return true;
            case EXPORT:
            case RAW:
                exportRaw = item.getItemId() == RAW; exportCompare = compare;
                exportLauncher.launch(exportRaw ? "bbs-checkin.txt" : "bbs-report.txt"); return true;
            case HELP: showHelp(); return true;
            default: return super.onOptionsItemSelected(item);
        }
    }

    private void showHelp() {
        String commands = "adb shell pm grant " + getPackageName() + " android.permission.DUMP\n"
                + "adb shell appops set " + getPackageName() + " GET_USAGE_STATS allow\n"
                + "adb shell dumpsys batterystats -c --charged > bbs-checkin.txt";
        String message = "Shizuku: start it and allow BBS access. Reconnect after a reboot or if Shizuku stops. Root is optional and only requested when selected.\n\n"
                + "ADB alternative (last line creates an importable file):\n" + commands + "\n\n"
                + "Save a baseline, use your phone, refresh, then enable Compare with baseline. Android resets, reboots and disappearing counters require a new baseline.\n\n"
                + "This dashboard collects on demand. Widgets show the last collected snapshot. Automatic unplug/screen-off references, legacy graphs, CPU frequencies and process statistics are not provided by this new backend.\n\n"
                + "Android and your phone manufacturer decide which counters are available. Kernel wakelocks may be absent even with Shizuku. Wakelock time is not measured battery energy. Pooled time shares overlapping timers; actual and background times can overlap across apps. A scan start is different from a scan result. Shared UIDs list all known packages, not attribution to one package.\n\n"
                + "Reports can contain app names and wakelock tags. All collection and storage stays on your device; exports go only to the location you choose.";
        new MaterialAlertDialogBuilder(this).setTitle("Setup and data limitations").setMessage(message)
                .setNeutralButton("Copy ADB commands", (dialog, which) -> {
                    ClipboardManager clipboard = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
                    clipboard.setPrimaryClip(ClipData.newPlainText("BBS setup", commands));
                }).setPositiveButton(android.R.string.ok, null).show();
    }

    @Override protected void onSaveInstanceState(@NonNull Bundle out) {
        super.onSaveInstanceState(out);
        out.putBoolean("compare", compare); out.putString("query", query);
        out.putString("category", category.name());
        out.putBoolean("exportRaw", exportRaw); out.putBoolean("exportCompare", exportCompare);
    }

    @Override protected void onDestroy() {
        Shizuku.removeRequestPermissionResultListener(permissionListener);
        Shizuku.removeBinderDeadListener(deadListener);
        super.onDestroy();
    }

    private final class StatsAdapter extends RecyclerView.Adapter<RecyclerView.ViewHolder> {
        final List<BatterySnapshot.Metric> entries = new ArrayList<>();
        @Override public int getItemCount() { return 1 + Math.max(1, entries.size()); }
        @Override public int getItemViewType(int position) { return position == 0 ? 0 : 1; }
        @NonNull @Override public RecyclerView.ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int type) {
            return new RecyclerView.ViewHolder(LayoutInflater.from(parent.getContext()).inflate(
                    type == 0 ? R.layout.modern_header : R.layout.modern_row, parent, false)) { };
        }
        @Override public void onBindViewHolder(@NonNull RecyclerView.ViewHolder holder, int position) {
            View view = holder.itemView;
            if (position == 0) { bindHeader(view); return; }
            TextView title = view.findViewById(R.id.modern_row_title);
            TextView detail = view.findViewById(R.id.modern_row_detail);
            ProgressBar bar = view.findViewById(R.id.modern_row_bar);
            if (entries.isEmpty()) {
                title.setText(displayed == null ? "No statistics to display" : "No matching records reported");
                detail.setText(displayed == null ? "Connect a data source or import a report." : "Try another category or search. Missing records do not prove there was no activity.");
                bar.setVisibility(View.GONE); return;
            }
            BatterySnapshot.Metric metric = entries.get(position - 1);
            title.setText(displayed.packageFor(metric.uid) + " · UID " + metric.uid);
            detail.setText(metric.name + "\n" + StatsFormat.metric(metric));
            bar.setVisibility(View.VISIBLE);
            bar.setProgress((int) (1000.0 * metric.rank() / Math.max(1, entries.get(0).rank())));
        }
        private void bindHeader(View view) {
            TextView period = view.findViewById(R.id.modern_period);
            TextView summary = view.findViewById(R.id.modern_summary);
            TextView status = view.findViewById(R.id.modern_status);
            TextView explanation = view.findViewById(R.id.modern_explanation);
            boolean loading = state == null || state.loading;
            period.setText(compare ? "Since saved baseline" : "Since Android statistics reset");
            if (displayed == null) summary.setText("Battery activity, without continuous polling.");
            else summary.setText("On battery  " + StatsFormat.duration(displayed.batteryRealtimeMs)
                    + "\nScreen off awake  " + StatsFormat.duration(displayed.screenOffAwakeMs())
                    + "\nDeep sleep  " + StatsFormat.duration(displayed.sleepMs())
                    + "\nScreen on  " + StatsFormat.duration(displayed.screenOnMs));
            String text = state == null ? "Loading…" : state.message;
            if (state != null && state.current != null) {
                BatterySnapshot current = state.current;
                boolean imported = "Imported report".equals(current.source);
                text += "\n" + current.source + " · " + (imported ? "Imported " : "Captured ")
                        + DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT).format(new Date(current.capturedAtMs));
                if (current.batteryLevel >= 0) text += " · " + current.batteryLevel + "% at capture";
                if (current.malformedRows > 0) text += "\n" + current.malformedRows + " malformed rows were skipped.";
                if (compare && state.baseline != null) text += "\nBaseline "
                        + DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT).format(new Date(state.baseline.capturedAtMs));
            }
            if (!comparisonError.isEmpty()) text += "\n" + comparisonError;
            status.setText(text);
            explanation.setText(category == BatterySnapshot.Kind.NETWORK ? "Ranked by transferred bytes (mobile + Wi-Fi)."
                    : category == BatterySnapshot.Kind.ALARM ? "Ranked by wakeup count."
                    : "Ranked by Android’s accounted time (pooled for app wakelocks and Bluetooth scans). Bars compare rows; they do not indicate battery percentage. Actual times may overlap. Missing data means not reported.");
            view.findViewById(R.id.modern_loading).setVisibility(loading ? View.VISIBLE : View.GONE);
            Button refresh = view.findViewById(R.id.modern_refresh);
            refresh.setEnabled(!loading); refresh.setOnClickListener(button -> model.refresh(access));
            Button connect = view.findViewById(R.id.modern_connect);
            connect.setEnabled(!loading); connect.setOnClickListener(button -> connectShizuku());
        }
    }
}
