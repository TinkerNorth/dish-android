# Changelog

All notable changes to the Dish Android client are documented in this
file. The format is loosely based on
[Keep a Changelog](https://keepachangelog.com/en/1.1.0/), and this
project follows [Semantic Versioning](https://semver.org/spec/v2.0.0.html)
once it reaches `v1.0.0`.

Cross-repo coordination: changes to the wire protocol or pairing flow
that require matching updates in `satellite`, `dish-linux`, or `dish-mac`
are marked `[wire-coordinated]`. Releases tagged in lockstep across the
four repos share a version number.

---

## [Unreleased]

### Added

- Firebase Crashlytics integration for crash + ANR reporting. Configured
  conditionally — builds without `app/google-services.json` skip the
  Firebase plugins so local development still works.
- `PRIVACY.md` describing data collection, processors, and user choices.
- `network_security_config.xml` denying cleartext traffic explicitly
  (the previous `usesCleartextTraffic` removal was implicit).
- `app/src/androidTest/` smoke test that launches `MainActivity` and
  asserts the process survives `onResume`.
- `StrictMode` thread + VM policy in debug builds, gated on
  `FLAG_DEBUGGABLE`.
- Mapping file (`mapping.txt`) is now shipped with every signed release,
  cosign-signed alongside the APK/AAB, so external de-obfuscation of
  prod stack traces is possible without Firebase access.

### Changed

- `versionCode` and `versionName` are now derived from CI environment
  variables (`DISH_VERSION_CODE` / `DISH_VERSION_NAME`), with a
  `git describe` fallback for local dev. The hardcoded `1` / `"1.0"`
  defaults remain only when neither signal is present.
- `release.yml` stages `mapping.txt` into `dist/` alongside the AAB and
  APK so it gets the same SHA256SUMS + cosign signature treatment.

### Notes

This is the first tracked release. Earlier history is captured in
`git log`; only the build/release/observability deltas listed above are
documented here because they're the ones a downstream consumer of the
binary cares about.

---

## [1.0.0] — TBD

Initial public release. Tag will be created when the Play Store internal
testing track is opened.

[Unreleased]: https://github.com/TinkerNorth/dish-android/compare/v1.0.0...HEAD
[1.0.0]: https://github.com/TinkerNorth/dish-android/releases/tag/v1.0.0
