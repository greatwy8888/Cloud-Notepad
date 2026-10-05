const INDEX_HTML = "<!doctype html>\n<html lang=\"zh-CN\"><head><meta charset=\"utf-8\"><meta name=\"viewport\" content=\"width=device-width,initial-scale=1,viewport-fit=cover\">\n<title>Cloud Notepad V7</title><link rel=\"manifest\" href=\"/manifest.webmanifest\"><meta name=\"theme-color\" content=\"#3b82f6\">\n<style>\n:root{--bg:#f5f7fb;--panel:#fff;--line:#e6e9ef;--text:#172033;--muted:#697386;--accent:#3b82f6;--danger:#ef4444}\n*{box-sizing:border-box}body{margin:0;background:var(--bg);color:var(--text);font-family:system-ui,-apple-system,\"Segoe UI\",sans-serif}\nbutton,input,textarea{font:inherit}button{border:0;border-radius:10px;padding:9px 12px;cursor:pointer;background:#eef2f7;color:var(--text)}\nbutton.primary{background:var(--accent);color:#fff}.danger{color:#b91c1c}.hidden{display:none!important}\n.app{height:100dvh;display:flex;overflow:hidden}.side{width:285px;background:var(--panel);border-right:1px solid var(--line);padding:16px;display:flex;flex-direction:column;gap:12px}\n.brand{font-weight:800;font-size:20px}.sub{color:var(--muted);font-size:12px}.search{width:100%;border:1px solid var(--line);padding:10px;border-radius:10px}\n.files{overflow:auto;flex:1}.row{display:flex;align-items:center;gap:8px;padding:10px;border-radius:10px;margin:2px 0}.row:hover{background:#f1f5f9}.row.active{background:#eaf2ff}.name{overflow:hidden;text-overflow:ellipsis;white-space:nowrap;flex:1}\n.main{flex:1;display:flex;flex-direction:column;min-width:0}.top{height:64px;background:var(--panel);border-bottom:1px solid var(--line);display:flex;align-items:center;gap:8px;padding:10px 14px}.title{flex:1;min-width:0;border:1px solid transparent;font-weight:700;padding:8px;border-radius:9px}.title:focus{border-color:var(--line);outline:none}.editor{flex:1;display:flex;min-height:0;padding:14px;gap:14px}.card{background:var(--panel);border:1px solid var(--line);border-radius:14px;overflow:hidden;flex:1;display:flex;flex-direction:column}.bar{padding:9px 12px;border-bottom:1px solid var(--line);display:flex;gap:8px;align-items:center}.status{color:var(--muted);font-size:12px;margin-left:auto}.area{width:100%;height:100%;resize:none;border:0;outline:0;padding:18px;font-family:ui-monospace,SFMono-Regular,Consolas,monospace;font-size:15px;line-height:1.65}.drop{position:fixed;inset:0;background:#3b82f622;display:flex;align-items:center;justify-content:center;font-size:24px;font-weight:800;z-index:9}.uploadbox{position:fixed;right:18px;bottom:18px;width:min(390px,calc(100vw - 36px));background:var(--panel);border:1px solid var(--line);border-radius:16px;box-shadow:0 15px 50px #0002;padding:14px;z-index:10}.task{padding:10px 0;border-bottom:1px solid var(--line)}progress{width:100%;height:8px}.login{position:fixed;inset:0;background:var(--bg);display:flex;align-items:center;justify-content:center}.loginbox{width:min(390px,92vw);background:var(--panel);padding:28px;border-radius:18px;border:1px solid var(--line)}.loginbox input{width:100%;padding:12px;border:1px solid var(--line);border-radius:10px;margin:12px 0}\n@media(max-width:720px){.side{position:fixed;z-index:8;inset:0 auto 0 0;width:min(88vw,320px);transform:translateX(-102%);transition:.2s}.side.open{transform:none}.top{height:58px}.editor{padding:8px}.bar{padding:8px}.area{padding:14px;font-size:14px}.desktopOnly{display:none}}\n</style></head>\n<body>\n<div id=\"login\" class=\"login\"><div class=\"loginbox\"><div class=\"brand\">☁ Cloud Notepad V6</div><div class=\"sub\">安全登录 · 断点续传 · Windows / Android</div><input id=\"pw\" type=\"password\" placeholder=\"密码\"><button class=\"primary\" style=\"width:100%\" onclick=\"login()\">登录</button><p id=\"le\" class=\"danger\"></p></div></div>\n<div id=\"app\" class=\"app hidden\">\n<aside id=\"side\" class=\"side\"><div class=\"brand\">☁ Cloud Notepad</div><div class=\"sub\">V6 · Resumable Upload</div><button class=\"primary\" onclick=\"newText()\">＋ 新建文本</button><button onclick=\"pickFiles()\">上传文件</button><button onclick=\"showTrash()\">回收站</button><button onclick=\"showStorage()\">空间</button><input id=\"search\" class=\"search\" placeholder=\"搜索文件\" oninput=\"renderFiles()\"><div id=\"files\" class=\"files\"></div><div class=\"sub\" id=\"storage\"></div><button onclick=\"batchDelete()\">批量删除</button><button onclick=\"zipSelected()\">ZIP下载</button><button onclick=\"toggleTasks()\">上传任务</button></aside>\n<main class=\"main\"><div class=\"top\"><button onclick=\"toggleSide()\">☰</button><input id=\"title\" class=\"title\" placeholder=\"选择文件\" oninput=\"dirty=true\"><button onclick=\"save()\">保存</button><button onclick=\"pickFiles()\">上传</button><button onclick=\"logout()\">退出</button></div>\n<div class=\"editor\"><section class=\"card\"><div class=\"bar\"><span id=\"meta\">没有打开文件</span><span id=\"status\" class=\"status\"></span></div><textarea id=\"area\" class=\"area\" spellcheck=\"false\" placeholder=\"选择一个文本文件，或新建文本\"></textarea><div id=\"preview\" style=\"display:none;flex:1;overflow:auto;padding:16px\"></div></section></div></main>\n</div>\n<input id=\"filePicker\" type=\"file\" multiple hidden onchange=\"handleFiles(this.files)\">\n<div id=\"drop\" class=\"drop hidden\">松开鼠标/手指以上传文件</div>\n<div id=\"uploadbox\" class=\"uploadbox hidden\"><b>上传任务</b><div id=\"tasks\"></div></div>\n<script>\nlet token=localStorage.getItem(\"cnv6_token\")||\"\", files=[], current=null, dirty=false, tasks=[], timer=null, selected=new Set();\nconst $=id=>document.getElementById(id);\nasync function api(path,opt={}){opt.headers={...(opt.headers||{}),\"x-notepad-auth\":token};let r=await fetch(path,opt);let j=await r.json().catch(()=>({}));if(!r.ok)throw Error(j.error||\"请求失败\");return j}\nasync function login(){try{let j=await fetch(\"/api/login\",{method:\"POST\",headers:{\"content-type\":\"application/json\"},body:JSON.stringify({password:$(\"pw\").value})}).then(r=>r.json());if(!j.token)throw Error(j.error||\"登录失败\");token=j.token;localStorage.setItem(\"cnv6_token\",token);showApp();}catch(e){$(\"le\").textContent=e.message}}\nfunction showApp(){$(\"login\").classList.add(\"hidden\");$(\"app\").classList.remove(\"hidden\");load()}\nfunction logout(){localStorage.removeItem(\"cnv6_token\");location.reload()}\nasync function load(){try{await loadFiles();await loadTasks()}catch(e){logout()}}\nasync function loadFiles(){let j=await api(\"/api/files\");files=j.files||[];renderFiles();showStorage()}\nfunction renderFiles(){\n let q=$(\"search\").value.trim().toLowerCase();\n $(\"files\").innerHTML=files.filter(f=>f.name.toLowerCase().includes(q)).map(f=>`<div class=\"row ${current?.id===f.id?\"active\":\"\"}\" onclick=\"openFile('${f.id}')\"><input type=\"checkbox\" onclick=\"event.stopPropagation();toggleSel('${f.id}')\" ${selected.has(f.id)?\"checked\":\"\"}> ${icon(f)} <span class=\"name\">${esc(f.name)}</span><span>${fmt(f.size)}</span></div>`).join(\"\");\n}\nfunction toggleSel(id){selected.has(id)?selected.delete(id):selected.add(id)}\nfunction icon(f){if(/^image\\//.test(f.mime))return\"🖼️\";if(f.mime===\"application/pdf\")return\"📕\";if(/^video\\//.test(f.mime))return\"🎬\";if(/^audio\\//.test(f.mime))return\"🎵\";if(f.name.toLowerCase().endsWith(\".md\"))return\"📝\";return\"📄\"}\nfunction newText(){current=null;dirty=true;$(\"title\").value=\"未命名.txt\";$(\"area\").value=\"\";$(\"meta\").textContent=\"新文件\";$(\"area\").focus()}\nlet saveTimer=null;\n$(\"area\").addEventListener(\"input\",()=>{dirty=true;clearTimeout(saveTimer);saveTimer=setTimeout(()=>{if(current)save()},1200)});\nasync function save(){if(!dirty)return;if(!current){let j=await api(\"/api/file\",{method:\"POST\",headers:{\"content-type\":\"application/json\"},body:JSON.stringify({name:$(\"title\").value||\"未命名.txt\",content:$(\"area\").value,mime:\"text/plain\"})});await loadFiles();await openFile(j.id);return}await api(\"/api/file\",{method:\"PUT\",headers:{\"content-type\":\"application/json\"},body:JSON.stringify({id:current.id,name:$(\"title\").value||current.name,content:$(\"area\").value})});dirty=false;$(\"status\").textContent=\"已自动保存\";await loadFiles()}\nfunction pickFiles(){$(\"filePicker\").click()}\nasync function handleFiles(list){for(const f of list)await startUpload(f)}\nasync function startUpload(f){let j=await api(\"/api/upload/init\",{method:\"POST\",headers:{\"content-type\":\"application/json\"},body:JSON.stringify({name:f.name,size:f.size,mime:f.type||\"application/octet-stream\"})});let t={...j,file:f,done:0,status:\"active\"};tasks.unshift(t);renderTasks();\nfor(let p=1;p<=j.total_parts;p++){let s=await api(\"/api/upload/status?id=\"+j.upload_id);let exists=(s.parts||[]).find(x=>x.part_number===p);if(exists){t.done+=exists.size;renderTasks();continue}\nlet start=(p-1)*j.part_size,end=Math.min(f.size,start+j.part_size),buf=await f.slice(start,end).arrayBuffer();let r=await api(`/api/upload/part?id=${j.upload_id}&part=${p}`,{method:\"PUT\",body:buf});t.done+=buf.byteLength;renderTasks()}\nawait api(\"/api/upload/complete\",{method:\"POST\",headers:{\"content-type\":\"application/json\"},body:JSON.stringify({id:j.upload_id})});t.status=\"completed\";renderTasks();await loadFiles()}\nasync function loadTasks(){try{let j=await api(\"/api/upload/tasks\");tasks=(j.uploads||[]).map(x=>({id:x.id,file:{name:x.file_name,size:x.size},done:0,status:x.status}));renderTasks()}catch{}}\nfunction toggleTasks(){$(\"uploadbox\").classList.toggle(\"hidden\");renderTasks()}\nfunction renderTasks(){$(\"tasks\").innerHTML=tasks.slice(0,8).map(t=>`<div class=\"task\"><div>${esc(t.file.name)} <small>${fmt(t.done)} / ${fmt(t.file.size)}</small></div><progress max=\"${t.file.size}\" value=\"${t.done}\"></progress><div class=\"sub\">${t.status===\"completed\"?\"完成\":\"上传中\"} · ${t.file.size?Math.floor(t.done/t.file.size*100):0}%</div></div>`).join(\"\")}\n\nasync function showTrash(){let j=await api(\"/api/trash\");files=j.files||[];$(\"files\").innerHTML=files.map(f=>`<div class=\"row\">🗑️ <span class=\"name\">${esc(f.name)}</span><button onclick=\"restoreFile('${f.id}')\">恢复</button><button onclick=\"purgeFile('${f.id}')\">永久删除</button></div>`).join(\"\")}\nasync function restoreFile(id){await api(\"/api/trash/restore\",{method:\"POST\",headers:{\"content-type\":\"application/json\"},body:JSON.stringify({id})});await loadFiles()}\nasync function purgeFile(id){if(confirm(\"永久删除后无法恢复，确定？\")){await api(\"/api/trash/purge\",{method:\"POST\",headers:{\"content-type\":\"application/json\"},body:JSON.stringify({id}));showTrash()}}\nasync function batchDelete(){let ids=[...selected];if(!ids.length)return alert(\"请先勾选文件\");if(confirm(`确定删除 ${ids.length} 个文件？`)){await api(\"/api/batch/delete\",{method:\"POST\",headers:{\"content-type\":\"application/json\"},body:JSON.stringify({ids})});selected.clear();await loadFiles()}}\nasync function showStorage(){try{let j=await api(\"/api/storage\");$(\"storage\").textContent=`已用 ${fmt(j.used)} / ${fmt(j.appLimit)} · ${j.count} 个文件 · 回收站 ${fmt(j.trash)}`}catch{}}\nfunction md(t){return esc(t).replace(/^### (.*)$/gm,\"<h3>$1</h3>\").replace(/^## (.*)$/gm,\"<h2>$1</h2>\").replace(/^# (.*)$/gm,\"<h1>$1</h1>\").replace(/\\*\\*(.*?)\\*\\*/g,\"<b>$1</b>\").replace(/`([^`]+)`/g,\"<code>$1</code>\").replace(/\\n/g,\"<br>\")}\nfunction crc32(bytes){let c=~0;for(let n of bytes){c^=n;for(let k=0;k<8;k++)c=(c>>>1)^((c&1)?0xEDB88320:0)}return(~c)>>>0}\nfunction u16(n){return new Uint8Array([n&255,n>>>8&255])}\nfunction u32(n){return new Uint8Array([n&255,n>>>8&255,n>>>16&255,n>>>24&255])}\nasync function zipSelected(){\n let ids=[...selected];if(!ids.length)return alert(\"请先勾选文件\");\n let chunks=[],central=[],offset=0;\n for(const id of ids){\n  const f=files.find(x=>x.id===id);if(!f)continue;\n  const data=new Uint8Array(await fetch(\"/api/file/content?id=\"+encodeURIComponent(id),{headers:{\"x-notepad-auth\":token}}).then(r=>r.arrayBuffer()));\n  const name=new TextEncoder().encode(f.name), crc=crc32(data);\n  let local=new Uint8Array(30+name.length+data.length);\n  local.set([80,75,3,4,20,0,0,0,0,0,0,0,0,0],0);local.set(u32(crc),14);local.set(u32(data.length),18);local.set(u32(data.length),22);local.set(u16(name.length),26);local.set(u16(0),28);local.set(name,30);local.set(data,30+name.length);\n  chunks.push(local);\n  let c=new Uint8Array(46+name.length);c.set([80,75,1,2,20,0,20,0,0,0,0,0,0,0],0);c.set(u32(crc),16);c.set(u32(data.length),20);c.set(u32(data.length),24);c.set(u16(name.length),28);c.set(u16(0),30);c.set(u16(0),32);c.set(u16(0),34);c.set(u16(0),36);c.set(u32(0),38);c.set(u32(offset),42);c.set(name,46);central.push(c);offset+=local.length;\n }\n let centralSize=central.reduce((a,b)=>a+b.length,0), end=new Uint8Array(22);end.set([80,75,5,6,0,0,0,0],0);end.set(u16(central.length),8);end.set(u16(central.length),10);end.set(u32(centralSize),12);end.set(u32(offset),16);\n let blob=new Blob([...chunks,...central,end],{type:\"application/zip\"}),a=document.createElement(\"a\");a.href=URL.createObjectURL(blob);a.download=\"cloud-notepad-files.zip\";a.click();setTimeout(()=>URL.revokeObjectURL(a.href),2000);\n}\n\nfunction toggleSide(){$(\"side\").classList.toggle(\"open\")}\nfunction esc(s){return String(s).replace(/[&<>\"']/g,c=>({\"&\":\"&amp;\",\"<\":\"&lt;\",\">\":\"&gt;\",'\"':\"&quot;\",\"'\":\"&#39;\"}[c]))}\nfunction fmt(n){n=Number(n||0);if(n<1024)return n+\" B\";if(n<1048576)return (n/1024).toFixed(1)+\" KB\";if(n<1073741824)return (n/1048576).toFixed(1)+\" MB\";return (n/1073741824).toFixed(2)+\" GB\"}\ndocument.addEventListener(\"keydown\",e=>{if((e.ctrlKey||e.metaKey)&&e.key.toLowerCase()===\"s\"){e.preventDefault();save()}});\naddEventListener(\"beforeunload\",e=>{if(dirty){e.preventDefault();e.returnValue=\"\"}});\n[\"dragenter\",\"dragover\"].forEach(x=>addEventListener(x,e=>{e.preventDefault();$(\"drop\").classList.remove(\"hidden\")}));\n[\"dragleave\",\"drop\"].forEach(x=>addEventListener(x,e=>{e.preventDefault();if(x===\"drop\")handleFiles(e.dataTransfer.files);$(\"drop\").classList.add(\"hidden\")}));\nif(token)showApp();if(\"serviceWorker\" in navigator)navigator.serviceWorker.register(\"/sw.js\").catch(()=>{});\n</script></body></html>";
const encoder = new TextEncoder();
const decoder = new TextDecoder();

function json(data, status=200, extra={}) {
  return new Response(JSON.stringify(data), {
    status,
    headers: {"content-type":"application/json; charset=utf-8", ...extra}
  });
}
function now(){ return Date.now(); }
function id(){ return crypto.randomUUID(); }
async function sha256(s){
  const b=await crypto.subtle.digest("SHA-256", encoder.encode(s));
  return [...new Uint8Array(b)].map(x=>x.toString(16).padStart(2,"0")).join("");
}
function b64u(a){
  return btoa(String.fromCharCode(...new Uint8Array(a))).replaceAll("+","-").replaceAll("/","_").replaceAll("=","");
}
function randomToken(){ return b64u(crypto.getRandomValues(new Uint8Array(32))); }
async function pbkdf2(password, salt, iterations=210000){
  const key=await crypto.subtle.importKey("raw",encoder.encode(password),"PBKDF2",false,["deriveBits"]);
  const bits=await crypto.subtle.deriveBits({name:"PBKDF2",salt,iterations,hash:"SHA-256"},key,256);
  return b64u(bits);
}
function hexToBytes(hex){
  const a=new Uint8Array(hex.length/2);
  for(let i=0;i<a.length;i++) a[i]=parseInt(hex.slice(i*2,i*2+2),16);
  return a;
}
async function requireAuth(req,env){
  const t=req.headers.get("x-notepad-auth");
  if(!t) throw new Error("UNAUTHORIZED");
  const h=await sha256(t);
  const s=await env.DB.prepare("SELECT * FROM sessions WHERE token_hash=? AND expires_at>?").bind(h,now()).first();
  if(!s) throw new Error("UNAUTHORIZED");
  return s;
}
const SCHEMA_SQL="CREATE TABLE IF NOT EXISTS settings (\n  key TEXT PRIMARY KEY,\n  value TEXT NOT NULL\n);\nCREATE TABLE IF NOT EXISTS sessions (\n  token_hash TEXT PRIMARY KEY,\n  expires_at INTEGER NOT NULL\n);\nCREATE TABLE IF NOT EXISTS folders (\n  id TEXT PRIMARY KEY,\n  name TEXT NOT NULL,\n  parent_id TEXT,\n  created_at INTEGER NOT NULL\n);\nCREATE TABLE IF NOT EXISTS files (\n  id TEXT PRIMARY KEY,\n  name TEXT NOT NULL,\n  folder_id TEXT,\n  object_key TEXT NOT NULL,\n  size INTEGER NOT NULL DEFAULT 0,\n  mime TEXT NOT NULL DEFAULT 'application/octet-stream',\n  deleted INTEGER NOT NULL DEFAULT 0,\n  created_at INTEGER NOT NULL,\n  updated_at INTEGER NOT NULL\n);\nCREATE TABLE IF NOT EXISTS versions (\n  id TEXT PRIMARY KEY,\n  file_id TEXT NOT NULL,\n  object_key TEXT NOT NULL,\n  size INTEGER NOT NULL,\n  created_at INTEGER NOT NULL\n);\nCREATE TABLE IF NOT EXISTS shares (\n  token TEXT PRIMARY KEY,\n  file_id TEXT NOT NULL,\n  expires_at INTEGER,\n  created_at INTEGER NOT NULL\n);\nCREATE TABLE IF NOT EXISTS uploads (\n  id TEXT PRIMARY KEY,\n  file_id TEXT,\n  file_name TEXT NOT NULL,\n  folder_id TEXT,\n  object_key TEXT NOT NULL,\n  upload_id TEXT NOT NULL,\n  size INTEGER NOT NULL,\n  mime TEXT NOT NULL,\n  part_size INTEGER NOT NULL,\n  total_parts INTEGER NOT NULL,\n  completed_parts INTEGER NOT NULL DEFAULT 0,\n  status TEXT NOT NULL DEFAULT 'active',\n  created_at INTEGER NOT NULL,\n  updated_at INTEGER NOT NULL\n);\nCREATE TABLE IF NOT EXISTS upload_parts (\n  upload_id TEXT NOT NULL,\n  part_number INTEGER NOT NULL,\n  etag TEXT NOT NULL,\n  size INTEGER NOT NULL,\n  PRIMARY KEY(upload_id, part_number)\n);";
let schemaReady=false;
async function ensureSchema(env){
  if(schemaReady)return;
  await env.DB.exec(SCHEMA_SQL);
  schemaReady=true;
}
async function init(env){
  const s=await env.DB.prepare("SELECT value FROM settings WHERE key='password_salt'").first();
  if(!s){
    const salt=crypto.getRandomValues(new Uint8Array(16));
    // Initial password is 268. Only a derived PBKDF2 value is persisted.
    const hash=await pbkdf2("268",salt);
    await env.DB.batch([
      env.DB.prepare("INSERT INTO settings(key,value) VALUES('password_salt',?)").bind(b64u(salt)),
      env.DB.prepare("INSERT INTO settings(key,value) VALUES('password_hash',?)").bind(hash)
    ]);
  }
}
function partSizeFor(size){
  // 8 MiB is a good mobile/desktop compromise; larger files use 16 MiB.
  return size >= 1024*1024*1024 ? 16*1024*1024 : 8*1024*1024;
}
async function route(req,env){
  await init(env);
  const u=new URL(req.url), p=u.pathname, method=req.method;
  if(p==="/api/login" && method==="POST"){
    const {password}=await req.json();
    const saltRow=await env.DB.prepare("SELECT value FROM settings WHERE key='password_salt'").first();
    const hashRow=await env.DB.prepare("SELECT value FROM settings WHERE key='password_hash'").first();
    const hash=await pbkdf2(String(password||""), atob(saltRow.value.replaceAll("-","+").replaceAll("_","/")+"==").split("").map(c=>c.charCodeAt(0)),210000);
    // The salt decode above is deliberately replaced below by a stable byte conversion.
    const saltString=atob(saltRow.value.replaceAll("-","+").replaceAll("_","/")+"==");
    const salt=new Uint8Array([...saltString].map(c=>c.charCodeAt(0)));
    const real=await pbkdf2(String(password||""),salt);
    if(real!==hashRow.value) return json({error:"密码错误"},401);
    const token=randomToken(), th=await sha256(token);
    await env.DB.prepare("INSERT INTO sessions(token_hash,expires_at) VALUES(?,?)").bind(th,now()+30*86400000).run();
    return json({token});
  }
  if(p==="/api/share" && method==="GET"){
    const token=u.searchParams.get("token");
    const s=await env.DB.prepare("SELECT f.*, sh.expires_at FROM shares sh JOIN files f ON f.id=sh.file_id WHERE sh.token=? AND f.deleted=0").bind(token||"").first();
    if(!s || (s.expires_at && s.expires_at<now())) return json({error:"分享不存在或已过期"},404);
    const obj=await env.STORAGE.get(s.object_key);
    if(!obj) return json({error:"文件不存在"},404);
    return new Response(obj.body,{headers:{"content-type":s.mime||"application/octet-stream","content-disposition":`inline; filename="${encodeURIComponent(s.name)}"`}});
  }
  try{ await requireAuth(req,env); }catch(e){ return json({error:"请先登录"},401); }

  if(p==="/api/files" && method==="GET"){
    const folder=u.searchParams.get("folder")||null;
    const r=folder
      ? await env.DB.prepare("SELECT * FROM files WHERE deleted=0 AND folder_id=? ORDER BY updated_at DESC").bind(folder).all()
      : await env.DB.prepare("SELECT * FROM files WHERE deleted=0 AND folder_id IS NULL ORDER BY updated_at DESC").all();
    return json({files:r.results});
  }
  if(p==="/api/folders" && method==="GET"){
    const r=await env.DB.prepare("SELECT * FROM folders ORDER BY name").all(); return json({folders:r.results});
  }
  if(p==="/api/folder" && method==="POST"){
    const b=await req.json(); const fid=id();
    await env.DB.prepare("INSERT INTO folders(id,name,parent_id,created_at) VALUES(?,?,?,?)").bind(fid,String(b.name||"新文件夹"),b.parent_id||null,now()).run();
    return json({id:fid});
  }
  if(p==="/api/file" && method==="POST"){
    const b=await req.json(), fid=id(), key=`files/${fid}/${String(b.name||"未命名.txt").replace(/[^\w.\-\u4e00-\u9fff ]/g,"_")}`;
    await env.STORAGE.put(key,String(b.content||""),{httpMetadata:{contentType:b.mime||"text/plain; charset=utf-8"}});
    await env.DB.prepare("INSERT INTO files(id,name,folder_id,object_key,size,mime,created_at,updated_at) VALUES(?,?,?,?,?,?,?,?)")
      .bind(fid,b.name||"未命名.txt",b.folder_id||null,key,new TextEncoder().encode(String(b.content||"")).byteLength,b.mime||"text/plain",now(),now()).run();
    return json({id:fid});
  }
  if(p==="/api/file" && method==="PUT"){
    const b=await req.json();
    const f=await env.DB.prepare("SELECT * FROM files WHERE id=? AND deleted=0").bind(b.id).first();
    if(!f) return json({error:"文件不存在"},404);
    const content=String(b.content??"");
    const vkey=`versions/${f.id}/${now()}-${id()}`;
    const old=await env.STORAGE.get(f.object_key);
    if(old) await env.STORAGE.put(vkey,old.body, {httpMetadata:{contentType:f.mime}});
    if(old) await env.DB.prepare("INSERT INTO versions(id,file_id,object_key,size,created_at) VALUES(?,?,?,?,?)").bind(id(),f.id,vkey,f.size,now()).run();
    await env.STORAGE.put(f.object_key,content,{httpMetadata:{contentType:f.mime}});
    const sz=encoder.encode(content).byteLength;
    await env.DB.prepare("UPDATE files SET name=?,size=?,updated_at=? WHERE id=?").bind(b.name||f.name,sz,now(),f.id).run();
    return json({ok:true});
  }

  if(p==="/api/trash" && method==="GET"){
    const r=await env.DB.prepare("SELECT * FROM files WHERE deleted=1 ORDER BY updated_at DESC").all();
    return json({files:r.results});
  }
  if(p==="/api/trash/restore" && method==="POST"){
    const b=await req.json();
    await env.DB.prepare("UPDATE files SET deleted=0,updated_at=? WHERE id=?").bind(now(),b.id).run();
    return json({ok:true});
  }
  if(p==="/api/trash/purge" && method==="POST"){
    const b=await req.json();
    const f=await env.DB.prepare("SELECT * FROM files WHERE id=? AND deleted=1").bind(b.id).first();
    if(f){try{await env.STORAGE.delete(f.object_key)}catch{} await env.DB.prepare("DELETE FROM files WHERE id=?").bind(b.id).run();}
    return json({ok:true});
  }
  if(p==="/api/batch/delete" && method==="POST"){
    const b=await req.json();
    for(const fid of (b.ids||[])) await env.DB.prepare("UPDATE files SET deleted=1,updated_at=? WHERE id=?").bind(now(),fid).run();
    return json({ok:true});
  }
  if(p==="/api/storage" && method==="GET"){
    const a=await env.DB.prepare("SELECT COALESCE(SUM(size),0) n,COUNT(*) c FROM files WHERE deleted=0").first();
    const t=await env.DB.prepare("SELECT COALESCE(SUM(size),0) n,COUNT(*) c FROM files WHERE deleted=1").first();
    return json({used:Number(a.n||0),count:Number(a.c||0),trash:Number(t.n||0),trashCount:Number(t.c||0),appLimit:5*1024*1024*1024});
  }
  if(p==="/api/file" && method==="DELETE"){
    const b=await req.json(); await env.DB.prepare("UPDATE files SET deleted=1,updated_at=? WHERE id=?").bind(now(),b.id).run(); return json({ok:true});
  }
  if(p==="/api/file/content" && method==="GET"){
    const f=await env.DB.prepare("SELECT * FROM files WHERE id=? AND deleted=0").bind(u.searchParams.get("id")).first();
    if(!f) return json({error:"不存在"},404);
    const obj=await env.STORAGE.get(f.object_key); if(!obj) return json({error:"对象不存在"},404);
    return new Response(obj.body,{headers:{"content-type":f.mime||"application/octet-stream"}});
  }
  if(p==="/api/share" && method==="POST"){
    const b=await req.json(), token=randomToken().slice(0,32);
    await env.DB.prepare("INSERT INTO shares(token,file_id,expires_at,created_at) VALUES(?,?,?,?)").bind(token,b.id,b.expires_at||null,now()).run();
    return json({token});
  }

  // ---- V6 resumable multipart upload ----
  if(p==="/api/upload/init" && method==="POST"){
    const b=await req.json();
    const size=Number(b.size||0);
    if(!Number.isFinite(size)||size<=0) return json({error:"文件大小无效"},400);
    const ps=partSizeFor(size), total=Math.ceil(size/ps), uid=id();
    const fid=id(), key=`files/${fid}/${String(b.name||"upload.bin").replace(/[^\w.\-\u4e00-\u9fff ]/g,"_")}`;
    const mp=await env.STORAGE.createMultipartUpload(key,{httpMetadata:{contentType:b.mime||"application/octet-stream"}});
    await env.DB.prepare(`INSERT INTO uploads(id,file_id,file_name,folder_id,object_key,upload_id,size,mime,part_size,total_parts,created_at,updated_at)
      VALUES(?,?,?,?,?,?,?,?,?,?,?,?)`).bind(uid,fid,b.name||"upload.bin",b.folder_id||null,key,mp.uploadId,size,b.mime||"application/octet-stream",ps,total,now(),now()).run();
    return json({upload_id:uid,file_id:fid,part_size:ps,total_parts:total});
  }
  if(p==="/api/upload/status" && method==="GET"){
    const x=await env.DB.prepare("SELECT * FROM uploads WHERE id=?").bind(u.searchParams.get("id")).first();
    if(!x) return json({error:"上传任务不存在"},404);
    const r=await env.DB.prepare("SELECT part_number,size,etag FROM upload_parts WHERE upload_id=? ORDER BY part_number").bind(x.id).all();
    return json({upload:x,parts:r.results});
  }
  if(p==="/api/upload/part" && method==="PUT"){
    const uid=u.searchParams.get("id"), pn=Number(u.searchParams.get("part"));
    const x=await env.DB.prepare("SELECT * FROM uploads WHERE id=? AND status='active'").bind(uid).first();
    if(!x) return json({error:"上传任务不存在"},404);
    if(!pn || pn>x.total_parts) return json({error:"分片编号无效"},400);
    const bytes=await req.arrayBuffer();
    const part=await env.STORAGE.resumeMultipartUpload(x.object_key,x.upload_id).uploadPart(pn,bytes);
    await env.DB.prepare("INSERT OR REPLACE INTO upload_parts(upload_id,part_number,etag,size) VALUES(?,?,?,?)").bind(uid,pn,part.etag,bytes.byteLength).run();
    const c=await env.DB.prepare("SELECT COUNT(*) n FROM upload_parts WHERE upload_id=?").bind(uid).first();
    await env.DB.prepare("UPDATE uploads SET completed_parts=?,updated_at=? WHERE id=?").bind(c.n,now(),uid).run();
    return json({ok:true,part:pn,etag:part.etag,completed:Number(c.n),total:x.total_parts});
  }
  if(p==="/api/upload/complete" && method==="POST"){
    const b=await req.json(), x=await env.DB.prepare("SELECT * FROM uploads WHERE id=? AND status='active'").bind(b.id).first();
    if(!x) return json({error:"上传任务不存在"},404);
    const parts=(await env.DB.prepare("SELECT part_number,etag FROM upload_parts WHERE upload_id=? ORDER BY part_number").bind(x.id).all()).results;
    if(parts.length!==x.total_parts) return json({error:"还有分片未上传",completed:parts.length,total:x.total_parts},409);
    await env.STORAGE.resumeMultipartUpload(x.object_key,x.upload_id).complete(parts.map(p=>({partNumber:p.part_number,etag:p.etag})));
    await env.DB.prepare("INSERT INTO files(id,name,folder_id,object_key,size,mime,created_at,updated_at) VALUES(?,?,?,?,?,?,?,?)")
      .bind(x.file_id,x.file_name,x.folder_id,x.object_key,x.size,x.mime,now(),now()).run();
    await env.DB.prepare("UPDATE uploads SET status='completed',updated_at=? WHERE id=?").bind(now(),x.id).run();
    return json({ok:true,file_id:x.file_id});
  }
  if(p==="/api/upload/cancel" && method==="POST"){
    const b=await req.json(), x=await env.DB.prepare("SELECT * FROM uploads WHERE id=? AND status='active'").bind(b.id).first();
    if(x){ try{await env.STORAGE.resumeMultipartUpload(x.object_key,x.upload_id).abort()}catch{} await env.DB.prepare("UPDATE uploads SET status='cancelled',updated_at=? WHERE id=?").bind(now(),x.id).run(); }
    return json({ok:true});
  }
  if(p==="/api/upload/tasks" && method==="GET"){
    const r=await env.DB.prepare("SELECT * FROM uploads WHERE status='active' ORDER BY updated_at DESC").all(); return json({uploads:r.results});
  }
  return json({error:"Not found"},404);
}

const MANIFEST = `{"name":"Cloud Notepad","short_name":"Notepad","start_url":"/","display":"standalone","background_color":"#f5f7fb","theme_color":"#3b82f6","icons":[]}`;
const SW = `self.addEventListener("install",e=>self.skipWaiting());self.addEventListener("activate",e=>e.waitUntil(self.clients.claim()));`;
export default {
 async fetch(req,env,ctx){
   const path = new URL(req.url).pathname;
   if(path === "/") return new Response(INDEX_HTML,{headers:{"content-type":"text/html; charset=utf-8"}});
   if(path === "/manifest.webmanifest") return new Response(MANIFEST,{headers:{"content-type":"application/manifest+json"}});
   if(path === "/sw.js") return new Response(SW,{headers:{"content-type":"application/javascript","cache-control":"no-cache"}});
   try{return await route(req,env)}catch(e){
     if(String(e.message)==="UNAUTHORIZED") return json({error:"请先登录"},401);
     return json({error:"服务器错误",detail:String(e.message)},500);
   }
 }
};
