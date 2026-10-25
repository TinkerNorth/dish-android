#!/usr/bin/env python3
"""Fails when a translatable string in one source set's values/ is missing from one of that source
set's own locales, for every app/src/*/res: main, each flavor and each shared flavor directory.
Mirrors lint's MissingTranslation without starting Gradle.

    scripts/check-translations.py
"""
import sys
import xml.etree.ElementTree as ET
from pathlib import Path

REPO_ROOT = Path(__file__).resolve().parent.parent
RES_GLOB = "app/src/*/res"


def string_files(values_dir: Path) -> list[Path]:
    return sorted(values_dir.glob("strings*.xml"))


def translatable_names(files: list[Path]) -> set[str]:
    names: set[str] = set()
    for path in files:
        try:
            root = ET.parse(path).getroot()
        except ET.ParseError as e:
            print(f"✗ {path}: {e}", file=sys.stderr)
            sys.exit(2)
        names |= {
            elem.attrib["name"]
            for elem in root.findall("string")
            if "name" in elem.attrib and elem.attrib.get("translatable", "true") != "false"
        }
    return names


def missing_by_locale(res: Path) -> dict[Path, set[str]]:
    source = translatable_names(string_files(res / "values"))
    missing: dict[Path, set[str]] = {}
    for locale_dir in sorted(res.glob("values-*")):
        files = string_files(locale_dir)
        if not files:
            continue
        gap = source - translatable_names(files)
        if gap:
            missing[locale_dir] = gap
    return missing


def main() -> int:
    res_dirs = [res for res in sorted(REPO_ROOT.glob(RES_GLOB)) if string_files(res / "values")]
    if not res_dirs:
        print(f"✗ no values/strings*.xml under {RES_GLOB}", file=sys.stderr)
        return 2

    any_missing = False
    for res in res_dirs:
        for locale_dir, names in missing_by_locale(res).items():
            any_missing = True
            print(f"⚠  Missing translations in {locale_dir.relative_to(REPO_ROOT).as_posix()}:")
            for name in sorted(names):
                print(f"    {name}")

    if any_missing:
        print()
        print(
            "  Add translations to the locale files above, or mark the "
            "string `translatable=\"false\"` in that source set's values/ if "
            "it's a brand constant or URL."
        )
        return 1

    return 0


if __name__ == "__main__":
    sys.exit(main())
