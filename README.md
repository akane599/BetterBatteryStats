# BetterBatteryStats — Android 16 modernization

An experimental modernization of this fork, based on commit `ccb0904` ("Updated to android 14, does not work yet"). The new dashboard uses Android's textual battery statistics rather than deserializing private `BatteryStatsImpl` parcels.

## What works in the new dashboard

- On-demand collection with **Shizuku**, **ADB-granted access**, or explicitly selected **root**.
- On-battery duration, screen-on time, screen-off awake time, and on-battery deep sleep.
- Searchable rankings for partial wakelocks, kernel wakelocks when reported, Bluetooth LE scans, syncs, jobs, wakeup alarms, and mobile + Wi-Fi traffic.
- Persistent manual baselines, with reset/reboot/counter-regression detection.
- Import current checkin reports and export either the displayed statistics or the original raw snapshot through Android's document picker.
- Timestamped widgets, system dark mode, a recycling list, and Android 16 window insets and standard back navigation.
- No background polling, persistent foreground service, system battery-statistics reset, or global hidden-API policy change in the new dashboard.

## Compatibility and scope

| Android | Behavior |
| --- | --- |
| 14–16 / API 34–36 | New dashboard replaces the broken private-API collection path. |
| 7–13 / API 24–33 | Existing dashboard remains available; legacy device behavior still needs regression testing. |
| 6 and earlier | No longer supported by this build; the selected Shizuku API requires API 24. |

This is **4.0.0-alpha01**, not a claim of full feature parity. Automatic unplug/screen-off baselines, legacy history graphs, CPU frequency states, process statistics, and watchdog automation have not been ported to the new backend. Widgets show the last manually collected snapshot. See [the review and validation notes](docs/ANDROID16.md) for the implementation boundaries and device checks.

## Install a test APK

Open a successful **Android build** run under this repository's [Actions](https://github.com/akane599/BetterBatteryStats/actions) and download the `BetterBatteryStats-Android16-debug` artifact. Extract its APK and install it.

The debug build is named **BBS Modern**, with application ID `com.asksven.betterbatterystats_xdaedition.modern`. It can be installed alongside the original XDA edition. It does not import the original app's private references or settings. CI debug signing keys can change between runs; an update signed by a different key requires uninstalling the previous test build. Use your own stable signing key for builds you intend to keep updating.

## Connect and compare

1. Start Shizuku, open BBS Modern, and tap **Connect Shizuku**. Allow BBS access. On a device without root, Shizuku needs to be restarted after reboot or if its server stops.
2. Tap **Refresh snapshot**. In the menu, choose **Use snapshot as baseline**.
3. Use the phone for the interval you want to measure. Refresh again and enable **Compare with baseline**.
4. If Android resets its statistics or a recorded counter disappears, save a new baseline. BBS does not clamp negative deltas into apparently valid results.

For ADB access, select **Data access → ADB** and run these commands for the installed variant:

```sh
adb shell pm grant com.asksven.betterbatterystats_xdaedition.modern android.permission.DUMP
adb shell appops set com.asksven.betterbatterystats_xdaedition.modern GET_USAGE_STATS allow
```

Alternatively, create a report on a computer and import the text file:

```sh
adb shell dumpsys batterystats -c --charged > bbs-checkin.txt
```

Use **`-c --charged`**, rather than `--checkin`: Android's `--checkin` path can return and consume its saved historical checkin report. Ordinary human-readable dumps and full bugreport ZIPs are not supported by this importer. Imported reports cannot be compared with a live device baseline because their device identity is unknown.

## Interpret the data

Counters describe activity, not measured battery energy. Pooled wakelock and Bluetooth scan times share accounting across overlapping timers. Actual and background durations are shown separately when Android reports them; they can overlap across apps. Scan starts and scan results are separate counters. Shared UIDs list all reported packages without assigning their shared activity to one package. An empty category means Android did not report matching records, not necessarily that no activity occurred.

Kernel statistics and some OEM-specific data may be unavailable even with Shizuku. Missing optional values are marked as not reported. Malformed rows are counted and prevent baseline comparisons.

Reports can contain package names and diagnostic tags. New snapshots are stored atomically in app-private, non-backed-up storage. Exported files go to the user-selected destination. The new collector has no upload or analytics path.

## Build and verify

Install JDK 17, Android SDK platform 36, and build tools 36.0.0. Set `ANDROID_HOME` or create `local.properties` with `sdk.dir`.

```sh
bash ./gradlew :app:assembleXdaeditionDebug :app:testXdaeditionDebugUnitTest :app:lintXdaeditionDebug
```

The project uses AGP 8.11.1 and Gradle 8.13. Ordinary builds do not require Google Play credentials or signing secrets. The old Play publishing plugin is removed; this branch does not publish to a store.

With an Android 16 emulator running:

```sh
bash ./gradlew :app:connectedXdaeditionDebugAndroidTest \
  -Pandroid.testInstrumentationRunnerArguments.class=com.asksven.betterbatterystats.modern.ModernStatsActivityTest
```

CI builds the debug APK, runs JVM tests and lint, and runs the new dashboard's smoke tests on API 36. Legacy lint findings are still reported without failing the build; inspect the uploaded reports. Release signing continues to accept `KEYSTORE_RELEASE`, `KEYSTORE_PASSWORD`, `KEY_ALIAS`, and `KEY_PASSWORD`.

## License

BetterBatteryStats is an open source project under the terms of the Apache 2.0 License. The license does not apply to the use of the names "BetterBatteryStats" and "Better Battery Stats", nor to the icon / artwork created for BetterBatteryStats. Existing attribution is preserved. Shizuku API is provided by RikkaApps under the MIT license.
