package com.asksven.betterbatterystats.modern;

import org.junit.Test;
import java.io.IOException;
import java.io.StringReader;
import static org.junit.Assert.*;

public class CheckinParserTest {
    static final String BATTERY = "9,0,l,bt,3,3600000,600000,4000000,800000,1700000000000,3000000,180000,4000,3900000,4100000,20000\n";
    static final String LOCK = "9,10011,l,wl,wear_transport,0,f,0,0,0,0,120000,p,12,0,30000,180000,18000,bp,3,0,6000,24000,0,w,0,0,0,0\n";

    private BatterySnapshot parse(String text) throws IOException { return new CheckinParser().parse(new StringReader(text)); }

    @Test public void readsAosp16TimersAndSharedUidWithoutConflatingBackground() throws Exception {
        BatterySnapshot snapshot = parse("9,0,i,uid,10011,com.google.android.gsf\n"
                + "9,0,i,uid,10011,com.example.shared\n" + BATTERY + LOCK
                + "9,0,l,m,600000,2000\n"
                + "9,10011,l,blem,6000,4,2,10000,7000,25,15,4000,3000,2000,1500\n");
        assertEquals(180000, snapshot.screenOffAwakeMs());
        assertEquals(3000000, snapshot.sleepMs());
        assertEquals(600000, snapshot.screenOnMs);
        assertEquals("com.google.android.gsf, com.example.shared", snapshot.packageFor(110011));
        BatterySnapshot.Metric lock = snapshot.metrics.values().iterator().next();
        assertEquals(120000, lock.durationMs);
        assertEquals(12, lock.count);
        assertEquals(180000, lock.actualMs);
        assertEquals(24000, lock.backgroundMs);
        BatterySnapshot.Metric scan = snapshot.metrics.get(new BatterySnapshot.Metric(
                BatterySnapshot.Kind.BLUETOOTH_SCAN, 10011, "Bluetooth LE scan", 0, 0).key());
        assertEquals(6000, scan.durationMs);
        assertEquals(4, scan.count);
        assertEquals(10000, scan.actualMs);
        assertEquals(7000, scan.backgroundMs);
        assertEquals(25, scan.scanResults);
        assertEquals(0, snapshot.malformedRows);
    }

    @Test public void acceptsQuotedTagsAndSumsDuplicateSharedUidAlarms() throws Exception {
        BatterySnapshot snapshot = parse(BATTERY
                + "9,10011,l,sy,\"sync, \"\"quoted\"\"\",900,3,-1,-1\n"
                + "9,10011,l,wua,tag,2\n9,10011,l,wua,tag,3\n"
                + "9,10011,l,nt,100,200,300,400,0,0,0,0,0,0\n");
        assertEquals(3, snapshot.metrics.size());
        BatterySnapshot.Metric sync = snapshot.metrics.values().iterator().next();
        assertEquals("sync, \"quoted\"", sync.name);
        assertEquals(-1, sync.backgroundMs);
        for (BatterySnapshot.Metric metric : snapshot.metrics.values()) {
            if (metric.kind == BatterySnapshot.Kind.ALARM) assertEquals(5, metric.count);
            if (metric.kind == BatterySnapshot.Kind.NETWORK) assertEquals(1000, metric.bytes);
        }
    }

    @Test public void skipsUnknownRowsAndNonCumulativeCategories() throws Exception {
        BatterySnapshot snapshot = parse(BATTERY + "9,1,l,future,1,2,3\n9,1,u,wua,old,100\n");
        assertTrue(snapshot.metrics.isEmpty());
        assertEquals(0, snapshot.malformedRows);
    }

    @Test public void marksMalformedMetricsInsteadOfInventingZeros() throws Exception {
        BatterySnapshot snapshot = parse(BATTERY + "9,1,l,kwl,broken,NaN,2\n9,1,l,blem,-8,2\n");
        assertEquals(2, snapshot.malformedRows);
        assertTrue(snapshot.metrics.isEmpty());
    }

    @Test public void handlesKernelTimerAndLongCounters() throws Exception {
        BatterySnapshot snapshot = parse(BATTERY + "9,0,l,kwl,kernel,5000000000,30,0,600\n");
        assertEquals(5000000000L, snapshot.metrics.values().iterator().next().durationMs);
    }

    @Test(expected = IOException.class) public void rejectsPermissionDenial() throws Exception {
        parse("Permission Denial: can't dump BatteryStats from uid=10042\n");
    }

    @Test(expected = IOException.class) public void rejectsUnknownEnvelope() throws Exception { parse(BATTERY.replace("9,", "10,")); }
    @Test(expected = IOException.class) public void rejectsHumanReadableDump() throws Exception { parse("Battery History (2% used):\n"); }
    @Test(expected = IOException.class) public void rejectsMultipleSessions() throws Exception { parse(BATTERY + BATTERY); }
    @Test(expected = IOException.class) public void rejectsBrokenBatteryRecord() throws Exception { parse("9,0,l,bt,3,1\n"); }
    @Test(expected = IOException.class) public void rejectsImpossibleBatteryTimes() throws Exception { parse(BATTERY.replace(",3600000,600000,", ",300000,600000,")); }
    @Test(expected = IOException.class) public void rejectsOversizedLine() throws Exception { parse(BATTERY + new String(new char[65537]).replace('\0', 'x')); }
}
