# Phase 5 — Multi-Device

## Goal
Allow one RELYRA account to use multiple independent devices with device-scoped keys, explicit approval, immediate trust revocation, and synchronization that never reuses stale cryptographic state.

## Added to source in this increment
- SQLCipher `trusted_devices` table for account device inventory.
- Separate public signing and encryption key fields per device; no private-key field.
- Explicit `PENDING`, `APPROVED`, and `REVOKED` states.
- DAO operations to observe/list devices, insert enrollment requests, conditionally approve/revoke, update last-seen time, advance sync cursors monotonically, and rotate key versions using compare-and-set.
- Non-destructive database migration from version 16 to 17.
- Security event types for enrollment, approval, revocation, key rotation, and synchronization conflicts.

## Not complete — do not mark Phase 5 done
1. A real pairing protocol: short-lived random challenge, proof of possession of the new device key, and approval signed by an existing trusted device.
2. Independent per-device signing/encryption key generation with private keys held only in Android Keystore.
3. Encrypted account-secret transfer/derivation without sending the seed or session keys in plaintext, plus replay protection for pairing requests.
4. Device-management service connected to existing network paths, rejecting revoked devices before accepting messages or signals.
5. Key rotation and invalidation of old sessions/tokens on revocation, including a policy for revoking the last trusted device.
6. Encrypted synchronization with durable cursors, deduplication, size limits, resumability, and user-selected data scope.
7. Conflict resolution for messages/settings/contacts without overwriting newer state or reusing nonce/ratchet state.
8. Arabic/English settings UI for pairing, approval, inventory, labels, last-seen, rotation, and revocation.
9. Security notifications and a reviewable local audit trail.
10. Protocol, unit and integration tests, followed by physical-device verification in Phase 10.

## Security invariants
- Never copy Ratchet, MLS, or private Keystore keys between devices.
- A database row is not proof of trust; state transitions and signatures must be checked.
- Revocation must prevent subsequent message/sync acceptance after the revocation state is distributed. It cannot retract data already received by the revoked device.
- Last-seen is not proof that a device is online or safe.
- A full backup restore is not a substitute for a multi-device protocol.

## Status
**Only the persistence foundation has started.** Pairing, synchronization, and device-management UI are not implemented; Phase 5 is not complete.
