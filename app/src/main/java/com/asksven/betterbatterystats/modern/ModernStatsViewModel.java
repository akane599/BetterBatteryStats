package com.asksven.betterbatterystats.modern;

import android.app.Application;
import android.net.Uri;
import androidx.annotation.NonNull;
import androidx.lifecycle.AndroidViewModel;
import androidx.lifecycle.LiveData;
import androidx.lifecycle.MutableLiveData;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

/** Owns I/O across rotation; publishes complete snapshots only after collection and parsing succeed. */
public final class ModernStatsViewModel extends AndroidViewModel {
    public static final class State {
        public final BatterySnapshot current;
        public final BatterySnapshot baseline;
        public final boolean loading;
        public final String message;
        State(BatterySnapshot current, BatterySnapshot baseline, boolean loading, String message) {
            this.current = current; this.baseline = baseline; this.loading = loading; this.message = message;
        }
    }

    private final ExecutorService worker = Executors.newSingleThreadExecutor();
    private final AtomicBoolean busy = new AtomicBoolean();
    private final MutableLiveData<State> state = new MutableLiveData<>();
    private final SnapshotStore store;
    private SnapshotStore.Record record;
    private BatterySnapshot current;
    private BatterySnapshot baseline;

    public ModernStatsViewModel(@NonNull Application application) {
        super(application);
        store = new SnapshotStore(application);
        run(() -> {
            record = store.load("latest");
            if (record != null) current = record.snapshot();
            SnapshotStore.Record saved = store.load("baseline");
            if (saved != null) baseline = saved.snapshot();
            return current == null ? "Connect Shizuku, use ADB access, or import a report to begin."
                    : "Saved snapshot loaded. Tap Refresh for current data.";
        });
    }

    public LiveData<State> state() { return state; }

    private interface Work { String execute() throws Exception; }

    private void run(Work work) {
        if (!busy.compareAndSet(false, true)) return;
        worker.execute(() -> {
            state.postValue(new State(current, baseline, true, "Working…"));
            String message;
            try { message = work.execute(); }
            catch (Exception e) { message = e.getMessage() == null ? "Operation failed. Try again." : e.getMessage(); }
            finally { busy.set(false); }
            state.postValue(new State(current, baseline, false, message));
        });
    }

    public void refresh(BatteryCollector.Access access) {
        run(() -> {
            String raw = new BatteryCollector().collect(getApplication(), access);
            SnapshotStore.Record fresh = store.capture(raw, access.name(), false);
            BatterySnapshot parsed = fresh.snapshot();
            store.save("latest", fresh);
            record = fresh;
            current = parsed;
            ModernWidget.updateAll(getApplication(), current);
            return "Snapshot refreshed. Collection stops between refreshes."
                    + (parsed.malformedRows > 0 ? " " + parsed.malformedRows + " malformed rows skipped; comparisons disabled." : "");
        });
    }

    public void saveBaseline() {
        run(() -> {
            if (record == null || current == null) throw new IOException("Refresh before saving a baseline.");
            if ("Imported report".equals(current.source)) throw new IOException("Baselines require a live snapshot from this device.");
            if (current.malformedRows != 0) throw new IOException("Incomplete reports cannot be used as baselines.");
            store.save("baseline", record);
            baseline = current;
            return "Displayed snapshot saved as baseline. Refresh later to compare.";
        });
    }

    public void clearBaseline() {
        run(() -> { store.delete("baseline"); baseline = null; return "Baseline cleared."; });
    }

    public void importReport(Uri uri) {
        run(() -> {
            String raw;
            try (InputStream input = getApplication().getContentResolver().openInputStream(uri)) {
                if (input == null) throw new IOException("Could not open report.");
                raw = DumpCommand.readLimited(input);
            }
            SnapshotStore.Record imported = store.capture(raw, "Imported report", true);
            BatterySnapshot parsed = imported.snapshot();
            record = imported;
            current = parsed;
            return "Imported report · acquisition time and device identity are unknown. Live baseline comparison is unavailable."
                    + (parsed.malformedRows > 0 ? " " + parsed.malformedRows + " malformed rows skipped." : "");
        });
    }

    public void export(Uri uri, boolean raw, boolean compare) {
        run(() -> {
            if (current == null || record == null) throw new IOException("Collect or import a snapshot first.");
            if (compare && baseline == null) throw new IOException("No baseline is available.");
            String text = raw ? record.raw : ReportExporter.render(current, baseline, compare);
            try (OutputStream output = getApplication().getContentResolver().openOutputStream(uri, "wt")) {
                if (output == null) throw new IOException("Could not open export destination.");
                output.write(text.getBytes(StandardCharsets.UTF_8));
            }
            return "Report exported.";
        });
    }

    @Override protected void onCleared() { worker.shutdownNow(); }
}
