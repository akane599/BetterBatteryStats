package com.asksven.betterbatterystats.modern;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.Reader;
import java.util.ArrayList;
import java.util.List;

import static com.asksven.betterbatterystats.modern.BatterySnapshot.Kind;
import static com.asksven.betterbatterystats.modern.BatterySnapshot.Metric;

/** AOSP Android 16 BatteryStats.dumpCheckinLocked(), checkin envelope version 9.
 * Only cumulative 'l' records are consumed. Unknown record types remain forward compatible.
 * Uses dumpsys batterystats -c --charged; --checkin can consume a historical checkin file.
 */
public final class CheckinParser {
    public static final int MAX_CHARS = 16 * 1024 * 1024;

    public BatterySnapshot parse(Reader input) throws IOException {
        BatterySnapshot snapshot = new BatterySnapshot();
        BufferedReader reader = new BufferedReader(input);
        boolean battery = false;
        int chars = 0;
        int lines = 0;
        String line;
        while ((line = reader.readLine()) != null) {
            chars += line.length() + 1;
            if (chars > MAX_CHARS || ++lines > 100000 || line.length() > 65536) {
                throw new IOException("Battery report exceeds the supported size.");
            }
            if (line.contains("Permission Denial") || line.contains("Permission denied")) {
                throw new IOException("Battery access denied. Connect Shizuku or grant DUMP and usage access with ADB.");
            }
            if (!line.startsWith("9,")) continue;
            List<String> row;
            try { row = csv(line); }
            catch (IllegalArgumentException e) { snapshot.malformedRows++; continue; }
            if (row.size() < 4) { snapshot.malformedRows++; continue; }
            String type = row.get(3);
            try {
                if ("i".equals(row.get(2)) && "uid".equals(type)) {
                    int uid = Math.toIntExact(number(row, 4));
                    String name = row.get(5);
                    String old = snapshot.packages.get(uid);
                    snapshot.packages.put(uid, old == null ? name : old + ", " + name);
                    continue;
                }
                if (!"l".equals(row.get(2))) continue;
                int uid = Math.toIntExact(number(row, 1));
                Metric metric = null;
                switch (type) {
                    case "bt":
                        if (battery) throw new IOException("Report contains multiple battery sessions. Export current stats with -c --charged.");
                        snapshot.startCount = number(row, 4);
                        snapshot.batteryRealtimeMs = number(row, 5);
                        snapshot.batteryUptimeMs = number(row, 6);
                        snapshot.totalRealtimeMs = number(row, 7);
                        snapshot.startClockTime = number(row, 9);
                        snapshot.screenOffRealtimeMs = number(row, 10);
                        snapshot.screenOffUptimeMs = number(row, 11);
                        if (snapshot.batteryUptimeMs > snapshot.batteryRealtimeMs
                                || snapshot.screenOffUptimeMs > snapshot.screenOffRealtimeMs
                                || snapshot.screenOffRealtimeMs > snapshot.batteryRealtimeMs) {
                            throw new IOException("Battery report contains inconsistent time counters.");
                        }
                        battery = true;
                        break;
                    case "m": snapshot.screenOnMs = number(row, 4); break;
                    case "wl":
                        // Each timer group is time,type,count,current,max,actual. Locate 'p';
                        // the optional background group must never be counted as a second lock.
                        int partial = row.indexOf("p");
                        if (partial < 6) throw new IllegalArgumentException("Missing partial timer");
                        metric = new Metric(Kind.WAKELOCK, uid, row.get(4),
                                number(row, partial - 1), number(row, partial + 1));
                        metric.actualMs = optional(row, partial + 4);
                        int background = row.indexOf("bp");
                        if (background >= 6) metric.backgroundMs = optional(row, background + 4);
                        break;
                    case "kwl":
                        metric = new Metric(Kind.KERNEL_WAKELOCK, uid, row.get(4), number(row, 5), number(row, 6));
                        break;
                    case "blem":
                        metric = new Metric(Kind.BLUETOOTH_SCAN, uid, "Bluetooth LE scan", number(row, 4), number(row, 5));
                        metric.actualMs = optional(row, 7);
                        metric.backgroundMs = optional(row, 8);
                        metric.scanResults = optional(row, 9);
                        break;
                    case "sy":
                    case "jb":
                        metric = new Metric("sy".equals(type) ? Kind.SYNC : Kind.JOB,
                                uid, row.get(4), number(row, 5), number(row, 6));
                        metric.backgroundMs = optional(row, 7);
                        break;
                    case "wua":
                        metric = new Metric(Kind.ALARM, uid, row.get(4), 0, number(row, 5));
                        break;
                    case "nt":
                        metric = new Metric(Kind.NETWORK, uid, "Mobile + Wi-Fi traffic", 0, 0);
                        metric.bytes = Math.addExact(Math.addExact(number(row, 4), number(row, 5)),
                                Math.addExact(number(row, 6), number(row, 7)));
                        break;
                    default: break;
                }
                if (metric != null) {
                    Metric old = snapshot.metrics.get(metric.key());
                    // Wakeup alarm tags can repeat in shared-UID package records.
                    if (old != null && metric.kind == Kind.ALARM) {
                        metric.count = Math.addExact(old.count, metric.count);
                    } else if (old != null) {
                        throw new IllegalArgumentException("Duplicate counter");
                    }
                    snapshot.metrics.put(metric.key(), metric);
                }
            } catch (IllegalArgumentException | IndexOutOfBoundsException | ArithmeticException e) {
                if ("bt".equals(type)) throw new IOException("Malformed battery-session record.", e);
                snapshot.malformedRows++;
            }
        }
        if (!battery) throw new IOException("No supported current battery statistics found. Use dumpsys batterystats -c --charged (checkin version 9).");
        return snapshot;
    }

    private static long number(List<String> row, int index) {
        long value = Long.parseLong(row.get(index));
        if (value < 0) throw new IllegalArgumentException("Negative counter");
        return value;
    }

    private static long optional(List<String> row, int index) {
        if (index >= row.size() || "-1".equals(row.get(index))) return -1;
        return number(row, index);
    }

    static List<String> csv(String line) {
        List<String> result = new ArrayList<>();
        StringBuilder field = new StringBuilder();
        boolean quoted = false;
        for (int i = 0; i < line.length(); i++) {
            char c = line.charAt(i);
            if (c == '"' && (quoted || field.length() == 0)) {
                if (quoted && i + 1 < line.length() && line.charAt(i + 1) == '"') {
                    field.append('"'); i++;
                } else quoted = !quoted;
            } else if (c == ',' && !quoted) {
                result.add(field.toString()); field.setLength(0);
            } else field.append(c);
        }
        if (quoted) throw new IllegalArgumentException("Unclosed CSV quote");
        result.add(field.toString());
        return result;
    }
}
