# RELYRA Phase 8 — Companion tools

**Source and CI status:** implementation files and CI coverage are present. Workflow #895 on main commit a2ba0bfb9702a2b182bbe1f0a2d3a0ac16d1ce3f passed APK assembly/upload, Android JVM tests, Node, Rust and Go/Docker jobs. SQLCipher instrumentation was cancelled, and operator validation against a real deployment remains pending. This is not a claim that a public RELYRA server is deployed.

## 1. Server Studio

Location: `companion/node/src/compose.mjs`, `companion/node/src/studio.mjs`, and `companion/node/bin/relyra.mjs`.

Server Studio manages the existing `services/discovery/compose.yaml` deployment. It does not create a new discovery server implementation.

- Operator config generation creates the ignored `services/discovery/.env`, uses a random TURN secret, restrictive file mode where supported, and refuses to overwrite an existing file.
- Later configuration validates public IPv4 multiaddresses and safely updates the DHT bootstrap and announce settings.
- Lifecycle operations use an explicit Docker Compose operation allow-list: inspect status, start, stop, restart, pull/rebuild and read recent logs.
- Logs are bounded and redact configured secret values and Bearer tokens before display.
- The browser control surface binds only to `127.0.0.1`, uses a one-time random memory token in the URL fragment, checks Host/Origin and request content type, limits request size/rate, and expires its session after four hours.
- Persistent volume snapshots cover the named Docker Compose volumes only. They are SHA-256 verified. Restore requires all services to be stopped, validates volume layout and archive paths, and saves a separate pre-restore snapshot before replacing data.

**Operational limitations:** The Studio does not deploy infrastructure or open firewall/DNS ports. Operators still configure DNS, public firewall rules, TURN public IP, bootstrap peers, persistent storage, monitoring and incident response. A single DHT node is not a resilient public overlay.

**Backup warning:** Server Studio volume archives are not encrypted by this tool. They may contain sensitive discovery state. The `.env` file is excluded and must be handled separately. Use encrypted host storage, strict file permissions and an offline copy. Log redaction cannot compensate for services that write secrets into arbitrary log messages.

## 2. Desktop / Node companion

Location: `companion/node`.

The package requires Node.js 20+ and has no third-party npm runtime dependencies. It provides:
- `relyra server ...` commands for local operator tasks.
- An importable `RelyraClient` for local API health, diagnostics, optional identity and OpenAPI.
- A desktop workflow through ADB port forwarding. The Android app still binds its API to `127.0.0.1`; the developer must use a trusted USB debugging session and forward only the displayed ephemeral port.

The client rejects non-loopback destinations (including arbitrary HTTPS hosts), limits response size, accepts the temporary token only from the environment or an explicit in-process value, and permits only the four documented read endpoints. It cannot send messages or read message contents.

## 3. Android Developer Tools

The new Settings entry is connected to actual application state:
- live Local API status, loopback endpoint, current token expiry, copy/reveal controls, and ADB forwarding command;
- configured/enabled/connected Nostr relay counts;
- current Mesh status;
- installed/enabled JSON workflow plugins, enable/disable/remove controls with confirmation, and recent metadata-only plugin events;
- navigation to the existing full Diagnostics screen.

The Local API adds:
- `GET /v1/diagnostics`: bounded operational counters and boolean states only;
- `GET /v1/openapi.json`: an OpenAPI 3.1 contract for the read-only API.

Existing `/v1/health` and optional `/v1/identity` are unchanged. All requests still require the 256-bit in-memory Bearer token, loopback Host, GET method, no request body, rate-limit checks and a 15-minute lifetime. Identity access remains opt-in. There are no write routes.

## 4. CI coverage and verification boundary

The build workflow adds a Node 22 job for URL policy, local API allow-list, DHT address validation, log redaction and a loopback authenticated client test. The Android JVM suite gains LocalApiServer network-level tests for bearer enforcement, health/diagnostics, opt-in identity and OpenAPI.

Workflow #895 confirmed APK assembly/upload and the JVM, Node, Rust and Go/Docker jobs. SQLCipher instrumentation did not complete in that run. CI evidence does not replace an operator validation against a live deployment. Actual service lifecycle, browser behavior, ADB connection to a physical phone and real deployment backup/restore still require an operator's environment; they are not claimed as verified by unit tests.

## 5. Start here

See [Node companion instructions](../companion/node/README.md) for install, server setup, multi-node configuration, lifecycle commands, backups, ADB forwarding and client usage. Arabic reference: [المرحلة الثامنة — الأدوات المرافقة](PHASE8_COMPANION_TOOLS_AR.md).
