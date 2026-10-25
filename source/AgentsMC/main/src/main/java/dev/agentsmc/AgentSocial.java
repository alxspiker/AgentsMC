package dev.agentsmc;

import java.util.*;

/** Per-agent chat inbox (public chat, agent say, whispers). Pure data; server thread only. */
final class AgentSocial {
    static final int CAPACITY = 30;
    record Message(long seq, long time, String from, String kind, String text) {
        Map<String,Object> map() { Map<String,Object> m = new LinkedHashMap<>(); m.put("from", from); m.put("kind", kind); m.put("text", text); m.put("time", time); return m; }
    }
    private static final Map<UUID, Deque<Message>> inbox = new HashMap<>();
    private static final Map<UUID, Long> readUpTo = new HashMap<>();
    private static final Map<UUID, String> lastWhisperFrom = new HashMap<>();
    private static long seq;

    static synchronized void deliver(UUID agent, String from, String kind, String text, long now) {
        Deque<Message> q = inbox.computeIfAbsent(agent, k -> new ArrayDeque<>());
        q.addLast(new Message(++seq, now, from, kind, text));
        while (q.size() > CAPACITY) q.removeFirst();
        if ("whisper".equals(kind)) lastWhisperFrom.put(agent, from);
    }
    /** Messages not yet read; marks them read. */
    static synchronized List<Map<String,Object>> unread(UUID agent) {
        long after = readUpTo.getOrDefault(agent, 0L); List<Map<String,Object>> out = new ArrayList<>();
        for (Message m : inbox.getOrDefault(agent, new ArrayDeque<>())) if (m.seq() > after) out.add(m.map());
        markRead(agent); return out;
    }
    /** Messages not yet read, without marking them read (observe/heartbeat). */
    static synchronized List<Map<String,Object>> peekUnread(UUID agent) {
        long after = readUpTo.getOrDefault(agent, 0L); List<Map<String,Object>> out = new ArrayList<>();
        for (Message m : inbox.getOrDefault(agent, new ArrayDeque<>())) if (m.seq() > after) out.add(m.map());
        return out;
    }
    static synchronized int unreadCount(UUID agent) {
        long after = readUpTo.getOrDefault(agent, 0L); int n = 0;
        for (Message m : inbox.getOrDefault(agent, new ArrayDeque<>())) if (m.seq() > after) n++;
        return n;
    }
    /** Entire retained inbox (up to 30) with an unread flag; marks all read. */
    static synchronized List<Map<String,Object>> all(UUID agent) {
        long after = readUpTo.getOrDefault(agent, 0L); List<Map<String,Object>> out = new ArrayList<>();
        for (Message m : inbox.getOrDefault(agent, new ArrayDeque<>())) { Map<String,Object> x = m.map(); x.put("unread", m.seq() > after); out.add(x); }
        markRead(agent); return out;
    }
    static synchronized String lastWhisperer(UUID agent) { return lastWhisperFrom.get(agent); }
    private static void markRead(UUID agent) { Deque<Message> q = inbox.get(agent); if (q != null && !q.isEmpty()) readUpTo.put(agent, q.getLast().seq()); }
    static synchronized void clear() { inbox.clear(); readUpTo.clear(); lastWhisperFrom.clear(); }
    private AgentSocial() {}
}
