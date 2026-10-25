package dev.agentsmc;

import java.nio.file.Files;
import java.util.Set;
import com.google.gson.JsonObject;

/** 0.6.20: idle after restart; pre-boot bridge queue is cancelled, not executed. */
public final class RestartTest {
    public static void main(String[] args) throws Exception {
        check(AgentRestart.REASON.equals("server_restart"), "reason constant");
        JsonObject cancelled = AgentRestart.cancelled("goto");
        check(Boolean.FALSE.equals(cancelled.get("ok").getAsBoolean()), "cancel not ok");
        check(cancelled.get("cancelled").getAsBoolean(), "cancelled flag");
        check("cancelled".equals(cancelled.get("status").getAsString()), "status");
        check("server_restart".equals(cancelled.get("reason").getAsString()), "reason");
        check("server_restart".equals(cancelled.get("error").getAsString()), "error is server_restart");
        check("goto".equals(cancelled.get("action").getAsString()), "action preserved");
        check("server_restart".equals(AgentRestart.cancelled(null).get("error").getAsString()), "null action");

        Set<String> first = AgentRestart.captureQueue(null, Set.of("old-goto", "old-fight"));
        check(first.contains("old-goto") && first.contains("old-fight") && first.size() == 2, "first poll captured");
        Set<String> later = AgentRestart.captureQueue(first, Set.of("old-goto", "new-observe"));
        check(later == first, "later polls keep the boot snapshot");
        check(AgentRestart.stale(first, "old-goto") && AgentRestart.stale(first, "old-fight"), "pre-restart ids are stale");
        check(!AgentRestart.stale(first, "new-observe"), "post-restart ids are fresh");
        check(!AgentRestart.stale(null, "old-goto"), "no snapshot yet");

        check("cancel".equals(AgentRestart.disposition(false, true)), "stale incomplete is cancelled");
        check("replay".equals(AgentRestart.disposition(true, true)), "finished receipt is replayed");
        check("execute".equals(AgentRestart.disposition(false, false)), "fresh id executes");
        check("replay".equals(AgentRestart.disposition(true, false)), "completed fresh is still a receipt");

        var path = Files.createTempDirectory("agentsmc-restart-").resolve("state.json");
        String agent = "00000000-0000-4000-8000-000000000001";
        BridgeStore store = new BridgeStore(path);
        store.register(agent);
        String fp = agent + "\n" + "goto" + "\n" + "{}";
        store.begin("queued-before-boot", fp);
        check(!store.hasResult("queued-before-boot"), "begun action has no result yet");
        check("server_restart".equals(store.previous("queued-before-boot", fp).get("error").getAsString()),
                "interrupted receipt is server_restart");
        store.cancelIncomplete("queued-before-boot", fp, AgentRestart.cancelled("goto"));
        check(store.hasResult("queued-before-boot"), "cancel persisted");
        JsonObject saved = store.previous("queued-before-boot", fp);
        check("server_restart".equals(saved.get("reason").getAsString()) && !saved.get("ok").getAsBoolean(),
                "stored cancel: " + saved);
        store.cancelIncomplete("never-begun", agent + "\nobserve\n{}", AgentRestart.cancelled("observe"));
        check("server_restart".equals(new BridgeStore(path).previous("never-begun", agent + "\nobserve\n{}").get("error").getAsString()),
                "durable across store reload");

        check(AgentProtect.decode("Steve;16;2") != null, "legacy persist still parses");
        check(!AgentProtect.running("stopped") && !AgentProtect.running("idle"), "stopped is not running");

        System.out.println("PASS: idle restart cancels pre-boot queue with server_restart");
    }

    private static void check(boolean ok, String message) {
        if (!ok) throw new AssertionError(message);
    }
}
