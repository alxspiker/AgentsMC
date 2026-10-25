import { App } from "@modelcontextprotocol/ext-apps";

const $ = (id) => document.getElementById(id);

const app = new App({ name: "AgentsMC status", version: "0.6.31" });

function setBar(el, value, max) {
  const pct = Math.max(0, Math.min(100, (Number(value) / max) * 100));
  el.classList.toggle("warn", pct < 40);
  el.classList.toggle("bad", pct < 20);
  el.firstElementChild.style.width = pct + "%";
}

function items(list) {
  return Array.isArray(list) ? list : [];
}

function render(data) {
  if (!data || typeof data !== "object") return;
  $("name").textContent = data.name || "Agent";
  $("meta").textContent = [data.agentId, data.version, data.spawned ? "online" : "offline"]
    .filter(Boolean).join(" · ");
  $("health").textContent = data.health == null ? "—" : String(data.health);
  $("food").textContent = data.food == null ? "—" : String(data.food);
  setBar($("healthBar"), data.health ?? 0, 20);
  setBar($("foodBar"), data.food ?? 0, 20);
  $("pos").textContent = [data.x, data.y, data.z].every((n) => n != null)
    ? `${Number(data.x).toFixed(1)}, ${Number(data.y).toFixed(1)}, ${Number(data.z).toFixed(1)}`
    : "—";
  $("world").textContent = data.world || "—";
  $("dimension").textContent = data.dimension || "—";
  $("spawned").textContent = data.spawned ? "yes" : "no";
  $("action").textContent = data.action || "idle";
  $("nav").textContent = [data.navStatus, data.navReason].filter(Boolean).join(" — ") || "idle";
  $("invLabel").textContent = data.inventoryLabel || "empty";
  $("inv").replaceChildren(...items(data.inventory).slice(0, 12).map((item) => {
    const li = document.createElement("li");
    li.textContent = `${item.amount || 1}× ${item.material || item.name || "item"}`;
    return li;
  }));
  $("events").replaceChildren(...items(data.events).slice(0, 8).map((ev) => {
    const li = document.createElement("li");
    li.textContent = ev.summary || ev.type || "event";
    return li;
  }));
  const select = $("player");
  const current = select.value;
  select.replaceChildren();
  for (const p of items(data.players)) {
    const opt = document.createElement("option");
    opt.value = p.name;
    opt.textContent = p.d != null ? `${p.name} (${p.d}m)` : p.name;
    if (p.x != null) opt.dataset.x = String(p.x);
    if (p.y != null) opt.dataset.y = String(p.y);
    if (p.z != null) opt.dataset.z = String(p.z);
    select.appendChild(opt);
  }
  if ([...select.options].some((o) => o.value === current)) select.value = current;
}

function structured(result) {
  if (!result) return null;
  if (result.structuredContent && typeof result.structuredContent === "object")
    return result.structuredContent;
  const text = result.content?.find((c) => c.type === "text")?.text;
  if (!text) return null;
  try { return JSON.parse(text); } catch { return null; }
}

async function call(name, args) {
  $("note").textContent = "";
  try {
    const result = await app.callServerTool({ name, arguments: args || {} });
    const data = structured(result);
    if (data) render(data);
    return result;
  } catch (err) {
    $("note").textContent = String(err?.message || err);
    throw err;
  }
}

function selectedPlayer() {
  return $("player").value || "";
}

app.ontoolresult = (result) => {
  const data = structured(result);
  if (data) render(data);
};

$("refresh").addEventListener("click", () => call("agentsmc_panel", {}));
$("stop").addEventListener("click", () => call("agentsmc_execute", { action: "stop" }));
$("follow").addEventListener("click", () => {
  const player = selectedPlayer();
  if (!player) { $("note").textContent = "No nearby player to follow"; return; }
  return call("agentsmc_execute", { action: "follow", player });
});
$("come").addEventListener("click", () => {
  const player = selectedPlayer();
  const opt = [...$("player").options].find((o) => o.value === player);
  if (!player) { $("note").textContent = "No nearby player to walk to"; return; }
  if (opt?.dataset.x != null && opt?.dataset.y != null && opt?.dataset.z != null) {
    return call("agentsmc_execute", {
      action: "goto",
      x: Number(opt.dataset.x),
      y: Number(opt.dataset.y),
      z: Number(opt.dataset.z),
      dig: false,
    });
  }
  return call("agentsmc_execute", { action: "follow", player });
});

app.connect().catch((err) => {
  $("note").textContent = "Host connect failed: " + (err?.message || err);
});
