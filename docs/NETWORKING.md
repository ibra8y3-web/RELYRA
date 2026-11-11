# RELYRA networking: current state and extension rules

## Current network map

![RELYRA network map](assets/relyra-network-map.svg)

## Optional nearby LAN transport

An opt-in local connector is added alongside Nostr. When the user enables it, RELYRA advertises the public RELYRA contact ID via Android NSD/mDNS and discovers only IDs that already exist as known contacts. The connector carries the already end-to-end-encrypted wire payload over a bounded TCP frame and passes incoming bytes to `DeliveryEngine.onWire`.

- Disabled by default; turning it on exposes the public contact ID to devices on the current local network.
- Both devices must share the same IP LAN or local hotspot. The local network can operate without Internet access.
- Nearby LAN is attempted before Nostr. Nostr remains the fallback if no known peer is discovered or the direct socket is unavailable.
- This is not Bluetooth, Wi-Fi Direct, Internet P2P, libp2p, or NAT traversal. Real-device interoperability still needs verification.

## Implemented transport

The current connected transport is **Nostr over user-configured secure WebSockets (WSS)**.

- No relay is silently enabled by default; users manage their own relay list.
- Relay URLs are normalized to WSS and can be probed with a WebSocket connection plus a Nostr REQ/EOSE exchange.
- Nostr events use Schnorr signatures over secp256k1 (BIP-340) and NIP-01 event serialization.
- RELYRA message packets use kind 4747 events and recipient tags.
- The recipient verifies the event, checks the destination tag, then passes the payload to the end-to-end decryption and delivery engine.
- The pool maintains inbox subscriptions, reconnects after disconnects and waits for relay OK responses when publishing.
- NetworkManager persists relay configuration locally. The relay list is not part of the current backup export.

## What is not implemented

The presence of a Connector interface and AdaptiveRouter is only an extension point. It is not proof of an active transport. These are not currently registered/implemented:

| Transport or capability | Status |
|---|---|
| Internet P2P / direct sockets across separate networks | Not implemented |
| libp2p | Not implemented |
| ICE/STUN/TURN traversal | Not implemented |
| Tor routing | Not implemented |
| Bluetooth / Wi-Fi Direct mesh | Not implemented |
| Matrix / XMPP bridges | Not implemented |
| Push relay infrastructure operated by RELYRA | Not implemented |

Blossom is a separate encrypted-file storage path, not a peer-to-peer messaging transport.

## AdaptiveRouter contract

A connector must:
1. Implement the existing Transport contract and expose a stable, human-readable name.
2. Return not-ready when it cannot currently deliver, so the delivery engine does not advance encryption state for an unavailable route.
3. Carry only the already-protected wire payload.
4. Return Accepted or Relayed only when the connector has actually accepted the payload; return Unavailable for temporary connectivity problems; return Rejected only for permanent failures.
5. Preserve the same message identifier across transport retries and never invent a new application message during a retry.

AdaptiveRouter attempts ready connectors in their configured order. The successful connector name is exposed through lastPath. A future connector must not be added to the registered list until its lifecycle and tests are complete.

## Network privacy and threat notes

- WSS protects the connection in transit to the relay; it does not hide the client's IP from that relay.
- Relays can observe event timing, connection metadata, public routing tags and ciphertext size.
- End-to-end encryption protects message contents, but does not make the network anonymous.
- A malicious relay can withhold, delay, reorder or replay events. Application-level authentication, message identifiers and deduplication are still required.
- File servers observe file transfer metadata. Attachment encryption protects file contents and keys from the server.
- Do not describe RELYRA as Tor-protected, fully P2P or anonymous in the current release.
- When Nearby LAN is enabled, the public RELYRA ID is visible to devices on the local network. Enable it only on a trusted LAN; application message content remains end-to-end encrypted.

## Requirements for a future direct-P2P connector

Before registering one, implement and test:
- Explicit user consent and clear disclosure before exposing a listening endpoint.
- Peer discovery with authenticated identity binding; discovery data alone must never authorize a peer.
- NAT traversal strategy with bounded retries and relay fallback.
- Connection timeouts, cancellation, backpressure and limits for concurrent peers.
- Replay protection, duplicate delivery handling and reconnect semantics consistent with DeliveryEngine.
- Android lifecycle behaviour, foreground execution requirements and battery/network restrictions.
- Tests for unavailable peers, forged peer identities, duplicate packets, disconnect during send and fallback to Nostr.

## Verification checklist

For every connector, record:
- Unit tests for protocol parsing and all SendOutcome branches.
- Integration tests with a local controllable endpoint.
- A real-device test on Wi-Fi and mobile data.
- Background/restart/force-stop behaviour and battery cost.
- Whether IP addresses, public identifiers, timing or other metadata are exposed.
