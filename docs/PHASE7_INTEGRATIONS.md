# Phase 7 — RELYRA integrations

**Status: partially implemented on `main`.** This document records source code, not a claim that it was built, run, or independently security-reviewed.

## 1. Local API

Source: `app/src/main/java/org/relyra/app/core/integrations/LocalApiServer.kt`.

- Disabled by default; the user starts/stops it from Settings → Integrations.
- Binds only to IPv4 loopback `127.0.0.1` on a temporary random port. It does not listen on LAN or the Internet.
- Creates a random 256-bit bearer token in memory only. The token expires after 15 minutes. Restarting creates a new port/token; stopping immediately invalidates the token.
- GET-only; rejects request bodies; validates Host and Origin; bounds header size/count and request rate.
- `GET /v1/health`: service status, app version and timestamp.
- `GET /v1/identity`: public display name and public RELYRA ID only; requires the explicit identity-read toggle.
- Phase 8 adds `GET /v1/diagnostics` for redacted operational counts/flags and `GET /v1/openapi.json` for the API contract.
- Does not expose messages, attachments, cryptographic keys or database contents.
- No write scopes or message/group management endpoints exist.

The temporary token is a local secret. Do not share it with untrusted apps. This API is not a substitute for authenticated trusted-device enrollment and cannot be reached from another device.

## 2. Restricted plugin workflow runtime

Source: `core/integrations/PluginRuntime.kt` and `ui/screens/IntegrationsScreen.kt`.

This is not a general code loader or Android DEX runtime. It interprets a constrained declarative JSON workflow with a fixed action allow-list:

- `notice`: display an in-app notice.
- `copy_text`: copy text to the clipboard; requires `clipboard.write`.
- `open_url`: open an external HTTPS URL; requires `external.open`.

Package rules:
- `schemaVersion: 1`, bounded package identifier, maximum package size 256 KiB.
- 1–20 actions with unique IDs and bounded text/title lengths.
- Unknown permissions or action types are rejected.
- The user chooses and previews a package, reviews its requested permissions, and explicitly approves installation. A newly installed plugin is disabled until manually enabled.
- Plugins can be disabled or removed. Capabilities are checked again whenever an action runs.
- The app stores a local log of the latest 100 install/enable/disable/remove/execute/blocked events, without recording copied action text or private data.

Example:

```json
{
  "schemaVersion": 1,
  "id": "example.notice",
  "name": "Example Notice",
  "version": "1.0.0",
  "description": "A local notice action",
  "permissions": [],
  "actions": [
    {
      "id": "show",
      "type": "notice",
      "title": "Show notice",
      "text": "Hello from a RELYRA workflow"
    }
  ]
}
```

Plugins do not execute DEX, native code, JavaScript, shell commands or executable files. This is therefore not yet a general-purpose Plugin SDK or an arbitrary-code sandbox. JSON packages are not publisher-signed and there is no trusted update channel yet; install packages only from sources you trust.

## 3. Matrix and XMPP/OMEMO

**Source integrations are present; a full cross-network message/identity bridge is not complete.**

### Matrix
Source: `MatrixRustE2eeClient.kt`, using the official Matrix Rust SDK and connected to the Integrations screen.
- Matrix login and session restore with local SDK state.
- Local SDK store protected using the RELYRA database key, with sensitive session data protected using Android Keystore.
- Room synchronization, joining rooms and sending messages through the SDK.
- Before sending, the client checks that the room encryption state is `ENCRYPTED`; it does not silently downgrade to plaintext when encryption is unavailable.
- This does not turn the RELYRA account into a Matrix identity or automatically translate/forward RELYRA one-to-one and group conversations to Matrix.

### XMPP / OMEMO
Source: `XmppOmemoClient.kt`, using Smack 4.5.0.
- XMPP connection/login, encrypted OMEMO sends, receiving and decrypting messages when peer sessions are available.
- OMEMO device fingerprint display and explicit trust/distrust decisions; unknown devices are not automatically trusted.
- Trust decisions are encrypted using Android Keystore.
- Security limitation: the OMEMO cryptographic key store itself remains in app-private storage; it is not accurate to claim every OMEMO key is directly wrapped by Keystore.
- License limitation: `smack-omemo-signal` uses the Signal provider under GPL-3.0. Review distribution/licensing compatibility before shipping an APK under the project's Apache-2.0 license.

**Verification boundary:** This document does not claim interoperability with independent Matrix/XMPP servers or external devices. Real-server login, encryption, key exchange and reconnect behavior still need validation. A full bridge that maps/forwards identities and messages across RELYRA and the external protocols remains separate work.

## 4. IPFS

**The optional Kubo client and encrypted attachment path are present in source.**
- The app accepts a Kubo HTTPS RPC endpoint; IPFS remains disabled until the user configures it.
- Outgoing files pass through RELYRA file encryption first; only ciphertext is uploaded to Kubo and the resulting CID is used in transfer metadata.
- Downloads retrieve ciphertext by CID and validate size and SHA-256 before handing it to the decryption path.
- The path is connected to the transfer engine and group attachments. Blossom remains available and IPFS is not required for core messaging.

**Verification boundary:** Source code does not prove that public IPFS nodes are reachable, that pins remain available, or that upload/download interoperability works across networks. These need separate deployment/runtime checks.

## 5. Delivery status

| Component | Source status |
|---|---|
| Local API | Health and optional identity endpoints; Phase 8 adds redacted diagnostics and OpenAPI; loopback-only and read-only |
| Restricted JSON plugin workflow runtime | Implemented and wired to Settings; fixed action allow-list, user-granted permissions and bounded event log; not a general-purpose code SDK |
| Matrix client | Rust SDK integration is wired to the app; not an automatic RELYRA message bridge |
| XMPP/OMEMO client | Smack send/receive and device-trust flow are wired to the app; OMEMO storage and GPL-3.0 dependency review remain important limitations |
| IPFS/Kubo | Optional ciphertext transfer path is connected to attachments; public-node operation has not been verified |
| External interoperability | Independent Matrix, XMPP and Kubo server testing remains required; these clients are not a complete cross-network bridge |

**Conclusion:** Phase 7 is partially implemented at source level: Matrix and XMPP/OMEMO clients and the IPFS attachment path exist alongside the Local API and restricted JSON workflow runtime. External-server interoperability is not yet verified, and a full identity/message bridge across RELYRA and those networks remains incomplete. Automated/runtime validation and the dependency-license review are required before declaring Phase 7 complete.
