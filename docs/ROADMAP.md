# RELYRA roadmap and completion criteria

This roadmap separates source code that is already present from features that still need implementation. A screen, interface or planned class does not count as a completed capability until the underlying behaviour works and is tested.

## Current source baseline — Phase 8b

| Capability | Current status |
|---|---|
| Android app, Compose UI, Arabic/English | Implemented in source |
| Local identity and contact cards | Implemented in source |
| SQLCipher storage and Android Keystore integration | Implemented in source |
| One-to-one payload encryption and Double Ratchet | Implemented in source |
| Persistent delivery queue and acknowledgements | Implemented in source |
| Nostr WSS relay transport | Implemented in source |
| Groups/channels using per-member one-to-one sessions | Implemented in source; not MLS |
| Blossom encrypted attachment transfer | Implemented in source |
| Background notifications and optional foreground connection service | Implemented in source; Android restrictions apply |
| Local file tools, diagnostics and redacted report | Implemented in source |\n| Live resource sampling (app CPU, app UID RX/TX rate, app-filesystem free space) | Added in current implementation batch; CI/device validation required |
| Passphrase backup/restore and encrypted local vault | Implemented in source; documented exclusions apply |

## Priority 1 — validate and harden the existing release

**Goal:** establish a reproducible, verified baseline before adding more transports.

Acceptance criteria:
- Latest main-branch GitHub Actions build completes successfully.
- All JVM tests pass from a clean checkout.
- Install and launch on Android 10+ and a current Android release.
- Verify identity creation/recovery, contact exchange, direct encrypted message, retry after relay outage, notification permission and background behaviour.
- Verify upload/download of an encrypted attachment, backup/restore and vault integrity on a real device.
- Fix every reproducible crash or data-loss issue and retain regression tests.
- Document tested devices, Android versions and exact workflow run.

## Priority 2 — direct connectivity

**Goal:** add a real direct transport without breaking the current relay route.

Work items:
- Select and evaluate a maintained Android-compatible libp2p implementation.
- Define peer discovery and authenticated peer-to-identity binding.
- Design NAT traversal using ICE/STUN/TURN where supported.
- Add connection lifecycle, timeouts, cancellation, retry/backoff and bounded resource usage.
- Integrate the connector behind the current Transport/Connector contract.
- Keep Nostr as a user-controlled fallback when direct delivery is unavailable.

Acceptance criteria:
- Two independently installed devices can discover and authenticate each other with user consent.
- A message is delivered over a verified direct path and is not duplicated when fallback occurs.
- Tests cover offline peer, NAT failure, disconnect mid-send, malicious peer and relay fallback.
- The UI reports the actual path, never a simulated connection status.

## Priority 3 — group protocol and community layer

**Goal:** move beyond per-member group fan-out only after a group-key protocol is reviewed.

Work items:
- Evaluate MLS and the required credential, epoch, key-package and membership lifecycle.
- Define group history, member removal and key rotation semantics.
- Add group attachments and group read receipts with explicit privacy controls.
- Design community discovery, moderation and abuse controls separately from private group messaging.

Acceptance criteria:
- Removed members cannot decrypt messages sent after the committed removal epoch.
- Membership changes converge safely under concurrent updates.
- Group state can recover after device restart without silently accepting stale membership.
- Protocol tests cover forged commits, replay, delayed messages and member churn.

## Priority 4 — privacy network options

Work items:
- Evaluate Tor integration and its effect on relay reachability and battery use.
- Evaluate Bluetooth and Wi-Fi Direct for nearby-device transport.
- Specify metadata exposure, user consent, discovery limits and fallback behaviour.

Acceptance criteria:
- The app clearly states which transport is active and what metadata it reveals.
- No nearby device is trusted based only on discovery.
- Background execution and battery restrictions are tested on supported Android versions.

## Priority 5 — interoperability and platform services

Potential work items:
- Matrix/XMPP bridges with explicit end-to-end trust boundaries.
- Multi-device identity/session management and secure device revocation.
- Voice/video call signalling and media transport.
- Plugin SDK/runtime, local API, browser, PDF reader and Server Studio.

These are separate projects, not current capabilities. Each requires a threat model, API contract, permission model and tests before UI exposure.

## Release gates

Do not mark a milestone complete until all of the following are true:
1. Real implementation exists; no demo data or placeholder action.
2. Unit tests cover normal, failure, retry, malformed and adversarial input.
3. Integration tests prove the feature against a real protocol endpoint.
4. Android lifecycle, permission and battery behaviour are understood.
5. English and Arabic strings are complete.
6. Documentation states limitations and metadata exposure.
7. CI passes from a clean checkout.


## Additional resource monitoring work

The current implementation batch adds a real `ResourceManager` sampler. It reads Android process CPU elapsed time, per-UID receive/transmit counters and available bytes on the app's filesystem. First-sample and unsupported counters remain unknown rather than being represented as zero. JVM tests cover the pure CPU/rate calculations and counter-reset handling.

The resource screen reports only what Android exposes for the RELYRA process/app UID. It is not a system-wide CPU profiler or a packet inspector.
