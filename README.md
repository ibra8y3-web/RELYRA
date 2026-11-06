# RELYRA — Phases 1-5 + Phase 6a (encrypted file transfer)

Native Android (Kotlin, Jetpack Compose, Material 3). Min Android 10, target 15.

## Build
Push to GitHub. `.github/workflows/build.yml` installs the SDK, Gradle 8.10.2, resolves every
dependency, builds `app-debug.apk` and runs the unit tests. Download the APK from the run's **Artifacts**.
No Gradle wrapper is committed; CI provisions Gradle itself. Locally: `gradle assembleDebug`.

## Working in this build
- Local identity: Ed25519 keys (Google Tink), generated on device, seed wrapped by Android Keystore (AES-256-GCM)
- Public ID with checksum, fingerprint, signed QR contact card, tamper detection
- Recovery key (Base32 + checksum) to restore the same identity on a new device
- Contacts: add by scanning QR / pasting link / bare ID, verify, block, delete
- SQLCipher-encrypted Room database, random passphrase held in Keystore, no destructive migrations
- App lock (biometric or device credential), FLAG_SECURE on recovery screen, local security log
- Arabic (RTL) and English, light/dark, adaptive + themed launcher icon
- Backups disabled (`allowBackup=false`) so keys never leave the device

## Phase 3: delivery engine (working, no network yet)
- Chats tab and chat screen; messages stored in the encrypted DB (schema v2, explicit migration 1->2)
- `DeliveryEngine`: new MessageID per user send (identical text = separate messages), same ID on automatic retries,
  receiver saves-then-acks, repeats are only re-acked, state machine QUEUED > SENT > RELAYED > DELIVERED > READ
  (+ FAILED / EXPIRED / CANCELLED, never backwards), exponential backoff, 7-day TTL, cancel while queued,
  optional read receipts, unknown/blocked senders dropped, periodic WorkManager retry
- `Transport` interface for connectors. **No connector is registered yet** (Nostr arrives in Phase 4), so queued
  messages stay "Waiting to send". Wire format is not end-to-end encrypted until Phase 5; the chat screen says so.
- 20+ JVM tests cover duplicate vs. intentional resend, lost acks, state order, TTL, codec fuzz inputs.

## Phase 4: network layer
- Nostr (NIP-01): secp256k1/BIP-340 signing (secp256k1-kmp), key derived from the identity seed, hand-written
  canonical serialization, event verification, relay protocol parser
- Relay manager: user-added `wss://` relays only (none by default), enable/disable/remove, real connection test
  (WebSocket open + REQ/EOSE, reports latency)
- `AdaptiveRouter` + `Connector` interface; Network screen and Home status show real relay data
- Sending over Nostr is gated by `Capabilities.PAYLOAD_ENCRYPTION` (enabled in Phase 5).
- Not done: libp2p / direct P2P / STUN (no mature Android library; planned as a separate step)

## Phase 5: end-to-end encryption + real delivery
- Contact card v2 (signed) carries Ed25519 + X25519 + Nostr keys; DB schema v3 (migration 2->3)
- `PayloadCrypto`: sender-authenticated ECIES (X25519 ephemeral+static, HKDF-SHA256, AES-256-GCM) from Tink primitives;
  recipient ID bound into AAD; impersonation / tampering / wrong-recipient tests
- Nostr connector is live: signed kind-4747 events tagged to the recipient, publish with relay OK, inbox
  subscription with reconnect, backlog sync in the periodic worker
- Contacts added before Phase 5 need to re-scan a new card (their encryption keys were never exchanged)
- **Known limits (honest):** no Double Ratchet yet, so no forward secrecy against theft of a recipient's long-term key;
  relays see sender/recipient keys and timing; receiving while the app is closed relies on the 15-minute worker
  (a foreground service is a later step); Android may delay background work.

## Phase 6a: encrypted files over Blossom (v0.6.0)
- **Fix:** the manifest had no `INTERNET` permission, so relay connections could not work on a real device. Added.
- Files are encrypted on the device before upload: Tink AES-GCM-HKDF streaming (1 MiB authenticated segments, fresh random
  key per file, truncation/tampering/reordering detected). Max 100 MB.
- Blossom client (BUD-01/02): `PUT /upload` with a signed kind-24242 auth event (throw-away key per upload by default,
  identity key optional per server), `GET /<sha256>` with HTTP Range resume, size and SHA-256 verified before decrypting.
- Only a small descriptor (name, size, hashes, one-time key, server) travels inside the E2EE message; the server holds ciphertext.
- Same retry/dedup rules as text: one MessageID per send, descriptor resent with the same ID, **no re-upload** on retry,
  receiver stores once and re-acks. Uploads never block text sending.
- Downloads are always manual. Server addresses from other people must be https and may not point at private/local networks.
- File servers are added by the user (none by default); "Network" screen has add/test/enable/remove.
- DB schema v4 (migration 3->4, `attachments` table). Files live in app-private storage; "Save a copy" uses the system file picker.
- While the app is open the outbox is retried every 30 s (backoff still applies).
- **Limits (honest):** no chunk-level upload resume (Blossom has none: an interrupted upload restarts); the server sees your IP
  and file size; no in-app viewer, folders, multi-file send or auto-download yet.

## Phase 6b: Double Ratchet (v0.6.1)
- One-to-one messages now use the Double Ratchet (X25519, HKDF-SHA256, HMAC-SHA256, AES-256-GCM from Tink): forward
  secrecy and recovery after a key compromise, out-of-order delivery (up to 5000 skipped keys), replay/forgery rejected.
  A failed or forged packet never changes the stored session. Sessions live in the SQLCipher database (schema v5).
- Start-up without prekeys: both sides derive a shared secret from their long-term keys; the side whose public ID sorts
  first starts the ratchet. The other side sends the older sealed-box format (no forward secrecy) **until Alice's first
  message arrives**, then switches. Old-format messages are still accepted.
- Encryption is skipped when no relay is enabled, so ratchet keys are not burned while offline.
- **Limits (honest):** if one side loses its session (reinstall, restore on a new device) messages stop decrypting;
  delete the contact and add it again to start a new session. Group (MLS) and multi-device sessions are not done.
  Wire/session design has not been externally reviewed.

## Not in this build (not shown in the UI)
MLS, groups, calls, libp2p/P2P, Tor, mesh, vault,
browser, documents, backup/export, Server Studio, Local API, plugins. See the roadmap phases 3-12.

## Notes
Single Gradle module with packages mirroring the planned layers (`core/crypto`, `core/database`,
`core/identity`, `ui/...`) to keep CI builds fast and reliable; modules can be split later.
