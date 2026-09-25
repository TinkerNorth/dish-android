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
# 1) Install Android Studio Otter (2025.2)+ for AGP 9.2, NDK, CMake 3.22.1, JDK 17+
# 2) Open the project in Android Studio (Gradle sync downloads deps)
# 3) Point git at the in-tree pre-commit hook
scripts/setup-hooks.sh
```

The pre-commit hook runs `clang-format -i` (autofix, re-stages) on staged
JNI C/C++ files and skips Kotlin to keep itself fast. CI runs `clang-format
--dry-run --Werror` on the JNI plus `ktlintCheck`, `detekt`, and `lint` on
the Kotlin tree, so anything that slips locally fails the PR.

## License headers

Every source file (`*.kt`, `*.cpp`, `*.h`) starts with:

```
// SPDX-License-Identifier: LGPL-3.0-or-later
// Copyright (C) 2026 Dish contributors.
```

New files must include both lines. Don't introduce code under a different
license: the project is LGPL-3.0-or-later end-to-end (`LICENSE`,
`COPYING.GPL3`, source headers).

## Style

`ktlint` and `detekt` (Kotlin) and `clang-format` (JNI) are authoritative
for layout; run `./gradlew ktlintFormat` and `clang-format -i` to fix.
This section is about what a formatter cannot check.

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
  A type that holds no state is a set of functions, not a class.

- **Split values into simple, named steps.** One operation per line, with
  the result in a named `val` that says what it is, even when that reads
  longer:

  ```kotlin
  val isAnotherSlot = entry.key != deviceId
  val isAPlaceholder = other.transitioning || other.needsReplug
  val isTheSameModel = other.vendorId == device.vendorId &&
      other.productId == device.productId
  return isAnotherSlot && isAPlaceholder && isTheSameModel
  ```

  not one five-term boolean. The names are the documentation, the debugger
  can show each value, and a test can pin each step.

- **One function, one flow.** When a function would hold two algorithms
  chosen by a condition, the condition dispatches to two named things that
  each do one thing, and the dispatcher does nothing else. In Kotlin that is
  usually a sealed type and an exhaustive `when` -- `RumbleTarget` in
  `FeedbackRouter` is the pattern -- so the compiler checks that every flow
  is handled. A guard clause is not an algorithm: do not invent indirection
  where there is only one flow.

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
  file that owns it. Kotlin emits a top-level function as a real
  `public static final` method on the file class, with no `INSTANCE` field,
  no private constructor and no virtual dispatch; an `object` emits all
  three. An `object` is justified only where something genuinely requires a
  single static entry point:

  - a `@JvmStatic` bridge that native code calls (`BluetoothGamepadBridge`,
    `MoonlightGamepadBridge`),
  - a framework that demands it (a Hilt `@Module`, a `Parcelable.CREATOR`),
  - a sealed-hierarchy case with no payload (`RumbleTarget.Phone`),
  - a namespace of `const val`s that the call site reads better for
    (`EnetProtocol`): Kotlin compiles a constant in an object to a
    `getstatic`, so those cost no instance at all; it is the *functions* on an
    object that pay for one,
  - a process-wide switch that owns state by definition
    (`HotPathBenchController`, whose one job is to be the single thing a
    broadcast toggles).

  Hilt `@Singleton` bindings are a different thing and are fine: they are
  graph-scoped, injected, and replaceable in a test.

- **Member naming.** Kotlin properties carry no prefix. The JNI uses a
  trailing underscore (`registry_`), which is the same "state, not scratch"
  signal at the point of use. Keep it; do not mix in `m_`.

- **Prefer a test to a comment.** Behaviour that needs explaining gets a test
  named for the behaviour. A comment is the last resort for a constraint that
  genuinely cannot be tested -- a platform quirk, a build-tool limitation, a
  wire-format byte layout, a lock order -- states why in one or two lines,
  and never narrates what the next line does. Before writing a comment, ask
  whether a test could pin the behaviour instead; then write the test.

  Exempt, because the constraint is untestable by construction: the pin-map
  headers in `.github/workflows/`, the usage headers in `scripts/`, and
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
  coordinator. [`docs/architecture.md`](docs/architecture.md) defines both
  and says where the line is.

### JNI / C++

- C++17, four-space indent, 100-column soft limit. The same `.clang-format`
  ships with `satellite`, `dish-linux`, and this repo.
- The JNI is the **hot path**. No allocations per packet, no JNI calls from
  the input thread other than `sendReport`, no logging on the per-event path.
- The shape rules above apply, and the hot path is where they pay: a named
  `const bool` costs nothing at runtime and a small function inlines, but a
  per-event allocation does not. Where a shape rule and the hot path
  disagree, the hot path wins and the reason goes in a test name, not a
  comment.

### Resources (XML)

- No magic dimensions. A size is a `@dimen`, a colour a `@color`, a string a
  `@string`. A literal `16dp` in a layout is the same defect as a literal in
  the middle of a function.
- Resource invariants that matter are tests, not comments: that every
  declared string is translated in every shipped locale, that an array's
  order matches the enum it indexes.
- `res/drawable/` vectors are generated from SVG by `tools/svg2vd.ps1`.
  Don't hand-edit them and don't apply style rules to them: change the SVG
  and regenerate.

## Tests

- `app/src/test`: JVM unit tests. No Robolectric and no Hilt in this source
  set. If a test seems to need either, the logic under test is in the wrong
  place: lift it into a reducer, a mapper or a top-level function first.
- `app/src/test/cpp`: googletest over the host-buildable JNI split
  (`gamepad_input`, `wire_encoders`, `audio_jitter`, `audio_codec`).
- `app/src/androidTest`: instrumented tests for what genuinely needs a
  device.

### Test-driven, every flow

- **Write the test first and watch it fail.** A fix starts with a test that
  reproduces the bug on the current code; a feature starts with a test that
  describes the behaviour. Red, then green. Say in the PR which tests failed
  before the change; a test that never failed has not proven anything.

- **Cover every flow, not every line.** Line coverage is not the target.
  Each branch of each new condition, each early return, each end of a clamp
  or a loop, and each state a machine can be in when the new code runs gets
  its own case, named for the behaviour
  (`deviceAdded_whenAPlaceholderForTheSameModelIsShowing_replacesIt`). If a
  branch has no test, either it is dead and goes, or it needs one.

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
- Use the PR template (`.github/pull_request_template.md`) to describe
  the change, the manual test matrix you ran (real device + emulator),
  and call out anything that touches the wire protocol.
- Keep commits focused; squash noisy fixup commits before review.

## What CI runs

Build + style:

- `android-ci.yml`: `clang-format` over `app/src/main/cpp/`,
  `./gradlew ktlintCheck`, `./gradlew detekt`, `./gradlew lint`,
  `./gradlew testDebugUnitTest`, `./gradlew assembleDebug` (uploads the
  APK as a CI artifact).

Security gates (also blocking):

- `security.yml`: action-pin lint, vulnerability allowlist expiry,
  OSV-Scanner, gitleaks secret scan, and GitHub `dependency-review-action`
  (consumes the Gradle dependency graph). Release artifacts additionally
  get a Grype scan in `release.yml`.
- `codeql.yml`: CodeQL `java-kotlin` and `cpp` analysis
  (security-extended + security-and-quality query packs).

If any step fails, the PR is blocked.

## Security

### Adding a vulnerability allowlist entry

Open a PR that adds an entry to [`.security/allowlist.yaml`](.security/allowlist.yaml)
(see the schema in the file). Required fields: `cve`, `reason`, `owner`,
`expires`. CI rejects the PR if any field is missing or `expires` is in
the past.

### Running security checks locally

```bash
# Gradle dependency verification: recompute checksums into
# gradle/verification-metadata.xml after a deliberate dep bump
./gradlew --write-verification-metadata sha256 help

# Action-pin lint
grep -REn '^\s*uses:' .github/workflows/ \
  | grep -vE '@[0-9a-f]{40}\b' \
  || echo "all pinned"

# Allowlist expiry
python3 - <<'PY'
import datetime, yaml, sys
data = yaml.safe_load(open('.security/allowlist.yaml').read()) or {}
for e in data.get('exceptions', []) or []:
    if datetime.date.fromisoformat(str(e['expires'])) < datetime.date.today():
        print('EXPIRED:', e); sys.exit(1)
PY

# OSV-Scanner
osv-scanner --recursive --skip-git .

# Gitleaks
gitleaks detect --no-banner --redact --source .
```

### Gradle dependency verification (`gradle/verification-metadata.xml`)

The file is not committed yet. The section below describes the
intended flow once a first generation pass lands; until then,
running these commands on a clean checkout still produces a useful
file you can diff as a review aid.

When you intentionally add or upgrade a dependency, regenerate the
verification metadata so transitive jar tampering fails resolution:

```bash
./gradlew --write-verification-metadata sha256 \
  assembleDebug bundleRelease testDebugUnitTest
```

Commit the regenerated `gradle/verification-metadata.xml` in the same
PR as the dep bump. Reviewers should sanity-check the diff: a single
new dep should add ~1 line per transitive jar; a change to many
unrelated jars suggests something is off.

### Verifying a release artifact

Each GitHub Release ships the signed `dish-X.Y.Z.apk` + `.aab`,
`*.sig`/`*.crt` (cosign keyless), `SHA256SUMS` + `SHA256SUMS.sig`/`*.crt`,
the SPDX + CycloneDX SBOMs, and `dish-android.intoto.jsonl` (SLSA L3).

```bash
sha256sum -c SHA256SUMS

cosign verify-blob \
  --certificate SHA256SUMS.crt \
  --signature   SHA256SUMS.sig \
  --certificate-identity-regexp '^https://github\.com/TinkerNorth/dish-android/\.github/workflows/release\.yml@refs/tags/v?[0-9].*$' \
  --certificate-oidc-issuer 'https://token.actions.githubusercontent.com' \
  SHA256SUMS

slsa-verifier verify-artifact \
  --provenance-path dish-android.intoto.jsonl \
  --source-uri      github.com/TinkerNorth/dish-android \
  --source-tag      X.Y.Z \
  dish-X.Y.Z.apk
```

The full cross-repo verification recipe lives in
[`SECURITY.md`](SECURITY.md).

## Touching the hot path

The Kotlin → JNI → `sendto()` chain runs at gamepad polling rate and
must never block. If you're modifying `MainActivity.dispatchGenericMotionEvent`,
the `core.jni` objects (`SlotReportNative`, `PhysicalSlotNative`), or the native
input path (`gamepad_input.cpp`,
`satellite_jni.cpp::sendReport`):

- No `withContext`, no `runBlocking`, no `Dispatchers.IO` on the send path.
- No allocations per event: use the preallocated `XUSB_REPORT`.
- The session map's lookup is the only lock allowed; hold it briefly.
- Preserve `IP_TOS = 0xB8` (DSCP EF) and `MSG_NOSIGNAL` on every send.

## Touching the wire protocol

The Android, macOS, and Linux clients all talk to the same `satellite`
server and must produce byte-identical traffic:

- AEAD: ChaCha20-Poly1305 IETF, 12-byte big-endian nonce derived from a
  monotonic counter.
- Packet layout: `token(4) | counter(4) | ciphertext+tag`, with the
  4-byte token as AAD.
- XUSB report: 12 bytes, little-endian.
- Ports: discovery UDP 9879 (broadcast) + mDNS, pairing + REST over
  HTTPS 9443, streaming UDP 9876.

The full opcode catalog and message layouts live in the protocol
contract, `satellite/docs/contract.md`, in the TinkerNorth/satellite
repo. The Android-side mapping is [`docs/contract.md`](docs/contract.md).

Any change here must be coordinated with `dish-linux`, `dish-mac`, and
`satellite` in the same PR / release cycle.

## Reporting bugs

Use the issue templates under `.github/ISSUE_TEMPLATE/`. Include the
device model, Android version, and `adb logcat -s SatelliteJNI:*` output
covering the misbehavior.
