# PR #170: the `Secret scan (gitleaks)` history

Working note, uncommitted and untracked on purpose. Delete it once PR #170 merges.

Status as of 2026-08-30: **fixed by a commit-scoped allowlist**; the check is
expected green. History as it happened, so nobody re-litigates it:

## The findings

Two, both rule `private-key`, both at line 1, both in commit `7563dd1`
(`7563dd10bee33b15354df8df61052b997c3b3d89`):

- `app/src/test/resources/moonlight/server_key.pem`
- `app/src/test/resources/moonlight/client_key.pem`

Throwaway RSA-2048 test keys, not real secrets, but gitleaks is right to flag
PEM private key material.

## What was done

1. Commit `a00046c` (2026-08-25) deleted all four PEM fixtures and has
   `MoonlightPairingTest` mint disposable RSA-2048 identities at runtime via
   okhttp-tls `HeldCertificate.Builder().rsa2048()`. The working tree has
   carried no key material since. That alone could not turn the job green:
   `_security.yml` checks out `fetch-depth: 0` and runs `gitleaks detect`,
   which scans full history, and the ADD diff still exists in `7563dd1`.
2. A history rewrite folding the deletion into `7563dd1` was implemented and
   verified (gitleaks 8.30.1: "no leaks found") but needs a force-push, which
   the user declined on 2026-08-25 and which the tooling's permission layer
   also blocks. The pre-rewrite head is still kept at the local branch
   `backup/moonlight-pre-gitleaks` (= `b92d231`).
3. 2026-08-30, on the user's request to fix the check without a force-push:
   `.gitleaks.toml` was converted to the `[[allowlists]]` form (the singular
   table is deprecated in 8.30 and cannot coexist with it) and gained one
   block scoped to the single commit `7563dd10be...`. Scoped to the COMMIT,
   not the paths, so a real key dropped at those locations later is still
   caught; the exemption covers exactly the two findings that exist and
   nothing that can ever be added.

The key blobs remain reachable in history. They are throwaway test keys that
never protected anything; if the user ever wants them truly gone, the rewrite
in (2) is the way, and it requires their own `git push --force-with-lease`.
