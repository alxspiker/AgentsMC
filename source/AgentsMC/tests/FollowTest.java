package dev.agentsmc;

/** Follow holds in 2–4 or on an overlook, walks with goto, retries no_path, never fails on navigator complete. */
public final class FollowTest {
    public static void main(String[] args) {
        check("close".equals(AgentCombat.followPace(1.9)), "close <2");
        check("hold".equals(AgentCombat.followPace(2)), "hold at 2");
        check("hold".equals(AgentCombat.followPace(4)), "hold at 4");
        check("walk".equals(AgentCombat.followPace(4.01)), "walk just past 4");
        check("walk".equals(AgentCombat.followPace(8)), "walk at 8");
        check("sprint".equals(AgentCombat.followPace(8.01)), "sprint past 8");

        check(AgentCombat.followHold(0) && AgentCombat.followHold(4), "hold through 4");
        check(!AgentCombat.followHold(4.01), "past 4 is not hold");
        check(!AgentCombat.followSprint(8) && AgentCombat.followSprint(8.01), "sprint only past 8");

        check(AgentCombat.followOverlook(2, 8), "hill above cave is overlook");
        check(AgentCombat.followOverlook(4, 4), "horiz 4 drop 4 is overlook");
        check(!AgentCombat.followOverlook(4, 3), "drop 3 is walkable");
        check(!AgentCombat.followOverlook(4.01, 8), "horiz past 4 is not overlook");
        check(AgentCombat.followHold(8.25, 2, 8, 0), "3D far but stacked is hold");
        check(!AgentCombat.followHold(8.49, 6, 6, 0), "beside a drop is not hold");

        check("hold".equals(AgentCombat.followComplete(3)), "navigator complete near target is hold");
        check("repath".equals(AgentCombat.followComplete(9)), "navigator complete far is repath");
        check(!"failed".equals(AgentCombat.followComplete(0)) && !"failed".equals(AgentCombat.followComplete(50)),
                "follow complete never fails");
        check("hold".equals(AgentCombat.followComplete(8.25, 2, 8, 0, false)), "overlook complete is hold");
        check("hold".equals(AgentCombat.followComplete(12, 10, 0, 0, true)), "no_path complete is hold");
        check("repath".equals(AgentCombat.followComplete(9, 9, 0, 0, false)), "far complete is repath");

        check(!AgentCombat.followShouldRetarget(true, true, 3, 10), "do not retarget while holding");
        check(AgentCombat.followShouldRetarget(false, true, 6, 0), "retarget when not navigating");
        check(AgentCombat.followShouldRetarget(true, false, 6, 0), "retarget when entity target differs");
        check(AgentCombat.followShouldRetarget(true, true, 6, 2), "retarget when target moved 2+");
        check(!AgentCombat.followShouldRetarget(true, true, 6, 1.9), "keep path if target moved under 2");

        check(!AgentCombat.followShouldRepath(true, 10, 6, 0, 8, 1.9, 0, false), "keep goto if dest still close");
        check(AgentCombat.followShouldRepath(true, 10, 6, 0, 8, 2, 0, false), "repath if target moved 2+");
        check(!AgentCombat.followShouldRepath(false, 8.25, 2, 8, 0, 0, 0, true), "overlook hold even after no_path");
        check(!AgentCombat.followShouldRepath(false, 12, 10, 6, 0, 0, 500, true), "no_path waits 2s");
        check(AgentCombat.followShouldRepath(false, 12, 10, 6, 0, 0, 2000, true), "no_path retry after 2s");
        check(AgentCombat.followShouldRepath(false, 12, 10, 6, 0, 2, 100, true), "no_path retry if target moved");
        check(!AgentCombat.followShouldRepath(false, 3, 2, 1, 1, 10, 5000, false), "3D hold never repath");
        check(AgentCombat.followShouldRepath(false, 9, 9, 0, 0, 0, 0, false), "arrived far is repath");

        check(AgentCombat.chaseFail(false, false, true, 0) != null, "fight still fails when not navigating");
        check(AgentCombat.chaseFail(true, false, true, 0) == null, "fight in reach is not a chase fail");

        targetLookup();

        String version = AgentVersion.fromPluginYml();
        check("0.7.6".equals(version), "plugin.yml version: " + version);

        System.out.println("PASS: follow pace/hold/overlook/no_path, name lookup, non-empty errors, 0.7.4");
    }

    private static void targetLookup() {
        check(AgentTargets.namesEqual("Helper", "helper"), "case-insensitive player/agent name");
        check(AgentTargets.namesEqual("Scout Bot", "scout bot"), "spaced display name");
        check(!AgentTargets.namesEqual("Helper", "Miner"), "different names");
        check(AgentTargets.looksLikeUuid("0a1b2c3d-4e5f-4a6b-8c7d-0e1f2a3b4c5d"), "hyphenated uuid");
        check(AgentTargets.looksLikeUuid("0a1b2c3d4e5f4a6b8c7d0e1f2a3b4c5d"), "dashless uuid");
        check(!AgentTargets.looksLikeUuid("Helper"), "name is not uuid");
        var named = com.google.gson.JsonParser.parseString("{\"target\":\"Helper\"}").getAsJsonObject();
        check("Helper".equals(AgentTargets.selector(named)), "selector reads target");
        var player = com.google.gson.JsonParser.parseString("{\"player\":\"player1\"}").getAsJsonObject();
        check("player1".equals(AgentTargets.selector(player)), "selector reads player");
        var empty = com.google.gson.JsonParser.parseString("{\"action\":\"follow\"}").getAsJsonObject();
        check(AgentTargets.selector(empty).isBlank(), "missing selector");
        String need = AgentTargets.followNeed();
        check(!need.isBlank() && need.toLowerCase().contains("name"), "follow need: " + need);
        String miss = AgentTargets.missing("Helper");
        check(!miss.isBlank() && miss.contains("Helper"), "missing: " + miss);
        check(AgentTargets.requireError("").equals("Action failed (no detail reported)"), "blank error filled");
        check(AgentTargets.requireError(null).equals("Action failed (no detail reported)"), "null error filled");
        check(AgentTargets.requireError("gone").equals("gone"), "specific error kept");
        check(AgentTargets.invalidUuid("Helper").contains("Helper"), "invalid uuid names the value");
        try {
            AgentTargets.parseUuid("Helper");
            throw new AssertionError("name parsed as uuid");
        } catch (IllegalArgumentException e) {
            check(!e.getMessage().isBlank() && e.getMessage().contains("Helper"), "parseUuid error: " + e.getMessage());
        }
    }

    private static void check(boolean ok, String message) {
        if (!ok) throw new AssertionError(message);
    }
}
