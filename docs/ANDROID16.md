# Android 16 implementation review

Original base: `ccb0904791ab20e35a187dbd2a4cf53643dcba19`. Integrated master: `83fdc6a142e0da1dcf131dcaa7d27969831b46b6`, merged concurrently through PR #1. Its WorkManager migration, resource/lint fixes, version catalog, signing setup and serialization protections are preserved.

## Findings addressed

| Finding in the base | Change and resulting behavior |
| --- | --- |
| SDK 33, AGP 7.2.2, Gradle 7.4.2; latest commit already documents a broken Android 14 update. | Compile/target SDK 36, AGP 8.13.2, Gradle 8.14.3, Java 17; explicit namespace, BuildConfig and AIDL generation. |
| `BatteryStatsProxy` depends on private parcel formats, reflective constructors and `getStatisticsStream`; the launcher attempts global hidden-API policy changes. | API 34+ redirects before any legacy setup/collection to a separate text-dump backend. No private parcel unmarshalling or global policy changes in that path. |
| Legacy collector starts background event services and jobs on boot. | API 34+ uses explicit, bounded collection; retires old jobs, skips legacy boot services, and makes existing widget providers render saved snapshots. |
| Targeting Android 16 enforces edge-to-edge and changes back dispatch. | Modern activity applies system bar, cutout and IME insets; standard AndroidX back handling has no legacy interception. Header content scrolls with the recycling list. |
| Shell collection and diagnostics can block the main thread. | New collection, parsing, persistence, imports and exports run on a ViewModel-owned executor. Rotation keeps in-flight work and displayed data. |
| Large reports are unsafe as Binder strings. | Shizuku UserService returns a reliable pipe. Fixed commands have deadlines; output/import size is capped at 16 MiB. The service is unbound after each collection. |
| Reference subtraction can make resets look like negative durations. | New comparisons validate epoch, boot count, source, monotonic counters and disappeared entries. Incomplete reports cannot become baselines. |
| Broad media permissions and exported setup screens are unnecessary for the new flow. | Removes media-read permissions; setup/diagnostic activities become internal. New exports use the document picker; new records use atomic app-private storage. |
| Build instructions require unrelated publishing credentials and offer no GitHub APK pipeline. | Preserves master's conditional publishing plugin and version catalog; adds build/test/lint/APK and API 36 smoke-test jobs. Debug package is isolated from the installed original app. |

## Data contract

The parser follows [AOSP Android 16 BatteryStats.java](https://github.com/aosp-mirror/platform_frameworks_base/blob/android-16.0.0_r1/core/java/android/os/BatteryStats.java), specifically `dumpCheckinLocked()` and `printWakeLockCheckin()`. The envelope version is `9`; only cumulative `l` records are interpreted. Unknown record types are skipped for forward compatibility. Unknown envelope versions fail with a useful message.

- `bt`: on-battery realtime and uptime, total realtime, start clock, screen-off realtime/uptime.
- `m`: screen-on time.
- `uid`: all packages listed for an app ID; the numeric UID remains visible for secondary users.
- `wl`: partial timer `p`; background `bp` and actual/unpooled durations are kept separate.
- `kwl`: kernel wakelock timer and count, if present.
- `blem`: pooled scan time, start count, actual/background time and result count.
- `sy`, `jb`: sync/job time, count and optional background time.
- `wua`: wakeup alarm count, aggregating repeated tags under a shared UID.
- `nt`: mobile and Wi-Fi receive/transmit byte totals; excludes packet counters and Bluetooth bytes.

Durations are milliseconds at the parser boundary. Overview sleep is on-battery realtime minus on-battery uptime; screen-off awake uses Android's screen-off uptime directly. It does not subtract screen-on time from unrelated uptime or mix microseconds with milliseconds.

The collector uses `/system/bin/dumpsys -t 15 batterystats -c --charged`. [AOSP BatteryStatsService](https://github.com/aosp-mirror/platform_frameworks_base/blob/android-16.0.0_r1/services/core/java/com/android/server/am/BatteryStatsService.java) distinguishes this from `--checkin`, which can consume a saved report. It checks DUMP and usage access for direct calls. Shizuku runs the fixed command in a shell/root identity through its [documented UserService API](https://github.com/RikkaApps/Shizuku-API#userservice). The Binder method accepts no shell command or path from callers.

## Verification

Automated checks cover AOSP-style fixtures, pooled/actual/background separation, quoted CSV tags, shared-UID mapping, duplicate alarm tags, optional missing values, long counters, malformed records, permission errors, unsupported dumps, size limits, delta calculations, resets, reboots, source changes and disappearing counters. Instrumented tests exercise a real Android 16 dump through ADB-granted DUMP/usage access, imports, summary rendering, rotation and a recoverable missing-Shizuku error. The emulator job also saves a screenshot for visual inspection.

See GitHub Actions for the result of the exact commit you install. A passing parser fixture does not establish that every OEM emits the same records. Lint is collected with the existing legacy `abortOnError false` policy, so a green job is not a claim of a clean legacy lint report.

Before treating this as a stable release, validate on the intended physical phone:

1. Start Shizuku through wireless debugging, grant access and collect a snapshot. Compare raw exported counters with `adb shell dumpsys batterystats -c --charged` captured close in time.
2. Save a baseline; exercise the phone, Bluetooth/watch activity and screen-off time; refresh and compare. Verify UID/tag attribution and millisecond units.
3. Stop/restart Shizuku; deny permission; rotate while collecting; export and import the raw report. Prior data should remain visible after failed collection.
4. Reboot or reset Android's statistics, then refresh. Old baselines must not produce a plausible-looking delta.
5. Check gestures and three-button navigation, light/dark mode, landscape, split-screen and large fonts. Inspect widgets for a clear last-snapshot timestamp.
6. Verify foreground/background behavior and absence of unexpected BBS wakeups. This backend deliberately has no automatic screen-off/unplug capture.

The existing dashboard, master's WorkManager migration and private-API implementation are retained for API 24–33, including their remaining limitations. This branch is not a complete audit or rewrite of those historical paths.

## Platform references

- [Android 16 setup](https://developer.android.com/about/versions/16/setup-sdk)
- [Android 16 behavior changes for targeting apps](https://developer.android.com/about/versions/16/behavior-changes-16)
- [Android 16 behavior changes for all apps](https://developer.android.com/about/versions/16/behavior-changes-all)
- [AGP 8.13 compatibility](https://developer.android.com/build/releases/agp-8-13-0-release-notes)
