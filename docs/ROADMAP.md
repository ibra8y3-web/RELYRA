# RELYRA roadmap and completion criteria

This roadmap separates source code that is already present from features that still need implementation. A screen, interface or planned class does not count as a completed capability until the underlying behaviour works and is tested.

## Current source baseline — Phase 8b

| Capability | Current status |
|---|---|
| Android app, Compose UI, Arabic/English | Implemented in source |
| Local identity and contact cards | Implemented in source |
| SQLCipher storage and Android Keystore integration | Implemented in source |
| One-to-one payload encryption and Double Ratchet | Implemented in source |
| Persistent delivery queue and acknowledgements | Implemented in source |
| Nostr WSS relay transport | Implemented in source |
| Optional same-LAN transport via NSD/mDNS and TCP | Added in current implementation batch; real-device verification required |
| Bluetooth Classic RFCOMM for already-bonded contacts | Added in current implementation batch; mutual Ed25519 identity check; device tests required |
| Wi-Fi Direct discovery, group connection and authenticated TCP transport | Added in current implementation batch; Android permission/vendor and two-device tests required |
| Durable local outbox and relay backlog | Implemented for sender retries and Nostr relay retrieval; no third-party peer Store & Forward |
| Groups/channels using per-member one-to-one sessions | Implemented in source; not MLS |
| Blossom encrypted attachment transfer | Implemented in source |
| Background notifications and optional foreground connection service | Implemented in source; Android restrictions apply |
| Local file tools, diagnostics and redacted report | Implemented in source |
| Live resource sampling (app CPU, app UID RX/TX rate, app-filesystem free space) | Added in current implementation batch; CI/device validation required |
| Passphrase backup/restore and encrypted local vault | Implemented in source; documented exclusions apply |

## Priority 1 — validate and harden the existing release

**Goal:** establish a reproducible, verified baseline before adding more transports.

Acceptance criteria:
- Latest main-branch GitHub Actions build completes successfully.
- All JVM tests pass from a clean checkout.
- Install and launch on Android 10+ and a current Android release.
- Verify identity creation/recovery, contact exchange, direct encrypted message, retry after relay outage, notification permission and background behaviour.
- Verify upload/download of an encrypted attachment, backup/restore and vault integrity on a real device.
- Fix every reproducible crash or data-loss issue and retain regression tests.
- Document tested devices, Android versions and exact workflow run.

## Priority 2 — direct connectivity

**Current source status: implementation and deployable services are now present on branch phase-2-p2p-rendezvous; release gate remains open for CI and operator/device validation.**

Implemented in source:
- Android WebRTC DataChannel transport using ICE connectivity checks and default STUN servers.
- Optional user-supplied TURN URI and credentials for networks that require relay candidates.
- Signed, recipient-bound, ten-minute offer/answer envelopes using RELYRA Ed25519 identities, with a signed random session ID to reject answers from stale connection attempts.
- Signed offers/answers are exchanged automatically inside E2EE RELYRA control envelopes over an enabled Nostr route; manual copy/paste remains fallback. Existing known-contact checks are required.
- Incoming offers are queued for explicit user approval; a verified signature alone does not create a WebRTC peer connection.
- Per-peer sends are serialized; DataChannel success requires a bounded receiver ACK. Peer count, buffered bytes, SDP callback and ICE gathering waits are bounded.
- Binary encrypted DeliveryEngine payloads pass through the DataChannel; AdaptiveRouter prefers a connected direct path and retains Nostr fallback.
- DataChannel frame size is bounded and larger attachments remain on the Blossom path.

Implemented in this branch at source level:
- A RELYRA-specific Kademlia DHT service in services/discovery, using libp2p Go nodes, a persistent Pebble datastore, a namespaced record validator, and HTTPS publish/lookup API.
- Android HTTPS gateway configuration, explicit opt-in public presence, signed three-minute Ed25519 presence leases, exact-public-ID lookup and local verification before displaying a result. Lookup does not create or trust a contact.
- A coturn service definition, bounded relay-port configuration, Caddy HTTPS gateway and short-lived TURN REST credentials. TURN credentials are issued only after a signed identity challenge and are never persisted on the Android device.
- Automatic bounded peer-session recreation and signed offer/answer renegotiation after ICE failure or network change. Only previously approved peers can be auto-answered; the first incoming offer still requires explicit approval.
- JVM regression coverage for DHT record verification, URL validation, bounded reconnect backoff, direct-send acknowledgement loss, Nostr fallback and receiver duplicate suppression.

Remaining release gates:
- The repository provides deployable service files, but no public RELYRA DHT/TURN infrastructure is hosted by this repository. Multiple independently reachable DHT nodes must be deployed and configured with bootstrap multiaddresses before public discovery works across operators.
- Clean CI success on the exact final phase-2 head, including Go service tests/vet/container build and Android JVM tests.
- Device and network verification is intentionally left to the project owner: Wi-Fi-to-mobile, carrier NAT/symmetric NAT, TURN-only, app backgrounding and network handover.

The Android app uses WebRTC for its DataChannel. It does not embed a libp2p host. The distributed Kademlia overlay is provided by the separate Go service; Android clients use its HTTPS gateway.

Acceptance criteria remain:
- Two independently installed devices authenticate each other using signed signaling and the RELYRA identity, not an IP address or WebRTC transport identifier.
- A message is delivered over a verified direct path and is not duplicated when fallback occurs.
- Tests cover offline peer, NAT failure, disconnect mid-send, malicious peer, TURN fallback and Nostr fallback.
- The UI reports the actual path, never a simulated connection status.
- Tested devices, Android versions, ICE candidate pair types and exact workflow run are recorded.

See INTERNET_P2P_WEBRTC.md and services/discovery/README.md for protocol workflow, consent model, service deployment, privacy implications and validation steps.

## Priority 3 — group protocol and community layer

**Current status: incremental group read receipts are implemented on branch `phase-3-group-protocol`; MLS, group attachments and public community services remain unfinished.**

Implemented in this phase branch:
- Optional, per-recipient group read receipts. Each receipt refers to the exact message-copy ID delivered to one member; the sender records it only when group, copy ID and recipient all match its durable outbox row.
- A SQLCipher Room migration adds the read timestamp without destructive migration or loss of existing group/outbox data.
- Read-receipt sending follows the existing user privacy preference. Local read state is saved even when sending receipts is disabled.
- Group UI reports delivered and read counts separately. Regression tests cover member-copy binding, preference-off behavior and receipt codec validation.
- `docs/MLS_AND_COMMUNITY_DESIGN.md` records the MLS integration decision, identity binding, epoch/removal semantics, attachment model and separate community security model.

Still required:
- Integrate a pinned, reviewed OpenMLS implementation through a small Android native bridge; the current production group engine remains pairwise Double Ratchet fan-out and is **not MLS**.
- Implement real MLS KeyPackage, Welcome, Proposal and Commit lifecycle, epoch persistence and restart recovery.
- Implement group attachments through encrypted Blossom blobs; the current attachment engine is one-to-one only.
- Implement community manifests, opt-in discovery, signed moderator events, reporting, replay protection and server-side abuse/rate controls. These are design-only, not runtime features.
- Test membership changes, removals, restart recovery, concurrent commits, malicious proposals and member churn on independent clients.

Acceptance criteria:
- Removed members cannot decrypt application messages created after the committed MLS removal epoch.
- Membership changes converge under concurrent proposals and reject forged, stale or replayed commits.
- Group state recovers after restart without accepting a stale transcript.
- Group attachments are encrypted before Blossom upload and verified before opening.
- Read receipts are optional, authenticated and bound to the correct recipient/message copy.
- Community moderator actions are signed, authorized, replay-protected and rate limited at the service.
- CI passes from a clean checkout and supported Android ABIs interoperate.

See `docs/MLS_AND_COMMUNITY_DESIGN.md` for the protocol design and explicit implementation boundaries.

## Priority 4 — privacy network options

Current source:
- An external Tor SOCKS5 proxy is supported for the shared OkHttp client. Strict mode omits the DIRECT fallback, and the Tor Project check endpoint verifies whether the exit is a Tor exit.
- A locally installed Tor service (for example, Orbot) is still required. RELYRA does not bundle Tor, manage Bridges or provide Onion Services.
- Bluetooth Low Energy is not implemented; the current Bluetooth transport is Classic RFCOMM over already-paired devices.
- Nearby LAN, Bluetooth and Wi-Fi Direct are local transports and are not routed through Tor SOCKS.

Remaining work:
- Evaluate whether a bundled Tor service is viable and supportable on Android, including its lifecycle and battery impact.
- Decide whether RELYRA needs its own bridge/onion-service management or should continue delegating that to the installed Tor application.
- Continue documenting metadata exposure and the exact scope of Strict Mode.

Acceptance criteria:
- The app clearly states which transport is active and what metadata it reveals.
- Strict Tor tests confirm SOCKS is the only route and fail closed when the local proxy is unavailable; non-strict fallback is visibly warned.
- No nearby device is trusted based only on discovery.
- Background execution and battery restrictions are tested on supported Android versions.
- Two Android devices complete Bluetooth Classic and Wi-Fi Direct handshake/message tests on supported vendors and OS releases.

## Priority 5 — interoperability and platform services

Potential work items:
- Matrix/XMPP bridges with explicit end-to-end trust boundaries.
- Multi-device identity/session management and secure device revocation.
- Voice/video call signalling and media transport.
- Plugin SDK/runtime, local API, Server Studio, desktop/node companion applications and Developer Studio.
- The restricted WebView and read-only PDF reader exist; full browser and document editing remain separate scope.

These are separate projects, not current capabilities. Each requires a threat model, API contract, permission model and tests before UI exposure.

## Release gates

Do not mark a milestone complete until all of the following are true:
1. Real implementation exists; no demo data or placeholder action.
2. Unit tests cover normal, failure, retry, malformed and adversarial input.
3. Integration tests prove the feature against a real protocol endpoint.
4. Android lifecycle, permission and battery behaviour are understood.
5. English and Arabic strings are complete.
6. Documentation states limitations and metadata exposure.
7. CI passes from a clean checkout.


## Additional resource monitoring work

The current implementation batch adds a real `ResourceManager` sampler. It reads Android process CPU elapsed time, per-UID receive/transmit counters and available bytes on the app's filesystem. First-sample and unsupported counters remain unknown rather than being represented as zero. JVM tests cover the pure CPU/rate calculations and counter-reset handling.

The resource screen reports only what Android exposes for the RELYRA process/app UID. It is not a system-wide CPU profiler or a packet inspector.
