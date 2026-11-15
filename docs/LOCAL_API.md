# RELYRA Local API

**Status:** Implemented · Tests written (`LocalApiServerTest`, JVM sockets) but not yet run on a Kotlin toolchain · Not verified on a device.

Start it in **Settings → Integrations → Local API**. It listens on `127.0.0.1` only, with a random port and a bearer token that expires after 15 minutes and disappears when the app stops. Requests must use the loopback `Host`, must not carry a browser `Origin`, and are limited to 60 per minute.

## Endpoints

| Method and path | Scope | Purpose |
|---|---|---|
| `GET /v1/health` | none | Process status |
| `GET /v1/diagnostics` | none | Redacted counters |
| `GET /v1/openapi.json` | none | Machine-readable contract |
| `GET /v1/identity` | `identity.read` | Display name and public id |
| `GET /v1/contacts` | `messages.send` | Contact ids and names (blocked contacts are hidden) |
| `GET /v1/groups` | `messages.send` | Group ids and names |
| `POST /v1/messages` | `messages.send` | Body `{"to": "<contact public id>", "text": "..."}` |
| `POST /v1/groups/messages` | `messages.send` | Body `{"groupId": "...", "text": "..."}` |

`messages.send` is **off by default** and is switched on in the app before the token is created. Sending goes through the same delivery and group engines as the app: end-to-end encryption, a new MessageID per send, retry with the same id, and blocked-contact rules all apply. A `202` answer means the message was queued, not delivered.

Limits for POST: `Content-Type: application/json`, a `Content-Length` of at most 8192 bytes, text up to 6000 characters, and 20 sends per minute. Errors use `403` (scope off or contact blocked), `404` (unknown contact or group), `409` (no encryption key yet), `413`, `415`, `422` and `429`.

```bash
curl -X POST -H "Authorization: Bearer $TOKEN" -H "Content-Type: application/json" \
  -d '{"to":"CONTACT_PUBLIC_ID","text":"hello"}' http://127.0.0.1:$PORT/v1/messages
```

## Not available through the API

Creating groups, adding or removing members, attachments, reading message history, and reading keys or the database are **Missing** by design or not yet built. Any program on the phone that obtains the token can send messages while the API runs, so stop it when you do not need it.
