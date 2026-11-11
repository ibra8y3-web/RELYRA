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

**Current source status: experimental WebRTC direct transport added on branch phase-2-internet-p2p; release gate remains open.**

Implemented in source:
- Android WebRTC DataChannel transport using ICE connectivity checks and default STUN servers.
- Optional user-supplied TURN URI and credentials for networks that require relay candidates.
- Signed, recipient-bound, ten-minute offer/answer envelopes using RELYRA Ed25519 identities, with a signed random session ID to reject answers from stale connection attempts.
- Signed offers/answers are exchanged automatically inside E2EE RELYRA control envelopes over an enabled Nostr route; manual copy/paste remains fallback. Existing known-contact checks are required.
- Incoming offers are queued for explicit user approval; a verified signature alone does not create a WebRTC peer connection.
- Per-peer sends are serialized; DataChannel success requires a bounded receiver ACK. Peer count, buffered bytes, SDP callback and ICE gathering waits are bounded.
- Binary encrypted DeliveryEngine payloads pass through the DataChannel; AdaptiveRouter prefers a connected direct path and retains Nostr fallback.
- DataChannel frame size is bounded and larger attachments remain on the Blossom path.

Still missing before Priority 2 can be declared complete:
- No automatic Internet DHT or unknown-peer discovery; signaling uses known contacts and the user's enabled Nostr relay route.
- Project-operated TURN service, short-lived credentials and operational abuse controls.
- Automatic ICE restart after network changes and full disconnect-mid-send delivery regression tests.
- Real-device verification across independent Wi-Fi and mobile carrier NATs, including TURN-only and symmetric NAT cases.
- Clean CI success on the exact phase-2 branch.

WebRTC was selected for Android direct connectivity rather than adding the previously evaluated JVM libp2p candidate, whose Android production status, DHT and hole-punching gaps were documented in LIBP2P_EVALUATION.md. This does not claim libp2p, DHT or Circuit Relay v2 support.

Acceptance criteria remain:
- Two independently installed devices authenticate each other using signed signaling and the RELYRA identity, not an IP address or WebRTC transport identifier.
- A message is delivered over a verified direct path and is not duplicated when fallback occurs.
- Tests cover offline peer, NAT failure, disconnect mid-send, malicious peer, TURN fallback and Nostr fallback.
- The UI reports the actual path, never a simulated connection status.
- Tested devices, Android versions, ICE candidate pair types and exact workflow run are recorded.

See INTERNET_P2P_WEBRTC.md for protocol workflow, consent model, privacy implications and validation steps.

## Priority 3 — group protocol and community layer

**Goal:** move beyond per-member group fan-out only after a group-key protocol is reviewed.

Work items:
- Evaluate MLS and the required credential, epoch, key-package and membership lifecycle.
- Define group history, member removal and key rotation semantics.
- Add group attachments and group read receipts with explicit privacy controls.
- Design community discovery, moderation and abuse controls separately from private group messaging.

Acceptance criteria:
- Removed members cannot decrypt messages sent after the committed removal epoch.
- Membership changes converge safely under concurrent updates.
- Group state can recover after device restart without silently accepting stale membership.
- Protocol tests cover forged commits, replay, delayed messages and member churn.

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
