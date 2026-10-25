package dev.agentsmc;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

/**
 * Durable event sessions and unacked batches. Kept out of {@link BridgeStore}
 * because that schema is only {@code agents} + {@code actions}.
 */
final class AgentEventStore {
    private final Path file;

    AgentEventStore(Path file) {
        this.file = file;
    }

    Map<String, AgentEvents.Queue> load() throws IOException {
        Map<String, AgentEvents.Queue> out = new LinkedHashMap<>();
        if (!Files.exists(file)) return out;
        JsonObject root = JsonParser.parseString(Files.readString(file)).getAsJsonObject();
        if (!root.has("agents") || !root.get("agents").isJsonObject())
            throw new IOException("Invalid agent-events.json schema");
        for (var entry : root.getAsJsonObject("agents").entrySet()) {
            if (!entry.getValue().isJsonObject()) continue;
            String id = AgentEvents.hyphenated(entry.getKey());
            if (id.isBlank()) continue;
            out.put(id, readQueue(entry.getValue().getAsJsonObject()));
        }
        return out;
    }

    void save(Map<String, AgentEvents.Queue> queues) {
        try {
            JsonObject root = new JsonObject();
            JsonObject agents = new JsonObject();
            if (queues != null) {
                for (var entry : queues.entrySet()) {
                    String id = AgentEvents.hyphenated(entry.getKey());
                    if (id.isBlank() || entry.getValue() == null) continue;
                    agents.add(id, writeQueue(entry.getValue()));
                }
            }
            root.add("agents", agents);
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
            try (FileChannel directory = FileChannel.open(file.toAbsolutePath().getParent(), StandardOpenOption.READ)) {
                directory.force(true);
            }
        } catch (IOException failure) {
            throw new IllegalStateException("Cannot persist agent event batches", failure);
        }
    }

    static AgentEvents.Queue readQueue(JsonObject o) {
        AgentEvents.Queue q = new AgentEvents.Queue();
        if (o.has("session") && o.get("session").isJsonPrimitive())
            q.session = o.get("session").getAsString();
        if (o.has("next") && o.get("next").isJsonPrimitive())
            q.next = Math.max(1, o.get("next").getAsLong());
        if (o.has("catalogDue") && o.get("catalogDue").isJsonPrimitive())
            q.catalogDue = o.get("catalogDue").getAsBoolean();
        if (o.has("announcedVersion") && o.get("announcedVersion").isJsonPrimitive())
            q.announcedVersion = o.get("announcedVersion").getAsString();
        if (o.has("pending") && o.get("pending").isJsonArray()) {
            for (var value : o.getAsJsonArray("pending")) {
                if (!value.isJsonObject()) continue;
                AgentEvents.Record r = readRecord(value.getAsJsonObject());
                if (r != null) q.pending.add(r);
            }
        }
        if (q.next <= 1 && !q.pending.isEmpty())
            q.next = q.pending.size() + 1;
        return q;
    }

    static JsonObject writeQueue(AgentEvents.Queue q) {
        JsonObject o = new JsonObject();
        o.addProperty("session", q.session);
        o.addProperty("next", q.next);
        o.addProperty("catalogDue", q.catalogDue);
        if (q.announcedVersion != null && !q.announcedVersion.isBlank())
            o.addProperty("announcedVersion", q.announcedVersion);
        JsonArray pending = new JsonArray();
        for (AgentEvents.Record r : q.pending) pending.add(r.json());
        o.add("pending", pending);
        return o;
    }

    static AgentEvents.Record readRecord(JsonObject o) {
        if (o == null) return null;
        AgentEvents.Record r = new AgentEvents.Record();
        r.id = o.has("id") && o.get("id").isJsonPrimitive() ? o.get("id").getAsString() : "";
        r.type = o.has("type") && o.get("type").isJsonPrimitive() ? o.get("type").getAsString() : "";
        r.at = o.has("at") && o.get("at").isJsonPrimitive() ? Math.max(0, o.get("at").getAsLong()) : 0;
        Map<String, Object> data = new LinkedHashMap<>();
        if (o.has("data") && o.get("data").isJsonObject()) {
            for (var entry : o.getAsJsonObject("data").entrySet()) {
                if (entry.getValue().isJsonArray()) {
                    List<Object> list = new ArrayList<>();
                    for (var item : entry.getValue().getAsJsonArray()) {
                        if (!item.isJsonPrimitive()) continue;
                        var p = item.getAsJsonPrimitive();
                        if (p.isNumber()) list.add(p.getAsNumber());
                        else if (p.isBoolean()) list.add(p.getAsBoolean());
                        else list.add(p.getAsString());
                    }
                    data.put(entry.getKey(), list);
                    continue;
                }
                if (!entry.getValue().isJsonPrimitive()) continue;
                var p = entry.getValue().getAsJsonPrimitive();
                if (p.isNumber()) data.put(entry.getKey(), p.getAsNumber());
                else if (p.isBoolean()) data.put(entry.getKey(), p.getAsBoolean());
                else data.put(entry.getKey(), p.getAsString());
            }
        }
        r.data = data;
        return r;
    }
}
