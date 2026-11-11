# RELYRA

**Private, Android-first communication built around local identity, end-to-end encrypted messaging, and user-controlled relays.**

[العربية](README_AR.md) · [Architecture](docs/ARCHITECTURE.md) · [Networking](docs/NETWORKING.md) · [Roadmap](docs/ROADMAP.md)

![RELYRA architecture](docs/assets/relyra-architecture.svg)

![Build](https://github.com/ibra8y3-web/RELYRA/actions/workflows/build.yml/badge.svg?branch=main)

> **Project status:** source currently reports version **0.9.0** (Phase 8b). This describes implemented code paths, not a claim that every feature has passed real-device testing or an independent security audit.

## What RELYRA is

RELYRA is a native Android messaging application built with Kotlin, Jetpack Compose and Material 3. It keeps identity and encrypted application data on the device, lets the user choose secure WebSocket relays, and encrypts attachments before sending them to a file server.

The current network path is **Nostr over user-configured WSS relays**. RELYRA has extension points for more transports, but direct peer-to-peer networking is not implemented yet.

## Implemented capabilities

| Area | Present in the current source |
|---|---|
| Android app | Kotlin, Jetpack Compose, Material 3; Android 10+ (minSdk 29) |
| Languages & appearance | Arabic RTL and English; light, dark and AMOLED themes |
| Identity | On-device Ed25519 identity, public ID, fingerprint, signed contact card and recovery key |
| Local security | SQLCipher-encrypted Room database; database secret protected by Android Keystore; biometric/device-credential app lock |
| Direct messaging | Persistent outbox, delivery state machine, retries, deduplication and authenticated encrypted payloads |
| Message encryption | X25519/HKDF/AES-GCM payload protection and one-to-one Double Ratchet sessions |
| Network | User-added WSS relays, Nostr event signing/verification, relay probing, inbox subscription, reconnect and publish acknowledgement |
| Groups & channels | Membership roles and delivery to members using separate one-to-one ratchet sessions |
| Attachments | Client-side encrypted files transferred through configured Blossom-compatible servers |
| Background work | WorkManager retries and optional visible foreground connection service |
| Local tools | Hashing, hex view, strings, MIME detection, EXIF inspection, archive listing/extraction and APK metadata inspection |
| Diagnostics | Relay/server checks, queue counts, database integrity, storage/device status and a redacted diagnostic report |
| Backup & vault | Passphrase-protected database backup/restore and a separate encrypted local file vault |

### Important implementation boundaries

- Group messages are encrypted separately for each recipient using the existing one-to-one sessions. **MLS is not implemented.**
- Nostr relays can observe connection metadata such as IP address, timing and routing information. Payload encryption does not hide all metadata.
- Attachments are encrypted on the device before upload. The configured file server stores ciphertext, not the original file.
- Remote delivery while the app is closed is subject to Android background limits, battery policy and user force-stop.
- Backups do not include downloaded attachments, vault files or relay/file-server configuration. Restoring an old ratchet-session snapshot can affect conversations that continued after the backup was created.
- The app has not had an independent cryptographic/security audit. Review the security notes before relying on it for high-risk communications.

## Message and attachment flow

1. The sender creates a new message ID and stores the outgoing item in the encrypted local outbox.
2. The payload protector encrypts the message for the recipient.
3. The adaptive router selects a ready registered transport. In this build, the registered transport is Nostr.
4. The Nostr connector signs an event and publishes it to enabled WSS relays.
5. The recipient verifies the event, authenticates and decrypts the payload, stores the message, then sends an acknowledgement.
6. Retries reuse the same message ID; a new user send always receives a fresh ID.

For files, the app encrypts the file locally, uploads the encrypted blob to a configured Blossom server, then sends the encrypted file descriptor through the messaging path.

See [the architecture guide](docs/ARCHITECTURE.md) and [networking guide](docs/NETWORKING.md) for diagrams and boundaries.

## Build

### Requirements

- Android SDK platform 35 and build-tools 35.0.0
- JDK 17
- Gradle 8.10.2
- Android Studio compatible with the Android Gradle Plugin 8.7.3

The repository currently does **not** include the Gradle wrapper. On a machine with Gradle 8.10.2 installed, run:

    gradle --no-daemon assembleDebug
    gradle --no-daemon testDebugUnitTest

The debug APK is produced under:

    app/build/outputs/apk/debug/

GitHub Actions runs the same debug build and JVM unit-test tasks for pushes, pull requests and manual dispatch. Download the APK and test reports from the workflow run's **Artifacts** section. Check the latest workflow run before treating a build as verified.

## Repository layout

    app/src/main/java/org/relyra/app/
      core/
        backup/       Passphrase-protected backup and staged restore
        crypto/       Identity, payload, ratchet and contact-card cryptography
        database/     SQLCipher Room database, entities and DAOs
        diagnostics/  Device and application diagnostics
        identity/     Identity and contact repositories
        messaging/    Delivery engine, envelopes and group messaging
        network/      Nostr, relay client, pool and adaptive router
        notify/       Message notification handling
        storage/      Encrypted local attachment storage
        tools/        On-device file inspection tools
        transfer/     Blossom transfer and file encryption
        vault/        Encrypted local vault
      ui/             Compose screens, navigation and theme
    app/src/test/     JVM tests for core behaviours
    docs/             Architecture, networking, roadmap and diagrams

## Not implemented yet

These are planned capabilities, not hidden or partially working UI controls:

- Direct peer-to-peer transport, libp2p and ICE/STUN/TURN traversal
- Tor routing and Bluetooth/Wi-Fi Direct mesh
- MLS group encryption, group file attachments and group read receipts
- Communities, public group discovery and a community relay/node network
- Voice/video calls and multi-device synchronization
- Matrix/XMPP interoperability
- Plugin SDK/runtime, local API, browser, PDF reader and Server Studio
- Independent security audit and broader real-device validation

The delivery sequence and acceptance criteria are tracked in [ROADMAP.md](docs/ROADMAP.md).

## Contributing

Please keep new capabilities real and testable:

1. Implement the feature behind a clear core contract.
2. Add unit tests for success, failure, retry and malformed input paths.
3. Connect it to the UI only after the real implementation exists.
4. Document platform limits, metadata exposure and recovery behaviour.
5. Do not add demo data, fake connection states or placeholder actions.

## Security reports

Do not publish exploitable details in a public issue. Use GitHub's private vulnerability reporting for this repository when available, or contact the maintainers privately before disclosure.
