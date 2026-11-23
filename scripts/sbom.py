#!/usr/bin/env python3
# SPDX-License-Identifier: LGPL-3.0-or-later
# Copyright (C) 2026 Dish contributors.
"""The components SBOM of what the app ships, written from the build rather than from the artifacts.

    scripts/sbom.py write OUT.spdx.json [--version X.Y.Z]
    scripts/sbom.py check OUT.spdx.json

Syft, which the release hardening runs over the release directory, sees an APK and a bundle and
names almost nothing inside them. This document says what is in them: every package in
app/gradle.lockfile (the three flavors' resolved release runtime classpaths, so the Play bundle's
billing library is in and the GitHub APK's absence of it is the flavor's) with its licence from
app/src/main/assets/licenses/licenses.json, and the two C libraries the NDK build fetches, libsodium
and Opus, at the versions and from the sources app/src/main/cpp/CMakeLists.txt pins. `check` fails a
document that misses a locked library, a library the licence list lacks, or a native version that
is not CMake's. release.yml and android-ci.yml stage the document beside the APK and the bundle, so
the hardening folds it into the product SBOM and Grype scans it.
"""

import argparse
import datetime
import json
import re
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
LOCKFILE = "app/gradle.lockfile"
LICENSES = "app/src/main/assets/licenses/licenses.json"
CMAKE = "app/src/main/cpp/CMakeLists.txt"
PRODUCT = "dish-android"
REPO_URL = "https://github.com/TinkerNorth/dish-android"

SPDX_OF = {
    "The Apache Software License, Version 2.0": "Apache-2.0",
    "The Apache License, Version 2.0": "Apache-2.0",
    "Apache License 2.0": "Apache-2.0",
    "Apache 2.0": "Apache-2.0",
    "Apache-2.0": "Apache-2.0",
    "BSD-3-Clause": "BSD-3-Clause",
    "MIT": "MIT",
}

# What the NDK build compiles in without a manifest anything else reads. The tarball's name in the
# CMake file gives the version; the rest is what the project's own release says.
NATIVE = (
    {"name": "libsodium", "pattern": r"libsodium-([0-9.]+)\.tar\.gz", "license": "ISC",
     "download": "https://download.libsodium.org/libsodium/releases/",
     "cpe": "cpe:2.3:a:libsodium_project:libsodium:{version}:*:*:*:*:*:*:*",
     "purl": "pkg:generic/libsodium@{version}"},
    {"name": "opus", "pattern": r"opus-([0-9.]+)\.tar\.gz", "license": "BSD-3-Clause",
     "download": "https://downloads.xiph.org/releases/opus/",
     "cpe": "cpe:2.3:a:xiph.org:opus:{version}:*:*:*:*:*:*:*",
     "purl": "pkg:generic/opus@{version}"},
)


def locked_packages(lockfile):
    found = []
    for line in lockfile.splitlines():
        line = line.strip()
        if not line or line.startswith("#") or line.startswith("empty="):
            continue
        coordinate = line.split("=", 1)[0]
        group, artifact, version = coordinate.split(":")
        found.append((group, artifact, version))
    return sorted(set(found))


def licence_of(entry):
    names = [item["name"] for item in entry.get("licenses", [])]
    return names[0] if names else None


def native_versions(cmake):
    versions = {}
    for lib in NATIVE:
        match = re.search(lib["pattern"], cmake)
        versions[lib["name"]] = match.group(1) if match else None
    return versions


def spdx_id(name):
    return "SPDXRef-Package-" + re.sub(r"[^A-Za-z0-9.-]", "-", name)


def package(name, version, license_id, download, refs=()):
    entry = {
        "name": name,
        "SPDXID": spdx_id(name),
        "versionInfo": version,
        "downloadLocation": download,
        "filesAnalyzed": False,
        "licenseConcluded": license_id,
        "licenseDeclared": license_id,
        "copyrightText": "NOASSERTION",
    }
    if refs:
        entry["externalRefs"] = list(refs)
    return entry


def document(lockfile, licenses, cmake, version, created):
    by_coordinate = {
        (e["group"], e["artifact"], e["version"]): e for e in licenses["libraries"]
    }
    extracted = {}
    packages = []
    for group, artifact, ver in locked_packages(lockfile):
        entry = by_coordinate.get((group, artifact, ver), {})
        name = licence_of(entry)
        if name in SPDX_OF:
            license_id = SPDX_OF[name]
        elif name:
            license_id = "LicenseRef-" + re.sub(r"[^A-Za-z0-9.-]", "-", name)
            extracted[license_id] = name
        else:
            license_id = "NOASSERTION"
        purl = f"pkg:maven/{group}/{artifact}@{ver}"
        packages.append(package(
            f"{group}:{artifact}", ver, license_id,
            f"https://maven.google.com/web/index.html#{group}:{artifact}:{ver}",
            [{"referenceCategory": "PACKAGE-MANAGER", "referenceType": "purl", "referenceLocator": purl}],
        ))
    versions = native_versions(cmake)
    for lib in NATIVE:
        ver = versions[lib["name"]]
        packages.append(package(
            lib["name"], ver, lib["license"], lib["download"],
            [
                {"referenceCategory": "PACKAGE-MANAGER", "referenceType": "purl",
                 "referenceLocator": lib["purl"].format(version=ver)},
                {"referenceCategory": "SECURITY", "referenceType": "cpe23Type",
                 "referenceLocator": lib["cpe"].format(version=ver)},
            ],
        ))
    root = package(PRODUCT, version, "LGPL-3.0-or-later", REPO_URL)
    root["copyrightText"] = "Copyright (C) 2026 Dish contributors."
    relationships = [{"spdxElementId": "SPDXRef-DOCUMENT", "relationshipType": "DESCRIBES",
                      "relatedSpdxElement": root["SPDXID"]}]
    relationships += [
        {"spdxElementId": root["SPDXID"], "relationshipType": "CONTAINS", "relatedSpdxElement": p["SPDXID"]}
        for p in packages
    ]
    doc = {
        "spdxVersion": "SPDX-2.3",
        "dataLicense": "CC0-1.0",
        "SPDXID": "SPDXRef-DOCUMENT",
        "name": f"{PRODUCT}-components",
        "documentNamespace": f"{REPO_URL}/spdx/components-{version}",
        "creationInfo": {"created": created, "creators": ["Tool: dish-android/scripts/sbom.py"]},
        "packages": [root] + packages,
        "relationships": relationships,
    }
    if extracted:
        doc["hasExtractedLicensingInfos"] = [
            {"licenseId": lid, "extractedText": name, "name": name} for lid, name in sorted(extracted.items())
        ]
    return doc


def problems(doc, lockfile, licenses, cmake):
    found = []
    names = {p["name"]: p for p in doc.get("packages", [])}
    licensed = {(e["group"], e["artifact"], e["version"]) for e in licenses["libraries"]}
    for group, artifact, version in locked_packages(lockfile):
        name = f"{group}:{artifact}"
        if (group, artifact, version) not in licensed:
            found.append(f"{name} {version} is locked but licenses.json does not list it")
        entry = names.get(name)
        if entry is None:
            found.append(f"{name} {version} is locked but the SBOM does not list it")
        elif entry["versionInfo"] != version:
            found.append(f"{name}: the SBOM says {entry['versionInfo']}, the lockfile {version}")
    for lib, version in native_versions(cmake).items():
        entry = names.get(lib)
        if version is None:
            found.append(f"{CMAKE} pins no {lib}")
        elif entry is None or entry["versionInfo"] != version:
            found.append(f"{lib}: the SBOM does not carry the version CMake fetches ({version})")
    return found


def read(path):
    return (ROOT / path).read_text(encoding="utf-8")


def main(argv):
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawTextHelpFormatter)
    sub = parser.add_subparsers(dest="command", required=True)
    write = sub.add_parser("write")
    write.add_argument("out")
    write.add_argument("--version", default="")
    check = sub.add_parser("check")
    check.add_argument("sbom")
    args = parser.parse_args(argv[1:])
    lockfile, licenses, cmake = read(LOCKFILE), json.loads(read(LICENSES)), read(CMAKE)
    if args.command == "write":
        version = args.version or "0.0.0"
        created = datetime.datetime.now(datetime.timezone.utc).strftime("%Y-%m-%dT%H:%M:%SZ")
        doc = document(lockfile, licenses, cmake, version, created)
        Path(args.out).write_text(json.dumps(doc, indent=2) + "\n", encoding="utf-8")
        found = problems(doc, lockfile, licenses, cmake)
    else:
        doc = json.loads(Path(args.sbom).read_text(encoding="utf-8"))
        found = problems(doc, lockfile, licenses, cmake)
    for line in found:
        print(f"::error::{line}", file=sys.stderr)
    if not found:
        print(f"{len(doc['packages'])} packages, no problem")
    return 1 if found else 0


if __name__ == "__main__":
    sys.exit(main(sys.argv))
