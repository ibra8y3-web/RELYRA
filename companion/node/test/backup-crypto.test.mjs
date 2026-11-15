import test from "node:test";
import assert from "node:assert/strict";
import { mkdtemp, writeFile, readFile, rm, readdir, access } from "node:fs/promises";
import { tmpdir } from "node:os";
import path from "node:path";
import { randomBytes } from "node:crypto";
import { encryptBackupFile, decryptBackupFile } from "../src/backup-crypto.mjs";

const PASS = "correct horse battery";

async function sandbox(fn) {
  const dir = await mkdtemp(path.join(tmpdir(), "relyra-bk-"));
  try { await fn(dir); } finally { await rm(dir, { recursive: true, force: true }); }
}

test("round trip restores identical bytes, including a multi-chunk file", async () => {
  await sandbox(async (dir) => {
    const plain = randomBytes(3 * 1024 * 1024 + 17);
    await writeFile(path.join(dir, "a.tar.gz"), plain);
    await encryptBackupFile(path.join(dir, "a.tar.gz"), path.join(dir, "a.enc"), PASS);
    assert.notDeepEqual(await readFile(path.join(dir, "a.enc")), plain);
    await decryptBackupFile(path.join(dir, "a.enc"), path.join(dir, "b.tar.gz"), PASS);
    assert.deepEqual(await readFile(path.join(dir, "b.tar.gz")), plain);
  });
});

test("empty files round trip", async () => {
  await sandbox(async (dir) => {
    await writeFile(path.join(dir, "e"), Buffer.alloc(0));
    await encryptBackupFile(path.join(dir, "e"), path.join(dir, "e.enc"), PASS);
    await decryptBackupFile(path.join(dir, "e.enc"), path.join(dir, "e.out"), PASS);
    assert.equal((await readFile(path.join(dir, "e.out"))).length, 0);
  });
});

test("wrong passphrase fails and leaves no output or partial file", async () => {
  await sandbox(async (dir) => {
    await writeFile(path.join(dir, "a"), randomBytes(5000));
    await encryptBackupFile(path.join(dir, "a"), path.join(dir, "a.enc"), PASS);
    await assert.rejects(decryptBackupFile(path.join(dir, "a.enc"), path.join(dir, "out"), "another passphrase!"), /wrong passphrase/);
    assert.deepEqual((await readdir(dir)).sort(), ["a", "a.enc"]);
  });
});

test("a modified byte anywhere is detected", async () => {
  await sandbox(async (dir) => {
    await writeFile(path.join(dir, "a"), randomBytes(5000));
    await encryptBackupFile(path.join(dir, "a"), path.join(dir, "a.enc"), PASS);
    for (const offset of [40, 2500]) {
      const bytes = await readFile(path.join(dir, "a.enc"));
      bytes[offset] ^= 0x01;
      await writeFile(path.join(dir, "bad.enc"), bytes);
      await assert.rejects(decryptBackupFile(path.join(dir, "bad.enc"), path.join(dir, "out"), PASS));
      await assert.rejects(access(path.join(dir, "out")));
    }
  });
});

test("rejects short passphrases, foreign files and existing outputs", async () => {
  await sandbox(async (dir) => {
    await writeFile(path.join(dir, "a"), Buffer.from("data"));
    await assert.rejects(encryptBackupFile(path.join(dir, "a"), path.join(dir, "x"), "short"), /at least 12/);
    await writeFile(path.join(dir, "junk"), randomBytes(200));
    await assert.rejects(decryptBackupFile(path.join(dir, "junk"), path.join(dir, "o"), PASS), /Not a RELYRA/);
    await writeFile(path.join(dir, "taken"), "x");
    await assert.rejects(encryptBackupFile(path.join(dir, "a"), path.join(dir, "taken"), PASS), /already exists/);
    assert.equal(await readFile(path.join(dir, "taken"), "utf8"), "x");
    await encryptBackupFile(path.join(dir, "a"), path.join(dir, "ok.enc"), PASS);
    await writeFile(path.join(dir, "exists"), "keep");
    await assert.rejects(decryptBackupFile(path.join(dir, "ok.enc"), path.join(dir, "exists"), PASS), /already exists/);
    assert.equal(await readFile(path.join(dir, "exists"), "utf8"), "keep");
    assert.ok(!(await readdir(dir)).some((n) => n.endsWith(".partial")));
  });
});

test("two encryptions of the same file differ (fresh salt and IV)", async () => {
  await sandbox(async (dir) => {
    await writeFile(path.join(dir, "a"), Buffer.from("same"));
    await encryptBackupFile(path.join(dir, "a"), path.join(dir, "1.enc"), PASS);
    await encryptBackupFile(path.join(dir, "a"), path.join(dir, "2.enc"), PASS);
    assert.notDeepEqual(await readFile(path.join(dir, "1.enc")), await readFile(path.join(dir, "2.enc")));
  });
});
