# Phase 5 — Multi-Device

## Implemented in the repository

### 1. Device identity and keys
- Each Android installation has an independent device ID.
- Each device generates its own Ed25519 signing key and X25519 encryption key.
- Private device keys are wrapped by the non-exportable Android Keystore master key. They are not stored in Room or included in sync bundles.
- SQLCipher table `trusted_devices` stores public keys, trust state, timestamps, key version and sync cursor.
- Database schema is version 18 with non-destructive migrations.

### 2. Pairing and approval
- A new device creates a bounded five-minute pairing request signed with its device signing key.
- An existing device verifies the signature, account identity and request age, then issues an approval signed by the account identity key.
- The approval receipt includes the approving device's public keys so the new device can authenticate the encrypted sync channel.
- The new device checks its locally stored random challenge and the approval signature before becoming APPROVED.
- Pairing request and approval codes are currently copied and pasted manually through the Devices screen.

### 3. Sync and conflict handling
- A bounded JSON snapshot is encrypted for one approved device using the existing `PayloadCrypto` X25519/HKDF/AES-GCM implementation.
- Included data: contacts, direct text-message history, per-chat preferences, and three non-secret global preferences (notifications, notification preview, conversation-list preview).
- Import verifies the RELYRA account and each contact's public identity key.
- Message merge is idempotent by message ID, rejects a conflicting ID with different message content, and advances delivery/read state monotonically.
- A sync packet cannot unblock or change local contact verification decisions.
- Chat preferences and selected global preferences use timestamp-based last-writer-wins merging.
- Imported incoming message IDs are recorded to prevent replay as new incoming messages.

### 4. Revocation and alerts
- Revoking a device changes its local trust state and creates a signed revocation code using the account identity.
- The signed code can be applied on other devices; a revocation tombstone is created even when the target was not previously in that device's roster.
- Pairing, approval and revocation events are written to the security log.
- Local Android notifications are available for selected approval/revocation actions.

## Current manual workflow
1. On the new device, restore the same RELYRA identity using the existing recovery flow, then open Settings → Trusted devices and create a pairing request.
2. Transfer the request code to the existing device.
3. On the existing device, paste and approve the request, then send the approval code back.
4. On the new device, paste and accept the signed approval.
5. From the existing device, export an encrypted snapshot file for the new device and import it with the document picker. A pasted code is also available for smaller snapshots.
6. When revoking a device, distribute the signed revocation code to the remaining devices and apply it on each.

## Explicit limitations — Phase 5 is not fully complete
- The same account identity must currently be restored on the new device. The existing shared account identity key is still used; a root-key/delegated-device architecture that avoids cloning the account signing secret is not implemented.
- Pairing, sync and revocation currently rely on manual code transfer. Automatic sync over Nostr, WebRTC or nearby networking is not implemented.
- Revocation codes must be manually applied to remaining devices; there is no guaranteed immediate broadcast or delivery to offline devices.
- Current revocation state gates device management and sync only; it is not yet enforced by every inbound/outbound messaging and signaling path, so full transport denial for a revoked device is not claimed.
- Current sync excludes file attachments, group/channel messages, group membership, MLS state or keys, Ratchet state, and outgoing messages still awaiting delivery.
- Keep Connected, Wi-Fi-only, screenshot protection, theme and app lock remain device-local.
- Device-scoped keys currently protect pairing and the sync channel, while core RELYRA messages still use the existing account identity. Per-message device signatures, key rotation and key-update distribution are not implemented.
- Notifications are local to actions performed on the current device; remote security push notifications are not implemented.
- No build, protocol tests, emulator tests or two-phone tests were run. Runtime success is not claimed.

## Status
**A practical foundation for signed pairing, device-scoped keys, manual encrypted sync, direct text history/contact/selected-preference merge, and signed revocation has been added and wired into the app UI. Phase 5 remains in progress because of the limitations above.**
