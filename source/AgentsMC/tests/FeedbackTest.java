package dev.agentsmc;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import com.google.gson.JsonObject;

/** Offline feedback store, filters, resolve, and catalog discovery. */
public final class FeedbackTest {
    public static void main(String[] args) throws Exception {
        AgentFeedback.resetForTest();
        Path file = Files.createTempDirectory("agentsmc-fb").resolve("feedback.json");
        AgentFeedback.attach(file);

        JsonObject submit = new JsonObject();
        submit.addProperty("action", "feedback");
        submit.addProperty("agent", "intern");
        submit.addProperty("text", "Step-down goto sat on the ledge");
        submit.addProperty("category", "bug");
        submit.addProperty("context", "0.6.29 play session");
        Map<String, Object> saved = AgentFeedback.submit(null, submit);
        check("fb-1".equals(saved.get("id")), "id " + saved);
        check("open".equals(saved.get("status")), "status open");
        check(Files.exists(file), "persisted");

        JsonObject idea = new JsonObject();
        idea.addProperty("action", "feedback");
        idea.addProperty("agent", "helper");
        idea.addProperty("text", "A status panel would help");
        idea.addProperty("category", "idea");
        AgentFeedback.submit(null, idea);

        JsonObject list = new JsonObject();
        list.addProperty("action", "feedback_list");
        list.addProperty("agent", "console");
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> open = (List<Map<String, Object>>) AgentFeedback.list(list).get("entries");
        check(open.size() == 2, "two open: " + open.size());

        JsonObject resolve = new JsonObject();
        resolve.addProperty("action", "feedback_resolve");
        resolve.addProperty("agent", "console");
        resolve.addProperty("id", "fb-1");
        resolve.addProperty("note", "fixed in 0.6.30");
        Map<String, Object> done = AgentFeedback.resolve(resolve);
        check("done".equals(done.get("status")), "resolved");

        list.addProperty("status", "open");
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> still = (List<Map<String, Object>>) AgentFeedback.list(list).get("entries");
        check(still.size() == 1 && "fb-2".equals(still.get(0).get("id")), "one open remains");

        list.addProperty("status", "done");
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> closed = (List<Map<String, Object>>) AgentFeedback.list(list).get("entries");
        check(closed.size() == 1 && "fb-1".equals(closed.get(0).get("id")), "one done");
        check("fixed in 0.6.30".equals(closed.get(0).get("note")), "note kept");

        list.addProperty("status", "all");
        check(((Number) AgentFeedback.list(list).get("count")).intValue() == 2, "all");

        list.addProperty("since", String.valueOf(System.currentTimeMillis() + 60_000));
        check(((Number) AgentFeedback.list(list).get("count")).intValue() == 0, "since future is empty");

        AgentFeedback.resetForTest();
        AgentFeedback.attach(file);
        JsonObject reload = new JsonObject();
        reload.addProperty("action", "feedback_list");
        reload.addProperty("agent", "console");
        reload.addProperty("status", "all");
        check(((Number) AgentFeedback.list(reload).get("count")).intValue() == 2, "reload");

        try {
            JsonObject bad = new JsonObject();
            bad.addProperty("action", "feedback");
            bad.addProperty("agent", "intern");
            bad.addProperty("text", "x");
            bad.addProperty("category", "explode");
            AgentFeedback.submit(null, bad);
            throw new AssertionError("bad category accepted");
        } catch (IllegalArgumentException expected) {}

        try {
            JsonObject missing = new JsonObject();
            missing.addProperty("action", "feedback");
            missing.addProperty("agent", "intern");
            AgentFeedback.submit(null, missing);
            throw new AssertionError("missing text accepted");
        } catch (IllegalArgumentException expected) {}

        check(AgentActionCatalog.advertised("feedback") && AgentActionCatalog.advertised("feedback_list")
                && AgentActionCatalog.advertised("feedback_resolve"), "catalog advertises feedback");
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> actions = (List<Map<String, Object>>) AgentActionCatalog.snapshot().get("actions");
        Map<String, Object> fb = actions.stream().filter(m -> "feedback".equals(m.get("action"))).findFirst().orElseThrow();
        String desc = String.valueOf(fb.get("description")).toLowerCase();
        check(desc.contains("bug") && desc.contains("feedback.json"), "feedback description: " + desc);
        List<String> lines = AgentFeedback.consoleList("done");
        check(lines.size() == 1 && lines.get(0).contains("fb-1"), "console list: " + lines);

        JsonObject alias = new JsonObject();
        alias.addProperty("action", "feedback_resolve");
        alias.addProperty("agent", "console");
        alias.addProperty("id", "fb-2");
        alias.addProperty("status", "done");
        alias.addProperty("note", "older status alias");
        Map<String, Object> aliased = AgentFeedback.resolve(alias);
        check("done".equals(aliased.get("status")), "status alias: " + aliased);

        System.out.println("PASS: feedback submit/list/resolve, persist, catalog, console list");
    }

    static void check(boolean ok, String message) {
        if (!ok) throw new AssertionError(message);
    }
}
