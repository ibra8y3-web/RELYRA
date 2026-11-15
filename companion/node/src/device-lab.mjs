import { execFile as execFileCallback } from "node:child_process";
import { promisify } from "node:util";
import { mkdir, writeFile } from "node:fs/promises";
import path from "node:path";

const execFile = promisify(execFileCallback);
const APP_ID = "org.relyra.app";
const TEST_TASK = ":app:connectedDebugAndroidTest";

export function parseAdbDevices(output) {
  return output.split(/\r?\n/).slice(1).map((line) => line.trim()).filter(Boolean).flatMap((line) => {
    const [serial, state, ...attributes] = line.split(/\s+/);
    if (!serial || !state) return [];
    const metadata = Object.fromEntries(attributes.map((item) => {
      const index = item.indexOf(":");
      return index > 0 ? [item.slice(0, index), item.slice(index + 1)] : [item, ""];
    }));
    return [{ serial, state, metadata }];
  });
}

export function parseGetprop(output) {
  const values = {};
  for (const line of output.split(/\r?\n/)) {
    const match = line.match(/^\[([^\]]+)\]: \[(.*)\]$/);
    if (match) values[match[1]] = match[2];
  }
  return values;
}

export function isEmulatorDevice(serial, properties) {
  const hardware = properties["ro.hardware"] || "";
  const product = properties["ro.product.name"] || "";
  return /^emulator-/i.test(serial) ||
    properties["ro.kernel.qemu"] === "1" ||
    /goldfish|ranchu|vbox86/i.test(hardware) ||
    /sdk_gphone|emulator/i.test(product);
}

export function parseBattery(output) {
  const level = Number(output.match(/^\s*level:\s*(\d+)/m)?.[1] ?? -1);
  const status = Number(output.match(/^\s*status:\s*(\d+)/m)?.[1] ?? -1);
  return {
    percent: Number.isInteger(level) && level >= 0 && level <= 100 ? level : null,
    charging: status === 2 || status === 5 ? true : status === 3 || status === 4 ? false : null
  };
}

export function parseNetworkTransports(output) {
  const found = [];
  for (const [token, label] of [
    ["TRANSPORT_WIFI", "wifi"],
    ["TRANSPORT_CELLULAR", "cellular"],
    ["TRANSPORT_ETHERNET", "ethernet"],
    ["TRANSPORT_VPN", "vpn"],
    ["TRANSPORT_BLUETOOTH", "bluetooth"]
  ]) if (output.includes(token)) found.push(label);
  return found;
}

export function parseAppVersion(output) {
  const versionName = output.match(/versionName=([^\s]+)/)?.[1] ?? null;
  const versionCode = output.match(/versionCode=(\d+)/)?.[1] ?? null;
  return versionName || versionCode ? { versionName, versionCode } : null;
}

export function makeDeviceProfile({ properties, batteryOutput = "", connectivityOutput = "", featuresOutput = "", packageOutput = "" }) {
  const features = featuresOutput.split(/\r?\n/)
    .map((line) => line.trim().replace(/^feature:/, ""))
    .filter((name) => /camera|bluetooth|wifi\.direct|telephony|microphone|nfc/i.test(name))
    .sort();
  return {
    manufacturer: properties["ro.product.manufacturer"] || null,
    model: properties["ro.product.model"] || null,
    device: properties["ro.product.device"] || null,
    androidRelease: properties["ro.build.version.release"] || null,
    sdk: Number(properties["ro.build.version.sdk"]) || null,
    primaryAbi: properties["ro.product.cpu.abi"] || null,
    securityPatch: properties["ro.build.version.security_patch"] || null,
    buildFingerprint: properties["ro.build.fingerprint"] || null,
    battery: parseBattery(batteryOutput),
    networkTransports: parseNetworkTransports(connectivityOutput),
    hardwareFeatures: features,
    appVersion: parseAppVersion(packageOutput)
  };
}

async function defaultRun(command, args, options = {}) {
  return execFile(command, args, {
    cwd: options.cwd,
    env: options.env,
    timeout: options.timeout ?? 30_000,
    maxBuffer: options.maxBuffer ?? 8 * 1024 * 1024,
    windowsHide: true
  });
}

async function safeRun(run, command, args, options) {
  try { return (await run(command, args, options)).stdout ?? ""; }
  catch { return ""; }
}

export async function discoverPhysicalDevices({ adbPath = "adb", run = defaultRun } = {}) {
  const listing = await run(adbPath, ["devices", "-l"], { timeout: 15_000 });
  const connected = parseAdbDevices(listing.stdout ?? "").filter((item) => item.state === "device");
  const physical = [];
  for (const item of connected) {
    const serial = item.serial;
    const propertiesOutput = await safeRun(run, adbPath, ["-s", serial, "shell", "getprop"], {});
    const properties = parseGetprop(propertiesOutput);
    if (isEmulatorDevice(serial, properties)) continue;
    const [batteryOutput, connectivityOutput, featuresOutput, packageOutput] = await Promise.all([
      safeRun(run, adbPath, ["-s", serial, "shell", "dumpsys", "battery"], {}),
      safeRun(run, adbPath, ["-s", serial, "shell", "dumpsys", "connectivity"], {}),
      safeRun(run, adbPath, ["-s", serial, "shell", "pm", "list", "features"], {}),
      safeRun(run, adbPath, ["-s", serial, "shell", "dumpsys", "package", APP_ID], {})
    ]);
    physical.push({
      serial,
      profile: makeDeviceProfile({ properties, batteryOutput, connectivityOutput, featuresOutput, packageOutput })
    });
  }
  return physical;
}

const MANUAL_CASES = [
  ["P10-01", "Pair two independent identities and verify fingerprints"],
  ["P10-02", "Direct encrypted messages, delivery receipts and duplicate replay"],
  ["P10-03", "MLS group messages, epoch updates and membership changes"],
  ["P10-04", "Trusted-device enrollment and revocation across send/signaling paths"],
  ["P10-05", "Encrypted attachment upload/download, integrity and large files"],
  ["P10-06", "Backup on device A and restore on device B"],
  ["P10-07", "Bidirectional voice calls over Wi-Fi"],
  ["P10-08", "Video calls and camera/microphone permission denial/recovery"],
  ["P10-09", "Wi-Fi-to-cellular handover during messaging and calls"],
  ["P10-10", "Restricted NAT with deployed TURN, allocation and credential expiry"],
  ["P10-11", "Background delivery under Doze and battery optimization"],
  ["P10-12", "Process death, reboot and app-update recovery"],
  ["P10-13", "Multi-hop BLE Mesh while internet is unavailable"],
  ["P10-14", "Wi-Fi Direct and Nearby LAN identity verification"],
  ["P10-15", "Arabic RTL, TalkBack, text scaling and notifications"]
];

function renderMarkdown(report) {
  return [
    "# RELYRA Phase 10 — Physical Device Lab Report",
    "",
    `- Generated: ${report.generatedAt}`,
    `- Connected physical devices: ${report.devices.length}`,
    `- Automated device runs: ${report.results.filter((item) => item.status === "passed").length} passed / ${report.results.length} total`,
    "- Cross-device manual scenarios: PENDING",
    "",
    "| Alias | Manufacturer | Model | Android | SDK | ABI | Battery | Network | App |",
    "|---|---|---|---|---:|---|---:|---|---|",
    ...report.devices.map((device, index) => {
      const p = device.profile;
      return `| device-${index + 1} | ${p.manufacturer ?? "unknown"} | ${p.model ?? "unknown"} | ${p.androidRelease ?? "unknown"} | ${p.sdk ?? "unknown"} | ${p.primaryAbi ?? "unknown"} | ${p.battery.percent ?? "unknown"}% | ${p.networkTransports.join(", ") || "unknown"} | ${p.appVersion?.versionName ?? "not installed"} |`;
    }),
    "",
    "## Instrumentation",
    "",
    ...(report.results.length
      ? report.results.map((item, index) => `- device-${index + 1}: **${item.status.toUpperCase()}**`)
      : ["- Not run."]),
    "",
    "## Acceptance boundary",
    "",
    "Passing instrumentation on one phone is not proof of interoperability between independent phones. Complete the manual matrix and retain reproducible evidence before closing Phase 10.",
    "",
    "This report excludes serial numbers, phone numbers, Wi-Fi SSIDs, IP/MAC addresses, message bodies, private keys and bearer tokens.",
    ""
  ].join("\n");
}

export async function runPhase10DeviceLab({
  adbPath = "adb",
  gradlePath = "gradle",
  cwd = process.cwd(),
  outputDir = path.join(cwd, ".relyra-device-lab", new Date().toISOString().replaceAll(":", "-")),
  allowSingleDevice = false,
  run = defaultRun
} = {}) {
  const devices = await discoverPhysicalDevices({ adbPath, run });
  const minimum = allowSingleDevice ? 1 : 2;
  if (devices.length < minimum) {
    throw new Error(`Phase 10 requires at least ${minimum} connected physical Android device(s); found ${devices.length}. Emulators are excluded.`);
  }
  await mkdir(outputDir, { recursive: true });
  const results = [];
  for (const device of devices) {
    try {
      await run(gradlePath, ["--no-daemon", "--console=plain", `-Pandroid.injected.device.serial=${device.serial}`, TEST_TASK], {
        cwd,
        env: { ...process.env, ANDROID_SERIAL: device.serial },
        timeout: 60 * 60 * 1000,
        maxBuffer: 32 * 1024 * 1024
      });
      results.push({ deviceAlias: `device-${results.length + 1}`, status: "passed", exitCode: 0 });
    } catch (error) {
      results.push({
        deviceAlias: `device-${results.length + 1}`,
        status: "failed",
        exitCode: Number.isInteger(error.code) ? error.code : null
      });
    }
  }
  const report = {
    schemaVersion: 1,
    generatedAt: new Date().toISOString(),
    instrumentation: true,
    devices: devices.map(({ profile }) => ({ profile })),
    results,
    manualScenarios: MANUAL_CASES.map(([id, title]) => ({ id, title, status: "PENDING" })),
    phaseGate: results.length > 0 && results.every((item) => item.status === "passed")
      ? "PENDING_MANUAL_MULTI_DEVICE_VALIDATION"
      : "AUTOMATED_DEVICE_FAILURES"
  };
  const checklist = [
    "case_id,scenario,status,device_a,device_b,network_conditions,evidence,notes",
    ...MANUAL_CASES.map(([id, title]) => [id, title, "PENDING", "", "", "", "", ""]
      .map((value) => '"' + String(value).replaceAll('"', '""') + '"').join(","))
  ].join("\n") + "\n";
  await writeFile(path.join(outputDir, "phase10-report.json"), JSON.stringify(report, null, 2) + "\n", { mode: 0o600 });
  await writeFile(path.join(outputDir, "phase10-report.md"), renderMarkdown(report), { mode: 0o600 });
  await writeFile(path.join(outputDir, "manual-checklist.csv"), checklist, { mode: 0o600 });
  return { report, outputDir };
}

export async function inventoryPhase10Devices({ adbPath = "adb", run = defaultRun } = {}) {
  const devices = await discoverPhysicalDevices({ adbPath, run });
  return devices.map(({ profile }, index) => ({ alias: `device-${index + 1}`, ...profile }));
}
