package dev.agentsmc;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.entity.Entity;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;

import net.citizensnpcs.api.npc.NPC;

/**
 * Plugin-owned agent events ({@code agent-events-v1}). IDs are
 * {@code persistent-session:counter} and survive restarts. The bridge copies
 * catalog JSON unchanged; this class owns types, schemas and emission.
 */
final class AgentEvents {
    static final String FEATURE = "agent-events-v1";
    static final int MAX_BATCH = 64;
    static final int MAX_EVENT_BYTES = 16_384;
    static final int MAX_CATALOG_BYTES = 131_072;
    static final int MAX_REQUEST_BYTES = 262_144;
    static final int MAX_ID = 128;
    static final int MAX_TYPE = 128;
    static final long MOB_MS = 5_000, HEALTH_MS = 10_000, HUNGER_MS = 10_000, DAMAGE_MS = 1_000, PICKUP_MS = 1_000;
    static final double CHAT_NEAR = 32, MOB_NEAR = 8, HEALTH_LOW = 8, FOOD_LOW = 6;

    private static final Gson JSON = new Gson();

    static final class Record {
        String id = "", type = "";
        long at;
        Map<String, Object> data = Map.of();
        JsonObject json() {
            JsonObject o = new JsonObject();
            o.addProperty("id", id);
            o.addProperty("type", type);
            o.addProperty("at", at);
            o.add("data", JSON.toJsonTree(data == null ? Map.of() : data));
            return o;
        }
    }

    static final class Queue {
        String session = UUID.randomUUID().toString();
        long next = 1;
        boolean catalogDue = true;
        String announcedVersion = "";
        final List<Record> pending = new ArrayList<>();
        final List<Record> recent = new ArrayList<>();
        final Map<String, Long> lastEmit = new LinkedHashMap<>();
        String lastNav = "", lastSelf = "", lastProtect = "", lastFight = "out";
    }

    static boolean supports(Iterable<String> features) {
        if (features == null) return false;
        for (String f : features) if (FEATURE.equals(f)) return true;
        return false;
    }

    static boolean supports(JsonArray features) {
        if (features == null) return false;
        List<String> names = new ArrayList<>();
        for (var v : features) if (v.isJsonPrimitive()) names.add(v.getAsString());
        return supports(names);
    }

    /** Registered website agent ID with hyphens, as in {@code agentIds}/{@code registrations}. */
    static String hyphenated(String raw) {
        if (raw == null) return "";
        String s = raw.trim();
        if (s.length() == 36 && s.charAt(8) == '-') return s;
        String d = s.replace("-", "");
        if (d.length() != 32) return s;
        return d.substring(0, 8) + "-" + d.substring(8, 12) + "-" + d.substring(12, 16)
                + "-" + d.substring(16, 20) + "-" + d.substring(20);
    }

    static String eventId(String session, long counter) {
        String id = session + ":" + counter;
        if (id.length() > MAX_ID) id = id.substring(0, MAX_ID);
        return id;
    }

    static Record event(String session, long counter, String type, long at, Map<String, Object> data) {
        Record r = new Record();
        r.id = eventId(session, counter);
        r.type = type == null ? "" : type;
        r.at = Math.max(0, at);
        Map<String, Object> copy = new LinkedHashMap<>();
        if (data != null) for (var e : data.entrySet()) if (e.getKey() != null && e.getValue() != null) copy.put(e.getKey(), e.getValue());
        r.data = copy;
        return r;
    }

    static Record enqueue(Queue q, String type, long at, Map<String, Object> data) {
        if (q == null || type == null || type.isBlank() || type.length() > MAX_TYPE) return null;
        Record r = event(q.session, q.next++, type, at, data);
        if (!eventFits(r)) return null;
        q.pending.add(r);
        remember(q, r);
        return r;
    }

    static void remember(Queue q, Record r) {
        if (q == null || r == null) return;
        q.recent.add(r);
        while (q.recent.size() > 16) q.recent.remove(0);
    }

    static boolean shouldEmit(long lastAt, long now, long minGapMs) {
        return lastAt <= 0 || now - lastAt >= minGapMs;
    }

    static boolean gated(Queue q, String key, long now, long gapMs) {
        long last = q.lastEmit.getOrDefault(key, 0L);
        if (!shouldEmit(last, now, gapMs)) return false;
        q.lastEmit.put(key, now);
        return true;
    }

    static List<Record> takeBatch(List<Record> pending, int max) {
        if (pending == null || pending.isEmpty() || max < 1) return List.of();
        int n = Math.min(max, Math.min(MAX_BATCH, pending.size()));
        return List.copyOf(pending.subList(0, n));
    }

    static int utf8(String s) {
        return s == null ? 0 : s.getBytes(StandardCharsets.UTF_8).length;
    }

    static boolean eventFits(Record r) {
        return r != null && !r.id.isBlank() && r.id.length() <= MAX_ID && !r.type.isBlank()
                && r.type.length() <= MAX_TYPE && r.at >= 0 && utf8(r.json().toString()) <= MAX_EVENT_BYTES;
    }

    static JsonObject catalog() {
        JsonObject root = new JsonObject();
        JsonArray events = new JsonArray();
        add(events, "chat.received", "A message addressed to or near this body",
                props("text", "string", "from", "string", "kind", "string"));
        add(events, "player.joined", "A human player joined the server", props("name", "string"));
        add(events, "player.left", "A human player left the server", props("name", "string"));
        add(events, "agent.damaged", "This body took damage",
                props("source", "string", "health", "number", "amount", "number"));
        add(events, "agent.died", "This body died", props("cause", "string"));
        add(events, "agent.respawned", "This body respawned after death",
                props("x", "number", "y", "number", "z", "number"));
        add(events, "combat.engaged", "Self-defense, protect, or fight started",
                props("source", "string", "threat", "string"));
        add(events, "combat.ended", "Self-defense, protect, or fight ended",
                props("source", "string", "threat", "string", "outcome", "string"));
        add(events, "action.completed", "A queued action finished successfully",
                props("action", "string", "actionId", "string"));
        add(events, "action.failed", "A queued action failed",
                props("action", "string", "actionId", "string", "error", "string"));
        add(events, "goto.arrived", "Navigation arrived or arrived_near",
                props("status", "string", "x", "number", "y", "number", "z", "number"));
        add(events, "goto.stuck", "Navigation stuck or failed", props("status", "string", "reason", "string"));
        add(events, "nav.recovered_from_air", "Body with no solid within ~1 block below the feet for ~1.5s was snapped down an open column (never through solid, never from scan/combat)",
                props("x", "number", "fromY", "number", "toY", "number", "z", "number"));
        add(events, "health.low", "Health dropped to 8 or below", props("health", "number"));
        add(events, "hunger.low", "Food level dropped to 6 or below", props("food", "number"));
        add(events, "mob.nearby", "A hostile is within 8 blocks (rate-limited)",
                props("type", "string", "distance", "number"));
        add(events, "item.picked_up", "Items entered this body's inventory",
                props("amount", "number"));
        add(events, "plugin.updated", "The plugin version running on this server changed",
                pluginUpdatedSchema());
        root.add("events", events);
        return root;
    }

    static String updatedId(String version) {
        return "plugin.updated:" + (version == null ? "" : version);
    }

    private static JsonObject pluginUpdatedSchema() {
        JsonObject properties = new JsonObject();
        JsonObject version = new JsonObject(); version.addProperty("type", "string"); properties.add("version", version);
        JsonObject previous = new JsonObject(); previous.addProperty("type", "string"); properties.add("previousVersion", previous);
        properties.add("changes", stringArraySchema());
        properties.add("actionsAdded", stringArraySchema());
        properties.add("actionsChanged", stringArraySchema());
        JsonObject schema = new JsonObject();
        schema.addProperty("type", "object");
        schema.add("properties", properties);
        return schema;
    }

    private static JsonObject stringArraySchema() {
        JsonObject items = new JsonObject();
        items.addProperty("type", "string");
        JsonObject array = new JsonObject();
        array.addProperty("type", "array");
        array.add("items", items);
        return array;
    }

    private static JsonObject props(String... kv) {
        JsonObject properties = new JsonObject();
        for (int i = 0; i + 1 < kv.length; i += 2) {
            JsonObject p = new JsonObject();
            p.addProperty("type", kv[i + 1]);
            properties.add(kv[i], p);
        }
        JsonObject schema = new JsonObject();
        schema.addProperty("type", "object");
        schema.add("properties", properties);
        return schema;
    }

    private static void add(JsonArray events, String type, String description, JsonObject schema) {
        JsonObject e = new JsonObject();
        e.addProperty("type", type);
        e.addProperty("description", description);
        e.addProperty("available", true);
        e.add("dataSchema", schema);
        events.add(e);
    }

    static String catalogHash(JsonObject catalog) {
        return Integer.toHexString(catalog.toString().hashCode());
    }

    /** Both catalog and events optional, but at least one required. Shrinks the batch to fit 256KB. */
    static JsonObject requestBody(JsonObject catalog, List<Record> events) {
        JsonObject body = new JsonObject();
        if (catalog != null) {
            if (utf8(catalog.toString()) > MAX_CATALOG_BYTES)
                throw new IllegalArgumentException("catalog exceeds 131072 bytes");
            body.add("catalog", catalog);
        }
        List<Record> batch = new ArrayList<>();
        if (events != null) {
            for (Record r : events) {
                if (batch.size() >= MAX_BATCH) break;
                if (!eventFits(r)) continue;
                batch.add(r);
                JsonObject probe = body.deepCopy();
                probe.add("events", eventsArray(batch));
                if (utf8(probe.toString()) > MAX_REQUEST_BYTES) {
                    batch.remove(batch.size() - 1);
                    break;
                }
            }
        }
        if (!batch.isEmpty()) body.add("events", eventsArray(batch));
        if (!body.has("catalog") && !body.has("events"))
            throw new IllegalArgumentException("events request needs catalog or events");
        return body;
    }

    static JsonArray eventsArray(List<Record> events) {
        JsonArray a = new JsonArray();
        for (Record r : events) a.add(r.json());
        return a;
    }

    /** 2xx acks. 5xx/IO retry same IDs. 400/409 drop after log. 401/404 retry, do not ack. */
    static String disposition(int status) {
        if (status >= 200 && status < 300) return "ack";
        if (status == 400 || status == 409) return "drop";
        if (status == 401 || status == 404) return "retry";
        if (status >= 500 || status <= 0) return "retry";
        return "retry";
    }

    static String statusLog(int status, String agentId, String body) {
        String snippet = body == null ? "" : (body.length() > 200 ? body.substring(0, 200) : body);
        return switch (status) {
            case 401 -> "events 401 invalid server credentials for " + agentId + ": " + snippet;
            case 404 -> "events 404 unknown agent " + agentId + ": " + snippet;
            case 409 -> "events 409 id conflict for " + agentId + " (same id, different contents): " + snippet;
            case 400 -> "events 400 malformed or over limit for " + agentId + ": " + snippet;
            default -> "events HTTP " + status + " for " + agentId + ": " + snippet;
        };
    }

    static boolean chatRelevant(String kind, String text, String agentName, double dist) {
        if ("whisper".equals(kind)) return true;
        if (agentName != null && text != null && text.toLowerCase(Locale.ROOT).contains(agentName.toLowerCase(Locale.ROOT)))
            return true;
        return Double.isFinite(dist) && dist <= CHAT_NEAR + 1e-9;
    }

    static Map<String, Object> data(Object... kv) {
        Map<String, Object> m = new LinkedHashMap<>();
        for (int i = 0; i + 1 < kv.length; i += 2)
            if (kv[i] != null && kv[i + 1] != null) m.put(String.valueOf(kv[i]), kv[i + 1]);
        return m;
    }

    static final class Job {
        String agentId = "";
        boolean catalog;
        List<String> ids = List.of();
        JsonObject body = new JsonObject();
    }

    private static final Object LOCK = new Object();
    private static AgentEventStore store;
    private static final Map<String, Queue> queues = new LinkedHashMap<>();
    private static boolean connected;

    static void resetForTest() {
        synchronized (LOCK) {
            store = null;
            queues.clear();
            connected = false;
        }
    }

    static void attach(AgentEventStore next) throws java.io.IOException {
        synchronized (LOCK) {
            Map<String, Queue> loaded = next == null ? Map.of() : next.load();
            for (var entry : loaded.entrySet()) {
                Queue live = queues.get(entry.getKey());
                if (live == null) queues.put(entry.getKey(), entry.getValue());
                else live.pending.addAll(0, entry.getValue().pending);
            }
            store = next;
            persistLocked();
        }
    }

    static Queue queue(String rawId) {
        String id = hyphenated(rawId);
        return queues.computeIfAbsent(id, key -> new Queue());
    }

    static String siteId(NPC npc) {
        if (npc == null || npc.data() == null || !npc.data().has("agentsmc-id")) return "";
        return hyphenated(AgentErrors.text(npc.data().get("agentsmc-id")));
    }

    static String agentId(NPC npc) {
        return siteId(npc);
    }

    static void onConnected() {
        onConnected(java.util.List.of());
    }

    /** Mark catalog due for every linked agent (hyphenated poll ids) and existing queues. */
    static void onConnected(Iterable<String> agentIds) {
        synchronized (LOCK) {
            connected = true;
            if (agentIds != null) for (String id : agentIds) {
                String h = hyphenated(id);
                if (!h.isBlank()) queue(h).catalogDue = true;
            }
            for (Queue q : queues.values()) q.catalogDue = true;
            persistLocked();
        }
    }

    /** Create queues for newly linked agents so the next takeJob posts catalog. */
    static int ensureLinked(Iterable<String> agentIds) {
        int added = 0;
        synchronized (LOCK) {
            if (agentIds == null) return 0;
            for (String id : agentIds) {
                String h = hyphenated(id);
                if (h.isBlank()) continue;
                if (!queues.containsKey(h)) { queue(h); added++; }
            }
            persistLocked();
        }
        return added;
    }

    /**
     * Post {@code plugin.updated} for the running version only. Persists
     * {@code announcedVersion} per agent and uses id {@code plugin.updated:<version>}.
     */
    static int announceRunningVersion(Iterable<String> agentIds) {
        return announceRunningVersion(agentIds, AgentVersion.current(), PluginChangelog.current());
    }

    static int announceRunningVersion(Iterable<String> agentIds, String version, PluginChangelog.Notes notes) {
        if (version == null || version.isBlank() || notes == null || !version.equals(notes.version)) return 0;
        int n = 0;
        synchronized (LOCK) {
            if (agentIds == null) return 0;
            for (String raw : agentIds) {
                String id = hyphenated(raw);
                if (!id.isBlank() && announceLocked(id, version, notes)) n++;
            }
            persistLocked();
        }
        return n;
    }

    private static boolean announceLocked(String id, String version, PluginChangelog.Notes notes) {
        Queue q = queue(id);
        String want = updatedId(version);
        if (version.equals(q.announcedVersion)) return false;
        if (q.pending.stream().anyMatch(r -> want.equals(r.id))) {
            q.announcedVersion = version;
            return false;
        }
        String previous = q.announcedVersion == null ? "" : q.announcedVersion;
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("version", version);
        payload.put("previousVersion", previous);
        payload.put("changes", List.copyOf(notes.changes));
        payload.put("actionsAdded", List.copyOf(notes.actionsAdded));
        payload.put("actionsChanged", List.copyOf(notes.actionsChanged));
        Record r = new Record();
        r.id = want;
        r.type = "plugin.updated";
        r.at = Math.max(0, System.currentTimeMillis());
        r.data = payload;
        if (!eventFits(r)) return false;
        q.pending.add(r);
        remember(q, r);
        q.announcedVersion = version;
        q.catalogDue = true;
        return true;
    }

    static String announcedVersion(String rawId) {
        synchronized (LOCK) {
            String id = hyphenated(rawId);
            if (id.isBlank() || !queues.containsKey(id)) return "";
            String v = queues.get(id).announcedVersion;
            return v == null ? "" : v;
        }
    }

    static boolean catalogDue(String rawId) {
        synchronized (LOCK) { return queue(rawId).catalogDue; }
    }

    static String publishLog(String agentId, Job job, int status, String disposition) {
        String id = hyphenated(agentId);
        String what = job == null ? "nothing"
                : job.catalog && job.ids.isEmpty() ? "catalog"
                : job.catalog ? "catalog and " + job.ids.size() + " events"
                : job.ids.size() + " events";
        if ("ack".equals(disposition))
            return "Posted event " + what + " for " + id + " HTTP " + status;
        return statusLog(status, id, "");
    }

    static Record emit(String agentId, String type, Map<String, Object> data) {
        return emit(agentId, type, System.currentTimeMillis(), data);
    }

    static Record emit(String agentId, String type, long at, Map<String, Object> data) {
        synchronized (LOCK) {
            String id = hyphenated(agentId);
            if (id.isBlank()) return null;
            Record r = enqueue(queue(id), type, at, data);
            persistLocked();
            return r;
        }
    }

    static Record emitGated(String agentId, String type, String gate, long gapMs, Map<String, Object> data) {
        synchronized (LOCK) {
            String id = hyphenated(agentId);
            if (id.isBlank()) return null;
            Queue q = queue(id);
            long now = System.currentTimeMillis();
            if (!gated(q, gate, now, gapMs)) return null;
            Record r = enqueue(q, type, now, data);
            persistLocked();
            return r;
        }
    }

    static void emitAction(String agentId, String actionId, String action, JsonObject result) {
        boolean ok = result != null && result.has("ok") && result.get("ok").isJsonPrimitive()
                && result.get("ok").getAsJsonPrimitive().isBoolean() && result.get("ok").getAsBoolean();
        String error = "";
        if (result != null && result.has("error") && result.get("error").isJsonPrimitive())
            error = result.get("error").getAsString();
        if (ok) emit(agentId, "action.completed", data("action", action, "actionId", actionId));
        else emit(agentId, "action.failed", data("action", action, "actionId", actionId, "error", AgentErrors.display(error)));
    }

    static void sample(NPC npc, Player player) {
        if (npc == null || player == null) return;
        String id = siteId(npc);
        if (id.isBlank()) return;
        synchronized (LOCK) {
            Queue q = queue(id);
            long now = System.currentTimeMillis();
            double health = player.getHealth();
            int food = player.getFoodLevel();
            if (health <= HEALTH_LOW && gated(q, "health.low", now, HEALTH_MS))
                enqueue(q, "health.low", now, data("health", health));
            if (food <= FOOD_LOW && gated(q, "hunger.low", now, HUNGER_MS))
                enqueue(q, "hunger.low", now, data("food", food));
            LivingEntity mob = nearestHostile(player, MOB_NEAR);
            if (mob != null && gated(q, "mob.nearby", now, MOB_MS))
                enqueue(q, "mob.nearby", now, data("type", mob.getType().name(),
                        "distance", Math.round(player.getLocation().distance(mob.getLocation()) * 10) / 10.0));
            sampleNav(q, npc, now);
            sampleCombat(q, npc, now);
            persistLocked();
        }
    }

    static void sampleNav(Queue q, String status, String reason, Double x, Double y, Double z, long now) {
        if (q == null || status == null) return;
        if (status.equals(q.lastNav)) return;
        String prev = q.lastNav;
        q.lastNav = status;
        boolean arrived = "arrived".equals(status) || "arrived_near".equals(status);
        boolean stuck = "stuck".equals(status) || "failed".equals(status);
        if (prev.isEmpty() && !arrived && !stuck) return;
        if (arrived) enqueue(q, "goto.arrived", now, data("status", status, "x", x, "y", y, "z", z));
        else if (stuck) {
            if ("arrived".equals(prev) || "arrived_near".equals(prev)) {
                q.lastNav = prev;
                return;
            }
            enqueue(q, "goto.stuck", now, data("status", status, "reason", reason == null ? "" : reason));
        }
    }

    static void sampleCombat(Queue q, boolean fighting, String source, String threat, String outcome, long now) {
        if (q == null) return;
        String flag = fighting ? "in" : "out";
        if (flag.equals(q.lastFight)) return;
        q.lastFight = flag;
        if (fighting) enqueue(q, "combat.engaged", now, data("source", source, "threat", threat));
        else enqueue(q, "combat.ended", now, data("source", source, "threat", threat, "outcome", outcome));
    }

    private static void sampleNav(Queue q, NPC npc, long now) {
        Map<String, Object> nav = AgentNavigation.snapshot(npc);
        String status = String.valueOf(nav.getOrDefault("status", "idle"));
        String reason = String.valueOf(nav.getOrDefault("reason", ""));
        Double x = null, y = null, z = null;
        Object target = nav.get("target");
        if (target instanceof Map<?, ?> point) {
            x = asDouble(point.get("x"));
            y = asDouble(point.get("y"));
            z = asDouble(point.get("z"));
        }
        sampleNav(q, status, reason, x, y, z, now);
    }

    private static void sampleCombat(Queue q, NPC npc, long now) {
        Map<String, Object> self = AgentSelfDefense.snapshot(npc);
        Map<String, Object> prot = AgentProtect.snapshot(npc);
        Map<String, Object> fight = AgentCombat.snapshot(npc);
        String selfSt = String.valueOf(self.getOrDefault("status", "idle"));
        String protSt = String.valueOf(prot.getOrDefault("state", "idle"));
        String fightSt = String.valueOf(fight.getOrDefault("status", "idle"));
        boolean fighting = Set.of("fighting", "fleeing").contains(selfSt)
                || Set.of("fighting", "chasing").contains(protSt)
                || Set.of("fighting", "chasing", "aiming").contains(fightSt);
        String source = Set.of("fighting", "fleeing").contains(selfSt) ? "self_defense"
                : Set.of("fighting", "chasing").contains(protSt) ? "protect"
                : Set.of("fighting", "chasing", "aiming").contains(fightSt) ? "fight" : q.lastFight.equals("in") ? "combat" : "";
        String threat = firstNonBlank(self.get("threat"), prot.get("lastThreat"), fight.get("target"));
        String outcome = "killed".equals(fightSt) ? "killed" : "idle".equals(selfSt) || "stopped".equals(protSt) ? "ended" : fightSt;
        sampleCombat(q, fighting, source, threat, outcome, now);
    }

    private static LivingEntity nearestHostile(Player player, double range) {
        LivingEntity closest = null;
        double best = range + 1e-9;
        for (Entity e : player.getNearbyEntities(range, range, range)) {
            if (!(e instanceof LivingEntity living) || !living.isValid() || living.isDead() || !AgentProtect.hostile(living))
                continue;
            double d = player.getLocation().distance(living.getLocation());
            if (d < best) { best = d; closest = living; }
        }
        return closest;
    }

    private static Double asDouble(Object v) {
        return v instanceof Number n ? n.doubleValue() : null;
    }

    private static String firstNonBlank(Object... values) {
        for (Object v : values) {
            if (v == null) continue;
            String s = String.valueOf(v);
            if (!s.isBlank() && !"null".equals(s)) return s;
        }
        return "";
    }

    static Job takeJob(String rawId) {
        synchronized (LOCK) {
            String id = hyphenated(rawId);
            if (id.isBlank()) return null;
            Queue q = queue(id);
            JsonObject catalog = q.catalogDue ? catalog() : null;
            List<Record> batch = takeBatch(q.pending, MAX_BATCH);
            if (catalog == null && batch.isEmpty()) return null;
            JsonObject body = requestBody(catalog, batch);
            List<String> ids = new ArrayList<>();
            if (body.has("events") && body.get("events").isJsonArray())
                for (var v : body.getAsJsonArray("events"))
                    if (v.isJsonObject() && v.getAsJsonObject().has("id"))
                        ids.add(v.getAsJsonObject().get("id").getAsString());
            Job job = new Job();
            job.agentId = id;
            job.catalog = body.has("catalog");
            job.ids = List.copyOf(ids);
            job.body = body;
            return job;
        }
    }

    static void finish(String rawId, Job job, String disposition) {
        synchronized (LOCK) {
            Queue q = queue(hyphenated(rawId));
            if (job != null && ("ack".equals(disposition) || "drop".equals(disposition))) {
                Set<String> done = new HashSet<>(job.ids);
                q.pending.removeIf(r -> done.contains(r.id));
                if (job.catalog && "ack".equals(disposition)) q.catalogDue = false;
            }
            persistLocked();
        }
    }

    static List<Record> pending(String rawId) {
        synchronized (LOCK) { return List.copyOf(queue(rawId).pending); }
    }

    static List<Map<String, Object>> recentView(String rawId, int max) {
        synchronized (LOCK) {
            String id = hyphenated(rawId);
            if (id.isBlank() || !queues.containsKey(id)) return List.of();
            List<Record> src = queues.get(id).recent;
            if (src.isEmpty()) src = queues.get(id).pending;
            int n = Math.max(0, Math.min(max, src.size()));
            List<Map<String, Object>> out = new ArrayList<>();
            for (int i = src.size() - n; i < src.size(); i++) {
                Record r = src.get(i);
                Map<String, Object> row = new LinkedHashMap<>();
                row.put("type", r.type);
                row.put("at", r.at);
                Object summary = r.data == null ? null : r.data.get("reason");
                if (summary == null && r.data != null) summary = r.data.get("action");
                row.put("summary", summary == null ? r.type : r.type + " " + summary);
                out.add(row);
            }
            return out;
        }
    }

    static String session(String rawId) {
        synchronized (LOCK) { return queue(rawId).session; }
    }

    static void persist() {
        synchronized (LOCK) { persistLocked(); }
    }

    private static void persistLocked() {
        if (store != null) store.save(queues);
    }

    private AgentEvents() {}
}
