# RELYRA — MLS and Community implementation status

This document describes the code on `main`, not an aspirational protocol diagram. The authoritative inventory and schedule are [MAIN_BRANCH_AUDIT_AR.md](MAIN_BRANCH_AUDIT_AR.md) and [IMPLEMENTATION_PHASES_AR.md](IMPLEMENTATION_PHASES_AR.md).

## 1. MLS implementation currently present

- OpenMLS 0.9.2 pinned in `native/mls/Cargo.toml`, exposed to Kotlin through JNI.
- MLS state stored in a separate SQLCipher database using the app's Keystore-derived database key.
- RELYRA identity/device credential is bound to OpenMLS BasicCredential; account Ed25519 identity signs the public ID and stable installation device ID.
- KeyPackage generation and validation, Welcome join, Commit processing, ApplicationData, epoch/member queries, add/remove membership operations and SQLCipher reopen tests.
- Native group operations are serialized by a Kotlin Mutex.
- MLS group attachment descriptors are carried inside MLS ApplicationData; file bytes are encrypted before Blossom upload.
- Group read receipts currently use authenticated per-recipient RELYRA control messages, not MLS ApplicationData.

## 2. Exact boundary: existing app-level proposal vs RFC 9420 Proposal

The Android group engine currently allows an admin to send a RELYRA-specific membership request to the owner. The request is authenticated by the existing one-to-one session and carries the group epoch and MLS epoch at which it was created. The owner validates both epochs and applies membership changes; only the owner writes an MLS Commit.

This is a useful serialization guard, but it is **not** an RFC 9420 MLS Proposal message. It does not create a native OpenMLS Add/Remove Proposal, does not populate the OpenMLS proposal store on all members, and does not provide a standards-based batch commit of multiple pending proposals.

The remaining MLS task is to complete that native flow on top of the existing OpenMLS engine:
1. Generate native Add/Remove proposals through OpenMLS where authorized.
2. Authenticate and process each received MLS proposal through OpenMLS; inspect its sender, proposal type, group ID and epoch before accepting it into the pending proposal store.
3. Persist pending proposals in OpenMLS SQLCipher storage and recover them after process restart.
4. Let the owner review/accept/reject pending proposals and create a Commit covering the accepted proposal set using OpenMLS's pending-proposal commit API.
5. Merge the owner's pending commit locally only under a delivery policy that prevents an unpersisted state advance; distribute the Commit and any required Welcome to the correct members.
6. Each recipient validates and merges the staged Commit; stale epoch, replay, duplicate, unauthorized sender, conflicting remove/add and removed-member cases must fail safely.
7. Update group metadata only after native MLS epoch and membership state agree. Never advance the Room epoch from an app-level request alone.

The current owner-routed custom proposal path must remain clearly distinguished until the native proposal flow and UI are fully integrated. Do not claim RFC 9420 multi-admin proposal coordination is complete before tests prove it.

## 3. MLS security and recovery requirements

- A removed member must not decrypt application data from a later epoch, including after reopening SQLCipher storage.
- Stale and duplicate proposals/commits must be idempotently rejected or acknowledged without double application.
- Concurrent proposals from different admins must be collected and reconciled by one authorized committer; invalid combinations must not partially advance the group.
- Pending proposal and pending commit recovery must be tested across process restart.
- Delivery metadata and native epoch transitions must not leave a committed state without a retryable packet.
- Credentials must bind the authenticated RELYRA account and device; unknown/blocked identities cannot join merely by presenting a KeyPackage.
- Never log plaintext, private keys, KeyPackages containing sensitive material, or MLS secrets.

## 4. Community implementation currently present

- Go discovery service and Android client.
- Signed community manifests, signed posts and moderation events.
- Private invitations and signed access proofs; membership, ban/unban and publication authorization.
- Per-author post sequence and deduplication; reports, persisted audit trail and moderator screens.
- Publication enforcement exists for ban, mute, slow-mode and allow-post controls.
- Community index is local to each Pebble-backed discovery service. It is not currently replicated over the DHT.
- **Privacy limit:** post bodies are stored as plaintext at the discovery service. Signatures prove origin/integrity; they do not provide end-to-end confidentiality. Do not publish sensitive private conversation content there.

## 5. MLS/community non-goals for this work batch

Do not rewrite the existing OpenMLS engine, community protocol, backup system or UI surfaces that already work. Complete only the missing native proposal lifecycle and required tests. Distributed community-directory replication is deferred with the remaining Phase 3 work until after Phases 4–10.

## 6. Verification levels

- Rust tests verify native OpenMLS behaviour and SQLCipher persistence.
- JVM tests verify the Android orchestration and app-level envelope rules.
- Emulator instrumentation verifies Android JNI/SQLCipher integration where configured.
- Independent physical Android devices, supported ABI coverage and network interoperability remain Phase 10 acceptance gates.
