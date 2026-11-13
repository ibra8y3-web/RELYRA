import http from "node:http";
import https from "node:https";

const ALLOWED_PATHS = new Set([
  "/v1/health",
  "/v1/diagnostics",
  "/v1/identity",
  "/v1/openapi.json"
]);

function isLoopback(hostname) {
  const value = hostname.toLowerCase().replace(/^\[|\]$/g, "");
  return value === "127.0.0.1" || value === "localhost" || value === "::1";
}

export function normalizeApiBase(input) {
  const url = new URL(input);
  if (url.username || url.password || url.search || url.hash) {
    throw new Error("API base URL must not contain credentials, query parameters or fragments");
  }
  if (url.protocol !== "https:" && url.protocol !== "http:") {
    throw new Error("Only HTTPS or HTTP loopback is supported");
  }
  if (!isLoopback(url.hostname)) {
    throw new Error("The RELYRA companion API must connect to loopback only");
  }
  if (!url.hostname || (url.port && (Number(url.port) < 1 || Number(url.port) > 65535))) {
    throw new Error("Invalid API endpoint");
  }
  url.pathname = "/";
  return url;
}

export function isSafeApiPath(path) {
  return ALLOWED_PATHS.has(path);
}

function requestJson({ baseUrl, token, endpoint, hostHeader, timeoutMs = 6000 }) {
  if (!isSafeApiPath(endpoint)) throw new Error("Unsupported RELYRA API endpoint");
  if (!token || !/^[A-Za-z0-9_-]{40,50}$/.test(token)) {
    throw new Error("Set RELYRA_API_TOKEN to the active temporary token");
  }
  const base = normalizeApiBase(baseUrl);
  const transport = base.protocol === "https:" ? https : http;
  const options = {
    hostname: base.hostname.replace(/^\[|\]$/g, ""),
    port: base.port || (base.protocol === "https:" ? 443 : 80),
    path: endpoint,
    method: "GET",
    headers: {
      Host: hostHeader || base.host,
      Authorization: "Bearer " + token,
      Accept: "application/json",
      Connection: "close"
    },
    timeout: timeoutMs
  };
  return new Promise((resolve, reject) => {
    const req = transport.request(options, (res) => {
      const chunks = [];
      let size = 0;
      res.on("data", (chunk) => {
        size += chunk.length;
        if (size > 1024 * 1024) {
          req.destroy(new Error("RELYRA API response exceeded 1 MiB"));
          return;
        }
        chunks.push(chunk);
      });
      res.on("end", () => {
        const body = Buffer.concat(chunks).toString("utf8");
        if (res.statusCode < 200 || res.statusCode >= 300) {
          let detail = "HTTP " + res.statusCode;
          try { detail = JSON.parse(body).error || detail; } catch {}
          reject(new Error("RELYRA API request rejected: " + detail));
          return;
        }
        try { resolve(JSON.parse(body)); }
        catch { reject(new Error("RELYRA API returned invalid JSON")); }
      });
    });
    req.on("timeout", () => req.destroy(new Error("RELYRA API request timed out")));
    req.on("error", reject);
    req.end();
  });
}

export class RelyraClient {
  constructor({ baseUrl = "http://127.0.0.1:8787", token = process.env.RELYRA_API_TOKEN, devicePort } = {}) {
    this.base = normalizeApiBase(baseUrl);
    this.token = token;
    this.hostHeader = devicePort ? "127.0.0.1:" + Number(devicePort) : undefined;
    if (devicePort !== undefined && (!Number.isInteger(Number(devicePort)) || Number(devicePort) < 1 || Number(devicePort) > 65535)) {
      throw new Error("Invalid Android Local API port");
    }
  }

  health() {
    return requestJson({ baseUrl: this.base.href, token: this.token, endpoint: "/v1/health", hostHeader: this.hostHeader });
  }

  diagnostics() {
    return requestJson({ baseUrl: this.base.href, token: this.token, endpoint: "/v1/diagnostics", hostHeader: this.hostHeader });
  }

  identity() {
    return requestJson({ baseUrl: this.base.href, token: this.token, endpoint: "/v1/identity", hostHeader: this.hostHeader });
  }

  openApi() {
    return requestJson({ baseUrl: this.base.href, token: this.token, endpoint: "/v1/openapi.json", hostHeader: this.hostHeader });
  }
}
