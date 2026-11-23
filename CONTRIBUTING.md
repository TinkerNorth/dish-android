# Contributing to Dish Android

Thanks for contributing to the Android client. This document captures
the conventions that are not obvious from reading the code: the style
gates, the JNI hot-path rules, and what CI enforces.

## Code of conduct

This project follows the [Contributor Covenant](CODE_OF_CONDUCT.md).
By participating, you agree to uphold it. Report unacceptable behavior
to `security@tinkernorth.com`.

## Getting set up

```bash
# 1) Install an Android Studio that supports the AGP version pinned in
#    gradle/libs.versions.toml, plus the NDK (pinned as ndkVersion in
#    app/build.gradle.kts), CMake 3.22.1 and a JDK 17 or newer to start Gradle
# 2) Open the project in Android Studio (Gradle sync downloads deps)
# 3) Point git at the in-tree pre-commit hook
scripts/setup-hooks.sh
# 4) Run every CI gate against your worktree, in CI's order
scripts/ci-local.sh
```

`scripts/ci-local.sh` runs what the build job of `android-ci.yml` runs, through
the same scripts: the pinned clang-format over the JNI, the Play metadata and
translation checks, the release script tests, root-level `ktlintCheck` and
`detekt`, the three flavours' lint and unit tests, the C++ host tests, the
three debug APKs with their ABI check, and the unsigned minified release builds
(the GitHub APK, the Play APK and bundle, the Amazon APK) with the ABI check and
`scripts/check_jni_keeps.py`. A green run there is a green "Build, test, lint,
format" job. It needs `ANDROID_HOME`, a JDK to start Gradle and `python3`. A missing tool fails the run; `--allow-missing` turns that into a
notice, so a green run never hides a skipped gate.

Two JDKs run the build, and they are not the same one. The JDK on `JAVA_HOME` (Temurin
17 from `actions/setup-java` in CI) only runs `./gradlew`'s launcher. The Gradle daemon,
which runs the build and compiles the code, is a JDK 21 chosen by
`gradle/gradle-daemon-jvm.properties` (`toolchainVersion=21`) and downloaded on first use
through the `foojay-resolver-convention` plugin in `settings.gradle.kts`. The code is
compiled to Java 17 bytecode (`sourceCompatibility`, `targetCompatibility` and `jvmTarget`
in `app/build.gradle.kts` and `baselineprofile/build.gradle.kts`; there is no
`jvmToolchain`), so the shipped app does not depend on either.

| Flag | Effect |
|---|---|
| `--quick` | the fast gates only: format, metadata, translations, script tests, ktlint, detekt, JVM unit tests |
| `--with-instrumented` | also run the `integration` package on the attached device or emulator. Start an emulator with `-read-only -dns-server 8.8.8.8,1.1.1.1` so it reaches the network |
| `--with-gmd` | also run it on the `pixel6Api34` managed device |
| `--with-armv7` | off Linux, also run the 32-bit ARM host tests under qemu in the container; qemu is too slow on a laptop for the bench's 1 ms pacing, so this is for a machine you can leave alone |
| `--allow-missing` | a missing tool is a notice, not a failure |

The C++ host tests (`:app:nativeTest`, `scripts/native-tests.sh`) build on Linux
only, because they use `sem_timedwait` and `SO_BUSY_POLL`. On any other host
`scripts/ci-local.sh` runs the same CMake targets in an `ubuntu:24.04` container
through `scripts/native-tests-docker.sh`, so Docker is needed there; the 32-bit ARM
run under `qemu-arm` stays in CI unless you pass `--with-armv7`.

What `ci-local.sh` cannot run is listed in its header: the API 35 and API 28
emulator jobs of `android-ci.yml` (`--with-instrumented` is the local
counterpart of the first), CodeQL, `security.yml`, and, because they need a
secret, signing, the Crashlytics symbol upload, the baseline profile and the
store uploads.

The formatter is pinned once, as `CLANG_FORMAT_VERSION` in `scripts/tools.sh`,
and installed into `build/tools` by `scripts/check-format.sh`, which CI and
`ci-local.sh` both call (`scripts/check-format.sh --fix` rewrites the files). The
fleet's C++ repos move it together. A bump is a deliberate commit that reformats
the tree with it.

The pre-commit hook runs `clang-format -i` (autofix, re-stages) on staged JNI
C/C++ files and `scripts/check-translations.py` when a strings file under any
`app/src/*/res` is staged; it skips Kotlin to keep itself fast. CI re-runs both
and `ktlintCheck`, `detekt` and lint on the Kotlin tree, so anything that slips
locally fails the PR.

## Where code goes

The packages under `app/src/main/java/com/tinkernorth/dish` are laid out so the
kind of thing you are writing says where it goes; `docs/ARCHITECTURE.md` has the
rules and the reasons.

| You have... | It goes in |
|---|---|
| a pure decision, mapping or derivation with no Android view types | a reducer or mapper under `composer/` or beside the screen (`ui/<screen>/`), tested on the JVM |
| state owned from a socket, sensor, Bluetooth link or store | `source/` |
| SharedPreferences-backed keyed storage | `repository/` |
| the satellite boundary: JNI bindings, HTTPS, wire types | `core/` (`core/jni`, `core/net`, `core/model`) |
| the controller database's reading and its pure decisions | `core/controllerdb/`; fetching and storing it is `source/controllerdb/` |
| per-event code that must not allocate or block | `hotpath/`, and the C++ under `app/src/main/cpp` |
| a screen | an Activity in `ui/`, inflating a layout XML, navigated to through `DishNavigator` |
| anything testable in the JNI | one of the host-buildable files under `app/src/main/cpp`, with its googletest target in `app/src/test/cpp`; `satellite_jni.cpp` is Android-only glue |

Flavour code lives in `app/src/github`, `play` and `amazon`, with the code two
flavours share in `app/src/donate` and `app/src/storeUpdates` (wired in
`app/build.gradle.kts`). `docs/contract.md` maps the satellite protocol onto
these files.

## Touching the UI

Every size is a `@dimen`, every colour a `@color`, every word a `@string`, and
`DESIGN.md` is the reference for the tokens, the styles, the layout composites,
the navigation destinations and the distribution flavours. A layout that carries
a raw size or an undeclared dimen fails `LayoutResourceRulesTest`. Views are
defined in layout XML and inflated, never built in code; standard screens go
through `BaseGamepadHostActivity.setScaffoldContent`, and all navigation goes
through `DishNavigator`. A value that is missing from `DESIGN.md` is either
deliberately one-off (the file says where) or new drift: name it there and route
the call site through it. `nav_graph.xml` and the destination table in
`DESIGN.md` are held equal by a test.

## Translations

Six locales ship: English, Bosnian, German, Spanish, French and Brazilian
Portuguese, each source set's `values/` and its own `values-<locale>/`. A new
string needs its entry in every locale of the source set it lives in, in the
same commit. Lint fails the build on a missing one (`MissingTranslation` is an
error), and `scripts/check-translations.py` runs the same check without Gradle:
the pre-commit hook, `ci-local.sh` and CI all call it.

## License headers

Every source file (`*.kt`, `*.cpp`, `*.h`) starts with the SPDX line, and a
new file carries the copyright line under it:

```
// SPDX-License-Identifier: LGPL-3.0-or-later
// Copyright (C) 2026 Dish contributors.
```

Don't introduce code under a different license: the project is
LGPL-3.0-or-later end-to-end (`LICENSE`, `COPYING.GPL3`, source headers).

## Style

`ktlint` and `detekt` (Kotlin) and `clang-format` (JNI) are authoritative
for layout; run `./gradlew ktlintFormat` and `clang-format -i` to fix.
This section is about what a formatter cannot check.

### Zero warnings

Warnings and lint findings are fixed, never silenced: the maintainer's rule is "we
don't want warnings, and we don't want to ignore any warnings or lint issues, instead
we want to do it right". Every Kotlin compiler warning and every lint warning fails the
build (`allWarningsAsErrors`, lint `warningsAsErrors` in `app/build.gradle.kts`), and a
ktlint or detekt finding fails CI. A new compiler, formatter, linter, SDK or scanner
version raises new findings; fix the code each one points at.

Do not add a suppression of any kind to get green: no `@Suppress`, `@SuppressLint`,
`tools:ignore`, `NOLINT`, `-Wno-*`, `noinspection`, lint or detekt baseline, `lint.xml`
severity change, rule switched off in `app/detekt.yml`, widened exclude, allowlist entry
for something that has a fix, or `continue-on-error`, and do not lower a
warnings-as-errors setting. Deprecations count: move to the replacement API, including
Gradle's "will be removed in Gradle 10" notices from this repo's own build scripts. A
finding that truly cannot be fixed correctly (a bug in the tool, third-party generated
code) is reported with its evidence in the pull request, not hidden.

Suppressions that predate this rule are inventoried, with the real fix for each, in
`app/src/test/PARITY.md`, "Suppressions". Remove one when you touch its file and the fix
is within reach; do not add to the list.

### Shape of the code

These rules are enforced in review, not by a gate. They come from the
Parchment library and are adapted where Kotlin, C++ or Android make the
literal form worse than the thing it is meant to achieve. They apply to
app, JNI and test code alike.

- **As immutable and as static as possible.** `val` over `var`; `const val`
  for a compile-time literal; `private` unless something outside needs it.
  In the JNI, `const` on every local and parameter that is not reassigned,
  and `constexpr` for a value known at compile time. A value that never
  changes is a named constant, never a literal in the middle of a function.
  A type that holds no state is a set of functions, not a class. Where a
  caller needs it as a value of an interface (a `DiffUtil.ItemCallback`, the
  no-op `UpdateNotices` of the store flavours), it is a class, constructed
  where it is needed, not an `object`.

- **Split values into simple, named steps.** One operation per line, with
  the result in a named `val` that says what it is, even when that reads
  longer (`PhysicalGamepadRegistry.isStalePlaceholderFor`):

  ```kotlin
  val isAnotherSlot = id != deviceId
  val isAPlaceholder = held.transitioning || held.needsReplug
  val isTheSameModel =
      held.vendorId == device.vendorId && held.productId == device.productId
  return isAnotherSlot && isAPlaceholder && !held.isUsbSynthetic && isTheSameModel
  ```

  not one six-term boolean. The names are the documentation, the debugger
  can show each value, and a test can pin each step.

- **One function, one flow.** When a function would hold two algorithms
  chosen by a condition, the condition dispatches to two named things that
  each do one thing, and the dispatcher does nothing else. In Kotlin that is
  usually a sealed type and an exhaustive `when` -- `RumbleTarget` and the
  `when` in `RumbleRouter.cancel` are the pattern -- so the compiler checks
  that every flow is handled. A guard clause is not an algorithm: do not
  invent indirection where there is only one flow.

- **A callback with a body gets a name.** A lambda is fine as a
  one-expression forward, and fine as an argument to an `inline` stdlib or
  coroutines function (`map`, `filter`, `let`, `update`), where the compiler
  inlines the body and there is no object to name. A lambda that carries an
  algorithm becomes a named function, so it can be found, read and tested on
  its own. A callback that is *stored* rather than called immediately prefers
  a function reference:

  ```kotlin
  btConnections.start(::refreshTransports)
  ```

  This is Parchment's "no anonymous methods" narrowed to what Kotlin can
  express. `launch`, `async`, `withContext`, `apply`, `run` and `flow` take
  *receiver* function types, which the language does not allow as a
  supertype, so for those there is no named form to prefer.

- **No singletons.** A stateless helper is a top-level `internal fun` in the
  file that owns it. Kotlin compiles a top-level function to a
  `public static final` method on the file class, called with
  `invokestatic`. An `object` adds a class with a private constructor and an
  `INSTANCE` field built in its static initializer, and each of its
  functions is an instance method: final, so there is no virtual dispatch
  either way, but every call loads `INSTANCE` first. A `companion object` is
  an `object` too: it holds the class's named constants, and a function that
  belongs with the class is a top-level function beside it
  (`parseUpdateVersion`, not `UpdateVersion.parse`). An `object` is
  justified only where something genuinely requires a single static entry
  point:

  - a `@JvmStatic` bridge that native code calls (`BluetoothGamepadBridge`,
    `MoonlightGamepadBridge`, `RumbleBridge`, `FeedbackBridge`,
    `MicMuteBridge`, `SpeakerAudioBridge`), holding the one target its
    upcalls deliver to,
  - a framework that demands it: a Hilt `@Module` of `@Provides` functions
    (`AppModule`), which Dagger reaches through `INSTANCE` rather than
    constructing a module of its own,
  - a sealed-hierarchy case with no payload (`RumbleTarget.Phone`, the
    `data object`s),
  - a namespace of named constants that the call site reads better for
    (`EnetProtocol`, `BatteryValidator`, a class's `companion object` of
    `const val`s and immutable values such as `CapabilitySet.EMPTY`): a
    `const val` is inlined at every use, so reading one never touches the
    object; it is the *functions* on an object, and its non-`const` values,
    that are reached through its instance,
  - a process-wide holder that owns state by definition:
    `HotPathBenchController`, whose one job is to be the single thing a
    broadcast toggles; `DishApplication.nativeLoadFailed`, the one answer to
    whether this process loaded its native library; and the instrumented
    tests' `AppSingletons`, which holds the app process's one Hilt graph,
    grabbed once per instrumentation run because grabbing it again would
    relaunch the app and wipe its preferences mid-suite,
  - a holder of `external fun` declarations (`SessionNative`,
    `SlotReportNative` and the other `core/jni` objects): the C symbol each
    binds to spells out the class name (`Java_..._SessionNative_openSocket`),
    so the object is the JNI contract, and it carries no state of its own.

  Hilt `@Singleton` bindings are a different thing and are fine: they are
  graph-scoped, injected, and replaceable in a test.

- **Member naming.** Kotlin properties carry no prefix, apart from the
  leading underscore of Kotlin's backing-property convention (`_uiState`
  behind the public `uiState`). A C++ class member in the JNI carries a
  trailing underscore (`mtx_`, `last_`, `primed_`), which is the same
  "state, not scratch" signal at the point of use. Keep it; do not mix in
  `m_`.

- **Prefer a test to a comment.** Behaviour that needs explaining gets a test
  named for the behaviour. A comment is the last resort for a constraint that
  genuinely cannot be tested -- a platform quirk, a build-tool limitation, a
  wire-format byte layout, a lock order -- states why in one or two lines,
  and never narrates what the next line does. Before writing a comment, ask
  whether a test could pin the behaviour instead; then write the test.

  Exempt, because the constraint is untestable by construction: the usage
  headers in `scripts/` and `.github/workflows/`, and
  `app/proguard-rules.pro`, where a keep rule's comment explains what would
  otherwise be shrunk away.

### Kotlin

- 4-space indent, ~120-column soft limit.
- `MainViewModel` exposes a single immutable `MainUiState` via a `StateFlow`.
  Don't introduce competing sources of truth: every UI-bound field belongs in
  `MainUiState`.
- Coroutines for async, `kotlinx.serialization` for JSON, Hilt for DI.
- All in-app navigation goes through `DishNavigator`; raw Intents are for
  external targets (system settings, browsers) only.
- Screens with reactive state or multi-step flows get a ViewModel exposing
  one immutable UiState `StateFlow`; static content screens may render
  directly. Derivation lives in composers/ViewModels, never in an Activity.
- Views are defined in layout XML and inflated; no programmatic View
  construction. Standard screens go through
  `BaseGamepadHostActivity.setScaffoldContent`.
- Pure decision and mapping logic belongs in a reducer or a mapper, not in a
  coordinator. [`docs/ARCHITECTURE.md`](docs/ARCHITECTURE.md) defines both
  and says where the line is.

### JNI / C++

- C++17, four-space indent, 100-column soft limit. The same `.clang-format`
  rules ship with `satellite`, `dish-windows`, `dish-linux`, and this repo.
- The JNI is the **hot path**. No allocations per packet, no JNI calls from
  the input thread other than the one that carries the event to native code
  (`PhysicalSlotNative.processGamepadMotionEvent` or
  `processGamepadKeyEvent` for a physical pad, a `SlotReportNative` send for
  the on-screen pad and a captured touchpad), no logging on the per-event
  path.
- The shape rules above apply, and the hot path is where they pay: a named
  `const bool` costs nothing at runtime and a small function inlines, but a
  per-event allocation does not. Where a shape rule and the hot path
  disagree, the hot path wins and the reason goes in a test name, not a
  comment.

### Resources (XML)

- No magic dimensions. A size is a `@dimen`, a colour a `@color`, a string a
  `@string`. A literal `16dp` in a layout is the same defect as a literal in
  the middle of a function.
- Resource invariants that matter are checked, not commented:
  `LayoutResourceRulesTest` fails on a layout that carries a raw size or
  references an undeclared dimen, and lint fails the build on a string
  missing from any shipped locale (see Translations).
- The controller-glyph vectors in `res/drawable/` that `tools/svg2vd.ps1`
  lists are generated from Kenney's input-prompt SVGs. Don't hand-edit them
  and don't apply style rules to them: change the SVG and regenerate.

## Tests

- `app/src/test`: JVM unit tests. No Robolectric and no Hilt in this source
  set. If a test seems to need either, the logic under test is in the wrong
  place: lift it into a reducer, a mapper or a top-level function first.
  `app/src/testGithub`, `app/src/testPlay` and `app/src/testAmazon` hold
  the tests of each flavour's own code, and `app/src/testDonate` (github and
  play) and `app/src/testStoreUpdates` (play and amazon) the tests of the
  code two flavours share, wired through `sourceSets` in
  `app/build.gradle.kts`, all under the same rules.
- `app/src/test/cpp`: googletest over the host-buildable JNI split, one
  target per layer; `app/src/test/cpp/CMakeLists.txt` is the list. `./gradlew
  :app:nativeTest` runs them on Linux, and `scripts/native-tests.sh` (or
  `scripts/native-tests-docker.sh` from another OS) runs them with the
  lock-statistics bench. CI also cross-compiles them for 32-bit ARM and runs
  them under `qemu-arm`, so a `size_t`, `long` or pointer-width assumption fails
  the way it would on `armeabi-v7a`.
- `NativeLookupKeepRulesTest` ties every member the JNI looks up by name to a
  rule in `app/proguard-rules.pro` (see "Adding a JNI lookup", under Touching
  the hot path).
- `app/src/androidTest`: instrumented tests for what genuinely needs a
  device; `app/src/androidTestGithub` and `app/src/androidTestAmazon` hold
  the ones about a single flavour.

### Test-driven, every flow

- **Write the test first and watch it fail.** A fix starts with a test that
  reproduces the bug on the current code; a feature starts with a test that
  describes the behaviour. Red, then green. Say in the PR which tests failed
  before the change; a test that never failed has not proven anything.

- **Cover every flow, not every line.** Line coverage is not the target.
  Each branch of each new condition, each early return, each end of a clamp
  or a loop, and each state a machine can be in when the new code runs gets
  its own case, named for the behaviour
  (`` `a re-enumerated pad replaces the held placeholder for the same model` ``).
  If a branch has no test, either it is dead and goes, or it needs one.

- **Assume nothing; validate with a test.** A claim about how the framework
  behaves -- what `InputDevice.getLightsManager` reports on API 33, what a
  `MotionEvent` carries once a view has captured the pointer, what SDL does
  with a Bluetooth pad before the rumble hint -- is confirmed by a test or a
  throwaway probe, never by reading a comment or a doc. Keep the test if it
  pins a dependency; delete it if it was only a probe.

- **Test at the level where the behaviour lives.** Decision logic in the
  reducer's test, derivation in the mapper's test, byte layout in
  `wire_encoders_test`, input processing in `gamepad_input_test`, and
  anything that genuinely needs the framework in `androidTest`. A test that
  reaches two layers down is testing the wrong thing.

- **Tests follow the same shape rules as the code.** Named functions instead
  of lambdas carrying algorithms, `val` locals, one asserted step at a time,
  and a named constant wherever a magic literal would need explaining.

## Branching & PRs

- All changes land on `main` via pull request: no direct pushes.
- Commits and PR descriptions name no tool. No `Co-Authored-By` trailer for an
  assistant, no "Generated with ..." footer: the author of record is the person
  who opened the PR, whatever helped write it.
- Use the PR template (`.github/pull_request_template.md`) to describe the
  change, the tests that failed before it, the manual check you ran (device or
  emulator, Android version, controller, host), and to call out anything that
  touches the wire protocol. Anything user-facing adds a line under
  `## [Unreleased]` in `CHANGELOG.md`.
- A change to the wire protocol or the pairing flow is marked
  `[wire-coordinated]` in the CHANGELOG and lands with the matching change in
  `satellite` and the sibling clients (see Touching the wire protocol).
- Keep commits focused; squash noisy fixup commits before review.

## What CI runs

Build + style:

- `android-ci.yml`, job "Build, test, lint, format": `scripts/check-format.sh`
  (the pinned clang-format over `app/src/main/cpp/`), `scripts/check_play_metadata.py`,
  `scripts/check-translations.py`, the release script tests (`python3 -m
  unittest discover -s scripts -p '*_test.py' -t scripts`), `./gradlew
  ktlintCheck`, `./gradlew detekt`, `./gradlew lintGithubDebug lintPlayDebug
  lintAmazonDebug`, `./gradlew testGithubDebugUnitTest testPlayDebugUnitTest
  testAmazonDebugUnitTest`, `./gradlew :app:nativeTest`, then
  `scripts/check-builds.sh debug` (the three debug APKs, uploaded as a CI
  artifact, each checked with `scripts/check_apk_abis.py` for its flavour's exact
  ABI set, `armeabi-v7a` included), `scripts/check-builds.sh release` (the
  unsigned minified GitHub, Play and Amazon builds, the Play bundle among them,
  each checked for its ABIs and, with `scripts/check_jni_keeps.py`, for every
  member native code resolves by name) and `scripts/check_sodium_ti_mode.py`
  (libsodium's 128-bit arithmetic on the 64-bit ABIs only).
- `android-ci.yml`, "Integration tests (emulator)": `connectedGithubDebugAndroidTest`
  over the `integration` package on an API 35 emulator.
- `android-ci.yml`, "C++ host tests (32-bit ARM, qemu)": the `app/src/test/cpp`
  targets built with `g++-arm-linux-gnueabihf` and run by `ctest` under
  `qemu-arm` (`scripts/native-tests.sh armv7`).
- `android-ci.yml`, "C++ host tests and bench smoke (x86_64)": the same targets
  with the runner's own g++, the lock-statistics bench and its smoke runs, and
  the measurements report tests over the JSON they wrote (`scripts/native-tests.sh
  host`).
- `android-ci.yml`, "Integration tests (API 28, armeabi-v7a through ARM
  translation)": `connectedAmazonDebugAndroidTest` over the `integration` package
  on an API 28 x86 emulator, which runs the amazon APK's `armeabi-v7a` library
  through ARM translation. First, `scripts/ci_arm_translation_proof.sh` fails
  unless the device ABI list names `armeabi-v7a` and no 64-bit ABI, a native
  bridge is set, the install has `primaryCpuAbi=armeabi-v7a`, the running app
  maps a translator and the ARM guest `liblog.so` and `libandroid.so`, and its
  log has no native-load failure.
- `version-consistency.yml`: the CHANGELOG's headings are well formed, and its
  newest released version has `play/metadata/android/<locale>/changelogs/<code>.txt`
  in every locale (`scripts/check_version_consistency.py`, then
  `scripts/check_play_metadata.py`). On a tag push the tag must be that version.
- `measurements.yml`: the host bench, by hand and from `release.yml` before
  publish; see [`tools/bench/README.md`](tools/bench/README.md).

Security gates (also blocking):

- `security.yml`, through the shared `_security.yml`: action-pin lint, vulnerability
  allowlist expiry, OSV-Scanner over `app/gradle.lockfile` and `Gemfile.lock`, and the gitleaks
  secret scan over the full history. On a pull request, dependency review fails on a new
  high-severity advisory (it needs the repository's Dependency graph setting, which is on).
  Release artifacts additionally get a Grype scan
  in the shared `_release_harden.yml`.
- `android-ci.yml`, "Release hardening (dry run)": the tag's whole hardening path (`_release_harden.yml`
  with `dry-run: true`) on the unsigned release files the build job just made: Grype, the SBOMs,
  `SHA256SUMS`, and a Sigstore bundle per file signed with a throwaway key and then checked. It
  cannot exercise the keyless identity, the code-scanning upload or the SLSA generator.
- `codeql.yml`: CodeQL `java-kotlin` and `cpp` analysis (security-extended +
  security-and-quality query packs). Its findings block and upload.

The Play workflows run only when started by hand or on a schedule, never on a pull
request: see "The Play workflows" under Cutting a release.

If any blocking step fails, the PR is blocked. `scripts/ci-local.sh` runs the
build and style gates locally in the same order.

## Security

### Adding a vulnerability allowlist entry

Open a PR that adds an entry to [`.security/allowlist.yaml`](.security/allowlist.yaml)
(see the schema in the file). Required fields: `cve`, `reason`, `owner`,
`expires`. CI rejects the PR if any field is missing or `expires` is in
the past.

### Running security checks locally

`scripts/ci-local.sh` runs the same gates `security.yml` runs, through the same shared scripts,
with OSV-Scanner and gitleaks fetched at the pinned versions by `scripts/tools.sh` (verified by
SHA-256, into `build/tools`):

```bash
sh scripts/check-action-pins.sh                      # every uses: is a 40-character SHA
python3 scripts/security-allowlist.py check          # the allowlist's fields and expiry (needs PyYAML)
bash scripts/osv-scan.sh -- .                        # OSV-Scanner over the lockfiles (OSV_SCANNER=<binary>)
gitleaks git --config=.gitleaks.toml --redact --exit-code=1 .
```

`.github/workflows/_security.yml`, `_release_harden.yml` and the three scripts above are the
fleet's shared copies, byte-identical in every repository. Never edit one here: a needed change
goes to satellite's copy first, then is copied everywhere.

### Dependency locking

The Maven dependencies that ship are the three flavors' release runtime classpaths
(`githubReleaseRuntimeClasspath`, `playReleaseRuntimeClasspath`, `amazonReleaseRuntimeClasspath`).
Their resolved versions are committed in `app/gradle.lockfile`, where OSV-Scanner reads them, so a
known-vulnerable library fails `security.yml`. A build resolves exactly the locked versions. After
any change that moves a resolved version (a bump in `gradle/libs.versions.toml`, a new dependency,
a plugin that adds one), regenerate the lock and commit it with the change:

```bash
./gradlew :app:resolveShippedDependencies --write-locks :app:generateLicenseManifest
```

The second task rewrites the in-app licence list (`app/src/main/assets/licenses/licenses.json`) from
the same classpaths; commit both files. A build that resolves a version or a module the lock lacks
fails (`LockMode.STRICT`; `kotlin-stdlib-common`, which `kotlin-stdlib` lists only as a constraint
with no artifact, is the one ignored module). `scripts/check-dependency-locks.sh` (run by CI and
`ci-local.sh`) regenerates both files and fails if that changed either, so a stale lockfile or
licence list cannot merge. A Dependabot pull request that bumps a
dependency will fail that check until someone runs the command above on its branch and pushes the
lockfile; this is the step that keeps the lock current. When OSV-Scanner reports a vulnerability,
upgrade the library and regenerate the lock; an `.security/allowlist.yaml` entry is for a finding
with no fixed version, with an expiry. Build plugins (the Gradle plugin classpath) are not locked.

### Gradle dependency verification

`gradle/verification-metadata.xml` is not committed: `.gitignore` lists it, and
neither CI nor `scripts/ci-local.sh` runs with one. Dependencies are pinned by
exact version in `gradle/libs.versions.toml` and scanned by OSV-Scanner, and the
release artifacts are signed. To look at what a dependency bump pulls in, generate
the file locally and diff it as a review aid, then delete it:

```bash
./gradlew --write-verification-metadata sha256 assembleDebug testGithubDebugUnitTest
```

### Verifying a release artifact

Each GitHub Release ships the signed `dish-X.Y.Z.apk` and `.aab`, a cosign bundle
(`<file>.sigstore.json`) for every file, `SHA256SUMS`, the SPDX and CycloneDX SBOMs, and
`dish-android.intoto.jsonl` (SLSA L3). The identity to verify against is the shared hardening
workflow at the tag, `_release_harden.yml@refs/tags/X.Y.Z`; releases up to 2.7.0 were signed by
`release.yml` and carry `.sig` and `.crt` files. The full recipe, both forms, is in
[`SECURITY.md`](SECURITY.md).

## Cutting a release

The version is the git tag and nothing else: `release.yml` derives versionName
and versionCode (`X*10000 + Y*100 + Z`, so `2.7.0` is `20700`) from it, and the
Gradle build derives the same from `git describe` for local builds. Tags are
bare `X.Y.Z`, no `v` prefix. `release.yml` runs on a tag push whose name matches
`[0-9]*.[0-9]*.[0-9]*`, or on a `workflow_dispatch` with a `tag` input for a tag
that already exists, started on that tag's ref
(`gh workflow run release.yml --ref X.Y.Z -f tag=X.Y.Z`): the signing certificate
names the ref the run was on, so a dispatch from `main` would sign as
`@refs/heads/main` and break every user's verification, and the shared hardening
workflow refuses to sign off a tag ref. A tag that is not exactly three numbers fails the release
before anything builds.

**Fleet order.** The website is tagged first. A client is tagged once
`https://dish.tinkernorth.com/controllers/v1/manifest.json` serves the new
controller database, so the database a release ships with is one the website
already publishes. Satellite and the sibling clients are tagged in the order a
wire change needs them.

**The bump pull request.** Before tagging, one pull request of its own ("Bump the
version to X.Y.Z and date the changelog") that holds:

- the CHANGELOG: `## [Unreleased]` becomes `## [X.Y.Z] - YYYY-MM-DD`, and a fresh
  empty `## [Unreleased]` goes above it;
- the Play release notes, one file per listing locale:
  `play/metadata/android/<locale>/changelogs/<versionCode>.txt` for en-US, de-DE,
  es-ES, fr-FR and pt-BR. Each is plain text of at most **500 characters**, which
  is Play's limit; `scripts/check_play_metadata.py` fails over it, and fails a
  locale that has no file for the version when `EXPECTED_VERSION_CODE` is set (the
  release workflow and `version-consistency.yml` set it);
- `PRIVACY.md`, `play/DATA_SAFETY.md` and `play/PERMISSIONS_JUSTIFICATION.md`
  first, if anything about data, permissions or network use changed (they mirror
  what is declared in the Play Console, so a change there is also made by hand in
  the console);
- `packaging/update-policy.json` only for a security fix or a protocol break
  (`minimumSupportedVersion`, in its own reviewed commit, never above the version
  being released).

`version-consistency.yml` fails a pull request whose newest CHANGELOG heading
lacks its Play notes. Merge it, then tag the squash commit on `main`:

```bash
git tag X.Y.Z <squash commit> && git push origin X.Y.Z
```

**What the tag publishes.** `release.yml` re-runs the security gates against the
tagged commit and the version checks against the tag, builds and signs the
GitHub APK, the Play bundle and the Amazon APK (the signing keystore, and
`google-services.json` for Crashlytics, come from repository secrets; the
keystore secrets are mandatory for a tag), and checks each artifact's ABIs and
that R8 kept what native code calls. Then:

- the GitHub Release is uploaded as a draft with the signed APK (`dish-X.Y.Z.apk`
  and the permanent `dish.apk`), the bundle, the R8 mapping files, the native
  debug symbols, `latest.json` (what the GitHub build's update check reads),
  `SHA256SUMS`, a cosign Sigstore bundle per file, the SPDX and CycloneDX SBOMs, the SLSA
  provenance and the bench measurements, and is flipped to published last so
  `releases/latest` never points at a half-published release;
- with `PLAY_SERVICE_ACCOUNT_JSON` set, fresh store screenshots are captured on CI
  emulators and the bundle is uploaded to the track the `PLAY_TRACK` repository
  variable names (production when unset) with the listing, the screenshots and
  the per-locale notes;
- with the Amazon secrets set, `scripts/amazon_appstore_publish.py` replaces the
  live APK in the Appstore.

**The channels today.** The GitHub APK and the Google Play bundle are signed with the same release
keystore (the 2.7.0 APK and bundle show one certificate, SHA-256
`20348e63be387f3ab1ba280b2031b08c4558f5c421c87dbae9cceb36577585d1`), but Google Play delivers
APKs signed with its own app-signing key, which this repository cannot read; it is under App
integrity in the Play Console. The Amazon Appstore channel is built and not published: the
`AMAZON_APPSTORE_CLIENT_ID` and `AMAZON_APPSTORE_CLIENT_SECRET` secrets and the
`AMAZON_APPSTORE_APP_ID` variable are not set, so for 2.7.0 `publish-amazon` ran and skipped its
upload, and the amazon APK exists only as the 90-day `amazon-appstore` workflow artifact. Nothing in
this repository says whether a version is live in the Amazon console, which the API cannot create
the first version of in any case.

The asset names `dish.apk` and `latest.json` are a permanent API: the website and
the app's update check read them by name. After the run, download `dish.apk`,
check it against `SHA256SUMS`, install it and pair once.

**The Play workflows.** Each is started by hand (or on a schedule) and none runs
on a pull request or from CI:

- `play-promote.yml` moves the release already on one track to another
  (internal to production by default) without rebuilding, since Play refuses a
  second upload of the same versionCode. Tag pushes land on `PLAY_TRACK`, so this
  is the way from internal testing to production.
- `play-listing.yml` syncs the text and graphics under `play/metadata` to the
  listing, as a dry run unless told otherwise.
- `play-products.yml` creates or updates the tip products from `play/products.json`
  (`play/BILLING.md`).
- `play-reviews.yml` writes a weekly digest of reviews and vitals to the job
  summary.
- `store-screenshots.yml` re-captures the screenshot set (by hand to refresh the
  committed baseline, and from `release.yml` on every tag).

They need `PLAY_SERVICE_ACCOUNT_JSON`, and the app record and its first build must
exist in the Play Console. The listing's content rating, data safety, permission
declarations and reviewer notes are declared in the Console by hand; the files
under `play/` mirror them and are updated in the same pull request as the change
they describe.

## Touching the hot path

The Kotlin -> JNI -> `sendto()` chain runs at gamepad polling rate and
must never block. If you're modifying `GamepadActivityHost`'s
`dispatchGenericMotionEvent` or `dispatchKeyEvent` (which `MainActivity` and
every `BaseGamepadHostActivity` forward to), the `core.jni` objects
(`PhysicalSlotNative`, `SlotReportNative`), or the native input path
(`gamepad_input.cpp`, `slot_table.cpp` and `send_path.cpp`, from `publishIfChanged`
down to `sendEncrypted`):

- No `withContext`, no `runBlocking`, no `Dispatchers.IO` on the send path.
- No heap allocation per event: the `XUSB_REPORT`, the payload and the
  packet are stack buffers.
- The send path takes no lock, and a change must not add one
  (`docs/ARCHITECTURE.md`, "The hot path"). A pad's slot and its session live in
  fixed tables the reporting thread reads through an epoch; the datagram is
  framed on the stack, then sealed and sent holding only its session's send gate,
  which keeps the counters in order on the wire, because the satellite drops a
  counter that is not above the last one it accepted. Never make a JVM upcall
  from the send path. The few mutexes that remain (`input.bindings`,
  `session.table`, `session.audio`) are `TrackedMutex`es, and `lock_laws_test`
  holds the list of locks each thread role may take to one that only shrinks.
- Preserve `IP_TOS = 0xB8` (DSCP EF) on the socket and `MSG_DONTWAIT` on
  every send.
- Measure it. `tools/bench/README.md` says how; `measurements.yml` records the
  host bench for every release.

### Adding a JNI lookup

Native code resolves some Kotlin members by name (`resolveStaticMethod` upcalls,
the `UsbInterfaceClaim` fields). R8 removes what no Kotlin code calls, and these
calls are made from C++, so a release build strips them and the bridge runs
silently dead: this shipped once, in every release build. Every such lookup needs
a keep rule in `app/proguard-rules.pro`. `NativeLookupKeepRulesTest` fails the
unit tests when one is missing, and `scripts/check_jni_keeps.py <apk|aab>` reads
the lookups out of `app/src/main/cpp` and checks, with the SDK's `dexdump`, that
each is in a minified build under its own class, name and descriptor. CI runs it
on the unsigned release builds of every pull request (`scripts/check-builds.sh
release`), and `release.yml` runs it on every artifact it ships. A failure there
means a missing rule; add the rule rather than loosening the check.

## Touching the wire protocol

The Android, iOS, macOS, Windows, and Linux clients all talk to the same
`satellite` server and must produce byte-identical traffic:

- AEAD: ChaCha20-Poly1305 IETF with a 12-byte nonce: one direction byte,
  seven zero bytes, then the monotonic send counter as 4 big-endian bytes.
- Packet layout: `token(4) | counter(4) | ciphertext+tag`, with the
  4-byte token as AAD.
- XUSB report: 12 bytes, little-endian, followed on a session settled at
  protocol 4 by the 4-byte extended-buttons word.
- Ports: discovery UDP 9879 (broadcast) + mDNS, pairing + REST over
  HTTPS 9443, streaming UDP 9876.

The full opcode catalog and message layouts live in the protocol
contract, `satellite/docs/contract.md`, in the TinkerNorth/satellite
repo. The Android-side mapping is [`docs/contract.md`](docs/contract.md), which
names the protocol version this client implements; a test holds it equal to
`DISH_PROTOCOL_CURRENT`.

Any change here must be coordinated with `satellite` and every sibling client in
the same release cycle, and is marked `[wire-coordinated]` in the CHANGELOG.

## Reporting bugs

Use the issue templates under `.github/ISSUE_TEMPLATE/`. The quickest way to
give us everything is the app's own *Report a problem*: it writes one file with the app, device, controller and
host facts, which you attach. PRIVACY.md section 2.7 says exactly what it holds.
For a crash or a native problem, add `adb logcat -s SatelliteJNI:*` output
covering the misbehavior.
