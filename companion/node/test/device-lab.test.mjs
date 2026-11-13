import test from "node:test";
import assert from "node:assert/strict";
import {
  parseAdbDevices, parseGetprop, isEmulatorDevice, parseBattery,
  parseNetworkTransports, parseAppVersion, makeDeviceProfile
} from "../src/device-lab.mjs";

test("ADB inventory parser handles connected, emulator and unauthorized states", () => {
  const devices = parseAdbDevices(
    "List of devices attached\n" +
    "R58M123456A device product:a52 model:SM_A525F device:a52\n" +
    "emulator-5554 device product:sdk_gphone64 model:sdk_gphone64_x86_64\n" +
    "ZX1 unauthorized usb:1-2\n"
  );
  assert.equal(devices.length, 3);
  assert.equal(devices[0].state, "device");
  assert.equal(devices[0].metadata.model, "SM_A525F");
  assert.equal(devices[2].state, "unauthorized");
});

test("getprop parser and emulator detector reject virtual devices", () => {
  const props = parseGetprop(
    "[ro.kernel.qemu]: [0]\n[ro.hardware]: [qcom]\n" +
    "[ro.product.name]: [a52xq]\n[ro.product.model]: [SM-A525F]\n"
  );
  assert.equal(props["ro.product.model"], "SM-A525F");
  assert.equal(isEmulatorDevice("R58M123", props), false);
  assert.equal(isEmulatorDevice("emulator-5554", props), true);
  assert.equal(isEmulatorDevice("serial", { "ro.hardware": "ranchu" }), true);
});

test("battery parser reports unknown readings instead of inventing values", () => {
  assert.deepEqual(parseBattery("level: 87\nstatus: 2\n"), { percent: 87, charging: true });
  assert.deepEqual(parseBattery("level: 140\nstatus: 0\n"), { percent: null, charging: null });
});

test("network parser returns transport types only, not SSID or IP", () => {
  const output = "NetworkCapabilities: TRANSPORT_WIFI&NOT_METERED SSID: private-wifi 192.168.1.8";
  assert.deepEqual(parseNetworkTransports(output), ["wifi"]);
});

test("package parser handles installed and missing app states", () => {
  assert.deepEqual(parseAppVersion("versionCode=42 minSdk=29 versionName=0.9.0"), {
    versionName: "0.9.0", versionCode: "42"
  });
  assert.equal(parseAppVersion("Unable to find package org.relyra.app"), null);
});

test("device profile excludes serial and stores only a privacy-minimized subset", () => {
  const profile = makeDeviceProfile({
    properties: {
      "ro.product.manufacturer": "Samsung", "ro.product.model": "SM-A525F",
      "ro.product.device": "a52", "ro.build.version.release": "14",
      "ro.build.version.sdk": "34", "ro.product.cpu.abi": "arm64-v8a",
      "ro.build.version.security_patch": "2026-10-01",
      "ro.build.fingerprint": "samsung/a52/a52:14/build:user/release-keys"
    },
    batteryOutput: "level: 72\nstatus: 3\n",
    connectivityOutput: "TRANSPORT_CELLULAR SSID=hidden 10.0.0.2",
    featuresOutput: "feature:android.hardware.camera\nfeature:android.hardware.bluetooth_le\n",
    packageOutput: "versionCode=10 versionName=0.9.0"
  });
  assert.equal("serial" in profile, false);
  assert.equal("serialHash" in profile, false);
  assert.equal(profile.manufacturer, "Samsung");
  assert.deepEqual(profile.networkTransports, ["cellular"]);
  assert.equal(JSON.stringify(profile).includes("PRIVATE-SERIAL-123"), false);
  assert.equal(JSON.stringify(profile).includes("hidden"), false);
  assert.equal(JSON.stringify(profile).includes("10.0.0.2"), false);
});
