# Parity: dish-android's own ledger

> **What this is.** The ledger for this client: what it does against the fleet's contracts, what
> is deferred and why, and every suppression and build-tool deprecation that is still in the tree.
> **A pull request that changes a row updates this file in the same change**, and a reviewer checks
> the row. Coverage is not mapped here: the suites under `app/src/test`, `app/src/androidTest` and
> `app/src/test/cpp` are the coverage truth and CI runs them. The cross-client matrix, with a column
> per client, is in `dish-mac`'s `Tests/PARITY.md`; this file holds Android's rows in the same
> vocabulary.
>
> Legend: works · shipped but not verified on real hardware · not available · deferred. Verified
> against the code on 2026-11-23.

## 1. What this client does

| Feature | State |
|---|---|
| Satellite protocol | 4 (`DISH_PROTOCOL_CURRENT`); offers 4, downgrades once inside 1 to 4 on a 409. `docs/contract.md` names the number and a test holds the two equal |
| Extended buttons (INPUT 17 bytes) | works on a session settled at 4; the cap is claimed only for a pad that sources the word |
| Controller database | bundled copy, fetched update from `dish.tinkernorth.com/controllers/v1/` after launch and every six hours, size and SHA-256 checked, opt-out in Settings |
| Diagnostics bundle | works; the host `snapshot` is the Satellite's own view from `GET /api/connections/{id}/diagnostics` (Satellite 2.3.0), with the capabilities fallback for older ones |
| Steam Controllers (USB and Bluetooth LE) | shipped, not verified on hardware (`docs/usb-direct-mode-followups.md`, items 8 to 10) |
| USB Direct: Xbox One, USB rumble | shipped, not verified on hardware (followups items 1 and 7) |
| USB Direct: Switch Pro | started by what the pad sends (`SwitchStart`): a pad the kernel started streams untouched, a cold one is walked through the hid-nintendo order. Run on a Pixel 8 (kernel-started pad) by the maintainer; a cold Pro on an Android device is not run, and neither are the 8BitDo adapters and the N64 Online pad (followups item 2) |
| Distribution | GitHub APK (self-update notice), Google Play AAB (Play Billing tips), Amazon Appstore APK (32-bit Fire tablets through ARM translation) |

## 2. Deferred

| Item | Why it waits |
|---|---|
| Baseline-profile generation on pull requests | `release.yml` generates it on a KVM emulator at tag time; a PR-time lane needs a second emulator job of about fifteen minutes. Nothing about it is checked before a tag |
| The 32-bit ARM qemu lane on a laptop | the pacing-sensitive tests (`LockLaws.TheRunReachedEveryRoleAndEveryPath`, `send_path_bench_smoke`) miss their deadlines under qemu on a Mac; `ci-local.sh --with-armv7` runs it in a container on a machine you can leave alone, CI runs it on a runner |
| Vulnerability scanning of the fetched C sources | libsodium and Opus are fetched by CMake at configure time and pinned by version and SHA-256. OSV-Scanner reads no manifest for them; at release the hardening's Grype scans them by CPE through the components SBOM (`scripts/sbom.py`), which a pull request's dry run also builds. CodeQL skips them (`.github/codeql/codeql-config.yml`). Between releases nothing watches their advisories |
| The Amazon Appstore channel | built by `release.yml` and not uploaded: the `AMAZON_APPSTORE_*` secrets and variable are not set, so nothing publishes there (CONTRIBUTING, "The channels today") |
| What Google Play signs with | the bundle is uploaded signed with the release key, but the key Play signs delivered APKs with is in the Play Console (App integrity) and not readable from here, so whether the Play build installs over the GitHub APK is unconfirmed |
| Locking the Gradle plugin classpath | only the shipped runtime classpaths are locked (`app/gradle.lockfile`); build plugins are not scanned for vulnerabilities |
| `play/` documents against the Console | `CONTENT_RATING.md`, `DATA_SAFETY.md`, `PERMISSIONS_JUSTIFICATION.md` and `REVIEWER_NOTES.md` mirror what is declared by hand in the Play Console; each change to them needs the same change there |

## 3. Suppressions

Every suppression that predates the rule "warnings are fixed, never silenced" (CONTRIBUTING, "Zero
warnings"), found with a search for `@Suppress`, `@SuppressLint`, `tools:ignore`, `NOLINT`,
`noinspection`, `-Wno-` and any `lint.xml`, lint or detekt baseline (none of the last three exists).
Each is deferred because its real fix is outside this wave's reach; the reason and the fix are in the
row. Remove a row's suppression in the change that does the fix.

| File | Rule | Real fix | Why it waits |
|---|---|---|---|
| `ui/common/ActivityExtensions.kt` (`overridePendingTransitionLegacy`) | `DEPRECATION` | `overrideActivityTransition` everywhere, which needs minSdk 34 | minSdk is a platform floor (24) and does not move without the maintainer |
| `source/connection/NsdServiceResolver.kt` (`resolveViaListener`, `legacyHostAddress`) | `DEPRECATION` | `registerServiceInfoCallback` and `hostAddresses`, minSdk 34 | floor |
| `source/system/WifiLinkSource.kt` | `DEPRECATION` | the network's `TransportInfo` on every device, minSdk 31 | floor |
| `source/bluetooth/AndroidSteamGatt.kt` (`legacyConnect`) | `DEPRECATION` | `connectGatt(settings, executor, callback)`, minSdk 37 | floor |
| `source/bluetooth/AndroidSteamGatt.kt` (`onCharacteristicChanged`) | `OVERRIDE_DEPRECATION`, `DEPRECATION` | the value-carrying overload only, minSdk 33 | floor |
| `source/bluetooth/AndroidSteamGatt.kt` (`legacyWriteDescriptor`, `legacyWriteCharacteristic`) | `DEPRECATION` | `writeDescriptor(descriptor, value)` and the characteristic form, minSdk 33 | floor |
| `hotpath/input/RumbleRouter.kt` | `DEPRECATION` | `VibrationEffect` on every device, minSdk 26 | floor |
| `hotpath/input/InputDeviceVibrators.kt` | `DEPRECATION` | `InputDevice.getVibratorManager`, minSdk 31 | floor |
| `app/src/test/.../LegacyVibratorApi.kt` (two helpers) | `DEPRECATION` | none of its own: it exists to test the two rows above, and goes with them | follows `RumbleRouter` and `InputDeviceVibrators` |
| `core/net/TofuTrustManager.kt` | `CustomX509TrustManager` | none short of a CA: a Satellite's certificate is self-signed and pinned on first use, which the platform's network security config cannot express | the pairing design; a lint rule that cannot be satisfied |
| `core/net/moonlight/MoonlightCrypto.kt` | `GetInstance` | a protocol revision upstream: the Moonlight pairing prescribes AES-128-ECB blobs | a protocol this client does not own |
| `core/jni/PhysicalSlotNative.kt`, `core/jni/SlotReportNative.kt`, `hotpath/input/MoonlightGamepadBridge.kt` | `LongParameterList` | a parameter object, which costs an allocation or a `GetFieldID` per field per event on the input hot path | the JNI signature is the contract with `satellite_jni.cpp`; changing hot-path code to satisfy detekt is not a safe change in this wave |
| `res/layout/activity_setup_usb.xml`, `activity_setup_bluetooth_host.xml`, `help_faq_row.xml` | `UseCompoundDrawables` (`tools:ignore`) | restructure each row so the glyph and label are not a `LinearLayout` of an `ImageView` and a `TextView` (a `ConstraintLayout` keeps the top alignment and the independent animation) | needs a before and after screenshot on a device for three screens; not done blind |
| `app/src/main/cpp/CMakeLists.txt` (`sodium_static`) | `-Wno-unused-function`, `-Wno-unused-variable` | none in this tree: the warnings are in libsodium's own sources, which this build compiles without its configure step | third-party code |
| `app/build.gradle.kts` (`lint { informational += ... }`) | `AndroidGradlePluginVersion`, `GradleDependency`, `NewerVersionAvailable` lowered to informational | none: they fire when someone else publishes a release and say nothing about this code; Dependabot acts on them weekly | a maintainer decision: gating on them turns a green pull request red with nothing changed |
| `app/detekt.yml` | thresholds raised (`LongMethod` 100, `CyclomaticComplexMethod` 20, `LargeClass` 1500, `TooManyFunctions` 60 and 20, `LongParameterList` 10, `ComplexCondition` 5, `ReturnCount` 5, `LoopWithTooManyJumpStatements` 3, `MaxLineLength` 140), `MagicNumber` off, `SwallowedException` ignoring `SerializationException` and `IllegalArgumentException` | meet detekt's defaults and name every constant | a tree-wide refactor; the configuration predates the rule |

Fixed in this wave, each proven by the lint run that used to need it: `BluetoothPadLinkReader`'s two
`@SuppressLint("MissingPermission")`, by making each read do its permission-gated calls inside its own
`try` that catches `SecurityException`.

## 4. Build tool deprecations

`./gradlew --warning-mode all help` prints two notices, both from the detekt Gradle plugin 1.23.8
(`io.gitlab.arturbosch.detekt`), which is the latest stable release; the only newer line is
`dev.detekt` 2.0.0-alpha.6, a pre-release.

| Notice | From | Upstream |
|---|---|---|
| `ReportingExtension.file(String)` is deprecated, removed in Gradle 10 | `DetektPlugin.apply` | https://github.com/detekt/detekt/issues/8452 (closed 2025-08-31; the 1.23.x plugin still makes the call) |
| `Configuration.setVisible(boolean)` is deprecated, removed in Gradle 11 | `DetektPlugin.configurePluginDependencies` | https://github.com/detekt/detekt/issues/9558 (closed 2026-08-07, three days after 2.0.0-alpha.6; the 1.23.x plugin still makes the call) |

Neither is in this repository's build scripts, so there is nothing to move to a replacement API here;
the fix is detekt 2.0 when it is stable, and `gradle/libs.versions.toml` moves to it then.
