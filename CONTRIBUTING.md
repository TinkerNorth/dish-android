# Contributing to Dish Android

Thanks for your interest in improving the Android client! This document
captures the conventions that aren't obvious from skimming the code.

## Code of conduct

This project follows the [Contributor Covenant](CODE_OF_CONDUCT.md).
By participating, you agree to uphold it. Report unacceptable behavior
to `security@tinkernorth.com`.

## Getting set up

```bash
# 1) Install Android Studio Ladybug+, NDK, CMake 3.22.1+, JDK 17+
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

### Kotlin

- 4-space indent, ~120-column soft limit. `ktlint` and `detekt` are
  authoritative: run `./gradlew ktlintFormat` to autofix and
  `./gradlew detekt` to lint.
- `MainViewModel` exposes a single immutable `MainUiState` via a
  `StateFlow`. Don't introduce competing sources of truth: every UI-bound
  field belongs in `MainUiState`.
- Coroutines for async, `kotlinx.serialization` for JSON, Hilt for DI.

### JNI / C++

- C++17, four-space indent, 100-column soft limit. The same `.clang-format`
  ships with `satellite`, `dish-linux`, and this repo. Run
  `clang-format -i app/src/main/cpp/*.{cpp,h}` if you're unsure.
- The JNI is the **hot path**. No allocations per packet, no JNI calls
  from the input thread other than `sendReport`, no logging on the
  per-event path.

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
  OSV-Scanner, gitleaks secret scan, GitHub `dependency-review-action`
  (consumes the Gradle dependency graph), and the OWASP Dependency-Check
  Gradle plugin (`./gradlew dependencyCheckAnalyze`: fails on
  CVSS >= 7.0).
- `codeql.yml`: CodeQL `java-kotlin` and `cpp` analysis
  (security-extended + security-and-quality query packs).

If any step fails, the PR is blocked.

## Security

### Adding a vulnerability allowlist entry

Open a PR that adds an entry to [`.security/allowlist.yaml`](.security/allowlist.yaml)
(see the schema in the file). Required fields: `cve`, `reason`, `owner`,
`expires`. CI rejects the PR if any field is missing or `expires` is in
the past.

If the same finding is raised by OWASP Dependency-Check, mirror the
entry in [`.security/dependency-check-suppressions.xml`](.security/dependency-check-suppressions.xml)
using the [official suppression schema](https://jeremylong.github.io/DependencyCheck/general/suppression.html).

### Running security checks locally

```bash
# OWASP Dependency-Check (the same task CI runs)
./gradlew dependencyCheckAnalyze

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
  assembleDebug bundleRelease testDebugUnitTest dependencyCheckAnalyze
```

Commit the regenerated `gradle/verification-metadata.xml` in the same
PR as the dep bump. Reviewers should sanity-check the diff: a single
new dep should add ~1 line per transitive jar; a change to many
unrelated jars suggests something is off.

### Verifying a release artifact

Each GitHub Release ships the signed `dish-vX.Y.Z.apk` + `.aab`,
`*.sig`/`*.crt` (cosign keyless), `SHA256SUMS` + `SHA256SUMS.sig`/`*.crt`,
the SPDX + CycloneDX SBOMs, and `dish-android.intoto.jsonl` (SLSA L3).

```bash
sha256sum -c SHA256SUMS

cosign verify-blob \
  --certificate SHA256SUMS.crt \
  --signature   SHA256SUMS.sig \
  --certificate-identity-regexp '^https://github\.com/TinkerNorth/dish-android/\.github/workflows/release\.yml@refs/tags/v.*$' \
  --certificate-oidc-issuer 'https://token.actions.githubusercontent.com' \
  SHA256SUMS

slsa-verifier verify-artifact \
  --provenance-path dish-android.intoto.jsonl \
  --source-uri      github.com/TinkerNorth/dish-android \
  --source-tag      vX.Y.Z \
  dish-vX.Y.Z.apk
```

The full cross-repo verification recipe lives in
[`SECURITY.md`](SECURITY.md).

## Touching the hot path

The Kotlin → JNI → `sendto()` chain runs at gamepad polling rate and
must never block. If you're modifying `MainActivity.dispatchGenericMotionEvent`,
`GamepadInputProcessor`, `SatelliteNative`, or `satellite_jni.cpp::sendReport`:

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

The full opcode catalog and message layouts live in
[`docs/wire-format.md`](docs/wire-format.md).

Any change here must be coordinated with `dish-linux`, `dish-mac`, and
`satellite` in the same PR / release cycle.

## Reporting bugs

Use the issue templates under `.github/ISSUE_TEMPLATE/`. Include the
device model, Android version, and `adb logcat -s SatelliteJNI:*` output
covering the misbehavior.
