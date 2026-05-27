const [tab] = await chrome.tabs.query({ active: true, currentWindow: true });

const HOST = "docker.lan:8090";
let pathname = tab.url
  .slice(8)
  .slice(
    tab.url.slice(8).indexOf("/"),
    tab.url.includes("?") ? tab.url.slice(8).indexOf("?") : undefined
  );

(function () {
  "use strict";
  const slug = pathname.slice(1);
  if (pathname.split("/").length !== 2) return;

  // ── cached state ──────────────────────────────────────────────────
  let roomId = null;
  let quality = "";

  // ── toast ─────────────────────────────────────────────────────────
  let toastTimer;
  function showToast(message, error) {
    clearTimeout(toastTimer);
    const old = document.querySelector("#popup div");
    if (old) old.remove();
    const toast = document.createElement("div");
    toast.textContent = message;
    toast.style.cssText = `
      padding: 8px 16px; border-radius: 5px;
      background-color: ${error ? "#d32f2f" : "#02ac4f"};
      color: white; font-family: Arial, sans-serif; font-size: 13px;
      z-index: 9999; box-shadow: 0 2px 10px rgba(0,0,0,0.2);
      animation: toast-slide-in 0.3s ease-out;
      text-align: center; word-break: break-all;
    `;
    document.getElementById("popup").appendChild(toast);
    toastTimer = setTimeout(() => {
      toast.style.animation = "toast-slide-out 0.3s ease-out forwards";
      setTimeout(() => { if (toast.parentNode) toast.parentNode.removeChild(toast); }, 300);
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
    if (roomId == null) { showToast("Room not tracked", true); return; }
    const r = await api("/remove", { id: roomId });
    showToast(await r.text());
  }
  async function actActivate() {
    if (roomId == null) { showToast("Room not tracked", true); return; }
    const r = await api("/activate", { id: roomId });
    showToast(await r.text());
  }
  async function actDeactivate() {
    if (roomId == null) { showToast("Room not tracked", true); return; }
    const r = await api("/deactivate", { id: roomId });
    showToast(await r.text());
  }
  async function actQuality(q) {
    if (roomId == null) { showToast("Room not tracked", true); return; }
    const r = await api("/quality", { id: roomId, q });
    showToast(await r.text());
  }

  // ── build UI ──────────────────────────────────────────────────────
  const status = document.createElement("div");
  status.className = "block";
  status.textContent = "...";
  status.style.cssText = `
    padding: 7px 10px; border-radius: 7px; font-weight: 600; text-align: center;
    font-size: 12px; word-break: break-all; transition: background-color .3s;
  `;

  const main = document.getElementById("main");
  main.style.cssText = "display:flex; flex-direction:column; gap:4px; justify-content:center; align-items:center;";
  main.appendChild(status);

  function mkBtn(text, handler) {
    const b = document.createElement("button");
    b.className = "block";
    b.textContent = text;
    b.style.margin = "0";
    b.addEventListener("click", handler);
    return b;
  }

  function mkRow(btns) {
    const r = document.createElement("div");
    r.style.cssText = "display:flex; gap:4px; justify-content:center; align-items:center; width:100%;";
    btns.forEach(b => r.appendChild(b));
    return r;
  }

  function withLock(handler, ...btns) {
    return async () => {
      btns.forEach(b => b.disabled = true);
      try { await handler(); } finally { btns.forEach(b => b.disabled = false); }
    };
  }

  // send buttons
  const sendInactive = mkBtn("Send (inactive)", () => {});
  const sendActive = mkBtn("Send (active)", () => {});
  sendInactive.addEventListener("click", withLock(async () => {
    const r = await api("/add", { name: slug, active: "false" });
    showToast(await r.text());
  }, sendInactive, sendActive));
  sendActive.addEventListener("click", withLock(async () => {
    const r = await api("/add", { name: slug, active: "true" });
    showToast(await r.text());
  }, sendInactive, sendActive));
  main.appendChild(mkRow([sendInactive, sendActive]));

  // manage row
  main.appendChild(mkRow([
    mkBtn("Remove", actRemove),
    mkBtn("Activate", actActivate),
    mkBtn("Deactivate", actDeactivate),
  ]));

  // quality rows
  const qRow1 = ["raw", "1080p60", "720p60", "720p"];
  const qRow2 = ["480p", "240p", "160p"];
  for (const qs of [qRow1, qRow2]) {
    main.appendChild(mkRow(qs.map(q =>
      mkBtn("Q:" + q, () => actQuality(q))
    )));
  }

  // ── status polling ────────────────────────────────────────────────
  async function refreshStatus() {
    try {
      const sResp = await fetch("http://" + HOST + "/status");
      const statuses = await sResp.json();
      const data = statuses[slug];

      if (data) {
        const running = data.running ? Object.keys(data.running).length : 0;
        const succ = data.success || 0;
        const total = data.total || 0;
        const bytes = fmtBytes(data.bytesWrite);
        let text = "⏺ REC " + bytes;
        if (quality) text += " [" + quality + "]";
        if (total > 0) text += " | " + succ + "/" + total + " seg";
        if (running > 0) text += " | " + running + "⇣";
        status.textContent = text;
        status.style.backgroundColor = "#d32f2f";
      } else {
        status.style.backgroundColor = "#4caf50";
        try {
          const lResp = await fetch("http://" + HOST + "/list");
          const list = await lResp.json();
          const info = list.find(l => l[3] === slug);
          if (info) {
            roomId = info[4];
            quality = info[5] || "";
            const listening = info[1] === "listening";
            const qtext = quality ? " [" + quality + "]" : "";
            status.textContent = listening
              ? "◉ Armed — " + info[0] + qtext
              : info.join(" · ");
          } else {
            roomId = null;
            status.textContent = slug + " — not tracked";
          }
        } catch (_) {
          status.textContent = "offline";
        }
      }
    } catch (e) {
      status.textContent = "offline";
      status.style.backgroundColor = "#4caf50";
    }
  }

  async function refreshRoomId() {
    try {
      const resp = await fetch("http://" + HOST + "/list");
      const list = await resp.json();
      const info = list.find(l => l[3] === slug);
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
})();
