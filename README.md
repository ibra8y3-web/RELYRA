<p align="center"><img src="docs/assets/relyra-cover.svg" alt="RELYRA — Private by design. Connected by choice." width="100%"></p>
<p align="center"><a href="README_AR.md">العربية</a> · <a href="docs/ARCHITECTURE.md">Architecture</a> · <a href="docs/NETWORKING.md">Networking</a> · <a href="docs/ROADMAP.md">Roadmap</a> · <a href="docs/SPECIFICATION_CROSSWALK_AR.md">Specification status</a> · <a href="LICENSE">Apache 2.0</a></p>
<p align="center">
<a href="https://github.com/ibra8y3-web/RELYRA/actions/workflows/build.yml"><img src="https://github.com/ibra8y3-web/RELYRA/actions/workflows/build.yml/badge.svg?branch=main" alt="Build status"></a>
<img src="https://img.shields.io/badge/Android-10%2B-3DDC84?logo=android&logoColor=white" alt="Android 10+">
<img src="https://img.shields.io/badge/Kotlin-2.4.21-7F52FF?logo=kotlin&logoColor=white" alt="Kotlin 2.4.21">
<img src="https://img.shields.io/badge/Release-0.9.0-64748b" alt="Version 0.9.0">
<img src="https://img.shields.io/badge/License-Apache--2.0-blue" alt="Apache License 2.0">
</p>

<h1 align="center">RELYRA</h1>
<p align="center"><strong>Private by design. Connected by choice.</strong></p>
<p align="center">An Android communication project built around local identity, end-to-end encrypted messaging, and user-controlled network relays.</p>

> **Current implementation:** The Android app supports Nostr WSS, WebRTC P2P DataChannel, and opt-in nearby Bluetooth Classic (already-paired devices), Wi-Fi Direct, and Nearby LAN for known contacts. The repository also contains a deployable Go/libp2p Kademlia DHT service, HTTPS gateway and coturn configuration; Android connects to the HTTPS gateway and is not itself a libp2p host. No public multi-operator DHT/TURN network is hosted by this repository. The diagrams below separate working code from operational infrastructure and planned capabilities.

## At a glance

| **What works today** | **How it connects** | **Build & explore** |
|:--|:--|:--|
| Local identity and encrypted storage | Nostr over user-managed WSS relays | Kotlin · Jetpack Compose |
| Direct messages and groups | Encrypted payloads before transport | Android 10+ · JDK 17 |
| Encrypted attachments and local vault | Blossom for file blobs | Gradle 9.6.0 · CI workflow |

## What is RELYRA?

RELYRA is a native Android app written in **Kotlin** with **Jetpack Compose and Material 3**. It keeps identity and private app data on the device, encrypts message contents before transport, and lets the user choose the relay and file servers the app connects to.

The current source includes local identity, SQLCipher storage, direct conversations, groups and channels, Nostr relay transport, encrypted file transfer, diagnostics, passphrase-protected backup/restore, and an encrypted local vault.

## Current implementation status

The current `main` branch contains the following implemented code. A green CI run confirms the build and automated tests listed in the workflow; it does **not** prove interoperability between independent phones or operation of a public DHT/TURN network.

| Area | Current implementation |
|---|---|
| Identity and local security | Ed25519 identity and signed contact cards; Room + SQLCipher; database key protected by Android Keystore; device/biometric lock |
| Direct messaging | X3DH + Double Ratchet, durable outgoing queue, acknowledgements, retry/backoff and duplicate suppression |
| Groups | Legacy groups use per-member Double Ratchet fan-out. New MLS groups use OpenMLS 0.9.2 through Rust/JNI, with KeyPackage, Welcome, Commit, ApplicationData, membership changes and persisted epochs |
| MLS attachments and receipts | Group attachment descriptors travel in MLS ApplicationData; encrypted blobs use Blossom. Per-recipient group read receipts exist as authenticated RELYRA control messages (not MLS ApplicationData) |
| Communities | Go discovery service and Android client; signed manifests/posts/moderation events, private invitations/access proofs, membership controls, reports and audit records. Community post bodies are currently stored as plaintext at the discovery service; signatures provide authenticity, not end-to-end confidentiality |
| Network | Nostr WSS relays; WebRTC P2P DataChannel; signed presence through an operator-deployed Go/libp2p Kademlia service and HTTPS gateway; coturn configuration and short-lived TURN credentials |
| Nearby connections | Bluetooth Classic for already-paired devices, Wi-Fi Direct and Nearby LAN; authenticated known-contact handshakes. These are not BLE mesh or multi-hop relaying |
| Files and local tools | Encrypted Blossom attachments, resumable HTTP Range downloads, local encrypted Vault, restricted WebView, read-only PDF viewer, file inspection and diagnostics |
| Backup and restore | Passphrase-protected archive includes the database, Vault, inbox/outbox, MLS data and portable network/server preferences; archive paths, sizes and hashes are checked; restore stages data and supports guarded rollback |
| UI | Arabic RTL and English resources, conversations, group/community screens, network settings and diagnostics |

### Known limits in `main`

- The Android app uses the HTTPS discovery gateway; it is not itself a libp2p DHT host. Public discovery requires operators to deploy and configure multiple reachable nodes.
- There is no public DHT/TURN cluster operated by this repository. Real NAT/TURN and independent-device interoperability testing remains pending.
- Presence anti-replay high-water persistence, a true multi-node DHT integration test, distributed community-directory replication, third-party Store-and-Forward, and per-attempt routing diagnostics remain outstanding on `main`.
- The WebRTC implementation is a data channel, not voice/video MediaTracks.
- MLS membership requests are routed through the group owner, who serializes epoch writes. Native multi-admin RFC 9420 Proposal merging and distributed fork reconciliation are not implemented.
- Community post content is not end-to-end encrypted from the discovery service.
- Blossom downloads support HTTP Range resume; uploads are not byte-resumable.
- Backup archive/crypto tests exist, but full SQLCipher RestoreApplier end-to-end, crash-at-each-restore-stage, Room/MLS cross-database consistency and real migration-fixture integration tests remain to be closed.
- No multi-device enrollment/sync, Bluetooth LE multi-hop mesh, Matrix/XMPP bridges, Plugin SDK/runtime, Local API, Server Studio, desktop/developer tools, independent security audit or final physical-device test matrix yet.

The current source intentionally does not label these remaining items as complete.

## Visual system maps

### 1. Network map — what is connected today

Nostr is the registered messaging transport in this build. Dashed/grey nodes are future work, not active connections.

<p align="center"><img src="docs/assets/relyra-network-map.svg" alt="RELYRA current network map" width="100%"></p>

### 2. Application architecture

<p align="center"><img src="docs/assets/relyra-architecture.svg" alt="RELYRA architecture map" width="100%"></p>

### 3. Message and attachment flow

<p align="center"><img src="docs/assets/relyra-message-flow.svg" alt="RELYRA message and encrypted file flow" width="100%"></p>

Read the [Architecture guide](docs/ARCHITECTURE.md), [Networking guide](docs/NETWORKING.md) and [Nearby transport notes](docs/NEARBY_TRANSPORTS.md) for implementation detail.

## How a message travels

1. Create a fresh message ID and persist the outgoing message in the encrypted outbox.
2. Encrypt the payload for the recipient.
3. The adaptive router tries the currently registered, ready connectors (WebRTC P2P, Wi-Fi Direct, Bluetooth Classic, Nearby LAN and Nostr) and records the successful path.
4. A nearby connector authenticates the other device with a mutual Ed25519 challenge and transfers only the end-to-end encrypted payload; Nostr signs an event and publishes it to enabled WSS relays.
5. The recipient verifies, decrypts and stores the message before acknowledging it.
6. Automatic retries reuse the same message ID; a new user send gets a new ID.

**Attachments use a separate path:** encrypt the file locally, upload only ciphertext to a configured Blossom server, then send the file descriptor inside the end-to-end encrypted message. Downloads are manual and verified before use.

## Nearby transports

Public Internet presence discovery is optional and off by default. The app can publish a signed three-minute RELYRA identity lease to an operator-run Kademlia DHT through HTTPS gateways. Finding presence never adds a contact or authorizes messaging. WebRTC direct sessions use signed E2EE signaling and bounded recovery for previously approved peers. The deployable DHT/TURN service templates live in services/discovery; the repository does not host public services for users.\n\nBluetooth Classic, Wi-Fi Direct and Nearby LAN are optional and off by default. Bluetooth requires the phones to be paired first in Android Settings. Wi-Fi Direct requires the user to choose a discovered device and form a Wi-Fi P2P group. Nearby LAN requires both phones to share a Wi-Fi network or local hotspot. All three verify the RELYRA Ed25519 identity against an existing contact before accepting an encrypted message.

These transports are for nearby devices only. They do not provide Internet P2P, multi-hop mesh or Store & Forward through third-party phones. The public RELYRA ID is visible on a local network when Nearby LAN is enabled; use it only on a trusted network. See [Nearby transport details](docs/NEARBY_TRANSPORTS.md).

## Security and privacy

- Message content is encrypted before it reaches a relay.
- Local app data is stored in an SQLCipher-encrypted database; key material is protected with Android Keystore.
- Only known contacts with usable key material can exchange accepted messages.
- A relay transports encrypted events; it is not trusted with plaintext.
- WSS does **not** hide your IP address from the relay. Relays may observe connection timing, routing tags and ciphertext size.
- File servers can observe transfer metadata (such as IP, time and size) but receive encrypted blobs.
- RELYRA has not had an independent cryptographic security audit. This README is not a security certification.

## Not implemented yet

See the detailed limits in **Current implementation status** above. The next scheduled work is: (1) fix/verify the current build pipeline, (2) reconcile the main-branch inventory and documentation, (3) close Phase 1 backup/restore integration-test gaps without rebuilding existing backup code, and (4) finish the remaining MLS coordination work. Only after these are complete will Phases 4–10 be implemented and integrated into the Android app. The remaining Phase 3 network items (distributed DHT, distributed community directory, TURN integration, route diagnostics and third-party Store-and-Forward) are explicitly deferred until after those phases.

## Build from source

**Android requirements:** Android SDK Platform 37.0, Build Tools 37.0.0, JDK 17 and Gradle 9.6.0. **Service requirements:** Go 1.26 and Docker Compose for the discovery/TURN services. The repository currently does not contain a root Gradle Wrapper; use the Gradle version configured by the project/CI.

Run from the repository root:

```bash
gradle --no-daemon assembleDebug
gradle --no-daemon testDebugUnitTest

cd services/discovery
go test -race ./...
go vet ./...
```

Debug APK output:

```text
app/build/outputs/apk/debug/
```

GitHub Actions builds the debug APK, runs Android JVM tests, tests/vets the Go discovery service and builds its container on pushes, pull requests and manual dispatch. Download APK and test reports from the run's **Artifacts** section. Check the latest workflow result before treating a build as verified.

## Repository map

```text
app/src/main/java/org/relyra/app/
├── core/
│   ├── backup/       Passphrase-protected backup and staged restore
│   ├── crypto/       Identity, payload crypto, contact cards and ratchet
│   ├── database/     Room entities, DAOs, migrations and SQLCipher
│   ├── diagnostics/  Device and application diagnostics
│   ├── identity/     Identity and contact repositories
│   ├── messaging/    Delivery engine, envelopes and groups
│   ├── network/      WebRTC P2P, DHT HTTPS client, Nostr, LAN, Bluetooth and Wi-Fi Direct
│   ├── notify/       Message notifications
│   ├── storage/      Private attachment storage
│   ├── tools/        On-device file inspection tools
│   ├── transfer/     File encryption and Blossom transfer
│   └── vault/        Encrypted local file vault
└── ui/               Compose screens, navigation and theme

app/src/test/          JVM tests
services/discovery/      Go Kademlia DHT gateway, coturn/Caddy deployment and protocol tests
docs/                  Architecture, networking, roadmap, specification crosswalk and visual maps
.github/workflows/     Build and test automation
```

## Development principles

- **Real functionality only:** no fake connection indicators, demo messages or placeholder buttons.
- **Security first:** encrypt before transport; validate identities and inputs; document metadata exposure.
- **Tests with each feature:** success, failure, retry, malformed input and recovery.
- **Honest status:** a planned class or UI screen is not a completed capability.
- **Arabic and English:** new user-facing strings should support both languages and correct RTL layout.

## Contributing

Issues and pull requests are welcome. For substantial changes, describe the threat model, protocol choice, Android lifecycle impact and test plan. Do not publish exploitable security details in a public issue; coordinate disclosure privately.

---

<p align="center"><strong>RELYRA · Private by design. Connected by choice.</strong></p>
