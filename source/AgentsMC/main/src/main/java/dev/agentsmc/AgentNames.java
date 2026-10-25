package dev.agentsmc;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.regex.Pattern;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

/**
 * Display names: bridge registration first, then a persisted copy of that
 * name, then {@code Agent-xxxxxxxx}. Nothing is bundled in the jar.
 */
final class AgentNames {
    static final Pattern VALID = Pattern.compile("[\\p{L}\\p{N}][\\p{L}\\p{N} _.-]{0,31}");

    private static final Object LOCK = new Object();
    private static final Map<String, String> registered = new LinkedHashMap<>();
    private static Path file;

    static void resetForTest() {
        synchronized (LOCK) {
            registered.clear();
            file = null;
        }
    }

    static void attach(Path next) throws IOException {
        synchronized (LOCK) {
            file = next;
            registered.clear();
            if (next != null && Files.exists(next)) {
                JsonObject root = JsonParser.parseString(Files.readString(next)).getAsJsonObject();
                for (var entry : root.entrySet()) {
                    if (!entry.getValue().isJsonPrimitive()) continue;
                    String name = entry.getValue().getAsString();
                    if (valid(name)) registered.put(dashless(entry.getKey()), name);
                }
            }
        }
    }

    static boolean valid(String name) {
        return name != null && !name.isBlank() && VALID.matcher(name).matches();
    }

    static String dashless(String id) {
        return id == null ? "" : id.replace("-", "").toLowerCase();
    }

    static String placeholder(String id) {
        String local = dashless(id);
        if (local.length() > 16) return "Agent-" + local.substring(0, 8);
        return local.isEmpty() ? "Agent" : local;
    }

    static boolean isPlaceholder(String current, String id) {
        if (current == null || current.isBlank()) return true;
        String local = dashless(id);
        if (current.equalsIgnoreCase(local) || current.equalsIgnoreCase(AgentEvents.hyphenated(local))) return true;
        if (current.equalsIgnoreCase(placeholder(local))) return true;
        return current.matches("Agent-[0-9a-fA-F]{8}");
    }

    static String persisted(String id) {
        synchronized (LOCK) { return registered.get(dashless(id)); }
    }

    static String resolve(String id, String registrationName) {
        if (valid(registrationName)) return registrationName.trim();
        String persisted = persisted(id);
        if (valid(persisted)) return persisted;
        return placeholder(id);
    }

    static boolean shouldApply(String current, String wanted, String id) {
        if (!valid(wanted) || wanted.equals(current)) return false;
        return !isPlaceholder(wanted, id) || isPlaceholder(current, id);
    }

    static void remember(String id, String name) {
        if (!valid(name)) return;
        synchronized (LOCK) {
            registered.put(dashless(id), name.trim());
            persistLocked();
        }
    }

    static boolean sameId(String a, String b) {
        String left = dashless(a), right = dashless(b);
        return !left.isEmpty() && left.equals(right);
    }

    static String textField(JsonObject o, String... keys) {
        if (o == null) return "";
        for (String key : keys) {
            if (!o.has(key) || !o.get(key).isJsonPrimitive()) continue;
            String v = o.get(key).getAsString();
            if (v != null && !v.isBlank()) return v.trim();
        }
        return "";
    }

    /** id / agentId / agent_id / uuid from a string or object poll entry. */
    static String pollId(JsonElement el) {
        if (el == null || el.isJsonNull()) return "";
        if (el.isJsonPrimitive()) return el.getAsString().trim();
        if (el.isJsonObject()) return textField(el.getAsJsonObject(), "id", "agentId", "agent_id", "uuid", "agent");
        return "";
    }

    static String pollName(JsonElement el) {
        if (el == null || !el.isJsonObject()) return "";
        return textField(el.getAsJsonObject(), "name", "displayName", "display_name", "agentName", "agent_name", "label");
    }

    /** Collect display names from every poll shape the site has used. */
    static Map<String, String> namesFromPoll(JsonObject poll) {
        Map<String, String> out = new LinkedHashMap<>();
        if (poll == null) return out;
        takeNamedArray(poll, out, "registrations", "agents", "identities", "bodies");
        if (poll.has("agentIds") && poll.get("agentIds").isJsonArray()) {
            for (var v : poll.getAsJsonArray("agentIds")) {
                String id = pollId(v), name = pollName(v);
                if (!id.isBlank() && valid(name)) out.put(dashless(id), name);
            }
        }
        if (poll.has("names") && poll.get("names").isJsonObject()) {
            for (var e : poll.getAsJsonObject("names").entrySet()) {
                if (!e.getValue().isJsonPrimitive()) continue;
                String name = e.getValue().getAsString();
                if (valid(name)) out.put(dashless(e.getKey()), name.trim());
            }
        }
        return out;
    }

    private static void takeNamedArray(JsonObject poll, Map<String, String> out, String... keys) {
        for (String key : keys) {
            if (!poll.has(key) || !poll.get(key).isJsonArray()) continue;
            for (var v : poll.getAsJsonArray(key)) {
                String id = pollId(v), name = pollName(v);
                if (!id.isBlank() && valid(name)) out.put(dashless(id), name);
            }
        }
    }

    static boolean arrayContainsId(JsonArray array, String id) {
        if (array == null || dashless(id).isEmpty()) return false;
        for (var v : array) if (sameId(pollId(v), id)) return true;
        return false;
    }

    static String pollShape(JsonObject poll) {
        if (poll == null) return "poll=null";
        StringBuilder b = new StringBuilder("pollKeys=").append(poll.keySet());
        if (poll.has("agentIds") && poll.get("agentIds").isJsonArray()) {
            JsonArray ids = poll.getAsJsonArray("agentIds");
            b.append(" agentIds=").append(ids.size());
            if (!ids.isEmpty()) b.append(" agentIds[0]=").append(kind(ids.get(0)));
        }
        if (poll.has("registrations") && poll.get("registrations").isJsonArray()) {
            JsonArray regs = poll.getAsJsonArray("registrations");
            b.append(" registrations=").append(regs.size());
            if (!regs.isEmpty() && regs.get(0).isJsonObject())
                b.append(" registrationKeys=").append(regs.get(0).getAsJsonObject().keySet());
        }
        return b.toString();
    }

    static String missingNameWarning(String id, String current, JsonObject poll) {
        Map<String, String> names = namesFromPoll(poll);
        boolean inIds = poll != null && arrayContainsId(poll.has("agentIds") ? poll.getAsJsonArray("agentIds") : null, id);
        boolean inRegs = poll != null && arrayContainsId(poll.has("registrations") ? poll.getAsJsonArray("registrations") : null, id);
        return "No display name in poll for " + dashless(id) + " (still " + current + "); in agentIds=" + inIds
                + " in registrations=" + inRegs + " extractedName=" + names.getOrDefault(dashless(id), "")
                + " " + pollShape(poll);
    }

    private static String kind(JsonElement el) {
        if (el == null || el.isJsonNull()) return "null";
        if (el.isJsonPrimitive()) return "string";
        if (el.isJsonObject()) return "object" + el.getAsJsonObject().keySet();
        if (el.isJsonArray()) return "array";
        return el.getClass().getSimpleName();
    }

    static void persist() {
        synchronized (LOCK) { persistLocked(); }
    }

    private static void persistLocked() {
        if (file == null) return;
        try {
            JsonObject root = new JsonObject();
            for (var entry : registered.entrySet()) root.addProperty(entry.getKey(), entry.getValue());
            Files.createDirectories(file.toAbsolutePath().getParent());
            Path temporary = file.resolveSibling(file.getFileName() + ".tmp");
            byte[] bytes = root.toString().getBytes(StandardCharsets.UTF_8);
            try (FileChannel channel = FileChannel.open(temporary, StandardOpenOption.CREATE,
                    StandardOpenOption.TRUNCATE_EXISTING, StandardOpenOption.WRITE)) {
                ByteBuffer buffer = ByteBuffer.wrap(bytes);
                while (buffer.hasRemaining()) channel.write(buffer);
                channel.force(true);
            }
            Files.move(temporary, file, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException failure) {
            throw new IllegalStateException("Cannot persist registered agent names", failure);
        }
    }

    private AgentNames() {}
}
