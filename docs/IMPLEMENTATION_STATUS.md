# RELYRA implementation status

Terms: **Implemented** = code exists and is connected to the app · **Tested** = automated tests exist and passed in CI or in the checks listed in `CHANGELOG.md` · **Verified** = confirmed on real devices or servers · **Partial** · **Planned** · **Missing**.

No feature is **Verified** yet: the multi-device, call, mesh, TURN, DHT and real-server scenarios of Phase 10 have not been run on physical hardware.

| Area | Status | What remains |
|---|---|---|
| Identity, keys, QR and invite links | Implemented, Tested | Real two-phone run |
| Encrypted local database (Room + SQLCipher, Keystore) | Implemented, Tested | Committed schema files for migration tests |
| X3DH, Prekeys, Double Ratchet | Implemented, Tested | Real two-phone run |
| Delivery engine, MessageID, ACK, deduplication | Implemented, Tested | Real-network interruption tests |
| MLS groups (OpenMLS via Rust/JNI) | Implemented, Partial | Proposal coordination between several admins, commit/epoch conflicts, fork and replay handling |
| Multi-device sync | Partial | MLS state, session keys, attachments |
| Communities | Partial | Post text is not end-to-end encrypted on the server; public discovery is not distributed |
| Nostr | Implemented | Test against a real relay |
| Blossom (encrypted upload/download, SHA-256) | Implemented, Partial | Resumable and chunked upload, real-server test |
| IPFS (Kubo, encrypted attachments) | Implemented, Partial | Attachment lifecycle and reference management |
| Direct P2P / WebRTC | Implemented | Multi-node and TURN tests on real networks |
| LibP2P DHT discovery service (Go) | Implemented, Partial | Multi-node test; known unresolved DHT advisory GO-2024-3218 (documented, not hidden); Android is not a LibP2P node |
| Bluetooth, BLE mesh, Wi-Fi Direct, LAN | Implemented | Multi-hop tests on real devices; Store & Forward is Missing |
| Tor | Partial | Only a SOCKS proxy selector and strict mode; no embedded Tor, no Bridges management, no leak test |
| Calls (WebRTC audio/video) | Implemented | Real-device and TURN tests |
| Backup and restore (app) | Implemented, Tested | Instrumentation test on a device; cancelled in the last CI run |
| Server backups (Docker volumes) | Implemented, Partial | Archives are unencrypted unless you run `relyra backup-file encrypt`; not wired into Server Studio |
| Built-in browser | Implemented, Partial | Rewritten after a blank-screen report; address bar, back/forward, bookmarks, optional encrypted history, confirmed downloads. Tabs (up to 8) and per-site rules for JavaScript, cookies, camera and microphone are implemented. Location, notifications, pop-ups and per-site storage clearing are Missing; not yet verified on a device |
| Vault, file manager, PDF viewer, file inspection tools | Implemented, Partial | Wider document studio, archive manager, APK analysis, better browser |
| Matrix client and Rust SDK E2EE | Implemented, Partial | No bridge between RELYRA and Matrix; real-server tests |
| XMPP / OMEMO | Implemented, Partial | OMEMO key store is not Keystore-protected; the Signal library licence must be reviewed before distribution (GPL-3.0) |
| Server Studio and Node Operator | Partial | Companion tools run on a computer, not inside the app |
| Plugins | Implemented, Tested | Data-only plugins (notice, copy text, open link). Code plugins are Planned |
| Local API | Implemented, Partial | Loopback and token only. GET read endpoints plus opt-in `messages.send` (send direct and group text messages, list contacts and groups); group management (create, add, remove members), attachments and message history through the API are Missing. Not yet verified on a device |
| Arabic and English UI | Implemented | Full RTL pass and TalkBack testing |
| CI, SBOM, security audit | Implemented, Partial | The vulnerability gate stays red while the DHT advisory is open; 283 licence findings need human review |
| Architecture | Partial | Single `app` Gradle module; `MainViewModel` is very large and should be split |
