package dev.agentsmc;

/** Bed / respawn-anchor spawn persist, observe view, and death choose. */
public final class SleepTest {
    public static void main(String[] args) {
        check(AgentRespawn.isBed("RED_BED") && AgentRespawn.isBed("bed"), "bed materials");
        check(!AgentRespawn.isBed("OAK_PLANKS") && !AgentRespawn.isAnchor("RED_BED"), "not a bed");
        check(AgentRespawn.isAnchor("RESPAWN_ANCHOR") && AgentRespawn.isAnchor("respawn_anchor"), "anchor");

        String raw = AgentRespawn.encode("world", 10.5, 64, -3.2, 90f, 0f, "bed");
        AgentRespawn.Point p = AgentRespawn.parse(raw);
        check(p != null && "world".equals(p.world) && "bed".equals(p.kind), "parse kind");
        check(Math.abs(p.x - 10.5) < 1e-9 && Math.abs(p.y - 64) < 1e-9, "parse xyz");
        check(AgentRespawn.parse("") == null && AgentRespawn.parse("nope") == null, "reject junk");

        var view = AgentRespawn.view(p);
        check(view != null && "world".equals(view.get("world")) && "bed".equals(view.get("kind")), "observe view");
        check(view.containsKey("x") && view.containsKey("y") && view.containsKey("z"), "observe xyz");

        AgentRespawn.Point missing = AgentRespawn.parse(null);
        check(missing == null, "null parse");

        // choose: persisted bed wins unless it is the death camp; else world spawn.
        check(AgentRespawn.choose(null, null, null) == null, "all missing");
        check(!AgentRespawn.usable(null), "null unusable");
        check(AgentRespawn.sameCamp(-2.5, 79, 1.5, -1.2, 77, 5.3, 8), "death nest is same camp");
        check(!AgentRespawn.sameCamp(0, 64, 0, 20, 64, 0, 8), "world spawn is not the nest");
        check(AgentRespawn.repeatCamp(java.util.UUID.randomUUID(), null, 1) == false, "null death is not a camp");

        // Voluntary respawn: persisted bed/anchor, else vanilla bed, else world spawn.
        check(AgentRespawn.chooseHome(null, null, null) == null, "chooseHome all missing");
        check("bed".equals(AgentRespawn.pickKind(true, "bed", false, true)), "persisted bed kind");
        check("anchor".equals(AgentRespawn.pickKind(true, "anchor", false, true)), "persisted anchor kind");
        check("bed".equals(AgentRespawn.pickKind(true, "", false, true)), "blank persisted kind is bed");
        check("bed".equals(AgentRespawn.pickKind(false, null, true, true)), "vanilla bed kind");
        check("world_spawn".equals(AgentRespawn.pickKind(false, null, false, true)), "starter spawn kind");
        check("bed".equals(AgentRespawn.pickKind(false, null, false, false)), "last-resort kind");

        check(AgentRespawn.covers("world", 10, 64, -3, "world", 10.5, 64.5, -2.5, null), "exact half matches");
        check(AgentRespawn.covers("world", 11, 64, -3, "world", 10.5, 64.5, -2.5, new int[]{10, 64, -3}),
                "other bed half matches");
        check(!AgentRespawn.covers("world", 12, 64, -3, "world", 10.5, 64.5, -2.5, new int[]{13, 64, -3}),
                "adjacent second bed is not this claim");
        check(!AgentRespawn.covers("nether", 10, 64, -3, "world", 10.5, 64.5, -2.5, null), "wrong world");
        check(AgentRespawn.stealError("Scout Bot").contains("Scout Bot")
                && AgentRespawn.stealError("Scout Bot").toLowerCase().contains("unclaimed"),
                "steal error names the owner");

        AgentRespawn.Claim hero = new AgentRespawn.Claim();
        hero.agentId = "abc"; hero.name = "Scout Bot"; hero.kind = "bed"; hero.world = "world";
        hero.x = 10; hero.y = 64; hero.z = -3;
        var taken = AgentRespawn.claimView(hero, "def");
        check(Boolean.TRUE.equals(taken.get("claimed")) && Boolean.FALSE.equals(taken.get("mine")),
                "other agent's bed is claimed not mine: " + taken);
        var mine = AgentRespawn.claimView(hero, "abc");
        check(Boolean.TRUE.equals(mine.get("mine")), "own bed is mine");
        var free = AgentRespawn.claimView(null, "abc");
        check(Boolean.FALSE.equals(free.get("claimed")), "unclaimed");

        AgentRespawn.Claim human = new AgentRespawn.Claim();
        human.player = true;
        human.name = ".ExamplePlayer";
        human.kind = "bed";
        human.world = "world";
        human.x = -10; human.y = 87; human.z = -55;
        human.uuid = java.util.UUID.fromString("00000000-0000-0000-0009-01f166e1152b");
        var hv = AgentRespawn.claimView(human, "abc");
        check(Boolean.TRUE.equals(hv.get("claimed")) && Boolean.FALSE.equals(hv.get("mine")),
                "human bed is claimed not mine: " + hv);
        @SuppressWarnings("unchecked")
        var by = (java.util.Map<String, Object>) hv.get("claimedBy");
        check(Boolean.TRUE.equals(by.get("player")) && ".ExamplePlayer".equals(by.get("name"))
                && !by.containsKey("agentId") && by.containsKey("uuid"),
                "human claimedBy: " + by);
        check(AgentRespawn.stealError(".ExamplePlayer").contains(".ExamplePlayer"), "steal names the player");
        check(AgentRespawn.stealError("").toLowerCase().contains("player"), "steal fallback mentions player");
        check(AgentRespawn.fromHuman(".ExamplePlayer", null, null, "bed") == null, "human claim needs spawn");

        check(AgentSurvivalActions.daytimeSleepNote(6000).contains("12:00 PM"),
                "daytime sleep note uses the clock: " + AgentSurvivalActions.daytimeSleepNote(6000));
        check(AgentSurvivalActions.daytimeSleepNote(1000).toLowerCase().contains("daytime"),
                "daytime note says daytime");
        check(AgentSurvivalActions.hostileSleep(null) == false, "null is not a sleep hostile");

        check(AgentSleep.lying("SLEEPING", false, false) && AgentSleep.lying("standing", true, false)
                && AgentSleep.lying("standing", false, true), "any sleep signal is lying");
        check(!AgentSleep.lying("standing", false, false) && !AgentSleep.lying(null, false, false),
                "standing is not lying");
        check("sleeping".equals(AgentSleep.displayPose("standing", true))
                && "standing".equals(AgentSleep.displayPose(null, false)),
                "display pose prefers sleeping then standing");
        var asleep = AgentSleep.view("SLEEPING", true, false);
        check(Boolean.TRUE.equals(asleep.get("sleeping")) && "sleeping".equals(asleep.get("pose")),
                "observe-shaped sleep view: " + asleep);
        var awake = AgentSleep.view("STANDING", false, false);
        check(Boolean.FALSE.equals(awake.get("sleeping")) && "standing".equals(awake.get("pose")),
                "observe-shaped stand view: " + awake);
        check(AgentSleep.shouldWakeOnAction("goto") && AgentSleep.shouldWakeOnAction("follow")
                && AgentSleep.shouldWakeOnAction("move") && AgentSleep.shouldWakeOnAction("stop"),
                "movement wakes");
        check(!AgentSleep.shouldWakeOnAction("observe") && !AgentSleep.shouldWakeOnAction("sleep")
                && !AgentSleep.shouldWakeOnAction("inspect_block"),
                "observe/sleep do not force-wake");

        String version = AgentVersion.fromPluginYml();
        check(AgentSleep.shouldWakeOnAction("survey") && AgentSleep.shouldWakeOnAction("walkmap"), "fb-22: survey/walkmap wake a sleeping body");
        // Bed orientation: FOOT + FACING is the HEAD; head input is unchanged (all four facings).
        check(java.util.Arrays.equals(AgentSleep.bedHead(-5, 87, -57, "NORTH", false), new int[]{-5, 87, -58}), "north foot->head");
        check(java.util.Arrays.equals(AgentSleep.bedHead(-5, 87, -57, "SOUTH", false), new int[]{-5, 87, -56}), "south foot->head");
        check(java.util.Arrays.equals(AgentSleep.bedHead(10, 64, 10, "EAST", false), new int[]{11, 64, 10}), "east foot->head");
        check(java.util.Arrays.equals(AgentSleep.bedHead(-9, 87, -57, "WEST", false), new int[]{-10, 87, -57}), "west foot->head");
        for (String f : new String[]{"NORTH", "SOUTH", "EAST", "WEST"})
            check(java.util.Arrays.equals(AgentSleep.bedHead(1, 2, 3, f, true), new int[]{1, 2, 3}), "head stays head " + f);
        check(AgentSleep.bedYaw("SOUTH") == 90f && AgentSleep.bedYaw("WEST") == 0f
                && AgentSleep.bedYaw("NORTH") == 270f && AgentSleep.bedYaw("EAST") == 180f, "bed yaw = vanilla sleepDirectionToRotation");
        // Auto-wake: beds work 12542..23459 or in thunder.
        check(AgentSleep.shouldAutoWake(24000L * 53 + 3980, false), "9:58 AM day 54 wakes");
        check(AgentSleep.shouldAutoWake(23460, false) && AgentSleep.shouldAutoWake(0, false), "dawn wakes");
        check(!AgentSleep.shouldAutoWake(18000, false) && !AgentSleep.shouldAutoWake(12542, false), "night keeps sleeping");
        check(!AgentSleep.shouldAutoWake(6000, true), "thunderstorm keeps sleeping");
        // Wake spot: first free cell beside the head, never the bed cells, every facing.
        for (String f : new String[]{"NORTH", "SOUTH", "EAST", "WEST"}) {
            int[] head = AgentSleep.bedHead(0, 64, 0, f, true);
            int fx = f.equals("EAST") ? 1 : f.equals("WEST") ? -1 : 0, fz = f.equals("SOUTH") ? 1 : f.equals("NORTH") ? -1 : 0;
            int[] spot = AgentSleep.wakeSpot(0, 64, 0, f, c -> true);
            check(spot != null && spot[1] == 64 && Math.abs(spot[0]) + Math.abs(spot[2]) == 1
                    && !(spot[0] == fx && spot[2] == fz) && !(spot[0] == -fx && spot[2] == -fz), "wake beside head " + f);
            java.util.Set<String> bedCells = java.util.Set.of(head[0] + "," + head[2], (-fx) + "," + (-fz));
            int[] any = AgentSleep.wakeSpot(0, 64, 0, f, c -> !bedCells.contains(c[0] + "," + c[2]) && c[0] == 5);
            check(any == null, "no candidate far away " + f);
            int[] up = AgentSleep.wakeSpot(0, 64, 0, f, c -> c[1] == 65);
            check(up != null && !bedCells.contains(up[0] + "," + up[2]), "step-up spot not on bed " + f);
        }
        check(AgentSleep.wakeSpot(0, 64, 0, "NORTH", c -> false) == null, "no spot -> null (fallback on top of bed)");
        check("0.7.4".equals(version), "plugin.yml version: " + version);
        System.out.println("PASS: sleep/respawn encode, bed claims visible, human player claims, steal refused, death-camp, voluntary home, daytime sleep note, pose wake, bed head/facing x4, dawn auto-wake, wake beside bed, 0.7.4");
    }

    private static void check(boolean ok, String message) {
        if (!ok) throw new AssertionError(message);
    }
}

