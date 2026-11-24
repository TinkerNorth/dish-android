#!/usr/bin/env python3
"""scripts/sbom.py: the components SBOM written from the build and what it is checked against.

    python3 -m unittest discover -s scripts -p 'sbom_test.py' -t scripts
"""
import json
import unittest

import sbom

LOCK = """# header
androidx.activity:activity-ktx:1.13.0=amazonReleaseRuntimeClasspath,githubReleaseRuntimeClasspath
com.android.billingclient:billing:9.1.0=playReleaseRuntimeClasspath
empty=
"""
LICENSES = {
    "libraries": [
        {"group": "androidx.activity", "artifact": "activity-ktx", "version": "1.13.0",
         "licenses": [{"name": "The Apache Software License, Version 2.0"}]},
        {"group": "com.android.billingclient", "artifact": "billing", "version": "9.1.0",
         "licenses": [{"name": "Android Software Development Kit License"}]},
    ]
}
CMAKE = """
FetchContent_Declare(
    libsodium
    URL      https://download.libsodium.org/libsodium/releases/libsodium-1.0.22.tar.gz
    URL_HASH SHA256=adbdd8f16149e81ac6078a03aca6fc03b592b89ef7b5ed83841c086191be3349
)
FetchContent_Declare(
    opus
    URL      https://downloads.xiph.org/releases/opus/opus-1.6.1.tar.gz
    URL_HASH SHA256=6ffcb593207be92584df15b32466ed64bbec99109f007c82205f0194572411a1
)
"""


def built():
    return sbom.document(LOCK, LICENSES, CMAKE, "2.7.0", "2026-11-23T00:00:00Z")


class DocumentTest(unittest.TestCase):
    def test_every_locked_library_is_a_package_with_its_version_and_purl(self):
        packages = {p["name"]: p for p in built()["packages"]}

        activity = packages["androidx.activity:activity-ktx"]
        self.assertEqual(activity["versionInfo"], "1.13.0")
        self.assertIn(
            {"referenceCategory": "PACKAGE-MANAGER", "referenceType": "purl",
             "referenceLocator": "pkg:maven/androidx.activity/activity-ktx@1.13.0"},
            activity["externalRefs"],
        )

    def test_the_vendored_native_libraries_carry_the_versions_cmake_fetches(self):
        packages = {p["name"]: p for p in built()["packages"]}

        self.assertEqual(packages["libsodium"]["versionInfo"], "1.0.22")
        self.assertEqual(packages["opus"]["versionInfo"], "1.6.1")

    def test_licence_names_map_to_spdx_identifiers_and_the_rest_stay_named(self):
        packages = {p["name"]: p for p in built()["packages"]}

        self.assertEqual(packages["androidx.activity:activity-ktx"]["licenseDeclared"], "Apache-2.0")
        self.assertEqual(packages["libsodium"]["licenseDeclared"], "ISC")
        self.assertEqual(packages["opus"]["licenseDeclared"], "BSD-3-Clause")
        billing = packages["com.android.billingclient:billing"]["licenseDeclared"]
        self.assertTrue(billing.startswith("LicenseRef-"))
        extracted = {i["licenseId"] for i in built()["hasExtractedLicensingInfos"]}
        self.assertIn(billing, extracted)

    def test_the_product_describes_every_package(self):
        doc = built()
        contained = {r["relatedSpdxElement"] for r in doc["relationships"] if r["relationshipType"] == "CONTAINS"}

        self.assertEqual(contained, {p["SPDXID"] for p in doc["packages"] if p["name"] != "dish-android"})
        self.assertEqual(doc["spdxVersion"], "SPDX-2.3")

    def test_the_document_is_json_a_second_build_repeats_exactly(self):
        self.assertEqual(json.dumps(built(), sort_keys=True), json.dumps(built(), sort_keys=True))


class CheckTest(unittest.TestCase):
    def test_a_document_built_from_the_same_inputs_has_no_problem(self):
        self.assertEqual(sbom.problems(built(), LOCK, LICENSES, CMAKE), [])

    def test_a_missing_locked_library_is_named(self):
        doc = built()
        doc["packages"] = [p for p in doc["packages"] if p["name"] != "com.android.billingclient:billing"]

        self.assertTrue(any("billing" in p for p in sbom.problems(doc, LOCK, LICENSES, CMAKE)))

    def test_a_library_the_licence_list_lacks_is_named(self):
        licences = {"libraries": LICENSES["libraries"][:1]}

        self.assertTrue(any("billing" in p for p in sbom.problems(built(), LOCK, licences, CMAKE)))

    def test_a_stale_native_version_is_named(self):
        stale = CMAKE.replace("opus-1.6.1", "opus-1.5.2")

        self.assertTrue(any("opus" in p for p in sbom.problems(built(), LOCK, LICENSES, stale)))


if __name__ == "__main__":
    unittest.main()
