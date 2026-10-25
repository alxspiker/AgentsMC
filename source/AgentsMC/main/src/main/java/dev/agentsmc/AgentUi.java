package dev.agentsmc;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;

import net.citizensnpcs.api.npc.NPC;

/**
 * Plugin-owned MCP App status panel. Template HTML is constant so the resource
 * URI stays stable; data is published on first sighting and when it changes
 * (debounced), never on every heartbeat.
 */
final class AgentUi {
    static final int MAX_BYTES = 524_288;
    static final long DEBOUNCE_MS = 3_000;
    static final String TITLE = "Agent status";
    static final String DESCRIPTION = "Live status and controls for this agent";
    static final String RESOURCE = "agent-panel.html";
    private static final Gson JSON = new Gson();
    private static final Object HTML_LOCK = new Object();
    private static String htmlCache;
    private static final Map<String, String> lastFp = new ConcurrentHashMap<>();
    private static final Map<String, Long> lastAt = new ConcurrentHashMap<>();

    static void resetForTest() {
        synchronized (HTML_LOCK) { htmlCache = null; }
        lastFp.clear();
        lastAt.clear();
    }

    static void overrideHtml(String html) {
        synchronized (HTML_LOCK) { htmlCache = html; }
    }

    static String html() {
        synchronized (HTML_LOCK) {
            if (htmlCache != null) return htmlCache;
            htmlCache = loadHtml();
            return htmlCache;
        }
    }

    static JsonObject template() {
        JsonObject t = new JsonObject();
        t.addProperty("title", TITLE);
        t.addProperty("description", DESCRIPTION);
        t.addProperty("html", html());
        t.add("dataSchema", schema());
        JsonObject csp = new JsonObject();
        csp.add("connectDomains", new JsonArray());
        csp.add("resourceDomains", new JsonArray());
        csp.add("frameDomains", new JsonArray());
        t.add("csp", csp);
        return t;
    }

    static JsonObject schema() {
        JsonObject s = new JsonObject();
        s.addProperty("type", "object");
        JsonObject props = new JsonObject();
        props.add("name", type("string"));
        props.add("agentId", type("string"));
        props.add("version", type("string"));
        props.add("spawned", type("boolean"));
        props.add("health", type("number"));
        props.add("food", type("number"));
        props.add("world", type("string"));
        props.add("dimension", type("string"));
        props.add("x", type("number"));
        props.add("y", type("number"));
        props.add("z", type("number"));
        props.add("navStatus", type("string"));
        props.add("navReason", type("string"));
        props.add("action", type("string"));
        props.add("inventoryLabel", type("string"));
        props.add("inventory", arrayObject());
        props.add("events", arrayObject());
        props.add("players", arrayObject());
        props.add("updatedAt", type("number"));
        s.add("properties", props);
        JsonArray req = new JsonArray();
        req.add("name"); req.add("agentId"); req.add("health"); req.add("food");
        s.add("required", req);
        return s;
    }

    static Map<String, Object> panelData(String name, String agentId, String version, boolean spawned,
            double health, int food, String world, String dimension, double x, double y, double z,
            String navStatus, String navReason, String action, List<Map<String, Object>> inventory,
            List<Map<String, Object>> events, List<Map<String, Object>> players) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("name", name == null ? "" : name);
        m.put("agentId", agentId == null ? "" : agentId);
        m.put("version", version == null ? "" : version);
        m.put("spawned", spawned);
        m.put("health", health);
        m.put("food", food);
        m.put("world", world == null ? "" : world);
        m.put("dimension", dimension == null ? "" : dimension);
        m.put("x", round1(x));
        m.put("y", round1(y));
        m.put("z", round1(z));
        m.put("navStatus", navStatus == null || navStatus.isBlank() ? "idle" : navStatus);
        m.put("navReason", navReason == null ? "" : navReason);
        m.put("action", action == null || action.isBlank() ? "idle" : action);
        List<Map<String, Object>> inv = inventory == null ? List.of() : inventory.stream().limit(16).toList();
        m.put("inventory", inv);
        m.put("inventoryLabel", inventoryLabel(inv));
        m.put("events", events == null ? List.of() : events.stream().limit(8).toList());
        m.put("players", players == null ? List.of() : players.stream().limit(6).toList());
        return m;
    }

    static Map<String, Object> snapshot(NPC npc) {
        if (npc == null) return panelData("", "", AgentVersion.current(), false, 0, 0, "", "", 0, 0, 0, "idle", "", "idle", List.of(), List.of(), List.of());
        String id = AgentIds.publicId(npc.data().get("agentsmc-id"));
        boolean up = AgentsMC.bodyUp(npc);
        Location at = up ? npc.getEntity().getLocation() : npc.getStoredLocation();
        String world = at != null && at.getWorld() != null ? at.getWorld().getName() : "";
        String dimension = dimension(at);
        double health = 0; int food = 0;
        List<Map<String, Object>> inv = new ArrayList<>();
        List<Map<String, Object>> players = new ArrayList<>();
        String navStatus = "idle", navReason = "", action = "idle";
        if (up && npc.getEntity() instanceof Player p) {
            health = p.getHealth();
            food = p.getFoodLevel();
            inv.addAll(inventoryOf(p));
            Map<String, Object> nav = AgentNavigation.snapshot(npc);
            navStatus = String.valueOf(nav.getOrDefault("status", "idle"));
            navReason = String.valueOf(nav.getOrDefault("reason", ""));
            Map<String, Object> focus = AgentFocus.view(npc);
            if (focus != null && focus.get("action") != null) action = String.valueOf(focus.get("action"));
            else if (!"idle".equals(navStatus)) action = "goto";
            for (Map<String, Object> who : AgentsMC.who(p, false)) {
                if (!"player".equals(who.get("kind"))) continue;
                Map<String, Object> row = new LinkedHashMap<>();
                row.put("name", who.get("name"));
                if (who.get("d") != null) row.put("d", who.get("d"));
                if (who.get("x") != null) { row.put("x", who.get("x")); row.put("y", who.get("y")); row.put("z", who.get("z")); }
                players.add(row);
                if (players.size() >= 6) break;
            }
        }
        return panelData(npc.getName(), id, AgentVersion.current(), up, health, food, world, dimension,
                at == null ? 0 : at.getX(), at == null ? 0 : at.getY(), at == null ? 0 : at.getZ(),
                navStatus, navReason, action, inv, AgentEvents.recentView(id, 8), players);
    }

    static JsonObject requestOf(Map<String, Object> data) {
        Map<String, Object> copy = new LinkedHashMap<>(data == null ? Map.of() : data);
        copy.put("updatedAt", System.currentTimeMillis());
        JsonObject body = new JsonObject();
        body.add("template", template());
        body.add("data", JSON.toJsonTree(copy));
        return body;
    }

    static int utf8Bytes(JsonObject body) {
        return body == null ? 0 : body.toString().getBytes(StandardCharsets.UTF_8).length;
    }

    static boolean withinLimit(JsonObject body) {
        return utf8Bytes(body) <= MAX_BYTES;
    }

    static String fingerprint(Map<String, Object> data) {
        Map<String, Object> copy = new LinkedHashMap<>(data == null ? Map.of() : data);
        copy.remove("updatedAt");
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(md.digest(JSON.toJson(copy).getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) {
            return Integer.toHexString(JSON.toJson(copy).hashCode());
        }
    }

    static boolean due(String agentId, String fingerprint, long now) {
        if (agentId == null || agentId.isBlank() || fingerprint == null) return false;
        String prev = lastFp.get(agentId);
        if (prev == null) return true;
        if (prev.equals(fingerprint)) return false;
        long last = lastAt.getOrDefault(agentId, 0L);
        return now - last >= DEBOUNCE_MS;
    }

    static boolean markPublished(String agentId, String fingerprint, long now) {
        if (agentId == null || agentId.isBlank()) return false;
        boolean first = !lastFp.containsKey(agentId);
        lastFp.put(agentId, fingerprint);
        lastAt.put(agentId, now);
        return first;
    }

    /** Null when this agent has no data change (or still inside the debounce). */
    static JsonObject takePublish(String agentId, NPC npc) {
        Map<String, Object> data = snapshot(npc);
        String fp = fingerprint(data);
        long now = System.currentTimeMillis();
        if (!due(agentId, fp, now)) return null;
        JsonObject body = requestOf(data);
        if (!withinLimit(body)) {
            data = panelData(String.valueOf(data.get("name")), String.valueOf(data.get("agentId")),
                    String.valueOf(data.get("version")), Boolean.TRUE.equals(data.get("spawned")),
                    num(data.get("health")), (int) num(data.get("food")),
                    String.valueOf(data.get("world")), String.valueOf(data.get("dimension")),
                    num(data.get("x")), num(data.get("y")), num(data.get("z")),
                    String.valueOf(data.get("navStatus")), String.valueOf(data.get("navReason")),
                    String.valueOf(data.get("action")), List.of(), List.of(), List.of());
            body = requestOf(data);
        }
        if (!withinLimit(body)) return null;
        body.addProperty("_fingerprint", fp);
        return body;
    }

    static String takeFingerprint(JsonObject body) {
        if (body == null || !body.has("_fingerprint")) return "";
        String fp = body.get("_fingerprint").getAsString();
        body.remove("_fingerprint");
        return fp;
    }

    static boolean looksSafe(String html) {
        if (html == null || html.isBlank()) return false;
        String lower = html.toLowerCase();
        if (!lower.contains("<!doctype html")) return false;
        if (html.contains("Bearer ") || html.contains("server-token") || html.contains("accessToken")) return false;
        return html.contains("@modelcontextprotocol/ext-apps") || html.contains("AgentsMC status")
                || html.contains("callServerTool") || html.contains("agentsmc_panel");
    }

    private static String loadHtml() {
        ClassLoader loader = AgentUi.class.getClassLoader();
        for (String name : new String[] { RESOURCE, "/" + RESOURCE }) {
            try (InputStream in = loader.getResourceAsStream(name.startsWith("/") ? name.substring(1) : name)) {
                if (in == null) continue;
                String html = new String(in.readAllBytes(), StandardCharsets.UTF_8);
                if (!html.isBlank()) return html;
            } catch (IOException ignored) {}
        }
        for (String path : new String[] {
                "source/AgentsMC/main/src/main/resources/" + RESOURCE,
                "main/src/main/resources/" + RESOURCE,
                RESOURCE
        }) {
            try {
                Path file = Path.of(path);
                if (Files.isRegularFile(file)) return Files.readString(file);
            } catch (IOException ignored) {}
        }
        throw new IllegalStateException("Bundled " + RESOURCE + " is missing");
    }

    private static List<Map<String, Object>> inventoryOf(Player p) {
        List<Map<String, Object>> out = new ArrayList<>();
        ItemStack[] storage = p.getInventory().getStorageContents();
        if (storage == null) return out;
        for (int i = 0; i < storage.length && out.size() < 16; i++) {
            ItemStack stack = storage[i];
            if (stack == null || stack.getType().isAir()) continue;
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("slot", i);
            row.put("material", stack.getType().name());
            row.put("amount", stack.getAmount());
            out.add(row);
        }
        return out;
    }

    static String inventoryLabel(List<Map<String, Object>> inventory) {
        if (inventory == null || inventory.isEmpty()) return "empty";
        int stacks = inventory.size(), items = 0;
        for (Map<String, Object> row : inventory) items += (int) num(row.get("amount"));
        return stacks + (stacks == 1 ? " stack, " : " stacks, ") + items + (items == 1 ? " item" : " items");
    }

    private static String dimension(Location at) {
        if (at == null || at.getWorld() == null) return "";
        try {
            World.Environment env = at.getWorld().getEnvironment();
            return env == null ? "" : env.name();
        } catch (RuntimeException e) { return ""; }
    }

    private static JsonObject type(String t) {
        JsonObject o = new JsonObject();
        o.addProperty("type", t);
        return o;
    }

    private static JsonObject arrayObject() {
        JsonObject o = new JsonObject();
        o.addProperty("type", "array");
        JsonObject items = new JsonObject();
        items.addProperty("type", "object");
        o.add("items", items);
        return o;
    }

    private static double round1(double v) {
        return Math.round(v * 10) / 10.0;
    }

    private static double num(Object o) {
        if (o instanceof Number n) return n.doubleValue();
        if (o == null) return 0;
        try { return Double.parseDouble(String.valueOf(o)); } catch (NumberFormatException e) { return 0; }
    }

    private AgentUi() {}
}
