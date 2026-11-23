#!/usr/bin/env bash
# OSV-Scanner 2.x over the worktree and any vendored components, with .security/allowlist.yaml
# applied as the only source of accepted vulnerabilities.
#
#   scripts/osv-scan.sh [--config FILE] [--lockfile-script SCRIPT] [--allow-no-packages] [PATH ...]
#
#   --config FILE            OSV-Scanner TOML to merge under the allowlist ([[PackageOverrides]]
#                            and other settings; never [[IgnoredVulns]], see security-allowlist.py)
#   --lockfile-script SCRIPT bash script that prints an OSV-Scanner custom lockfile (the JSON
#                            shape of `osv-scanner --format json`), for vendored code named by
#                            upstream commit; it must name at least one package
#   --allow-no-packages      the repository has no manifest OSV-Scanner can read; say so instead
#                            of failing. Without it, "no packages found" (exit 128) fails: a
#                            green scan of nothing is not a gate
#   PATH ...                 directories to scan recursively (none: only the lockfile)
#
# The binary is ${OSV_SCANNER} when set, otherwise `osv-scanner` on PATH, and must be the version
# _security.yml pins. Exit status: 0 clean, 1 a vulnerability or a scan that could not run.
#
# Shared byte-identical by every TinkerNorth repository; CI's _security.yml and the local
# scripts/ci-local.sh both run it.
set -euo pipefail

here=$(cd "$(dirname "$0")" && pwd)
osv=${OSV_SCANNER:-osv-scanner}
config=""
lockfile_script=""
allow_no_packages=false
paths=()

while [[ "$#" -gt 0 ]]; do
    case "$1" in
        --config) config=${2:?--config needs a file}; shift 2 ;;
        --lockfile-script) lockfile_script=${2:?--lockfile-script needs a script}; shift 2 ;;
        --allow-no-packages) allow_no_packages=true; shift ;;
        --) shift; paths+=("$@"); break ;;
        -*) echo "osv-scan: unknown option $1" >&2; exit 1 ;;
        *) paths+=("$1"); shift ;;
    esac
done

if [[ -n "${config}" ]] && [[ ! -f "${config}" ]]; then
    echo "::error::osv-scan: config file '${config}' does not exist" >&2
    exit 1
fi
if [[ "${#paths[@]}" -eq 0 ]] && [[ -z "${lockfile_script}" ]]; then
    echo "osv-scan: give a path to scan, a --lockfile-script, or both" >&2
    exit 1
fi

work=$(mktemp -d)
trap 'rm -rf "${work}"' EXIT

python3 "${here}/security-allowlist.py" osv-config --base "${config}" > "${work}/osv-scanner.toml"
args=(scan source --config="${work}/osv-scanner.toml")

if [[ -n "${lockfile_script}" ]]; then
    if ! bash "${lockfile_script}" > "${work}/vendored.json"; then
        echo "::error::osv-scan: ${lockfile_script} could not write the OSV-Scanner lockfile" >&2
        exit 1
    fi
    args+=("--lockfile=osv-scanner:${work}/vendored.json")
fi
if [[ "${#paths[@]}" -gt 0 ]]; then
    args+=(--recursive "${paths[@]}")
fi

echo "+ osv-scanner ${args[*]}"
rc=0
"${osv}" "${args[@]}" || rc=$?

# OSV-Scanner 2 exit codes: 0 nothing found, 1 vulnerabilities, 127 general error,
# 128 no packages found.
case "${rc}" in
    0) exit 0 ;;
    128)
        if [[ "${allow_no_packages}" = true ]] && [[ -z "${lockfile_script}" ]]; then
            echo "osv-scan: no manifest OSV-Scanner reads in ${paths[*]}, as the caller declared."
            exit 0
        fi
        echo "::error::osv-scan: OSV-Scanner found no packages to scan" >&2
        exit 1 ;;
    *) exit 1 ;;
esac
