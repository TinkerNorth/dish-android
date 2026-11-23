#!/bin/sh
# Action pin lint: every `uses:` in the workflows and composite actions must name a
# 40-character commit SHA followed by a `# vX.Y.Z` comment saying which release it is.
#
#   scripts/check-action-pins.sh [dir ...]
#
# With no arguments it checks .github/workflows and, when it exists, .github/actions,
# relative to the current directory (run it from the repository root). Local references
# (`uses: ./...`) and digest-pinned containers (`docker://image@sha256:<64 hex>`) pass.
# Exit status: 0 when every reference is pinned, 1 when one is not, 2 on a usage error.
#
# Shared byte-identical by every TinkerNorth repository; CI's _security.yml and the local
# scripts/ci-local.sh both run it. It is plain POSIX sh and POSIX awk: no interval
# expressions and no alternation, so mawk 1.3.4 (/usr/bin/awk on Ubuntu 24.04), BSD awk
# (macOS) and gawk give the same answer. On Windows, call it through Git for Windows' bash:
#   & bash scripts/check-action-pins.sh; if ($LASTEXITCODE -ne 0) { exit $LASTEXITCODE }
set -eu

if [ "$#" -eq 0 ]; then
    set -- .github/workflows
    if [ -d .github/actions ]; then
        set -- "$@" .github/actions
    fi
fi

for dir in "$@"; do
    if [ ! -d "${dir}" ]; then
        echo "check-action-pins: '${dir}' is not a directory" >&2
        exit 2
    fi
done

list=$(mktemp)
trap 'rm -f "${list}"' EXIT
find "$@" -type f \( -name '*.yml' -o -name '*.yaml' \) | LC_ALL=C sort > "${list}"
if [ ! -s "${list}" ]; then
    echo "check-action-pins: no workflow files under $*" >&2
    exit 2
fi

fail=0
while IFS= read -r file; do
    awk '
        # A whole-line comment is never a reference.
        /^[[:space:]]*#/ { next }
        # Only a YAML `uses:` key (optionally a list item), not the text of a run: block.
        !/^[[:space:]]*-?[[:space:]]*uses:[[:space:]]/ { next }
        {
            ref = $0
            sub(/^[[:space:]]*-?[[:space:]]*uses:[[:space:]]+/, "", ref)
            comment = ""
            hash = index(ref, " #")
            if (hash > 0) {
                comment = substr(ref, hash + 2)
                ref = substr(ref, 1, hash - 1)
            }
            sub(/[[:space:]]+$/, "", ref)
            gsub(/"/, "", ref)
            gsub(/\047/, "", ref)
            sub(/^[[:space:]]+/, "", comment)
            sub(/[[:space:]]+$/, "", comment)

            if (substr(ref, 1, 2) == "./") { next }
            if (substr(ref, 1, 9) == "docker://") {
                at = index(ref, "@sha256:")
                digest = (at > 0) ? substr(ref, at + 8) : ""
                if (length(digest) == 64 && digest !~ /[^0-9a-f]/) { next }
                printf "%s:%d: %s (a container must be pinned by @sha256 digest)\n", FILENAME, NR, ref
                bad = 1
                next
            }

            n = split(ref, parts, "@")
            sha = (n >= 2) ? parts[n] : ""
            if (length(sha) != 40 || sha ~ /[^0-9a-f]/) {
                printf "%s:%d: %s (not pinned to a 40-character commit SHA)\n", FILENAME, NR, ref
                bad = 1
                next
            }
            if (sha !~ /[^0]/) {
                printf "%s:%d: %s (all-zero placeholder SHA)\n", FILENAME, NR, ref
                bad = 1
                next
            }
            if (comment !~ /^v[0-9][0-9]*\.[0-9][0-9]*(\.[0-9][0-9]*)?$/) {
                printf "%s:%d: %s (needs a \"# vX.Y.Z\" comment naming the release)\n", FILENAME, NR, ref
                bad = 1
            }
        }
        END { exit bad }
    ' "${file}" || fail=1
done < "${list}"

if [ "${fail}" -ne 0 ]; then
    echo "check-action-pins: pin each reference above to the commit of a release tag:" >&2
    echo "  gh api repos/<owner>/<repo>/commits/<tag> --jq .sha" >&2
    echo "and write the tag after it: uses: <owner>/<repo>@<sha> # <tag>" >&2
    exit 1
fi
echo "check-action-pins: every action reference is pinned to a commit SHA."
