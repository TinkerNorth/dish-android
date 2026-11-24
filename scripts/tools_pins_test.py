#!/usr/bin/env python3
"""The pins repeated across scripts/tools.sh, the shared workflows and THIRD_PARTY.md agree.

    python3 -m unittest discover -s scripts -p 'tools_pins_test.py' -t scripts
"""
import re
import unittest
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent


def read(path):
    return (ROOT / path).read_text(encoding="utf-8")


def assigned(text, name):
    match = re.search(rf'^\s*{name}[=:]\s*"([^"]+)"', text, re.MULTILINE)
    return match.group(1) if match else None


TOOLS = read("scripts/tools.sh")
SECURITY = read(".github/workflows/_security.yml")
HARDEN = read(".github/workflows/_release_harden.yml")
THIRD_PARTY = read("THIRD_PARTY.md")
RELEASE = read(".github/workflows/release.yml")
SECURITY_DOC = read("SECURITY.md")


class ToolPinsTest(unittest.TestCase):
    def test_osv_scanner_version_is_the_same_in_the_script_and_the_workflow(self):
        self.assertEqual(assigned(TOOLS, "OSV_SCANNER_VERSION"), assigned(SECURITY, "OSV_SCANNER_VERSION"))

    def test_osv_scanner_linux_digest_is_the_workflows(self):
        self.assertIn(assigned(SECURITY, "OSV_SCANNER_SHA256"), TOOLS)

    def test_gitleaks_version_is_the_same_in_the_script_and_the_workflow(self):
        self.assertEqual(assigned(TOOLS, "GITLEAKS_VERSION"), assigned(SECURITY, "GITLEAKS_VERSION"))

    def test_gitleaks_linux_digest_is_the_workflows(self):
        self.assertIn(assigned(SECURITY, "GITLEAKS_SHA256"), TOOLS)

    def test_pyyaml_version_is_the_same_in_the_script_and_the_workflow(self):
        self.assertEqual(assigned(TOOLS, "PYYAML_VERSION"), assigned(SECURITY, "PYYAML_VERSION"))

    def test_third_party_names_every_tool_the_gates_and_the_release_run(self):
        pins = {
            "cosign": assigned(HARDEN, "COSIGN_VERSION"),
            "Syft": assigned(HARDEN, "SYFT_VERSION"),
            "Grype": assigned(HARDEN, "GRYPE_VERSION"),
            "OSV-Scanner": assigned(SECURITY, "OSV_SCANNER_VERSION"),
            "gitleaks": assigned(SECURITY, "GITLEAKS_VERSION"),
            "clang-format": assigned(TOOLS, "CLANG_FORMAT_VERSION"),
            "clang-tidy": assigned(TOOLS, "CLANG_TIDY_VERSION"),
        }
        for tool, version in pins.items():
            with self.subTest(tool=tool):
                self.assertIsNotNone(version)
                self.assertRegex(THIRD_PARTY, rf"\*\*{re.escape(tool)}\*\* \(`{re.escape(version)}`")


class ProvenanceRecipeTest(unittest.TestCase):
    def test_the_recipe_names_the_generator_commit_release_yml_pins(self):
        pinned = re.findall(r"generator_generic_slsa3\.yml@([0-9a-f]{40}) # v", RELEASE)

        self.assertEqual(1, len(pinned))
        self.assertIn(
            f"generator_generic_slsa3.yml@{pinned[0]}",
            SECURITY_DOC,
        )

    def test_the_recipe_verifies_with_cosign_not_slsa_verifier_for_new_releases(self):
        self.assertIn("cosign verify-blob-attestation", SECURITY_DOC)


if __name__ == "__main__":
    unittest.main()
