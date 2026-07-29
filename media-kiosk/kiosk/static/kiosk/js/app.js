function getCookie(name) {
  return document.cookie.split(";").map((v) => v.trim()).find((v) => v.startsWith(name + "="))?.split("=").slice(1).join("=") || "";
}

async function jsonPost(url, data) {
  const response = await fetch(url, {
    method: "POST",
    headers: {"Content-Type": "application/json", "X-CSRFToken": decodeURIComponent(getCookie("csrftoken"))},
    body: JSON.stringify(data),
  });
  const body = await response.json().catch(() => ({error: "Răspuns invalid de la server."}));
  if (!response.ok) throw new Error(body.error || "Acțiunea nu a reușit.");
  return body;
}

const uploadForm = document.querySelector("#upload-form");
if (uploadForm) {
  const fileInput = document.querySelector("#upload-file");
  const summary = document.querySelector("#file-summary");
  const errorBox = document.querySelector("#upload-error");
  const progress = document.querySelector("#upload-progress");
  const bar = progress.querySelector(".progress-bar");
  const drop = document.querySelector(".upload-drop");
  const showFile = () => {
    const file = fileInput.files[0];
    if (!file) return;
    summary.textContent = `${file.name} · ${(file.size / 1024 / 1024).toFixed(2)} MB`;
    summary.classList.remove("d-none");
    if (!document.querySelector("#upload-title").value) document.querySelector("#upload-title").value = file.name.replace(/\.[^.]+$/, "");
  };
  fileInput.addEventListener("change", showFile);
  ["dragenter", "dragover"].forEach((event) => drop.addEventListener(event, (e) => { e.preventDefault(); drop.classList.add("is-dragging"); }));
  ["dragleave", "drop"].forEach((event) => drop.addEventListener(event, (e) => { e.preventDefault(); drop.classList.remove("is-dragging"); }));
  drop.addEventListener("drop", (e) => { if (e.dataTransfer.files.length) { fileInput.files = e.dataTransfer.files; showFile(); } });
  uploadForm.addEventListener("submit", async (event) => {
    event.preventDefault();
    const file = fileInput.files[0];
    if (!file) return;
    const button = uploadForm.querySelector("button[type=submit]");
    button.disabled = true; errorBox.classList.add("d-none"); progress.classList.remove("d-none");
    try {
      const presigned = await jsonPost(uploadForm.dataset.presignUrl, {filename: file.name, mime_type: file.type, file_size: file.size, title: document.querySelector("#upload-title").value});
      await new Promise((resolve, reject) => {
        const xhr = new XMLHttpRequest(); xhr.open("PUT", presigned.upload_url); xhr.setRequestHeader("Content-Type", file.type);
        xhr.upload.onprogress = (e) => { if (e.lengthComputable) { const pct = Math.round(e.loaded / e.total * 100); bar.style.width = pct + "%"; bar.textContent = pct + "%"; } };
        xhr.onload = () => xhr.status >= 200 && xhr.status < 300 ? resolve() : reject(new Error("R2 a refuzat fișierul.")); xhr.onerror = () => reject(new Error("Conexiunea către R2 a eșuat.")); xhr.send(file);
      });
      const confirmed = await jsonPost(uploadForm.dataset.confirmUrl, {upload_token: presigned.upload_token});
      window.location.assign(confirmed.redirect_url);
    } catch (error) { errorBox.textContent = error.message; errorBox.classList.remove("d-none"); button.disabled = false; }
  });
}

const itemList = document.querySelector("#playlist-items");
if (itemList && window.Sortable) {
  const feedback = document.querySelector("#editor-feedback");
  const notify = (text, bad = false) => { feedback.textContent = text; feedback.className = `small mt-3 ${bad ? "text-danger" : "text-success"}`; };
  const renumber = () => itemList.querySelectorAll(".position-number").forEach((node, i) => node.textContent = i + 1);
  new Sortable(itemList, {animation: 180, handle: ".drag-handle", ghostClass: "drag-ghost", onEnd: async () => { renumber(); try { await jsonPost(itemList.dataset.reorderUrl, {item_ids: [...itemList.querySelectorAll(".playlist-item")].map((n) => Number(n.dataset.id))}); notify("Ordinea draftului a fost salvată."); } catch (e) { notify(e.message, true); } }});
  itemList.addEventListener("change", async (event) => {
    const row = event.target.closest(".playlist-item"); if (!row) return;
    try { await jsonPost(row.dataset.updateUrl, {is_active: row.querySelector(".js-active").checked, image_duration_seconds: row.querySelector(".js-duration")?.value}); notify("Element actualizat în draft."); } catch (e) { notify(e.message, true); }
  });
  itemList.addEventListener("click", async (event) => {
    const button = event.target.closest(".js-remove"); if (!button) return;
    const row = button.closest(".playlist-item"); if (!confirm("Elimini acest material din draft?")) return;
    try { await jsonPost(row.dataset.deleteUrl, {}); row.remove(); renumber(); notify("Element eliminat din draft."); } catch (e) { notify(e.message, true); }
  });
  const assetList = document.querySelector("#asset-list");
  assetList?.addEventListener("click", async (event) => { const option = event.target.closest(".asset-option"); if (!option) return; option.disabled = true; try { await jsonPost(assetList.dataset.addUrl, {media_asset_id: option.dataset.id}); window.location.reload(); } catch (e) { notify(e.message, true); option.disabled = false; } });
  document.querySelector("#asset-search")?.addEventListener("input", (event) => assetList.querySelectorAll(".asset-option").forEach((option) => option.hidden = !option.dataset.title.includes(event.target.value.toLowerCase())));
}

const previewStage = document.querySelector("#preview-stage");
if (previewStage) {
  const items = JSON.parse(document.querySelector("#preview-data").textContent || "[]");
  const caption = document.querySelector("#preview-caption"); let current = 0; let timer;
  const playNext = () => {
    clearTimeout(timer); previewStage.querySelectorAll("img,video").forEach((node) => node.remove());
    if (!items.length) return;
    const item = items[current]; current = (current + 1) % items.length; caption.textContent = item.title;
    const node = document.createElement(item.type === "video" ? "video" : "img"); node.src = item.url; node.alt = item.title;
    if (item.type === "video") { node.autoplay = true; node.muted = false; node.playsInline = true; node.addEventListener("ended", playNext); node.addEventListener("error", () => timer = setTimeout(playNext, 2000)); }
    else { timer = setTimeout(playNext, Math.max(1, item.duration) * 1000); }
    previewStage.prepend(node);
  };
  playNext();
  document.querySelector("#fullscreen-button")?.addEventListener("click", () => previewStage.requestFullscreen?.());
}

