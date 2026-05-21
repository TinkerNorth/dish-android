# Dish Android — Release-readiness Handoff

This document tracks the open items required to ship Dish Android to the
Google Play Store and to keep the security/CI posture consistent across
the four TinkerNorth repos. It is referenced from
`.github/workflows/security.yml` and `.github/workflows/codeql.yml`
(search for "HANDOFF.md item N"); keep the numbering stable when adding
new entries, and only delete an item once the gap is fully closed.

When an item is closed, leave the heading and replace the body with a
single line: `Closed: <commit SHA> — <PR or short rationale>`. That way
the workflow comments still resolve to something readable.

---

## 1. Privacy policy hosting

Closed: hosted at
`https://dish.tinkernorth.com/privacy/dish-android/` (dish-website PR
#8). The in-repo `PRIVACY.md` mirrors the canonical hosted copy and
references the URL. Paste the hosted URL into the Play Console's
*App content → Privacy policy* field when the listing is created
(see item 7).

## 2. Crash-reporting opt-out toggle

Closed: shipped via `SettingsActivity` + `CrashReportingStore` +
`CrashReportingController`. Reachable from
*Connections → ⋮ → Settings → Send crash reports*. The switch persists
to `user_preferences.xml` (separate from the encrypted-keys
`connection_store.xml` so cloud backup CAN carry the preference across
device transfers) and the controller applies it via
`FirebaseCrashlytics.setCrashlyticsCollectionEnabled` on every
`ProcessLifecycleOwner.onStart` and whenever the user flips the switch.
`PRIVACY.md` §1 and §5 reflect the shipped behaviour.

## 3. Firebase project & `google-services.json`

**Status:** Firebase project `dish-tinkernorth` (project number
`62501381550`) created and `app/google-services.json` present locally
(gitignored — not committed). Local Gradle builds now apply the
`google-services` and `firebase-crashlytics` plugins; Crashlytics
auto-initializes at runtime; the in-app toggle is live end-to-end.

**Still needed for CI release builds:** store the file's contents as a
base64 GitHub Actions secret (`GOOGLE_SERVICES_JSON_BASE64`) so the
release workflow's `Decode google-services.json` step can materialize
it before `assembleRelease bundleRelease`. Without the secret, CI
builds still succeed but ship without Crashlytics wired — the
`-unsigned` / mapping-upload-to-Firebase paths require it.

```bash
# Generate the value for the GitHub secret:
base64 -w0 app/google-services.json | pbcopy   # macOS
base64 -w0 app/google-services.json            # Linux (paste manually)
```

Add via *Repository → Settings → Secrets and variables → Actions* →
*New repository secret* → name `GOOGLE_SERVICES_JSON_BASE64`. The
release workflow already knows how to decode it.

## 4. Versioning ramp

**Status:** `app/build.gradle.kts` now derives `versionCode` /
`versionName` from `DISH_VERSION_CODE` / `DISH_VERSION_NAME` env vars,
falling back to `git describe` for local dev. Default of `1` / `"1.0"`
only applies when neither is available.

**Needed:**

- Decide on the versionCode scheme. Recommended: `YYYYMMDDPP` (date +
  patch) or `MAJOR * 10000 + MINOR * 100 + PATCH`.
- Wire the chosen scheme into `release.yml`'s build step so tag pushes
  produce the right number automatically.
- Document the scheme in `CONTRIBUTING.md`.

## 5. GitHub Advanced Security (GHAS)

**Status:** Disabled. CodeQL and Dependency-Check SARIF uploads are
flagged `continue-on-error: true` so the workflows pass while GHAS is
unavailable.

**Needed:** enable GHAS on the repo (paid feature on private repos in
free orgs). Once enabled:

- Remove the `continue-on-error: true` line from
  `.github/workflows/codeql.yml` (currently around line 70).
- Remove the `continue-on-error: true` line from
  `.github/workflows/security.yml` (currently around line 94).
- Add branch protection requiring CodeQL + Dependency-Check status
  checks before merge to `main`.

Alternative path if GHAS is not adopted: make the repo public, which
unlocks branch protection + CodeQL upload on the free plan.

## 6. Verification metadata

**Status:** `CONTRIBUTING.md` documents the regeneration recipe but
`gradle/verification-metadata.xml` is not committed.

**Needed:** run
`./gradlew --write-verification-metadata sha256 assembleDebug bundleRelease testDebugUnitTest dependencyCheckAnalyze`
on a clean checkout, commit the resulting `gradle/verification-metadata.xml`,
and add a CI check that fails if a dependency resolves outside the
allowed checksums.

## 7. Play Console items

**Status:** No Play Console listing exists yet.

**Needed (one-time, for first release):**

- Create the app in the Play Console under `com.tinkernorth.dish`.
- Complete *App content*: privacy policy URL (item 1), ads declaration
  (none), content rating (PEGI 3 / ESRB Everyone is the target), target
  audience (16+ recommended), data safety form (use `PRIVACY.md` as the
  source of truth).
- Set up the *Internal testing* track and add the engineering team.
- Upload screenshots, feature graphic, short description, full
  description. Consider committing these to
  `fastlane/metadata/android/<locale>/` so the Play listing is managed
  as code.
- Enable *Pre-launch reports* so Google's device crawl exercises the
  app on real hardware before each release.

## 8. Required CI secrets

The release workflow needs these GitHub Actions secrets to produce a
signed release. The current `required-secrets` job fails the build if
any are missing on a tag push.

| Secret | Purpose |
|---|---|
| `KEYSTORE_BASE64` | base64 of the upload keystore (`.jks`). |
| `KEYSTORE_PASSWORD` | Keystore password. |
| `KEY_ALIAS` | Alias of the signing key. |
| `KEY_PASSWORD` | Key password. |
| `GOOGLE_SERVICES_JSON_BASE64` | base64 of `app/google-services.json` (see item 3). |
| `NVD_API_KEY` (optional) | Speeds up OWASP Dependency-Check NVD mirror sync. Already optional in the workflow. |

Add via *Repository → Settings → Secrets and variables → Actions*.

## 9. Localization review

**Status:** Six locales (`en`, `bs`, `de`, `es`, `fr`, `pt-rBR`). Source
strings: 165; translated strings per locale: 147 (some are
`translatable="false"`).

**Needed:** human translator pass on at least the top two non-English
locales before launch. The `<plurals>` block in `low_power_status_bound`
needs Slavic-rule verification for `bs`. Consider adding `it`, `ja`,
`ko`, `zh-CN`, `zh-TW`, `ru` once the product has traction.

## 10. Accessibility audit

**Status:** 4 of 9 layouts declare `contentDescription` on
`ImageView`/`ImageButton`. No TalkBack walkthrough has been done.

**Needed:** run `./gradlew lintRelease` and resolve every
`ContentDescription`, `SpeakableTextPresentCheck`, and
`TouchTargetSizeCheck` warning. Manually run TalkBack against the
Connections list, the pairing dialog, and the gamepad overlay. Verify
WCAG 2.1 AA contrast for `colorMuted` on `colorBackground`.
