package com.asksven.betterbatterystats.modern;

import org.junit.Test;
import java.io.StringReader;
import static org.junit.Assert.*;

public class SnapshotComparisonTest {
    private BatterySnapshot snapshot() throws Exception {
        BatterySnapshot snapshot = new CheckinParser().parse(new StringReader(CheckinParserTest.BATTERY + CheckinParserTest.LOCK));
        snapshot.source = "SHIZUKU";
        snapshot.bootCount = 3;
        snapshot.elapsedRealtimeMs = 10000;
        return snapshot;
    }

    @Test public void subtractsAllCumulativeCountersAndNewTags() throws Exception {
        BatterySnapshot before = snapshot(), after = snapshot();
        after.elapsedRealtimeMs += 1000;
        after.batteryRealtimeMs += 1000;
        after.batteryUptimeMs += 400;
        after.screenOffRealtimeMs += 1000;
        after.screenOffUptimeMs += 200;
        BatterySnapshot.Metric lock = after.metrics.values().iterator().next();
        lock.durationMs += 200;
        lock.actualMs += 400;
        lock.count += 1;
        BatterySnapshot.Metric newLock = new BatterySnapshot.Metric(BatterySnapshot.Kind.WAKELOCK, 10012, "new", 120, 2);
        after.metrics.put(newLock.key(), newLock);
        BatterySnapshot result = SnapshotComparison.between(before, after);
        assertEquals(1000, result.batteryRealtimeMs);
        assertEquals(600, result.sleepMs());
        assertEquals(200, result.screenOffAwakeMs());
        assertEquals(200, result.metrics.get(lock.key()).durationMs);
        assertEquals(400, result.metrics.get(lock.key()).actualMs);
        assertEquals(1, result.metrics.get(lock.key()).count);
        assertEquals(120, result.metrics.get(newLock.key()).durationMs);
        assertEquals(-1, result.screenOnMs);
        assertEquals(120000, before.metrics.values().iterator().next().durationMs);
    }

    @Test public void sameSnapshotIsZeroWithoutDivisionByZero() throws Exception {
        BatterySnapshot snapshot = snapshot();
        assertEquals(0, SnapshotComparison.between(snapshot, snapshot).batteryRealtimeMs);
        assertTrue(ReportExporter.render(snapshot, snapshot, true).contains("0.0 s"));
    }

    @Test(expected = IllegalArgumentException.class) public void rejectsAndroidReset() throws Exception {
        BatterySnapshot a = snapshot(), b = snapshot(); b.startClockTime++; SnapshotComparison.between(a, b);
    }
    @Test(expected = IllegalArgumentException.class) public void rejectsRebootEvenAfterUptimeCatchesUp() throws Exception {
        BatterySnapshot a = snapshot(), b = snapshot(); b.bootCount++; b.elapsedRealtimeMs += 100000; SnapshotComparison.between(a, b);
    }
    @Test(expected = IllegalArgumentException.class) public void rejectsClockRollback() throws Exception {
        BatterySnapshot a = snapshot(), b = snapshot(); b.elapsedRealtimeMs--; SnapshotComparison.between(a, b);
    }
    @Test(expected = IllegalArgumentException.class) public void rejectsCounterReset() throws Exception {
        BatterySnapshot a = snapshot(), b = snapshot(); b.metrics.values().iterator().next().durationMs = 1; SnapshotComparison.between(a, b);
    }
    @Test(expected = IllegalArgumentException.class) public void rejectsRemovedUid() throws Exception {
        BatterySnapshot a = snapshot(), b = snapshot(); b.metrics.clear(); SnapshotComparison.between(a, b);
    }
    @Test(expected = IllegalArgumentException.class) public void rejectsIncompleteSnapshot() throws Exception {
        BatterySnapshot a = snapshot(), b = snapshot(); b.malformedRows = 1; SnapshotComparison.between(a, b);
    }
    @Test(expected = IllegalArgumentException.class) public void rejectsOtherSource() throws Exception {
        BatterySnapshot a = snapshot(), b = snapshot(); b.source = "Imported report"; SnapshotComparison.between(a, b);
    }
}
