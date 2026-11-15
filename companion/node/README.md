# RELYRA Companion Tools (Node.js)

The companion package provides the operator-side Server Studio and a local Android/desktop client for the existing RELYRA app. It requires Node.js 20 or newer. There are no third-party runtime npm dependencies.

## Install and check

From a RELYRA checkout:

```bash
cd companion/node
npm test
node ./bin/relyra.mjs help
npm link
```

The Node tests are also part of the `companion-node` GitHub Actions job.

## Server Studio

Run this on the trusted host that owns the existing Docker Compose deployment. It requires Docker Engine and the Docker Compose plugin.

First create the operator configuration:

```bash
node ./bin/relyra.mjs server init --domain discovery.example.org --turn-host turn.example.org --turn-ip <public-ipv4> --email ops@example.org
```

The command creates `services/discovery/.env` with a random 256-bit TURN shared secret and owner-only file permissions where supported. It refuses to overwrite an existing configuration. The IP must be globally routable. Keep the .env file private and out of source control.

For a multi-node DHT, configure a public bootstrap peer and the public address of this node:

```bash
node ./bin/relyra.mjs server configure --bootstrap-peer /ip4/<peer-ip>/tcp/4001/p2p/<peer-id> --announce-multiaddr /ip4/<this-public-ip>/tcp/4001/p2p/<this-peer-id>
```

Pass multiple addresses as comma-separated values. The command validates the IP, port and peer-ID shape, updates only the two bootstrap keys in `.env`, and writes the updated configuration atomically. Add matching peer addresses to the other operator-run nodes; a single node is only a bootstrap seed, not a resilient DHT.

Start the dashboard:

```bash
node ./bin/relyra.mjs server studio
```

The browser console binds to `127.0.0.1` on a random port. Its 256-bit session token is generated in memory, passed to the browser in a URL fragment (not sent in the HTTP request), removed from browser history immediately, and expires after four hours. The action endpoint requires the token, same-origin validation, loopback source, a fixed operation allow-list and a local rate limit. It does not add any public or LAN administration endpoint.

Available server operations:

```bash
node ./bin/relyra.mjs server status
node ./bin/relyra.mjs server start
node ./bin/relyra.mjs server stop
node ./bin/relyra.mjs server restart
node ./bin/relyra.mjs server rebuild
node ./bin/relyra.mjs server logs
```

`rebuild` pulls existing service images and rebuilds the checked-out discovery container. It does not pull Git source or modify the repository checkout. Update source through your normal reviewed Git workflow.

### Volume backups and restore

```bash
node ./bin/relyra.mjs server backup create
node ./bin/relyra.mjs server backup list
node ./bin/relyra.mjs server backup verify <backup-id>
node ./bin/relyra.mjs server stop
node ./bin/relyra.mjs server backup restore <backup-id> --confirm 'RESTORE <backup-id>'
node ./bin/relyra.mjs server start
node ./bin/relyra.mjs server backup delete <backup-id> --confirm 'DELETE <backup-id>'
```

Snapshots cover only the named Docker Compose volumes (discovery data and Caddy state). The tool stops running services while creating a consistent snapshot, then starts them again. Restore requires every Compose service to be stopped, verifies the SHA-256 hashes and volume layout, and creates a separate pre-restore recovery snapshot before replacing any volume. If restore fails, that recovery snapshot is retained and its ID is reported.

**These archives are not password-encrypted by Server Studio.** Protect `.relyra-backups/` using encrypted disk storage and restrictive host access. The directory is git-ignored. The private `.env` file and source checkout are intentionally excluded; keep secrets in a password manager and back up configuration separately. The backup helper uses the `alpine:3.20` utility image and Docker may pull it on first use. Restore can replace all service volume data; review the target project and recovery snapshot before confirming.

Logs are limited to the most recent 200 lines and known values from `.env` plus bearer tokens are redacted before display. This is defense in depth, not a guarantee that arbitrary application log messages cannot contain secrets. Services must never log private keys, passwords or credentials.

## Android / Desktop / Node companion

1. In RELYRA, open **Settings → Integrations** and start the Local API. It binds to IPv4 loopback only and issues a temporary 256-bit bearer token that expires after 15 minutes.
2. Open **Settings → Developer Tools** in RELYRA. It shows the live port, token expiry and ADB forwarding command. Copy the token only to a trusted computer.
3. On that computer, connect the phone over a trusted USB debugging session and use Android Platform Tools:

```bash
adb forward tcp:8787 tcp:<device-api-port>
export RELYRA_API_TOKEN='<temporary-token>'
node ./bin/relyra.mjs android health --device-port <device-api-port>
node ./bin/relyra.mjs android diagnostics --device-port <device-api-port>
node ./bin/relyra.mjs android schema --device-port <device-api-port>
```

Use `android identity` only after the user explicitly enables the `identity.read` scope in the app. Remove the forwarding rule when finished:

```bash
node ./bin/relyra.mjs android unforward
```

The client uses only the documented read-only endpoints: `/v1/health`, `/v1/diagnostics`, optional `/v1/identity`, and `/v1/openapi.json`. It sends the original Android loopback Host header after ADB forwarding because RELYRA validates the app's ephemeral port. HTTP/HTTPS connections are restricted to loopback; a token cannot be configured through a CLI argument and is never written to disk.

The API does not support sending messages, reading message bodies, accessing attachments, changing plugins or controlling account settings. No remote, LAN or write API is exposed.

## Developer Tools

The Android Developer Tools screen displays the real Local API state, current token expiry and ADB information; current Nostr relay counts; Mesh status; enabled/installed workflow plugins; and the bounded plugin event history. The Local API diagnostics endpoint exposes only operational counters and public booleans, never message content, relay URLs, private keys or database contents.

Import the same Node client in another local Node tool:

```js
import { RelyraClient } from "./src/client.mjs";

const client = new RelyraClient({
  baseUrl: "http://127.0.0.1:8787",
  token: process.env.RELYRA_API_TOKEN
});
const health = await client.health();
const diagnostics = await client.diagnostics();
```

The existing plugin runtime remains a constrained JSON workflow engine. This package does not load arbitrary DEX, JavaScript, native code or shell scripts and does not bypass Android permissions.

## Physical Device Lab — Phase 10

Connect at least two authorized physical Android phones over ADB, each with an independent RELYRA identity. Emulators are excluded from the physical-device inventory.

```bash
node ./bin/relyra.mjs device-lab inventory
node ./bin/relyra.mjs device-lab run
```

The run command executes Android instrumentation on each physical phone and writes a privacy-minimized JSON/Markdown report plus a manual acceptance CSV under the repository's `.relyra-device-lab/` directory. Use `--allow-single-device` only for a one-phone preflight; it does not close Phase 10. Multi-phone messaging, calls, MLS, TURN, Mesh, handover and recovery scenarios must still be performed and recorded manually. See [the Arabic Phase 10 validation guide](../../docs/PHASE10_DEVICE_VALIDATION_AR.md) and [the English guide](../../docs/PHASE10_DEVICE_VALIDATION.md).
