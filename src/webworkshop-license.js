const encoder = new TextEncoder();

function reply(data, status = 200) {
  return new Response(JSON.stringify(data), {
    status,
    headers: {
      "content-type": "application/json; charset=utf-8",
      "cache-control": "no-store"
    }
  });
}

async function digest(value) {
  const bytes = await crypto.subtle.digest("SHA-256", encoder.encode(value));
  return [...new Uint8Array(bytes)].map(v => v.toString(16).padStart(2, "0")).join("");
}

function normalizeSerial(value) {
  return String(value || "").toUpperCase().replace(/[^A-Z2-9]/g, "");
}

function makeSerial() {
  const alphabet = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789";
  const bytes = crypto.getRandomValues(new Uint8Array(16));
  let bits = 0, buffer = 0, out = "";
  for (const byte of bytes) {
    buffer = (buffer << 8) | byte;
    bits += 8;
    while (bits >= 5) {
      bits -= 5;
      out += alphabet[(buffer >>> bits) & 31];
    }
  }
  if (bits > 0) out += alphabet[(buffer << (5 - bits)) & 31];
  return "WW-" + out.match(/.{1,4}/g).join("-");
}

async function ensureLicenseSchema(db) {
  await db.exec(`
    CREATE TABLE IF NOT EXISTS webworkshop_licenses (
      id TEXT PRIMARY KEY,
      serial_hash TEXT NOT NULL UNIQUE,
      serial_hint TEXT NOT NULL,
      product_id TEXT NOT NULL,
      build_id TEXT NOT NULL,
      created_at INTEGER NOT NULL,
      used_at INTEGER,
      device_id TEXT,
      app_version TEXT,
      package_name TEXT,
      revoked_at INTEGER
    );
    CREATE INDEX IF NOT EXISTS idx_webworkshop_licenses_build
      ON webworkshop_licenses(product_id, build_id, created_at);
    CREATE INDEX IF NOT EXISTS idx_webworkshop_licenses_status
      ON webworkshop_licenses(used_at, revoked_at, created_at);
  `);
}

function adminAuthorized(req, env) {
  const configured = String(env.WEBWORKSHOP_ADMIN_KEY || "");
  const supplied = String(req.headers.get("x-webworkshop-admin-key") || "");
  return configured.length >= 32 && supplied.length === configured.length && supplied === configured;
}

async function handleAdmin(req, env, url) {
  if (!adminAuthorized(req, env)) return reply({ error: "管理员密钥错误或尚未配置" }, 401);

  if (url.pathname === "/api/webworkshop/admin/create" && req.method === "POST") {
    let body;
    try { body = await req.json(); } catch { return reply({ error: "请求内容无效" }, 400); }
    const buildId = String(body.build_id || "").trim();
    const count = Number(body.count || 1);
    if (!/^[A-Za-z0-9._-]{1,40}$/.test(buildId)) return reply({ error: "版本标识格式无效" }, 400);
    if (!Number.isInteger(count) || count < 1 || count > 500) return reply({ error: "每次只能生成 1 至 500 个序列号" }, 400);

    const createdAt = Date.now();
    const issued = [];
    const statements = [];
    for (let i = 0; i < count; i++) {
      const serial = makeSerial();
      const serialHash = await digest(normalizeSerial(serial));
      const id = crypto.randomUUID();
      issued.push({ serial, id });
      statements.push(env.DB.prepare(
        "INSERT INTO webworkshop_licenses(id,serial_hash,serial_hint,product_id,build_id,created_at) VALUES(?,?,?,?,?,?)"
      ).bind(id, serialHash, serial.slice(0, 7) + "…" + serial.slice(-4), "webworkshop", buildId, createdAt));
    }
    // D1 batch requests are chunked to stay within practical statement limits.\n    for (let i = 0; i < statements.length; i += 100) {\n      await env.DB.batch(statements.slice(i, i + 100));\n    }
    // Plaintext serials are returned only once and are never stored in the database.
    return reply({ ok: true, product_id: "webworkshop", build_id: buildId, count, serials: issued.map(x => x.serial) });
  }

  if (url.pathname === "/api/webworkshop/admin/list" && req.method === "GET") {
    const buildId = url.searchParams.get("build_id");
    const result = buildId
      ? await env.DB.prepare("SELECT id,serial_hint,product_id,build_id,created_at,used_at,device_id,app_version,package_name,revoked_at FROM webworkshop_licenses WHERE build_id=? ORDER BY created_at DESC LIMIT 500").bind(buildId).all()
      : await env.DB.prepare("SELECT id,serial_hint,product_id,build_id,created_at,used_at,device_id,app_version,package_name,revoked_at FROM webworkshop_licenses ORDER BY created_at DESC LIMIT 500").all();
    return reply({ licenses: result.results || [] });
  }

  if (url.pathname === "/api/webworkshop/admin/revoke" && req.method === "POST") {
    let body;
    try { body = await req.json(); } catch { return reply({ error: "请求内容无效" }, 400); }
    const id = String(body.id || "");
    if (!id) return reply({ error: "缺少序列号记录 ID" }, 400);
    const result = await env.DB.prepare(
      "UPDATE webworkshop_licenses SET revoked_at=? WHERE id=? AND used_at IS NULL AND revoked_at IS NULL"
    ).bind(Date.now(), id).run();
    if (!result.meta || result.meta.changes !== 1) return reply({ error: "记录不存在、已使用或已撤销" }, 409);
    return reply({ ok: true });
  }

  return reply({ error: "Not found" }, 404);
}

const ADMIN_HTML = `<!doctype html>
<html lang="zh-CN"><head><meta charset="utf-8"><meta name="viewport" content="width=device-width,initial-scale=1">
<title>网页工坊序列号管理</title>
<style>
*{box-sizing:border-box}body{margin:0;background:#f3f6fa;color:#1e293b;font:15px system-ui,"Microsoft YaHei",sans-serif;padding:22px}
main{max-width:900px;margin:auto;background:white;border:1px solid #e2e8f0;border-radius:16px;padding:22px}
h2{margin:0 0 8px}p,.muted{color:#64748b;font-size:13px}
input,button{font:inherit;border-radius:9px;padding:11px;border:1px solid #cbd5e1}
input{width:100%;margin:7px 0 12px}button{background:#2563eb;color:white;border:0;cursor:pointer;margin:4px 4px 4px 0}
button.secondary{background:#e2e8f0;color:#1e293b}button.danger{background:#b91c1c}
.grid{display:grid;grid-template-columns:1fr 140px;gap:10px}.panel{padding:15px;background:#f8fafc;border:1px solid #e2e8f0;border-radius:12px;margin-top:16px}
pre{white-space:pre-wrap;word-break:break-all;background:#0f172a;color:#e2e8f0;padding:14px;border-radius:10px;max-height:260px;overflow:auto}
table{border-collapse:collapse;width:100%;font-size:13px;margin-top:12px}td,th{border-bottom:1px solid #e2e8f0;text-align:left;padding:9px 5px;word-break:break-word}
@media(max-width:600px){body{padding:9px}main{padding:14px}.grid{grid-template-columns:1fr}}
</style></head><body><main>
<h2>网页工坊 · 序列号管理</h2>
<p>管理员页面。序列号只在生成时显示一次；数据库仅保存 SHA-256 摘要。请立即复制并安全保存。</p>
<label>管理员密钥</label><input id="key" type="password" autocomplete="off" placeholder="输入 Cloudflare Worker 管理员密钥">
<div class="panel"><b>生成一次性序列号</b><div class="grid">
<div><label>适用网页工坊版本标识</label><input id="build" value="1.4.6" placeholder="例如 1.4.6"></div>
<div><label>数量</label><input id="count" type="number" min="1" max="500" value="10"></div>
</div><button onclick="createCodes()">生成序列号</button> <button class="secondary" onclick="loadList()">刷新记录</button>
<div class="muted">每个序列号仅可成功激活一次；不同序列号可在同一设备上先后使用。生成后无法再次查看完整明文。</div></div>
<div class="panel"><b>本次生成的序列号（仅显示一次）</b><pre id="codes">尚未生成</pre><button class="secondary" onclick="copyCodes()">复制序列号</button><button class="secondary" onclick="downloadCodes()">导出 TXT</button></div>
<div class="panel"><b>使用记录</b><div id="message" class="muted">输入管理员密钥后点击刷新。</div><div style="overflow:auto"><table><thead><tr><th>序列号提示</th><th>版本</th><th>状态</th><th>激活时间 / 设备</th><th>操作</th></tr></thead><tbody id="rows"></tbody></table></div></div>
</main><script>
let latest=[];
const key=()=>document.getElementById("key").value;
async function api(path,options={}){const r=await fetch(path,{...options,headers:{...(options.headers||{}),"content-type":"application/json","x-webworkshop-admin-key":key()}});const j=await r.json();if(!r.ok)throw Error(j.error||"请求失败");return j}
async function createCodes(){try{const j=await api("/api/webworkshop/admin/create",{method:"POST",body:JSON.stringify({build_id:document.getElementById("build").value,count:Number(document.getElementById("count").value)})});latest=j.serials;document.getElementById("codes").textContent=latest.join("\n");document.getElementById("message").textContent="已生成 "+j.count+" 个序列号。明文仅显示本次，请立即保存。";await loadList()}catch(e){alert(e.message)}}
async function loadList(){try{const j=await api("/api/webworkshop/admin/list");document.getElementById("rows").innerHTML=(j.licenses||[]).map(x=>{const status=x.revoked_at?"已撤销":x.used_at?"已使用":"未使用";const detail=x.used_at?new Date(x.used_at).toLocaleString()+" / "+(x.device_id||""):"—";return "<tr><td>"+x.serial_hint+"</td><td>"+x.build_id+"</td><td>"+status+"</td><td>"+detail+"</td><td>"+(!x.used_at&&!x.revoked_at?'<button class="danger" onclick="revoke(\''+x.id+'\')">撤销</button>':"—")+"</td></tr>"}).join("");document.getElementById("message").textContent="共显示 "+(j.licenses||[]).length+" 条（最多 500 条）"}catch(e){document.getElementById("message").textContent=e.message}}
async function revoke(id){if(!confirm("确定撤销这个尚未使用的序列号？"))return;try{await api("/api/webworkshop/admin/revoke",{method:"POST",body:JSON.stringify({id})});await loadList()}catch(e){alert(e.message)}}
function copyCodes(){if(latest.length)navigator.clipboard.writeText(latest.join("\n")).then(()=>alert("已复制"))}
function downloadCodes(){if(!latest.length)return;const a=document.createElement("a");a.href=URL.createObjectURL(new Blob([latest.join("\n")],{type:"text/plain;charset=utf-8"}));a.download="webworkshop-serials.txt";a.click();URL.revokeObjectURL(a.href)}
</script></body></html>`;

export async function handleWebWorkshopLicenseRequest(req, env) {
  const url = new URL(req.url);
  if (url.pathname === "/admin/webworkshop-licenses" && req.method === "GET") {
    return new Response(ADMIN_HTML, { headers: { "content-type": "text/html; charset=utf-8", "cache-control": "no-store" } });
  }
  if (!url.pathname.startsWith("/api/webworkshop/")) return null;
  if (!env.DB) return reply({ error: "Worker 的 D1 数据库绑定 DB 未配置" }, 503);
  await ensureLicenseSchema(env.DB);

  if (url.pathname === "/api/webworkshop/activate" && req.method === "POST") {
    let body;
    try { body = await req.json(); } catch { return reply({ error: "激活请求格式错误" }, 400); }
    const serial = normalizeSerial(body.serial);
    const buildId = String(body.build_id || "").trim();
    const deviceId = String(body.device_id || "").trim();
    const packageName = String(body.package_name || "").trim();
    const appVersion = String(body.app_version || "").trim();
    if (serial.length < 20 || serial.length > 40) return reply({ error: "序列号无效" }, 400);
    if (!/^[A-Za-z0-9._-]{1,40}$/.test(buildId) || !deviceId || deviceId.length > 128) return reply({ error: "激活信息不完整" }, 400);
    if (packageName !== "com.greatwy8888.webworkshop") return reply({ error: "此序列号不适用于该应用" }, 403);

    const serialHash = await digest(serial);
    const activatedAt = Date.now();
    const result = await env.DB.prepare(
      "UPDATE webworkshop_licenses SET used_at=?,device_id=?,app_version=?,package_name=? WHERE serial_hash=? AND product_id='webworkshop' AND build_id=? AND used_at IS NULL AND revoked_at IS NULL"
    ).bind(activatedAt, deviceId, appVersion, packageName, serialHash, buildId).run();

    if (result.meta && result.meta.changes === 1) {
      const row = await env.DB.prepare("SELECT id,build_id,used_at FROM webworkshop_licenses WHERE serial_hash=?").bind(serialHash).first();
      return reply({ ok: true, activation_id: row.id, build_id: row.build_id, activated_at: row.used_at, device_id: deviceId });
    }

    const existing = await env.DB.prepare("SELECT build_id,used_at,revoked_at FROM webworkshop_licenses WHERE serial_hash=?").bind(serialHash).first();
    if (!existing) return reply({ error: "序列号不存在或输入错误" }, 404);
    if (existing.revoked_at) return reply({ error: "该序列号已被管理员撤销" }, 403);
    if (existing.used_at) return reply({ error: "该序列号已经使用，不能重复激活" }, 409);
    return reply({ error: "该序列号不适用于当前网页工坊版本" }, 403);
  }

  if (url.pathname.startsWith("/api/webworkshop/admin/")) {
    if (!adminAuthorized(req, env)) return reply({ error: "管理员密钥错误或尚未配置" }, 401);
    return handleAdmin(req, env, url);
  }
  return reply({ error: "Not found" }, 404);
}
