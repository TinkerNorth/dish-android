# Security policy: TinkerNorth

This file covers the TinkerNorth repositories that ship the wireless-gamepad
product end-to-end, the server and the five clients:

- [`satellite`](https://github.com/TinkerNorth/satellite): Satellite, the server (Windows / Linux / macOS)
- [`dish-android`](https://github.com/TinkerNorth/dish-android): Dish for Android
- [`dish-ios`](https://github.com/TinkerNorth/dish-ios): Dish for iOS
- [`dish-mac`](https://github.com/TinkerNorth/dish-mac): Dish for macOS (SwiftUI)
- [`dish-windows`](https://github.com/TinkerNorth/dish-windows): Dish for Windows (Qt6 / SDL2)
- [`dish-linux`](https://github.com/TinkerNorth/dish-linux): Dish for Linux (Qt6 / SDL2)

The website, `https://dish.tinkernorth.com`, serves the controller database and
the privacy policies; report a problem with it the same way.

Each repo has its own `CONTRIBUTING.md#security` section with
ecosystem-specific local commands; this file is the single source of
truth for **(1) reporting a vulnerability**, **(2) what we do with the
report**, and **(3) how a downstream consumer verifies a release
artifact**.

---

## Reporting a vulnerability

**Do not file a public issue for a suspected vulnerability.**

Use one of:

1. **GitHub private vulnerability reporting**: open the repo, click
   *Security → Report a vulnerability*. This is preferred because it
   creates a tracked advisory and a private discussion thread.
2. **Email**: `security@tinkernorth.com` (PGP key on request).
   Include the repo, version (commit SHA or release tag), reproduction
   steps, and impact.

Please do **not** test exploits against infrastructure you don't own.
The on-LAN threat model already covers an attacker with packet-injection
ability on the local network. That's the documented design boundary,
not a bug.

### Response SLA

| Severity | Triage acknowledgement | Initial assessment | Fix target |
|---|---|---|---|
| Critical (CVSS >= 9.0) | 1 business day | 3 business days | 14 days, coordinated disclosure |
| High (CVSS 7.0-8.9)    | 2 business days | 5 business days | 30 days |
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
- The vendored ViGEmBus driver itself: file with [nefarius/ViGEmBus](https://github.com/nefarius/ViGEmBus).
- DoS via raw network flooding: UDP without rate-limit is a known
  trade-off for hot-path latency; mitigations belong in the network
  fabric, not the protocol.

---

## Supported versions

| Repo | Supported | Notes |
|---|---|---|
| `satellite` | latest minor on `main` (2.3.x); previous minor for 90 days | Windows is the canonical target; Linux is supported; macOS ships as a stub (no virtual gamepad) |
| `dish-android` | latest minor on `main` (2.7.x); previous minor for 90 days | minSdk 24 |
| `dish-ios` | `main` until the first release | not yet released |
| `dish-mac` | latest minor on `main` (1.4.x); previous minor for 90 days | macOS 13+ |
| `dish-windows` | latest minor on `main` (2.8.x); previous minor for 90 days | Windows 10 (1809) or 11 |
| `dish-linux` | latest minor on `main` (2.8.x); previous minor for 90 days | tracks the oldest LTS the release CI builds against |

Patch releases (`X.Y.Z+1`) are issued on demand for the latest minor;
the previous minor only receives backports for high/critical fixes.

---

## How CI prevents vulnerable code from shipping

Each repo runs the same shape of gates:

**On every PR** (blocking):

- Action-pin lint: every `uses:` line must reference a 40-char SHA.
- Allowlist expiry: `.security/allowlist.yaml` entries must be unexpired.
- Dependency review: GitHub advisory DB (PR-only).
- OSV-Scanner: vendored components and the dependencies a repo's lockfiles
  name. dish-android's `app/gradle.lockfile` pins the three flavors' shipped
  Maven dependencies, and CI fails when it is stale.
- Gitleaks: secret scanning over the full git history.
- CodeQL: `cpp` for `satellite` / `dish-linux`, `swift` for `dish-mac`,
  `java-kotlin` + `cpp` for `dish-android`.

**On every tagged release** (also blocking):

- Re-run of every PR-time gate against the tagged commit.
- Required-secrets gate: refuses to publish if the platform signing
  secret is missing for a tag (Windows Authenticode, Apple Developer ID
  + notarization, Android keystore). `workflow_dispatch` runs against
  feature branches still produce `-unsigned` artifacts for testing the
  pipeline.
- Artifact-level vulnerability scan: Anchore Grype, fails on
  CRITICAL/HIGH.
- SBOM generation: Syft, both SPDX-JSON and CycloneDX-JSON.
- `SHA256SUMS` over every artifact + its signatures + the SBOMs.
- Cosign keyless signing: every artifact and `SHA256SUMS` get a Sigstore
  bundle (`<file>.sigstore.json`: signature, certificate and transparency
  log proof), signed by `_release_harden.yml`. Releases up to dish-android
  2.7.0 carry a `.sig` and a `.crt` per file instead.
- SLSA L3 build provenance: `slsa-framework/slsa-github-generator`
  emits `<repo>.intoto.jsonl`.

The result: a known-vulnerable dep, a missing signature, or a tampered
binary all fail the release before any artifact lands on the GitHub
Release page.

---

## Verifying a release artifact (consumer recipe)

This recipe works the same way for every release, every repo, every
platform. Only the artifact filenames change.

### 0) Pre-requisites

```bash
# cosign 3.x
brew install cosign            # macOS, or a release binary from
                               # https://github.com/sigstore/cosign/releases

# slsa-verifier (for the SLSA provenance step)
# a release binary from https://github.com/slsa-framework/slsa-verifier/releases
```

### 1) Download every file from the Release

For tag `X.Y.Z` of `<repo>` (one of `satellite`, `dish-android`,
`dish-ios`, `dish-mac`, `dish-windows`, `dish-linux`):

```bash
TAG=X.Y.Z
REPO=<repo>
gh release download "$TAG" -R "TinkerNorth/$REPO" -D "$REPO-$TAG"
cd "$REPO-$TAG"
ls
```

You should see (filenames vary per repo):

```
dish-...apk                   # platform binary / installer / .app / .apk / etc.
dish-...apk.sigstore.json     # its cosign bundle
SHA256SUMS
SHA256SUMS.sigstore.json
<repo>.sbom.spdx.json
<repo>.sbom.cdx.json
<repo>.intoto.jsonl           # SLSA L3 provenance
```

A release cut before the signing moved to bundles (dish-android 2.7.0 and
earlier) has `<file>.sig` and `<file>.crt` beside each file instead of the
`.sigstore.json`; step 3 has its form.

### 2) Verify the checksum bundle

```bash
sha256sum --ignore-missing -c SHA256SUMS   # Linux / Windows
shasum -a 256 -c SHA256SUMS                # macOS
```

Every line you downloaded must say `OK`. A failure here means the artifact
was modified after release.

### 3) Verify cosign signed `SHA256SUMS` from the right workflow

```bash
cosign verify-blob \
  --bundle SHA256SUMS.sigstore.json \
  --certificate-identity "https://github.com/TinkerNorth/$REPO/.github/workflows/_release_harden.yml@refs/tags/$TAG" \
  --certificate-oidc-issuer https://token.actions.githubusercontent.com \
  SHA256SUMS
```

Output ends with `Verified OK`. The identity is exact, not a pattern: it
names the shared release-hardening workflow that ran the signing step, at the
tag. The certificate's own Build Config URI names the calling `release.yml`.
A match means the signature came from a tagged-release run on the public
commit that produced these artifacts; the Sigstore transparency log
(`https://search.sigstore.dev/`) carries the same record.

To verify one artifact on its own (not just `SHA256SUMS`), use its bundle:

```bash
cosign verify-blob \
  --bundle <file>.sigstore.json \
  --certificate-identity "https://github.com/TinkerNorth/$REPO/.github/workflows/_release_harden.yml@refs/tags/$TAG" \
  --certificate-oidc-issuer https://token.actions.githubusercontent.com \
  <file>
```

**Releases up to dish-android 2.7.0** were signed by `release.yml` itself and
carry `.sig` and `.crt` files. cosign 3 still verifies them with its
deprecated flags (it prints two deprecation lines), against that workflow's
identity:

```bash
cosign verify-blob \
  --certificate SHA256SUMS.crt --signature SHA256SUMS.sig \
  --certificate-identity "https://github.com/TinkerNorth/dish-android/.github/workflows/release.yml@refs/tags/2.7.0" \
  --certificate-oidc-issuer https://token.actions.githubusercontent.com \
  SHA256SUMS
```

(Run against dish-android 2.7.0: `Verified OK`, and the same form verifies
`dish-2.7.0.apk` with `dish-2.7.0.apk.crt` and `.sig`.) For the other
repositories' older releases, the workflow in the identity is the one named in
the certificate; `satellite`, `dish-windows` and `dish-linux` used
`_release_harden.yml` already.

### 4) Verify the SLSA L3 provenance

`<repo>.intoto.jsonl` is a Sigstore bundle whose statement names every
released file by SHA-256. For a release cut with the generator pinned by
commit (every release after dish-android 2.7.0), it is signed by the SLSA
generator at the exact commit the workflow pins, which `slsa-verifier` refuses
("invalid ref") because that is not a release tag. Verify it with cosign,
naming the generator's commit (the one in the provenance's `builder.id`; for
dish-android it is the generator v2.1.0 commit
`f7dd8c54c2067bafc12ca7a55595d5ee9b75204a`, the one `release.yml` pins):

```bash
cosign verify-blob-attestation \
  --bundle "$REPO.intoto.jsonl" --type slsaprovenance \
  --certificate-identity "https://github.com/slsa-framework/slsa-github-generator/.github/workflows/generator_generic_slsa3.yml@f7dd8c54c2067bafc12ca7a55595d5ee9b75204a" \
  --certificate-oidc-issuer https://token.actions.githubusercontent.com \
  <artifact-filename>
```

dish-android 2.7.0 and earlier were built with the generator called by its
tag, so their certificate names `generator_generic_slsa3.yml@refs/tags/v2.1.0`.
Either `slsa-verifier` or the same cosign command with that identity verifies
them; both were run against 2.7.0 and `dish-2.7.0.apk` and passed:

```bash
slsa-verifier verify-artifact <artifact-filename> \
  --provenance-path "$REPO.intoto.jsonl" \
  --source-uri "github.com/TinkerNorth/$REPO" \
  --source-tag "$TAG"
```

`slsa-verifier` ends with `PASSED: SLSA verification passed`; cosign ends with
`Verified OK`. The first release cut after the generator moved to a commit pin
is the first the cosign form with the commit identity applies to; it is run
against that release when it exists.

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
- **Vendored-header scanners.** `satellite/lib/` and
  `satellite/vigem/include/` are not understood by ecosystem scanners.
  We feed OSV-Scanner a synthetic `osv-scanner.toml` derived from
  [`lib/VENDORED.md` in TinkerNorth/satellite](https://github.com/TinkerNorth/satellite/blob/main/lib/VENDORED.md), and the
  `vendored-freshness` CI job fails if any `Last-vendored:` date is
  more than 90 days old. This is best-effort, not exhaustive. File an
  advisory if you spot a vendored component that's missing from
  `VENDORED.md`.
- **macOS satellite is a stub.** No signed DriverKit equivalent of
  ViGEmBus exists, so the macOS server build runs the protocol stack
  but rejects controller-add requests with `ACK_ERR_VIGEM_UNAVAIL`. The
  artifact name (`satellite-macos-stub-...`) reflects this. Don't open
  a vulnerability report for the absence of virtual-gamepad creation
  on macOS. It's a documented platform gap.
