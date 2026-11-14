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
  // Execute each DDL statement separately. D1's exec() multi-statement path
  // can reject this schema batch as an incomplete CREATE TABLE statement.
  await db.prepare(`
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
    )
  `).run();
  await db.prepare(`
    CREATE INDEX IF NOT EXISTS idx_webworkshop_licenses_build
    ON webworkshop_licenses(product_id, build_id, created_at)
  `).run();
  await db.prepare(`
    CREATE INDEX IF NOT EXISTS idx_webworkshop_licenses_status
    ON webworkshop_licenses(used_at, revoked_at, created_at)
  `).run();
}

function adminAuthorized(req, env) {
  const configured = String(env.WEBWORKSHOP_ADMIN_KEY || "");
  // Admin keys may contain Unicode. Headers only accept ISO-8859-1 byte strings,
  // so the browser sends the UTF-8 bytes as lowercase hexadecimal ASCII.
  const encoded = String(req.headers.get("x-webworkshop-admin-key-utf8") || "");
  if (configured.length < 32 || !/^(?:[0-9a-f]{2})+$/.test(encoded)) return false;
  const bytes = new Uint8Array(encoded.match(/../g).map(part => parseInt(part, 16)));
  const supplied = new TextDecoder().decode(bytes);
  return supplied.length === configured.length && supplied === configured;
}

async function handleAdmin(req, env, url) {
  if (url.pathname === "/admin/webworkshop-licenses.js" && req.method === "GET") {
    return new Response(ADMIN_SCRIPT, { headers: { "content-type": "application/javascript; charset=utf-8", "cache-control": "no-store" } });
  }
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
    // D1 batch requests are chunked to stay within practical statement limits.
    for (let i = 0; i < statements.length; i += 100) {
      await env.DB.batch(statements.slice(i, i + 100));
    }
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
</div><button id="createButton" type="button">生成序列号</button> <button id="refreshButton" class="secondary" type="button" onclick="var m=document.getElementById('message');if(m)m.textContent='刷新按钮点击事件已触发，正在读取记录…';if(typeof window.loadList==='function'){window.loadList()}else if(m){m.textContent='按钮事件已触发，但刷新函数未加载。请反馈页面版本号。'}">刷新记录</button>
<div class="muted">每个序列号仅可成功激活一次；不同序列号可在同一设备上先后使用。生成后无法再次查看完整明文。</div></div>
<div class="panel"><b>本次生成的序列号（仅显示一次）</b><pre id="codes">尚未生成</pre><button id="copyButton" class="secondary" type="button">复制序列号</button><button id="downloadButton" class="secondary" type="button">导出 TXT</button></div>
<div class="panel"><b>使用记录</b><div id="message" class="muted">管理页面版本：2026-11-14-3；正在等待脚本启动…</div><div style="overflow:auto"><table><thead><tr><th>序列号提示</th><th>版本</th><th>状态</th><th>激活时间 / 设备</th><th>操作</th></tr></thead><tbody id="rows"></tbody></table></div></div>
</main><script src="/admin/webworkshop-licenses.js?v=20261114-4"></script></body></html>`;
const ADMIN_SCRIPT = "\"use strict\";\n// 立即更新状态：用于区分脚本完全未运行与后续初始化失败。\nvar bootMessage = document.getElementById(\"message\");\nif (bootMessage) { bootMessage.textContent = \"管理页面版本：2026-11-14-3；脚本已启动，正在初始化按钮…\"; bootMessage.style.color = \"#166534\"; }\nvar latest = [];\nvar keyInput = document.getElementById(\"key\");\nvar messageBox = document.getElementById(\"message\");\nvar codesBox = document.getElementById(\"codes\");\nvar createButton = document.getElementById(\"createButton\");\nvar refreshButton = document.getElementById(\"refreshButton\");\nfunction showMessage(message, isError) {\n  messageBox.textContent = message;\n  messageBox.style.color = isError ? \"#b91c1c\" : \"#166534\";\n  messageBox.style.fontWeight = isError ? \"700\" : \"400\";\n}\nfunction safe(value) {\n  return String(value == null ? \"\" : value)\n    .replace(/&/g, \"&amp;\").replace(/</g, \"&lt;\")\n    .replace(/>/g, \"&gt;\").replace(/\"/g, \"&quot;\")\n    .replace(/'/g, \"&#39;\");\n}\nfunction adminKey() { return keyInput.value.trim(); }\nfunction explainError(error) {\n  if (!error) return \"未知错误\";\n  var parts = [];\n  if (error.name) parts.push(error.name);\n  if (error.message) parts.push(error.message);\n  if (error.stack) parts.push(error.stack.split(\"\\n\").slice(0, 3).join(\" | \"));\n  return parts.join(\"：\") || String(error);\n}\nasync function api(path, options) {\n  options = options || {};\n  var headers = Object.assign({}, options.headers || {}, {\n    \"content-type\": \"application/json\",\n    \"x-webworkshop-admin-key-utf8\": Array.from(new TextEncoder().encode(adminKey()), function (b) { return b.toString(16).padStart(2, \"0\"); }).join(\"\")\n  });\n  var response;\n  try {\n    response = await fetch(path, Object.assign({}, options, { headers: headers, cache: \"no-store\" }));\n  } catch (error) {\n    throw new Error(\"网络请求失败（\" + path + \"）： \" + explainError(error) + \"。请检查网络、Worker 地址及浏览器连接。\");\n  }\n  var raw = await response.text();\n  var data;\n  try { data = raw ? JSON.parse(raw) : {}; }\n  catch (_) { data = { raw: raw }; }\n  if (!response.ok) {\n    var detail = data.detail ? ((data.error || \"服务器错误\") + \"；内部详情：\" + data.detail) : (data.error || data.message || data.raw || raw || \"服务器未返回错误详情\");\n    throw new Error(\"请求 \" + path + \" 失败，HTTP \" + response.status + \" \" + response.statusText + \"；服务器信息：\" + String(detail).slice(0, 1200));\n  }\n  if (data && data.ok === false) throw new Error(data.error || \"服务器返回失败状态\");\n  return data;\n}\nasync function loadList() {\n  showMessage(\"正在读取授权记录…\", false);\n  refreshButton.disabled = true;\n  try {\n    var data = await api(\"/api/webworkshop/admin/list\", { method: \"GET\" });\n    var records = data.licenses || [];\n    document.getElementById(\"rows\").innerHTML = records.map(function (item) {\n      var status = item.revoked_at ? \"已撤销\" : item.used_at ? \"已使用\" : \"未使用\";\n      var detail = item.used_at ? new Date(item.used_at).toLocaleString() + \" / \" + (item.device_id || \"\") : \"—\";\n      var action = !item.used_at && !item.revoked_at\n        ? '<button class=\"danger revokeButton\" data-id=\"' + safe(item.id) + '\">撤销</button>' : \"—\";\n      return \"<tr><td>\" + safe(item.serial_hint) + \"</td><td>\" + safe(item.build_id) +\n        \"</td><td>\" + status + \"</td><td>\" + safe(detail) + \"</td><td>\" + action + \"</td></tr>\";\n    }).join(\"\");\n    showMessage(\"记录读取成功：当前显示 \" + records.length + \" 条（最多 500 条）。\", false);\n  } catch (error) {\n    showMessage(\"读取记录失败：\" + explainError(error), true);\n  } finally {\n    refreshButton.disabled = false;\n  }\n}\nasync function createCodes() {\n  var buildId = document.getElementById(\"build\").value.trim();\n  var count = Number(document.getElementById(\"count\").value);\n  if (!adminKey()) {\n    showMessage(\"生成失败：管理员密钥为空，请先输入管理员密钥。\", true);\n    keyInput.focus();\n    return;\n  }\n  if (!/^[A-Za-z0-9._-]{1,40}$/.test(buildId)) {\n    showMessage(\"生成失败：版本标识格式无效，只能使用字母、数字、点、下划线和短横线。\", true);\n    return;\n  }\n  if (!Number.isInteger(count) || count < 1 || count > 500) {\n    showMessage(\"生成失败：数量必须是 1 至 500 的整数。\", true);\n    return;\n  }\n  createButton.disabled = true;\n  codesBox.textContent = \"正在生成，请稍候…\";\n  showMessage(\"正在连接授权服务器并生成序列号…\", false);\n  try {\n    var data = await api(\"/api/webworkshop/admin/create\", {\n      method: \"POST\",\n      body: JSON.stringify({ build_id: buildId, count: count })\n    });\n    latest = Array.isArray(data.serials) ? data.serials : [];\n    codesBox.textContent = latest.length ? latest.join(\"\\n\") : \"服务器请求成功，但没有返回序列号。请检查 Worker 响应。\";\n    if (!latest.length) {\n      showMessage(\"生成接口返回成功，但序列号列表为空。服务器响应：\" + JSON.stringify(data).slice(0, 1000), true);\n    } else {\n      showMessage(\"生成成功：已生成 \" + latest.length + \" 个序列号。请立即复制或导出保存，明文只显示这一次。\", false);\n      await loadList();\n    }\n  } catch (error) {\n    latest = [];\n    codesBox.textContent = \"生成失败。请查看上方详细错误信息。\";\n    showMessage(\"生成序列号失败：\" + explainError(error), true);\n  } finally {\n    createButton.disabled = false;\n  }\n}\nasync function revokeCode(id) {\n  if (!window.confirm(\"确定撤销这个尚未使用的序列号？\")) return;\n  try {\n    await api(\"/api/webworkshop/admin/revoke\", { method: \"POST\", body: JSON.stringify({ id: id }) });\n    showMessage(\"撤销成功。\", false);\n    await loadList();\n  } catch (error) {\n    showMessage(\"撤销失败：\" + explainError(error), true);\n  }\n}\nfunction copyCodes() {\n  if (!latest.length) {\n    showMessage(\"目前没有可复制的序列号。\", true);\n    return;\n  }\n  var value = latest.join(\"\\n\");\n  // Clipboard API is unavailable in some embedded/mobile browsers; use the\n  // selection-based legacy path as a fallback while still in the click gesture.\n  try {\n    var area = document.createElement(\"textarea\");\n    area.value = value;\n    area.setAttribute(\"readonly\", \"\");\n    area.style.position = \"fixed\";\n    area.style.left = \"-9999px\";\n    area.style.top = \"0\";\n    document.body.appendChild(area);\n    area.focus();\n    area.select();\n    area.setSelectionRange(0, area.value.length);\n    var copied = false;\n    try { copied = document.execCommand(\"copy\"); } catch (_) {}\n    area.remove();\n    if (copied) {\n      showMessage(\"序列号已复制到剪贴板。\", false);\n      return;\n    }\n  } catch (_) {}\n  if (navigator.clipboard && navigator.clipboard.writeText) {\n    navigator.clipboard.writeText(value).then(function () {\n      showMessage(\"序列号已复制到剪贴板。\", false);\n    }).catch(function (error) {\n      showMessage(\"自动复制失败：\" + explainError(error) + \"。请长按下方序列号文本并选择复制。\", true);\n      codesBox.focus();\n    });\n  } else {\n    showMessage(\"当前浏览器禁止自动复制。请长按下方序列号文本并选择复制。\", true);\n  }\n}\nfunction downloadCodes() {\n  if (!latest.length) {\n    showMessage(\"目前没有可导出的序列号。请先生成序列号。\", true);\n    return;\n  }\n  // Add a BOM so Chinese-capable Windows editors open the TXT as UTF-8.\n  var textContent = \"\\uFEFF\" + latest.join(\"\\r\\n\") + \"\\r\\n\";\n  var blob = new Blob([textContent], { type: \"text/plain;charset=utf-8\" });\n  var filename = \"webworkshop-serials-\" + new Date().toISOString().slice(0,10) + \".txt\";\n  var blobUrl = URL.createObjectURL(blob);\n  var link = document.createElement(\"a\");\n  link.href = blobUrl;\n  link.download = filename;\n  link.style.display = \"none\";\n  document.body.appendChild(link);\n  try {\n    link.click();\n    showMessage(\"已触发 TXT 导出：\" + filename + \"。如果浏览器询问保存位置，请选择保存。\", false);\n  } catch (error) {\n    showMessage(\"浏览器未能直接下载：\" + explainError(error) + \"。正在尝试打开文本文件。\", true);\n    window.open(blobUrl, \"_blank\");\n  }\n  link.remove();\n  // Keep the object URL alive long enough for Android/iOS download managers.\n  setTimeout(function () { URL.revokeObjectURL(blobUrl); }, 60000);\n}\nwindow.addEventListener(\"error\", function (event) {\n  showMessage(\"页面脚本运行错误：\" + (event.message || \"未知错误\") +\n    (event.filename ? \"；文件：\" + event.filename : \"\") +\n    (event.lineno ? \"；行号：\" + event.lineno : \"\"), true);\n});\nwindow.addEventListener(\"unhandledrejection\", function (event) {\n  showMessage(\"页面异步操作异常：\" + explainError(event.reason), true);\n});\n// 显式公开管理操作函数，供按钮调用。\nwindow.createCodes = createCodes;\nwindow.loadList = loadList;\nwindow.copyCodes = copyCodes;\nwindow.downloadCodes = downloadCodes;\n// Inline onclick + listeners: keep controls functional in normal browsers and embedded WebViews.\ncreateButton.addEventListener(\"click\", function (event) { event.preventDefault(); createCodes(); });\ndocument.getElementById(\"copyButton\").addEventListener(\"click\", function (event) { event.preventDefault(); copyCodes(); });\ndocument.getElementById(\"downloadButton\").addEventListener(\"click\", function (event) { event.preventDefault(); downloadCodes(); });\ndocument.getElementById(\"rows\").addEventListener(\"click\", function (event) {\n  var button = event.target.closest(\".revokeButton\");\n  if (button) revokeCode(button.getAttribute(\"data-id\"));\n});\nshowMessage(\"管理页面版本：2026-11-14-3；脚本初始化成功。请输入管理员密钥，然后点击刷新记录或生成序列号。\", false);\n";


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
