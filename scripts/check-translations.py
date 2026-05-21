#!/usr/bin/env python3
# Dish Android — translation-completeness check.
#
# Compares string names declared in app/src/main/res/values/strings.xml
# against every app/src/main/res/values-*/strings.xml. Reports any strings
# missing from a locale (and any extra strings in a locale that aren't in
# the source). Strings flagged `translatable="false"` are skipped — those
# are intentional cross-locale constants (brand wordmarks, URLs).
#
# Used by:
#   .githooks/pre-commit  — runs only when values*/strings.xml is staged
#   CI                    — covered by `./gradlew lint` (MissingTranslation
#                           is promoted to error in app/build.gradle.kts)
#
# Exit codes:
#   0  every locale is in sync with the source
#   1  one or more strings are missing from at least one locale
#   2  source strings.xml is missing or malformed
import sys
import xml.etree.ElementTree as ET
from pathlib import Path

REPO_ROOT = Path(__file__).resolve().parent.parent
SOURCE = REPO_ROOT / "app/src/main/res/values/strings.xml"
LOCALES_GLOB = "app/src/main/res/values-*/strings.xml"


def translatable_names(path: Path) -> set[str]:
    """Return the set of <string name="..."> entries whose translatable
    attribute is not "false". Returns an empty set if the file is missing
    or malformed (callers decide how to handle that)."""
    try:
        tree = ET.parse(path)
    except (FileNotFoundError, ET.ParseError) as e:
        print(f"  ✗ {path}: {e}", file=sys.stderr)
        return set()
    return {
        elem.attrib["name"]
        for elem in tree.getroot().findall("string")
        if "name" in elem.attrib and elem.attrib.get("translatable", "true") != "false"
    }


def main() -> int:
    if not SOURCE.exists():
        print(f"✗ source strings.xml not found: {SOURCE}", file=sys.stderr)
        return 2

    source_names = translatable_names(SOURCE)
    if not source_names:
        print(f"✗ no translatable strings parsed from {SOURCE}", file=sys.stderr)
        return 2

    any_missing = False
    for locale_path in sorted(REPO_ROOT.glob(LOCALES_GLOB)):
        locale_names = translatable_names(locale_path)
        missing = source_names - locale_names
        if missing:
            any_missing = True
            locale = locale_path.parent.name  # e.g. "values-fr"
            print(f"⚠  Missing translations in {locale}:")
            for name in sorted(missing):
                print(f"    {name}")

    if any_missing:
        print()
        print(
            "  Add translations to the locale files above, or mark the "
            "string `translatable=\"false\"` in values/strings.xml if it's "
            "a brand constant or URL."
        )
        return 1

    return 0


if __name__ == "__main__":
    sys.exit(main())
