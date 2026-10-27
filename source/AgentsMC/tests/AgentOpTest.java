package dev.agentsmc;

import java.nio.file.Files;
import java.util.List;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;

/** Offline: op grant persistence, subcommand allow-list, arg parsing, caps, 404 drop rule. */
public final class AgentOpTest {
    public static void main(String[] args) throws Exception {
        java.io.File file = Files.createTempDirectory("agentsmc-op").resolve("ops.yml").toFile();
        AgentOp.resetForTest();
        AgentOp.attach(file);
        check(!AgentOp.isOp("intern"), "not opped by default");
        check(AgentOp.grant("Intern"), "grant");
        check(!AgentOp.grant("intern"), "grant idempotent");
        check(AgentOp.isOp("intern"), "opped");
        AgentOp.attach(file);
        check(AgentOp.isOp("intern") && AgentOp.ops().equals(List.of("intern")), "persisted " + AgentOp.ops());
        check(AgentOp.revoke("intern") && !AgentOp.isOp("intern"), "revoke");
        AgentOp.attach(file);
        check(AgentOp.ops().isEmpty(), "revoke persisted");

        check("Intern Steve".equals(AgentOp.joinName(new String[]{"op", "Intern", "Steve"})), "op name with space");
        check("intern".equals(AgentOp.joinName(new String[]{"deop", "intern"})), "op by id");
        check("".equals(AgentOp.joinName(new String[]{"op"})), "op missing name");
        JsonObject o = new JsonObject();
        o.addProperty("action", "op");
        o.addProperty("command", "Give");
        o.addProperty("args", "Steve  diamond 3");
        check(AgentOp.subcommand(o).equals("give"), "subcommand lower");
        check(AgentOp.args(o).equals(List.of("Steve", "diamond", "3")), "args string " + AgentOp.args(o));
        o.addProperty("args", "\"Hero Steve\" to 'Intern  Steve'");
        check(AgentOp.args(o).equals(List.of("Hero Steve", "to", "Intern Steve")), "quoted names " + AgentOp.args(o));
        java.util.function.Predicate<String> known = n -> n.equalsIgnoreCase("Hero Steve") || n.equalsIgnoreCase("Intern Steve");
        check(AgentOp.mergeNames(List.of("Hero", "Steve", "to", "10", "64", "5"), known).equals(List.of("Hero Steve", "to", "10", "64", "5")), "unquoted agent name with space");
        check(AgentOp.mergeNames(List.of("Hero", "Steve", "Intern", "Steve"), known).equals(List.of("Hero Steve", "Intern Steve")), "two spaced names");
        check(AgentOp.mergeNames(List.of("Steve", "diamond", "3"), known).equals(List.of("Steve", "diamond", "3")), "no merge");
        java.util.Set<Integer> solid = java.util.Set.of(64, 65);
        check(AgentOp.safeFeetY(64, solid::contains, 320) == 66, "embedded body moves to first air");
        check(AgentOp.safeFeetY(66, solid::contains, 320) == 66, "clear spot unchanged");
        check(AgentOp.safeFeetY(63, solid::contains, 320) == 66, "head in floor moves up");
        JsonArray arr = new JsonArray(); arr.add("day");
        o.add("args", arr);
        check(AgentOp.args(o).equals(List.of("day")), "args array");
        for (String bad : List.of("stop", "say", "execute", "reload")) {
            o.addProperty("command", bad);
            fails(() -> AgentOp.subcommand(o), bad + " rejected");
        }
        o.addProperty("command", "/restart");
        check(AgentOp.subcommand(o).equals("restart"), "slash stripped");
        check(AgentOp.timeTicks("day") == 1000 && AgentOp.timeTicks("night") == 13000 && AgentOp.timeTicks("6000") == 6000, "time");
        fails(() -> AgentOp.timeTicks("24000"), "time range");
        check(AgentOp.volume(0, 0, 0, 31, 31, 31) == 32768 && AgentOp.volume(5, 5, 5, 5, 5, 5) == 1, "volume");
        check(AgentOp.killRadius(List.of()) == 32 && AgentOp.killRadius(List.of("500")) == 64 && AgentOp.killRadius(List.of("10")) == 10, "radius cap");
        fails(() -> AgentOp.playerName("bad name!"), "player name");
        check(AgentOp.SUBCOMMANDS.size() == 20 && !AgentOp.SUBCOMMANDS.contains("stop"), "allow-list");
        check(AgentActionCatalog.executable("op"), "op in catalog");
        check(BridgeProtocol.dropActionResult("Bridge POST /api/server/actions/x returned HTTP 404 sentKeys={}") , "404 dropped");
        check(!BridgeProtocol.dropActionResult("Bridge POST /api/server/actions/x returned HTTP 409"), "409 kept");
        System.out.println("PASS: op grant persistence, allow-list (no stop), args, time, fill/killmobs caps, 404 result drop, 0.7.5");
    }
    private static void fails(Runnable r, String what) {
        try { r.run(); } catch (IllegalArgumentException expected) { return; }
        throw new AssertionError("expected failure: " + what);
    }
    private static void check(boolean ok, String what) { if (!ok) throw new AssertionError(what); }
}
