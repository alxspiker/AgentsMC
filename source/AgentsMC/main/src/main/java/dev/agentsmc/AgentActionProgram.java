package dev.agentsmc;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

/** Fixed, bounded generic envelope. Site/MCP does not need a new schema for each gameplay verb. */
final class AgentActionProgram {
    static final Set<String> FORBIDDEN = Set.of("agent", "agentId", "agent_id", "bodyUuid", "bodyId",
            "npcId", "accessToken", "credential", "serverToken", "world", "registration", "userId");

    static final Set<String> ENVELOPE_KEYS = Set.of(
            "steps", "continueOnError", "background", "async", "preempt", "queue", "abortIf");

    /**
     * Actions that do not hijack body navigation/combat and can execute concurrently
     * on the server thread while a background {@code goto}, {@code harvest}, or
     * multi-step movement program is in progress.
     */
    static final Set<String> CONCURRENT_SAFE_ACTIONS = Set.of(
            "observe", "inventory", "block", "scan", "scan_entities", "scan_box", "region", "build_plan", "preflight", "heightmap", "walkmap", "survey", "worldmap", "time",
            "inspect_block", "inspect_entity", "inspect_item", "inspect_container", "inspect_interface",
            "chat_log", "locate", "recipe", "say", "whisper", "reply",
            "select", "equip", "move_item", "split_stack", "swap", "sort",
            "craft", "eat", "drink", "heal", "sprint", "sneak", "shield", "look", "journal", "waypoint",
            "emote", "trade_offers",
            "feedback", "feedback_list", "feedback_resolve");

    /** Fast 100ms navigation check interval (2 server ticks) for low-latency step transitions. */
    static final long NAV_POLL_MS = 100;

    interface StepExecutor { Map<String,Object> execute(JsonObject step, int index); }

    static Map<String,Object> execute(AgentsMC plugin, String credential, JsonObject payload, String world) {
        return execute(payload, world, (step, index) -> plugin.executeAuthenticated(credential, step));
    }

    static Map<String,Object> execute(JsonObject payload, String world, StepExecutor executor) {
        if (payload == null) throw new IllegalArgumentException("execute requires JSON payload");
        if (payload.has("steps")) {
            if (payload.has("continueOnError") && !payload.get("continueOnError").isJsonPrimitive())
                throw new IllegalArgumentException("continueOnError must be boolean");
            if (payload.has("continueOnError") && !payload.getAsJsonPrimitive("continueOnError").isBoolean())
                throw new IllegalArgumentException("continueOnError must be boolean");
            for (String boolKey : List.of("background", "async", "preempt", "queue")) {
                if (payload.has(boolKey) && !isBooleanish(payload.get(boolKey)))
                    throw new IllegalArgumentException(boolKey + " must be boolean");
            }
            if (payload.has("abortIf") && (!payload.get("abortIf").isJsonPrimitive() || !payload.getAsJsonPrimitive("abortIf").isString()))
                throw new IllegalArgumentException("abortIf must be a string");
            if (payload.keySet().stream().anyMatch(key -> !ENVELOPE_KEYS.contains(key)))
                throw new IllegalArgumentException("Program envelope permits only steps, continueOnError, background, async, preempt, queue, and abortIf");
            if (!payload.get("steps").isJsonArray()) throw new IllegalArgumentException("steps must be an array");
            JsonArray steps = payload.getAsJsonArray("steps");
            if (steps.size() < 1 || steps.size() > 12) throw new IllegalArgumentException("Program must contain 1 to 12 steps");
            List<Map<String,Object>> results = new ArrayList<>();
            boolean continueOnError = flag(payload, "continueOnError", false);
            boolean halted = false;
            String haltReason = null;
            for (int index = 0; index < steps.size(); index++) {
                JsonElement element = steps.get(index);
                String action = element.isJsonObject() && element.getAsJsonObject().has("action")
                        && element.getAsJsonObject().get("action").isJsonPrimitive()
                        ? element.getAsJsonObject().get("action").getAsString() : "unknown";
                Map<String,Object> result;
                if (halted) {
                    result = new java.util.LinkedHashMap<>();
                    result.put("ok", false); result.put("action", action); result.put("step", index + 1);
                    result.put("error", "Step " + (index + 1) + " (" + action + ") was not attempted; " + haltReason);
                    results.add(result);
                    continue;
                }
                try {
                    if (!element.isJsonObject()) throw new IllegalArgumentException("Each step must be a JSON object");
                    JsonObject safe = normalize(element.getAsJsonObject(), world);
                    Map<String,Object> raw = executor.execute(safe, index);
                    result = new java.util.LinkedHashMap<>(raw == null ? Map.of("ok", false, "error", "Step produced no result") : raw);
                    if (!result.containsKey("action")) result.put("action", action);
                    if (!result.containsKey("ok")) result.put("ok", false);
                    result = new java.util.LinkedHashMap<>(AgentErrors.ensureError(result));
                    result.put("step", index + 1);
                    result.put("action", action);
                } catch (RuntimeException failure) {
                    result = new java.util.LinkedHashMap<>(AgentErrors.failure(action, failure));
                    result.put("step", index + 1);
                }
                if (AgentErrors.blank(result.get("error")) && !Boolean.TRUE.equals(result.get("ok")))
                    result.put("error", "Step " + (index + 1) + " (" + action + ") failed with no detail reported");
                results.add(result);
                if (Boolean.TRUE.equals(result.get("ok")) && "wait".equals(action)) pauseWait(waitSeconds(result, element));
                if (!Boolean.TRUE.equals(result.get("ok")) && !continueOnError) {
                    halted = true;
                    Object err = result.get("error");
                    haltReason = "previous step " + (index + 1) + " (" + action + ") failed: " +
                            (AgentErrors.blank(err) ? "no detail reported" : err);
                }
            }
            boolean success = results.size() == steps.size() && results.stream().allMatch(v -> Boolean.TRUE.equals(v.get("ok")));
            int succeeded = (int) results.stream().filter(v -> Boolean.TRUE.equals(v.get("ok"))).count();
            int skipped = (int) results.stream().filter(v -> String.valueOf(v.get("error")).contains("was not attempted")).count();
            int attempted = results.size() - skipped;
            int failed = attempted - succeeded;
            Map<String,Object> summary = new java.util.LinkedHashMap<>();
            summary.put("ok", success);
            summary.put("attempted", attempted);
            summary.put("succeeded", succeeded);
            summary.put("failed", failed);
            summary.put("skipped", skipped);
            summary.put("partial", succeeded > 0 && !success);
            summary.put("results", results);
            if (!success && succeeded > 0) {
                String firstFail = null;
                for (Map<String,Object> row : results) {
                    if (Boolean.TRUE.equals(row.get("ok"))) continue;
                    if (String.valueOf(row.get("error")).contains("was not attempted")) continue;
                    firstFail = "Step " + row.get("step") + " (" + row.get("action") + ") failed: " +
                            (AgentErrors.blank(row.get("error")) ? "no detail reported" : row.get("error"));
                    break;
                }
                summary.put("error", "Program partially succeeded (" + succeeded + " ok, " + failed + " failed"
                        + (firstFail == null ? ")" : "; " + firstFail + ")"));
            }
            return AgentErrors.ensureError(summary);
        }
        String action = payload.has("action") && payload.get("action").isJsonPrimitive() ? payload.get("action").getAsString() : "unknown";
        try {
            Map<String,Object> single = AgentErrors.ensureError(executor.execute(normalize(payload,world), 0));
            if (Boolean.TRUE.equals(single.get("ok")) && "wait".equals(action)) pauseWait(waitSeconds(single, payload));
            return single;
        }
        catch (RuntimeException failure) { return AgentErrors.failure(action, failure); }
    }

    /** True when this action name does not conflict with an in-progress navigation or background program. */
    static boolean concurrentSafeAction(String action) {
        return action != null && CONCURRENT_SAFE_ACTIONS.contains(action);
    }

    /**
     * True when the queued action or {@code execute} payload can run immediately
     * in parallel with an active movement program on the same agent body.
     */
    static boolean concurrentSafe(String queuedAction, JsonObject payload) {
        if (queuedAction == null) return false;
        if (!"execute".equals(queuedAction)) {
            if ("map".equals(queuedAction)) return isMapStatusOnly(payload);
            return concurrentSafeAction(queuedAction);
        }
        if (payload == null) return false;
        if (payload.has("steps") && payload.get("steps").isJsonArray()) {
            JsonArray steps = payload.getAsJsonArray("steps");
            if (steps.isEmpty()) return false;
            for (JsonElement el : steps) {
                if (!el.isJsonObject()) return false;
                JsonObject step = el.getAsJsonObject();
                String verb = step.has("action") && step.get("action").isJsonPrimitive() ? step.get("action").getAsString() : "";
                if ("map".equals(verb)) {
                    if (!isMapStatusOnly(step)) return false;
                } else if (!concurrentSafeAction(verb)) {
                    return false;
                }
            }
            return true;
        }
        String inner = payload.has("action") && payload.get("action").isJsonPrimitive() ? payload.get("action").getAsString() : "";
        if ("map".equals(inner)) return isMapStatusOnly(payload);
        return concurrentSafeAction(inner);
    }

    private static boolean isMapStatusOnly(JsonObject o) {
        if (o == null || !o.has("mode") || !o.get("mode").isJsonPrimitive()) return true;
        return "status".equalsIgnoreCase(o.get("mode").getAsString());
    }

    /** True when a program or step requests non-blocking background execution. */
    static boolean isBackground(JsonObject o) {
        if (o == null) return false;
        if (flag(o, "background", false) || flag(o, "async", false)) return true;
        if (o.has("await") && isBooleanish(o.get("await"))) return !flag(o, "await", true);
        return false;
    }

    /** True when a new action or program should preempt (cancel) an in-progress program for the same agent. */
    static boolean isPreempt(String queuedAction, JsonObject payload) {
        if ("stop".equals(queuedAction)) return true;
        if (payload == null) return false;
        if (flag(payload, "preempt", false)) return true;
        if ("execute".equals(queuedAction) && payload.has("action") && payload.get("action").isJsonPrimitive()) {
            String inner = payload.get("action").getAsString();
            if ("stop".equals(inner)) return true;
        }
        return false;
    }

    /** True when a program explicitly requests pipelined queueing behind the current program. */
    static boolean isQueued(JsonObject payload) {
        return payload != null && flag(payload, "queue", false);
    }

    /** Optional reactive interrupt condition name (e.g. "chat", "whisper", "threat", "damage", "any"). */
    static String abortIfRule(JsonObject envelope, JsonObject step) {
        if (step != null && step.has("abortIf") && step.get("abortIf").isJsonPrimitive())
            return step.get("abortIf").getAsString().trim().toLowerCase(Locale.ROOT);
        if (envelope != null && envelope.has("abortIf") && envelope.get("abortIf").isJsonPrimitive())
            return envelope.get("abortIf").getAsString().trim().toLowerCase(Locale.ROOT);
        return "";
    }

    /** Evaluates whether an active navigation wait should abort early due to a real-time world/chat event. */
    static String checkInterrupt(String rule, boolean threatActive, boolean unreadWhisper, boolean unreadChat, boolean damaged) {
        if (rule == null || rule.isBlank() || "none".equals(rule)) return null;
        for (String token : rule.split("[,|\\s]+")) {
            String t = token.trim().toLowerCase(Locale.ROOT);
            if (t.isEmpty()) continue;
            switch (t) {
                case "any", "all" -> {
                    if (threatActive) return "threat";
                    if (damaged) return "damage";
                    if (unreadWhisper) return "whisper";
                    if (unreadChat) return "chat";
                }
                case "threat", "hostile", "combat", "self_defense" -> {
                    if (threatActive) return "threat";
                }
                case "damage", "hurt", "damaged" -> {
                    if (damaged) return "damage";
                }
                case "whisper", "private_chat" -> {
                    if (unreadWhisper) return "whisper";
                }
                case "chat", "message" -> {
                    if (unreadWhisper || unreadChat) return unreadWhisper ? "whisper" : "chat";
                }
            }
        }
        return null;
    }

    private static boolean isBooleanish(JsonElement el) {
        if (el == null || !el.isJsonPrimitive()) return false;
        var p = el.getAsJsonPrimitive();
        if (p.isBoolean()) return true;
        if (p.isString()) {
            String s = p.getAsString().trim().toLowerCase(Locale.ROOT);
            return "true".equals(s) || "false".equals(s);
        }
        return false;
    }

    static boolean flag(JsonObject o, String key, boolean def) {
        if (o == null || !o.has(key) || !o.get(key).isJsonPrimitive()) return def;
        var p = o.getAsJsonPrimitive(key);
        if (p.isBoolean()) return p.getAsBoolean();
        if (p.isString()) return Boolean.parseBoolean(p.getAsString().trim());
        return def;
    }

    static int waitSeconds(Map<String,Object> result, JsonElement step) {
        Object n = result != null ? result.get("seconds") : null;
        if (n instanceof Number num) return num.intValue();
        if (step != null && step.isJsonObject() && step.getAsJsonObject().has("seconds") && step.getAsJsonObject().get("seconds").isJsonPrimitive()) {
            try { return (int) Math.round(step.getAsJsonObject().get("seconds").getAsDouble()); }
            catch (RuntimeException ignored) {}
        }
        return 0;
    }

    static void pauseWait(int seconds) {
        if (seconds < 1 || seconds > 30) return;
        try {
            if (org.bukkit.Bukkit.getServer() != null && org.bukkit.Bukkit.isPrimaryThread())
                throw new IllegalStateException("wait cannot sleep on the server thread");
        } catch (NoClassDefFoundError | ExceptionInInitializerError | NullPointerException ignored) {}
        try { Thread.sleep(seconds * 1000L); }
        catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("wait was interrupted", interrupted);
        }
    }

    /** Blocks a program until a goto settles. arrived and arrived_near are success so later steps run. */
    static Map<String,Object> awaitNavigation(Map<String,Object> outcome, java.util.function.Supplier<Map<String,Object>> snapshot,
                                              Runnable stop, long timeoutNanos, long pollMillis) {
        return awaitNavigation(outcome, snapshot, stop, () -> null, timeoutNanos, pollMillis);
    }

    /** Blocks a program until a goto settles or an optional real-time interrupt fires. */
    static Map<String,Object> awaitNavigation(Map<String,Object> outcome, java.util.function.Supplier<Map<String,Object>> snapshot,
                                              Runnable stop, java.util.function.Supplier<String> interruptProbe,
                                              long timeoutNanos, long pollMillis) {
        long deadline=System.nanoTime()+timeoutNanos;
        while(true){
            Map<String,Object> navigation=snapshot.get();
            String state=String.valueOf(navigation.get("status"));
            if(AgentNavigation.success(state)){
                Map<String,Object> completed=new java.util.LinkedHashMap<>(outcome);
                completed.put("ok",true);completed.put("state",state);completed.put("status",state);completed.put("navigation",navigation);return completed;
            }
            if(state.equals("failed")||state.equals("stopped")||state.equals("idle")||state.equals("blocked_shore")){
                Object reason=navigation.get("reason");
                Map<String,Object> failed=new java.util.LinkedHashMap<>();
                failed.put("ok",false);failed.put("action","goto");failed.put("state",state);failed.put("status",state);
                failed.put("error",reason==null||String.valueOf(reason).isEmpty()?"Navigation ended before arrival":String.valueOf(reason));
                failed.put("navigation",navigation);
                return failed;
            }
            if(interruptProbe!=null){
                String interruptedBy=interruptProbe.get();
                if(interruptedBy!=null&&!interruptedBy.isBlank()){
                    stop.run();
                    Map<String,Object> aborted=new java.util.LinkedHashMap<>();
                    aborted.put("ok",false);aborted.put("action","goto");aborted.put("state","interrupted");aborted.put("status","interrupted");
                    aborted.put("interruptedBy",interruptedBy);
                    aborted.put("error","Navigation interrupted by "+interruptedBy);
                    aborted.put("navigation",navigation);
                    return aborted;
                }
            }
            if(System.nanoTime()>=deadline){stop.run();return Map.of("ok",false,"action","goto","error","Navigation timed out after "+TimeUnit.NANOSECONDS.toSeconds(timeoutNanos)+" seconds","navigation",navigation);}
            try{Thread.sleep(pollMillis);}catch(InterruptedException interrupted){Thread.currentThread().interrupt();throw new IllegalStateException("Program navigation wait was interrupted",interrupted);}
        }
    }

    static JsonObject normalize(JsonObject request, String world) {
        if (request == null || !request.has("action") || !request.get("action").isJsonPrimitive()
                || !request.getAsJsonPrimitive("action").isString())
            throw new IllegalArgumentException("Action must be a string");
        String action = request.get("action").getAsString();
        if (!AgentActionCatalog.executable(action)) throw new IllegalArgumentException("Unsupported action: " + action);
        for (String key : request.keySet()) {
            if (FORBIDDEN.contains(key)) throw new IllegalArgumentException("Body or world selector forbidden: " + key);
            JsonElement value = request.get(key);
            if (value == null || value.isJsonNull())
                throw new IllegalArgumentException("Field '" + key + "' for action " + action + " must not be null");
            if (value.isJsonArray())
                throw new IllegalArgumentException("Field '" + key + "' for action " + action + " must be a scalar (string, number, or boolean), not an array");
            if (value.isJsonObject())
                throw new IllegalArgumentException("Field '" + key + "' for action " + action + " must be a scalar (string, number, or boolean), not an object");
        }
        JsonObject normalized = request.deepCopy();
        if (normalized.has("x") || normalized.has("y") || normalized.has("z"))
            normalized.addProperty("world", world);
        return normalized;
    }

    private AgentActionProgram() {}
}
