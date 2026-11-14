# RELYRA Completion Plan — Eight Phases

This plan extends the existing RELYRA implementation. It is not a rewrite. Existing files, modules, APIs, database records, and working features must be preserved unless a migration or replacement is strictly required and is proven backward-compatible.

## Mandatory execution rules

- No mock data, simulated networking, fake services, placeholder buttons, or success states disconnected from real operations.
- Do not delete existing functionality or files to make a phase pass.
- Reuse and complete existing implementations before adding parallel implementations.
- Pin compatible dependency versions and review licenses before integration.
- Keep security gates strict. Never suppress a vulnerability finding merely to obtain a green workflow.
- For every feature, report separately: Implemented, Integrated, Tested, Verified, Partial, Planned, or Missing.
- A phase is not complete because files or UI exist. It requires real integration, error handling, tests, and evidence.
- Preserve user data and use explicit, tested database migrations.
- Physical multi-device validation is reported separately from CI simulation; no simulated result may be represented as device verification.

## Phase 1 — Build, CI, dependency and security stabilization

- Fix the govulncheck JSON parser and add regression coverage for compact and multi-line JSON streams.
- Resolve the outstanding Go vulnerability review without waiving findings; verify advisory scope and mitigations against authoritative advisories.
- Resolve Rust audit findings, including unmaintained dependencies, through compatible maintained versions or documented, evidence-based risk decisions.
- Correct workflow failures and ensure failed audit commands cannot be hidden by shell pipelines or continue-on-error handling.
- Run Python audit tests, Go tests/vet, Rust tests/audit, Android unit tests, clean APK build and instrumentation tests where runners support them.
- Preserve and validate existing lockfiles, checksum verification, SBOM and license reports.

## Phase 2 — Identity, devices, sessions and cryptographic lifecycle

- Complete identity creation, signed identity cards, fingerprints, QR/invitation links and recovery lifecycle.
- Complete device enrollment, trust, revocation, device migration and multi-device synchronization without copying Keystore secrets or unsafe session state.
- Complete session inventory, revocation, new-device alerts and security event handling.
- Verify X3DH/PreKey publication, retrieval, consumption and replenishment with real peer implementations.
- Verify Double Ratchet persistence, skipped-message handling, replay resistance and reinitialization.
- Complete OpenMLS group state persistence, membership commits, epoch conflict handling, fork/recovery behavior and inter-device synchronization.
- Keep private keys in protected storage; never expose them through logs, API, plugins or relays.

## Phase 3 — Messaging, delivery, groups, channels and communities

- Complete Outbox/Inbox persistence, stable MessageID retry semantics, deduplication and authenticated ACK handling.
- Complete SENT/RELAYED/DELIVERED/READ/FAILED/EXPIRED/CANCELLED state transitions with accurate meanings.
- Complete individual chat features and synchronization, including edits/deletes under explicit policy.
- Complete group membership, roles, admin conflict resolution, MLS membership changes, group receipts and encrypted attachments.
- Complete channel publishing/subscription permissions.
- Complete public/private community discovery, invitations, moderation, reports and audit history.
- Validate behavior across restarts, offline periods, duplicate delivery and concurrent administration.

## Phase 4 — P2P network, discovery, routing, NAT, relay and local mesh

- Complete direct P2P and Android peer connectivity, using actual LibP2P APIs where intended.
- Complete DHT peer records, expiration, refresh, routing, peer diversity and anti-poisoning controls.
- Complete multi-operator bootstrap and independent-node interoperability.
- Complete STUN/ICE/TURN and LibP2P Circuit Relay selection, health, limits and fallback.
- Complete adaptive routing policy and diagnostics without sending traffic to unapproved services.
- Complete Bluetooth, Wi-Fi Direct and Store & Forward paths within Android platform constraints.
- Validate with independent devices and independently operated nodes; document NAT/network limitations.

## Phase 5 — File transfer and external protocol interoperability

- Complete encrypted file chunking, resumable upload/download, selective retry, integrity checks and transfer cancellation.
- Complete Blossom server selection, encrypted upload/download and real external-server interoperability.
- Complete IPFS/Kubo encrypted blob references and attachment lifecycle.
- Complete Nostr relay management, event signatures, subscriptions, reconnection and real relay interoperability.
- Complete Matrix and XMPP/OMEMO account, room, event and message bridges with explicit E2EE boundary warnings.
- Keep external identities and cryptographic trust domains separate; do not claim end-to-end encryption crosses bridges automatically.

## Phase 6 — Calls, background delivery, offline synchronization and privacy routing

- Complete WebRTC call setup, signaling, media tracks, ICE/TURN, reconnection, permissions and call lifecycle.
- Complete notifications by category and privacy-preserving lock-screen behavior.
- Complete WorkManager/foreground service usage within current Android restrictions and battery policies.
- Complete offline queues, reconciliation, expiry and conflict handling.
- Complete Tor/Orbot integration, bridge configuration, strict proxy enforcement and leak tests.
- Complete route privacy disclosures and metadata limitations.
- Do not claim guaranteed background reachability when Android or the user has stopped the app.

## Phase 7 — Backup, Vault, Server Studio and local productivity tools

- Complete encrypted backup of database, attachments, Vault, settings, server configurations and recoverable identity material.
- Complete integrity validation, password/key verification, restore previews, conflict protection and migration.
- Complete Vault and File Manager integration with backup and transfer lifecycle.
- Complete Server Studio and Node Operator workflows for supported external Linux/Windows/Docker nodes; distinguish remote management from hosting on Android.
- Complete Browser, Document Studio, Archive Manager, APK metadata/signature/permission inspection and utility tools.
- Complete real resource measurements and diagnostics with sensitive-data redaction.
- Test backup/restore on clean installations and ensure existing user data is never overwritten without consent.

## Phase 8 — Plugin SDK, Local API, developer tools, UI, architecture and release

- Complete authenticated loopback Local API with scoped tokens, expiry, revocation, rate limits and audit logs.
- Complete a documented Plugin SDK, manifest, permissions, lifecycle, compatibility checks, isolation and safe update/removal.
- Complete Developer Studio, API documentation, event/log viewers and diagnostics export.
- Finish Arabic/English strings, RTL/LTR, responsive layouts, accessibility and screen-reader labels.
- Split oversized ViewModels and establish maintainable modules incrementally without breaking existing package/API contracts.
- Complete license/SBOM/security documentation, release signing, changelog, README, CONTRIBUTING and SECURITY.
- Run full regression, accessibility, performance, privacy and release checks.

## Delivery and reporting

Work is delivered as reviewable pull requests against the existing repository. Each phase lists changed files, existing components reused, new dependencies and licenses, database migrations, tests executed, workflow links, remaining limitations and exact acceptance status. Do not merge a phase or call it complete while required checks fail or evidence is missing.
