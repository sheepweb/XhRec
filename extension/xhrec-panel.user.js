// ==UserScript==
// @name         XhRec Control Panel
// @namespace    https://github.com/RikaCelery/XhRec
// @version      1.0
// @description  Inject recording control panel directly into the page
// @author       RikaCelery
// @match        *://*/*
// @grant        none
// @run-at       document-end
// ==/UserScript==

(function () {
  "use strict";

  const HOST = "localhost:8090";
  const PATH = window.location.pathname;
  const SLUG = PATH.split("/")[1];
  if (!SLUG || PATH.split("/").length !== 2) return;

  // ── style ─────────────────────────────────────────────────────────
  document.head.insertAdjacentHTML("beforeend", `
    <style>
      #xhrec-toggle {
        position: fixed; bottom: 20px; right: 20px; z-index: 2147483648;
        width: 38px; height: 38px; border-radius: 50%; border: none;
        background: #02ac4f; color: #fff; font-size: 18px; cursor: pointer;
        box-shadow: 0 2px 12px rgba(0,0,0,.35); line-height: 1;
      }
      #xhrec-panel {
        position: fixed; bottom: 68px; right: 20px; z-index: 2147483647;
        background: #1a1a2e; border-radius: 12px; padding: 12px;
        font-family: -apple-system, BlinkMacSystemFont, "Segoe UI", Roboto, sans-serif;
        font-size: 12px; color: #eee; box-shadow: 0 4px 24px rgba(0,0,0,.55);
        display: flex; flex-direction: column; gap: 6px; min-width: 290px;
        transition: transform .25s, opacity .25s;
      }
      #xhrec-panel.xhrec-hidden { transform: translateY(20px); opacity: 0; pointer-events: none; }
      #xhrec-panel .xhrec-status {
        padding: 7px 10px; border-radius: 7px; font-weight: 600; text-align: center;
        background: #4caf50; font-size: 11px; transition: background .3s;
        word-break: break-all;
      }
      #xhrec-panel .xhrec-status.recording { background: #d32f2f; }
      #xhrec-panel .xhrec-row { display: flex; gap: 4px; }
      #xhrec-panel button {
        flex: 1; padding: 6px 2px; border: none; border-radius: 5px;
        background: #02ac4f; color: #fff; font-size: 11px; cursor: pointer;
        white-space: nowrap; transition: background .15s;
      }
      #xhrec-panel button:hover { filter: brightness(1.2); }
      #xhrec-panel button:disabled { background: #555; cursor: not-allowed; filter: none; }
      #xhrec-panel .xhrec-toast {
        padding: 5px 10px; border-radius: 5px; color: #fff; font-size: 11px;
        text-align: center; word-break: break-all;
        animation: xhrec-fade-in .2s ease-out;
      }
      @keyframes xhrec-fade-in { from { opacity: 0; transform: translateY(-4px); } to { opacity: 1; transform: translateY(0); } }
      @keyframes xhrec-fade-out { from { opacity: 1; } to { opacity: 0; } }
    </style>
  `);

  // ── cached state (from polling) ────────────────────────────────────
  let roomId = null;
  let quality = "";

  // ── build panel ───────────────────────────────────────────────────
  const toggleBtn = document.createElement("button");
  toggleBtn.id = "xhrec-toggle";
  toggleBtn.textContent = "⏺";
  toggleBtn.title = "XhRec — " + SLUG;

  const panel = document.createElement("div");
  panel.id = "xhrec-panel";

  // ── toast (injected at top of panel) ──────────────────────────────
  let toastTimer;
  function toast(msg, error) {
    clearTimeout(toastTimer);
    const old = panel.querySelector(".xhrec-toast");
    if (old) old.remove();
    const el = document.createElement("div");
    el.className = "xhrec-toast";
    el.textContent = msg;
    el.style.background = error ? "#d32f2f" : "#02ac4f";
    panel.insertBefore(el, panel.firstChild);
    toastTimer = setTimeout(() => {
      el.style.animation = "xhrec-fade-out .25s ease-out forwards";
      setTimeout(() => el.remove(), 250);
    }, 2000);
  }

  // ── api helper ────────────────────────────────────────────────────
  async function api(path, query) {
    const qs = query ? "?" + new URLSearchParams(query).toString() : "";
    const resp = await fetch("http://" + HOST + path + qs);
    if (!resp.ok) throw new Error(resp.status + " " + resp.statusText);
    return resp;
  }

  // ── formatting ────────────────────────────────────────────────────
  function fmtBytes(n) {
    if (n == null) return "0 B";
    if (n >= 1e9) return (n / 1e9).toFixed(1) + " GB";
    if (n >= 1e6) return (n / 1e6).toFixed(1) + " MB";
    if (n >= 1e3) return (n / 1e3).toFixed(1) + " KB";
    return n + " B";
  }

  // ── action helpers ────────────────────────────────────────────────
  async function actRemove() {
    if (roomId == null) { toast("Room not tracked", true); return; }
    const r = await api("/remove", { id: roomId });
    toast(await r.text());
  }
  async function actActivate() {
    if (roomId == null) { toast("Room not tracked", true); return; }
    const r = await api("/activate", { id: roomId });
    toast(await r.text());
  }
  async function actDeactivate() {
    if (roomId == null) { toast("Room not tracked", true); return; }
    const r = await api("/deactivate", { id: roomId });
    toast(await r.text());
  }
  async function actQuality(q) {
    if (roomId == null) { toast("Room not tracked", true); return; }
    const r = await api("/quality", { id: roomId, q });
    toast(await r.text());
  }

  // ── panel content ─────────────────────────────────────────────────
  const statusEl = document.createElement("div");
  statusEl.className = "xhrec-status";
  statusEl.textContent = "...";
  panel.appendChild(statusEl);

  function mkRow(labels, handlers) {
    const r = document.createElement("div");
    r.className = "xhrec-row";
    labels.forEach((l, i) => {
      const b = document.createElement("button");
      b.textContent = l;
      b.addEventListener("click", handlers[i]);
      r.appendChild(b);
    });
    return r;
  }

  function withLock(btns, fn) {
    return async () => {
      btns.forEach(b => b.disabled = true);
      try { await fn(); } finally { btns.forEach(b => b.disabled = false); }
    };
  }

  const sendBtns = [];
  const sendInactive = document.createElement("button");
  sendInactive.textContent = "Send (inactive)";
  sendBtns.push(sendInactive);
  sendInactive.addEventListener("click", withLock(sendBtns, async () => {
    const r = await api("/add", { name: SLUG, active: "false" });
    toast(await r.text());
  }));

  const sendActive = document.createElement("button");
  sendActive.textContent = "Send (active)";
  sendBtns.push(sendActive);
  sendActive.addEventListener("click", withLock(sendBtns, async () => {
    const r = await api("/add", { name: SLUG, active: "true" });
    toast(await r.text());
  }));

  panel.appendChild(mkRow(["Send (inactive)", "Send (active)"], [
    () => sendInactive.click(), () => sendActive.click(),
  ]));

  panel.appendChild(mkRow(["Remove", "Activate", "Deactivate"], [
    actRemove, actActivate, actDeactivate,
  ]));

  panel.appendChild(mkRow(["Q:raw", "Q:1080p60", "Q:720p60", "Q:720p"], [
    () => actQuality("raw"), () => actQuality("1080p60"),
    () => actQuality("720p60"), () => actQuality("720p"),
  ]));
  panel.appendChild(mkRow(["Q:540p","Q:480p", "Q:240p", "Q:160p"], [
    () => actQuality("540p"), () => actQuality("480p"), () => actQuality("240p"), () => actQuality("160p"),
  ]));

  document.body.appendChild(toggleBtn);
  document.body.appendChild(panel);

  // ── status polling ────────────────────────────────────────────────
  // /status for live metrics (only active rooms), /list for room id + fallback
  async function refreshStatus() {
    try {
      const sResp = await fetch("http://" + HOST + "/status");
      const statuses = await sResp.json();
      const data = statuses[SLUG]; // keyed by room name

      if (data) {
        // room is actively recording/downloading
        const running = data.running ? Object.keys(data.running).length : 0;
        const succ = data.success || 0;
        const total = data.total || 0;
        const bytes = fmtBytes(data.bytesWrite);
        let text = "⏺ REC " + bytes;
        if (quality) text += " [" + quality + "]";
        if (total > 0) text += " | " + succ + "/" + total + " seg";
        if (running > 0) text += " | " + running + "⇣";
        statusEl.textContent = text;
        statusEl.classList.add("recording");
        toggleBtn.style.background = "#d32f2f";
      } else {
        // not active — fall back to /list for basic tracking status
        statusEl.classList.remove("recording");
        toggleBtn.style.background = "#02ac4f";
        try {
          const lResp = await fetch("http://" + HOST + "/list");
          const list = await lResp.json();
          const info = list.find(l => l[3] === SLUG);
          if (info) {
            roomId = info[4];
            quality = info[5] || "";
            const listening = info[1] === "listening";
            const qtext = quality ? " [" + quality + "]" : "";
            statusEl.textContent = listening ? "◉ Armed — " + info[0] + qtext : info.join(" · ");
          } else {
            roomId = null;
            statusEl.textContent = SLUG + " — not tracked";
          }
        } catch (_) {
          statusEl.textContent = "offline";
        }
      }
    } catch (e) {
      statusEl.textContent = "offline";
      statusEl.classList.remove("recording");
      toggleBtn.style.background = "#555";
    }
  }

  // also refresh room id + quality from /list every 5s
  async function refreshRoomId() {
    try {
      const resp = await fetch("http://" + HOST + "/list");
      const list = await resp.json();
      const info = list.find(l => l[3] === SLUG);
      if (info) {
        roomId = info[4];
        quality = info[5] || "";
      } else {
        roomId = null;
        quality = "";
      }
    } catch (_) {}
  }

  refreshStatus();
  refreshRoomId();
  setInterval(refreshStatus, 1000);
  setInterval(refreshRoomId, 5000);

  // ── toggle visibility ─────────────────────────────────────────────
  let visible = true;
  toggleBtn.addEventListener("click", () => {
    visible = !visible;
    panel.classList.toggle("xhrec-hidden", !visible);
    toggleBtn.textContent = visible ? "✕" : "⏺";
  });
  document.addEventListener("keydown", e => {
    if (e.key === "Escape" && visible) toggleBtn.click();
  });

})();
