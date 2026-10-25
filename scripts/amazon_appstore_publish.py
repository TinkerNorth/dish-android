#!/usr/bin/env python3
"""Upload a tagged Amazon Appstore APK and its release notes through Amazon's App Submission API.

    AMAZON_APPSTORE_CLIENT_ID=... AMAZON_APPSTORE_CLIENT_SECRET=... AMAZON_APPSTORE_APP_ID=... \\
    scripts/amazon_appstore_publish.py --apk dist-amazon/dish-2.5.0-amazon.apk \\
        --release-notes play/metadata/android --version-code 20500 [--dry-run]

One run is one upcoming version: the app's open edit (or a new one), the live APK replaced by
the file given (uploaded afresh when the app has none), every listing language's "recent
changes" set from play/metadata/android/<locale>/changelogs/<version code>.txt, then validate
and commit. Amazon's API cannot create an app or its first version, so the app must already be
live; the console stays the place for pricing, DRM, content rating and device targeting.

Zero dependencies: urllib does the HTTP, and the transport is injectable so the flow is testable
without Amazon. --dry-run reads the local inputs and prints the plan, touching nothing remote.
"""
import argparse
import json
import os
import sys
import urllib.error
import urllib.parse
import urllib.request
from pathlib import Path

TOKEN_URL = "https://api.amazon.com/auth/o2/token"
TOKEN_SCOPE = "appstore::apps:readwrite"
API_BASE = "https://developer.amazon.com/api/appstore/v1/applications"
APK_CONTENT_TYPE = "application/octet-stream"
JSON_CONTENT_TYPE = "application/json"


class PublishError(Exception):
    """A step the run cannot proceed past; the message names the step and Amazon's answer."""


class Response:
    def __init__(self, status, headers, body):
        self.status = status
        self.headers = {k.lower(): v for k, v in dict(headers).items()}
        self.body = body

    @property
    def etag(self):
        return self.headers.get("etag")

    def json(self):
        if not self.body or not self.body.strip():
            return None
        return json.loads(self.body.decode("utf-8"))


def urllib_transport(method, url, headers, body):
    request = urllib.request.Request(url, data=body, method=method, headers=headers)
    try:
        with urllib.request.urlopen(request, timeout=120) as reply:
            return Response(reply.status, reply.headers.items(), reply.read())
    except urllib.error.HTTPError as err:
        return Response(err.code, err.headers.items(), err.read())


def release_notes_for(notes_root, version_code):
    """{locale: text} for every locale directory carrying changelogs/<version_code>.txt."""
    notes = {}
    root = Path(notes_root)
    if not root.is_dir():
        return notes
    for locale_dir in sorted(p for p in root.iterdir() if p.is_dir()):
        path = locale_dir / "changelogs" / f"{version_code}.txt"
        if path.is_file():
            notes[locale_dir.name] = path.read_text(encoding="utf-8").strip()
    return notes


def listing_with_notes(listing, notes):
    """The listing as Amazon returned it, with only recentChanges replaced."""
    updated = dict(listing)
    updated["recentChanges"] = notes
    return updated


def choose_apk_to_replace(apks):
    """The one APK of a single-APK app, None when the app has none, an error for anything else."""
    if not apks:
        return None
    if len(apks) == 1:
        return apks[0]
    names = ", ".join(f"{a.get('name')} ({a.get('id')})" for a in apks)
    raise PublishError(
        f"the edit carries {len(apks)} APKs ({names}); Dish ships one, so a person must sort this edit out"
    )


class Publisher:
    def __init__(self, client_id, client_secret, app_id, transport=urllib_transport, log=print):
        self.client_id = client_id
        self.client_secret = client_secret
        self.app_id = app_id
        self.transport = transport
        self.log = log
        self.token = None

    # -- HTTP -------------------------------------------------------------------------------------
    def fetch_token(self):
        body = urllib.parse.urlencode({
            "grant_type": "client_credentials",
            "client_id": self.client_id,
            "client_secret": self.client_secret,
            "scope": TOKEN_SCOPE,
        }).encode("ascii")
        reply = self.transport("POST", TOKEN_URL, {"Content-Type": "application/x-www-form-urlencoded"}, body)
        if reply.status != 200:
            raise PublishError(f"token request failed with HTTP {reply.status}: {reply.body[:300]!r}")
        self.token = reply.json()["access_token"]

    def call(self, method, path, headers=None, body=None, ok=(200,)):
        url = f"{API_BASE}/{self.app_id}/{path.lstrip('/')}"
        sent = {"Authorization": f"Bearer {self.token}", **(headers or {})}
        reply = self.transport(method, url, sent, body)
        if reply.status not in ok:
            raise PublishError(f"{method} {path} failed with HTTP {reply.status}: {reply.body[:1000]!r}")
        return reply

    def put_json(self, path, etag, payload):
        body = json.dumps(payload).encode("utf-8")
        return self.call("PUT", path, {"Content-Type": JSON_CONTENT_TYPE, "If-Match": etag}, body)

    # -- steps ------------------------------------------------------------------------------------
    def open_edit(self):
        active = self.call("GET", "edits").json()
        if active and active.get("id"):
            self.log(f"reusing open edit {active['id']} ({active.get('status')})")
            return active["id"]
        created = self.call("POST", "edits").json()
        self.log(f"created edit {created['id']}")
        return created["id"]

    def put_apk(self, edit_id, apk_path):
        apk_path = Path(apk_path)
        data = apk_path.read_bytes()
        headers = {"Content-Type": APK_CONTENT_TYPE, "fileName": apk_path.name}
        current = choose_apk_to_replace(self.call("GET", f"edits/{edit_id}/apks").json() or [])
        if current is None:
            self.call("POST", f"edits/{edit_id}/apks/upload", headers, data)
            self.log(f"uploaded {apk_path.name} ({len(data)} bytes) as the app's first APK in this edit")
            return
        etag = self.call("GET", f"edits/{edit_id}/apks/{current['id']}").etag
        if not etag:
            raise PublishError(f"GET apks/{current['id']} returned no ETag, which replaceApk needs")
        headers["If-Match"] = etag
        replaced = self.call("PUT", f"edits/{edit_id}/apks/{current['id']}/replace", headers, data).json()
        self.log(
            f"replaced {current.get('name')} (versionCode {current.get('versionCode')}) with "
            f"{apk_path.name} ({len(data)} bytes): versionCode {replaced.get('versionCode') if replaced else '?'}"
        )

    def put_release_notes(self, edit_id, notes):
        listings = self.call("GET", f"edits/{edit_id}/listings").json() or {}
        languages = sorted((listings.get("listings") or listings).keys())
        for language in languages:
            text = notes.get(language)
            if text is None:
                self.log(f"listing {language}: no release notes for this version, left as is")
                continue
            reply = self.call("GET", f"edits/{edit_id}/listings/{language}")
            if not reply.etag:
                raise PublishError(f"GET listings/{language} returned no ETag, which updateListing needs")
            self.put_json(f"edits/{edit_id}/listings/{language}", reply.etag, listing_with_notes(reply.json(), text))
            self.log(f"listing {language}: recent changes set ({len(text)} chars)")

    def validate_and_commit(self, edit_id):
        self.call("POST", f"edits/{edit_id}/validate")
        etag = self.call("GET", f"edits/{edit_id}").etag
        if not etag:
            raise PublishError(f"GET edits/{edit_id} returned no ETag, which commitEdit needs")
        committed = self.call("POST", f"edits/{edit_id}/commit", {"If-Match": etag}).json()
        self.log(f"committed edit {edit_id}: status {committed.get('status') if committed else '?'}")

    def publish(self, apk_path, notes):
        self.fetch_token()
        edit_id = self.open_edit()
        self.put_apk(edit_id, apk_path)
        self.put_release_notes(edit_id, notes)
        self.validate_and_commit(edit_id)
        return edit_id


def plan(apk_path, notes, version_code, log=print):
    apk = Path(apk_path)
    log(f"dry run: would upload {apk.name} ({apk.stat().st_size} bytes) as versionCode {version_code}")
    for locale, text in sorted(notes.items()):
        log(f"dry run: release notes {locale}: {len(text)} chars")
    if not notes:
        log("dry run: no release notes found for this version code")


def parse_args(argv):
    parser = argparse.ArgumentParser(description=__doc__.split("\n", 1)[0])
    parser.add_argument("--apk", required=True, help="the signed Amazon flavor APK")
    parser.add_argument("--release-notes", required=True, help="play/metadata/android, one changelogs/ per locale")
    parser.add_argument("--version-code", required=True, help="the versionCode the tag resolves to")
    parser.add_argument("--dry-run", action="store_true", help="print the plan; no network")
    return parser.parse_args(argv)


def main(argv=None):
    args = parse_args(argv)
    apk = Path(args.apk)
    if not apk.is_file():
        raise PublishError(f"APK not found: {apk}")
    if "-unsigned" in apk.name:
        raise PublishError(f"{apk.name} is an unsigned build; the Appstore takes signed APKs only")
    notes = release_notes_for(args.release_notes, args.version_code)
    if args.dry_run:
        plan(apk, notes, args.version_code)
        return 0
    credentials = {name: os.environ.get(name, "").strip() for name in
                   ("AMAZON_APPSTORE_CLIENT_ID", "AMAZON_APPSTORE_CLIENT_SECRET", "AMAZON_APPSTORE_APP_ID")}
    missing = [name for name, value in credentials.items() if not value]
    if missing:
        raise PublishError("missing environment: " + ", ".join(missing))
    publisher = Publisher(credentials["AMAZON_APPSTORE_CLIENT_ID"], credentials["AMAZON_APPSTORE_CLIENT_SECRET"],
                          credentials["AMAZON_APPSTORE_APP_ID"])
    publisher.publish(apk, notes)
    return 0


if __name__ == "__main__":
    try:
        sys.exit(main())
    except PublishError as err:
        print(f"::error::{err}", file=sys.stderr)
        sys.exit(1)
