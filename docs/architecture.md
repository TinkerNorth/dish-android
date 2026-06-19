# Architecture

A map of the codebase aimed at people working in it. The protocol contract
lives in `satellite/docs/contract.md` (Android-side mapping:
[`contract.md`](contract.md)); the design-system tokens are in
[`design-system.md`](design-system.md).

## Pipeline

```
gamepad / virtual pad / sensor
        │
        ▼
  Kotlin UI (per-Activity)
        │
        ▼
  hotpath/  ──── direct JNI calls, no allocations
        │
        ▼
  satellite_jni.cpp (NDK)
        │
        ▼
  encrypted UDP ───►  satellite server
```

Controller events enter through `MainActivity.dispatchKeyEvent` /
`dispatchGenericMotionEvent` (or the on-screen virtual pad), get
normalised on the JVM, and cross into the native layer via
`SatelliteNative`. The native side runs the encoder + `sendto` on the
same thread the event arrived on. Discovery, motion, battery, and
touchpad take the same JNI path with their own opcodes.

The REST control plane (pairing, the declarative session/controller
routes, the catalog) **doesn't** go through JNI: TLS lives on the JVM
(`SatelliteHttpClient`) so the NDK build doesn't need OpenSSL. The JNI
layer carries only the encrypted UDP streams plus the enriched
heartbeat ack and the close-notify.

## Top-level packages

```
com.tinkernorth.dish/
  DishApplication           Hilt entry point
  architecture/             base classes + interface contracts
  composer/                 pure derivations from one or more sources
  core/                     input mapping, JNI bindings, models, net helpers
  di/                       Hilt modules
  hotpath/                  per-event code that must not allocate or block
  repository/               SharedPreferences-backed CRUD
  source/                   stateful wrappers (sensors, sockets, BT, stores)
  ui/                       Activities, adapters, custom views
```

`source/` and `composer/` together carry the state graph. `core/` is
the boundary to the satellite (JNI, HTTPS, wire types). `hotpath/`
holds the bits that have to honour the no-allocation rule.

## The hot path

The Kotlin → JNI → `sendto()` chain runs at gamepad polling rate
(≤250 Hz on most controllers) and must never block. The rules:

- No `withContext`, no `runBlocking`, no `Dispatchers.IO` on the
  send path.
- No allocations per event: the JNI uses a preallocated
  `XUSB_REPORT` and a packed `Int` for HID button + hat (a `Pair`
  would burn ~6 KB/s of garbage at 250 Hz).
- The session map's `mutex` is the only lock the send path takes,
  and it's released before the encrypted `sendto`.
- `IP_TOS = 0xB8` (DSCP EF) and `MSG_NOSIGNAL` stay set on every
  send.

Anything that wants to ride the hot path lives in `hotpath/` (e.g.
`GamepadInputProcessor`, `BluetoothGamepadBridge`); code outside that
package is forbidden from making JNI calls from the input thread
besides `sendReport`.

## Base classes: what lives where

The `architecture/` package fixes three contracts. Subclassing the
wrong base is the most common architectural mistake in the codebase;
the package name (`abstracts/` for base classes, `interfaces/` for
pure contracts) is the first hint.

### `AbstractStateSource<S>`

Owns one piece of state, exposes it read-only as a `StateFlow<S>`,
with optional lifecycle hooks.

Use this for any class wrapping a sensor, socket, BroadcastReceiver,
system service, timer, or in-memory cache. Provides:

- `state: StateFlow<S>`: the only public read surface.
- `protected setState(...)`: atomic updates via
  `MutableStateFlow.update`.
- `DefaultLifecycleObserver` integration: opt-in. Sources whose
  lifecycle is registry-managed (e.g. `SatelliteConnection`) simply
  don't override the hooks.

Sources do **not** carry an event channel. Earlier iterations had a
shared `events: SharedFlow<E>` alongside `state`; in practice only
one class actually used it, and that class needed two flows, so the
inheritance only covered half its surface. Rare event-emitting
classes own their own `SharedFlow`s directly
(`SatelliteConnectionManager`, `DishNotifications`). Keep the base
tight: state + optional lifecycle.

A class that is purely a lifecycle hook with no observable state
(e.g. `ConnectionForegroundObserver`, `StreamingServiceController`)
implements `DefaultLifecycleObserver` directly rather than
`AbstractStateSource<Unit>`: a state holder that holds no state is
just noise. Every `AbstractStateSource` subclass therefore has a
meaningful `S`.

### `AbstractComposer<S>`

Pure derivation from one or more upstream flows into a single
`StateFlow<S>`. No external input, no events, no lifecycle of its
own.

The base provides:

- a `StateFlow<S>` named `state`, started **eagerly** on the supplied
  scope so consumers never see a one-frame flicker on Activity
  resume;
- exactly one extension point (`upstream: Flow<S>`) so subclasses
  can't smuggle in extra surface;
- explicit `initial: S` so the StateFlow has a sane value before the
  first emission.

If you find yourself reaching for an event channel inside a composer,
you're not writing an `AbstractComposer`: you're writing an
`AbstractStateSource` that happens to combine inputs. Switch base
classes rather than add the field.

The combine runs on whichever dispatcher `upstream` is collected on
(by default the composer's `scope` dispatcher). UI consumers `collect`
on `Main`.

### `Repository<K, V>` / `KeyedRepository<K, V>`

Synchronous CRUD over a single durable backing store. Lives in
`interfaces/` because it's a pure contract: no inheritable state.

- Shaped like `get / all / put / remove / clear`.
- No flows, no lifecycle, no events, no scope. Repositories are dumb
  storage.
- Threading: implementations must be safe for concurrent calls from
  any thread. The canonical pattern is a private `writeLock: Any`
  serializing read-modify-write of the value list under a single
  SharedPreferences key.
- Every concrete repository extends `AbstractRepositoryContract` (in
  `architecture/testing/`) to inherit the standard property checks.

For reactive reads, wrap a repository in an `AbstractStateSource`
that observes the backing store and republishes. Don't fold
reactivity into the repository itself.

`KeyedRepository<K, V>` is the variant where the value carries its
own id (`keyOf(value: V): K`). Most real repos in this codebase
look like this. The satellite id is `satellite:mid:<machineId>` when the
receiver advertises a stable per-install id, else `satellite:<ip>:<udpPort>`
for older satellites (see `DiscoveredServer.stableKey`). Keying on the stable
id is what stops a receiver's DHCP address change spawning a duplicate row. The
Bluetooth id is `bt:<MAC>`. The distinct `removeValue` name is
deliberate: it avoids a JVM-erasure clash with `remove(K)`.

## Patterns beyond the base classes

The three base classes cover owned state, derived state, and storage.
Five more patterns recur often enough to name, even though four are
conventions (plus one small base class) rather than a shared
supertype. Pick the one that matches what a class actually does: most
architectural drift is a class wearing the wrong one of these.

### Coordinator

Imperative orchestration over several sources, stores, or gateways. A
coordinator owns the cross-cutting commands (bind a slot, connect a
satellite, auto-reconnect) and the invariants that span more than one
store. It does not derive state (that is a composer) and it is not a
lifecycle actuator (that is a controller).

- `ConnectionCoordinator` sequences `SlotBindingStore`,
  `ControllerTypeStore`, and each `SatelliteConnection`, and re-exposes
  `ConnectionsComposer.state` as `connections` directly: no
  coordinator-local mirror, which would create a two-writer race with
  the composer's `onEach`.
- `SatelliteConnectionManager` owns the connect/pair/retry lifecycle
  and the live `SatelliteConnection` map.

Rule: a coordinator may read flows and call commands, but it never
becomes the source of truth for state another class already owns.

### Gateway

The boundary to something outside the process (a socket, an HTTP
endpoint, the native library) behind a narrow Kotlin surface. A
gateway holds no domain state and never derives: it translates a call
into IO and back.

- `DiscoveryGateway` serializes native discovery and per-host HTTP.
- `SatelliteHttpClient` is the HTTPS gateway. The self-signed-cert
  trust manager and trust-on-first-use pinning live here, not in
  callers.
- The JNI wrappers (`ControllerRepository`, `PhysicalInputNative`) are
  gateways over `SatelliteNative`.

A gateway is mockable by construction: callers depend on the gateway
type, never on the raw socket or JNI object, so a test injects a fake.
Name it `*Gateway` or `*Client`, never `*Repository`: a repository is
keyed local storage, not an IO boundary.

### Reducer (pure transition or decision function)

A pure `(state, event) -> result` (or `(inputs) -> decision`) function
with sealed input and output types and no side effects, so the whole
decision space is unit-testable without standing up the machine.
Effects are returned as data and executed by the caller.

- `UsbPathMachine.reduce(UsbController, UsbEvent): Reduction` is the
  canonical full state machine: every `(phase x event)` pair is total,
  and effects (`Claim`, `SetPref`, `MarkFailure`, and so on) are a
  returned list the coordinator runs.
- `reconcileSlots`, `resolveRumble`, `resolvePathChoice`, and the
  registry's placeholder-lifecycle transition are smaller pure decision
  functions lifted out of Android- or JNI-coupled coordinators for the
  same reason.

Rule: keep the transition pure. If you need a socket or a StateFlow
read inside the function, you are writing a coordinator, not a reducer:
read the live state once at the call site and pass it in.

### Mapper

A pure function from domain state to a UI or value shape, with no
Android view types. Mappers are the testable seam under the
humble-object Activities and Views.

- `PathCardMapper`, `motionIndicatorFor`, `satelliteLinkState`,
  `connectionsVisibleInPicker`.

Rule: a mapper takes data in and returns data out. The moment it
touches a `View`, a `Context` for anything but `getString`, or a
StateFlow, it has stopped being a mapper.

### `AbstractController<S>`

A lifecycle actuator: it subscribes to one upstream `Flow<S>` and
turns each value into a side effect (a wakelock, a foreground service,
a Crashlytics opt-in). It is the mirror image of a composer: a composer
derives state from inputs, a controller drives effects from state.

The base (`architecture/abstracts/AbstractController.kt`) provides:

- `protected abstract fun upstream(): Flow<S>` and
  `protected abstract fun apply(value: S)`.
- an idempotent `onStart` (re-entrant lifecycle callbacks do not stack
  collectors), an `onStarting()` hook for setup a subclass must run
  under its own lock before the collector launches, and a
  `cancelCollection()` helper.
- an open `onStop`, because teardown genuinely differs:
  `WakeStateController` cancels and releases its wakelock under the
  `stopped` guard that drops a post-stop emission;
  `StreamingServiceController` cancels and stops the service;
  `CrashReportingController` deliberately does not cancel, so the
  opt-in survives an Activity restart.

Harness: `ControllerProbe` (`architecture/testing/`), the controller
analogue of `ComposerProbe` and `StateSourceProbe`.

Rule: if a class both derives a value and effects something, split it
(derive in a composer, effect in a controller) rather than collapsing
the two.

## Capabilities

"What is and isn't available" (motion, rumble, touchpad, mouse, analog
triggers) is resolved in ONE place so the dashboard, the setup flow, and the
review screen never re-derive it. A capability is a `Feature` with a fixed
`Direction` from the phone's perspective: SEND (gamepad, motion, touchpad,
mouse) rides out, RECEIVE (rumble) rides in. The model is
`core/model/Capability.kt`.

A feature is **available** only when every layer in the path permits it, and
each layer reads its own source of truth:

- **controller** (the input): a live device probe unioned with a static
  known-model DB (`device.hasGyro || native.modelHasImu(...)`). The on-screen
  pad is the phone, so it always sources touchpad/mouse and drives the phone
  vibrator for rumble; a physical pad rumbles only with its own motor (routing
  never falls back to the phone for a physical controller, see "Rumble path").
- **transport** (`composer/TransportProfiles.kt`, static): a Bluetooth host
  carries only the gamepad axes; a Satellite carries everything.
- **type** (the emulated controller): the satellite's own per-type features from
  `CatalogTypeDto.features`, with `composer/BundledCatalog.kt` as the fallback
  for the slugs the app ships or a catalog not yet fetched.
- **host** (the satellite itself): `CatalogDto.hostFeatures` as a `HostFeatureSet`
  (mouse control today; the rest are in `capability-followups.md`).
- **user**: the per-slot toggle stores.

`composer/CapabilityResolver.kt` is the pure Reducer:
`available = controller ∩ transport ∩ type ∩ host`,
`enabled = available ∩ userEnabled`, `live = enabled - runtimeDown` (runtime
health from the apply response). `composer/CapabilityComposer.kt` is the
`AbstractComposer` that feeds the live inputs per slot; its live map is the
reactive surface for bound-slot consumers, while `capabilityForCandidate(...)`
answers "what if the type/host were X" for the draft-editing setup and configure
screens. The setup type-card table renders through the `CapabilityRows` mapper.

The wire caps the satellite is told (`ControllerDescriptor` via
`MotionCapabilityComposer.toCapBits`) are a separate concern, NOT derived here.

## Multi-session model

A session is a `(socket, token, key, counter, heartbeat)` tuple,
identified by a positive integer `handle` returned from `openSocket`.
Every other JNI call that touches a session takes that handle, so
multiple satellites can run side-by-side with independent sockets,
tokens, counters, and heartbeat threads.

The session table lives in `satellite_jni.cpp` under
`g_sessions: std::unordered_map<int, std::shared_ptr<Session>>` with
its own mutex; the hot-path lookup is `getSession(handle)` returning
a shared pointer, after which the lock is released.

Slot bindings (physical Android `deviceId` → which session + which
controller index) live in `g_slots`, also keyed by id, in
`hotpath/input/`. The same physical pad can be reassigned between
satellites without touching the per-slot deadzone configuration:
deadzones are per-device, not per-event.

## Bluetooth lifecycle

Android's `BluetoothHidDevice` profile only allows **one registered
app per process**. The `source/bluetooth/` package squeezes the
multi-slot UI through that constraint without leaking framework
state.

```
BluetoothHidSession (one per process)
  └── AndroidHidProxyClient
        └── BluetoothHidDevice profile proxy
              └── one host at a time

BluetoothGamepadRegistry
  └── projects the single session onto a per-connection-id slot view
```

Key invariants:

- **Tear-down on every restart.** Every transition out of a
  registered state must call `unregisterAndRelease` before
  re-acquiring the proxy. The original "background → return →
  reconnect is dead" bug came from skipping this on restart.
- **Released proxies are ignored.** Callbacks arriving from a proxy
  that's already been released don't drive state changes; the
  session FSM gates on the current proxy identity.
- **Permissions can be revoked between sessions.** `BLUETOOTH_CONNECT`
  can be denied between auto-reconnect runs on API 31+, surfacing
  as `SecurityException`. The session routes that through its events
  channel as a clean `Failed` state instead of throwing.
- **Re-keying.** Discovery starts a session under a transient id
  (`bt-pending-<n>`) because the host MAC is unknown until it
  connects. On the first `BluetoothSessionState.Connected` the
  registry swaps the transient id for `bt:<MAC>`, persists the host
  through `ConnectionStore.rememberBt`, and drops the transient
  entry. The UI sees a single stable row.
- **Stale markers.** `BluetoothBondMonitor` reports `KEY_MISSING` or
  unexpected `BOND_NONE` for remembered MACs; the registry surfaces
  these as a `Stale` lift on `ConnectionCoordinator`. `KEY_MISSING` is the
  more specific signal: if both arrive for the same host, the
  registry promotes `BOND_REMOVED` to `KEY_MISSING` so the user
  copy reads *"Re-pair X"* instead of the weaker *"X was unpaired"*.

## Rumble path

Rumble flows the opposite direction to the input hot path:

```
satellite                                  dish-android
  │                                              │
  ├─ MSG_RUMBLE (encrypted UDP) ────────────────►├─ receiveAck (Dispatchers.IO)
                                                 │   ├─ decrypt + parse
                                                 │   └─ JNI → RumbleBridge.dispatchRumble
                                                 │         └─ RumbleRouter → actuator
```

`receiveAck` runs on `Dispatchers.IO`, which is JVM-attached, so the
JNI side calls into Java directly with no `AttachCurrentThread`
ceremony and no dispatcher thread. The dispatch is synchronous on
the JNI caller.

`RumbleRouter` routes each `MSG_RUMBLE` to the device that owns the
targeted slot (reverse-resolved from the session `handle` + `ctrlIdx`
through `SatelliteConnection.slots`) instead of always buzzing the
phone: the on-screen pad drives the phone vibrator, a framework gamepad
drives its own `InputDevice` vibrator, and a claimed USB-direct pad
gets a device-specific report written to its USB OUT endpoint
(`SatelliteNative.sendUsbRumble` to `usbhost::sendRumble` to
`usbparsers::runRumble`). Routing is strict: a pad with no usable
actuator stays silent rather than buzzing the phone. Slot-kind routing,
magnitude/duration mapping, the USB report layouts, and the satellite
feature requests are in [`rumble.md`](rumble.md).

Pure rumble helpers (`rumbleMagnitudeTo255`, `rumbleSafeDurationMs`,
`resolveSlotId`, `classifyTarget`) are covered by
`RumbleBridgeHelpersTest.kt` and `RumbleRouterTest.kt`.

## Host-testable C++ split

`app/src/main/cpp/` is split so the parts of the JNI that the JVM
can't reach are still unit-testable from a host build:

- `gamepad_input.{cpp,h}`: pure gamepad-input processing. Owns
  per-device button/axis state, the keycode → XUSB bit mapping, and
  the send-on-change gate. No Android, JNI, or sodium headers: it
  builds against `app/src/test/cpp/` with googletest.
- `wire_encoders.h`: pure byte-layout encoders for `MSG_MOTION`,
  `MSG_BATTERY`, `MSG_TOUCHPAD`, and `MSG_LIGHTBAR`. Same host-build
  rule; the wire layout is type-checked against the contract
  (`satellite/docs/contract.md`).
- `satellite_jni.cpp`: the Android-only glue. Owns sockets, libsodium,
  the session map, the rumble + Bluetooth callbacks, and the JNI
  registration. This file does **not** ship pure helpers: anything
  testable belongs in one of the headers above.

The split is the reason `gamepad_input.h` mirrors Android keycodes
as integer constants instead of including `<android/keycodes.h>`:
the constants must compile on host, and the values match Android's
stable input ABI.

## Activities and navigation

Dish uses per-Activity navigation, wired through a single
`androidx.navigation` graph at `res/navigation/nav_graph.xml`. All
destinations are `<activity>` nodes; `ActivityNavigator` translates
each `navigate(actionId)` call into `startActivity(Intent)` with the
action's arguments applied as extras (argument names map 1:1).

`DishNavigator` (`ui/common/`) is a thin typed wrapper so every
navigation site is a compile-time check rather than a runtime
"extra missing" branch:

```kotlin
nav.toGamepad(connectionId = cid, usePsLayout = true)
nav.toTouchpad(connectionId = cid, touchpadMode = mode, slotId = slotId)
```

The input overlays (`GamepadOverlayActivity`, `TouchpadOverlayActivity`)
intentionally bypass the activity-transition scaffolding and the
edge-to-edge inset wiring: they prioritise zero-latency display and
own their own immersive-mode setup through `BaseInputOverlayActivity`.

Overlay input is event-driven; the shared resend loop exists only to
heal a lost edge frame (button-up, finger-up, stick-to-neutral) over
plain UDP. Pacing is `ui/common/ResendPacer`: a changed state is
re-sent 3 scheduler ticks in a row (50 ms apart), then the stream
idles at a 1 Hz keepalive. There is no constant-rate re-send.
