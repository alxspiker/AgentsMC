package dev.agentsmc;

import java.util.List;
import java.util.Map;

/** Offline idle self-defense rules: triggers, flee threshold, observe events. */
public final class SelfDefenseTest {
    public static void main(String[] args) {
        check(AgentSelfDefense.RANGE == 4, "proximity range is 4");
        check(AgentSelfDefense.FLEE_HEALTH == 12, "flee under 12 health");
        check(AgentSelfDefense.FLEE_BLOCKS >= 8, "flee distance");
        check(AgentSelfDefense.RANGED_FLEE_BLOCKS >= 12, "ranged flee is farther");

        check(!AgentSelfDefense.shouldRespond(true, true, true), "exclusive action blocks defense");
        check(AgentSelfDefense.shouldRespond(false, true, false), "idle + damaged");
        check(AgentSelfDefense.shouldRespond(false, false, true), "idle + hostile within 4");
        check(!AgentSelfDefense.shouldRespond(false, false, false), "idle with no threat");
        check(AgentSelfDefense.shouldRespond(false, true, true), "both triggers");

        check(AgentSelfDefense.actionRunning(true, false, false, false), "player fight is exclusive");
        check(!AgentSelfDefense.actionRunning(true, true, false, false), "own self-defense fight is not exclusive");
        check(!AgentSelfDefense.actionRunning(false, false, true, false), "protect is paused, not exclusive");
        check(!AgentSelfDefense.actionRunning(false, false, false, true), "harvest is paused, not exclusive");
        check(!AgentSelfDefense.actionRunning(false, false, false, false), "idle/goto is not exclusive");
        check(AgentSelfDefense.shouldRespond(false, true, false), "goto/follow/protect/harvest still respond");

        java.util.UUID spider = java.util.UUID.fromString("aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee");
        check(AgentSelfDefense.skipEngaged(spider, spider), "same spider is one engaged event");
        check(!AgentSelfDefense.skipEngaged(null, spider), "first engaged is logged");
        check(!AgentSelfDefense.skipEngaged(spider, java.util.UUID.randomUUID()), "a new threat may log engaged");
        check(AgentSelfDefense.REASON.equals("self_defense"), "pause reason");

        check(AgentSelfDefense.CRITICAL_HEALTH == 6, "armed melee fights down to 6");
        check("fight".equals(AgentSelfDefense.choose(7.9)), "armed melee at 7.9 fights");
        check("flee".equals(AgentSelfDefense.choose(0)), "0 flees");
        check("fight".equals(AgentSelfDefense.choose(11.9)), "armed melee at 11.9 fights");
        check("fight".equals(AgentSelfDefense.choose(12)), "12 fights");
        check("fight".equals(AgentSelfDefense.choose(20)), "full health fights");
        check("fight".equals(AgentSelfDefense.choose(11.3, false, false)), "fb-13 iron sword vs zombie fights");
        check("flee".equals(AgentSelfDefense.choose(5.9, false, false)), "armed melee below 6 flees");
        check("flee".equals(AgentSelfDefense.choose(11.3, false, true)), "armed vs skeleton at 11.3 flees");
        check("flee".equals(AgentSelfDefense.choose(11.3, true, false)), "unarmed melee at 11.3 flees");
        check("resume".equals(AgentSelfDefense.afterSafe(true, true)), "paused work resumes after flee");
        check("return".equals(AgentSelfDefense.afterSafe(false, true)), "idle flee returns home via goto");
        check("idle".equals(AgentSelfDefense.afterSafe(false, false)), "no home stays idle");
        check(AgentSelfDefense.keepFleeGoto(true) && !AgentSelfDefense.keepFleeGoto(false), "do not replan while goto is walking");
        check(AgentSelfDefense.sameCell(3, 64, -2, 3, 64, -2) && !AgentSelfDefense.sameCell(3, 64, -2, 4, 64, -2), "same flee cell");
        check("flee".equals(AgentSelfDefense.choose(20, true, true)), "unarmed vs ranged flees at full health");
        check("fight".equals(AgentSelfDefense.choose(20, false, true)), "armed vs ranged still fights at full health");
        check("fight".equals(AgentSelfDefense.choose(20, true, false)), "unarmed vs melee at full health fights");
        check(!AgentSelfDefense.armed("AIR") && !AgentSelfDefense.armed("STONE_PICKAXE")
                && AgentSelfDefense.armed("IRON_SWORD") && AgentSelfDefense.armed("BOW"), "armed names");
        check(AgentSelfDefense.rangedType("SKELETON") && AgentSelfDefense.rangedType("STRAY")
                && !AgentSelfDefense.rangedType("ZOMBIE") && !AgentSelfDefense.rangedType("CREEPER"), "ranged types");
        AgentSelfDefense.CoverWorld cover = new AgentSelfDefense.CoverWorld() {
            public boolean standable(int x, int y, int z) { return y == 64 && x >= -16 && x <= 16 && z >= -16 && z <= 16; }
            public boolean solid(int x, int y, int z) { return x == 2 && z == 0 && y == 65; }
        };
        int[] hid = AgentSelfDefense.coverOrAway(0, 64, 0, -4, 64, 0, 6, 16, cover);
        check(hid != null && hid[0] > 0, "flee dest is away from the skeleton: " + java.util.Arrays.toString(hid));
        check(AgentSelfDefense.losBlocked(0, 65, 0, 4, 65, 0, cover), "wall at x=2 blocks LOS");
        check(!AgentSelfDefense.losBlocked(0, 65, 1, 4, 65, 1, cover), "open row has LOS");
        AgentSelfDefense.CoverWorld hill = new AgentSelfDefense.CoverWorld() {
            public boolean standable(int x, int y, int z) {
                if (x == 6 && z == 0 && y == 66) return true;
                return y == 64 && x >= -16 && x <= 16 && z >= -16 && z <= 16;
            }
            public boolean solid(int x, int y, int z) {
                return x == 2 && z == 0 && y == 65
                        || x == 6 && z == 0 && (y == 65 || y == 66);
            }
        };
        int[] stay = AgentSelfDefense.coverOrAway(0, 64, 0, -4, 64, 0, 6, 16, hill);
        check(stay != null && stay[1] - 64 <= 1,
                "flee dest is not a 2-block hop: " + java.util.Arrays.toString(stay));

        check(AgentSelfDefense.hostileWithin(4, 4), "4.0 is in range");
        check(AgentSelfDefense.hostileWithin(0, 4), "touching is in range");
        check(!AgentSelfDefense.hostileWithin(4.01, 4), "just outside 4");
        check(!AgentSelfDefense.hostileWithin(Double.NaN, 4), "NaN is not in range");

        check("damage".equals(AgentSelfDefense.triggerOf(true, false)), "damage trigger");
        check("proximity".equals(AgentSelfDefense.triggerOf(false, true)), "proximity trigger");
        check("damage".equals(AgentSelfDefense.triggerOf(true, true)), "damage wins when both");
        check(AgentSelfDefense.triggerOf(false, false).isEmpty(), "no trigger");

        Map<String, Object> ev = AgentSelfDefense.event("engaged", "ZOMBIE", "proximity", 16.4, 1000L);
        check("engaged".equals(ev.get("type")) && "ZOMBIE".equals(ev.get("threat"))
                && "proximity".equals(ev.get("trigger")) && Double.valueOf(16.4).equals(ev.get("health"))
                && Long.valueOf(1000L).equals(ev.get("time")), "event shape: " + ev);
        Map<String, Object> hit = AgentSelfDefense.event("damaged", "SKELETON", "damage", 11, 2L);
        check("damaged".equals(hit.get("type")) && "damage".equals(hit.get("trigger")), "damage event");
        Map<String, Object> fled = AgentSelfDefense.event("fled", "ZOMBIE", "damage", 7.5, 3L);
        check("fled".equals(fled.get("type")) && Double.valueOf(7.5).equals(fled.get("health")), "flee event");
        Map<String, Object> killed = AgentSelfDefense.event("killed", "ZOMBIE", "proximity", 12, 4L);
        check("killed".equals(killed.get("type")), "kill event");

        check(AgentProtect.hostileType("ZOMBIE") && AgentProtect.hostileType("SKELETON"), "hostiles");
        check(!AgentProtect.hostileType("COW") && !AgentProtect.hostileType("VILLAGER"), "passives are not threats");

        check("IRON_SWORD".equals(AgentCombat.bestWeapon(List.of("STONE_AXE", "IRON_SWORD"))), "best weapon for idle fight");

        java.util.UUID trapped = java.util.UUID.fromString("22fcc794-0000-0000-0000-000000000001");
        long now = 1_000_000L;
        check(AgentSelfDefense.ignored(trapped, trapped, now + AgentSelfDefense.IGNORE_MS, now), "unreachable spider is ignored");
        check(!AgentSelfDefense.ignored(trapped, trapped, now, now), "ignore expires");
        check(!AgentSelfDefense.ignored(java.util.UUID.randomUUID(), trapped, now + 1, now), "a different mob is not ignored");
        check(AgentSelfDefense.overridden(now + AgentSelfDefense.OVERRIDE_MS, now), "goto/stop override proximity");
        check(!AgentSelfDefense.overridden(now, now), "override expires");

        check(!AgentSelfDefense.shouldEngage(false, false, true, true, false), "ignored proximity does not re-engage");
        check(!AgentSelfDefense.shouldEngage(false, false, true, false, true), "override blocks proximity");
        check(AgentSelfDefense.shouldEngage(false, true, true, true, true), "a hit still fights back");
        check(AgentSelfDefense.shouldEngage(false, false, true, false, false), "fresh hostile still engages");
        check(!AgentSelfDefense.shouldEngage(true, true, true, false, false), "player fight stays exclusive");

        check(AgentSelfDefense.gaveUp("failed", "target unreachable"), "unreachable is a give-up");
        check(AgentSelfDefense.gaveUp("failed", "Fight timed out after 30s"), "timeout is a give-up");
        check(!AgentSelfDefense.gaveUp("killed", ""), "a kill is not a give-up");
        check(!AgentSelfDefense.gaveUp("retreated", "Health below 6; retreated"), "low-health retreat is not ignore");

        check(AgentCombat.noChaseProgress(0, AgentCombat.GIVE_UP_MS), "0 hits and 5s no close is stuck");
        check(!AgentCombat.noChaseProgress(1, AgentCombat.GIVE_UP_MS), "a hit is progress");
        check(!AgentCombat.noChaseProgress(0, AgentCombat.GIVE_UP_MS - 1), "under 5s is not yet stuck");
        check(AgentCombat.closedGap(3.9, 3.6), "0.3 closer is a close");
        check(!AgentCombat.closedGap(3.9, 3.8), "0.1 jitter is not a close");
        check("target unreachable".equals(AgentCombat.chaseFail(false, true, true, 0, 0, AgentCombat.GIVE_UP_MS)),
                "jittering chase with 0 hits still gives up");
        check(AgentCombat.chaseFail(false, true, true, 0, 1, AgentCombat.GIVE_UP_MS) == null, "hits keep the chase");

        java.util.List<String> leaves = AgentBehaviors.preferredTools("SPRUCE_LEAVES", "_HOE");
        check(leaves.get(0).equals("SHEARS") && leaves.contains("_HOE") && leaves.contains("_SWORD"),
                "leaves prefer shears then hoe/sword: " + leaves);
        java.util.List<String> snow = AgentBehaviors.preferredTools("SNOW_BLOCK", "_SHOVEL");
        check(snow.equals(java.util.List.of("_SHOVEL")), "snow uses shovel: " + snow);
        check(AgentBehaviors.allowEmptyDrops("SPRUCE_LEAVES") && AgentBehaviors.allowEmptyDrops("SNOW_BLOCK"),
                "no-drop blocks are still breakable");
        check(!AgentBehaviors.allowEmptyDrops("IRON_ORE") && !AgentBehaviors.allowEmptyDrops("DEEPSLATE_IRON_ORE"),
                "iron ore is not an empty-drop clear");
        check(AgentBehaviors.toolCanHarvest("STONE_PICKAXE", "IRON_ORE") && !AgentBehaviors.toolCanHarvest("WOODEN_PICKAXE", "IRON_ORE"),
                "stone pick harvests iron; wooden does not");
        check(AgentBehaviors.toolCanHarvest("WOODEN_PICKAXE", "STONE") && AgentBehaviors.wouldDestroy("WOODEN_PICKAXE", "IRON_ORE"),
                "wooden pick is fine on stone and destroys iron");
        check(AgentBehaviors.needToolMessage("IRON_ORE", "WOODEN_PICKAXE").contains("STONE_PICKAXE")
                && AgentBehaviors.needToolMessage("IRON_ORE", "WOODEN_PICKAXE").contains("IRON_ORE"),
                "fb-28 names the required pick");
        check(AgentProtection.containerUse("CHEST") && AgentProtection.containerUse("minecraft:barrel")
                && !AgentProtection.containerUse("COBBLESTONE"),
                "chest/barrel use is not a build");

        String version = AgentVersion.fromPluginYml();
        check("0.7.4".equals(version), "plugin.yml version: " + version);

        System.out.println("PASS: idle self-defense triggers, flee at 12, armed melee to 6, unarmed-ranged cover, pause not exclusive, one engaged per threat, fb-15 give-up/override, fb-16 no-drop tools, 0.7.4");
    }

    private static void check(boolean ok, String message) {
        if (!ok) throw new AssertionError(message);
    }
}

