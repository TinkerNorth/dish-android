#!/usr/bin/env python3
"""The App Submission API flow against a scripted transport, so the order of calls, the headers
Amazon insists on (fileName, If-Match, the octet-stream body) and the failure stops are pinned
without an Amazon account."""
import json
import tempfile
import unittest
from pathlib import Path

import amazon_appstore_publish as pub

APP = "amzn1.devportal.mobileapp.test"
BASE = f"{pub.API_BASE}/{APP}"


class ScriptedTransport:
    """Answers (method, url) from a table, records every request, and fails loudly on a request
    the script did not plan for."""

    def __init__(self, answers):
        self.answers = answers
        self.calls = []

    def __call__(self, method, url, headers, body):
        self.calls.append((method, url, headers, body))
        key = (method, url)
        if key not in self.answers:
            raise AssertionError(f"unplanned request {method} {url}")
        status, headers_out, payload = self.answers[key]
        if isinstance(payload, (dict, list)):
            payload = json.dumps(payload).encode()
        return pub.Response(status, headers_out, payload)


def token_answer():
    return {("POST", pub.TOKEN_URL): (200, {}, {"access_token": "tok", "expires_in": 3600})}


class ReleaseNotes(unittest.TestCase):
    def test_reads_one_file_per_locale_that_has_the_version(self):
        with tempfile.TemporaryDirectory() as root:
            for locale, text in (("en-US", "New.\n"), ("de-DE", "Neu.")):
                d = Path(root, locale, "changelogs")
                d.mkdir(parents=True)
                (d / "20500.txt").write_text(text, encoding="utf-8")
            Path(root, "fr-FR", "changelogs").mkdir(parents=True)
            (Path(root, "fr-FR", "changelogs") / "20400.txt").write_text("old", encoding="utf-8")
            notes = pub.release_notes_for(root, "20500")
        self.assertEqual({"de-DE": "Neu.", "en-US": "New."}, notes)

    def test_a_missing_root_means_no_notes(self):
        self.assertEqual({}, pub.release_notes_for("/nowhere/at/all", "20500"))


class ListingMerge(unittest.TestCase):
    def test_only_recent_changes_change(self):
        listing = {"language": "en-US", "title": "Dish", "fullDescription": "Long", "shortDescription": "Short",
                   "featureBullets": ["a"], "keywords": ["k"], "recentChanges": "old"}
        merged = pub.listing_with_notes(listing, "new")
        self.assertEqual("new", merged["recentChanges"])
        self.assertEqual({k: v for k, v in listing.items() if k != "recentChanges"},
                         {k: v for k, v in merged.items() if k != "recentChanges"})
        self.assertEqual("old", listing["recentChanges"])


class ApkChoice(unittest.TestCase):
    def test_no_apk_means_upload(self):
        self.assertIsNone(pub.choose_apk_to_replace([]))

    def test_one_apk_is_the_one_to_replace(self):
        self.assertEqual({"id": "a1"}, pub.choose_apk_to_replace([{"id": "a1"}]))

    def test_two_apks_stop_the_run(self):
        with self.assertRaises(pub.PublishError):
            pub.choose_apk_to_replace([{"id": "a1", "name": "x"}, {"id": "a2", "name": "y"}])


class FullFlow(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory()
        self.apk = Path(self.tmp.name, "dish-2.5.0-amazon.apk")
        self.apk.write_bytes(b"PK\x03\x04fake")

    def tearDown(self):
        self.tmp.cleanup()

    def run_publish(self, answers, notes):
        transport = ScriptedTransport({**token_answer(), **answers})
        publisher = pub.Publisher("cid", "secret", APP, transport=transport, log=lambda *_: None)
        edit = publisher.publish(self.apk, notes)
        return transport, edit

    def test_replaces_the_live_apk_sets_notes_validates_and_commits_with_etags(self):
        answers = {
            ("GET", f"{BASE}/edits"): (200, {}, {"id": "e1", "status": "IN_PROGRESS"}),
            ("GET", f"{BASE}/edits/e1/apks"): (200, {}, [{"id": "a1", "name": "old.apk", "versionCode": 20400}]),
            ("GET", f"{BASE}/edits/e1/apks/a1"): (200, {"ETag": "apk-etag"}, {"id": "a1"}),
            ("PUT", f"{BASE}/edits/e1/apks/a1/replace"): (200, {}, {"id": "a1", "versionCode": 20500}),
            ("GET", f"{BASE}/edits/e1/listings"): (200, {}, {"listings": {"en-US": {}, "de-DE": {}}}),
            ("GET", f"{BASE}/edits/e1/listings/en-US"): (200, {"ETag": "en-etag"},
                                                         {"language": "en-US", "title": "Dish", "recentChanges": "old"}),
            ("PUT", f"{BASE}/edits/e1/listings/en-US"): (200, {}, {}),
            ("GET", f"{BASE}/edits/e1/listings/de-DE"): (200, {"ETag": "de-etag"},
                                                         {"language": "de-DE", "title": "Dish", "recentChanges": "alt"}),
            ("PUT", f"{BASE}/edits/e1/listings/de-DE"): (200, {}, {}),
            ("POST", f"{BASE}/edits/e1/validate"): (200, {}, {"id": "e1"}),
            ("GET", f"{BASE}/edits/e1"): (200, {"ETag": "edit-etag"}, {"id": "e1"}),
            ("POST", f"{BASE}/edits/e1/commit"): (200, {}, {"id": "e1", "status": "SUBMITTED"}),
        }
        transport, edit = self.run_publish(answers, {"en-US": "New.", "de-DE": "Neu."})
        self.assertEqual("e1", edit)

        token = transport.calls[0]
        self.assertEqual(("POST", pub.TOKEN_URL), token[:2])
        self.assertIn(b"grant_type=client_credentials", token[3])
        self.assertIn(b"scope=appstore%3A%3Aapps%3Areadwrite", token[3])

        replace = next(c for c in transport.calls if c[0] == "PUT" and c[1].endswith("/replace"))
        self.assertEqual("apk-etag", replace[2]["If-Match"])
        self.assertEqual("dish-2.5.0-amazon.apk", replace[2]["fileName"])
        self.assertEqual(pub.APK_CONTENT_TYPE, replace[2]["Content-Type"])
        self.assertEqual(b"PK\x03\x04fake", replace[3])
        self.assertEqual("Bearer tok", replace[2]["Authorization"])

        en = next(c for c in transport.calls if c[0] == "PUT" and c[1].endswith("/listings/en-US"))
        self.assertEqual("en-etag", en[2]["If-Match"])
        self.assertEqual({"language": "en-US", "title": "Dish", "recentChanges": "New."}, json.loads(en[3]))
        de = next(c for c in transport.calls if c[0] == "PUT" and c[1].endswith("/listings/de-DE"))
        self.assertEqual("Neu.", json.loads(de[3])["recentChanges"])

        commit = transport.calls[-1]
        self.assertEqual(("POST", f"{BASE}/edits/e1/commit"), commit[:2])
        self.assertEqual("edit-etag", commit[2]["If-Match"])
        validate_index = [c[1] for c in transport.calls].index(f"{BASE}/edits/e1/validate")
        self.assertLess(validate_index, len(transport.calls) - 1)
        self.assertFalse(any(c[0] == "POST" and c[1].endswith("/edits") for c in transport.calls))

    def test_creates_an_edit_when_none_is_open_and_uploads_when_the_app_has_no_apk(self):
        answers = {
            ("GET", f"{BASE}/edits"): (200, {}, b""),
            ("POST", f"{BASE}/edits"): (200, {}, {"id": "e9", "status": "IN_PROGRESS"}),
            ("GET", f"{BASE}/edits/e9/apks"): (200, {}, []),
            ("POST", f"{BASE}/edits/e9/apks/upload"): (200, {}, b""),
            ("GET", f"{BASE}/edits/e9/listings"): (200, {}, {"listings": {"en-US": {}}}),
            ("POST", f"{BASE}/edits/e9/validate"): (200, {}, {"id": "e9"}),
            ("GET", f"{BASE}/edits/e9"): (200, {"ETag": "e9-etag"}, {"id": "e9"}),
            ("POST", f"{BASE}/edits/e9/commit"): (200, {}, {"id": "e9", "status": "SUBMITTED"}),
        }
        transport, edit = self.run_publish(answers, {})
        self.assertEqual("e9", edit)
        upload = next(c for c in transport.calls if c[1].endswith("/apks/upload"))
        self.assertEqual("dish-2.5.0-amazon.apk", upload[2]["fileName"])
        self.assertNotIn("If-Match", upload[2])
        self.assertFalse(any(c[1].endswith("/listings/en-US") for c in transport.calls))

    def test_a_validation_failure_stops_before_the_commit(self):
        answers = {
            ("GET", f"{BASE}/edits"): (200, {}, {"id": "e1", "status": "IN_PROGRESS"}),
            ("GET", f"{BASE}/edits/e1/apks"): (200, {}, []),
            ("POST", f"{BASE}/edits/e1/apks/upload"): (200, {}, b""),
            ("GET", f"{BASE}/edits/e1/listings"): (200, {}, {"listings": {}}),
            ("POST", f"{BASE}/edits/e1/validate"): (403, {}, {"errors": ["Content rating missing"]}),
        }
        transport = ScriptedTransport({**token_answer(), **answers})
        publisher = pub.Publisher("cid", "secret", APP, transport=transport, log=lambda *_: None)
        with self.assertRaises(pub.PublishError) as caught:
            publisher.publish(self.apk, {})
        self.assertIn("validate", str(caught.exception))
        self.assertIn("Content rating missing", str(caught.exception))
        self.assertFalse(any(c[1].endswith("/commit") for c in transport.calls))

    def test_a_missing_etag_stops_the_replace(self):
        answers = {
            ("GET", f"{BASE}/edits"): (200, {}, {"id": "e1", "status": "IN_PROGRESS"}),
            ("GET", f"{BASE}/edits/e1/apks"): (200, {}, [{"id": "a1", "name": "old.apk", "versionCode": 20400}]),
            ("GET", f"{BASE}/edits/e1/apks/a1"): (200, {}, {"id": "a1"}),
        }
        transport = ScriptedTransport({**token_answer(), **answers})
        publisher = pub.Publisher("cid", "secret", APP, transport=transport, log=lambda *_: None)
        with self.assertRaises(pub.PublishError):
            publisher.publish(self.apk, {})
        self.assertFalse(any(c[1].endswith("/replace") for c in transport.calls))

    def test_a_rejected_token_names_the_status(self):
        transport = ScriptedTransport({("POST", pub.TOKEN_URL): (401, {}, {"error": "invalid_client"})})
        publisher = pub.Publisher("cid", "bad", APP, transport=transport, log=lambda *_: None)
        with self.assertRaises(pub.PublishError) as caught:
            publisher.publish(self.apk, {})
        self.assertIn("401", str(caught.exception))


class CommandLine(unittest.TestCase):
    def test_dry_run_touches_no_network_and_reports_the_plan(self):
        with tempfile.TemporaryDirectory() as root:
            apk = Path(root, "dish-2.5.0-amazon.apk")
            apk.write_bytes(b"x" * 10)
            notes = Path(root, "en-US", "changelogs")
            notes.mkdir(parents=True)
            (notes / "20500.txt").write_text("New.", encoding="utf-8")
            lines = []
            pub.plan(apk, pub.release_notes_for(root, "20500"), "20500", log=lines.append)
        self.assertTrue(any("10 bytes" in line and "20500" in line for line in lines))
        self.assertTrue(any("en-US: 4 chars" in line for line in lines))

    def test_an_unsigned_apk_is_refused(self):
        with tempfile.TemporaryDirectory() as root:
            apk = Path(root, "dish-2.5.0-unsigned-amazon.apk")
            apk.write_bytes(b"x")
            with self.assertRaises(pub.PublishError):
                pub.main(["--apk", str(apk), "--release-notes", root, "--version-code", "20500", "--dry-run"])

    def test_missing_credentials_are_named(self):
        with tempfile.TemporaryDirectory() as root:
            apk = Path(root, "dish-2.5.0-amazon.apk")
            apk.write_bytes(b"x")
            saved = {k: pub.os.environ.pop(k, None) for k in
                     ("AMAZON_APPSTORE_CLIENT_ID", "AMAZON_APPSTORE_CLIENT_SECRET", "AMAZON_APPSTORE_APP_ID")}
            try:
                with self.assertRaises(pub.PublishError) as caught:
                    pub.main(["--apk", str(apk), "--release-notes", root, "--version-code", "20500"])
            finally:
                for k, v in saved.items():
                    if v is not None:
                        pub.os.environ[k] = v
        self.assertIn("AMAZON_APPSTORE_APP_ID", str(caught.exception))


if __name__ == "__main__":
    unittest.main()
