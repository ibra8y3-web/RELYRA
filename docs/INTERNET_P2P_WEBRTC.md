# RELYRA Internet P2P — WebRTC, DHT and TURN

## Current implementation

The Android app contains a WebRTC DataChannel transport behind the existing Connector / Transport contract. The separate service layer in services/discovery supplies Kademlia discovery and managed TURN credential issuance.

### Android direct transport

- WebRTC SDK: io.github.webrtc-sdk:android:150.7871.04.
- ICE candidate gathering and connectivity checks use configured STUN servers; optional TURN candidates are accepted from either operator-issued short-lived credentials or user-provided TURN settings.
- Binary DataChannel frames are ordered and reliable, bounded to 64 KiB, and include a random transfer ID and SHA-256 digest.
- A direct send is reported as accepted only after the receiver authenticates and accepts the encrypted DeliveryEngine wire payload and returns the matching ACK.
- The AdaptiveRouter tries an established WebRTC path before nearby paths and Nostr. If the direct path fails or its ACK times out, the same stored ciphertext/message ID can be sent by Nostr; the receiving DeliveryEngine deduplicates by message ID and re-acknowledges a duplicate.
- The app monitors Android's default network and WebRTC ICE state. On disconnect/failure it performs bounded exponential retries, recreates the PeerConnection/DataChannel, and sends a new signed offer through RELYRA's encrypted signaling envelope.
- A peer is marked eligible for automatic recovery only after the user approved an incoming offer or the peer accepted the user's offer. The first offer from a known contact is always queued for explicit approval. A previously approved peer's reconnect offer can be answered automatically.
- Incoming data remains end-to-end encrypted by RELYRA before being passed to WebRTC. DTLS is additional transport protection, not a replacement for RELYRA E2EE.

### Signed signaling

Every offer/answer is signed by the sender's RELYRA Ed25519 identity, bound to the recipient ID and a random session UUID, versioned, bounded to 48,000 serialized characters and valid for ten minutes. A stale answer from a different session is rejected. When Nostr is enabled, signaling envelopes travel inside the existing E2EE DeliveryEngine path; manual signed-card exchange remains a fallback when no encrypted route is available.

SDP contains ICE candidates and may expose local/public network addresses. WebRTC sockets do not use RELYRA's Tor SOCKS proxy and can reveal the network address to the remote peer and network observers. Manual copy/paste signaling must be exchanged over a private channel because the card is signed but not independently encrypted.

## Kademlia DHT discovery service

The Android app does not embed a libp2p host. It uses user-configured HTTPS gateways to a separate RELYRA libp2p service in services/discovery.

- DHT protocol prefix: /relyra/kad/1.0.0.
- Record namespace/key: /rlyra/peer/v1/<RELYRA-public-id>.
- The Go service runs a Kademlia DHT node, uses a persistent Pebble datastore, and validates namespaced records before they are stored or returned.
- Presence records are signed with the device's RELYRA Ed25519 identity and expire after three minutes. The app refreshes the lease every 90 seconds while the user has explicitly enabled public presence.
- A record contains the RELYRA public ID/key, issue/expiry times and fixed capability labels. It does not contain a display name, SDP, IP address, phone number or contact list.
- Lookup is by exact RELYRA public ID. Finding a presence record does not add the peer to the contact book or authorize messages; the existing known-contact check remains mandatory.
- Public presence is off by default. An enabled DHT record exposes that the identity is online and which generic connection capabilities it supports.

A single DHT node is only a seed, not a resilient distributed overlay. Operators must deploy multiple nodes on independently reachable hosts and configure each node's RELYRA_BOOTSTRAP_PEERS using full libp2p multiaddresses. The source includes Docker Compose, a container build, persistent node identity, Caddy HTTPS reverse proxy and deployment instructions. The repository does not host a public RELYRA DHT cluster.

## Managed TURN service

The same deployment can run coturn with time-limited REST credentials.

- The Android app asks a configured HTTPS discovery gateway for TURN credentials.
- The request is signed using the device's RELYRA Ed25519 identity, with a short timestamp window and a random nonce.
- The service verifies the identity signature and nonce, then returns coturn REST credentials computed from a server-only shared secret.
- The default credential lifetime is five minutes. The shared secret is not embedded in the app or stored on the device.
- Coturn is configured with user/total allocation quotas and a bounded relay-port range. The sample deployment uses turn: over UDP/TCP. TLS/DTLS must not be advertised until the operator installs valid certificates and enables those listeners.

RELYRA does not run these services on behalf of users. The operator must provision DNS, public IP routing, firewall rules, HTTPS certificates, a high-entropy TURN secret, bootstrap nodes, service monitoring and abuse controls. See services/discovery/README.md.

## Known boundaries

- DHT discovery is identity-indexed; it is not a global searchable directory of usernames or display names.
- Android accesses the DHT through HTTPS gateways; it is not a native libp2p/DHT peer.
- No public default bootstrap network is configured in the repository. A multi-node overlay must be deployed by the project/operator.
- The deployment templates do not prove that the chosen VPS, firewall, DNS, NAT mapping or coturn relay range is reachable.
- WebRTC reconnection is bounded and best-effort. A user-approved peer may need manual retry after all retry attempts fail.
- The service code and JVM regression tests do not replace physical-device acceptance tests across independent Wi-Fi and mobile carrier NATs.

## Validation included in source

- Unit tests for signed/expired/tampered DHT presence records and HTTPS gateway validation.
- Go protocol tests for RELYRA identity binding, record signature, DHT key namespace, expiry, newest-record selection, TURN challenge and temporary credential creation.
- WebRTC codec tests for frame bounds, digest tampering, transfer IDs and ACKs.
- Delivery fallback test: simulate direct packet accepted but ACK lost, send the same packet over Nostr, and assert one stored message.
- Reconnect backoff is bounded and tested as a pure policy.

The remaining physical-device testing is deliberately left to the project owner.
