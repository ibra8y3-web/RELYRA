<p align="center"><img src="docs/assets/relyra-cover.svg" alt="RELYRA — Private by design. Connected by choice." width="100%"></p>
<p align="center"><a href="README_AR.md">العربية</a> · <a href="docs/ARCHITECTURE.md">Architecture</a> · <a href="docs/NETWORKING.md">Networking</a> · <a href="docs/ROADMAP.md">Roadmap</a> · <a href="docs/SPECIFICATION_CROSSWALK_AR.md">Specification status</a> · <a href="LICENSE">Apache 2.0</a></p>
<p align="center">
<a href="https://github.com/ibra8y3-web/RELYRA/actions/workflows/build.yml"><img src="https://github.com/ibra8y3-web/RELYRA/actions/workflows/build.yml/badge.svg?branch=main" alt="Build status"></a>
<img src="https://img.shields.io/badge/Android-10%2B-3DDC84?logo=android&logoColor=white" alt="Android 10+">
<img src="https://img.shields.io/badge/Kotlin-2.0.21-7F52FF?logo=kotlin&logoColor=white" alt="Kotlin 2.0.21">
<img src="https://img.shields.io/badge/Release-0.9.0-64748b" alt="Version 0.9.0">\n<img src="https://img.shields.io/badge/License-Apache--2.0-blue" alt="Apache License 2.0">
</p>

<h1 align="center">RELYRA</h1>
<p align="center"><strong>Private by design. Connected by choice.</strong></p>
<p align="center">An Android communication project built around local identity, end-to-end encrypted messaging, and user-controlled network relays.</p>

> **Current implementation:** Version 0.9.0. The active message paths are opt-in Nearby LAN for known contacts on the same local network, plus Nostr over user-configured secure WebSocket relays (WSS). The diagrams below separate working paths from planned capabilities. They are system illustrations, not screenshots of the running app.

## At a glance

| **What works today** | **How it connects** | **Build & explore** |
|:--|:--|:--|
| Local identity and encrypted storage | Nostr over user-managed WSS relays | Kotlin · Jetpack Compose |
| Direct messages and groups | Encrypted payloads before transport | Android 10+ · JDK 17 |
| Encrypted attachments and local vault | Blossom for file blobs | Gradle 9.6.0 · CI workflow |

## What is RELYRA?

RELYRA is a native Android app written in **Kotlin** with **Jetpack Compose and Material 3**. It keeps identity and private app data on the device, encrypts message contents before transport, and lets the user choose the relay and file servers the app connects to.

The current source includes local identity, SQLCipher storage, direct conversations, groups and channels, Nostr relay transport, encrypted file transfer, diagnostics, passphrase-protected backup/restore, and an encrypted local vault.

## Features implemented in the current source

| Area | Included |
|---|---|
| **Identity** | On-device Ed25519 identity, public ID and fingerprint, signed contact cards, QR/link exchange and recovery key |
| **Local security** | Room + SQLCipher; database key protected with Android Keystore; biometric/device-credential lock |
| **Direct messages** | Persistent outbox, acknowledgements, delivery states, retries, backoff and duplicate suppression |
| **Message encryption** | X25519/HKDF/AES-GCM and one-to-one Double Ratchet sessions |
| **Network** | User-added WSS relays, Nostr event signing/verification, relay probing, inbox subscription and reconnect |
| **Groups / channels** | Membership roles and per-member message delivery using individual sessions |
| **File transfer** | Client-side encrypted files over user-configured Blossom-compatible servers |
| **Background work** | WorkManager retries and optional visible foreground connection service (subject to Android restrictions) |
| **On-device tools** | Hashes, hex/strings inspection, MIME detection, EXIF, archive inspection and APK metadata |
| **Diagnostics and resources** | Relay/server tests, database integrity, battery and memory, live app CPU and per-app RX/TX rates, filesystem availability and redacted report |
| **Backup / vault** | Passphrase-protected database backup/restore and encrypted local file vault |
| **UI** | Arabic RTL and English; light, dark and AMOLED themes |

## Visual system maps

### 1. Network map — what is connected today

Nostr is the registered messaging transport in this build. Dashed/grey nodes are future work, not active connections.

<p align="center"><img src="docs/assets/relyra-network-map.svg" alt="RELYRA current network map" width="100%"></p>

### 2. Application architecture

<p align="center"><img src="docs/assets/relyra-architecture.svg" alt="RELYRA architecture map" width="100%"></p>

### 3. Message and attachment flow

<p align="center"><img src="docs/assets/relyra-message-flow.svg" alt="RELYRA message and encrypted file flow" width="100%"></p>

Read the [Architecture guide](docs/ARCHITECTURE.md) and [Networking guide](docs/NETWORKING.md) for implementation detail.

## How a message travels

1. Create a fresh message ID and persist the outgoing message in the encrypted outbox.
2. Encrypt the payload for the recipient.
3. Select a ready registered transport. Nostr is the active connector in this source.
4. Sign a Nostr event and publish it to the user's enabled WSS relays.
5. The recipient verifies, decrypts and stores the message before acknowledging it.
6. Automatic retries reuse the same message ID; a new user send gets a new ID.

**Attachments use a separate path:** encrypt the file locally, upload only ciphertext to a configured Blossom server, then send the file descriptor inside the end-to-end encrypted message. Downloads are manual and verified before use.

## Nearby LAN discovery

The optional local transport can work without Internet when two devices share the same Wi-Fi network or local hotspot. It is off by default. Enabling it advertises your public RELYRA contact ID on that local network and only accepts discovery matches for contacts already in your address book. Use it only on a network you trust. This is not Bluetooth, Wi-Fi Direct or Internet P2P.

## Security and privacy

- Message content is encrypted before it reaches a relay.
- Local app data is stored in an SQLCipher-encrypted database; key material is protected with Android Keystore.
- Only known contacts with usable key material can exchange accepted messages.
- A relay transports encrypted events; it is not trusted with plaintext.
- WSS does **not** hide your IP address from the relay. Relays may observe connection timing, routing tags and ciphertext size.
- File servers can observe transfer metadata (such as IP, time and size) but receive encrypted blobs.
- RELYRA has not had an independent cryptographic security audit. This README is not a security certification.

## Not implemented yet

The following are **not** present in the current build:

- Internet P2P across separate networks, libp2p and ICE/STUN/TURN traversal.
- Tor routing, Bluetooth mesh and Wi-Fi Direct. Nearby LAN is not Wi-Fi Direct.
- MLS group encryption. Current group messages are encrypted separately for each member using one-to-one sessions.
- Group file attachments and group read receipts.
- Communities, public group discovery and a RELYRA-operated relay/node network.
- Voice/video calls and multi-device synchronization.
- Matrix/XMPP interoperability, plugin runtime, Local API, browser, PDF reader and Server Studio.

Other limits: Android may defer background work due to battery policy or force-stop; backups exclude downloaded attachments, vault files and relay/file-server settings; restoring an older ratchet snapshot can affect conversations that continued after backup creation. See [Networking](docs/NETWORKING.md) and [Roadmap](docs/ROADMAP.md).

## Build from source

**Requirements:** Android SDK Platform 35, Build Tools 35.0.0, JDK 17 and Gradle 9.6.0. The repository does not currently include a Gradle Wrapper.

Run from the repository root:

```bash
gradle --no-daemon assembleDebug
gradle --no-daemon testDebugUnitTest
```

Debug APK output:

```text
app/build/outputs/apk/debug/
```

GitHub Actions builds the debug APK and runs JVM tests on pushes, pull requests and manual dispatch. Download APK and test reports from the run's **Artifacts** section. Check the latest workflow result before treating a build as verified.

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
│   ├── network/      Nostr protocol, relay clients and adaptive router
│   ├── notify/       Message notifications
│   ├── storage/      Private attachment storage
│   ├── tools/        On-device file inspection tools
│   ├── transfer/     File encryption and Blossom transfer
│   └── vault/        Encrypted local file vault
└── ui/               Compose screens, navigation and theme

app/src/test/          JVM tests
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
