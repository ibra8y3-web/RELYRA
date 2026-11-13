# RELYRA roadmap — verified main status and delivery order

The authoritative source inventory is [MAIN_BRANCH_AUDIT_AR.md](MAIN_BRANCH_AUDIT_AR.md). This roadmap describes source that exists separately from integration/device verification and public infrastructure deployment.

## Verified current baseline

- Android Kotlin/Compose app with Arabic RTL and English resources.
- Ed25519 identity/contact cards, Room + SQLCipher, Android Keystore, biometric/device lock.
- X3DH + Double Ratchet direct messages, durable queue, acknowledgements, retries and duplicate suppression.
- Nostr WSS relay transport; WebRTC P2P DataChannel and voice/video MediaTracks in CallEngine; Bluetooth Classic (paired devices), Wi-Fi Direct, Nearby LAN, and source implementation for opt-in BLE GATT Mesh.
- Go/libp2p Kademlia DHT service, Pebble store, HTTPS gateway, signed presence and coturn configuration/short-lived TURN credentials. Android is an HTTPS gateway client, not a libp2p host. The repository does not host a public DHT/TURN cluster.
- Legacy groups use pairwise Double Ratchet fan-out. New MLS groups use OpenMLS 0.9.2 through Rust/JNI and a separate SQLCipher database.
- Signed community manifests/posts/moderation events, membership/access controls, reports and audit history. Post bodies are plaintext at the discovery service; community index is local to each Pebble-backed service, not globally DHT replicated.
- Blossom encrypted attachment uploads and resumable HTTP Range downloads. Upload itself is not byte-resumable.
- Encrypted Vault, restricted WebView, read-only PDF reader, file inspection and diagnostics.
- Backup/restore code includes database, Vault, inbox/outbox, MLS directory and portable network/server preferences. Restore sanitizes old Ratchet and one-time-prekey state.

## Current work — must close before new phases

### 1. Build and documentation
- CI #43254623203 passed all three jobs on its then-current main commit.
- CI now includes an Android emulator instrumentation job for SQLCipher migration and backup/restore tests. Its result is the acceptance gate for this added coverage.
- Keep Arabic/English README, implementation phases, crosswalk, architecture, networking and MLS design synchronized with the actual code.

### 2. Phase 1 backup/restore verification — no rewrite
Existing BackupManager, BackupArchive, BackupCrypto and RestoreApplier are the system of record. Do not create a parallel backup system.
Required gates:
- SQLCipher encrypted database migration execution against real SQLite/SQLCipher.
- Full encrypted export → stage → apply → reopen integration test, including Vault/inbox/outbox/MLS files and preferences.
- Restore crash recovery/rollback at every filesystem/database/key transition. A test that only round-trips ZIP or crypto is not enough.
- Room/MLS database snapshot consistency while MLS operations are active.
- Actual migration fixtures for every supported historical schema, not only a contiguous-number assertion.

### 3. Remaining MLS coordination
Current implementation uses owner-routed RELYRA control proposals with base group/MLS epoch checks and a single owner epoch writer. These are **not** native RFC 9420 MLS Proposal messages.
Complete native OpenMLS proposal creation, verification, persistence, owner review/commit of accepted pending proposals, delivery/merge to members, stale/conflict handling, restart recovery and tests. Do not replace OpenMLS or write a new MLS state machine.

## New phases — execute only after the current work above is accepted

### Phase 4 — voice/video calls
**Source implementation present:** CallEngine creates audio/video MediaTracks and contains signed call signaling, incoming/outgoing lifecycle, microphone/camera controls, call notifications and conversation UI. Independent-device interoperability and complete lifecycle validation remain pending in Phase 10.

### Phase 5 — multi-device

**Implementation status:** signed manual pairing, per-device Keystore-wrapped keys, encrypted manual direct-message/contact/selected-preference sync, deterministic conflict merge, signed revocation codes, local security notifications, and Arabic/English device UI are now present. Automatic network sync, group history/MLS/Ratchet state, attachments, delegated root-key design, enforcement of revocation across all messaging transports, per-message device authentication, key-rotation propagation and physical-device verification remain outstanding. See [Phase 5 implementation status](PHASE5_MULTI_DEVICE.md) and [Arabic status](PHASE5_MULTI_DEVICE_AR.md).
Secure device enrollment and approval, device inventory, revoke, key rotation, account/device binding, authenticated synchronization, conflict handling and settings UI.

### Phase 6 — offline Mesh
**Source implementation present; physical-device verification pending.** RELYRA has an opt-in Bluetooth LE GATT transport, signed origin and per-hop transit envelopes, an eight-hop limit, 24-hour TTL, replay/deduplication tracking, loop prevention, a Keystore-encrypted local transit queue, a configurable 5–100 MB quota, independent relay consent, and Arabic/English network controls and diagnostics. Mesh envelope and delivery-receipt codec unit tests are present in source; no claim is made about multi-phone interoperability, reliable background operation, or final runtime verification. See [Phase 6 implementation notes](PHASE6_OFFLINE_MESH.md).

### Phase 7 — integrations
**Source implementations present; interoperability verification pending.** The app includes a Matrix Rust SDK E2EE client, an XMPP/OMEMO client, optional Kubo/IPFS encrypted attachment transfer, the authenticated loopback Local API and the constrained declarative JSON plugin workflow. These integrations still require CI and external-server interoperability verification. The Smack Signal OMEMO dependency requires a separate GPL-3.0 distribution-license review. See [Phase 7 status](PHASE7_INTEGRATIONS.md) and [Arabic status](PHASE7_INTEGRATIONS_AR.md).

### Phase 8 — companion tools
**Implementation files and CI coverage added; final CI and operator acceptance pending.** `companion/node` provides Server Studio for the existing discovery Docker Compose deployment, safe operator configuration, lifecycle controls, redacted logs, volume snapshots with pre-restore recovery snapshots, and a Desktop/Node client for the Android loopback API through ADB. The Android Settings hub contains Developer Tools bound to live Local API, Nostr, Mesh and plugin state. The Local API now publishes read-only operational diagnostics and an OpenAPI 3.1 document. Server snapshots are not encrypted by the tool; no remote admin API is exposed. See [Phase 8 English guide](PHASE8_COMPANION_TOOLS.md), [Arabic guide](PHASE8_COMPANION_TOOLS_AR.md), and [Node companion instructions](../companion/node/README.md).

### Phase 9 — audit
SBOM; dependency-license review; independent security review; RTL/accessibility audit; reproducible builds, supply-chain checks and release signing.

### Phase 10 — real devices
Multiple independent phones and identities; different Android versions/networks; NAT/TURN; foreground/background/restart/battery; calls, MLS, attachments, backup/restore and multi-device tests. Physical-device claims are prohibited before evidence is recorded.

## Deferred Phase 3 remainder — after Phases 4–10 only

- Multi-node distributed DHT publish/lookup and presence anti-replay high-water persistence.
- Distributed community directory replication.
- Real TURN allocation, nonce/rate-limit and restart/instance integration tests.
- Per-attempt AdaptiveRouter diagnostics (connector, outcome, latency and failure reason).
- Third-party Store-and-Forward with consent, TTL, quotas and deletion.
- Deployment/operation of a public multi-operator DHT/TURN network.

## Acceptance gate for every feature

A feature is complete only when production behaviour exists, it is reachable through the correct in-app workflow (conversation/settings/notifications/management), permission/error/empty states are handled, Arabic and English UI are present, tests cover success and failure, documentation is updated in the same change, and CI passes on the exact commit. Compilation alone is not feature completion.
