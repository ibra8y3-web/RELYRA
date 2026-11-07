# RELYRA — Phases 1-6b + 7a (groups) + 7b (everyday completeness) + 8a (backup) + 8b (vault)

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

## Phase 7a: groups and broadcast channels (v0.7.0)
- Chats tab now has **Direct | Groups**. Create a group (everyone posts) or a channel (only owner/admins post), add members
  from your contacts, rename, promote/demote admins, remove members, transfer ownership, leave, delete the local copy.
- **Encryption is NOT MLS.** Each group packet is encrypted separately for every member through the existing one-to-one
  Double Ratchet session (the design of the first Signal groups). A removed member simply stops being an addressee, so no
  group key rotation is needed. Cost: sending grows with the member count (max 200). Every member must be one of your
  contacts with an encryption key; members who are not your contacts are shown but cannot exchange messages with you.
- Each message copy has its own MessageID, is stored by the receiver before it is acknowledged, retried with the same ID
  and shown once. The sender sees "Delivered to X of Y" from real acknowledgements. Identical text sent twice is two messages.
- Authority is decided from each receiver's own copy of the group, never from what a packet claims: an unknown group is
  only an invitation the user must accept; a membership snapshot is applied only when its sender is owner/admin locally and
  its epoch is higher; admins cannot touch the owner/admin set; in a channel members cannot post.
- DB schema v6 (migration 5->6: `chat_groups`, `group_members`, `group_messages`, `group_outbox`).
- 14 JVM tests cover invitations, acceptance, delivery, lost acks, channel posting rights, forged snapshots from outsiders and
  plain members, admin limits, removal, leaving, ownership transfer and codec fuzzing.
- **Limits (honest):** no read receipts or file attachments in groups yet; two admins editing at the same moment can briefly
  diverge (higher epoch wins, equal epochs are ignored); a member who is not your contact cannot be reached; no MLS,
  no public/discoverable groups, no communities yet. Not tested on real devices yet.

## Phase 7b: notifications, background receiving, tools, diagnostics (v0.8.0)
Filled the gaps around the core so every screen has something real to do. No simulated data anywhere.
- **Notifications** for new direct messages, group messages and group invitations. By default a notification only says who
  wrote; the text is shown only if the user turns that on. Nothing shows for the chat you are reading or a muted chat.
  Android 13+ permission is requested from Home or Settings.
- **Stay connected** (Settings, off by default): a visible foreground service (`remoteMessaging` type) keeps the existing relay
  connections alive so messages arrive while the app is closed; restarts after reboot/update only if enabled. Costs battery.
  Android still decides what happens when the user force-stops the app or restricts it.
- **Chats**: last message + time + unread badge, search (list and inside a chat), pin, mute, archive, clear conversation,
  copy / forward (sent as a new message) / delete-on-this-device for messages. Same for groups.
  Local-only choices live in the encrypted DB (schema v7, migration 6->7: `chat_prefs`).
- **Empty screens now lead somewhere**: Home has a getting-started list driven by real state (relay added, contact added,
  notifications allowed); Chats and Create-group offer "Add contact"; Network offers one-tap *suggested public servers*
  (third-party, not enabled silently, testable). The wrong "all contacts already in the group" message is fixed.
- **Share sheet**: share text or one file from any app to RELYRA and pick the recipient.
- **File tools** (all on-device, nothing uploaded or run): SHA-256/SHA-1/MD5 with compare, hex viewer, strings, real file type
  from content, EXIF viewer + save a JPEG copy without location/device tags, text/JSON viewer, ZIP/APK/JAR/DOCX listing with
  preview and extract, APK inspector (package, version, SDK, permissions, signing certificate SHA-256).
- **Diagnostics**: live relay/server status and tests, queue/failed/expired counts, database integrity check, storage use,
  battery, network type, memory, notification and battery-optimization state, and a redacted report (no names, IDs, keys,
  addresses or text) that can be shared.
- **Settings**: screenshot blocking (whole app), list-preview off, Wi-Fi-only downloads (enforced), theme incl. AMOLED black,
  clear temporary files, delete messages older than 30/90/365 days.
- Tests added: `FileToolsTest` (digests with known vectors, hex dump, strings, type sniffing, ZIP listing/extract/limits).
- **Not verified by a compiler or on a device by the author (this applies to 8a and 8b too).** The code was reviewed statically; the GitHub Actions build and
  tests are the first real compile. Report any build error and it will be fixed.

## Build fix (v0.8.1 -> v0.9.0)
`MainViewModel` declared two members called `settings` (the private `SharedPreferences` and the public `AppSettings`). Kotlin
reported "Conflicting declarations" and every screen that read `vm.settings...` or `vm.lockEnabled` then failed type inference
(Home, SettingsScreens, MainActivity). The private one is now `prefs`. No behaviour changed.

## Phase 8a: encrypted backup and restore (v0.9.0)
- Settings > Backup and restore. One file holding the SQLCipher database (identity, contacts, messages, groups, ratchet
  sessions, settings stored in the database) plus the two secrets that open it (database key, identity seed).
- Locked with a passphrase you choose (min 10 characters, typed twice). PBKDF2-HMAC-SHA256 (310,000 rounds) derives an AES-256
  key; data is sealed in 1 MiB AES-256-GCM segments, each bound to the header, its position and a "last segment" flag. Wrong
  passphrase, flipped bits, reordered segments, a file cut short and appended data are all rejected (18 JVM tests).
  JDK primitives only, nothing home-made. The passphrase is never stored.
- The snapshot is taken after a WAL checkpoint while holding the database write lock, so it is a consistent database.
- Restore is two steps: the file is decrypted and verified completely, then staged; **nothing is replaced until the app is
  closed and opened again**. Backups from a newer database version are refused. Cancel is possible before the restart.
- **Limits (honest):** downloaded attachments, vault files and relay/file-server lists are not included. A restored backup
  brings back old Double Ratchet sessions: conversations that continued after the backup was made can stop decrypting until
  the contact is added again (same limit as losing a session). Restoring needs the same app version or newer. Not tested on a
  real device yet; the backup file is only as safe as its passphrase.

## Phase 8b: vault (v0.9.0)
- Settings > Vault. Add any file (up to 200 MB); it is encrypted on this device with its own random key (Tink AES-GCM-HKDF
  streaming) and stored in app-private storage. Keys live in the SQLCipher database (schema v8, migration 7->8: `vault_items`).
- Opening the vault needs fingerprint or screen lock every time (it re-locks when the app leaves the screen). Without a screen
  lock set up it stays closed.
- List, search by name, rename, delete, check integrity (full authentication + SHA-256), save a decrypted copy through the
  system file picker. Nothing is uploaded. Resetting the identity wipes the vault files.
- **Limits (honest):** no in-app preview or folders yet; vault files are not in backups; the vault gate is the device lock, not a
  separate passphrase.

## Not in this build (not shown in the UI)
MLS, communities, calls, libp2p/P2P, Tor, mesh, browser, PDF reader, Server Studio, Local API, plugins, group files and read receipts.

## Notes
Single Gradle module with packages mirroring the planned layers (`core/crypto`, `core/database`,
`core/identity`, `ui/...`) to keep CI builds fast and reliable; modules can be split later.
