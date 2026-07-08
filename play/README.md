# Play Store assets

This directory holds the metadata, copy, and (eventually) screenshots/graphics required to submit Dish to Google Play.

## Structure

```
play/
  README.md                          ← you are here
  REVIEWER_NOTES.md                  ← test instructions for the Play reviewer (English only)
  DATA_SAFETY.md                     ← form answers for the Data Safety section
  PERMISSIONS_JUSTIFICATION.md       ← per-permission rationale for Play Console
  CONTENT_RATING.md                  ← notes for the IARC content-rating questionnaire
  metadata/
    android/
      en-US/                         ← English (US), canonical listing locale
        title.txt
        short_description.txt
        full_description.txt
        video.txt
        changelogs/
          10000.txt                  ← versionCode → release notes
      bs/                            ← Bosnian
      de-DE/                         ← German
      es-ES/                         ← Spanish (Spain)
      fr-FR/                         ← French (France)
      pt-BR/                         ← Brazilian Portuguese
```

## Tooling

The directory layout follows the [Fastlane Supply](https://docs.fastlane.tools/actions/supply/) convention so it can be uploaded with one command once a Play Console API key is configured:

```bash
fastlane supply --aab path/to/dish-1.0.0.aab --metadata_path play/metadata
```

Without Fastlane, the same files can be copy-pasted into the Play Console store-listing pages by hand.

## Visual assets: status

Screenshots and feature graphics are committed under
`play/metadata/android/<locale>/images/`. The store icon still needs to be
exported, and the screenshots need re-capturing before submission (see the
note below).

| Asset | Spec | Status |
|---|---|---|
| Store icon | 512x512 PNG, 32-bit, no alpha, 1 MB max | Not exported yet |
| Feature graphic | 1024x500 PNG or JPG | Present for every locale except `bs` (needs one before submission) |
| Phone screenshots | 2 to 8, 16:9 or 9:16, 320 to 3840 px short side | 9 per locale, committed (stale, see note) |
| 7-inch tablet screenshots | 16:9 or 9:16, recommended for tablet surfacing | 9 per locale, committed (stale, see note) |
| 10-inch tablet screenshots | 16:9 or 9:16, recommended for foldable/ChromeOS surfacing | 9 per locale, committed (stale, see note) |
| Promo video | YouTube URL, 30s max landscape, optional | Not set (`video.txt` is empty) |

Note: the committed screenshots were captured before the guided Setup flow
and the dashboard card rework, so some show screens that no longer exist.
Re-capture them before submission. See [SCREENSHOT-STRATEGY.md](SCREENSHOT-STRATEGY.md).

Fastlane Supply expects the images under `play/metadata/android/<locale>/images/`:

```
images/icon.png
images/featureGraphic.png
images/phoneScreenshots/01_dashboard.png
images/sevenInchScreenshots/01_dashboard.png
images/tenInchScreenshots/01_dashboard.png
```

## Locales

This listing is localized into the same six languages the app itself supports (`values/`, `values-bs/`, `values-de/`, `values-es/`, `values-fr/`, `values-pt-rBR/`). If you add more in-app locales later, mirror them here.

Note on Play Console locale codes: `pt-BR` matches Android's `pt-rBR`. German uses `de-DE`. Spanish uses `es-ES`; switch to `es-419` later if Latin-American Spanish coverage matters more than Iberian Spanish.

## Privacy policy

Hosted at `https://dish.tinkernorth.com/privacy/dish-android/`. The `PRIVACY.md` file at the repo root is the in-repo mirror. Paste the hosted URL into the Play Console "Privacy policy" field on the App content page.

## Screenshot pipeline

See [SCREENSHOT-STRATEGY.md](SCREENSHOT-STRATEGY.md) for how the screenshots in this directory were produced, including fixture data and emulator setup so they can be recreated.
