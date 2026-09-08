package com.asksven.betterbatterystats.modern;

import android.content.Context;
import android.os.BatteryManager;
import android.os.Build;
import android.os.SystemClock;
import android.provider.Settings;
import android.util.AtomicFile;
import com.google.gson.Gson;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.StringReader;
import java.nio.charset.StandardCharsets;

/** Atomic app-private storage; keep raw data so cached records are validated on every load. */
public final class SnapshotStore {
    private final Context context;
    private final Gson gson = new Gson();

    public SnapshotStore(Context context) { this.context = context.getApplicationContext(); }

    public static final class Record {
        public String raw;
        public long capturedAtMs;
        public long elapsedRealtimeMs;
        public int bootCount = -1;
        public int batteryLevel = -1;
        public String source;

        public BatterySnapshot snapshot() throws IOException {
            if (raw == null || source == null) throw new IOException("Saved snapshot is incomplete.");
            BatterySnapshot result = new CheckinParser().parse(new StringReader(raw));
            result.capturedAtMs = capturedAtMs;
            result.elapsedRealtimeMs = elapsedRealtimeMs;
            result.bootCount = bootCount;
            result.batteryLevel = batteryLevel;
            result.source = source;
            return result;
        }
    }

    public Record capture(String raw, String source, boolean imported) {
        Record record = new Record();
        record.raw = raw;
        record.source = source;
        record.capturedAtMs = System.currentTimeMillis();
        if (!imported) {
            record.elapsedRealtimeMs = SystemClock.elapsedRealtime();
            if (Build.VERSION.SDK_INT >= 24) {
                record.bootCount = Settings.Global.getInt(context.getContentResolver(), Settings.Global.BOOT_COUNT, -1);
            }
            BatteryManager battery = (BatteryManager) context.getSystemService(Context.BATTERY_SERVICE);
            int level = battery.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY);
            record.batteryLevel = level >= 0 && level <= 100 ? level : -1;
        }
        return record;
    }

    private AtomicFile file(String name) {
        return new AtomicFile(new File(context.getNoBackupFilesDir(), "modern-" + name + ".json"));
    }

    public Record load(String name) throws IOException {
        AtomicFile file = file(name);
        if (!file.getBaseFile().exists()) return null;
        try (FileInputStream input = file.openRead()) {
            Record record = gson.fromJson(DumpCommand.readLimited(input), Record.class);
            if (record == null) throw new IOException("Saved snapshot is empty.");
            record.snapshot();
            return record;
        } catch (RuntimeException e) { throw new IOException("Saved snapshot could not be read.", e); }
    }

    public void save(String name, Record record) throws IOException {
        byte[] bytes = gson.toJson(record).getBytes(StandardCharsets.UTF_8);
        if (bytes.length > CheckinParser.MAX_CHARS) throw new IOException("Snapshot is too large to save.");
        AtomicFile target = file(name);
        FileOutputStream output = null;
        try {
            output = target.startWrite();
            output.write(bytes);
            target.finishWrite(output);
        } catch (IOException e) { target.failWrite(output); throw e; }
    }

    public void delete(String name) { file(name).delete(); }
}
