#!/usr/bin/env python3
"""The CodeQL config skips exactly the third-party sources the native build fetches.

    python3 -m unittest discover -s scripts -p 'codeql_config_test.py' -t scripts
"""
import re
import unittest
from pathlib import Path

import check_source_urls as sources

ROOT = Path(__file__).resolve().parent.parent
CONFIG = (ROOT / ".github/codeql/codeql-config.yml").read_text(encoding="utf-8")
WORKFLOW = (ROOT / ".github/workflows/codeql.yml").read_text(encoding="utf-8")
THIRD_PARTY = (ROOT / "THIRD_PARTY.md").read_text(encoding="utf-8")


def fetched_names():
    names = set()
    for cmake in ("app/src/main/cpp/CMakeLists.txt", "app/src/test/cpp/CMakeLists.txt"):
        names.update(name for name, _, _ in sources.pinned_sources((ROOT / cmake).read_text(encoding="utf-8")))
    return names


def ignored_names():
    return set(re.findall(r"^\s*-\s*['\"]?app/\.cxx/\*\*/_deps/(\w+)-src/\*\*['\"]?\s*$", CONFIG, re.MULTILINE))


class CodeqlConfigTest(unittest.TestCase):
    def test_it_ignores_each_fetched_source_and_nothing_else(self):
        self.assertEqual(fetched_names(), ignored_names())

    def test_every_ignored_source_is_attributed_in_third_party(self):
        for name in sorted(ignored_names()):
            with self.subTest(name=name):
                self.assertRegex(THIRD_PARTY.lower(), rf"\b(lib)?{name}\b")

    def test_the_cpp_analysis_reads_the_config(self):
        self.assertIn("config-file: ./.github/codeql/codeql-config.yml", WORKFLOW)


if __name__ == "__main__":
    unittest.main()
