# Encrypting server backup archives

**Status:** Implemented and tested (Node tests) · The Server Studio **Create backup** button still produces unencrypted `.tar.gz` snapshots; encryption is a separate command you run on those archives.

Snapshots made by the companion tools live in `.relyra-backups/` next to your Compose project. Protect a copy with a passphrase:

```bash
export RELYRA_BACKUP_PASSPHRASE='a long passphrase of at least 12 characters'
node bin/relyra.mjs backup-file encrypt .relyra-backups/<id>-<volume>.tar.gz <id>-<volume>.tar.gz.rlybk
node bin/relyra.mjs backup-file decrypt <id>-<volume>.tar.gz.rlybk restored.tar.gz
```

- The passphrase is read only from the environment, never from command-line arguments.
- Format: AES-256-GCM with a key derived by scrypt (N=32768), fresh salt and IV per file.
- A wrong passphrase or any modified byte makes decryption fail, and no partial output is left behind.
- Existing files are never overwritten.
- If you lose the passphrase the file cannot be recovered.

Restoring through Server Studio still needs the plain `.tar.gz` in `.relyra-backups/` (its SHA-256 must match the manifest). Wiring encryption directly into create/restore needs a Docker test environment and is **Planned**.
