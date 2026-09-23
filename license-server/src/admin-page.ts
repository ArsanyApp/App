/**
 * Owner admin page served by the Worker at /admin. It contains no secret: the owner types the
 * ADMIN_TOKEN, which is kept only in this browser tab (sessionStorage) and sent as a Bearer header.
 * All values are rendered with textContent (no HTML injection).
 */
export const ADMIN_HTML = `<!doctype html>
<html lang="en">
<head>
<meta charset="utf-8">
<meta name="viewport" content="width=device-width, initial-scale=1">
<meta name="robots" content="noindex">
<title>Choice Auto Tap · Licenses</title>
<style>
  :root { --bg:#f6f7f9; --card:#fff; --text:#1c1f24; --muted:#5f6670; --line:#dfe3e8; --accent:#1b5e20; --danger:#b3261e; }
  @media (prefers-color-scheme: dark) { :root { --bg:#121417; --card:#1c1f24; --text:#e8eaed; --muted:#9aa0a6; --line:#30353b; --accent:#81c784; --danger:#f28b82; } }
  * { box-sizing: border-box; }
  body { margin:0; font:15px/1.45 system-ui, sans-serif; background:var(--bg); color:var(--text); }
  main { max-width: 1000px; margin: 0 auto; padding: 16px; }
  h1 { font-size: 20px; margin: 8px 0 16px; }
  section { background:var(--card); border:1px solid var(--line); border-radius:12px; padding:16px; margin-bottom:16px; }
  label { display:block; font-size:13px; color:var(--muted); margin-bottom:4px; }
  input, select, button { font:inherit; padding:8px 10px; border-radius:8px; border:1px solid var(--line); background:var(--card); color:var(--text); }
  button { cursor:pointer; background:var(--accent); color:#fff; border-color:var(--accent); }
  button.secondary { background:transparent; color:var(--text); border-color:var(--line); }
  button.danger { background:transparent; color:var(--danger); border-color:var(--danger); }
  .row { display:flex; gap:8px; flex-wrap:wrap; align-items:flex-end; }
  .grow { flex:1; min-width: 180px; }
  table { width:100%; border-collapse:collapse; font-size:14px; }
  th, td { text-align:left; padding:8px 6px; border-bottom:1px solid var(--line); vertical-align:middle; }
  th { color:var(--muted); font-weight:600; }
  code { font: 14px ui-monospace, monospace; }
  .pill { display:inline-block; padding:2px 8px; border-radius:999px; font-size:12px; font-weight:600; border:1px solid var(--line); }
  .UNUSED { color:#1565c0; } .ACTIVE { color:var(--accent); } .REVOKED { color:var(--danger); }
  #msg { min-height: 20px; color: var(--muted); }
  #created code { display:block; font-size:16px; margin:4px 0; }
  .scroll { overflow-x:auto; }
  .hidden { display:none; }
</style>
</head>
<body>
<main>
  <h1>Choice Auto Tap · License admin</h1>
  <section id="login">
    <div class="row">
      <div class="grow"><label for="token">Admin token</label><input id="token" type="password" autocomplete="current-password" style="width:100%"></div>
      <button id="loginBtn">Sign in</button>
    </div>
  </section>
  <div id="app" class="hidden">
    <section>
      <div class="row">
        <div><label for="count">How many codes</label><input id="count" type="number" min="1" max="100" value="1" style="width:100px"></div>
        <div class="grow"><label for="note">Note (optional, e.g. customer name)</label><input id="note" maxlength="200" style="width:100%"></div>
        <button id="genBtn">Generate</button>
        <button id="logoutBtn" class="secondary">Sign out</button>
      </div>
      <div id="created"></div>
    </section>
    <section>
      <div class="row" style="margin-bottom:8px">
        <div><label for="filter">Show</label>
          <select id="filter"><option value="">All</option><option>UNUSED</option><option>ACTIVE</option><option>REVOKED</option></select></div>
        <button id="refreshBtn" class="secondary">Refresh</button>
        <span id="counts" style="color:var(--muted)"></span>
      </div>
      <div class="scroll"><table>
        <thead><tr><th>Code</th><th>Status</th><th>Note</th><th>Created</th><th>Activated</th><th>Last seen</th><th></th></tr></thead>
        <tbody id="rows"></tbody>
      </table></div>
    </section>
  </div>
  <p id="msg" role="status"></p>
</main>
<script src="/admin.js"></script>
</body>
</html>`;

export const ADMIN_JS = `(() => {
  const $ = (id) => document.getElementById(id);
  const KEY = "cat-admin-token";
  let token = sessionStorage.getItem(KEY) || "";

  function msg(text) { $("msg").textContent = text || ""; }
  function fmt(ms) { return ms ? new Date(ms).toLocaleString() : "—"; }
  function el(tag, text, cls) { const e = document.createElement(tag); if (text !== undefined) e.textContent = text; if (cls) e.className = cls; return e; }

  async function api(method, path, body) {
    const res = await fetch(path, {
      method,
      headers: { "authorization": "Bearer " + token, ...(body ? { "content-type": "application/json" } : {}) },
      body: body ? JSON.stringify(body) : undefined,
    });
    const data = await res.json().catch(() => ({}));
    if (res.status === 401) { signOut(); throw new Error("Wrong admin token."); }
    if (res.status === 429) throw new Error("Too many attempts. Try again in " + (data.retryAfterSeconds || 60) + " s.");
    if (!res.ok) throw new Error(data.error || ("HTTP " + res.status));
    return data;
  }

  async function copy(text, btn) {
    try { await navigator.clipboard.writeText(text); btn.textContent = "Copied"; setTimeout(() => btn.textContent = "Copy", 1200); }
    catch { window.prompt("Copy the code:", text); }
  }

  function codeCell(code) {
    const td = el("td");
    td.appendChild(el("code", code || "?"));
    if (code) { const b = el("button", "Copy", "secondary"); b.style.marginLeft = "6px"; b.onclick = () => copy(code, b); td.appendChild(b); }
    return td;
  }

  async function load() {
    try {
      const f = $("filter").value;
      const data = await api("GET", "/api/admin/licenses" + (f ? "?status=" + f : ""));
      const rows = $("rows"); rows.replaceChildren();
      const counts = { UNUSED: 0, ACTIVE: 0, REVOKED: 0 };
      for (const l of data.licenses) {
        counts[l.status]++;
        const tr = el("tr");
        tr.appendChild(codeCell(l.activationCode));
        const st = el("td"); st.appendChild(el("span", l.status, "pill " + l.status)); tr.appendChild(st);
        tr.appendChild(el("td", l.note || ""));
        tr.appendChild(el("td", fmt(l.createdAt)));
        tr.appendChild(el("td", fmt(l.activatedAt)));
        tr.appendChild(el("td", fmt(l.lastSeenAt)));
        const act = el("td");
        if (l.status !== "REVOKED") {
          const b = el("button", "Revoke", "danger");
          b.onclick = async () => {
            if (!confirm("Revoke " + (l.activationCode || l.codeHint) + "? The app stops working at its next license check.")) return;
            try { await api("POST", "/api/license/revoke", { id: l.id }); msg("Revoked."); load(); } catch (e) { msg(e.message); }
          };
          act.appendChild(b);
        }
        tr.appendChild(act);
        rows.appendChild(tr);
      }
      $("counts").textContent = data.licenses.length + " shown · " + counts.UNUSED + " unused · " + counts.ACTIVE + " active · " + counts.REVOKED + " revoked";
      msg("");
    } catch (e) { msg(e.message); }
  }

  async function generate() {
    const btn = $("genBtn"); btn.disabled = true;
    try {
      const data = await api("POST", "/api/admin/licenses", { count: Number($("count").value || 1), note: $("note").value || undefined });
      const box = $("created"); box.replaceChildren(el("p", "New codes (also listed below):"));
      for (const l of data.licenses) {
        const line = el("div"); line.appendChild(el("code", l.activationCode));
        const b = el("button", "Copy", "secondary"); b.onclick = () => copy(l.activationCode, b); line.appendChild(b);
        box.appendChild(line);
      }
      load();
    } catch (e) { msg(e.message); } finally { btn.disabled = false; }
  }

  function signIn() {
    token = $("token").value.trim();
    if (!token) return;
    sessionStorage.setItem(KEY, token);
    $("token").value = "";
    show();
  }
  function signOut() { token = ""; sessionStorage.removeItem(KEY); $("app").classList.add("hidden"); $("login").classList.remove("hidden"); }
  function show() { $("login").classList.add("hidden"); $("app").classList.remove("hidden"); load(); }

  $("loginBtn").onclick = signIn;
  $("token").addEventListener("keydown", (e) => { if (e.key === "Enter") signIn(); });
  $("genBtn").onclick = generate;
  $("refreshBtn").onclick = load;
  $("filter").onchange = load;
  $("logoutBtn").onclick = signOut;
  if (token) show();
})();`;
