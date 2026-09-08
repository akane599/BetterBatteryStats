package com.asksven.betterbatterystats.modern;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.Date;
import java.util.List;

public final class ReportExporter {
    private ReportExporter() { }

    public static String render(BatterySnapshot current, BatterySnapshot baseline, boolean compare) {
        BatterySnapshot data = compare ? SnapshotComparison.between(baseline, current) : current;
        StringBuilder out = new StringBuilder("BetterBatteryStats 4 · Battery report\n");
        out.append("Imported report".equals(current.source) ? "Imported (capture time unknown): " : "Captured: ").append(new Date(current.capturedAtMs)).append('\n');
        out.append("Access: ").append(current.source).append('\n');
        out.append("Period: ").append(compare ? "Since baseline " + new Date(baseline.capturedAtMs)
                : "Since Android statistics reset " + new Date(current.startClockTime)).append('\n');
        out.append("On battery: ").append(StatsFormat.duration(data.batteryRealtimeMs)).append('\n');
        out.append("Screen off awake: ").append(StatsFormat.duration(data.screenOffAwakeMs())).append('\n');
        out.append("Deep sleep on battery: ").append(StatsFormat.duration(data.sleepMs())).append('\n');
        out.append("Screen on: ").append(StatsFormat.duration(data.screenOnMs)).append('\n');
        out.append("Malformed rows skipped: ").append(current.malformedRows).append('\n');
        out.append("Times reflect Android accounting, not measured energy. Pooled times share overlapping activity; actual times can overlap across apps. Starts and scan results are different counters. Missing categories are not proof of no activity.\n");
        for (BatterySnapshot.Kind kind : BatterySnapshot.Kind.values()) {
            out.append('\n').append(StatsFormat.kind(kind)).append('\n');
            List<BatterySnapshot.Metric> entries = new ArrayList<>();
            for (BatterySnapshot.Metric metric : data.metrics.values()) if (metric.kind == kind) entries.add(metric);
            entries.sort(Comparator.comparingLong(BatterySnapshot.Metric::rank).reversed());
            if (entries.isEmpty()) out.append("No records reported\n");
            for (BatterySnapshot.Metric metric : entries) {
                out.append(data.packageFor(metric.uid)).append(" [UID ").append(metric.uid).append("]\n");
                out.append(metric.name.replace('\n', ' ').replace('\r', ' ')).append(" — ")
                        .append(StatsFormat.metric(metric).replace('\n', ';')).append('\n');
            }
        }
        return out.toString();
    }
}
