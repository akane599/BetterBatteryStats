package com.asksven.betterbatterystats.modern;

import java.util.Locale;

public final class StatsFormat {
    private StatsFormat() { }

    public static String duration(long ms) {
        if (ms < 0) return "Not reported";
        long seconds = ms / 1000;
        if (seconds < 60) return String.format(Locale.getDefault(), "%.1f s", ms / 1000.0);
        if (seconds < 3600) return String.format(Locale.getDefault(), "%d m %02d s", seconds / 60, seconds % 60);
        return String.format(Locale.getDefault(), "%d h %02d m", seconds / 3600, seconds / 60 % 60);
    }

    public static String bytes(long value) {
        if (value < 1024) return value + " B";
        if (value < 1024 * 1024) return String.format(Locale.getDefault(), "%.1f KiB", value / 1024.0);
        return String.format(Locale.getDefault(), "%.1f MiB", value / 1048576.0);
    }

    public static String kind(BatterySnapshot.Kind kind) {
        switch (kind) {
            case WAKELOCK: return "Partial wakelocks";
            case KERNEL_WAKELOCK: return "Kernel wakelocks";
            case BLUETOOTH_SCAN: return "Bluetooth LE scans";
            case SYNC: return "Syncs";
            case JOB: return "Jobs";
            case ALARM: return "Wakeup alarms";
            case NETWORK: return "Network traffic";
            default: throw new IllegalArgumentException("Unknown category");
        }
    }

    public static String metric(BatterySnapshot.Metric metric) {
        if (metric.kind == BatterySnapshot.Kind.NETWORK) return bytes(metric.bytes);
        if (metric.kind == BatterySnapshot.Kind.ALARM) return metric.count + " wakeups";
        String value = duration(metric.durationMs) + " · " + metric.count + " starts";
        if (metric.actualMs >= 0) value += "\nActual " + duration(metric.actualMs);
        if (metric.backgroundMs >= 0) value += " · Background " + duration(metric.backgroundMs);
        if (metric.scanResults >= 0) value += "\n" + metric.scanResults + " scan results";
        return value;
    }
}
