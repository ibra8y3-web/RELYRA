# RELYRA architecture — current main branch

This document describes implemented source. Planned features are not represented as active services until their end-to-end path is connected.

## Layers

| Layer | Current implementation |
|---|---|
| UI | Jetpack Compose screens/navigation, Arabic RTL and English resources |
| Application | AppContainer, ViewModels, WorkManager and optional foreground connection service |
| Identity/security | Ed25519 identity/contact cards, Android Keystore, device/biometric lock |
| Messaging | X3DH, Double Ratchet, durable queue, ACK/retry/deduplication, group engine |
| MLS | OpenMLS 0.9.2 Rust/JNI engine, separate SQLCipher database, KeyPackage/Welcome/Commit/ApplicationData |
| Persistence | Room + SQLCipher, encrypted local Vault and app-private attachment directories |
| Transport | Nostr WSS, WebRTC P2P DataChannel, Bluetooth Classic RFCOMM, Wi-Fi Direct, Nearby LAN and opt-in BLE GATT Mesh (Phase 6; runtime verification pending) |
| Discovery service | Separate Go/libp2p Kademlia DHT + Pebble datastore + HTTPS gateway and presence API |
| TURN | coturn deployment configuration and signed short-lived REST credentials; actual public service deployment is operator-owned |
| File transfer | Locally encrypted attachment payloads and Blossom-compatible storage; downloads support HTTP Range resume |
| Communities | Go service and Android client for signed manifests/posts/moderation, membership, reports and audit |
| Backup | Encrypted archive, passphrase crypto, staged restore, rollback artifacts and key/session sanitization |
| Local tools | Vault, restricted WebView, read-only PDF viewer, file inspection and diagnostics/resource sampling |

## Direct message path

1. The UI submits a message to the delivery engine.
2. The engine persists a stable message ID and outgoing state.
3. X3DH/Double Ratchet protects the payload for the recipient.
4. AdaptiveRouter sends the opaque protected payload through an available registered connector.
5. The recipient authenticates, decrypts, persists and deduplicates the message before acknowledging.
6. Sender state advances only from authenticated delivery/relay acknowledgement.

Transport connectors must never receive plaintext message bodies or private ratchet keys.

## Group path

- Legacy groups fan out a separately protected copy through one-to-one sessions.
- MLS-enabled groups protect group application payloads with OpenMLS ApplicationData.
- Current membership request handling is owner-routed at the RELYRA application layer. This is not yet native RFC 9420 Proposal coordination across admins.
- Group attachments put the descriptor inside MLS ApplicationData; encrypted file bytes travel through Blossom.
- Group read receipts are authenticated per-recipient RELYRA control messages, not MLS ApplicationData.

## Discovery and Internet transport boundary

The Android application calls the discovery service's HTTPS gateway. The Go service runs libp2p Kademlia and stores records in Pebble. The Android application is not itself a libp2p DHT host. The repository contains deployment configuration, not a live public multi-operator DHT/TURN network. Operators must deploy reachable nodes and configure bootstrap, DNS, firewall and TURN.

WebRTC in the current application is a P2P DataChannel. It is not voice/video MediaTracks.

## Backup/restore boundary

BackupManager exports the SQLCipher database, Vault, inbox/outbox, MLS directory and portable network/server preferences. It validates referenced attachment files and Vault content, then packages an authenticated archive protected by the user's passphrase. Restore authenticates and stages the archive before applying a guarded database/directory/key/preferences swap. Restored Ratchet sessions and one-time prekeys are deliberately invalidated to prevent cryptographic rollback.

The implementation exists; full SQLCipher restore, crash-recovery and Room/MLS consistency integration tests are the remaining verification work.

## Security boundaries

- Relays and discovery services may observe IP addresses, timing, routing metadata and ciphertext sizes.
- Community post bodies are currently plaintext at the discovery service; do not treat signatures as end-to-end encryption.
- Blossom stores encrypted blobs but can observe transfer metadata.
- Local database secrets are wrapped by Android Keystore. Device compromise while unlocked remains a risk.
- Backup confidentiality depends on the user's passphrase; the app does not store that passphrase.

## Source map

- `core/crypto`: identity, contact cards and cryptographic primitives.
- `core/database`: Room entities, DAOs, migrations and SQLCipher initialization.
- `core/messaging`: delivery engine, envelope codec, groups and MLS orchestration.
- `core/network`: Nostr, WebRTC and nearby transports.
- `core/transfer`: file encryption and Blossom.
- `core/backup` and `core/vault`: backup/restore and encrypted local files.
- `services/discovery`: Go/libp2p DHT gateway, presence and community service.
- `native/mls`: OpenMLS Rust/JNI engine.
- `ui`: Compose workflows.
