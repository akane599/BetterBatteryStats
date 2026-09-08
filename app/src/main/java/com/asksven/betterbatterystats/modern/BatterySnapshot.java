package com.asksven.betterbatterystats.modern;

import java.util.LinkedHashMap;
import java.util.Map;

/** Cumulative counters in milliseconds, scoped to one Android batterystats epoch. */
public final class BatterySnapshot {
    public long capturedAtMs;
    public long elapsedRealtimeMs;
    public int bootCount = -1;
    public int batteryLevel = -1;
    public long startClockTime;
    public long startCount;
    public long batteryRealtimeMs;
    public long batteryUptimeMs;
    public long totalRealtimeMs;
    public long screenOffRealtimeMs;
    public long screenOffUptimeMs;
    public long screenOnMs = -1;
    public int malformedRows;
    public String source = "Imported report";
    public Map<Integer, String> packages = new LinkedHashMap<>();
    public Map<String, Metric> metrics = new LinkedHashMap<>();

    public enum Kind { WAKELOCK, KERNEL_WAKELOCK, BLUETOOTH_SCAN, SYNC, JOB, ALARM, NETWORK }

    public static final class Metric {
        public Kind kind;
        public int uid;
        public String name;
        public long durationMs;
        public long count;
        public long actualMs = -1;
        public long backgroundMs = -1;
        public long scanResults = -1;
        public long bytes = -1;

        public Metric() { }

        public Metric(Kind kind, int uid, String name, long durationMs, long count) {
            this.kind = kind;
            this.uid = uid;
            this.name = name;
            this.durationMs = durationMs;
            this.count = count;
        }

        public String key() { return kind + "\u0000" + uid + "\u0000" + name; }

        public long rank() {
            if (kind == Kind.ALARM) return count;
            if (kind == Kind.NETWORK) return bytes;
            return durationMs;
        }
    }

    public String packageFor(int uid) {
        String name = packages.get(uid);
        // Android's checkin UID map uses app IDs, even for secondary users.
        if (name == null) name = packages.get(uid % 100000);
        return name == null ? "UID " + uid : name;
    }

    public long sleepMs() { return Math.max(0, batteryRealtimeMs - batteryUptimeMs); }

    public long screenOffAwakeMs() { return screenOffUptimeMs; }
}
