# RELYRA — Tor SOCKS and Strict Mode

## What is implemented

RELYRA has an app-level SOCKS5 route selector. A user can point it to a local Tor service such as Orbot, with the default endpoint 127.0.0.1:9050.

Strict Mode selects exactly one SOCKS proxy for requests made through the shared OkHttp client. It never returns DIRECT as a fallback. If the SOCKS endpoint is unavailable, the request is expected to fail closed.

## Covered network paths

- Nostr relay probes and WSS sessions use the shared OkHttp client.
- Nostr WebSockets are cancelled and recreated when the proxy policy or port changes.
- Blossom upload/download and the Blossom server reachability check use the shared OkHttp client.
- The OkHttp dispatcher is cancelled and its connection pool is evicted on policy changes.

## Deliberately excluded paths

- The internal WebView browser is blocked while Strict Mode is active; it does not use the app's OkHttp SOCKS selector.
- Bluetooth Classic, Wi-Fi Direct and Nearby LAN are local direct links and do not pass through Tor.
- No other Android app traffic is routed. This is not a VPN.

## Not implemented

- A bundled Tor daemon or automatic Orbot installation/startup.
- Bridge management, obfs4 or other pluggable transports.
- A proof that a configured loopback SOCKS listener is actually Tor.
- Device-level packet/DNS leak testing or an anonymity guarantee.

## Manual validation

1. Install and start a Tor service such as Orbot, and confirm its SOCKS listener port.
2. Set that port in RELYRA Network settings and enable Strict Mode.
3. Test a Nostr relay and an encrypted Blossom upload/download.
4. Stop the Tor service while Strict Mode remains enabled; new requests must fail rather than connect directly.
5. Start a relay session, toggle Strict Mode or change the port, and verify the WebSocket reconnects using the selected route.
6. Open the internal browser and confirm it is blocked while Strict Mode is on.
7. Capture device network traffic on a test device to verify there is no direct app connection or DNS leak. Do not treat source code alone as proof of this final item.