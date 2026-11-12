# RELYRA Phase 3 — MLS and Community Protocol Design

Status: design decision and staged integration plan. This document does not claim that MLS or public communities are implemented.

## 1. Current group baseline

The existing group engine sends a separate RELYRA one-to-one encrypted packet to every member. Each packet uses that member's existing Double Ratchet session. This preserves the current delivery and retry model, but sending cost grows with the number of recipients. Removing a member stops future fan-out to that member; it does not create a new cryptographic group epoch or rotate a shared group secret.

The current production group engine is therefore **pairwise fan-out, not MLS**. It must not be relabelled as MLS until a real MLS state machine is integrated and interoperable tests pass.

## 2. MLS implementation decision

Use the IETF Messaging Layer Security protocol defined by RFC 9420. The candidate implementation is OpenMLS (Rust), behind a narrow JNI boundary for Android. Do not write a new Kotlin implementation of the MLS state machine.

- Protocol: https://www.rfc-editor.org/rfc/rfc9420.html
- Candidate implementation: https://github.com/openmls/openmls
- API guide: https://book.openmls.tech/

Integration gates:
- Pin a reviewed OpenMLS release and its complete Rust dependency graph.
- Build and link the native library for each Android ABI RELYRA supports.
- Define a JNI interface with bounded byte-array input/output, deterministic error mapping and no secret material in logs.
- Run RFC 9420 test vectors and two-independent-client interoperability tests.
- Complete threat-model, license and dependency review before enabling MLS by default.

A successful native compilation alone does not prove MLS interoperability or correct group membership semantics.

## 3. Identity and credential binding

- A RELYRA account identity remains the existing Ed25519 public identity; MLS does not replace it.
- An MLS credential binds the RELYRA public ID, a per-installation device identifier, credential version and MLS signing public key.
- The credential is signed by the existing RELYRA identity key. Verification checks the RELYRA signature and the MLS credential signature chain.
- Device keys are distinct from the account identity key. Revoking one device must not silently revoke every device belonging to the account.
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
3. Add/remove/role changes become MLS proposals followed by a Commit. The new epoch is accepted only from authorized group state and transcript.
4. A removed member is excluded from the new epoch secret. Future application messages use only the committed epoch. History access is governed by the explicit history policy.
5. Concurrent membership changes must be resolved through MLS proposal/commit processing and transcript rules, not by choosing a snapshot with a larger integer alone.
6. On restart, restore committed state from SQLCipher and reject stale or forked state that does not extend the accepted transcript.

Default history policy: new members receive no earlier message history. Existing members retain only history already stored on their own devices. Removal cannot erase content already decrypted or copied by the removed member.

## 5. Group messages, receipts and attachments

Group application messages are encrypted once per MLS group epoch, rather than once per member. Transports and relays see ciphertext only. Receipts are authenticated control/application messages and identify the original message and receipt-producing credential.

Attachments:
- Encrypt file bytes on the sender device with a fresh content key.
- Upload only ciphertext to Blossom.
- Deliver a group-encrypted descriptor containing the content-key material, digests, sizes, MIME type and immutable blob reference.
- Keep downloads user initiated; verify ciphertext and plaintext hashes before opening.
- Never send the file key in an unencrypted group control packet.

This phase's per-recipient group read receipts are an incremental feature on the existing pairwise fan-out path; they are not the final MLS receipt format. Group attachments are still not implemented.

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

This design does not create a public community index, moderation server, hosted relay, or live MLS service. Those require separately deployed infrastructure and reviewed protocols.

Phase 3 is not complete until:
- MLS state is genuinely produced and consumed by the pinned OpenMLS integration on supported Android ABIs.
- RFC 9420 vectors and independent clients interoperate.
- A removed member cannot decrypt messages created after its committed removal epoch.
- Restart recovery, duplicate commits, stale commits, concurrent proposals and member churn are covered by tests.
- Group attachments and optional read receipts pass authenticated integration tests.
- Community event signatures, moderator authorization, replay controls and service-side rate limiting are implemented and tested.
- CI passes from a clean checkout.

Until those gates pass, the current pairwise group transport remains active and must be labelled accurately.
