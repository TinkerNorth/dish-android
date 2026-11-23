#!/usr/bin/env python3
"""scripts/check_version_consistency.py: the CHANGELOG heading, the tag and the version code.

    python3 -m unittest discover -s scripts -p 'check_version_consistency_test.py' -t scripts
"""
import unittest

import check_version_consistency as vc

CHANGELOG = """# Changelog

preamble

---

## [Unreleased]

Next.

## [2.7.0] - 2026-11-23

Text.

## [2.6.1] - 2026-11-19

Text.
"""


class VersionCodeTest(unittest.TestCase):
    def test_code_is_major_minor_patch_in_digit_pairs(self):
        self.assertEqual(vc.version_code("2.7.0"), 20700)
        self.assertEqual(vc.version_code("2.8.1"), 20801)
        self.assertEqual(vc.version_code("10.0.3"), 100003)

    def test_a_minor_or_patch_above_99_is_refused(self):
        with self.assertRaises(ValueError):
            vc.version_code("2.100.0")
        with self.assertRaises(ValueError):
            vc.version_code("2.0.100")

    def test_a_tag_that_is_not_three_plain_numbers_is_refused(self):
        for tag in ("v2.7.0", "2.7", "2.7.0-rc1", "2.07.0", "", "2.7.x"):
            with self.subTest(tag=tag), self.assertRaises(ValueError):
                vc.version_code(tag)


class ChangelogTest(unittest.TestCase):
    def test_newest_released_version_skips_unreleased(self):
        self.assertEqual(vc.newest_released(CHANGELOG), "2.7.0")

    def test_a_consistent_changelog_and_tag_pass(self):
        self.assertEqual(vc.problems(CHANGELOG, "2.7.0"), [])
        self.assertEqual(vc.problems(CHANGELOG, None), [])

    def test_a_tag_without_its_heading_fails(self):
        self.assertTrue(any("2.8.0" in p for p in vc.problems(CHANGELOG, "2.8.0")))

    def test_a_tag_older_than_the_newest_heading_fails(self):
        self.assertTrue(any("2.6.1" in p for p in vc.problems(CHANGELOG, "2.6.1")))

    def test_a_heading_without_a_date_fails(self):
        text = CHANGELOG.replace("## [2.7.0] - 2026-11-23", "## [2.7.0]")
        self.assertTrue(vc.problems(text, None))

    def test_headings_out_of_order_fail(self):
        text = CHANGELOG.replace("[2.7.0] - 2026-11-23", "[2.6.0] - 2026-11-23")
        self.assertTrue(vc.problems(text, None))

    def test_unreleased_must_come_first(self):
        text = "## [2.7.0] - 2026-11-23\n\n## [Unreleased]\n"
        self.assertTrue(vc.problems(text, None))

    def test_no_released_heading_is_a_problem(self):
        self.assertTrue(vc.problems("# Changelog\n\n## [Unreleased]\n", None))


if __name__ == "__main__":
    unittest.main()
