# RELYRA — libp2p and Internet P2P integration evaluation

**Decision date:** 2026-11-11

## Current decision

Do not add a libp2p dependency to the Android app as though that alone enables global P2P. The candidate Kotlin/JVM implementation has an Android example, but its own README says the maintainers are not aware of production Android use. Its implementation table also shows important gaps: Kademlia DHT is not implemented, hole punching is not listed as implemented, and Circuit Relay v2 and AutoNAT remain prototype/beta rather than production-tested. See the upstream [jvm-libp2p README](https://github.com/libp2p/jvm-libp2p#readme) and [release history](https://github.com/libp2p/jvm-libp2p/releases).

The release page currently identifies version 1.3.5 as the latest release. The published version should be rechecked when the integration branch is created, rather than copied permanently into the application version catalog.

## What the candidate actually provides

| Capability | Upstream status | RELYRA decision |
|---|---|---|
| TCP transport | Listed as production-tested | Candidate for a controlled Android proof of concept |
| Noise secure transport | Listed as production-tested | Candidate; must bind authenticated libp2p PeerId to RELYRA identity |
| GossipSub | Listed as production-tested | Candidate for peer announcements only after abuse and resource controls |
| QUIC / WebSocket | Prototype or beta | Not a release dependency until Android device tests pass |
| Circuit Relay v2 / AutoNAT | Prototype or beta | Not enough to claim reliable NAT traversal |
| Hole punching | Not implemented in the upstream component table | No direct NAT-hole-punching claim |
| Kademlia DHT | Not implemented in the upstream component table | No global DHT discovery claim |
| Android production compatibility | Android sample exists; no known production users according to upstream README | Require isolated compatibility and battery tests |

## What RELYRA would still need

1. **Identity binding:** verify a libp2p PeerId and prove that it is authorized by a RELYRA Ed25519 identity. Never equate a PeerId, IP address or multiaddr with a contact identity.
2. **Bootstrap and discovery:** operate or select stable bootstrap/rendezvous infrastructure, define signed peer records, expiry, abuse limits and private discovery settings. A DHT dependency is not present in the candidate implementation.
3. **NAT traversal:** coordinate candidate gathering and connectivity checks. Public STUN discovery alone does not make a peer reachable. TURN or Circuit Relay is needed for networks where direct connectivity fails; hole punching must be implemented and tested before it can be advertised.
4. **Android lifecycle:** bound sockets, CPU, wakeups and peer counts; manage foreground execution, network changes, captive portals and battery optimization.
5. **Delivery contract:** carry only DeliveryEngine's already encrypted wire payload, retain message IDs across route changes, and preserve Nostr as a user-configured fallback.
6. **Operational infrastructure:** public bootstrap/rendezvous and relay nodes need operators, rate limits, monitoring, privacy disclosures, abuse response and funding. These are not magically supplied by a library.

## Integration gate

Create a separate experimental module and add no production dependency until all of these pass:

- Clean Android build with minSdk 29 and targetSdk 35.
- An Android device connects to a desktop JVM peer and authenticates the RELYRA identity independently of the libp2p PeerId.
- Two Android devices exchange a signed message across different Wi-Fi networks and mobile carrier NATs.
- Test direct TCP/QUIC, relay fallback, failed hole-punch attempts, IPv4/IPv6, network handover, offline peer, peer spoofing, replay, duplicated delivery and relay outage.
- Verify that the app can stop and resume peers without leaking sockets or keeping the device awake indefinitely.
- Record the exact library version, Android vendors, workflow run, NAT types and tested network paths.

Until this gate passes, the implemented local transports (Bluetooth Classic, Wi-Fi Direct and Nearby LAN) plus user-configured Nostr relays remain the supported routes. Internet-wide direct P2P, DHT and NAT traversal remain unimplemented.