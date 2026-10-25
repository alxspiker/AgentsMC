package dev.agentsmc;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import net.citizensnpcs.api.npc.NPC;

/**
 * Plugin-owned feedback log at {@code plugins/AgentsMC/feedback.json}.
 * Any agent can file an entry; list/resolve are maintainer reads.
 */
final class AgentFeedback {
    static final String OPEN = "open", DONE = "done", ALL = "all";
    static final Set<String> CATEGORIES = Set.of("bug", "request", "idea");
    static final int MAX_TEXT = 2000, MAX_NOTE = 1000;
    private static final Object LOCK = new Object();
    private static Path file;
    private static int nextId = 1;
    private static final List<Entry> entries = new ArrayList<>();

    static final class Entry {
        String id = "", agentId = "", agentName = "", category = "", text = "", context = "", status = OPEN, note = "";
        long timestamp;
        Map<String, Object> view() {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("id", id);
            m.put("agentId", agentId);
            m.put("agentName", agentName);
            m.put("timestamp", timestamp);
            if (!category.isBlank()) m.put("category", category);
            m.put("text", text);
            if (!context.isBlank()) m.put("context", context);
            m.put("status", status);
            if (!note.isBlank()) m.put("note", note);
            return m;
        }
    }

    static void attach(Path path) {
        synchronized (LOCK) {
            file = path;
            loadLocked();
        }
    }

    static void resetForTest() {
        synchronized (LOCK) {
            file = null;
            nextId = 1;
            entries.clear();
        }
    }

    static boolean handles(String action) {
        return "feedback".equals(action) || "feedback_list".equals(action) || "feedback_resolve".equals(action);
    }

    static Map<String, Object> execute(NPC npc, JsonObject input) {
        String action = str(input, "action");
        if ("feedback".equals(action)) return submit(npc, input);
        if ("feedback_list".equals(action)) return list(input);
        if ("feedback_resolve".equals(action)) return resolve(input);
        throw new IllegalArgumentException("Unknown feedback action: " + action);
    }

    static Map<String, Object> submit(NPC npc, JsonObject input) {
        fields(input, List.of("category", "context", "message", "text"), "action", "agent");
        String text = first(input, "text", "message");
        require(text != null && !text.isBlank(), "feedback requires text");
        text = printable(text, MAX_TEXT, "text");
        String category = optional(input, "category");
        if (category != null) {
            category = category.trim().toLowerCase(Locale.ROOT);
            require(CATEGORIES.contains(category), "category must be bug, request, or idea");
        } else category = "";
        String context = optional(input, "context");
        if (context != null) context = printable(context, MAX_TEXT, "context");
        else context = "";
        Entry e = new Entry();
        synchronized (LOCK) {
            e.id = "fb-" + nextId++;
            e.agentId = npc == null ? "" : AgentIds.publicId(npc.data().get("agentsmc-id"));
            if (e.agentId == null) e.agentId = "";
            e.agentName = npc == null ? "" : npc.getName();
            e.timestamp = System.currentTimeMillis();
            e.category = category;
            e.text = text;
            e.context = context;
            e.status = OPEN;
            entries.add(e);
            saveLocked();
        }
        return map("id", e.id, "status", e.status, "category", e.category, "text", e.text, "saved", true);
    }

    static Map<String, Object> list(JsonObject input) {
        fields(input, List.of("status", "since"), "action", "agent");
        String status = optional(input, "status");
        if (status == null || status.isBlank()) status = OPEN;
        else status = status.trim().toLowerCase(Locale.ROOT);
        require(status.equals(OPEN) || status.equals(DONE) || status.equals(ALL), "status must be open, done, or all");
        long since = sinceOf(input);
        List<Map<String, Object>> out = new ArrayList<>();
        synchronized (LOCK) {
            for (Entry e : entries) {
                if (e.timestamp < since) continue;
                if (!ALL.equals(status) && !status.equals(e.status)) continue;
                out.add(e.view());
            }
        }
        return map("count", out.size(), "status", status, "since", since, "entries", out);
    }

    static Map<String, Object> resolve(JsonObject input) {
        fields(input, List.of("note", "status"), "action", "agent", "id");
        String id = str(input, "id").trim();
        require(id.matches("fb-[0-9]+"), "id must look like fb-1");
        String note = optional(input, "note");
        if (note != null) note = printable(note, MAX_NOTE, "note");
        else note = "";
        synchronized (LOCK) {
            Entry found = null;
            for (Entry e : entries) if (id.equals(e.id)) { found = e; break; }
            require(found != null, "Unknown feedback id: " + id);
            found.status = DONE;
            if (!note.isBlank()) found.note = note;
            saveLocked();
            return map("id", found.id, "status", found.status, "note", found.note, "resolved", true);
        }
    }

    static List<Entry> all() {
        synchronized (LOCK) { return List.copyOf(entries); }
    }

    static List<String> consoleList(String status) {
        JsonObject o = new JsonObject();
        o.addProperty("action", "feedback_list");
        o.addProperty("agent", "console");
        if (status != null && !status.isBlank()) o.addProperty("status", status);
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> rows = (List<Map<String, Object>>) list(o).get("entries");
        List<String> lines = new ArrayList<>();
        for (Map<String, Object> row : rows) {
            lines.add(row.get("id") + " " + row.get("status") + " " + row.get("agentName")
                    + " " + Instant.ofEpochMilli(((Number) row.get("timestamp")).longValue())
                    + (row.get("category") == null ? "" : " " + row.get("category"))
                    + ": " + row.get("text"));
        }
        return lines;
    }

    static Map<String, Object> consoleResolve(String id, String note) {
        JsonObject o = new JsonObject();
        o.addProperty("action", "feedback_resolve");
        o.addProperty("agent", "console");
        o.addProperty("id", id);
        if (note != null && !note.isBlank()) o.addProperty("note", note);
        return resolve(o);
    }

    private static void loadLocked() {
        entries.clear();
        nextId = 1;
        if (file == null || !Files.exists(file)) return;
        try {
            JsonObject root = JsonParser.parseString(Files.readString(file)).getAsJsonObject();
            if (root.has("nextId") && root.get("nextId").isJsonPrimitive())
                nextId = Math.max(1, root.get("nextId").getAsInt());
            if (root.has("entries") && root.get("entries").isJsonArray()) {
                for (var v : root.getAsJsonArray("entries")) {
                    if (!v.isJsonObject()) continue;
                    Entry e = read(v.getAsJsonObject());
                    if (e != null) entries.add(e);
                }
            }
            int max = 0;
            for (Entry e : entries) {
                if (e.id.startsWith("fb-")) try { max = Math.max(max, Integer.parseInt(e.id.substring(3))); }
                catch (NumberFormatException ignored) {}
            }
            if (nextId <= max) nextId = max + 1;
        } catch (RuntimeException | IOException failure) {
            throw new IllegalStateException("Cannot read feedback.json", failure);
        }
    }

    private static void saveLocked() {
        if (file == null) return;
        try {
            JsonObject root = new JsonObject();
            root.addProperty("nextId", nextId);
            JsonArray arr = new JsonArray();
            for (Entry e : entries) arr.add(write(e));
            root.add("entries", arr);
            Path temporary = file.resolveSibling(file.getFileName() + ".tmp");
            Files.createDirectories(file.toAbsolutePath().getParent());
            byte[] bytes = root.toString().getBytes(StandardCharsets.UTF_8);
            try (FileChannel channel = FileChannel.open(temporary, StandardOpenOption.CREATE,
                    StandardOpenOption.TRUNCATE_EXISTING, StandardOpenOption.WRITE)) {
                ByteBuffer buffer = ByteBuffer.wrap(bytes);
                while (buffer.hasRemaining()) channel.write(buffer);
                channel.force(true);
            }
            Files.move(temporary, file, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException failure) {
            throw new IllegalStateException("Cannot persist feedback.json", failure);
        }
    }

    private static Entry read(JsonObject o) {
        if (o == null || !o.has("id")) return null;
        Entry e = new Entry();
        e.id = prim(o, "id");
        e.agentId = prim(o, "agentId");
        e.agentName = prim(o, "agentName");
        e.timestamp = o.has("timestamp") && o.get("timestamp").isJsonPrimitive() ? o.get("timestamp").getAsLong() : 0;
        e.category = prim(o, "category");
        e.text = prim(o, "text");
        e.context = prim(o, "context");
        e.status = prim(o, "status");
        if (!DONE.equals(e.status)) e.status = OPEN;
        e.note = prim(o, "note");
        return e.id.isBlank() ? null : e;
    }

    private static JsonObject write(Entry e) {
        JsonObject o = new JsonObject();
        o.addProperty("id", e.id);
        o.addProperty("agentId", e.agentId);
        o.addProperty("agentName", e.agentName);
        o.addProperty("timestamp", e.timestamp);
        o.addProperty("category", e.category);
        o.addProperty("text", e.text);
        o.addProperty("context", e.context);
        o.addProperty("status", e.status);
        o.addProperty("note", e.note);
        return o;
    }

    private static void fields(JsonObject input, List<String> optional, String... required) {
        List<String> allow = new ArrayList<>(optional);
        allow.addAll(List.of(required));
        String action = input.has("action") && input.get("action").isJsonPrimitive() ? input.get("action").getAsString() : "action";
        for (String key : input.keySet())
            require(allow.contains(key), "Unknown field '" + key + "' for action " + action);
        for (String key : required)
            require(input.has(key) && !input.get(key).isJsonNull(), "Missing field '" + key + "' for action " + action);
    }

    private static String str(JsonObject o, String key) {
        require(o.has(key) && o.get(key).isJsonPrimitive() && o.getAsJsonPrimitive(key).isString(), "Expected string: " + key);
        String v = o.get(key).getAsString();
        require(!v.isEmpty(), "Empty field: " + key);
        return v;
    }

    private static String optional(JsonObject o, String key) {
        if (!o.has(key) || o.get(key).isJsonNull()) return null;
        require(o.get(key).isJsonPrimitive() && o.getAsJsonPrimitive(key).isString(), "Expected string: " + key);
        String v = o.get(key).getAsString();
        return v.isBlank() ? null : v;
    }

    private static String first(JsonObject o, String... keys) {
        for (String key : keys) {
            if (!o.has(key) || o.get(key).isJsonNull()) continue;
            if (o.get(key).isJsonPrimitive() && o.getAsJsonPrimitive(key).isString()) {
                String v = o.get(key).getAsString();
                if (!v.isBlank()) return v;
            }
        }
        return null;
    }

    private static long sinceOf(JsonObject o) {
        if (!o.has("since") || o.get("since").isJsonNull()) return 0;
        require(o.get("since").isJsonPrimitive(), "since must be epoch milliseconds");
        var p = o.getAsJsonPrimitive("since");
        if (p.isNumber()) return Math.max(0, p.getAsLong());
        if (p.isString()) {
            try { return Math.max(0, Long.parseLong(p.getAsString().trim())); }
            catch (NumberFormatException e) { throw new IllegalArgumentException("since must be epoch milliseconds"); }
        }
        throw new IllegalArgumentException("since must be epoch milliseconds");
    }

    private static String printable(String raw, int max, String field) {
        require(raw.length() <= max && raw.chars().noneMatch(Character::isISOControl),
                field + " must be printable and at most " + max + " characters");
        return raw;
    }

    private static String prim(JsonObject o, String key) {
        return o.has(key) && o.get(key).isJsonPrimitive() ? o.get(key).getAsString() : "";
    }

    private static void require(boolean ok, String message) {
        if (!ok) throw new IllegalArgumentException(message);
    }

    private static Map<String, Object> map(Object... pairs) {
        Map<String, Object> m = new LinkedHashMap<>();
        for (int i = 0; i < pairs.length; i += 2) m.put((String) pairs[i], pairs[i + 1]);
        return m;
    }

    private AgentFeedback() {}
}
