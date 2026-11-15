import test from "node:test";
import assert from "node:assert/strict";
import { readFile, readdir } from "node:fs/promises";
import path from "node:path";
import { fileURLToPath } from "node:url";
import { validatePluginManifest, scaffoldPlugin, MAX_PACKAGE_BYTES } from "../src/plugin-sdk.mjs";

const examples = path.resolve(path.dirname(fileURLToPath(import.meta.url)), "../../../plugins/examples");

function pkg(overrides = {}) {
  return JSON.stringify({
    schemaVersion: 1, id: "dev.sample", name: "Sample", version: "1.2.3", description: "d",
    permissions: [], actions: [{ id: "a1", type: "notice", title: "T", text: "hello" }], ...overrides
  });
}

test("every shipped example plugin is valid", async () => {
  const files = (await readdir(examples)).filter((f) => f.endsWith(".json"));
  assert.ok(files.length >= 2);
  for (const file of files) {
    const result = validatePluginManifest(await readFile(path.join(examples, file), "utf8"));
    assert.equal(result.ok, true, file + ": " + (result.errors || []).join("; "));
  }
});

test("scaffold output validates", () => {
  const result = validatePluginManifest(scaffoldPlugin({ id: "dev.new-one", name: "New one" }));
  assert.equal(result.ok, true);
});

test("rejects bad JSON, non-objects and oversize packages", () => {
  assert.equal(validatePluginManifest("{").ok, false);
  assert.equal(validatePluginManifest("[]").ok, false);
  assert.equal(validatePluginManifest(42).ok, false);
  assert.equal(validatePluginManifest(pkg({ description: "x".repeat(MAX_PACKAGE_BYTES) })).ok, false);
});

test("rejects wrong schema, id, version and name", () => {
  assert.equal(validatePluginManifest(pkg({ schemaVersion: 2 })).ok, false);
  assert.equal(validatePluginManifest(pkg({ id: "Bad ID" })).ok, false);
  assert.equal(validatePluginManifest(pkg({ id: "ab" })).ok, false);
  assert.equal(validatePluginManifest(pkg({ version: "1.0" })).ok, false);
  assert.equal(validatePluginManifest(pkg({ name: "  " })).ok, false);
  assert.equal(validatePluginManifest(pkg({ name: "n".repeat(81) })).ok, false);
});

test("permissions are an allow-list without duplicates", () => {
  assert.equal(validatePluginManifest(pkg({ permissions: ["root.shell"] })).ok, false);
  assert.equal(validatePluginManifest(pkg({ permissions: ["clipboard.write", "clipboard.write"] })).ok, false);
});

test("actions need their permission and valid content", () => {
  const copy = { id: "c", type: "copy_text", title: "Copy", text: "x" };
  assert.equal(validatePluginManifest(pkg({ actions: [copy] })).ok, false);
  assert.equal(validatePluginManifest(pkg({ permissions: ["clipboard.write"], actions: [copy] })).ok, true);
  const open = (url) => ({ id: "o", type: "open_url", title: "Open", url });
  const withOpen = (url) => validatePluginManifest(pkg({ permissions: ["external.open"], actions: [open(url)] })).ok;
  assert.equal(withOpen("https://example.org/a"), true);
  assert.equal(withOpen("http://example.org"), false);
  assert.equal(withOpen("javascript:alert(1)"), false);
  assert.equal(withOpen("https://user:pw@example.org"), false);
  assert.equal(withOpen("https://exa mple.org"), false);
  assert.equal(validatePluginManifest(pkg({ actions: [open("https://example.org")] })).ok, false);
});

test("action limits match the Android runtime", () => {
  const many = (n) => Array.from({ length: n }, (_, i) => ({ id: "a" + i, type: "notice", title: "T", text: "t" }));
  assert.equal(validatePluginManifest(pkg({ actions: many(20) })).ok, true);
  assert.equal(validatePluginManifest(pkg({ actions: many(21) })).ok, false);
  assert.equal(validatePluginManifest(pkg({ actions: [] })).ok, false);
  assert.equal(validatePluginManifest(pkg({ actions: [{ id: "a", type: "notice", title: "T", text: "x".repeat(2001) }] })).ok, false);
  assert.equal(validatePluginManifest(pkg({ actions: [{ id: "a", type: "shell", title: "T" }] })).ok, false);
  const dup = [{ id: "a", type: "notice", title: "T", text: "t" }, { id: "a", type: "notice", title: "U", text: "u" }];
  assert.equal(validatePluginManifest(pkg({ actions: dup })).ok, false);
});
