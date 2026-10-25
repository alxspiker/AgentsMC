package dev.agentsmc;

import java.util.List;
import java.util.Map;

/** Offline boat item/entity rules, mount target parsing, and water steering. */
public final class BoatTest {
    public static void main(String[] args) {
        check(AgentBoats.isBoatItem("oak_boat") && AgentBoats.isBoatItem("OAK_CHEST_BOAT")
                && AgentBoats.isBoatItem("minecraft:bamboo_raft") && AgentBoats.isBoatItem("bamboo_chest_raft"),
                "boat items");
        check(!AgentBoats.isBoatItem("oak_planks") && !AgentBoats.isBoatItem("dirt"), "non-boat item");
        check(AgentBoats.entityTypeName("oak_boat").equals("OAK_BOAT"), "entity type");
        check(AgentBoats.entityTypeName("bamboo_raft").equals("BAMBOO_RAFT"), "raft entity type");
        check(AgentBoats.itemForEntity("OAK_CHEST_BOAT").equals("oak_chest_boat"), "item from entity");
        check(AgentBoats.itemForEntity("BOAT").equals("oak_boat"), "legacy boat item");
        check(AgentBoats.isRideableType("OAK_BOAT") && AgentBoats.isRideableType("MINECART")
                && AgentBoats.isRideableType("HORSE") && AgentBoats.isRideableType("CHEST_MINECART"),
                "rideable types");
        check(!AgentBoats.isRideableType("ZOMBIE") && !AgentBoats.isRideableType("COW"), "non-rideable");

        check(AgentBoats.canPlaceOn("WATER", true, false, false, "STONE", false, true), "place on water");
        check(AgentBoats.canPlaceOn("GRASS_BLOCK", false, true, false, "STONE", false, true), "place on solid");
        check(AgentBoats.canPlaceOn("AIR", false, false, true, "WATER", true, false), "place in air over water");
        check(!AgentBoats.canPlaceOn("AIR", false, false, true, "AIR", false, false), "floating boat");
        check(!AgentBoats.canPlaceOn("LAVA", true, false, false, "STONE", false, true), "no lava boat");

        double[] water = AgentBoats.spawnOffset(true, false, false);
        check(water[1] == 0.1, "water spawn y");
        double[] solid = AgentBoats.spawnOffset(false, true, false);
        check(solid[1] == 1.0, "solid spawn y");

        double[] east = AgentBoats.velocity(0, 0, 10, 0, AgentBoats.SPEED);
        check(Math.abs(east[0] - AgentBoats.SPEED) < 1e-9 && Math.abs(east[1]) < 1e-9, "east vx");
        check(Math.abs(east[2] + 90) < 1e-6, "east yaw");
        double[] south = AgentBoats.velocity(0, 0, 0, 8, AgentBoats.SPEED);
        check(Math.abs(south[1] - AgentBoats.SPEED) < 1e-9 && Math.abs(south[2]) < 1e-6, "south yaw 0");
        check(AgentBoats.SPEED >= 0.28 && AgentBoats.SPEED <= 0.5, "sprint-like boat speed");

        check("arrived".equals(AgentBoats.settle(0.1, true, true)), "arrived");
        check("arrived_near".equals(AgentBoats.settle(0.5, true, true)), "arrived_near");
        check("blocked_shore".equals(AgentBoats.settle(12, true, false)), "blocked_shore");
        check("arrived_near".equals(AgentBoats.settle(2.0, false, false)), "shore near target");
        check(AgentBoats.settle(8, true, true) == null, "keep steering");
        check(AgentBoats.shouldDismount("blocked_shore", true), "dismount at shore");
        check(!AgentBoats.shouldDismount("arrived", true), "stay mounted on water arrival");
        check(AgentBoats.shouldDismount("arrived", false), "dismount when beached at target");
        check(AgentBoats.terminal("blocked_shore") && !AgentNavigation.success("blocked_shore"), "blocked_shore is terminal failure");

        check("nearest".equals(AgentBoats.normalizeTarget(null)) && "nearest".equals(AgentBoats.normalizeTarget("")), "default nearest");
        check("boat".equals(AgentBoats.normalizeTarget("BOAT")) && AgentBoats.matchesFilter("OAK_BOAT", "boat"), "boat filter");
        check(AgentBoats.matchesFilter("CHEST_MINECART", "minecart") && AgentBoats.matchesFilter("DONKEY", "horse"), "cart/horse filter");
        check(AgentBoats.matchesFilter("OAK_BOAT", "nearest") && !AgentBoats.matchesFilter("ZOMBIE", "nearest"), "nearest rideable");
        String uuid = "8f3a1c2e-4b5d-6789-abcd-ef0123456789";
        check(AgentBoats.isUuid(uuid) && uuid.equals(AgentBoats.normalizeTarget(uuid)), "uuid target");
        check(AgentBoats.kind("OAK_BOAT").equals("boat") && AgentBoats.kind("MINECART").equals("minecart"), "kind");

        String version = AgentVersion.fromPluginYml();
        check("0.7.4".equals(version), "plugin.yml version: " + version);
        check("0.7.4".equals(AgentVersion.current(null)), "status version fallback: " + AgentVersion.current(null));
        check(AgentVersion.parseVersion("name: AgentsMC\nversion: 0.7.4\n").equals("0.7.4"), "parse version");
        check(!AgentVersion.agentsMc("2.0.44-SNAPSHOT (build 4268)"), "reject Citizens version");

        List<String> actions = AgentActionCatalog.advertisedNames();
        check(actions.contains("mount") && actions.contains("dismount") && actions.contains("goto"), "catalog advertises vehicles");
        check(actions.contains("place") && actions.contains("break") && actions.contains("attack"), "catalog advertises place/break");

        Map<String, Object> catalog = AgentActionCatalog.snapshot();
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> all = (List<Map<String, Object>>) catalog.get("actions");
        Map<String, Object> mount = all.stream().filter(m -> "mount".equals(m.get("action"))).findFirst().orElseThrow();
        check(String.valueOf(mount.get("fields")).toLowerCase().contains("target"), "mount field is target: " + mount.get("fields"));
        check(String.valueOf(mount.get("description")).toLowerCase().contains("boat"), "mount description");

        java.util.Iterator<String> states = java.util.List.of("navigating", "moving", "blocked_shore").iterator();
        Map<String, Object> shore = AgentActionProgram.awaitNavigation(Map.of("ok", true, "action", "goto"),
                () -> Map.of("status", states.next(), "reason", "Shore blocks the remaining water route"),
                () -> { throw new AssertionError("stopped"); }, java.util.concurrent.TimeUnit.SECONDS.toNanos(5), 1);
        check(Boolean.FALSE.equals(shore.get("ok")) && "blocked_shore".equals(shore.get("status")),
                "blocked_shore must settle the program: " + shore);

        System.out.println("PASS: boat items, water/solid place, mount target, steer settle, 0.7.4 version from plugin.yml");
    }

    private static void check(boolean ok, String message) {
        if (!ok) throw new AssertionError(message);
    }
}

