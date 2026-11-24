## What changes, and why

<!-- Two or three sentences. Name the behaviour a user or a developer sees, not the files. -->

## Kind of change

- [ ] Fix
- [ ] Feature
- [ ] Refactor (behaviour unchanged)
- [ ] Docs, CI or repo plumbing
- [ ] Wire protocol or pairing flow (`[wire-coordinated]` in CHANGELOG; the satellite and the sibling clients need the matching change)

## Shape of the code (CONTRIBUTING, "Shape of the code")

- [ ] Immutable by default; one flow per function; named steps instead of comments.
- [ ] A `when` over a sealed type is exhaustive, with no `else`.
- [ ] No singleton, no mutable static, no discarded result, no lint suppression, no baseline entry.
- [ ] Hot-path code allocates nothing and takes no lock the path did not already take (CONTRIBUTING, "Touching the hot path").
- [ ] Words, sizes and colours reach a layout through `@string`, `@dimen` and `@color`, and screens through `DishNavigator`.

## Tests

- [ ] Red first: a test failed before the change and passes after it.
- [ ] Every new flow has a test, including the empty, error and loading shapes.
- [ ] A new user-facing string is in every locale of its source set (`scripts/check-translations.py`).
- [ ] `scripts/ci-local.sh` passes (format, lint, unit tests, native tests, debug and release builds).
- [ ] Manual check: <!-- the device or emulator, the Android version, the controller and the host you tried -->

## Bookkeeping

- [ ] `CHANGELOG.md` has a line under `[Unreleased]`.
- [ ] `docs/ARCHITECTURE.md`, `docs/contract.md` or `DESIGN.md` is updated if a rule, the wire mapping or a token changed.
- [ ] `PRIVACY.md` and `play/DATA_SAFETY.md` are updated if the app now sends, stores or asks for something new.
