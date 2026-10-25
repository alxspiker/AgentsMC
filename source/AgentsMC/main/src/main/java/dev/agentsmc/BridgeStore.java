package dev.agentsmc;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.nio.charset.StandardCharsets;
import java.util.Set;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

/** Durable credentials and action receipts. An interrupted world action is never replayed. */
final class BridgeStore {
    private final Path file;
    private final JsonObject root;

    BridgeStore(Path file) throws IOException {
        this.file = file;
        if (Files.exists(file)) {
            root = JsonParser.parseString(Files.readString(file)).getAsJsonObject();
            if (!root.keySet().equals(Set.of("agents", "actions")) || !root.get("agents").isJsonObject()
                    || !root.get("actions").isJsonObject()) throw new IOException("Invalid bridge-state.json schema");
            for (String id : agents().keySet()) {
                BridgeProtocol.localId(id);
                AgentCredential.digest(agents().get(id).getAsString());
            }
        } else {
            root = new JsonObject();
            root.add("agents", new JsonObject());
            root.add("actions", new JsonObject());
            save();
        }
    }

    JsonObject agents() { return root.getAsJsonObject("agents"); }
    private JsonObject actions() { return root.getAsJsonObject("actions"); }

    String register(String id) throws IOException {
        BridgeProtocol.localId(id);
        if (!agents().has(id)) {
            agents().addProperty(id, AgentCredential.mint());
            save(); // Bind identity before creating its world body.
        }
        return agents().get(id).getAsString();
    }

    String credential(String id) {
        BridgeProtocol.localId(id);
        if (!agents().has(id)) throw new IllegalArgumentException("No local credential binding for registered agent");
        return agents().get(id).getAsString();
    }

    JsonObject previous(String id, String fingerprint) {
        if (!actions().has(id)) return null;
        JsonObject entry = actions().getAsJsonObject(id);
        if (!fingerprint.equals(BridgeProtocol.string(entry, "fingerprint")))
            throw new IllegalArgumentException("Action ID was reused with a different body or payload");
        if (entry.has("result")) return entry.getAsJsonObject("result");
        return AgentRestart.cancelled(null);
    }

    boolean hasResult(String id) {
        if (!actions().has(id)) return false;
        return actions().getAsJsonObject(id).has("result");
    }

    /** Finish a pre-restart queue item without executing it. */
    void cancelIncomplete(String id, String fingerprint, JsonObject result) throws IOException {
        if (hasResult(id)) return;
        if (!actions().has(id)) begin(id, fingerprint);
        complete(id, result);
    }

    void begin(String id, String fingerprint) throws IOException {
        if (actions().has(id)) throw new IllegalStateException("Action already has a receipt");
        JsonObject entry = new JsonObject();
        entry.addProperty("fingerprint", fingerprint);
        actions().add(id, entry);
        save();
    }

    void complete(String id, JsonObject result) throws IOException {
        if (!actions().has(id)) throw new IllegalStateException("Missing started action receipt");
        actions().getAsJsonObject(id).add("result", result);
        save();
    }

    void prune(Set<String> queuedIds) throws IOException {
        boolean changed = actions().keySet().removeIf(id -> !queuedIds.contains(id));
        if (changed) save();
    }

    private void save() {
        try {
        Path temporary = file.resolveSibling(file.getFileName() + ".tmp");
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
        } catch (IOException failure) { throw new IllegalStateException("Cannot persist durable bridge state", failure); }
    }
}
