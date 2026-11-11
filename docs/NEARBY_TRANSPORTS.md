# RELYRA — Nearby transport implementation notes

## Protocol boundary

Wi-Fi Direct, Bluetooth Classic and Nearby LAN are transport adapters only. They receive and transmit the already end-to-end-encrypted wire payload used by DeliveryEngine. They never parse chat text, derive message keys or decrypt application content.

Before a peer is registered as a RELYRA peer, the shared NearbyHandshakeProtocol completes:

1. The initiating device sends its public RELYRA ID and a fresh 32-byte nonce.
2. The responder resolves the ID to an Ed25519 public key and checks that the contact is known and not blocked.
3. The responder returns its own ID, a fresh 32-byte nonce and a signature over the protocol domain, both IDs and both nonces.
4. The initiator verifies the signature and its local contact status, then returns its signature over the same transcript.
5. Only after mutual verification does the responder read the bounded ciphertext frame and call DeliveryEngine.acceptWire.
6. The responder acknowledges only an accepted frame. Message-level replay/deduplication remains the responsibility of DeliveryEngine.

The empty frame is reserved for the initial authenticated reachability probe. It is not a chat message.

## Bluetooth Classic

- Uses RFCOMM with a RELYRA-specific service UUID.
- Only Android-bonded devices are considered; users pair them through Android Settings.
- Each bonded candidate must pass the mutual Ed25519 handshake and local contact check before its RELYRA ID is cached.
- The implementation does not use Bluetooth Low Energy, does not request scan permission and does not start a discoverable pairing flow.
- Devices must keep the app transport enabled and Bluetooth powered on; foreground/background restrictions and manufacturer behaviour require testing.

## Wi-Fi Direct

- Uses Android WifiP2pManager peer discovery and user-selected group formation.
- The P2P MAC address and device label are discovery hints only.
- After a group forms, RELYRA starts a bounded TCP listener on the P2P network and authenticates the group owner with the same challenge protocol.
- Clients and group owners cache IP addresses only after a successful authenticated probe or message exchange.
- The current implementation supports direct peers inside one P2P group. It is not an Internet route and does not imply automatic multi-hop forwarding.
- Android 13+ asks for NEARBY_WIFI_DEVICES; Android 10–12 uses ACCESS_FINE_LOCATION for the discovery APIs. Older Android releases may also require Location mode to be enabled.

## Store and Forward boundary

The current DeliveryEngine persists outgoing messages locally, retries them with backoff, and keeps a stable message ID across retries. Nostr relays can retain events until a recipient's inbox subscription retrieves them. These two paths provide local queueing and relay-assisted delivery.

They do not implement a mesh store-and-forward network. A nearby device does not yet accept, persist, route or forward another user's ciphertext on their behalf. That requires a separate signed transit envelope, hop limits, expiry, consent/quota controls, storage quotas, privacy analysis and end-to-end duplicate/replay tests.

## Not covered by this implementation

- libp2p host-to-host stream transport or DHT discovery over the public Internet.
- ICE, STUN, TURN, AutoNAT or hole punching.
- Bluetooth LE transport/mesh.
- Third-party peer Store & Forward.
- Real-device certification across Android OEM builds.

## Required validation before release

- Run `./gradlew testDebugUnitTest` and `./gradlew assembleDebug` from a clean checkout.
- Test two physical Android devices with Bluetooth pairing and RELYRA contacts already added on both sides.
- Test Wi-Fi Direct discovery, group-owner and client roles, both send directions, contact block/removal and app stop/restart.
- Confirm disabled transports do not advertise or accept connections.
- Test malformed IDs, invalid signatures, unknown/blocked contacts, timeouts and disconnects.
- Record Android versions, device models, CI run and observed battery behaviour.