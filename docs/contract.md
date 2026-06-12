# Dish ↔ Satellite contract: client notes

The protocol contract (REST surface, UDP streams, crypto, liveness, identity)
lives in ONE place: **`satellite/docs/contract.md`** in the
[TinkerNorth/satellite](https://github.com/TinkerNorth/satellite) repo. This
client implements protocol 1 against it; this file only records the
Android-side mapping. The former `wire-format.md` is replaced by the contract.

## Where the contract lands in this app

| Contract concept | Android home |
|---|---|
| `hmacProof`, HKDF session key | `core/net/SessionCrypto.kt` (pinned interop vectors in `SessionCryptoTest`) |
| ControllerDescriptor JSON | `core/net/ControllerDescriptor.kt` |
| REST routes (PUT/GET/DELETE session + controllers, pair, unpair, catalog) | `core/net/SatelliteHttpClient.kt` via `core/net/DiscoveryGateway.kt` |
| Wire DTOs (SessionResponse, apply results, catalog) | `core/model/Models.kt` |
| Session lifecycle, desired vs applied slots, epoch reference | `source/connection/SatelliteConnection.kt` |
| Connect flow, terminal 401, exponential backoff, reconcile, self-unpair | `source/connection/SatelliteConnectionManager.kt` |
| UDP streams + enriched heartbeat ack + close-notify (socket/crypto only) | `app/src/main/cpp/satellite_jni.cpp` |
| Catalog cache (ETag, per-satellite) | `repository/SatelliteCatalogRepository.kt` |

## Client behaviours required by the contract

- **Declarative topology.** A bind carries the FULL descriptor (type, caps,
  touchpad routing); the satellite plugs the right virtual device on the
  first try. While live, single-slot changes ride
  `PUT /api/connections/{id}/controllers/{idx}` so the session token never
  rotates for a toggle; the full-session `PUT /api/connections` runs only on
  connect and reconcile.
- **Terminal 401.** `code: NOT_PAIRED | BAD_PROOF` drops the stored pairing
  key, marks the satellite row "needs pairing", and STOPS retrying. Only a
  re-pair recovers.
- **Reconcile loop.** Every heartbeat ack carries the session epoch and the
  active-controller bitmap. On mismatch with the last applied state the
  client GETs the session; if applied ≠ desired it re-PUTs (free, the server
  converges).
- **Close-notify (0x000F).** Authenticated and immediate: `unpaired` is
  terminal, `replaced` stays down (the replacement session is already live),
  `shutdown`/`kicked` re-enter the bounded backoff (1 s → 60 s cap).
- **Host-feature grant.** `hostFeatures.mouseControl` is requested in the
  session PUT and the server computes the grant ONLY there. A mid-session
  toggle to `mouse` leaves wants ≠ granted even after a successful
  controller PUT, so `syncSlot` escalates to the reconcile path, whose
  full session re-PUT carries the request. The grant is part of the
  applied view the reconcile GET compares against.
- **Identity.** Satellites are keyed on `machineId` only. The pairing key is
  never used on the UDP path: each session derives
  `HKDF(pairingKey, sessionSalt, token)`.
- **Catalog.** The "Emulate" picker renders from `GET /api/catalog`
  (Accept-Language, ETag-cached). Known slugs (`xbox360`, `ds4`) keep bundled
  labels; an unknown type still renders from server strings; unknown
  feature/hostFeature slugs are silently not offered.
