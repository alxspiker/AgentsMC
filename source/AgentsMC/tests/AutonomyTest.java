package dev.agentsmc;

import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

/**
 * Offline verification of 0.6.41 real-time concurrency, 100ms action polling,
 * non-blocking background execution, preemption/pipelining, reactive interrupts,
 * and System-1 micro-autonomy while the LLM thinks.
 */
public final class AutonomyTest {
    public static void main(String[] args) {
        // 1. Fast-lane polling & navigation cadence
        check(AgentBridge.FAST_POLL_MS == 100, "Fast-lane action poll must be 100ms (10 Hz)");
        check(AgentBridge.HEARTBEAT_INTERVAL_MS == 1_000, "Telemetry heartbeat stays 1000ms (1 Hz)");
        check(AgentBridge.BODY_WAIT_MS == 10_000, "offline body budget stays 10s");
        check("run".equals(AgentBridge.bodyReady(true, 0)), "online body runs now");
        check("defer".equals(AgentBridge.bodyReady(false, 0)), "offline body defers instead of sleeping the poller");
        check("defer".equals(AgentBridge.bodyReady(false, 9_999)), "offline body still defers under 10s");
        check("fail".equals(AgentBridge.bodyReady(false, 10_000)), "offline body fails after 10s");
        check(AgentActionProgram.NAV_POLL_MS == 100, "Program navigation wait check is 100ms");

        // 2. Concurrent-safe action classification (thinking/talking/crafting/observing while walking)
        for (String safe : List.of("say", "whisper", "reply", "observe", "inventory", "scan", "scan_entities",
                "heightmap", "walkmap", "survey", "worldmap", "time", "scan_box", "region", "build_plan", "preflight", "craft", "equip", "select", "move_item", "swap", "sort", "eat",
                "drink", "heal", "shield", "emote", "trade_offers",
                "chat_log", "locate", "inspect_item", "inspect_block", "inspect_entity", "feedback")) {
            check(AgentActionProgram.concurrentSafeAction(safe), safe + " must be concurrent-safe while moving");
            check(AgentActionProgram.concurrentSafe(safe, new JsonObject()), "direct " + safe + " must be concurrent-safe");
            JsonObject execSingle = JsonParser.parseString("{\"action\":\"" + safe + "\"}").getAsJsonObject();
            check(AgentActionProgram.concurrentSafe("execute", execSingle), "execute " + safe + " must be concurrent-safe");
        }
        for (String exclusive : List.of("goto", "harvest", "fight", "kill", "protect", "follow", "collect", "dig", "break", "place", "respawn",
                "give", "throw", "interact", "trade", "brew", "enchant", "anvil", "smith", "stonecut", "move", "portal", "retreat")) {
            check(!AgentActionProgram.concurrentSafeAction(exclusive), exclusive + " is body-exclusive");
            JsonObject execEx = JsonParser.parseString("{\"action\":\"" + exclusive + "\"}").getAsJsonObject();
            check(!AgentActionProgram.concurrentSafe("execute", execEx), "execute " + exclusive + " must not be concurrent-safe");
        }
        JsonObject mapStatus = JsonParser.parseString("{\"action\":\"map\",\"mode\":\"status\"}").getAsJsonObject();
        JsonObject mapSet = JsonParser.parseString("{\"action\":\"map\",\"mode\":\"set\",\"behavior\":\"auto\"}").getAsJsonObject();
        check(AgentActionProgram.concurrentSafe("execute", mapStatus), "map status is concurrent-safe");
        check(!AgentActionProgram.concurrentSafe("execute", mapSet), "map set is not concurrent-safe");

        JsonObject concurrentSteps = JsonParser.parseString(
                "{\"steps\":[{\"action\":\"observe\"},{\"action\":\"craft\",\"item\":\"oak_planks\",\"amount\":4},{\"action\":\"say\",\"text\":\"Walking and crafting!\"}]}"
        ).getAsJsonObject();
        check(AgentActionProgram.concurrentSafe("execute", concurrentSteps), "All-concurrent steps program must run in parallel with movement");

        JsonObject mixedSteps = JsonParser.parseString(
                "{\"steps\":[{\"action\":\"goto\",\"x\":10,\"y\":64,\"z\":10},{\"action\":\"say\",\"text\":\"Here\"}]}"
        ).getAsJsonObject();
        check(!AgentActionProgram.concurrentSafe("execute", mixedSteps), "Program containing goto is exclusive unless backgrounded");

        // 3. Non-blocking background execution, preemption, and pipelining flags
        check(AgentActionProgram.isBackground(JsonParser.parseString("{\"background\":true,\"steps\":[]}").getAsJsonObject()), "background:true");
        check(AgentActionProgram.isBackground(JsonParser.parseString("{\"async\":true,\"steps\":[]}").getAsJsonObject()), "async:true");
        check(AgentActionProgram.isBackground(JsonParser.parseString("{\"action\":\"goto\",\"await\":false}").getAsJsonObject()), "await:false");
        check(!AgentActionProgram.isBackground(JsonParser.parseString("{\"background\":false,\"steps\":[]}").getAsJsonObject()), "background:false");

        check(AgentActionProgram.isPreempt("stop", new JsonObject()), "stop always preempts");
        check(AgentActionProgram.isPreempt("execute", JsonParser.parseString("{\"action\":\"stop\"}").getAsJsonObject()), "execute stop preempts");
        check(AgentActionProgram.isPreempt("execute", JsonParser.parseString("{\"preempt\":true,\"steps\":[{\"action\":\"goto\",\"x\":0,\"y\":64,\"z\":0}]}").getAsJsonObject()), "preempt:true preempts");
        check(!AgentActionProgram.isPreempt("execute", mixedSteps), "default steps program does not preempt");
        check(AgentActionProgram.isQueued(JsonParser.parseString("{\"queue\":true,\"steps\":[{\"action\":\"observe\"}]}").getAsJsonObject()), "queue:true");

        // 4. Reactive abortIf interrupts on awaitNavigation
        check("whisper".equals(AgentActionProgram.checkInterrupt("whisper", false, true, true, false)), "whisper interrupt");
        check(AgentActionProgram.checkInterrupt("whisper", false, false, true, false) == null, "public chat does not trigger whisper-only interrupt");
        check("chat".equals(AgentActionProgram.checkInterrupt("chat", false, false, true, false)), "chat interrupt");
        check("threat".equals(AgentActionProgram.checkInterrupt("threat", true, false, false, false)), "threat interrupt");
        check("damage".equals(AgentActionProgram.checkInterrupt("damage", false, false, false, true)), "damage interrupt");
        check("threat".equals(AgentActionProgram.checkInterrupt("any", true, true, true, true)), "any interrupt priority");
        check(AgentActionProgram.checkInterrupt("none", true, true, true, true) == null, "none disables interrupt");

        boolean[] stopped = {false};
        Map<String, Object> interrupted = AgentActionProgram.awaitNavigation(
                Map.of("ok", true, "action", "goto"),
                () -> Map.of("status", "moving"),
                () -> stopped[0] = true,
                () -> "whisper",
                TimeUnit.SECONDS.toNanos(5),
                1
        );
        check(Boolean.FALSE.equals(interrupted.get("ok")) && stopped[0], "Interrupted goto must stop navigation");
        check("interrupted".equals(interrupted.get("status")) && "whisper".equals(interrupted.get("interruptedBy")),
                "Interrupted goto must report status=interrupted and interruptedBy=whisper: " + interrupted);

        // 5. Program envelope accepts new real-time orchestration flags
        JsonObject env = JsonParser.parseString(
                "{\"background\":true,\"preempt\":false,\"queue\":true,\"abortIf\":\"chat\",\"continueOnError\":true,\"steps\":[{\"action\":\"observe\"}]}"
        ).getAsJsonObject();
        Map<String, Object> envResult = AgentActionProgram.execute(env, "world", (step, idx) -> Map.of("ok", true, "action", "observe"));
        check(Boolean.TRUE.equals(envResult.get("ok")), "Program envelope with real-time flags failed: " + envResult);

        // 6. Live program status tracking
        AgentBridge.resetProgramSnapshotsForTest();
        check(Boolean.FALSE.equals(AgentBridge.programSnapshot("0a1b2c3d-4e5f-4a6b-8c7d-0e1f2a3b4c5d").get("active")), "idle program snapshot");
        AgentBridge.updateProgramStatus("0a1b2c3d-4e5f-4a6b-8c7d-0e1f2a3b4c5d", "prog-1", true, "running", 2, 5, "goto");
        Map<String, Object> live = AgentBridge.programSnapshot("0a1b2c3d4e5f4a6b8c7d0e1f2a3b4c5d");
        check(Boolean.TRUE.equals(live.get("active")) && Boolean.TRUE.equals(live.get("background"))
                && Integer.valueOf(2).equals(live.get("step")) && "goto".equals(live.get("currentAction")),
                "live program snapshot: " + live);
        AgentBridge.resetProgramSnapshotsForTest();

        // 7. System-1 Micro-Autonomy rules (water buoyancy, proactive auto-eat, mode normalization, social gaze)
        AgentAutonomy.resetForTest();
        check(AgentAutonomy.validMode("auto") && AgentAutonomy.validMode("autonomy")
                && AgentAutonomy.validMode("night_home") && AgentAutonomy.validMode("passive")
                && AgentAutonomy.validMode("none") && !AgentAutonomy.validMode("invalid"), "valid autonomy modes");
        check("auto".equals(AgentAutonomy.normalizeMode("autonomy")) && "passive".equals(AgentAutonomy.normalizeMode("off")), "normalizeMode");
        check(AgentAutonomy.activeForMode("auto") && AgentAutonomy.activeForMode("night_home")
                && !AgentAutonomy.activeForMode("passive"), "activeForMode");

        check(AgentAutonomy.shouldAutoEat("auto", false, 20.0, 20.0, 14), "hungry at food<=14 auto-eats");
        check(!AgentAutonomy.shouldAutoEat("auto", false, 20.0, 20.0, 18), "full health and food 18 does not auto-eat");
        check(AgentAutonomy.shouldAutoEat("auto", false, 12.0, 20.0, 18), "hurt at health 12 and food 18 auto-eats for regen");
        check(!AgentAutonomy.shouldAutoEat("auto", true, 12.0, 20.0, 10), "never interrupt active melee swing to auto-eat");
        check(!AgentAutonomy.shouldAutoEat("passive", false, 10.0, 20.0, 6), "passive mode disables auto-eat");

        check(AgentAutonomy.shouldFloatInWater(true, false, null, 62.0), "idle in water floats");
        check(AgentAutonomy.shouldFloatInWater(true, false, 63.0, 62.0), "climbing out of water floats");
        check(!AgentAutonomy.shouldFloatInWater(true, false, 58.0, 62.0), "diving toward deeper waypoint does not float");
        check(!AgentAutonomy.shouldFloatInWater(true, true, null, 62.0), "riding boat does not apply player swim float");
        check(AgentAutonomy.waterBuoyancyVy(-0.15) == AgentAutonomy.WATER_FLOAT_VY, "buoyancy counters sinking");

        check(AgentAutonomy.shouldUpdateSocialGaze("auto", null, 10_000), "gaze updates when no focus");
        check(!AgentAutonomy.shouldUpdateSocialGaze("auto", Map.of("action", "dig", "since", 9_000L), 10_000), "fresh work focus is kept");
        check(AgentAutonomy.shouldUpdateSocialGaze("auto", Map.of("action", "dig", "since", 5_000L), 10_000), "stale work focus yields to social gaze");

        if (!AgentAutonomy.staleItemUse(true, "BREAD") || !AgentAutonomy.staleItemUse(true, null))
            throw new AssertionError("fb-20: lingering food use must be cleared");
        if (AgentAutonomy.staleItemUse(true, "SHIELD") || AgentAutonomy.staleItemUse(false, "BREAD"))
            throw new AssertionError("fb-20: shield block and idle hand must be left alone");
        System.out.println("PASS: 100ms action poll, separate telemetry, defer offline body, concurrent actions while moving, background programs, preemption/pipelining, abortIf interrupts, micro-autonomy, 0.7.1, fb-20 stale eat use cleared");
    }

    private static void check(boolean ok, String message) {
        if (!ok) throw new AssertionError(message);
    }
}
