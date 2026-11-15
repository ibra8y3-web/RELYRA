# Phase 10 — Physical Device Lab and Multi-Phone Validation

**Scope:** Phase 10 validates RELYRA on independent physical Android devices. It does not introduce a new network protocol or rebuild existing transports. Emulator results must never be represented as physical-device results.

## Added implementation

- companion/node/src/device-lab.mjs: discovers authorized physical ADB devices, excludes emulators, reads a privacy-minimized device/battery/network/app profile, and runs Android instrumentation separately on each device.
- companion/node/bin/relyra.mjs: exposes device-lab inventory and device-lab run.
- companion/node/test/device-lab.test.mjs: covers ADB parsing, emulator exclusion, battery/network parsing and serial redaction.
- app/src/androidTest/java/org/relyra/app/Phase10PhysicalDeviceProfileTest.kt: captures physical device capabilities from inside the app and skips emulators.
- JSON/Markdown reports and a CSV manual test checklist are written locally under .relyra-device-lab/ with restrictive file permissions.

## Requirements

- At least two physical Android phones with independent RELYRA identities, USB debugging enabled and ADB authorization granted.
- Android SDK Platform Tools (adb), Gradle 9.6.0 and Java 17.
- Build the current RELYRA debug variant.
- Reports intentionally exclude serial numbers, IP/MAC addresses, Wi-Fi SSIDs, message bodies and private keys.

## Run

From the repository root:

~~~bash
node companion/node/bin/relyra.mjs device-lab inventory
node companion/node/bin/relyra.mjs device-lab run
~~~

The full run requires at least two physical devices. A one-device preflight is available but cannot close the phase:

~~~bash
node companion/node/bin/relyra.mjs device-lab run --allow-single-device
~~~

The runner executes :app:connectedDebugAndroidTest once per device using ANDROID_SERIAL and the Android Gradle device-serial selector, then writes:
- phase10-report.json
- phase10-report.md
- manual-checklist.csv

Emulators and unauthorized devices are excluded. A passing instrumentation run is not evidence of cross-phone interoperability.

## Required manual acceptance matrix

1. Pair two independent identities and verify identity fingerprints.
2. Direct encrypted messages, delivery receipts and duplicate/retry handling.
3. MLS group creation, application messages, epoch updates and membership changes.
4. Trusted-device enrollment and revocation across every send/signaling path.
5. Encrypted attachment upload/download, integrity hashes and large files.
6. Backup on one device and restore on another, including old Ratchet-state invalidation.
7. Bidirectional voice calls over Wi-Fi.
8. Video calls and camera/microphone permission denial/recovery.
9. Wi-Fi-to-cellular handover during messages and active calls.
10. Restricted NAT with a deployed TURN server, allocation, credential expiry and reconnect.
11. Background delivery under Doze and battery optimization.
12. Process death, device reboot and app-update recovery.
13. Multi-hop BLE Mesh while internet is unavailable.
14. Wi-Fi Direct and Nearby LAN with signed identity verification.
15. Arabic RTL, TalkBack, text scaling and notifications.

## Result rules

- Mark PASS only after the scenario runs on the named physical devices and reproducible evidence is retained.
- Mark FAIL with app version, device model, Android version, network type and reproduction steps.
- Mark BLOCKED when a required phone, server or permission is unavailable.
- Keep unexecuted cases PENDING. Emulator tests are never physical-device passes.
- Do not attach logs containing credentials or personal data. The generated inventory contains no message logs or sensitive network identifiers.

## Physical-device boundary

Cloud CI and this assistant cannot claim that two independent phones were tested unless those phones are actually connected and the test is run against them. The runner, instrumentation profile test and acceptance matrix are repository implementation; multi-device results remain pending until the phones are connected and every case is executed.

Passing Phase 10 does not automatically close the separate Phase 9 security gate. The unresolved DHT advisory, license review and independent security review remain separate.
