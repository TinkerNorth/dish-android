# Capability follow-ups (satellite contract wish-list)

The client now resolves every feature through one place (see the "Capabilities"
section of [`architecture.md`](architecture.md)). A feature is available only
when each layer permits it: the input controller, the link transport, the
emulated controller **type**, the satellite **host**, and the user's toggle. Type
features come from the satellite catalog (`CatalogTypeDto.features`); host
features come from `CatalogDto.hostFeatures`.

The items below are the places the client still has to **assume** because the
contract does not expose them yet. Each is marked in code with
`// TODO(capability-contract):`. When the satellite side
(`satellite/docs/contract.md`) gains the field, delete the placeholder and read
it. Direction is from the phone's perspective: SEND = phone to host, RECEIVE =
host to phone.

## 1. Rumble-return as an explicit host feature (RECEIVE)

- **Context.** Rumble is a RECEIVE feature: the host streams `MSG_RUMBLE` back to
  the phone. Today the client assumes any satellite returns rumble.
- **Where.** `HostFeatureSet.fromCatalog` / `SATELLITE_DEFAULT` set
  `rumbleReturn = true` unconditionally (`core/model/Capability.kt`).
- **Task.** Publish a host feature (e.g. `hostFeatures.rumble.supported`) so the
  host layer gates rumble instead of always allowing it.
- **Acceptance.** A satellite that cannot return rumble reports it, and the
  rumble row/chip hides for that host without a client change.

## 2. Keyboard emulation as a host feature (SEND)

- **Context.** `Feature.KEYBOARD` is modeled (SEND, phone to host) but never
  offered: there is no host feature for it and no phone-side source yet.
- **Where.** `HostFeatureSet.keyboardControl` is hardwired `false`
  (`core/model/Capability.kt`).
- **Task.** Add `hostFeatures.keyboardControl` (mirroring `mouseControl`,
  including any `modes`) so a host that injects keyboard input advertises it.
- **Acceptance.** With the field present, keyboard becomes a real host-gated
  capability; without it, it stays unoffered.

## 3. Touchpad pad-vs-mouse modes in the catalog

- **Context.** "DS4 pad" mode is a type feature (the emulated DualSense has a
  touchpad); "mouse" mode is host injection. The client currently infers pad
  mode from the PlayStation type and mouse mode from `hostFeatures.mouseControl`.
- **Where.** `CapabilityComposer`/`CapabilityResolver` read
  `CatalogTypeDto.features["touchpad"]` for the pad and
  `hostFeatures.mouseControl` for the mouse.
- **Task.** Make the catalog explicit: `features["touchpad"].modes` (or the
  existing `hostFeatures.mouseControl.modes`) so the offered modes are read, not
  inferred from the type id.
- **Acceptance.** A type advertising a touchpad with only `mouse` mode (or a
  future type with a pad but no DS4 lineage) gates correctly.

## 4. Host capability presence (does this satellite have a catalog at all?)

- **Context.** The user asked for a holistic host view: does the receiver expose
  a catalog, mouse emulation, keyboard emulation, rumble return. The client
  currently treats "we fetched a catalog" as "has a catalog".
- **Where.** `HostFeatureSet.hasCatalog` is set `true` only after a successful
  fetch (`core/model/Capability.kt`); it is informational and not yet read by any
  gate.
- **Task.** A small host descriptor (in discovery, the session response, or a
  dedicated `GET /api/host`) enumerating the host's own capabilities, so the host
  layer is read rather than assumed-optimistic (`SATELLITE_DEFAULT`).
- **Acceptance.** The host layer reflects the real receiver before any catalog
  round-trip, and the holistic host view is complete.

## 5. Pre-bind host runtime capability read

- **Context.** Runtime health (motion `backendOk`, the mouse-control grant)
  arrives only in the session/controller apply response, i.e. after a bind. The
  pre-bind setup screens cannot show transient host state (backend down, no
  virtual-pad driver installed) until the user commits.
- **Where.** `RuntimeRefinement` is empty pre-bind; `runtimeDown` is populated
  only from `SatelliteMotionBackendStatus` after an apply.
- **Task.** A read-only host runtime probe (e.g. `GET /api/host`) the setup flow
  can call before binding.
- **Acceptance.** The setup capability table can show a feature as present but
  currently-down before the user binds.

## 6. Lightbar (RECEIVE) has no Android sink

- **Context.** `CAP_LIGHTBAR` exists on the wire and `Feature.LIGHTBAR` is
  modeled for a complete type/host view, but Android exposes no LED API, so the
  controller layer never produces it and it is never "available" locally.
- **Where.** `Feature.LIGHTBAR` (`core/model/Capability.kt`).
- **Task.** None on the contract; revisit only if a device LED sink appears.
- **Acceptance.** n/a (tracked so the modeled-but-unoffered feature is not
  mistaken for a bug).
