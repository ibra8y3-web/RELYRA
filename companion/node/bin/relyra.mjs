#!/usr/bin/env node
import path from "node:path";
import { fileURLToPath } from "node:url";
import { spawn } from "node:child_process";
import { RelyraClient } from "../src/client.mjs";
import {
  initializeServerConfig,
  updateServerBootstrapConfig,
  runServerAction,
  listServiceBackups,
  verifyServiceBackup,
  restoreServiceBackup,
  deleteServiceBackup
} from "../src/compose.mjs";
import { startServerStudio } from "../src/studio.mjs";
import { inventoryPhase10Devices, runPhase10DeviceLab } from "../src/device-lab.mjs";

const here = path.dirname(fileURLToPath(import.meta.url));
const defaultRoot = path.resolve(here, "../../..");

function options(args) {
  const result = { _: [] };
  for (let i = 0; i < args.length; i++) {
    if (args[i].startsWith("--")) {
      const key = args[i].slice(2);
      if (key === "no-open" || key === "allow-single-device") {
        result[key] = true;
        continue;
      }
      const value = args[i + 1];
      if (!value || value.startsWith("--")) throw new Error("Missing value for --" + key);
      result[key] = value;
      i++;
    } else result._.push(args[i]);
  }
  return result;
}

function usage() {
  process.stdout.write([
    "RELYRA Companion — local Server Studio and Android/Node tools",
    "",
    "Server operations (run on the host that owns the Docker Compose stack):",
    "  relyra server init --domain <dns> --turn-host <dns> --turn-ip <public-ipv4> --email <email>",
    "  relyra server configure --bootstrap-peer <multiaddr> --announce-multiaddr <multiaddr>",
    "  relyra server studio [--port <port>] [--no-open]",
    "  relyra server status|start|stop|restart|rebuild|logs",
    "  relyra server backup create|list|verify <id>|restore <id> --confirm 'RESTORE <id>'|delete <id> --confirm 'DELETE <id>',
    "",
    "Physical device validation (requires Android SDK Platform Tools):",
    "  relyra device-lab inventory",
    "  relyra device-lab run [--allow-single-device] [--output <directory>] [--adb <path>] [--gradle <path>]",
    "",
    "Android companion (enable Local API in RELYRA first):",
    "  relyra android forward --device-port <app-port> [--local-port 8787] [--serial <adb-serial>]",
    "  relyra android unforward [--local-port 8787] [--serial <adb-serial>]",
    "  relyra android health|diagnostics|identity|schema [--local-port 8787] --device-port <app-port>",
    "",
    "Set RELYRA_API_TOKEN in the environment; do not pass it as a command-line argument.",
    "Use --directory <repository-root> to select a different RELYRA checkout."
  ].join("\n") + "\n");
}

async function runAdb(args) {
  return new Promise((resolve, reject) => {
    const child = spawn("adb", args, { stdio: "inherit", windowsHide: true });
    child.on("error", (e) => reject(new Error("adb could not be started: " + e.message)));
    child.on("close", (code) => code === 0 ? resolve() : reject(new Error("adb exited with code " + code)));
  });
}

function print(value) {
  process.stdout.write((typeof value === "string" ? value : JSON.stringify(value, null, 2)) + "\n");
}

async function main() {
  const parsed = options(process.argv.slice(2));
  const root = path.resolve(parsed.directory || defaultRoot);
  const [area, command, ...rest] = parsed._;
  if (!area || area === "help" || parsed.help) { usage(); return; }

  if (area === "server") {
    if (command === "init") {
      const file = await initializeServerConfig(root, {
        domain: parsed.domain, turnHost: parsed["turn-host"], turnIp: parsed["turn-ip"], email: parsed.email
      });
      print("Created " + file + " with mode 0600. Keep the TURN secret private; it is not printed.");
      return;
    }
    if (command === "configure") {
      const split = (value) => value ? value.split(",").map((item) => item.trim()).filter(Boolean) : undefined;
      print(await updateServerBootstrapConfig(root, {
        bootstrapPeers: split(parsed["bootstrap-peer"]),
        announceMultiaddrs: split(parsed["announce-multiaddr"])
      }));
      return;
    }
    if (command === "studio") {
      const port = parsed.port ? Number(parsed.port) : 0;
      if (!Number.isInteger(port) || port < 0 || port > 65535) throw new Error("Invalid Studio port");
      await startServerStudio({ repositoryRoot: root, port, openBrowser: parsed["no-open"] !== true });
      return;
    }
    if (command === "backup") {
      const [backupCommand, id] = rest;
      if (backupCommand === "create") print(await runServerAction(root, "backup"));
      else if (backupCommand === "list") print(await listServiceBackups(root));
      else if (backupCommand === "verify" && id) print(await verifyServiceBackup(root, id));
      else if (backupCommand === "restore" && id) print(await restoreServiceBackup(root, id, parsed.confirm));
      else if (backupCommand === "delete" && id && parsed.confirm === "DELETE " + id) print(await deleteServiceBackup(root, id));
      else throw new Error("Use server backup create|list|verify <id>|restore <id> --confirm 'RESTORE <id>'|delete <id> --confirm 'DELETE <id>'");
      return;
    }
    if (["status", "start", "stop", "restart", "rebuild", "logs"].includes(command)) {
      print(await runServerAction(root, command));
      return;
    }
    throw new Error("Unknown server command. Use 'relyra help'.");
  }

  if (area === "device-lab") {
    if (command === "inventory") {
      print(await inventoryPhase10Devices({ adbPath: parsed.adb || "adb" }));
      return;
    }
    if (command === "run") {
      const outputDir = parsed.output ? path.resolve(root, parsed.output) : undefined;
      const result = await runPhase10DeviceLab({
        adbPath: parsed.adb || "adb",
        gradlePath: parsed.gradle || "gradle",
        cwd: root,
        outputDir,
        allowSingleDevice: parsed["allow-single-device"] === true
      });
      print({ outputDir: result.outputDir, phaseGate: result.report.phaseGate, results: result.report.results });
      if (result.report.phaseGate !== "PENDING_MANUAL_MULTI_DEVICE_VALIDATION") process.exitCode = 1;
      return;
    }
    throw new Error("Use device-lab inventory|run.");
  }

  if (area === "android") {
    const localPort = parsed["local-port"] ? Number(parsed["local-port"]) : 8787;
    if (!Number.isInteger(localPort) || localPort < 1 || localPort > 65535) throw new Error("Invalid local port");
    const serial = parsed.serial ? ["-s", parsed.serial] : [];
    if (command === "forward") {
      const devicePort = Number(parsed["device-port"]);
      if (!Number.isInteger(devicePort) || devicePort < 1 || devicePort > 65535) throw new Error("--device-port must be the active RELYRA Local API port");
      await runAdb([...serial, "forward", "tcp:" + localPort, "tcp:" + devicePort]);
      print("ADB forward active: localhost:" + localPort + " -> Android 127.0.0.1:" + devicePort);
      return;
    }
    if (command === "unforward") {
      await runAdb([...serial, "forward", "--remove", "tcp:" + localPort]);
      print("ADB forward removed");
      return;
    }
    const devicePort = parsed["device-port"] ? Number(parsed["device-port"]) : undefined;
    const client = new RelyraClient({
      baseUrl: "http://127.0.0.1:" + localPort,
      token: process.env.RELYRA_API_TOKEN,
      devicePort
    });
    if (command === "health") print(await client.health());
    else if (command === "diagnostics") print(await client.diagnostics());
    else if (command === "identity") print(await client.identity());
    else if (command === "schema") print(await client.openApi());
    else throw new Error("Unknown Android command. Use health|diagnostics|identity|schema.");
    return;
  }

  throw new Error("Unknown command group. Use 'relyra help'.");
}

main().catch((error) => {
  process.stderr.write("RELYRA: " + String(error?.message || error) + "\n");
  process.exitCode = 1;
});
