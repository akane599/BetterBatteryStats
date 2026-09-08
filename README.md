*Build* [![CircleCI](https://circleci.com/gh/asksven/BetterBatteryStats/tree/master.svg?style=svg)](https://circleci.com/gh/asksven/BetterBatteryStats/tree/master)

# License
BetterBatteryStats is an open source project unter the terms of the Apache 2.0 License. The license does not apply to the use of the names "BetterBatteryStats" and "Better Battery Stats", nor to the icon / artwork created for BetterBatteryStats.

# Build

| | |
|---|---|
| Android Gradle Plugin | 8.13.2 |
| Gradle | 8.14.3 |
| JDK | 17 or later |
| `compileSdk` / `targetSdk` | 36 (Android 16) |
| `minSdk` | 24 (Android 7.0 — the floor for the Shizuku API) |

Dependency and SDK versions live in the version catalog at
[`gradle/libs.versions.toml`](gradle/libs.versions.toml) — change them there, not in
`app/build.gradle`.

```
./gradlew assembleXdaeditionDebug     # debug build, no keystore needed
./gradlew assembleXdaeditionRelease   # release build; unsigned unless a keystore is configured
./gradlew test lint                   # unit tests and lint
```

A debug APK is also built by GitHub Actions for every push and pull request
([`.github/workflows/build.yml`](.github/workflows/build.yml)) and attached to the run as an
artifact, so a change can be installed without a local Android SDK. That workflow also runs the unit
tests and fails on any lint **error** or **fatal** (warnings do not block it). The CircleCI pipeline
still owns the signed release and Play publishing, because it has the keys.

Both flavours build without any of the CI secrets below: when a keystore or the Play
service-account file is absent, the corresponding signing config and the Play Publisher plugin are
simply not wired up.

## A note on what works on which Android release

The detailed statistics are read out of `com.android.internal.os.BatteryStatsImpl`, which the app
obtains as a parcel from the `batterystats` system service through reflection. Two platform changes
constrain that:

- **Android 9 (API 28)** introduced non-SDK interface restrictions. The reflective calls need those
  lifted, which on a rooted device means `adb shell settings put global hidden_api_policy 1`.
- **Android 14 (API 34)** moved `BatteryStatsImpl` from `framework.jar` to `services.jar`
  (`com.android.server.power.stats`), where an app process cannot reach it, and replaced
  `IBatteryStats.getStatistics()` / `getStatisticsStream()` with `getBatteryUsageStats()`. No
  permission and no amount of root restores the old path: the code is not in the process.

The app detects which of these applies and says so, in Diagnostics and above the stat list, rather
than showing an empty list. What can still be collected on Android 14 and later comes from parsing
`dumpsys batterystats`, which needs `android.permission.DUMP` — a permission no ordinary app can
hold.

### Getting the permissions

There are three ways, and the app uses whichever is available, in this order:

1. **Root.** Unchanged from before.
2. **[Shizuku](https://shizuku.rikka.app/).** The user starts Shizuku once (over adb, or from a
   rooted device) and authorises BetterBatteryStats. The app then runs `dumpsys` with adb-shell
   privileges through a Shizuku *user service* — see `app/src/main/java/.../shizuku/`. Diagnostics
   shows the Shizuku state and offers two buttons: one to request Shizuku's authorisation, one to
   have Shizuku run the `pm grant` calls below on the app's behalf.
3. **adb, by hand.**

```
adb shell pm grant com.asksven.betterbatterystats android.permission.DUMP
adb shell pm grant com.asksven.betterbatterystats android.permission.PACKAGE_USAGE_STATS
adb shell pm grant com.asksven.betterbatterystats android.permission.BATTERY_STATS
```

`PrivilegedShell` picks the backend; the dumpsys parsers go through it and do not know which one
served them. Diagnostics reports the choice.

## Signing

The signing configs read environment variables, and are only attached to a variant when the
keystore file they point at actually exists:

- `KEYSTORE_RELEASE` points to the release `.keystore` file (default `app/app.keystore`)
- `KEYSTORE_DEBUG` points to the debug `.keystore` file (default `app/app.keystore`)
- `KEY_ALIAS` defines the alias name
- `KEY_PASSWORD` is the key password
- `KEYSTORE_PASSWORD` is the keystore password

## R8

Release builds are minified and resource-shrunk. References are persisted as Java-serialized blobs
in SQLite, so renaming any class or field on that object graph would make every reference already
on a user's device unreadable after an update — silently. `app/proguard-rules.pro` keeps that graph,
and the `verifyPersistedModelNotObfuscated` task (which runs automatically after any
`assemble*Release`) reads R8's mapping output and fails the build if a persisted type or field was
renamed. If you touch the keep rules, that task is what tells you whether you broke them.

# Continuous Integration

The pipeline is defined in [`.circleci/config.yml`](.circleci/config.yml). It needs access to some
private settings.

## Google play publishing

### Publishing profile
The encrypted file (`sa-google-play.json-cipher`) is located in `/app`, and referenced by the gradle
build. The Play Publisher plugin is only applied when the decrypted `app/sa-google-play.json` is
present, so its absence does not affect local builds.

See also https://github.com/Triple-T/gradle-play-publisher.

### Deploy task

The Google Play publishing (to beta) runs from `master` after a manual approval step.

## Encrypt

`openssl enc -in infile -out infile-cipher -e -aes256 -k $KEY`

See also https://github.com/circleci/encrypted-files

## Decrypt (on CircleCI, as defined in `.circleci/config.yml` and using an env-variable `KEY`)

`openssl enc -in encrypted-cipher -out encrypted -d -aes256 -k $KEY`

## The signing keys

The environment variables `$KEYSTORE_RELEASE`, `$KEYSTORE_DEBUG`, `$KEY_ALIAS`, `$KEY_PASSWORD` and
`$KEYSTORE_PASSWORD` must be set.

These variables are set in `secret-env-plain` (not part of the project for obvious reasons).

In order to run your own build create a file `secret-env-plain` and set the variables:
```
export KEYSTORE_PASSWORD=<your-keystore-pwd>
export KEY_PASSWORD=<your-key-pwd>
export KEY_ALIAS=<your-key-alias>
export KEYSTORE_DEBUG=<name-of-debug-keystore>
export KEYSTORE_RELEASE=<name-of-release-keystore>
```
and then encrypt this file using `openssl aes-256-cbc -e -in secret-env-plain -out secret-env-cipher -k $KEY`

In the pipeline the decryption is done using the script `circleciscripts/decrypt_env_vars.sh` with
the `$KEY` stored in CircleCI's env vars.

As the signing keys are not in the github repo a script `circleciscripts/download_keystore.sh` does
the job of downloading and decrypting the keys at build-time. For that to happen the following
additional environment variables must be set:
- `$KEYSTORE_URI` a public URI from where the files can be downloaded using http
- `$KEY` the key to decrypt the keystores (same env var as for `google-services.json`)
