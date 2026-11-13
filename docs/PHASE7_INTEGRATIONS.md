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
- Does not expose messages, attachments, cryptographic keys or database contents.
- No write scopes or message/group management endpoints exist yet.

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

## 3. Matrix and XMPP/OMEMO bridges

**Not implemented.** There is no Matrix account binding, Matrix send/receive/sync path, XMPP stream/client, OMEMO implementation, or mapping between RELYRA identity/keys and external identities.

RELYRA will not forward message plaintext to these services and call that an encrypted bridge. A secure bridge needs explicit trust boundaries, independent key/session management, visibility into whether the remote peer is end-to-end encrypted, and clear disclosure of decryption points and metadata.

## 4. IPFS

**Not implemented.** There is no Kubo/HTTP RPC client, IPFS upload/fetch path, or binding from CIDs to encrypted attachment descriptors and secure file-key exchange over RELYRA messages. Blossom remains the current attachment path. IPFS must remain optional and must not be required for core messaging.

## 5. Delivery status

| Component | Source status |
|---|---|
| Authenticated loopback Local API | Implemented and wired to Settings; not runtime-tested in this task |
| Restricted JSON plugin workflow runtime | Implemented and wired to Settings; not a general-purpose code SDK |
| Plugin activity log | Local bounded log of the latest 100 events |
| Matrix bridge | Not implemented |
| XMPP/OMEMO bridge | Not implemented |
| IPFS integration | Not implemented |
| Build/runtime tests | None were manually started; the repository workflow triggers automatically on every push to main. Automatic run [#43406875573](https://github.com/ibra8y3-web/RELYRA/actions/runs/43406875573) was created by the push and does not by itself establish final app verification. |

**Conclusion:** Phase 7 is not complete. The implemented portion is the authenticated Local API and restricted JSON workflow runtime with in-app management. Matrix, XMPP/OMEMO and IPFS, plus runtime tests and security review, remain before Phase 7 can be declared complete.
