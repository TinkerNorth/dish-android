# Capability follow-ups (satellite contract wish-list)

The client now resolves every feature through one place (see the "Capabilities"
section of [`architecture.md`](architecture.md)). A feature is available only
when each layer permits it: the input controller, the link transport, the
emulated controller **type**, the satellite **host**, and the user's toggle. Type
features come from the satellite catalog (`CatalogTypeDto.features`); host
features come from `CatalogDto.hostFeatures`.

Items 1–5 below are **resolved** (2026-06): the satellite contract now exposes the
fields and the client reads them. They are kept here as a record of the contract
decision and the residual non-contract work. Item 6 remains tracked-only. Direction
is from the phone's perspective: SEND = phone to host, RECEIVE = host to phone.

## 1. Rumble-return as an explicit host feature (RECEIVE) — DONE

- **Context.** Rumble is a RECEIVE feature: the host streams `MSG_RUMBLE` back to
  the phone. The client used to assume any satellite returns rumble.
- **Shipped.** The catalog publishes `hostFeatures.rumble.supported` and the
  capabilities `host.rumble`. `HostFeatureSet.fromCatalog` reads it
  presence-aware: an ABSENT slug keeps the optimistic `true` (back-compat with
  satellites predating it), a PRESENT `false` hides rumble for that host.
- **Acceptance met.** A satellite that reports `rumble.supported = false` hides
  the rumble row/chip with no client change.

## 2. Keyboard emulation as a host feature (SEND) — DONE (contract), blocked downstream

- **Context.** `Feature.KEYBOARD` is modeled (SEND) but never offered.
- **Shipped.** The catalog/capabilities publish `keyboardControl.supported` and
  `HostFeatureSet.fromCatalog` reads it (opt-IN: absent → unsupported). The
  hardwired `false` is gone.
- **Residual (not a contract gap).** The satellite reports
  `keyboardControl.supported = false` because no host keystroke-injection backend
  exists yet, and no phone-side keyboard input source produces `Feature.KEYBOARD`.
  Keyboard stays correctly unoffered until BOTH land; flip the satellite trait and
  add the phone source then.

## 3. Touchpad pad-vs-mouse modes in the catalog — DONE

- **Context.** "DS4 pad" mode is a type feature; "mouse" mode is host injection.
  The client used to infer pad mode from the PlayStation type id.
- **Shipped.** `CatalogFeatureDto.modes` carries per-type mode slugs; the DS4
  `touchpad` advertises `["ds4"]`. `CapabilityResolver.typeCapabilities` gates
  `Feature.TOUCHPAD` (the pad) on the type declaring the `ds4` mode (empty modes →
  legacy fallback). Mouse stays host-gated via `hostFeatures.mouseControl`.
- **Acceptance met.** A type advertising a touchpad with only `mouse` mode (or a
  pad with no `ds4` lineage) gates the pad off while keeping host mouse.

## 4. Host capability presence — DONE

- **Context.** A holistic host view: does the receiver expose a catalog, mouse,
  keyboard, rumble. The client used to treat "we fetched a catalog" as "has one".
- **Shipped.** `GET /api/server/capabilities` carries a `host` block enumerating
  the receiver's own capabilities. `SatelliteCapabilitiesRepository` probes it and
  seeds the host layer via `HostFeatureSet.fromServerCapabilities`
  (`setIfAbsent`, so a richer catalog still wins). `host.catalog.supported` is the
  presence signal; its absence means an older satellite (fall back to the default).
- **Acceptance met.** The host layer reflects the real receiver before any catalog
  round-trip.

## 5. Pre-bind host runtime capability read — DONE

- **Context.** Runtime health (motion `backendOk`) arrived only in the apply
  response, after a bind. Pre-bind setup could not show transient host state.
- **Shipped.** The capabilities probe also populates `SatelliteHostRuntimeStore`
  (motion backend up/down). `CapabilityComposer.capabilityForCandidate` reads it
  via `candidateRuntimeDownLayer`, so the pre-bind report shows a feature as
  present-but-currently-down. (The mouse-control grant remains a post-bind
  per-session decision by design — it is policy, not a static probe.)
- **Acceptance met.** The setup capability table can show a feature present but
  currently-down before the user binds.

## 6. Lightbar (RECEIVE) has no Android sink

- **Context.** `CAP_LIGHTBAR` exists on the wire and `Feature.LIGHTBAR` is
  modeled for a complete type/host view, but Android exposes no LED API, so the
  controller layer never produces it and it is never "available" locally.
- **Where.** `Feature.LIGHTBAR` (`core/model/Capability.kt`).
- **Task.** None on the contract; revisit only if a device LED sink appears.
- **Acceptance.** n/a (tracked so the modeled-but-unoffered feature is not
  mistaken for a bug).
