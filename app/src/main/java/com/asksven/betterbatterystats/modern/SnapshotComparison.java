package com.asksven.betterbatterystats.modern;

import static com.asksven.betterbatterystats.modern.BatterySnapshot.Metric;

/** Rejects resets instead of producing negative durations or fabricated zero-drain results. */
public final class SnapshotComparison {
    private SnapshotComparison() { }

    public static BatterySnapshot between(BatterySnapshot before, BatterySnapshot after) {
        if (before.startClockTime != after.startClockTime || before.startCount != after.startCount
                || !before.source.equals(after.source)
                || (before.bootCount >= 0 && after.bootCount != before.bootCount)
                || after.elapsedRealtimeMs < before.elapsedRealtimeMs
                || before.malformedRows != 0 || after.malformedRows != 0) {
            throw new IllegalArgumentException("Baseline belongs to a different or incomplete battery session. Save a new baseline.");
        }
        BatterySnapshot delta = new BatterySnapshot();
        delta.source = after.source;
        delta.capturedAtMs = after.capturedAtMs;
        delta.batteryLevel = after.batteryLevel;
        delta.batteryRealtimeMs = difference(before.batteryRealtimeMs, after.batteryRealtimeMs);
        delta.batteryUptimeMs = difference(before.batteryUptimeMs, after.batteryUptimeMs);
        delta.totalRealtimeMs = difference(before.totalRealtimeMs, after.totalRealtimeMs);
        delta.screenOffRealtimeMs = difference(before.screenOffRealtimeMs, after.screenOffRealtimeMs);
        delta.screenOffUptimeMs = difference(before.screenOffUptimeMs, after.screenOffUptimeMs);
        delta.screenOnMs = optionalDifference(before.screenOnMs, after.screenOnMs);
        delta.packages.putAll(after.packages);
        for (Metric old : before.metrics.values()) {
            if (!after.metrics.containsKey(old.key()) && (old.durationMs > 0 || old.count > 0 || old.bytes > 0)) {
                throw new IllegalArgumentException("A baseline counter disappeared (app removal or statistics reset). Save a new baseline.");
            }
        }
        for (Metric now : after.metrics.values()) {
            Metric old = before.metrics.get(now.key());
            Metric entry = new Metric(now.kind, now.uid, now.name,
                    difference(old == null ? 0 : old.durationMs, now.durationMs),
                    difference(old == null ? 0 : old.count, now.count));
            entry.actualMs = optionalDifference(old == null ? 0 : old.actualMs, now.actualMs);
            entry.backgroundMs = optionalDifference(old == null ? 0 : old.backgroundMs, now.backgroundMs);
            entry.scanResults = optionalDifference(old == null ? 0 : old.scanResults, now.scanResults);
            entry.bytes = optionalDifference(old == null ? 0 : old.bytes, now.bytes);
            delta.metrics.put(entry.key(), entry);
        }
        return delta;
    }

    private static long difference(long before, long after) {
        if (after < before) throw new IllegalArgumentException("Android reset a battery counter. Save a new baseline.");
        return after - before;
    }

    private static long optionalDifference(long before, long after) {
        return before < 0 || after < 0 ? -1 : difference(before, after);
    }
}
