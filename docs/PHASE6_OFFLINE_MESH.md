# RELYRA Phase 6 — Offline Bluetooth LE Mesh

## Scope
A local BLE mesh is separate from Bluetooth Classic RFCOMM, Wi-Fi Direct and Nearby LAN. It transports opaque, already end-to-end-encrypted RELYRA wire packets.

## Source implementation
- Android BLE GATT service discovery and advertising use a RELYRA-specific 128-bit service UUID. Each opted-in phone runs both the GATT server and BLE scanner/central client.
- The peer identity characteristic exposes only a public RELYRA ID. Transit authenticity is based on signatures verified against the public key encoded by that ID, not on a Bluetooth MAC address.
- The origin signature binds the message ID, origin, destination, creation/expiry times, hop budget and SHA-256 digest of the encrypted payload.
- Every forwarding node appends a signature chained to the previous hop. Repeated nodes and paths beyond eight hops are rejected.
- Transit TTL is limited to 24 hours. Payload, frame and hop counts are bounded.
- A local transit queue stores signed envelopes and ciphertext only. Each queued file is encrypted with AES-GCM using a non-exportable Android Keystore key.
- Queue capacity is bounded to 256 packets and a user-configurable 5–100 MB quota.
- Third-party relaying is a separate explicit opt-in and is off by default. A phone can use BLE for its own outgoing messages without consenting to relay other users' packets.
- Network settings expose BLE state, connected-neighbor count, queued packet count/size, expired packet count, relay consent and storage quota in Arabic and English.
- The adaptive router prefers existing online and direct transports; BLE mesh is the offline fallback.

## Trust and privacy boundaries
- BLE discovery is a transport hint, not contact approval.
- Intermediate nodes never decrypt message payloads. They can observe nearby-device metadata, packet sizes and timing.
- Origin and hop signatures authenticate the transit path; they do not hide radio metadata or guarantee delivery.
- Relays forward third-party packets only while relay consent is enabled. The queue is bounded and expires transit data.
- Delivery is opportunistic: participating devices must be powered, in radio range and running the mesh service.

## Not verified / acceptance work still required
- Mesh envelope and signed delivery-receipt codec unit tests have since been added to the JVM test suite. No APK build, emulator instrumentation or physical-device interoperability tests are claimed here.
- BLE GATT interoperability, MTU negotiation, OEM-specific scan/advertise restrictions, multi-hop delivery while devices move, and battery impact must be tested on multiple Android devices.
- Android may stop BLE scanning/advertising in the background or after force-stop; reliable background mesh operation is not claimed.
- There is no global route table or guaranteed eventual delivery. The transport uses opportunistic neighbor forwarding.
- The separate deferred Phase 3 public-Internet third-party Store-and-Forward remains out of scope and is not implemented by this local BLE mesh.
