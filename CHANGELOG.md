# Changelog

All notable changes are listed here. Entries describe what was changed and how it was checked; nothing is marked complete before it is implemented and verified.

## Unreleased

### Added
- Plugin SDK for developers: `relyra plugin init` and `relyra plugin validate`, example plugins in `plugins/examples/`, and guides `docs/PLUGIN_SDK.md` and `docs/PLUGIN_SDK_AR.md`. Checked with Node tests; the Android side is covered by `PluginSdkTest` (not yet run on a Kotlin toolchain).
- Passphrase encryption for server backup archives: `relyra backup-file encrypt|decrypt` (AES-256-GCM, scrypt). Checked with Node tests. The Server Studio backup button is not yet connected to it (Planned).
- `docs/IMPLEMENTATION_STATUS.md` and `docs/IMPLEMENTATION_STATUS_AR.md`: an honest status table per feature.
- `CODE_OF_CONDUCT.md` and this changelog.

### Changed
- Browser: tabs and per-site permissions. Up to 8 tabs (each with its own web view; open tabs are restored after the screen is recreated), and per-site choices for JavaScript, cookies, camera and microphone, stored Keystore-wrapped. Camera and microphone requests from a page ask first (allow now, always allow, block this site); Android's own permission is requested if the app does not have it yet. Cookie handling in Android's WebView is app-wide, so the cookie rule of the site you are viewing applies to all requests while that tab is shown. Rule storage and parsing are covered by `BrowserSiteRulesTest`; the screen itself is not yet run on a device or compiler.
- Local API can now send messages. New opt-in scope `messages.send` (off by default, switch in Settings → Integrations): `POST /v1/messages {to, text}` and `POST /v1/groups/messages {groupId, text}` go through the same delivery engine and group engine as the app, so encryption, MessageID and blocking rules still apply. `GET /v1/contacts` and `GET /v1/groups` list ids and names for addressing. Limits: JSON only, 8 KiB body, 20 sends per minute, loopback and bearer token as before. Covered by `LocalApiServerTest` (JVM socket tests, not yet run on a Kotlin toolchain). Creating groups and adding or removing members through the API is not implemented.
- Built-in browser rewritten for reliability. The page view is created only after the first navigation, so an empty white web view can no longer cover the address bar and controls (the blank screen reported on a Samsung phone). Back and forward buttons now follow the real page state. Added: start panel, bookmarks and optional browsing history (both wrapped with the Android Keystore, history off by default), stop button, copy and share link, confirmed downloads (saved in the app downloads folder, never opened automatically) and confirmed hand-off to another app. Not yet verified on a device; tabs are not implemented.
- Developer Tools now includes a Plugin SDK section that really uses `PluginSdk`: check a plugin file, save an example plugin, and open the plugin manager. `PluginSdk` was previously not connected to anything.
- `PluginSdk` limits now match `PluginRuntime` (at most 20 actions, shorter text limits, open_url checked with the browser URL policy).
- Integrations screen (Matrix, XMPP/OMEMO, IPFS): all remaining English text moved to string resources with Arabic translations (60 strings).

### Fixed
- `companion/node/bin/relyra.mjs` had an unterminated string in its usage text, so the `relyra` command could not start.
- `scripts/audit_go_vulns.py` failed with `JSONDecodeError` on real `govulncheck -json` output (indented JSON objects). It now reads a stream of JSON objects and still accepts one object per line.
