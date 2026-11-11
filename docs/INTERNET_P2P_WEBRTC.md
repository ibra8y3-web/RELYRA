# RELYRA Internet P2P — WebRTC transport

## Scope implemented in this branch

The Android app now contains a real WebRTC DataChannel transport behind the existing Connector / Transport contract.

- WebRTC SDK: io.github.webrtc-sdk:android:150.7871.04 (BSD-3-Clause).
- ICE candidate gathering and connectivity checks are provided by WebRTC.
- STUN is configured by default for server-reflexive candidates.
- Optional TURN URI, username and credential can be supplied in Network settings for networks where direct ICE paths fail.
- DataChannel traffic is binary, ordered and reliable. The current application frame ceiling is 64 KiB; larger attachments must continue using the encrypted Blossom transfer path.
- Every signaling envelope (offer/answer) is signed with the sender's RELYRA Ed25519 identity, bound to the intended recipient ID, versioned, size-limited and expires after ten minutes.
- Only existing, unblocked RELYRA contacts can be used as peers.
- The DataChannel carries the existing already end-to-end-encrypted DeliveryEngine wire payload. WebRTC DTLS is an additional transport protection, not a replacement for RELYRA E2EE.
- AdaptiveRouter tries an already-connected Internet WebRTC path before nearby paths and Nostr. Nostr remains the fallback.

## Signaling workflow

This branch intentionally uses explicit, out-of-band signaling:

1. Alice enters Bob's RELYRA ID and creates an offer.
2. Alice sends the signed offer text privately to Bob.
3. Bob enters Alice's RELYRA ID, pastes the offer and accepts it. RELYRA verifies the signature, intended recipient, age and contact status, then creates a signed answer.
4. Bob sends the answer privately to Alice.
5. Alice pastes and accepts the answer. Both peers perform ICE checks and open the RELYRA DataChannel when a path is available.

SDP contains ICE candidates and can expose local/public network addresses. The app warns users to share it only with the intended contact. The offer/answer is signed but not itself encrypted by this signaling envelope; use a private channel when exchanging it.

## TURN operations

RELYRA does not operate a TURN service. The optional TURN fields configure a TURN server that the user or project operator already controls. TURN credentials are kept in the screen's in-memory state and are not written to SharedPreferences or the database. Removing the fields resets new sessions to the bundled STUN list.

A TURN server requires deployment, authentication, quotas, abuse controls and operational monitoring. Do not publish static long-lived TURN credentials in the Android application.

## Deliberate limitations — not release-complete yet

- No automatic signaling/rendezvous service. Manual offer/answer exchange is required.
- No automatic peer discovery over the public Internet and no DHT.
- No libp2p host, Kademlia DHT or Circuit Relay v2 is included; WebRTC is the selected direct transport for this Android stage.
- TURN fallback works only when a valid reachable TURN server and credentials are supplied. RELYRA does not provision one.
- No verified real-device connectivity result is claimed in this branch. ICE success must be tested across separate Wi-Fi and carrier NATs, including symmetric NAT and IPv6.
- No automatic ICE restart/re-offer after network handover is implemented.
- WebRTC signaling currently waits for ICE gathering to complete before producing the signed card; the timeout is bounded to 25 seconds.
- DeliveryEngine's existing retry/fallback behaviour still needs end-to-end tests while a WebRTC DataChannel disconnects mid-send.

## Required validation

- Clean CI build and JVM tests.
- Two physical Android devices, each with a separate RELYRA identity and the other device added as a signed contact.
- Direct path on two different Wi-Fi networks, then Wi-Fi-to-mobile and mobile-to-mobile.
- TURN-only relay path with a controlled TURN server.
- Bad signature, wrong recipient, expired offer, unknown/blocked contact, malformed SDP and oversized DataChannel frame.
- Disconnect during send, app background/foreground, process restart, network handover and Nostr fallback.
- Record device models, Android versions, ICE candidate pair type (host/srflx/relay), network type and exact CI run.

Until those checks pass, the code is an implemented experimental direct transport, not a certified global P2P service.