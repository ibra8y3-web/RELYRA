import http from "node:http";
import { randomBytes, timingSafeEqual } from "node:crypto";
import { execFile } from "node:child_process";
import { promisify } from "node:util";
import path from "node:path";
import { runServerAction, listServiceBackups, restoreServiceBackup, verifyServiceBackup, deleteServiceBackup, redactComposeOutput } from "./compose.mjs";

const execFileAsync = promisify(execFile);

function equalToken(expected, supplied) {
  if (typeof supplied !== "string") return false;
  const left = Buffer.from(expected);
  const right = Buffer.from(supplied);
  return left.length === right.length && timingSafeEqual(left, right);
}

function sendJson(res, status, value) {
  const body = Buffer.from(JSON.stringify(value));
  res.writeHead(status, {
    "Content-Type": "application/json; charset=utf-8",
    "Content-Length": body.length,
    "Cache-Control": "no-store",
    "X-Content-Type-Options": "nosniff",
    "Referrer-Policy": "no-referrer"
  });
  res.end(body);
}

const page = [
"<!doctype html><html lang='en'><head><meta charset='utf-8'><meta name='viewport' content='width=device-width,initial-scale=1'><title>RELYRA Server Studio</title>",
"<style>body{font:15px system-ui;background:#101923;color:#e8eef5;margin:0;padding:24px}main{max-width:920px;margin:auto}h1{margin-bottom:4px}.muted{color:#a8bacb}.grid{display:grid;grid-template-columns:repeat(auto-fit,minmax(160px,1fr));gap:10px;margin:20px 0}.card{background:#1b2a38;border:1px solid #314457;border-radius:12px;padding:14px}button{border:0;border-radius:8px;padding:11px 14px;margin:4px;background:#86b7d8;color:#071522;font-weight:650;cursor:pointer}button.danger{background:#d99b9b}pre{white-space:pre-wrap;overflow-wrap:anywhere;background:#08111b;padding:16px;border-radius:10px;min-height:110px;max-height:460px;overflow:auto}.row{display:flex;flex-wrap:wrap;gap:4px}small{color:#a8bacb}</style></head><body><main>",
"<h1>RELYRA Server Studio</h1><div class='muted'>Local operator console · loopback only · no public admin endpoint</div>",
"<div class='grid'><div class='card'><b>Discovery</b><p id='status'>Loading service state…</p><small>Docker Compose</small></div><div class='card'><b>Backups</b><p id='backup-count'>Not loaded</p><small>Encrypted data volumes are not password-encrypted by this tool; protect the backup directory.</small></div></div>",
"<div class='card'><b>Service lifecycle</b><div class='row'><button data-action='status'>Refresh status</button><button data-action='start'>Start</button><button data-action='stop' class='danger'>Stop</button><button data-action='restart'>Restart</button><button data-action='rebuild'>Pull & rebuild</button><button data-action='logs'>Read redacted logs</button></div></div>",
"<div class='card' style='margin-top:12px'><b>Persistent volume snapshots</b><div class='row'><button data-action='backup'>Create backup</button><button data-action='backup-list'>List backups</button><button id='verify-latest'>Verify latest</button><button id='restore-latest' class='danger'>Restore latest snapshot</button><button id='delete-latest' class='danger'>Delete latest</button></div></div>",
"<h3>Output</h3><pre id='output'>Connecting to local Server Studio…</pre><small>Backups contain Docker Compose named volumes only. Keep the .env file separately in a password manager. Never publish it.</small>",
"</main><script>",
"const token=location.hash.slice(1);history.replaceState(null,'',location.pathname);",
"const out=document.getElementById('output');const status=document.getElementById('status');",
"async function call(action,extra={}){out.textContent='Running '+action+'…';try{const r=await fetch('/api/action',{method:'POST',headers:{'Content-Type':'application/json','Authorization':'Bearer '+token},body:JSON.stringify({action,...extra})});const data=await r.json();if(!r.ok)throw new Error(data.error||('HTTP '+r.status));out.textContent=JSON.stringify(data,null,2);if(action==='status')status.textContent=data.output||'No service rows returned';return data}catch(e){out.textContent='Error: '+e.message;throw e}}",

"async function loadBackups(){const data=await call('backup-list');const list=data.backups||[];document.getElementById('backup-count').textContent=list.length+' snapshot(s)';document.getElementById('restore-latest').disabled=!list.length;document.getElementById('restore-latest').dataset.id=list[0]?.id||'';document.getElementById('verify-latest').disabled=!list.length;document.getElementById('verify-latest').dataset.id=list[0]?.id||'';document.getElementById('delete-latest').disabled=!list.length;document.getElementById('delete-latest').dataset.id=list[0]?.id||''}",
"document.querySelectorAll('[data-action]').forEach(b=>b.addEventListener('click',()=>{const action=b.dataset.action;const task=action==='backup-list'?loadBackups():call(action).then(()=>{if(action==='backup')return loadBackups()});task.catch(()=>{})}));",
"document.getElementById('restore-latest').addEventListener('click',async()=>{const id=document.getElementById('restore-latest').dataset.id;if(!id)return;const confirmText=prompt('Stop every RELYRA container first. Type RESTORE '+id+' to replace all named volumes.');if(confirmText!=='RESTORE '+id)return;await call('restore',{id,confirmation:confirmText})});",
"document.getElementById('verify-latest').addEventListener('click',async()=>{const id=document.getElementById('verify-latest').dataset.id;if(id)await call('verify-backup',{id})});",
"document.getElementById('delete-latest').addEventListener('click',async()=>{const id=document.getElementById('delete-latest').dataset.id;if(!id)return;const confirmText=prompt('Type DELETE '+id+' to permanently remove this snapshot.');if(confirmText!=='DELETE '+id)return;await call('delete-backup',{id,confirmation:confirmText});await loadBackups()});",
"call('status').then(loadBackups).catch(()=>{});",
"</script></body></html>"
].join("");

export async function startServerStudio({ repositoryRoot, port = 0, openBrowser = true }) {
  const root = path.resolve(repositoryRoot);
  const token = randomBytes(32).toString("base64url");
  let actionCount = 0;
  let windowStart = Date.now();
  const startedAt = Date.now();
  const server = http.createServer(async (req, res) => {
    try {
      if (!req.socket.remoteAddress || !(req.socket.remoteAddress === "::1" || req.socket.remoteAddress === "127.0.0.1" || req.socket.remoteAddress === "::ffff:127.0.0.1")) {
        sendJson(res, 403, { error: "Loopback clients only" });
        return;
      }
      const address = server.address();
      const expectedHost = "127.0.0.1:" + address.port;
      if (req.headers.host !== expectedHost && req.headers.host !== "localhost:" + address.port) {
        sendJson(res, 400, { error: "Invalid Host header" });
        return;
      }
      if (req.url === "/" && req.method === "GET") {
        res.writeHead(200, {
          "Content-Type": "text/html; charset=utf-8",
          "Cache-Control": "no-store",
          "Content-Security-Policy": "default-src 'none'; style-src 'unsafe-inline'; script-src 'unsafe-inline'; connect-src 'self'; base-uri 'none'; form-action 'none'; frame-ancestors 'none'",
          "X-Content-Type-Options": "nosniff",
          "Referrer-Policy": "no-referrer",
          "Cross-Origin-Resource-Policy": "same-origin",
          "Permissions-Policy": "camera=(), microphone=(), geolocation=()"
        });
        res.end(page);
        return;
      }
      if (req.url !== "/api/action" || req.method !== "POST") {
        sendJson(res, 404, { error: "Not found" });
        return;
      }
      if (Date.now() - startedAt > 4 * 60 * 60 * 1000) {
        sendJson(res, 401, { error: "Server Studio session expired; restart the local console" });
        return;
      }
      const origin = req.headers.origin;
      if (origin && origin !== "http://" + expectedHost && origin !== "http://localhost:" + address.port) {
        sendJson(res, 403, { error: "Cross-origin requests are not allowed" });
        return;
      }
      if (req.headers["content-type"]?.split(";")[0].trim().toLowerCase() !== "application/json") {
        sendJson(res, 415, { error: "application/json is required" });
        return;
      }
      const auth = req.headers.authorization || "";
      if (!auth.startsWith("Bearer ") || !equalToken(token, auth.slice(7))) {
        sendJson(res, 401, { error: "Local Studio token required" });
        return;
      }
      if (Date.now() - windowStart >= 60000) { windowStart = Date.now(); actionCount = 0; }
      if (++actionCount > 60) {
        sendJson(res, 429, { error: "Local action rate limit exceeded" });
        return;
      }
      let raw = "";
      for await (const chunk of req) {
        raw += chunk.toString("utf8");
        if (raw.length > 4096) {
          sendJson(res, 413, { error: "Request too large" });
          return;
        }
      }
      const body = JSON.parse(raw || "{}");
      let result;
      if (body.action === "restore") {
        result = await restoreServiceBackup(root, String(body.id || ""), body.confirmation);
      } else if (body.action === "verify-backup") {
        if (!/^[0-9TZP]{14,20}$/.test(String(body.id || ""))) throw new Error("Invalid backup ID");
        result = await verifyServiceBackup(root, body.id);
      } else if (body.action === "delete-backup") {
        if (body.confirmation !== "DELETE " + body.id) throw new Error("Backup deletion requires the exact confirmation: DELETE " + body.id);
        result = await deleteServiceBackup(root, body.id);
      } else if (["status", "start", "stop", "restart", "rebuild", "logs", "backup", "backup-list"].includes(body.action)) {
        result = await runServerAction(root, body.action);
      } else {
        sendJson(res, 400, { error: "Unsupported operation" });
        return;
      }
      sendJson(res, 200, result);
    } catch (error) {
      const safeMessage = await redactComposeOutput(root, String(error?.message || "Server Studio operation failed"));
      sendJson(res, 500, { error: safeMessage.slice(0, 1000) });
    }
  });
  await new Promise((resolve, reject) => {
    server.once("error", reject);
    server.listen(port, "127.0.0.1", resolve);
  });
  const address = server.address();
  const url = "http://127.0.0.1:" + address.port + "/#" + token;
  process.stdout.write("RELYRA Server Studio: http://127.0.0.1:" + address.port + "/ (session token is in the one-time launch fragment)\n");
  if (openBrowser) {
    try {
      if (process.platform === "win32") await execFileAsync("cmd", ["/c", "start", "", "http://127.0.0.1:" + address.port + "/#" + token]);
      else if (process.platform === "darwin") await execFileAsync("open", [url]);
      else await execFileAsync("xdg-open", [url]);
    } catch {
      process.stdout.write("Open the local Studio URL printed above in your browser.\n");
    }
  }
  return server;
}
