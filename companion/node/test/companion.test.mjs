import test from "node:test";
import assert from "node:assert/strict";
import { normalizeApiBase, isSafeApiPath, RelyraClient } from "../src/client.mjs";
import { createServer } from "node:http";
import { startServerStudio } from "../src/studio.mjs";
import { redactLogText, isPublicIPv4, validateBootstrapMultiaddr, serializeBackupManifest } from "../src/compose.mjs";
import { mkdtemp, writeFile, rm } from "node:fs/promises";
import os from "node:os";
import path from "node:path";

test("cleartext API is restricted to loopback", () => {
  assert.equal(normalizeApiBase("http://127.0.0.1:8787").hostname, "127.0.0.1");
  assert.equal(normalizeApiBase("http://localhost:8787").port, "8787");
  assert.throws(() => normalizeApiBase("http://192.168.1.10:8787"), /loopback/);
  assert.throws(() => normalizeApiBase("http://example.org"), /loopback/);
});

test("HTTPS API endpoints are still restricted to loopback", () => {
  assert.equal(normalizeApiBase("https://127.0.0.1:8787").protocol, "https:");
  assert.throws(() => normalizeApiBase("https://user:pass@127.0.0.1"), /credentials/);
  assert.throws(() => normalizeApiBase("https://api.example.org"), /loopback/);
  assert.throws(() => normalizeApiBase("ftp://127.0.0.1"), /HTTPS/);
});

test("public service addresses reject private and documentation ranges", () => {
  assert.equal(isPublicIPv4("8.8.8.8"), true);
  assert.equal(isPublicIPv4("192.168.1.2"), false);
  assert.equal(isPublicIPv4("127.0.0.1"), false);
  assert.equal(isPublicIPv4("203.0.113.10"), false);
});

test("bootstrap peer multiaddresses require a public IPv4 and valid TCP port", () => {
  const peer = "12D3KooW" + "a".repeat(44);
  assert.equal(validateBootstrapMultiaddr("/ip4/8.8.8.8/tcp/4001/p2p/" + peer), true);
  assert.equal(validateBootstrapMultiaddr("/ip4/127.0.0.1/tcp/4001/p2p/" + peer), false);
  assert.equal(validateBootstrapMultiaddr("/ip4/8.8.8.8/tcp/70000/p2p/" + peer), false);
});

test("only documented read-only API routes are accepted", () => {
  assert.equal(isSafeApiPath("/v1/health"), true);
  assert.equal(isSafeApiPath("/v1/diagnostics"), true);
  assert.equal(isSafeApiPath("/v1/openapi.json"), true);
  assert.equal(isSafeApiPath("/v1/messages/send"), false);
  assert.equal(isSafeApiPath("/admin"), false);
});

test("operator log redaction removes configured secrets and bearer values", async () => {
  const dir = await mkdtemp(path.join(os.tmpdir(), "relyra-tools-"));
  const envFile = path.join(dir, ".env");
  try {
    await writeFile(envFile, "TURN_SHARED_SECRET=very-secret-value\nAPI_TOKEN=other-private-value\n");
    const result = await redactLogText(
      "TURN_SHARED_SECRET=very-secret-value Authorization: Bearer abcdefghi other-private-value",
      envFile
    );
    assert.equal(result.includes("very-secret-value"), false);
    assert.equal(result.includes("other-private-value"), false);
    assert.equal(result.includes("abcdefghi"), false);
    assert.equal(result.includes("[REDACTED]"), true);
  } finally {
    await rm(dir, { recursive: true, force: true });
  }
});

test("Node companion reads live health over a loopback bearer-authenticated connection", async () => {
  const token = "A".repeat(43);
  let receivedAuthorization = "";
  let receivedHost = "";
  const server = createServer((req, res) => {
    receivedAuthorization = req.headers.authorization || "";
    receivedHost = req.headers.host || "";
    res.writeHead(200, { "Content-Type": "application/json", "Cache-Control": "no-store" });
    res.end(JSON.stringify({ service: "RELYRA Local API", status: "ok" }));
  });
  await new Promise((resolve, reject) => {
    server.once("error", reject);
    server.listen(0, "127.0.0.1", resolve);
  });
  try {
    const address = server.address();
    const client = new RelyraClient({ baseUrl: "http://127.0.0.1:" + address.port, token });
    const result = await client.health();
    assert.equal(result.status, "ok");
    assert.equal(receivedAuthorization, "Bearer " + token);
    assert.equal(receivedHost, "127.0.0.1:" + address.port);
  } finally {
    await new Promise((resolve) => server.close(resolve));
  }
});

test("Server Studio serves only a local console and rejects unauthenticated actions", async () => {
  const originalWrite = process.stdout.write;
  process.stdout.write = function () { return true; };
  let server;
  try {
    server = await startServerStudio({ repositoryRoot: process.cwd(), port: 0, openBrowser: false });
  } finally {
    process.stdout.write = originalWrite;
  }
  try {
    const port = server.address().port;
    const page = await fetch("http://127.0.0.1:" + port + "/");
    assert.equal(page.status, 200);
    assert.match(page.headers.get("content-security-policy") || "", /default-src 'none'/);
    const denied = await fetch("http://127.0.0.1:" + port + "/api/action", {
      method: "POST",
      headers: { "Content-Type": "application/json", "Authorization": "Bearer invalid" },
      body: JSON.stringify({ action: "start" })
    });
    assert.equal(denied.status, 401);
    const crossOrigin = await fetch("http://127.0.0.1:" + port + "/api/action", {
      method: "POST",
      headers: { "Content-Type": "application/json", "Authorization": "Bearer invalid", "Origin": "http://attacker.invalid" },
      body: JSON.stringify({ action: "start" })
    });
    assert.equal(crossOrigin.status, 403);
  } finally {
    await new Promise((resolve) => server.close(resolve));
  }
});

test("backup manifest serialization produces parseable JSON with a real line ending", () => {
  const manifest = { schemaVersion: 1, id: "20261113T160000Z", files: [] };
  const serialized = serializeBackupManifest(manifest);
  assert.equal(serialized.endsWith("\n"), true);
  assert.deepEqual(JSON.parse(serialized), manifest);
  assert.equal(serialized.endsWith("\\n"), false);
});
