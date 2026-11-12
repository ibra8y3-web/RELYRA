# RELYRA networking — verified main branch

This document describes transports and services present on `main`. It distinguishes Android transport code from independently deployed infrastructure.

## Android transports currently present

- **Nostr WSS:** user-configured relay connections; signed events carry encrypted RELYRA packets and support inbox retrieval/retry.
- **WebRTC P2P DataChannel:** signed, recipient-bound offer/answer signalling, explicit consent for incoming offers and bounded reconnect/renegotiation. This is a data channel only; it is not voice/video.
- **Bluetooth Classic RFCOMM:** already-bonded devices only; mutual Ed25519 challenge against known contacts.
- **Wi-Fi Direct:** local P2P group/socket transport with signed identity verification.
- **Nearby LAN:** NSD/mDNS discovery on the same local network and signed identity verification.
- **Bluetooth LE Mesh (Phase 6):** opt-in GATT advertising/scanning, signed origin and relay-hop envelopes, bounded TTL/hop count, encrypted local transit queue, relay consent and storage quota. Source implementation is present; physical-device interoperability and background behaviour are not verified.
- **Blossom:** encrypted file blob storage, separate from message transport. HTTP Range download resume exists; byte-resumable upload does not.

The router selects among currently registered, ready connectors and records the successful path. It does not yet retain a complete per-attempt history with latency and failure reasons.

## DHT and presence service

The repository includes a separate Go/libp2p Kademlia service with persistent Pebble datastore, namespaced record validation, HTTPS publish/lookup API and signed short-lived presence records. The Android app is an HTTPS gateway client, not a libp2p host.

The repository does not operate a public multi-operator DHT cluster. Public discovery requires operators to deploy multiple reachable nodes and configure bootstrap multiaddresses, DNS, firewall and gateway endpoints.

Still missing on main:
- persistent presence high-water marks to reject older-but-unexpired replayed leases after restart;
- automated integration tests publishing on one DHT node and looking up on another;
- globally replicated community directory;
- operator deployment/health verification for a public multi-node network.

## TURN

The source includes coturn deployment configuration and a signed challenge flow that issues short-lived REST credentials. A deployable configuration is not proof that a public TURN service is running. Real allocation, nonce expiry, rate limits, multiple instances and restart behaviour still need integration tests and operator deployment.

## Nearby vs offline mesh

Bluetooth Classic, Wi-Fi Direct and Nearby LAN remain direct local connection methods. Phase 6 adds a separate BLE GATT mesh with signed multi-hop transit envelopes, TTL/hop limits, loop/replay suppression, encrypted local Store-and-Forward, relay consent and quota controls. The BLE source has not been validated across physical devices, and reliable background delivery is not claimed.

The separate deferred Phase 3 work remains: public-Internet third-party Store-and-Forward, distributed DHT/community replication, real TURN integration and per-attempt route diagnostics.

## Privacy and trust

- WSS and HTTPS protect links in transit but do not hide the client's IP from the relay/gateway operator.
- Relays and gateways can observe timing, connection metadata, routing tags and ciphertext size.
- Message bodies remain end-to-end encrypted on the current direct messaging path.
- Community post bodies are currently stored as plaintext at the discovery service; signatures prove authenticity, not confidentiality.
- Blossom servers observe file transfer metadata but receive encrypted file bytes.
- A discovered peer or presence record never automatically becomes a trusted contact.
- Bluetooth/Wi-Fi/LAN identity is accepted only after the signed RELYRA identity challenge succeeds.

## Remaining Phase 3 work — explicitly deferred

Do not implement this before Phases 4–10:
1. Distributed DHT integration and replay-resistant presence persistence.
2. Distributed community directory replication.
3. Real TURN integration and deployment tests.
4. Per-attempt route diagnostics.
5. Third-party Store-and-Forward with user consent, TTL, storage quotas, deduplication and deletion.

See [IMPLEMENTATION_PHASES_AR.md](IMPLEMENTATION_PHASES_AR.md) for the authoritative sequence and [MAIN_BRANCH_AUDIT_AR.md](MAIN_BRANCH_AUDIT_AR.md) for the source inventory.
