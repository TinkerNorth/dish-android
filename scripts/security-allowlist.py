#!/usr/bin/env python3
"""The vulnerability allowlist, .security/allowlist.yaml: its gate and its OSV-Scanner form.

    python3 scripts/security-allowlist.py check [ALLOWLIST]
    python3 scripts/security-allowlist.py osv-config [--base OSV_TOML] [ALLOWLIST] > merged.toml

`check` fails when an entry lacks a field, has a malformed or past `expires`, or expires more
than 90 days out. `osv-config` prints an OSV-Scanner 2.x config: the optional base file (for
[[PackageOverrides]] and other settings) followed by one [[IgnoredVulns]] entry per allowlist
exception, so the allowlist is the one place a vulnerability is accepted. A base file that
carries its own [[IgnoredVulns]], or a [[PackageOverrides]] entry that hides vulnerabilities
(`ignore`, `vulnerability.ignore`, the v1 `ignoreUntil` key), is refused. ALLOWLIST defaults to .security/allowlist.yaml; a missing file is an empty list.

Shared byte-identical by every TinkerNorth repository; CI's _security.yml and the local
scripts/ci-local.sh both run it. Needs Python 3.11 or newer and PyYAML.
"""

import argparse
import datetime
import json
import pathlib
import sys
import tomllib

import yaml

DEFAULT_ALLOWLIST = ".security/allowlist.yaml"
REQUIRED_FIELDS = ("cve", "reason", "owner", "expires")
MAX_DAYS = 90


def load_entries(path, out=sys.stdout):
    """Returns the exception entries, or None (after printing why) when the file is malformed."""
    file = pathlib.Path(path)
    if not file.is_file():
        return []
    data = yaml.safe_load(file.read_text(encoding="utf-8")) or {}
    if not isinstance(data, dict):
        print(f"::error file={path}::the top level must be a mapping with an 'exceptions' list", file=out)
        return None
    entries = data.get("exceptions") or []
    if not isinstance(entries, list):
        print(f"::error file={path}::'exceptions' must be a list", file=out)
        return None
    return entries


def expiry_of(entry):
    expires = entry.get("expires")
    if isinstance(expires, datetime.date):
        return expires
    if isinstance(expires, str):
        try:
            return datetime.date.fromisoformat(expires)
        except ValueError:
            return None
    return None


def entry_errors(entry, index, today):
    if not isinstance(entry, dict):
        return [f"entry #{index} is not a mapping"]
    name = entry.get("cve") or f"entry #{index}"
    errors = [f"{name}: missing required field '{field}'"
              for field in REQUIRED_FIELDS if not entry.get(field)]
    if entry.get("expires"):
        expires = expiry_of(entry)
        if expires is None:
            errors.append(f"{name}: expires must be a YYYY-MM-DD date")
        elif expires < today:
            errors.append(f"{name}: expired on {expires}; re-evaluate the finding or remove the entry")
        elif (expires - today).days > MAX_DAYS:
            errors.append(f"{name}: expires {expires} is more than {MAX_DAYS} days out")
    return errors


def check(path, out=sys.stdout):
    entries = load_entries(path, out)
    if entries is None:
        return 1
    today = datetime.date.today()
    errors = [error for index, entry in enumerate(entries)
              for error in entry_errors(entry, index, today)]
    for error in errors:
        print(f"::error file={path}::{error}", file=out)
    if errors:
        return 1
    print(f"{path}: {len(entries)} exception(s), all current.", file=out)
    return 0


def toml_string(value):
    # A JSON string literal is a valid TOML basic string for the text an allowlist holds.
    return json.dumps(str(value), ensure_ascii=True)


def osv_config(path, base):
    if check(path, out=sys.stderr) != 0:
        return 1
    parts = []
    if base:
        base_text = pathlib.Path(base).read_text(encoding="utf-8")
        parsed = tomllib.loads(base_text)
        if "IgnoredVulns" in parsed:
            print(f"::error file={base}::[[IgnoredVulns]] belongs in {path}, where its expiry is enforced",
                  file=sys.stderr)
            return 1
        for override in parsed.get("PackageOverrides", []):
            hides = override.get("ignore") or override.get("vulnerability", {}).get("ignore")
            if hides or "ignoreUntil" in override:
                print(f"::error file={base}::a [[PackageOverrides]] entry may not hide vulnerabilities;"
                      f" accept a finding in {path}, where its expiry is enforced",
                      file=sys.stderr)
                return 1
        parts.append(base_text.rstrip("\n") + "\n")
    for entry in load_entries(path, sys.stderr) or []:
        parts.append(
            "[[IgnoredVulns]]\n"
            f"id = {toml_string(entry['cve'])}\n"
            f"effectiveUntil = {expiry_of(entry).isoformat()}\n"
            f"reason = {toml_string(entry['reason'])}\n")
    sys.stdout.write("\n".join(parts))
    return 0


def main():
    parser = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    commands = parser.add_subparsers(dest="command", required=True)
    check_command = commands.add_parser("check", help="fail on a malformed or expired entry")
    check_command.add_argument("allowlist", nargs="?", default=DEFAULT_ALLOWLIST)
    config_command = commands.add_parser("osv-config", help="print the merged OSV-Scanner config")
    config_command.add_argument("--base", default="", help="OSV-Scanner TOML to start from")
    config_command.add_argument("allowlist", nargs="?", default=DEFAULT_ALLOWLIST)
    args = parser.parse_args()
    if args.command == "check":
        return check(args.allowlist)
    return osv_config(args.allowlist, args.base)


if __name__ == "__main__":
    sys.exit(main())
