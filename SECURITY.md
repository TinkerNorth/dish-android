# Security policy — TinkerNorth

This file covers all four TinkerNorth repositories that ship the
wireless-gamepad product end-to-end:

- [`satellite`](https://github.com/TinkerNorth/satellite) — server (Windows / Linux / macOS)
- [`dish-android`](https://github.com/TinkerNorth/dish-android) — Android client
- [`dish-linux`](https://github.com/TinkerNorth/dish-linux) — Linux client (Qt6 / SDL2)
- [`dish-mac`](https://github.com/TinkerNorth/dish-mac) — macOS client (SwiftUI)

Each repo has its own `CONTRIBUTING.md#security` section with
ecosystem-specific local commands; this file is the single source of
truth for **(1) reporting a vulnerability**, **(2) what we do with the
report**, and **(3) how a downstream consumer verifies a release
artifact**.

---

## Reporting a vulnerability

**Do not file a public issue for a suspected vulnerability.**

Use one of:

1. **GitHub private vulnerability reporting** — open the repo, click
   *Security → Report a vulnerability*. This is preferred because it
   creates a tracked advisory and a private discussion thread.
2. **Email** — `security@tinkernorth.invalid` (PGP key on request).
   Include the repo, version (commit SHA or release tag), reproduction
   steps, and impact.

Please do **not** test exploits against infrastructure you don't own.
The on-LAN threat model already covers an attacker with packet-injection
ability on the local network — that's the documented design boundary,
not a bug.

### Response SLA

| Severity | Triage acknowledgement | Initial assessment | Fix target |
|---|---|---|---|
| Critical (CVSS >= 9.0) | 1 business day | 3 business days | 14 days, coordinated disclosure |
| High (CVSS 7.0–8.9)    | 2 business days | 5 business days | 30 days |
| Medium / Low           | 5 business days | 10 business days | next minor release |

If we miss the SLA, you may publish 90 days after the original report
date regardless. We'd rather know than not know.

### Scope

In scope:

- All four repos in this directory.
- Release artifacts attached to GitHub Releases for any of the four repos.
- The wire protocol (`token(4) | counter(4) | ChaCha20-Poly1305`).
- The pairing flow + HTTP/SSE web UI exposed by `satellite`.

Out of scope:

- Anything that requires the attacker to already have local privileges
  on the user's PC (root, Administrator, ability to drop binaries in
  `%APPDATA%`, etc.).
- The vendored ViGEmBus driver itself — file with [nefarius/ViGEmBus](https://github.com/nefarius/ViGEmBus).
- DoS via raw network flooding — UDP without rate-limit is a known
  trade-off for hot-path latency; mitigations belong in the network
  fabric, not the protocol.

---

## Supported versions

| Repo | Supported | Notes |
|---|---|---|
| `satellite` | latest minor on `main`; previous minor for 90 days | Windows is the canonical target; Linux is supported; macOS ships as a stub (no virtual gamepad) |
| `dish-android` | latest minor on `main`; previous minor for 90 days | minSdk 24 |
| `dish-linux` | latest minor on `main`; previous minor for 90 days | tracks the oldest LTS the release CI builds against |
| `dish-mac` | latest minor on `main`; previous minor for 90 days | macOS 13+ |

Patch releases (`vX.Y.Z+1`) are issued on demand for the latest minor;
the previous minor only receives backports for high/critical fixes.

---

## How CI prevents vulnerable code from shipping

Each repo runs the same shape of gates:

**On every PR** (blocking):

- Action-pin lint — every `uses:` line must reference a 40-char SHA.
- Allowlist expiry — `.security/allowlist.yaml` entries must be unexpired.
- Dependency review — GitHub advisory DB (PR-only).
- OSV-Scanner — vendored components + manifest deps; ecosystem-specific
  scope (see each repo's `security.yml`).
- Gitleaks — secret scanning over the worktree.
- CodeQL — `cpp` for `satellite` / `dish-linux`, `swift` for `dish-mac`,
  `java-kotlin` + `cpp` for `dish-android`.
- (`dish-android` only) OWASP Dependency-Check
  (`./gradlew dependencyCheckAnalyze`) — fails on CVSS >= 7.0.

**On every tagged release** (also blocking):

- Re-run of every PR-time gate against the tagged commit.
- Required-secrets gate — refuses to publish if the platform signing
  secret is missing for a tag (Windows Authenticode, Apple Developer ID
  + notarization, Android keystore). `workflow_dispatch` runs against
  feature branches still produce `-unsigned` artifacts for testing the
  pipeline.
- Artifact-level vulnerability scan — Anchore Grype, fails on
  CRITICAL/HIGH.
- SBOM generation — Syft, both SPDX-JSON and CycloneDX-JSON.
- `SHA256SUMS` over every artifact + its signatures + the SBOMs.
- Cosign keyless signing — every artifact and `SHA256SUMS` get a `.sig`
  + `.crt`, anchored in the Sigstore transparency log.
- SLSA L3 build provenance — `slsa-framework/slsa-github-generator`
  emits `<repo>.intoto.jsonl`.

The result: a known-vulnerable dep, a missing signature, or a tampered
binary all fail the release before any artifact lands on the GitHub
Release page.

---

## Verifying a release artifact (consumer recipe)

This recipe works the same way for every release, every repo, every
platform — only the artifact filenames change.

### 0) Pre-requisites

```bash
# cosign 2.x
brew install cosign            # macOS
go install github.com/sigstore/cosign/v2/cmd/cosign@latest

# slsa-verifier (for the SLSA provenance step)
go install github.com/slsa-framework/slsa-verifier/v2/cli/slsa-verifier@latest
```

### 1) Download every file from the Release

For tag `vX.Y.Z` of `<repo>` (one of `satellite`, `dish-android`,
`dish-linux`, `dish-mac`):

```bash
gh release download vX.Y.Z -R TinkerNorth/<repo> -D ./release
cd release
ls
```

You should see (filenames vary per repo):

```
satellite-...        # platform binary / installer / .app / .apk / etc.
satellite-....sig    # cosign signature
satellite-....crt    # cosign certificate
SHA256SUMS
SHA256SUMS.sig
SHA256SUMS.crt
satellite.sbom.spdx.json
satellite.sbom.cdx.json
satellite.intoto.jsonl   # SLSA L3 provenance
```

### 2) Verify the checksum bundle

```bash
sha256sum -c SHA256SUMS         # Linux / Windows
shasum -a 256 -c SHA256SUMS     # macOS
```

Every line must say `OK`. A failure here means the artifact was
modified after release.

### 3) Verify cosign signed `SHA256SUMS` from the right workflow

```bash
cosign verify-blob \
  --certificate SHA256SUMS.crt \
  --signature   SHA256SUMS.sig \
  --certificate-identity-regexp '^https://github\.com/TinkerNorth/<repo>/\.github/workflows/release\.yml@refs/tags/v.*$' \
  --certificate-oidc-issuer 'https://token.actions.githubusercontent.com' \
  SHA256SUMS
```

Output ends with `Verified OK`. The `--certificate-identity-regexp`
binds the signature to a specific workflow path on `TinkerNorth/<repo>` —
substitute the actual GitHub organisation. A match means the signature
came from a tagged-release run of `release.yml` on the public commit
that produced these artifacts; the Sigstore transparency log
(`https://search.sigstore.dev/`) carries the same record.

To verify each artifact individually (not just `SHA256SUMS`):

```bash
for f in *.exe *.zip *.deb *.AppImage *.apk *.aab; do
  [ -f "$f" ] || continue
  cosign verify-blob \
    --certificate "$f.crt" \
    --signature   "$f.sig" \
    --certificate-identity-regexp '^https://github\.com/TinkerNorth/<repo>/\.github/workflows/release\.yml@refs/tags/v.*$' \
    --certificate-oidc-issuer 'https://token.actions.githubusercontent.com' \
    "$f"
done
```

### 4) Verify the SLSA L3 provenance

```bash
slsa-verifier verify-artifact \
  --provenance-path <repo>.intoto.jsonl \
  --source-uri      github.com/TinkerNorth/<repo> \
  --source-tag      vX.Y.Z \
  <artifact-filename>
```

This proves the artifact was produced by a tagged run of `release.yml`
on the named source repo. Output ends with
`PASSED: SLSA verification passed`.

### 5) (Optional) Inspect the SBOM

```bash
# Top-level summary
syft attestation --output spdx-json release/<repo>.sbom.spdx.json

# Or just diff against last release
diff <(jq -S . prev-release/<repo>.sbom.spdx.json) \
     <(jq -S . release/<repo>.sbom.spdx.json) \
  | less
```

---

## Known gaps

- **Branch protection on `main`.** Three of the four repos run on a
  free org plan that does not expose required-status-check enforcement
  for private repositories. Direct pushes to `main` are blocked by
  convention only; the per-repo CI workflows are the de-facto gate.
  See the matching `README.md` in each repo for the full text.
- **Vendored-header scanners.** `satellite/lib/` and
  `satellite/vigem/include/` are not understood by ecosystem scanners.
  We feed OSV-Scanner a synthetic `osv-scanner.toml` derived from
  [`lib/VENDORED.md` in TinkerNorth/satellite](https://github.com/TinkerNorth/satellite/blob/main/lib/VENDORED.md), and the
  `vendored-freshness` CI job fails if any `Last-vendored:` date is
  more than 90 days old. This is best-effort, not exhaustive — file an
  advisory if you spot a vendored component that's missing from
  `VENDORED.md`.
- **macOS satellite is a stub.** No signed DriverKit equivalent of
  ViGEmBus exists, so the macOS server build runs the protocol stack
  but rejects controller-add requests with `ACK_ERR_VIGEM_UNAVAIL`. The
  artifact name (`satellite-macos-stub-...`) reflects this. Don't open
  a vulnerability report for the absence of virtual-gamepad creation
  on macOS — it's a documented platform gap.
