const [tab] = await chrome.tabs.query({
  active: true,
  currentWindow: true,
});
const HOST = `docker.lan:8090`;
let pathname = tab.url
  .slice(8)
  .slice(
    tab.url.slice(8).indexOf("/"),
    tab.url.includes("?") ? tab.url.slice(8).indexOf("?") : undefined
  );
// document.getElementById("aa").innerHTML = `${pathname}`;

function showToast(message, type = "success") {
  // Create toast element
  const toast = document.createElement("div");
  toast.textContent = message;
  toast.style.cssText = `
        padding: 12px 20px;
        border-radius: 4px;
        background-color: ${type === "error" ? "#d32f2f" : "#4caf50"};
        color: white;
        font-family: Arial, sans-serif;
        font-size: 14px;
        z-index: 9999;
        box-shadow: 0 2px 10px rgba(0,0,0,0.2);
        animation: toast-slide-in 0.3s ease-out;
    `;

  // Add toast to document
  document.getElementById("popup").appendChild(toast);

  // Remove toast after 3 seconds
  setTimeout(() => {
    toast.style.animation = "toast-slide-out 0.3s ease-out forwards";
    setTimeout(() => {
      if (toast.parentNode) {
        toast.parentNode.removeChild(toast);
      }
    }, 300);
  }, 1000);
}

(function () {
  "use strict";
  if (pathname.split("/").length != 2) return;

  let status = document.createElement("div");
  status.classList = "block";
  status.innerText = "...";
  status.style.cssText = `
        padding: 8px 10px;
        border-radius: 9px;
        background-color: ${false ? "#d32f2f" : "#4caf50"};
        color: white;
        font-family: Arial, sans-serif;
        font-size: 14px;
        z-index: 9999;
        box-shadow: 0 2px 10px rgba(0,0,0,0.2);
        background-color: ${false ? "#d32f2f" : "#4caf50"};
    `;
  async function refresh() {
    let slug = pathname.slice(1);
    let resp = await fetch(`http://${HOST}/list`);
    let list = await resp.json();
    let info = list.filter((l) => l[3] == slug)[0];
    if (!info) {
      status.innerHTML = "...";
      return;
    }
    console.log(info);
    status.style.backgroundColor = `${
      info[2] == "recording" ? "#d32f2f" : "#4caf50"
    }`;
    if (info.join(", ") != status.innerHTML) status.innerHTML = info.join(", ");
    return;
  }
  refresh().catch((err) => {
    console.error(err);
  });
  setInterval(() => {
    refresh().catch((err) => {
      console.error(err);
    });
  }, 1000);
  let item = document.createElement("button");
  item.innerHTML = `
    Send (Not Activate)
    `;
  let item2 = document.createElement("button");
  item2.innerHTML = `
    Send (Activate)
    `;
  item.classList = "block";
  item2.classList = "block";
  item.style.margin = 0;
  item2.style.margin = 0;
  item.addEventListener("click", async () => {
    let slug = pathname.slice(1);
    item.disabled = true;
    item2.disabled = true;
    try {
      let resp = await fetch(
        `http://${input}/add?slug=${encodeURIComponent(
            slug
        )}&active=false`
      );
      let msg = await resp.text();
      // 显示一个Tost
      showToast(msg);
    } finally {
      item2.disabled = false;
      item.disabled = false;
    }
  });
  item2.addEventListener("click", async () => {
    let slug = pathname.slice(1);
    item.disabled = true;
    item2.disabled = true;
    try {
      let resp = await fetch(
        `http://${HOST}/add?slug=${encodeURIComponent(
          slug
        )}&active=true`
      );
      let msg = await resp.text();
      // 显示一个Tost
      showToast(msg);
    } finally {
      item2.disabled = false;
      item.disabled = false;
    }
  });

  let remove = document.createElement("button");
  remove.classList = "block";
  remove.style.margin = 0;
  remove.innerHTML = `Remove`;
  remove.addEventListener("click", async () => {
    let slug = pathname.slice(1);
    let resp = await fetch(
      `http://${HOST}/remove?slug=${encodeURIComponent(slug)}`
    );
    let msg = await resp.text();
    // 显示一个Tost
    showToast(msg);
  });

  let activate = document.createElement("button");
  activate.classList = "block";
  activate.style.margin = 0;
  activate.innerHTML = `Activate`;
  activate.addEventListener("click", async () => {
    let slug = pathname.slice(1);
    let resp = await fetch(
      `http://${HOST}/activate?slug=${encodeURIComponent(slug)}`
    );
    let msg = await resp.text();
    // 显示一个Tost
    showToast(msg);
  });
  let deactivate = document.createElement("button");
  deactivate.classList = "block";
  deactivate.style.margin = 0;
  deactivate.innerHTML = `Deactivate`;
  deactivate.addEventListener("click", async () => {
    let slug = pathname.slice(1);
    let resp = await fetch(
      `http://${HOST}/deactivate?slug=${encodeURIComponent(slug)}`
    );
    let msg = await resp.text();
    // 显示一个Tost
    showToast(msg);
  });
  let setRaw = document.createElement("button");
  setRaw.classList = "block";
  setRaw.style.margin = 0;
  setRaw.innerHTML = `Q:raw`;
  setRaw.addEventListener("click", async () => {
    let slug = pathname.slice(1);
    let resp = await fetch(
      `http://${HOST}/quality?slug=${encodeURIComponent(
        slug
      )}&quality=raw`
    );
    let msg = await resp.text();
    try {
      showToast(`${JSON.parse(msg).name} => ${JSON.parse(msg).quality}`);
    } catch (error) {
      showToast(msg);
    }
  });
  let set720 = document.createElement("button");
  set720.classList = "block";
  set720.style.margin = 0;
  set720.innerHTML = `Q:720p`;
  set720.addEventListener("click", async () => {
    let slug = pathname.slice(1);
    let resp = await fetch(
      `http://${HOST}/quality?slug=${encodeURIComponent(
        slug
      )}&quality=720p`
    );
    let msg = await resp.text();
    try {
      showToast(`${JSON.parse(msg).name} => ${JSON.parse(msg).quality}`);
    } catch (error) {
      showToast(msg);
    }
  });
  let set720p60 = document.createElement("button");
  set720p60.classList = "block";
  set720p60.style.margin = 0;
  set720p60.innerHTML = `Q:720p60`;
  set720p60.addEventListener("click", async () => {
    let slug = pathname.slice(1);
    let resp = await fetch(
      `http://${HOST}/quality?slug=${encodeURIComponent(
        slug
      )}&quality=720p60`
    );
    let msg = await resp.text();
    try {
      showToast(`${JSON.parse(msg).name} => ${JSON.parse(msg).quality}`);
    } catch (error) {
      showToast(msg);
    }
  });
  let set1080p60 = document.createElement("button");
  set1080p60.classList = "block";
  set1080p60.style.margin = 0;
  set1080p60.innerHTML = `Q:1080p60`;
  set1080p60.addEventListener("click", async () => {
    let slug = pathname.slice(1);
    let resp = await fetch(
      `http://${HOST}/quality?slug=${encodeURIComponent(
        slug
      )}&quality=1080p60`
    );
    let msg = await resp.text();
    try {
      showToast(`${JSON.parse(msg).name} => ${JSON.parse(msg).quality}`);
    } catch (error) {
      showToast(msg);
    }
  });
  let set480 = document.createElement("button");
  set480.classList = "block";
  set480.style.margin = 0;
  set480.innerHTML = `Q:480p`;
  set480.addEventListener("click", async () => {
    let slug = pathname.slice(1);
    let resp = await fetch(
      `http://${HOST}/quality?slug=${encodeURIComponent(
        slug
      )}&quality=480p`
    );
    let msg = await resp.text();
    try {
      showToast(`${JSON.parse(msg).name} => ${JSON.parse(msg).quality}`);
    } catch (error) {
      showToast(msg);
    }
  });
  let set240 = document.createElement("button");
  set240.classList = "block";
  set240.style.margin = 0;
  set240.innerHTML = `Q:240p`;
  set240.addEventListener("click", async () => {
    let slug = pathname.slice(1);
    let resp = await fetch(
      `http://${HOST}/quality?slug=${encodeURIComponent(
        slug
      )}&quality=240p`
    );
    let msg = await resp.text();
    try {
      showToast(`${JSON.parse(msg).name} => ${JSON.parse(msg).quality}`);
    } catch (error) {
      showToast(msg);
    }
  });
  let set160 = document.createElement("button");
  set160.classList = "block";
  set160.style.margin = 0;
  set160.innerHTML = `Q:160p`;
  set160.addEventListener("click", async () => {
    let slug = pathname.slice(1);
    let resp = await fetch(
      `http://${HOST}/quality?slug=${encodeURIComponent(
        slug
      )}&quality=160p`
    );
    let msg = await resp.text();
    try {
      showToast(`${JSON.parse(msg).name} => ${JSON.parse(msg).quality}`);
    } catch (error) {
      showToast(msg);
    }
  });

  let container = document.createElement("div");
  // container.style.position = "absolute"
  // container.style.left = "10px"
  // container.style.top = "50%"
  // container.style.background = "white"
  // container.style.padding = "8px"
  container.style.display = "flex";
  container.style.flexDirection = "column";
  container.style.gap = "4px";
  container.style.justifyContent = "center";
  container.style.alignItems = "center";
  container.style.zIndex = "3";
  // container.style.transform = "translateX(0%) translateY(-50%)"
  // container.style.pointerEvents = "none"

  let containerRowSend = document.createElement("div");
  containerRowSend.style.display = "flex";
  containerRowSend.style.gap = "4px";
  containerRowSend.style.justifyContent = "center";
  containerRowSend.style.alignItems = "center";
  let containerRow = document.createElement("div");
  containerRow.style.display = "flex";
  containerRow.style.gap = "4px";
  containerRow.style.justifyContent = "center";
  containerRow.style.alignItems = "center";
  let containerRow2 = document.createElement("div");
  containerRow2.style.display = "flex";
  containerRow2.style.gap = "4px";
  containerRow2.style.justifyContent = "center";
  containerRow2.style.alignItems = "center";
  let containerRow3 = document.createElement("div");
  containerRow3.style.display = "flex";
  containerRow3.style.gap = "4px";
  containerRow3.style.justifyContent = "center";
  containerRow3.style.alignItems = "center";

  container.append(status);
  container.append(containerRowSend);
  container.append(containerRow);
  container.append(containerRow2);
  container.append(containerRow3);
  containerRowSend.append(item);
  containerRowSend.append(item2);
  containerRow.append(remove);
  containerRow.append(activate);
  containerRow.append(deactivate);
  containerRow2.append(setRaw);
  containerRow2.append(set1080p60);
  containerRow2.append(set720p60);
  containerRow2.append(set720);
  containerRow3.append(set480);
  containerRow3.append(set240);
  containerRow3.append(set160);

  document.getElementById("main").append(container);
  // Your code here...
})();
