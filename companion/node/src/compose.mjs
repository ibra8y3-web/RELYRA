import { spawn } from "node:child_process";
import { createHash, randomBytes } from "node:crypto";
import { createReadStream } from "node:fs";
import { mkdir, readFile, readdir, rm, rename, stat, writeFile } from "node:fs/promises";
import path from "node:path";
import net from "node:net";

function run(command, args, { cwd, timeoutMs = 180000, maxOutput = 2 * 1024 * 1024 } = {}) {
  return new Promise((resolve, reject) => {
    const child = spawn(command, args, { cwd, windowsHide: true, stdio: ["ignore", "pipe", "pipe"] });
    let stdout = "";
    let stderr = "";
    let outputSize = 0;
    const collect = (target) => (chunk) => {
      outputSize += chunk.length;
      if (outputSize > maxOutput) {
        child.kill();
        reject(new Error("Command output exceeded the configured limit"));
        return;
      }
      if (target === "out") stdout += chunk.toString("utf8");
      else stderr += chunk.toString("utf8");
    };
    child.stdout.on("data", collect("out"));
    child.stderr.on("data", collect("err"));
    const timer = setTimeout(() => child.kill(), timeoutMs);
    child.on("error", (error) => {
      clearTimeout(timer);
      reject(new Error(command + " could not be started: " + error.message));
    });
    child.on("close", (code) => {
      clearTimeout(timer);
      if (code !== 0) reject(new Error((stderr || stdout || command + " failed").trim()));
      else resolve({ stdout, stderr });
    });
  });
}

export async function redactLogText(value, envFile) {
  let output = String(value ?? "");
  let env = "";
  try { env = await readFile(envFile, "utf8"); } catch {}
  for (const line of env.split(/\r?\n/)) {
    const match = line.match(/^\s*([A-Z0-9_]*(?:SECRET|TOKEN|PASSWORD|PRIVATE_KEY)[A-Z0-9_]*)\s*=\s*(.*)\s*$/i);
    if (match && match[2]) output = output.split(match[2]).join("[REDACTED]");
  }
  return output
    .replace(/(TURN_SHARED_SECRET\s*[=:]\s*)[^\s"'&,]+/gi, "$1[REDACTED]")
    .replace(/(Authorization\s*:\s*Bearer\s+)[A-Za-z0-9._~+/-]+=*/gi, "$1[REDACTED]");
}

async function dockerCompose(serviceDir, args, options = {}) {
  const result = await run("docker", ["compose", ...args], { cwd: serviceDir, ...options });
  return result.stdout.trimEnd();
}

function serviceDirectory(repositoryRoot) {
  const dir = path.resolve(repositoryRoot, "services", "discovery");
  return dir;
}

async function ensureServiceDirectory(root) {
  const dir = serviceDirectory(root);
  const compose = path.join(dir, "compose.yaml");
  await stat(compose).catch(() => { throw new Error("Could not find services/discovery/compose.yaml under " + root); });
  return dir;
}

function validDomain(value) {
  return typeof value === "string" && value.length <= 253 &&
    value.split(".").every((part) => /^[a-z0-9](?:[a-z0-9-]{0,61}[a-z0-9])?$/i.test(part));
}

export function isPublicIPv4(value) {
  if (net.isIP(value) !== 4) return false;
  const [a, b, c] = value.split(".").map(Number);
  if (a === 0 || a === 10 || a === 127 || a >= 224) return false;
  if (a === 100 && b >= 64 && b <= 127) return false;
  if (a === 169 && b === 254) return false;
  if (a === 172 && b >= 16 && b <= 31) return false;
  if (a === 192 && (b === 0 || b === 168 || (b === 0 && c === 2))) return false;
  if (a === 198 && (b === 18 || b === 19 || (b === 51 && c === 100))) return false;
  if (a === 203 && b === 0 && c === 113) return false;
  return true;
}

export function validateBootstrapMultiaddr(value) {
  const match = typeof value === "string" &&
    value.match(/^\/ip4\/([^/]+)\/tcp\/(\d+)\/p2p\/([A-Za-z0-9]{40,64})$/);
  if (!match || !isPublicIPv4(match[1])) return false;
  const port = Number(match[2]);
  return Number.isInteger(port) && port >= 1 && port <= 65535;
}

export async function updateServerBootstrapConfig(root, { bootstrapPeers, announceMultiaddrs }) {
  const dir = await ensureServiceDirectory(root);
  const envPath = path.join(dir, ".env");
  let raw = "";
  try { raw = await readFile(envPath, "utf8"); }
  catch { throw new Error("Run 'relyra server init' before configuring bootstrap peers"); }
  const updates = {};
  if (bootstrapPeers !== undefined) {
    if (!Array.isArray(bootstrapPeers) || !bootstrapPeers.every(validateBootstrapMultiaddr)) {
      throw new Error("Every bootstrap peer must be a public /ip4/<ip>/tcp/<port>/p2p/<peer-id> multiaddress");
    }
    updates.RELYRA_BOOTSTRAP_PEERS = bootstrapPeers.join(",");
  }
  if (announceMultiaddrs !== undefined) {
    if (!Array.isArray(announceMultiaddrs) || !announceMultiaddrs.every(validateBootstrapMultiaddr)) {
      throw new Error("Every announce address must be a public /ip4/<ip>/tcp/<port>/p2p/<peer-id> multiaddress");
    }
    updates.RELYRA_ANNOUNCE_MULTIADDRS = announceMultiaddrs.join(",");
  }
  if (Object.keys(updates).length === 0) throw new Error("No server settings were supplied");
  const lines = raw.split(/\r?\n/);
  for (const [key, value] of Object.entries(updates)) {
    const index = lines.findIndex((line) => line.startsWith(key + "="));
    if (index >= 0) lines[index] = key + "=" + value;
    else lines.push(key + "=" + value);
  }
  const output = lines.slice(0, lines.length - (lines.at(-1) === "" ? 1 : 0)).join("\n") + "\n";
  const temp = envPath + "." + randomBytes(6).toString("hex") + ".tmp";
  try {
    await writeFile(temp, output, { mode: 0o600, flag: "wx" });
    await rename(temp, envPath);
  } catch (error) {
    await rm(temp, { force: true });
    throw error;
  }
  return { configuredKeys: Object.keys(updates) };
}

export async function initializeServerConfig(root, { domain, turnHost, turnIp, email }) {
  if (!validDomain(domain) || !validDomain(turnHost)) throw new Error("Use valid DNS hostnames for --domain and --turn-host");
  if (!isPublicIPv4(turnIp)) throw new Error("--turn-ip must be a globally routable public IPv4 address");
  if (typeof email !== "string" || email.length > 254 || !/^[^\s@]+@[^\s@]+\.[^\s@]+$/.test(email)) {
    throw new Error("A valid ACME email is required");
  }
  const dir = await ensureServiceDirectory(root);
  const file = path.join(dir, ".env");
  const body = [
    "DISCOVERY_DOMAIN=" + domain,
    "ACME_EMAIL=" + email,
    "TURN_PUBLIC_HOST=" + turnHost,
    "TURN_REALM=" + turnHost,
    "TURN_EXTERNAL_IP=" + turnIp,
    "TURN_INTERNAL_IP=172.30.0.11",
    "RELYRA_DOCKER_SUBNET=172.30.0.0/24",
    "TURN_SHARED_SECRET=" + randomBytes(32).toString("hex"),
    "RELYRA_BOOTSTRAP_PEERS=",
    "RELYRA_ANNOUNCE_MULTIADDRS="
  ].join("\n") + "\n";
  await writeFile(file, body, { flag: "wx", mode: 0o600 });
  return file;
}

async function composeVolumes(serviceDir) {
  const raw = await dockerCompose(serviceDir, ["config", "--format", "json"]);
  const config = JSON.parse(raw);
  const project = config.name || path.basename(serviceDir);
  return Object.entries(config.volumes || {}).map(([key, value]) => ({
    key,
    name: value.name || (project + "_" + key)
  }));
}

async function sha256(file) {
  const hash = createHash("sha256");
  await new Promise((resolve, reject) => {
    const input = createReadStream(file);
    input.on("data", (chunk) => hash.update(chunk));
    input.on("error", reject);
    input.on("end", resolve);
  });
  return hash.digest("hex");
}

function safeVolumeName(value) {
  return /^[A-Za-z0-9_.-]{1,128}$/.test(value);
}

async function backupDirectory(root) {
  const dir = path.resolve(root, ".relyra-backups");
  await mkdir(dir, { recursive: true, mode: 0o700 });
  return dir;
}

export function serializeBackupManifest(manifest) {
  return JSON.stringify(manifest, null, 2) + "\n";
}

async function snapshotStoppedVolumes(root, serviceDir, label = "manual") {
  const volumes = await composeVolumes(serviceDir);
  if (volumes.length === 0) throw new Error("Compose configuration declares no persistent volumes");
  const backupDir = await backupDirectory(root);
  const stamp = new Date().toISOString().replace(/[-:]/g, "").replace(/\.\d{3}Z$/, "Z");
  const id = stamp + (label === "pre-restore" ? "P" : "");
  const files = [];
  try {
    for (const volume of volumes) {
      if (!safeVolumeName(volume.name)) throw new Error("Unsafe Compose volume name");
      const filename = id + "-" + volume.key + ".tar.gz";
      await run("docker", [
        "run", "--rm",
        "--mount", "type=volume,source=" + volume.name + ",target=/data,readonly",
        "--mount", "type=bind,source=" + backupDir + ",target=/backup",
        "alpine:3.20", "tar", "-czf", "/backup/" + filename, "-C", "/data", "."
      ], { cwd: serviceDir, timeoutMs: 300000 });
      files.push({ volumeKey: volume.key, volumeName: volume.name, file: filename, sha256: await sha256(path.join(backupDir, filename)) });
    }
    const manifest = {
      schemaVersion: 1,
      id,
      createdAt: new Date().toISOString(),
      scope: "Docker Compose named volumes only; .env secrets and source files are intentionally excluded",
      label,
      files
    };
    await writeFile(path.join(backupDir, id + ".json"), serializeBackupManifest(manifest), { flag: "wx", mode: 0o600 });
    return manifest;
  } catch (error) {
    await Promise.all(files.map((item) => rm(path.join(backupDir, item.file), { force: true })));
    throw error;
  }
}

export async function createServiceBackup(root) {
  const serviceDir = await ensureServiceDirectory(root);
  const running = await dockerCompose(serviceDir, ["ps", "--services", "--filter", "status=running"]);
  const runningServices = running.split(/\r?\n/).map((name) => name.trim()).filter(Boolean);
  if (runningServices.length) await dockerCompose(serviceDir, ["stop", ...runningServices]);
  try {
    return await snapshotStoppedVolumes(root, serviceDir);
  } finally {
    // Restore only services that were running before the snapshot began.
    if (runningServices.length) await dockerCompose(serviceDir, ["start", ...runningServices]);
  }
}

async function readManifest(root, id) {
  if (!/^[0-9TZP]{14,20}$/.test(id)) throw new Error("Invalid backup ID");
  const dir = await backupDirectory(root);
  const file = path.join(dir, id + ".json");
  const manifest = JSON.parse(await readFile(file, "utf8"));
  if (manifest.schemaVersion !== 1 || manifest.id !== id || !Array.isArray(manifest.files)) {
    throw new Error("Unsupported or damaged backup manifest");
  }
  return { dir, manifest };
}

export async function listServiceBackups(root) {
  const dir = await backupDirectory(root);
  const names = (await readdir(dir)).filter((name) => /^[0-9TZP]{14,20}\.json$/.test(name)).sort().reverse();
  const results = [];
  for (const name of names) {
    try {
      const manifest = JSON.parse(await readFile(path.join(dir, name), "utf8"));
      results.push({ id: manifest.id, createdAt: manifest.createdAt, label: manifest.label || "manual", files: manifest.files.length, scope: manifest.scope });
    } catch {
      results.push({ id: name.slice(0, -5), invalid: true });
    }
  }
  return results;
}

export async function verifyServiceBackup(root, id) {
  const { dir, manifest } = await readManifest(root, id);
  for (const item of manifest.files) {
    if (!/^[A-Za-z0-9_.-]{1,160}\.tar\.gz$/.test(item.file)) throw new Error("Unsafe backup archive name");
    const file = path.resolve(dir, item.file);
    if (path.dirname(file) !== dir) throw new Error("Backup path escaped its directory");
    if (await sha256(file) !== item.sha256) throw new Error("SHA-256 mismatch for " + item.file);
  }
  return { id, verifiedFiles: manifest.files.length, valid: true };
}

export async function restoreServiceBackup(root, id, confirmation) {
  if (confirmation !== "RESTORE " + id) throw new Error("Restore requires the exact confirmation: RESTORE " + id);
  const serviceDir = await ensureServiceDirectory(root);
  const running = await dockerCompose(serviceDir, ["ps", "--services", "--filter", "status=running"]);
  if (running.trim()) throw new Error("Stop all RELYRA services before restoring a volume snapshot");
  const { dir, manifest } = await readManifest(root, id);
  await verifyServiceBackup(root, id);
  const recoverySnapshot = await snapshotStoppedVolumes(root, serviceDir, "pre-restore");
  const currentVolumes = await composeVolumes(serviceDir);
  try {
  for (const item of manifest.files) {
    const current = currentVolumes.find((volume) => volume.key === item.volumeKey);
    if (!current || current.name !== item.volumeName) {
      throw new Error("Backup volume layout does not match the current Compose project");
    }
    const archive = path.resolve(dir, item.file);
    if (path.dirname(archive) !== dir) throw new Error("Backup path escaped its directory");
    const listing = await run("docker", [
      "run", "--rm",
      "--mount", "type=bind,source=" + dir + ",target=/backup,readonly",
      "alpine:3.20", "tar", "-tzf", "/backup/" + item.file
    ], { cwd: serviceDir, timeoutMs: 120000 });
    const entries = listing.stdout.split(/\r?\n/).filter(Boolean);
    if (entries.some((entry) => entry.startsWith("/") || entry.split("/").includes(".."))) {
      throw new Error("Archive contains an unsafe path");
    }
    await run("docker", [
      "run", "--rm",
      "--mount", "type=volume,source=" + item.volumeName + ",target=/data",
      "--mount", "type=bind,source=" + dir + ",target=/backup,readonly",
      "alpine:3.20", "sh", "-c",
      "find /data -mindepth 1 -maxdepth 1 -exec rm -rf -- {} + && tar -xzf \"/backup/$1\" -C /data",
      "--", item.file
    ], { cwd: serviceDir, timeoutMs: 300000 });
  }
  return { id, restoredVolumes: manifest.files.length, recoverySnapshotId: recoverySnapshot.id };
  } catch (error) {
    throw new Error("Restore did not finish. A pre-restore recovery snapshot was kept as " +
      recoverySnapshot.id + ". Verify it before retrying. " + String(error?.message || error));
  }
}

export async function deleteServiceBackup(root, id) {
  const { dir, manifest } = await readManifest(root, id);
  const names = manifest.files.map((item) => item.file);
  for (const name of names) {
    if (!/^[A-Za-z0-9_.-]{1,160}\.tar\.gz$/.test(name)) throw new Error("Unsafe backup archive name");
    const target = path.resolve(dir, name);
    if (path.dirname(target) !== dir) throw new Error("Backup path escaped its directory");
  }
  await Promise.all([...names.map((name) => rm(path.join(dir, name), { force: true })), rm(path.join(dir, id + ".json"), { force: true })]);
  return { id, deleted: true };
}

export async function redactComposeOutput(root, value) {
  return redactLogText(value, path.join(serviceDirectory(root), ".env"));
}

export async function runServerAction(root, action) {
  const serviceDir = await ensureServiceDirectory(root);
  switch (action) {
    case "status":
      return { action, output: await dockerCompose(serviceDir, ["ps", "--all"]) };
    case "start":
      return { action, output: await dockerCompose(serviceDir, ["up", "-d", "--remove-orphans"]) };
    case "stop":
      return { action, output: await dockerCompose(serviceDir, ["stop"]) };
    case "restart":
      return { action, output: await dockerCompose(serviceDir, ["restart"]) };
    case "rebuild":
      return {
        action,
        output: [
          await dockerCompose(serviceDir, ["pull", "--ignore-buildable"]),
          await dockerCompose(serviceDir, ["build", "--pull"]),
          await dockerCompose(serviceDir, ["up", "-d", "--remove-orphans"])
        ].filter(Boolean).join("\n")
      };
    case "logs":
      return { action, output: await redactComposeOutput(root, await dockerCompose(serviceDir, ["logs", "--tail", "200", "--no-color"])) };
    case "backup":
      return { action, backup: await createServiceBackup(root) };
    case "backup-list":
      return { action, backups: await listServiceBackups(root) };
    default:
      throw new Error("Unsupported Server Studio operation");
  }
}
