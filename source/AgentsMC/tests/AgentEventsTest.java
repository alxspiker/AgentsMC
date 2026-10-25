package dev.agentsmc;

import java.nio.file.Files;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;

/** Offline: batching, retry-same-IDs, uniqueness across persist/restart. */
public final class AgentEventsTest {
    public static void main(String[] args) throws Exception {
        AgentEvents.resetForTest();
        batching();
        retryIdempotency();
        uniquenessAcrossRestart();
        dispositions();
        catalogContract();
        hyphenatedPath();
        chatGate();
        catalogOnConnect();
        pluginUpdated();
        AgentEvents.resetForTest();
        PluginChangelog.resetForTest();
        System.out.println("PASS: event batching, retry idempotency, unique IDs across restart, catalog on connect, plugin.updated dedupe");
    }

    private static void batching() {
        AgentEvents.Queue q = new AgentEvents.Queue();
        for (int i = 0; i < 70; i++)
            check(AgentEvents.enqueue(q, "chat.received", 1_000 + i, AgentEvents.data("text", "m" + i, "from", "A")) != null, "enqueue " + i);
        List<AgentEvents.Record> first = AgentEvents.takeBatch(q.pending, 100);
        check(first.size() == 64, "batch cap is 64, got " + first.size());
        JsonObject body = AgentEvents.requestBody(null, q.pending);
        check(body.has("events") && body.getAsJsonArray("events").size() == 64, "request batch 64");
        check(AgentEvents.utf8(body.toString()) <= AgentEvents.MAX_REQUEST_BYTES, "batch under 256KB");

        AgentEvents.Queue fat = new AgentEvents.Queue();
        Map<String, Object> big = AgentEvents.data("text", "x".repeat(12_000), "from", "A");
        for (int i = 0; i < 20; i++) AgentEvents.enqueue(fat, "chat.received", 2_000 + i, big);
        JsonObject catalog = new JsonObject();
        catalog.addProperty("pad", "y".repeat(80_000));
        JsonObject shrunk = AgentEvents.requestBody(catalog, fat.pending);
        check(shrunk.has("catalog") && shrunk.has("events"), "catalog plus some events");
        check(shrunk.getAsJsonArray("events").size() < 20, "request shrinks to 256KB");
        check(AgentEvents.utf8(shrunk.toString()) <= AgentEvents.MAX_REQUEST_BYTES, "shrunk request size");
        try {
            AgentEvents.requestBody(null, List.of());
            throw new AssertionError("empty request must fail");
        } catch (IllegalArgumentException expected) { }
    }

    private static void retryIdempotency() throws Exception {
        AgentEvents.resetForTest();
        var path = Files.createTempDirectory("agentsmc-events-").resolve("agent-events.json");
        AgentEvents.attach(new AgentEventStore(path));
        String agent = "00000000-0000-4000-8000-000000000001";
        List<String> ids = new ArrayList<>();
        for (int i = 0; i < 3; i++)
            ids.add(AgentEvents.emit(agent, "chat.received", AgentEvents.data("text", "n" + i, "from", "A")).id);
        AgentEvents.Job first = AgentEvents.takeJob(agent);
        check(first != null && first.ids.equals(ids), "first job IDs: " + first.ids);
        String payload = first.body.getAsJsonArray("events").toString();
        AgentEvents.finish(agent, first, AgentEvents.disposition(503));
        check(AgentEvents.pending(agent).size() == 3, "5xx keeps pending");
        AgentEvents.Job retry = AgentEvents.takeJob(agent);
        check(retry.ids.equals(first.ids), "retry uses the same IDs");
        check(retry.body.getAsJsonArray("events").toString().equals(payload), "retry body is identical");
        AgentEvents.finish(agent, retry, AgentEvents.disposition(401));
        check(AgentEvents.pending(agent).size() == 3, "401 does not ack");
        AgentEvents.Job again = AgentEvents.takeJob(agent);
        check(again.ids.equals(ids), "401 retry still same IDs");
        AgentEvents.finish(agent, again, AgentEvents.disposition(200));
        Set<String> left = new HashSet<>();
        for (AgentEvents.Record r : AgentEvents.pending(agent)) left.add(r.id);
        for (String id : ids) check(!left.contains(id), "acked ID still pending: " + id);
        AgentEvents.Job afterAck = AgentEvents.takeJob(agent);
        if (afterAck != null && !afterAck.ids.isEmpty())
            throw new AssertionError("acked events were republished: " + afterAck.ids);

        AgentEvents.emit(agent, "player.joined", AgentEvents.data("name", "Zed"));
        AgentEvents.Job conflict = AgentEvents.takeJob(agent);
        AgentEvents.finish(agent, conflict, AgentEvents.disposition(409));
        Set<String> after409 = new HashSet<>();
        for (AgentEvents.Record r : AgentEvents.pending(agent)) after409.add(r.id);
        for (String id : conflict.ids) check(!after409.contains(id), "409 drop left " + id);
    }

    private static void uniquenessAcrossRestart() throws Exception {
        AgentEvents.resetForTest();
        var path = Files.createTempDirectory("agentsmc-events-").resolve("agent-events.json");
        AgentEvents.attach(new AgentEventStore(path));
        String dashed = "00000000-0000-4000-8000-00000000000a";
        String dashless = dashed.replace("-", "");
        AgentEvents.Record a = AgentEvents.emit(dashless, "chat.received", AgentEvents.data("text", "hi", "from", "A"));
        AgentEvents.Record b = AgentEvents.emit(dashless, "player.joined", AgentEvents.data("name", "B"));
        check(a.id.length() <= 128 && b.id.length() <= 128, "id length");
        check(a.id.startsWith(AgentEvents.session(dashed) + ":"), "session prefix");
        check(!a.id.equals(b.id), "ids differ in one session");
        String session = AgentEvents.session(dashed);
        check(AgentEvents.pending(dashed).size() == 2, "pending persisted in memory");
        AgentEvents.resetForTest();
        AgentEvents.attach(new AgentEventStore(path));
        check(session.equals(AgentEvents.session(dashed)), "session survives restart");
        check(AgentEvents.pending(dashed).size() == 2, "pending survives restart");
        Set<String> before = new HashSet<>();
        for (AgentEvents.Record r : AgentEvents.pending(dashed)) before.add(r.id);
        check(before.contains(a.id) && before.contains(b.id), "same IDs after reload");
        AgentEvents.Record c = AgentEvents.emit(dashed, "player.left", AgentEvents.data("name", "B"));
        check(c.id.startsWith(session + ":"), "counter continues on same session");
        check(!before.contains(c.id), "restart must not reuse an ID");
        Set<String> all = new HashSet<>(before);
        check(all.add(c.id), "third ID unique");
    }

    private static void dispositions() {
        check("ack".equals(AgentEvents.disposition(200)) && "ack".equals(AgentEvents.disposition(204)), "2xx ack");
        check("drop".equals(AgentEvents.disposition(400)) && "drop".equals(AgentEvents.disposition(409)), "400/409 drop");
        check("retry".equals(AgentEvents.disposition(401)) && "retry".equals(AgentEvents.disposition(404)), "401/404 retry");
        check("retry".equals(AgentEvents.disposition(500)) && "retry".equals(AgentEvents.disposition(0)), "5xx/IO retry");
        check(AgentEvents.statusLog(401, "a", "no").contains("401"), "401 log");
        check(AgentEvents.statusLog(404, "a", "no").contains("404"), "404 log");
        check(AgentEvents.statusLog(409, "a", "no").contains("409"), "409 log");
        check(AgentEvents.statusLog(400, "a", "no").contains("400"), "400 log");
    }

    private static void catalogContract() {
        JsonObject catalog = AgentEvents.catalog();
        check(catalog.has("events") && catalog.get("events").isJsonArray(), "catalog.events");
        JsonArray events = catalog.getAsJsonArray("events");
        Set<String> types = new HashSet<>();
        for (var v : events) {
            JsonObject e = v.getAsJsonObject();
            String type = e.get("type").getAsString();
            check(types.add(type), "duplicate catalog type " + type);
            check(e.has("description") && !e.get("description").getAsString().isBlank(), "description " + type);
            check(e.has("dataSchema") && e.get("dataSchema").isJsonObject(), "dataSchema " + type);
            check(e.get("available").getAsBoolean(), "available " + type);
        }
        for (String need : List.of("chat.received", "player.joined", "player.left", "agent.damaged",
                "agent.died", "agent.respawned", "combat.engaged", "combat.ended", "action.completed",
                "action.failed", "goto.arrived", "goto.stuck", "nav.recovered_from_air", "health.low", "hunger.low", "mob.nearby",
                "item.picked_up", "plugin.updated"))
            check(types.contains(need), "catalog missing " + need);
        JsonObject updated = null;
        for (var v : events) {
            JsonObject e = v.getAsJsonObject();
            if ("plugin.updated".equals(e.get("type").getAsString())) updated = e;
        }
        check(updated != null && updated.getAsJsonObject("dataSchema").getAsJsonObject("properties").has("changes"),
                "plugin.updated schema");
        check(AgentEvents.supports(List.of("agent-action-catalog-v1", "agent-events-v1")), "feature");
        check(!AgentEvents.supports(List.of("agent-action-catalog-v1")), "feature off");
        AgentEvents.Queue q = new AgentEvents.Queue();
        AgentEvents.sampleNav(q, "navigating", "", 1.0, 2.0, 3.0, 10);
        AgentEvents.sampleNav(q, "arrived", "", 1.0, 2.0, 3.0, 20);
        check(q.pending.stream().anyMatch(r -> "goto.arrived".equals(r.type)), "goto arrived");
        AgentEvents.sampleNav(q, "failed", "Arrival was not verified; body is 3.7 blocks from target", 1.0, 2.0, 3.0, 25);
        check(q.pending.stream().noneMatch(r -> "goto.stuck".equals(r.type)), "arrived must not be followed by stuck");
        AgentEvents.Queue fail = new AgentEvents.Queue();
        AgentEvents.sampleNav(fail, "navigating", "", 1.0, 2.0, 3.0, 10);
        AgentEvents.sampleNav(fail, "failed", "timeout", 1.0, 2.0, 3.0, 30);
        check(fail.pending.stream().anyMatch(r -> "goto.stuck".equals(r.type)), "goto stuck from navigating");
        AgentEvents.sampleCombat(q, true, "self_defense", "ZOMBIE", "", 40);
        AgentEvents.sampleCombat(q, true, "self_defense", "ZOMBIE", "", 41);
        check(q.pending.stream().filter(r -> "combat.engaged".equals(r.type)).count() == 1, "combat debounce");
        AgentEvents.sampleCombat(q, false, "self_defense", "ZOMBIE", "killed", 50);
        check(q.pending.stream().anyMatch(r -> "combat.ended".equals(r.type)), "combat ended");
    }

    private static void hyphenatedPath() {
        check(AgentEvents.hyphenated("00000000-0000-4000-8000-000000000001")
                .equals("00000000-0000-4000-8000-000000000001"), "already dashed");
        check(AgentEvents.hyphenated("00000000000040008000000000000001")
                .equals("00000000-0000-4000-8000-000000000001"), "dashless to dashed");
        AgentEvents.resetForTest();
        AgentEvents.emit("00000000000040008000000000000001", "player.joined", AgentEvents.data("name", "A"));
        AgentEvents.Job job = AgentEvents.takeJob("00000000000040008000000000000001");
        check(job.agentId.equals("00000000-0000-4000-8000-000000000001"), "job path id is hyphenated");
    }

    private static void chatGate() {
        check(AgentEvents.chatRelevant("whisper", "hi", "Bob", 99), "whisper always");
        check(AgentEvents.chatRelevant("public", "hey Bob", "Bob", 99), "name mention");
        check(AgentEvents.chatRelevant("public", "hello", "Bob", 8), "nearby");
        check(!AgentEvents.chatRelevant("public", "hello", "Bob", 64), "far public");
    }

    /** Catalog posts on connect for every linked agent, hyphenated path id, even with no pending events. */
    private static void catalogOnConnect() {
        AgentEvents.resetForTest();
        String dashless = "0a1b2c3d4e5f4a6b8c7d0e1f2a3b4c5d";
        String hyphen = AgentEvents.hyphenated(dashless);
        check(AgentEvents.ensureLinked(List.of(dashless)) == 1, "ensureLinked creates queue");
        check(AgentEvents.catalogDue(hyphen), "new queue catalog due");
        AgentEvents.onConnected(List.of(hyphen, "1111aaaa2222411183334444bbbbcccc"));
        check(AgentEvents.catalogDue(hyphen), "connect marks Miner");
        check(AgentEvents.catalogDue("1111aaaa-2222-4111-8333-4444bbbbcccc"), "connect marks Helper hyphenated");
        AgentEvents.Job job = AgentEvents.takeJob(dashless);
        check(job != null && job.catalog && job.ids.isEmpty(), "catalog-only job on connect");
        check(job.agentId.equals(hyphen), "POST path is hyphenated: " + job.agentId);
        check(job.body.has("catalog") && !job.body.has("events"), "catalog without events");
        String ok = AgentEvents.publishLog(dashless, job, 200, "ack");
        check(ok.contains("catalog") && ok.contains(hyphen) && ok.contains("HTTP 200"), "success log: " + ok);
        String fail = AgentEvents.publishLog(hyphen, job, 404, "retry");
        check(fail.contains("404") && fail.contains(hyphen), "failure log: " + fail);
        AgentEvents.finish(hyphen, job, "ack");
        check(!AgentEvents.catalogDue(hyphen), "catalog acked");
        check(AgentEvents.takeJob(hyphen) == null, "no republish after ack");
    }

    /** Current version only; last-announced persists; id plugin.updated:<version> never duplicates across restart. */
    private static void pluginUpdated() throws Exception {
        PluginChangelog.resetForTest();
        AgentEvents.resetForTest();
        var path = Files.createTempDirectory("agentsmc-events-").resolve("agent-events.json");
        AgentEvents.attach(new AgentEventStore(path));
        String a = "00000000-0000-4000-8000-0000000000aa";
        String b = "00000000-0000-4000-8000-0000000000bb";
        PluginChangelog.Notes notes = PluginChangelog.notes("0.6.29");
        check(notes != null && notes.changes.size() >= 5, "bundled 0.6.29 notes: " + (notes == null ? "null" : notes.changes));
        check(notes.changes.stream().anyMatch(s -> s.toLowerCase().contains("classcastexception") || s.toLowerCase().contains("char[]")),
                "0.6.28 say/dig CCE in notes: " + notes.changes);
        check(notes.changes.stream().anyMatch(s -> s.toLowerCase().contains("goto") && s.toLowerCase().contains("20")),
                "0.6.28 20+ goto in notes");
        check(notes.changes.stream().anyMatch(s -> s.toLowerCase().contains("catalog")), "events catalog in notes");
        check(notes.changes.stream().anyMatch(s -> s.toLowerCase().contains("follow") && s.toLowerCase().contains("name")),
                "follow/whisper by name in notes");
        check(notes.changes.stream().anyMatch(s -> s.toLowerCase().contains("respawn") || s.toLowerCase().contains("bed")),
                "bed respawn in notes");
        check(notes.actionsChanged.contains("say") && notes.actionsChanged.contains("goto"), "actionsChanged: " + notes.actionsChanged);
        check(PluginChangelog.notes("0.6.28") == null, "never backfill 0.6.28 as its own announce");

        check(AgentEvents.announceRunningVersion(List.of(a, b), "0.6.29", notes) == 2, "first announce both agents");
        check("0.6.29".equals(AgentEvents.announcedVersion(a)) && "0.6.29".equals(AgentEvents.announcedVersion(b)), "persisted announced");
        AgentEvents.Record first = AgentEvents.pending(a).stream().filter(r -> "plugin.updated".equals(r.type)).findFirst().orElse(null);
        check(first != null && "plugin.updated:0.6.29".equals(first.id), "deterministic id: " + (first == null ? null : first.id));
        check("0.6.29".equals(first.data.get("version")) && "".equals(first.data.get("previousVersion")), "data versions: " + first.data);
        check(first.data.get("changes") instanceof List<?> ch && ch.size() == notes.changes.size(), "changes list");
        check(AgentEvents.announceRunningVersion(List.of(a, b), "0.6.29", notes) == 0, "same version is a no-op");
        check(AgentEvents.pending(a).stream().filter(r -> "plugin.updated".equals(r.type)).count() == 1, "no live duplicate");
        check(AgentEvents.announceRunningVersion(List.of(a), "0.6.28", notes) == 0, "version/notes mismatch is not announced");

        AgentEvents.resetForTest();
        AgentEvents.attach(new AgentEventStore(path));
        check("0.6.29".equals(AgentEvents.announcedVersion(a)), "announcedVersion survives restart");
        check(AgentEvents.pending(a).stream().anyMatch(r -> "plugin.updated:0.6.29".equals(r.id)), "pending id survives restart");
        AgentEvents.Record reloaded = AgentEvents.pending(a).stream().filter(r -> "plugin.updated".equals(r.type)).findFirst().orElseThrow();
        check(reloaded.data.get("changes") instanceof List<?> live && live.contains(notes.changes.get(0)),
                "changelog list survives restart: " + reloaded.data.get("changes"));
        check(AgentEvents.announceRunningVersion(List.of(a, b), "0.6.29", notes) == 0, "restart must not enqueue again");
        check(AgentEvents.pending(a).stream().filter(r -> "plugin.updated".equals(r.type)).count() == 1, "one pending after restart announce");

        AgentEvents.Job job = AgentEvents.takeJob(a);
        check(job != null && job.ids.contains("plugin.updated:0.6.29"), "job uses deterministic id");
        AgentEvents.finish(a, job, "ack");
        check(AgentEvents.pending(a).stream().noneMatch(r -> "plugin.updated".equals(r.type)), "acked");
        AgentEvents.resetForTest();
        AgentEvents.attach(new AgentEventStore(path));
        check("0.6.29".equals(AgentEvents.announcedVersion(a)), "announced after ack+restart");
        check(AgentEvents.announceRunningVersion(List.of(a), "0.6.29", notes) == 0, "acked version never republishes");
        check(AgentEvents.pending(a).stream().noneMatch(r -> "plugin.updated".equals(r.type)), "no backfill after ack");

        PluginChangelog.Notes next = new PluginChangelog.Notes("0.6.31", List.of("next"), List.of("wait"), List.of("say"));
        check(AgentEvents.announceRunningVersion(List.of(a), "0.6.31", next) == 1, "later version announces once");
        AgentEvents.Record upgrade = AgentEvents.pending(a).stream().filter(r -> "plugin.updated".equals(r.type)).findFirst().orElseThrow();
        check("plugin.updated:0.6.31".equals(upgrade.id), "new version id");
        check("0.6.29".equals(upgrade.data.get("previousVersion")), "previousVersion is last announced");
        check(AgentEvents.announceRunningVersion(List.of(a), "0.6.31", next) == 0, "0.6.31 deduped");
    }

    private static void check(boolean ok, String message) {
        if (!ok) throw new AssertionError(message);
    }
}
