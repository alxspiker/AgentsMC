package dev.agentsmc;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

import com.google.gson.JsonObject;

/**
 * Server-wide chat for the current plugin run. Public messages are one ordered
 * feed; whispers stay in the intended agent's private buffer. History is
 * captured with no viewer open. Retention is bounded; truncated history is
 * never described as complete.
 */
final class AgentChatLog {
    static final String FEATURE = "agent-chat-log-v1";
    static final int PUBLIC_CAP = 512;
    static final int PRIVATE_CAP = 64;
    static final int HEARTBEAT_PUBLIC = 16;
    static final int HEARTBEAT_PRIVATE = 8;
    static final int DEFAULT_LIMIT = 50;
    static final int MAX_LIMIT = 100;
    static final int MAX_TEXT = 256;

    static final class Message {
        final String id, from, text, scope, kind, to, toAgentId;
        final long seq, at;
        Message(String id, long seq, long at, String from, String text, String scope, String kind, String to, String toAgentId) {
            this.id = id; this.seq = seq; this.at = at; this.from = from; this.text = text;
            this.scope = scope; this.kind = kind; this.to = to; this.toAgentId = toAgentId;
        }
        Map<String, Object> map() {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("id", id); m.put("seq", seq); m.put("at", at);
            m.put("from", from); m.put("text", text); m.put("scope", scope); m.put("kind", kind);
            if (to != null && !to.isBlank()) m.put("to", to);
            if (toAgentId != null && !toAgentId.isBlank()) m.put("toAgentId", toAgentId);
            return m;
        }
    }

    private static final Object LOCK = new Object();
    private static String sessionId = "";
    private static long seq;
    private static final Deque<Message> pub = new ArrayDeque<>();
    private static final Map<String, Deque<Message>> priv = new HashMap<>();
    private static boolean publicComplete = true;
    private static final Map<String, Boolean> privateComplete = new HashMap<>();

    static void reset() {
        synchronized (LOCK) {
            sessionId = UUID.randomUUID().toString().replace("-", "");
            seq = 0;
            pub.clear();
            priv.clear();
            publicComplete = true;
            privateComplete.clear();
        }
    }

    static boolean supports(Iterable<String> features) {
        if (features == null) return false;
        for (String f : features) if (FEATURE.equals(f)) return true;
        return false;
    }

    static boolean supports(com.google.gson.JsonArray features) {
        if (features == null) return false;
        List<String> names = new ArrayList<>();
        for (var v : features) if (v.isJsonPrimitive()) names.add(v.getAsString());
        return supports(names);
    }

    static String sessionId() { synchronized (LOCK) { return sessionId; } }
    static long latestSeq() { synchronized (LOCK) { return seq; } }
    static boolean publicComplete() { synchronized (LOCK) { return publicComplete; } }

    static Message appendPublic(String from, String text, long at) {
        return append(from, text, at, "public", "public", null, null);
    }

    static Message appendPrivate(String from, String text, String to, String toAgentId, long at) {
        return append(from, text, at, "private", "whisper", to, toAgentId);
    }

    private static Message append(String from, String text, long at, String scope, String kind, String to, String toAgentId) {
        String sender = clip(from, 32); if (sender.isEmpty()) sender = "unknown";
        String body = clip(text, MAX_TEXT); if (body.isEmpty()) return null;
        synchronized (LOCK) {
            ensureSession();
            Message m = new Message(UUID.randomUUID().toString().replace("-", ""), ++seq, at, sender, body, scope, kind,
                    to == null || to.isBlank() ? null : clip(to, 32),
                    toAgentId == null || toAgentId.isBlank() ? null : AgentIds.publicId(toAgentId));
            if ("public".equals(scope)) {
                pub.addLast(m);
                while (pub.size() > PUBLIC_CAP) { pub.removeFirst(); publicComplete = false; }
            } else {
                String key = privateKey(m.toAgentId, m.to);
                Deque<Message> q = priv.computeIfAbsent(key, k -> new ArrayDeque<>());
                q.addLast(m);
                while (q.size() > PRIVATE_CAP) { q.removeFirst(); privateComplete.put(key, false); }
                if (!privateComplete.containsKey(key)) privateComplete.put(key, true);
            }
            return m;
        }
    }

    /** Heartbeat cursor: public recent plus this agent's private recent. */
    static Map<String, Object> heartbeat(String agentId) {
        synchronized (LOCK) {
            ensureSession();
            Map<String, Object> out = envelope("public", agentId);
            out.put("recent", maps(tail(pub, HEARTBEAT_PUBLIC)));
            Deque<Message> mine = priv.get(privateKey(agentId, null));
            out.put("privateRecent", maps(tail(mine, HEARTBEAT_PRIVATE)));
            out.put("privateComplete", privateComplete.getOrDefault(privateKey(agentId, null), true));
            out.put("privateTruncated", !privateComplete.getOrDefault(privateKey(agentId, null), true));
            out.put("privateOldestSeq", oldest(mine));
            return out;
        }
    }

    static Map<String, Object> page(String wantSession, String scope, Long after, Long before, int limit, String agentId) {
        if (limit < 1) limit = DEFAULT_LIMIT;
        if (limit > MAX_LIMIT) limit = MAX_LIMIT;
        String which = scope == null || scope.isBlank() ? "public" : scope.trim().toLowerCase(Locale.ROOT);
        if (!which.equals("public") && !which.equals("private"))
            throw new IllegalArgumentException("scope must be public or private");
        synchronized (LOCK) {
            ensureSession();
            Map<String, Object> out = envelope(which, agentId);
            out.put("limit", limit);
            if (wantSession != null && !wantSession.isBlank() && !wantSession.equals(sessionId)) {
                out.put("reset", true);
                out.put("messages", List.of());
                return out;
            }
            out.put("reset", false);
            Deque<Message> source = which.equals("public") ? pub : priv.getOrDefault(privateKey(agentId, null), new ArrayDeque<>());
            List<Message> selected = new ArrayList<>();
            for (Message m : source) {
                if (after != null && m.seq <= after) continue;
                if (before != null && m.seq >= before) continue;
                selected.add(m);
            }
            if (after == null && before == null && selected.size() > limit)
                selected = selected.subList(selected.size() - limit, selected.size());
            else if (before != null && after == null && selected.size() > limit)
                selected = selected.subList(0, limit);
            else if (selected.size() > limit)
                selected = selected.subList(0, limit);
            out.put("messages", maps(selected));
            return out;
        }
    }

    static Map<String, Object> execute(JsonObject input, String agentId) {
        Long after = optionalLong(input, "after");
        Long before = optionalLong(input, "before");
        Long limitVal = optionalLong(input, "limit");
        int limit = limitVal == null ? DEFAULT_LIMIT : limitVal.intValue();
        if (limit < 1 || limit > MAX_LIMIT) throw new IllegalArgumentException("limit must be 1.." + MAX_LIMIT);
        String scope = input.has("scope") ? input.get("scope").getAsString() : "public";
        String want = input.has("serverSessionId") ? input.get("serverSessionId").getAsString() : "";
        return page(want, scope, after, before, limit, agentId);
    }

    private static Map<String, Object> envelope(String scope, String agentId) {
        Deque<Message> source = "private".equals(scope)
                ? priv.getOrDefault(privateKey(agentId, null), new ArrayDeque<>()) : pub;
        boolean complete = "private".equals(scope)
                ? privateComplete.getOrDefault(privateKey(agentId, null), true) : publicComplete;
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("serverSessionId", sessionId);
        out.put("scope", scope);
        out.put("latestSeq", seq);
        out.put("oldestSeq", oldest(source));
        out.put("complete", complete);
        out.put("truncated", !complete);
        return out;
    }

    private static void ensureSession() {
        if (sessionId == null || sessionId.isBlank())
            sessionId = UUID.randomUUID().toString().replace("-", "");
    }

    private static String privateKey(String agentId, String fallbackName) {
        if (agentId != null && !agentId.isBlank()) return AgentIds.publicId(agentId);
        if (fallbackName != null && !fallbackName.isBlank()) return "name:" + fallbackName.toLowerCase(Locale.ROOT);
        return "unknown";
    }

    private static long oldest(Deque<Message> q) {
        return q == null || q.isEmpty() ? 0 : q.getFirst().seq;
    }

    private static List<Message> tail(Deque<Message> q, int n) {
        if (q == null || q.isEmpty() || n <= 0) return List.of();
        List<Message> all = new ArrayList<>(q);
        return all.subList(Math.max(0, all.size() - n), all.size());
    }

    private static List<Map<String, Object>> maps(List<Message> messages) {
        List<Map<String, Object>> out = new ArrayList<>(messages.size());
        for (Message m : messages) out.add(m.map());
        return out;
    }

    private static String clip(String raw, int max) {
        if (raw == null) return "";
        String t = raw.trim();
        return t.length() <= max ? t : t.substring(0, max);
    }

    private static Long optionalLong(JsonObject input, String key) {
        if (input == null || !input.has(key) || input.get(key).isJsonNull()) return null;
        try {
            if (input.get(key).isJsonPrimitive() && input.get(key).getAsJsonPrimitive().isNumber())
                return input.get(key).getAsLong();
            String s = input.get(key).getAsString().trim();
            if (s.isEmpty()) return null;
            return Long.parseLong(s);
        } catch (RuntimeException e) {
            throw new IllegalArgumentException(key + " must be an integer sequence");
        }
    }

    private AgentChatLog() {}
}
