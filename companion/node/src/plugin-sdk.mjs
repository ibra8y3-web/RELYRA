// RELYRA Plugin SDK (developer side): validates and scaffolds data-only plugin packages.
// The limits below intentionally match the Android runtime (PluginRuntime / PluginSdk).
// A package accepted here is accepted by the app; the app still asks the user to approve
// permissions at install time and re-checks every action when it runs.

export const SCHEMA_VERSION = 1;
export const MIME_TYPE = "application/vnd.relyra.plugin+json";
export const MAX_PACKAGE_BYTES = 256 * 1024;
export const PERMISSIONS = Object.freeze(["clipboard.write", "external.open"]);
export const ACTION_TYPES = Object.freeze(["notice", "copy_text", "open_url"]);

const ID_PATTERN = /^[a-z0-9][a-z0-9._-]{2,63}$/;
const VERSION_PATTERN = /^[0-9]+\.[0-9]+\.[0-9]+$/;
const ACTION_ID_PATTERN = /^[A-Za-z0-9_-]{1,40}$/;
const LIMITS = Object.freeze({
  name: 80, description: 500, title: 80, notice: 2000, copy: 4000, url: 2048, minActions: 1, maxActions: 20
});

function isHttpsUrl(value) {
  if (typeof value !== "string" || value.length === 0 || value.length > LIMITS.url) return false;
  if (/[\s\u0000-\u001f\u007f]/.test(value)) return false;
  if (!value.toLowerCase().startsWith("https://")) return false;
  try {
    const url = new URL(value);
    return url.protocol === "https:" && url.hostname.length > 0 && url.username === "" && url.password === "";
  } catch {
    return false;
  }
}

/** Returns { ok: true, manifest } or { ok: false, errors: string[] }. Never throws. */
export function validatePluginManifest(source) {
  const errors = [];
  if (typeof source !== "string") return { ok: false, errors: ["Plugin package must be JSON text"] };
  if (Buffer.byteLength(source, "utf8") > MAX_PACKAGE_BYTES) {
    return { ok: false, errors: ["Plugin package is larger than " + MAX_PACKAGE_BYTES + " bytes"] };
  }
  let root;
  try {
    root = JSON.parse(source);
  } catch (e) {
    return { ok: false, errors: ["Invalid JSON: " + e.message] };
  }
  if (root === null || typeof root !== "object" || Array.isArray(root)) {
    return { ok: false, errors: ["Plugin package must be a JSON object"] };
  }
  if (root.schemaVersion !== SCHEMA_VERSION) errors.push("schemaVersion must be " + SCHEMA_VERSION);
  if (typeof root.id !== "string" || !ID_PATTERN.test(root.id)) {
    errors.push("id must match [a-z0-9][a-z0-9._-]{2,63}");
  }
  const name = typeof root.name === "string" ? root.name.trim() : "";
  if (name.length === 0 || name.length > LIMITS.name) errors.push("name must be 1-" + LIMITS.name + " characters");
  if (typeof root.version !== "string" || !VERSION_PATTERN.test(root.version.trim())) {
    errors.push("version must look like 1.0.0");
  }
  if (root.description !== undefined && (typeof root.description !== "string" || root.description.length > LIMITS.description)) {
    errors.push("description must be text of at most " + LIMITS.description + " characters");
  }

  let grants = [];
  if (root.permissions !== undefined) {
    if (!Array.isArray(root.permissions)) errors.push("permissions must be an array");
    else {
      grants = root.permissions;
      if (grants.length > PERMISSIONS.length) errors.push("too many permissions");
      if (new Set(grants).size !== grants.length) errors.push("duplicate permission");
      for (const p of grants) if (!PERMISSIONS.includes(p)) errors.push("unsupported permission: " + String(p));
    }
  }

  const actions = [];
  if (!Array.isArray(root.actions)) errors.push("actions must be an array");
  else {
    if (root.actions.length < LIMITS.minActions || root.actions.length > LIMITS.maxActions) {
      errors.push("a plugin must declare between " + LIMITS.minActions + " and " + LIMITS.maxActions + " actions");
    }
    const seen = new Set();
    root.actions.forEach((item, index) => {
      const at = "actions[" + index + "]";
      if (item === null || typeof item !== "object" || Array.isArray(item)) { errors.push(at + " must be an object"); return; }
      if (typeof item.id !== "string" || !ACTION_ID_PATTERN.test(item.id)) errors.push(at + ".id must match [A-Za-z0-9_-]{1,40}");
      else if (seen.has(item.id)) errors.push(at + ".id is duplicated");
      else seen.add(item.id);
      const title = typeof item.title === "string" ? item.title.trim() : "";
      if (title.length === 0 || title.length > LIMITS.title) errors.push(at + ".title must be 1-" + LIMITS.title + " characters");
      if (!ACTION_TYPES.includes(item.type)) { errors.push(at + ".type must be one of " + ACTION_TYPES.join(", ")); return; }
      if (item.type === "notice") {
        if (typeof item.text !== "string" || item.text.trim().length === 0 || item.text.length > LIMITS.notice) {
          errors.push(at + ".text is required and must be at most " + LIMITS.notice + " characters");
        }
      } else if (item.type === "copy_text") {
        if (!grants.includes("clipboard.write")) errors.push(at + " uses copy_text and requires the clipboard.write permission");
        if (typeof item.text !== "string" || item.text.trim().length === 0 || item.text.length > LIMITS.copy) {
          errors.push(at + ".text is required and must be at most " + LIMITS.copy + " characters");
        }
      } else if (item.type === "open_url") {
        if (!grants.includes("external.open")) errors.push(at + " uses open_url and requires the external.open permission");
        if (!isHttpsUrl(item.url)) errors.push(at + ".url must be a valid https:// link without credentials");
      }
      actions.push(item);
    });
  }

  if (errors.length > 0) return { ok: false, errors };
  return {
    ok: true,
    manifest: {
      id: root.id,
      name,
      version: root.version.trim(),
      description: typeof root.description === "string" ? root.description : "",
      permissions: [...grants],
      actions: actions.map((a) => ({ ...a }))
    }
  };
}

/** Creates a minimal valid package (a single notice action, no permissions). */
export function scaffoldPlugin({ id, name, version = "1.0.0", description = "" }) {
  const manifest = {
    schemaVersion: SCHEMA_VERSION,
    id,
    name,
    version,
    description,
    permissions: [],
    actions: [{ id: "hello", type: "notice", title: "Say hello", text: "Hello from " + name }]
  };
  const text = JSON.stringify(manifest, null, 2) + "\n";
  const check = validatePluginManifest(text);
  if (!check.ok) throw new Error(check.errors.join("; "));
  return text;
}
