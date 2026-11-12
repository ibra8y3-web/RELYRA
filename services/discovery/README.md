# RELYRA discovery and TURN infrastructure

This directory contains the independently deployable Internet discovery services used by RELYRA:

- A **libp2p Kademlia DHT node** with a small HTTPS API gateway.
- A **coturn STUN/TURN server** using time-limited REST credentials.
- A Caddy HTTPS reverse proxy for the API.

The Android app is a DHT client through the HTTPS gateway. The gateway stores and retrieves signed presence records on the distributed Kademlia overlay. It is not a centralized peer directory database.

## Network model

The discovery overlay uses the application-specific libp2p protocol prefix `/relyra` and the namespaced record key `/rlyra/peer/v1/<RELYRA-public-id>`. It is intentionally separate from the public IPFS Amino DHT: the service nodes must share the RELYRA protocol and record validator.

A deployment with one node is only a bootstrap seed, not a resilient distributed network. To form a public overlay, operate multiple nodes on independent publicly reachable hosts and configure each node's `RELYRA_BOOTSTRAP_PEERS` with one or more other nodes' full libp2p multiaddresses. Keep the libp2p TCP port 4001 reachable between nodes. Keep the API port 8080 private behind HTTPS.

Each presence record is signed by the device's RELYRA Ed25519 identity and contains only:
- RELYRA public ID and corresponding public key
- Issue and expiry timestamps (maximum lifetime: three minutes)
- Supported signaling/data-channel capability labels

It does **not** publish a display name, SDP, IP address, phone number, or contact list. The record is public and reveals that the identity is currently online. Users must explicitly enable public discovery in RELYRA. The app refreshes its lease while enabled; expired records are rejected by both the DHT validator and the Android client.

Lookup is identity-indexed: a user needs the exact RELYRA public ID to query the record. This implementation deliberately does not create a public searchable directory of private display names. Discovering presence does not add or trust a contact. Messaging and WebRTC negotiation still require the identity to be in the local trusted contact book.

## Deploy

1. Provision at least one Linux host with a public IPv4 address. Open TCP 80/443, TCP 4001, UDP/TCP 3478 and the UDP/TCP relay range 49160-49200.
2. Copy `.env.example` to `.env`. Set `DISCOVERY_DOMAIN`, `TURN_PUBLIC_HOST`, the public `TURN_EXTERNAL_IP`, a unique `TURN_SHARED_SECRET` (at least 32 random bytes), and `ACME_EMAIL`.
3. Point DNS A records for the discovery and TURN hostnames to the server's public IPv4. Do not publish the shared TURN secret in the Android app, a public repository, or a public environment file.
4. Start the first node with `docker compose up -d --build`. Its persistent libp2p identity is stored in the `discovery-data` volume. Read its `peerId` from `https://<DISCOVERY_DOMAIN>/healthz`.
5. Set `RELYRA_ANNOUNCE_MULTIADDRS=/ip4/<public-ip>/tcp/4001/p2p/<peer-id>` on that node and restart it. Configure additional independently hosted nodes with this address in `RELYRA_BOOTSTRAP_PEERS`, then add the additional node multiaddresses to the other nodes. Do not use Docker-only service names as public bootstrap addresses.
6. Add the HTTPS discovery URL in RELYRA Network settings. Enable Public presence only on devices that consent to publish their public ID and online status.

The node's peer ID is stable across restarts as long as the persistent volume is retained. Losing the volume changes the peer ID and requires bootstrap addresses to be updated.

## Opt-in Store-and-Forward mailbox

Store-and-Forward is disabled by default. A relay operator must explicitly set
`RELYRA_STORE_FORWARD_ENABLED=true` in the server environment to accept mailbox
traffic. The Compose deployment exposes the same opt-in through
`RELYRA_STORE_FORWARD_ENABLED` in `.env`.

The mailbox stores only a sender-signed RELYRA wire packet. The app encrypts the
message end-to-end before it reaches this service; the service verifies the
sender signature and payload digest but cannot decrypt the packet. Each recipient
mailbox is limited to 256 unexpired records and 16 MiB, each packet is capped at
64 KiB, and each item expires within 24 hours. A recipient authenticates inbox
reads and acknowledgements with an identity signature; successful delivery is
acknowledged by deleting the queued item. Operators must still protect the host,
monitor disk usage, and understand that metadata (sender, recipient, time and
packet size) remains visible to the relay.

API:
- `POST /v1/mailbox` — submit a signed opaque packet (operator opt-in required).
- `GET /v1/mailbox?limit=20` — list the authenticated identity's pending packets.
- `DELETE /v1/mailbox/<message-id>` — acknowledge and delete a packet as its recipient.

## TURN credentials

The discovery API issues a short-lived coturn REST credential only after verifying a signed Ed25519 challenge from a valid RELYRA identity. A nonce can be used once, challenge timestamps are limited to a 60-second window, and each identity/IP is rate-limited. The default credential lifetime is five minutes and can be set between 60 and 600 seconds.

Coturn receives the same `TURN_SHARED_SECRET` through its container command line. The API returns only the temporary username and HMAC-SHA1 credential, never the shared secret. Coturn applies per-user and total allocation quotas and a bounded relay port range.

The Compose configuration uses `turn:` over UDP/TCP. TLS/DTLS listeners are disabled until an operator deliberately provisions certificate/key files and changes the coturn configuration. The deployment must not claim `turns:` support without those certificates and external connectivity checks.

## API

- `GET /healthz` — node status and libp2p peer ID.
- `POST /v1/records` — publish a signed RELYRA presence record.
- `GET /v1/records/<public-id>` — DHT lookup by RELYRA public ID.
- `POST /v1/turn-credentials` — exchange a signed identity challenge for temporary TURN credentials.

All endpoints are HTTPS at the public edge. The service does not trust client-supplied `X-Forwarded-For` by default; set `RELYRA_TRUST_PROXY=true` only behind a reverse proxy that overwrites forwarding headers.

## Operations and limitations

- This is an operator-run open-source service. The repository does not operate or host a public discovery/DHT cluster or TURN service on behalf of users.
- A public DHT needs several reachable, independently operated bootstrap nodes. One node alone cannot provide DHT resilience or discovery after it goes offline.
- The gateway endpoints are not authenticated with an account. Presence writes are authenticated by the device's Ed25519 signature; TURN credentials require a signed, nonce-protected identity challenge. Rate limits and coturn quotas are enabled as abuse controls, but operators still need monitoring, firewall rules, backups, upgrades and incident response.
- Public DHT records are visible to network participants. Do not include sensitive profile metadata.
- HTTPS is mandatory for the Android gateway. TURN traffic itself is direct UDP/TCP and does not pass through RELYRA's Tor SOCKS proxy.
- A successful API health check does not prove that the DHT has other reachable nodes. Use multiple nodes and monitor the routing table before treating discovery as resilient.
