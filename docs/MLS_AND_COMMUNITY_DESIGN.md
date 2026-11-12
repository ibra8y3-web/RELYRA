# RELYRA Phase 3 — MLS and Community Protocol Design

Status: protocol design and partial implementation. Phase 3 is merged into `main` by PR #27. The PR-head workflow #418 passed Android, Go discovery-service, and OpenMLS native jobs. Post-merge `main` workflow #419 was still running at the last check; independent-device interoperability gates remain open.

## 0. Implementation status

The current branch contains:
- Group read receipts bound to the exact per-member message-copy ID.
- OpenMLS 0.9.2 through Rust/JNI, with SQLCipher-backed group state.
- RELYRA account/device credentials signed by the existing Ed25519 identity key and verified before KeyPackage acceptance.
- KeyPackage, Welcome, Commit, ApplicationData, owner-serialized membership commits, member removal and a SQLCipher reopen-recovery regression test.
- MLS-encrypted group file descriptors, Blossom ciphertext upload/download, and digest verification tests.
- Signed community manifests/events, private invitations and access proofs, private membership ban/unban, and a moderator-only report inbox.

Not yet complete: distributed multi-admin MLS proposal coordination, public replication of the community index, a community post/feed pipeline for mute and slow-mode enforcement, Android device/ABI interoperability, and clean CI on the final PR head.

## 1. Current group baseline

Legacy groups retain the earlier pairwise RELYRA one-to-one Double Ratchet fan-out path. New groups created with the phase-3 implementation set MLS enabled and use one OpenMLS group epoch for application data. Existing legacy groups are not silently migrated into MLS; the distinction is carried in stored group state and shown by the UI.

## 2. MLS implementation decision

Use the IETF Messaging Layer Security protocol defined by RFC 9420. The merged implementation pins OpenMLS 0.9.2 (Rust) behind a JNI boundary for Android. Do not write a new Kotlin implementation of the MLS state machine. Native build and Rust tests pass in the phase-3 PR CI; independent client interoperability and device-process recovery remain open.

- Protocol: https://www.rfc-editor.org/rfc/rfc9420.html
- Candidate implementation: https://github.com/openmls/openmls
- API guide: https://book.openmls.tech/

Integration gates:
- Pin a reviewed OpenMLS release and its complete Rust dependency graph.
- Build and link the native library for each Android ABI RELYRA supports.
- Define a JNI interface with bounded byte-array input/output, deterministic error mapping and no secret material in logs.
- Run RFC 9420 test vectors and two-independent-client interoperability tests.
- Complete threat-model, license and dependency review before enabling MLS by default.

A successful native compilation and unit tests do not by themselves prove cross-device MLS interoperability or correct behaviour under concurrent membership changes.

## 3. Identity and credential binding

- A RELYRA account identity remains the existing Ed25519 public identity; MLS does not replace it.
- The current credential binds the RELYRA public ID and stable per-installation device identifier inside the OpenMLS BasicCredential.
- The canonical account/device tuple is signed by the existing RELYRA Ed25519 identity key and verified when the authenticated RELYRA sender presents a KeyPackage.
- OpenMLS signing keys remain separate from the account identity key. Multi-device registration, revocation and device-specific key lifecycle remain future work.
- Unknown or blocked contacts cannot be added merely because a valid MLS KeyPackage is presented. Local trust and explicit invitation acceptance remain mandatory.

## 4. Group state, epochs and membership lifecycle

Persist each MLS group independently in SQLCipher:
- group ID and ciphersuite;
- current epoch and serialized MLS group state;
- local credential/device binding;
- pending proposals and pending commit metadata;
- last accepted authenticated transcript hash;
- active member credential references and removal state.

Commit state transactionally with epoch and delivery metadata. Never persist an advanced MLS state without the packet needed to retry its delivery.

Lifecycle:
1. The creator creates an MLS group and emits an authenticated Add/Commit/Welcome flow for invited members.
2. An invited member verifies the creator credential, checks that the RELYRA contact is trusted, and explicitly accepts before installing Welcome state.
3. The current app serializes MLS membership-changing operations through the group owner. The owner produces the Commit/Welcome update; admins cannot independently advance epochs.
4. A removed member is excluded from the new epoch secret. Future application messages use only the committed epoch. History access is governed by the explicit history policy.
5. Distributed multi-admin proposal creation, reconciliation of simultaneous proposals and fork resolution remain unimplemented; this is the main protocol feature still missing from phase 3.
6. OpenMLS state is stored in SQLCipher and has a Rust close/reopen regression test. Android process death, reinstall/recovery and independent-device restore still require validation.

Default history policy: new members receive no earlier message history. Existing members retain only history already stored on their own devices. Removal cannot erase content already decrypted or copied by the removed member.

## 5. Group messages, receipts and attachments

Group application messages are encrypted once per MLS group epoch, rather than once per member. Transports and relays see ciphertext only. Receipts are authenticated control/application messages and identify the original message and receipt-producing credential.

Attachments:
- Encrypt file bytes on the sender device with a fresh content key.
- Upload only ciphertext to Blossom.
- Deliver a group-encrypted descriptor containing the content-key material, digests, sizes, MIME type and immutable blob reference.
- Keep downloads user initiated; verify ciphertext and plaintext hashes before opening.
- Never send the file key in an unencrypted group control packet.

Read receipts currently use authenticated per-recipient RELYRA control packets bound to the exact group-message copy; they are not yet encoded as MLS application/control messages. For MLS-enabled groups, attachment descriptors are sent inside MLS ApplicationData and encrypted file bytes are uploaded to Blossom. JVM tests verify download, ciphertext digest, decryption and plaintext digest. A real-device transfer test remains a release gate.

## 6. Public communities are a separate protocol surface

Private groups and public communities have different trust and abuse models. A public community must not reuse private-group membership snapshots as a public directory.

A future community manifest is signed by its owner identity and includes:
- stable community ID and manifest version;
- public display name and description;
- explicit public/private discoverability setting;
- owner credential and moderator credential references;
- policy version and public moderation keyset;
- optional public entry point, without publishing private member lists.

Discovery:
- Publish a community record only after explicit owner/admin opt-in.
- Keep community discovery records separate from RELYRA peer-presence records and private contact information.
- Discovery does not add a contact, join a community or grant posting permissions.
- Private communities are invitation-only; they do not publish member lists or private metadata.

Moderation:
- Owner/moderator actions are signed events tied to one community ID and policy version.
- Every action includes actor credential, action sequence, target, reason code, issue time and expiry where applicable.
- Receivers verify moderator authority against locally accepted community state. A role claim inside an untrusted event is never sufficient.
- Actions are idempotent and replay-protected; conflicting actions use a canonical sequence/epoch rule.
- Block, mute, report, slow-mode and member removal are distinct actions with explicit scopes.
- Reports are opt-in and redact private message content by default. A report must never upload private conversations automatically.
- Network services enforce bounded request sizes, per-IP and per-identity rate limits, abuse monitoring and retention limits. Client-only rate limiting is insufficient against hostile clients.
- Moderation cannot retroactively erase content already decrypted or copied by another participant.

## 7. Non-goals and release gates

The branch implements a signed community manifest service and a local Pebble-backed public index, not a globally replicated community DHT. Private invitations, signed moderation events, replay sequences, private membership ban/unban and a moderator-only report inbox are implemented. There is no community post/feed pipeline yet, so mute, slow-mode and allow-post events are not enforced by a content service and are not exposed in the current moderation UI.

Phase 3 is not complete until:
- MLS state is genuinely produced and consumed by the pinned OpenMLS integration on supported Android ABIs.
- RFC 9420 vectors and independent clients interoperate.
- A removed member cannot decrypt messages created after its committed removal epoch.
- Restart recovery, duplicate commits, stale commits, concurrent proposals and member churn are covered by tests.
- Group attachments and optional read receipts pass the current JVM/native regression tests; independent Android-device transfer remains open.
- Community event signatures, owner/moderator authorization, per-actor sequence replay controls, private ban/unban effects, report authorization and service-side rate limiting are implemented and tested.
- Distributed MLS proposal reconciliation and community-index replication remain separate incomplete items.
- CI passes from a clean checkout and supported Android ABIs interoperate.

Until those gates pass, the current pairwise group transport remains active and must be labelled accurately.
