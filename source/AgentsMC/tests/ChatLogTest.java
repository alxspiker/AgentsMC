package dev.agentsmc;

import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import com.google.gson.JsonObject;

/** Offline server-wide chat log: session, paging, truncation, public/private split. */
public final class ChatLogTest {
    public static void main(String[] args) {
        AgentChatLog.reset();
        String session = AgentChatLog.sessionId();
        check(session != null && session.matches("[0-9a-f]{32}"), "session id: " + session);
        check(AgentChatLog.sessionId().equals(session), "session reused for the run");
        check(AgentChatLog.publicComplete(), "fresh log is complete");
        check(AgentChatLog.supports(List.of("agent-chat-log-v1", "agent-events-v1")), "feature flag");
        check(!AgentChatLog.supports(List.of("agent-events-v1")), "feature off");

        AgentChatLog.Message a = AgentChatLog.appendPublic("Player1", "hello world", 1_000);
        AgentChatLog.Message b = AgentChatLog.appendPublic("Builder Bot", "on my way", 1_100);
        check(a != null && b != null && b.seq == a.seq + 1, "monotonic seq");
        check(a.id != null && !a.id.equals(b.id) && a.id.matches("[0-9a-f]{32}"), "stable unique ids");
        check("Player1".equals(a.from) && "hello world".equals(a.text) && a.at == 1_000, "public fields");
        check("public".equals(a.scope) && "public".equals(a.kind), "public scope");

        AgentChatLog.Message whisper = AgentChatLog.appendPrivate("Player1", "secret ore", "Builder Bot", "2222bbbb3333422293335555ccccdddd", 1_200);
        check(whisper != null && "private".equals(whisper.scope) && "whisper".equals(whisper.kind), "private kind");
        check("Builder Bot".equals(whisper.to) && "2222bbbb3333422293335555ccccdddd".equals(whisper.toAgentId), "private recipient");
        check(whisper.seq == b.seq + 1, "shared seq space");

        Map<String, Object> pub = AgentChatLog.page(session, "public", null, null, 50, "2222bbbb3333422293335555ccccdddd");
        check(session.equals(pub.get("serverSessionId")), "page session");
        check(Boolean.TRUE.equals(pub.get("complete")) && Boolean.FALSE.equals(pub.get("truncated")), "public complete");
        @SuppressWarnings("unchecked") List<Map<String, Object>> pubMsgs = (List<Map<String, Object>>) pub.get("messages");
        check(pubMsgs.size() == 2, "public page size: " + pubMsgs.size());
        check("hello world".equals(pubMsgs.get(0).get("text")) && "on my way".equals(pubMsgs.get(1).get("text")), "public order");
        for (Map<String, Object> m : pubMsgs) check("public".equals(m.get("scope")), "no whisper in public");

        Map<String, Object> priv = AgentChatLog.page(session, "private", null, null, 50, "2222bbbb3333422293335555ccccdddd");
        @SuppressWarnings("unchecked") List<Map<String, Object>> privMsgs = (List<Map<String, Object>>) priv.get("messages");
        check(privMsgs.size() == 1 && "secret ore".equals(privMsgs.get(0).get("text")), "private to intended agent");
        Map<String, Object> other = AgentChatLog.page(session, "private", null, null, 50, "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa");
        @SuppressWarnings("unchecked") List<Map<String, Object>> otherMsgs = (List<Map<String, Object>>) other.get("messages");
        check(otherMsgs.isEmpty(), "private not visible to another agent");

        Map<String, Object> newer = AgentChatLog.page(session, "public", a.seq, null, 50, null);
        @SuppressWarnings("unchecked") List<Map<String, Object>> afterA = (List<Map<String, Object>>) newer.get("messages");
        check(afterA.size() == 1 && "on my way".equals(afterA.get(0).get("text")), "after seq fetches newer public");

        AgentChatLog.appendPublic("Player1", "three", 1_300);
        Map<String, Object> older = AgentChatLog.page(session, "public", null, whisper.seq + 2, 1, null);
        @SuppressWarnings("unchecked") List<Map<String, Object>> beforePage = (List<Map<String, Object>>) older.get("messages");
        check(beforePage.size() == 1 && "hello world".equals(beforePage.get(0).get("text")), "before pages oldest of the older window: " + beforePage);

        Map<String, Object> reset = AgentChatLog.page("deadbeefdeadbeefdeadbeefdeadbeef", "public", null, null, 50, null);
        check(Boolean.TRUE.equals(reset.get("reset")), "session mismatch resets");
        @SuppressWarnings("unchecked") List<Map<String, Object>> resetMsgs = (List<Map<String, Object>>) reset.get("messages");
        check(resetMsgs.isEmpty(), "mismatch returns no messages");
        check(session.equals(reset.get("serverSessionId")), "mismatch reports current session");

        AgentChatLog.reset();
        String overflowSession = AgentChatLog.sessionId();
        Set<String> ids = new HashSet<>();
        AgentChatLog.Message first = null, last = null;
        for (int i = 0; i < AgentChatLog.PUBLIC_CAP + 5; i++) {
            AgentChatLog.Message m = AgentChatLog.appendPublic("Player1", "m" + i, 2_000 + i);
            if (first == null) first = m;
            last = m;
            check(ids.add(m.id), "duplicate id " + m.id);
        }
        check(!AgentChatLog.publicComplete(), "overflow is not complete");
        Map<String, Object> clipped = AgentChatLog.page(overflowSession, "public", null, null, 100, null);
        check(Boolean.FALSE.equals(clipped.get("complete")) && Boolean.TRUE.equals(clipped.get("truncated")),
                "truncated marker: " + clipped);
        long oldest = ((Number) clipped.get("oldestSeq")).longValue();
        check(oldest > first.seq, "oldestSeq after drop: " + oldest + " first=" + first.seq);
        check(((Number) clipped.get("latestSeq")).longValue() == last.seq, "latestSeq is last assigned");
        @SuppressWarnings("unchecked") List<Map<String, Object>> kept = (List<Map<String, Object>>) clipped.get("messages");
        check(kept.size() == 100, "newest page limit");
        check(!kept.get(0).get("text").equals("m0"), "dropped head is not claimed present");

        AgentChatLog.reset();
        AgentChatLog.appendPublic("Player1", "surface", 3_000);
        AgentChatLog.appendPrivate("Player1", "only steve", "Builder Bot", "2222bbbb3333422293335555ccccdddd", 3_100);
        Map<String, Object> beat = AgentChatLog.heartbeat("2222bbbb3333422293335555ccccdddd");
        check(AgentChatLog.sessionId().equals(beat.get("serverSessionId")), "heartbeat session");
        @SuppressWarnings("unchecked") List<Map<String, Object>> recent = (List<Map<String, Object>>) beat.get("recent");
        check(recent.size() == 1 && "surface".equals(recent.get(0).get("text")), "heartbeat public recent");
        @SuppressWarnings("unchecked") List<Map<String, Object>> privRecent = (List<Map<String, Object>>) beat.get("privateRecent");
        check(privRecent.size() == 1 && "only steve".equals(privRecent.get(0).get("text")), "heartbeat private scoped");
        Map<String, Object> otherBeat = AgentChatLog.heartbeat("bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb");
        @SuppressWarnings("unchecked") List<Map<String, Object>> otherPriv = (List<Map<String, Object>>) otherBeat.get("privateRecent");
        check(otherPriv.isEmpty(), "heartbeat private empty for other agent");

        JsonObject req = new JsonObject();
        req.addProperty("after", 0);
        req.addProperty("limit", 10);
        req.addProperty("scope", "public");
        req.addProperty("serverSessionId", AgentChatLog.sessionId());
        Map<String, Object> exec = AgentChatLog.execute(req, "2222bbbb3333422293335555ccccdddd");
        @SuppressWarnings("unchecked") List<Map<String, Object>> execMsgs = (List<Map<String, Object>>) exec.get("messages");
        check(execMsgs.size() == 1, "execute public page");

        check(AgentChatLog.appendPublic("Player1", "   ", 4_000) == null, "blank text is not stored");
        check(AgentActionCatalog.advertised("chat_log"), "chat_log advertised");

        System.out.println("PASS: chat log session, public/private split, after/before paging, truncation marker, heartbeat scope");
    }

    static void check(boolean ok, String message) {
        if (!ok) throw new AssertionError(message);
    }
}
