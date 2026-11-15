# RELYRA Plugin SDK

**Status:** Implemented (data-only plugins) · Tested (Node validator and JUnit tests in the repository) · Not verified on a physical device.

RELYRA plugins are **JSON packages**, not code. They contain no DEX, native code, scripts, reflection or shell commands. The app validates a package at install time, asks the user to approve permissions, and checks every action again when it runs.

## Package format

MIME type: `application/vnd.relyra.plugin+json` · Maximum size: 256 KiB · Schema version: `1`

| Field | Rule |
|---|---|
| `schemaVersion` | must be `1` |
| `id` | `[a-z0-9][a-z0-9._-]{2,63}` |
| `name` | 1–80 characters |
| `version` | `MAJOR.MINOR.PATCH`, e.g. `1.0.0` |
| `description` | optional, up to 500 characters |
| `permissions` | optional, from the allow-list below, no duplicates |
| `actions` | 1–20 actions with unique ids matching `[A-Za-z0-9_-]{1,40}` |

## Permissions

- `clipboard.write` — required by `copy_text`.
- `external.open` — required by `open_url`.

## Action types

- `notice` — shows an in-app message. Field `text`, up to 2000 characters.
- `copy_text` — copies `text` (up to 4000 characters) to the clipboard. Needs `clipboard.write`.
- `open_url` — opens `url` after the user confirms. Must start with `https://`, no credentials, up to 2048 characters. Needs `external.open`.

Every action also has `id` and `title` (1–80 characters).

## Developer tools (no phone needed)

From `companion/node`:

```bash
node bin/relyra.mjs plugin init --id dev.my-plugin --name "My plugin" --output my-plugin.json
node bin/relyra.mjs plugin validate my-plugin.json
```

Working examples are in `plugins/examples/`. The validator in `companion/node/src/plugin-sdk.mjs` uses the same limits as the Android runtime (`PluginRuntime`, `PluginSdk`), so a package accepted by `validate` is accepted by the app.

## Checking a plugin inside the app

Open **Settings → Developer tools → Plugin SDK**. You can check a plugin file with the same validator the app uses at install time, save an example plugin to start from, and open the plugin manager to install it.

## What plugins cannot do

Plugins cannot read messages, keys or the database, cannot make network requests, cannot run code and cannot call the Local API. Loading code (DEX, native or JavaScript) is **Planned**, not implemented, and would need a separate sandbox and security review.
