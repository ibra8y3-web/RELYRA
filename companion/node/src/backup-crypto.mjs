// Passphrase encryption for backup files created by the companion tools.
// Format: "RLYBK1" | scrypt log2(N) (1 byte) | salt (16) | IV (12) | AES-256-GCM ciphertext | tag (16)
// Decryption writes to a temporary file and renames it only after the GCM tag verifies,
// so a wrong passphrase or a modified file never leaves partial plaintext behind.
import { createCipheriv, createDecipheriv, randomBytes, scrypt } from "node:crypto";
import { createReadStream, createWriteStream } from "node:fs";
import { link, open, rm, stat } from "node:fs/promises";
import { pipeline } from "node:stream/promises";

const MAGIC = Buffer.from("RLYBK1", "ascii");
const SALT_BYTES = 16;
const IV_BYTES = 12;
const TAG_BYTES = 16;
const HEADER_BYTES = MAGIC.length + 1 + SALT_BYTES + IV_BYTES;
const LOG2_N = 15; // scrypt N=32768, r=8, p=1 (about 32 MiB of memory)
export const MIN_PASSPHRASE_LENGTH = 12;

// Publishes a finished temporary file without ever replacing an existing file.
async function publishNew(temporary, finalPath) {
  try {
    await link(temporary, finalPath); // fails with EEXIST instead of overwriting
  } finally {
    await rm(temporary, { force: true });
  }
}

function deriveKey(passphrase, salt, log2n) {
  return new Promise((resolve, reject) => {
    scrypt(passphrase, salt, 32, { N: 2 ** log2n, r: 8, p: 1, maxmem: 128 * 1024 * 1024 }, (error, key) =>
      error ? reject(error) : resolve(key));
  });
}

function requirePassphrase(passphrase) {
  if (typeof passphrase !== "string" || passphrase.length < MIN_PASSPHRASE_LENGTH) {
    throw new Error("Passphrase must be at least " + MIN_PASSPHRASE_LENGTH + " characters");
  }
}

export async function encryptBackupFile(inputPath, outputPath, passphrase) {
  requirePassphrase(passphrase);
  const salt = randomBytes(SALT_BYTES);
  const iv = randomBytes(IV_BYTES);
  const key = await deriveKey(passphrase, salt, LOG2_N);
  const cipher = createCipheriv("aes-256-gcm", key, iv);
  const temporary = outputPath + ".partial";
  try {
    const output = createWriteStream(temporary, { flags: "wx", mode: 0o600 });
    output.write(Buffer.concat([MAGIC, Buffer.from([LOG2_N]), salt, iv]));
    await pipeline(createReadStream(inputPath), cipher, output, { end: false });
    output.write(cipher.getAuthTag());
    await new Promise((resolve, reject) => { output.once("error", reject); output.end(resolve); });
    await publishNew(temporary, outputPath);
  } catch (error) {
    await rm(temporary, { force: true });
    if (error?.code === "EEXIST") throw new Error("Output file already exists: " + outputPath);
    throw error;
  }
  return { output: outputPath, bytes: (await stat(outputPath)).size };
}

export async function decryptBackupFile(inputPath, outputPath, passphrase) {
  requirePassphrase(passphrase);
  const size = (await stat(inputPath)).size;
  if (size < HEADER_BYTES + TAG_BYTES) throw new Error("Not a RELYRA encrypted backup (file too small)");
  const handle = await open(inputPath, "r");
  let header;
  let tag;
  try {
    header = Buffer.alloc(HEADER_BYTES);
    await handle.read(header, 0, HEADER_BYTES, 0);
    tag = Buffer.alloc(TAG_BYTES);
    await handle.read(tag, 0, TAG_BYTES, size - TAG_BYTES);
  } finally {
    await handle.close();
  }
  if (!header.subarray(0, MAGIC.length).equals(MAGIC)) throw new Error("Not a RELYRA encrypted backup (bad header)");
  const log2n = header[MAGIC.length];
  if (log2n < 14 || log2n > 20) throw new Error("Unsupported key derivation setting");
  const salt = header.subarray(MAGIC.length + 1, MAGIC.length + 1 + SALT_BYTES);
  const iv = header.subarray(MAGIC.length + 1 + SALT_BYTES);
  const key = await deriveKey(passphrase, salt, log2n);
  const decipher = createDecipheriv("aes-256-gcm", key, iv);
  decipher.setAuthTag(tag);
  const temporary = outputPath + ".partial";
  try {
    const output = createWriteStream(temporary, { flags: "wx", mode: 0o600 });
    if (size === HEADER_BYTES + TAG_BYTES) {
      // Empty plaintext: there is no ciphertext body, only the tag to verify.
      output.write(decipher.final());
      await new Promise((resolve, reject) => { output.once("error", reject); output.end(resolve); });
    } else {
      await pipeline(createReadStream(inputPath, { start: HEADER_BYTES, end: size - TAG_BYTES - 1 }), decipher, output);
    }
    await publishNew(temporary, outputPath);
  } catch (error) {
    await rm(temporary, { force: true });
    if (error?.code === "EEXIST") throw new Error("Output file already exists: " + outputPath);
    throw new Error("Decryption failed: wrong passphrase or the backup was modified");
  }
  return { output: outputPath, bytes: (await stat(outputPath)).size };
}
