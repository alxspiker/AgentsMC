package dev.agentsmc;

import java.util.UUID;
import com.google.gson.JsonObject;

/** Exact translation from the hosted queue to credential-bound player actions. */
final class BridgeProtocol {
    static String localId(String siteId) {
        if (!UUID.fromString(siteId).toString().equals(siteId))
            throw new IllegalArgumentException("Expected canonical agent UUID");
        return siteId.replace("-", "");
    }

    static JsonObject action(String action, JsonObject payload, String world) {
        if (action.equals("execute")) {
            if (!payload.has("action") && !payload.has("steps"))
                throw new IllegalArgumentException("execute requires an action or steps");
            return payload.deepCopy();
        }
        if (!AgentActionCatalog.executable(action))
            throw new IllegalArgumentException("Unsupported queued action: " + action);
        for (String key : payload.keySet()) {
            if (AgentActionProgram.FORBIDDEN.contains(key) || key.equals("action"))
                throw new IllegalArgumentException("Body or world selector forbidden: " + key);
        }
        JsonObject input = new JsonObject();
        input.addProperty("action", action);
        for (var entry : payload.entrySet()) input.add(entry.getKey(), entry.getValue());
        if (action.equals("say")) {
            if (input.has("message") && !input.has("text")) input.addProperty("text", string(input, "message"));
            if (!input.has("text") || !input.get("text").isJsonPrimitive())
                throw new IllegalArgumentException("say requires text or message");
            String message = input.get("text").getAsString();
            if (message.isEmpty() || message.length() > 256 || message.chars().anyMatch(Character::isISOControl))
                throw new IllegalArgumentException("text must contain 1 to 256 printable characters");
        }
        if (action.equals("craft") && input.has("material") && !input.has("item")) input.add("item", input.get("material"));
        if (action.equals("whisper") || action.equals("reply")) {
            if (input.has("message") && !input.has("text")) input.add("text", input.get("message"));
            if (input.has("name") && !input.has("player") && !input.has("to")) input.add("player", input.get("name"));
        }
        if (action.equals("place")) {
            String raw = input.has("block") ? string(input, "block") : input.has("item") ? string(input, "item")
                    : input.has("material") ? string(input, "material") : null;
            if (raw != null) input.addProperty("block", AgentPlacement.blockKey(raw));
        }
        if (input.has("x") || input.has("y") || input.has("z")) input.addProperty("world", world);
        return input;
    }

    static boolean removedTarget(int status, String path, String body) {
        if (status != 404 || !(path.equals("/api/server/agent") || path.startsWith("/api/server/actions/") || path.startsWith("/api/server/viewer/"))) return false;
        JsonObject error = com.google.gson.JsonParser.parseString(body).getAsJsonObject();
        String expected = path.equals("/api/server/agent") ? "Agent not found."
                : path.startsWith("/api/server/viewer/") ? "Viewer session is no longer active."
                : "Queued action not found.";
        return error.has("error") && expected.equals(string(error, "error"));
    }

    static String string(JsonObject value, String key) {
        if (!value.has(key) || !value.get(key).isJsonPrimitive() || !value.get(key).getAsJsonPrimitive().isString())
            throw new IllegalArgumentException("Expected string: " + key);
        return value.get(key).getAsString();
    }
}
