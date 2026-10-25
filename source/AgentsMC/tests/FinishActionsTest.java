package dev.agentsmc;

/** Offline contracts for the remaining catalog verbs. command stays off. */
public final class FinishActionsTest {
    public static void main(String[] args) {
        check(AgentFinishActions.giveNeed().toLowerCase().contains("player"), "give need names player");
        check(!AgentFinishActions.healSetsHealth(), "heal must not set health");

        check("snowball".equals(AgentFinishActions.throwKind("SNOWBALL")), "snowball");
        check("ender_pearl".equals(AgentFinishActions.throwKind("ENDER_PEARL")), "pearl");
        check("potion".equals(AgentFinishActions.throwKind("SPLASH_POTION")), "splash");
        check("toss".equals(AgentFinishActions.throwKind("DIRT")), "dirt is a toss");

        check(AgentFinishActions.drinkable("POTION") && AgentFinishActions.drinkable("MILK_BUCKET")
                && AgentFinishActions.drinkable("HONEY_BOTTLE"), "drinks");
        check(!AgentFinishActions.drinkable("BREAD") && !AgentFinishActions.drinkable("DIRT"), "food is not drink");

        check(AgentFinishActions.milkable("COW") && AgentFinishActions.milkable("GOAT")
                && AgentFinishActions.milkable("MOOSHROOM"), "milkable");
        check(!AgentFinishActions.milkable("PIG") && !AgentFinishActions.milkable("PLAYER"), "not milkable");

        check(AgentFinishActions.tameFood("WOLF", "BONE") && AgentFinishActions.tameFood("CAT", "COD")
                && AgentFinishActions.tameFood("PARROT", "WHEAT_SEEDS") && AgentFinishActions.tameFood("HORSE", "GOLDEN_APPLE"),
                "tame foods");
        check(!AgentFinishActions.tameFood("WOLF", "BEEF") && !AgentFinishActions.tameFood("ZOMBIE", "BONE"), "wrong tame food");

        check(AgentFinishActions.climbable("LADDER") && AgentFinishActions.climbable("VINE")
                && AgentFinishActions.climbable("SCAFFOLDING"), "climbable");
        check(!AgentFinishActions.climbable("STONE"), "stone is not climbable");

        check(AgentFinishActions.portalBlock("NETHER_PORTAL") && AgentFinishActions.portalBlock("END_PORTAL"), "portals");
        check(!AgentFinishActions.portalBlock("END_PORTAL_FRAME") && !AgentFinishActions.portalBlock("RESPAWN_ANCHOR"),
                "frames are not portals");

        check("fill_water".equals(AgentFinishActions.fillMode("BUCKET", "WATER")), "fill water");
        check("empty_water".equals(AgentFinishActions.fillMode("WATER_BUCKET", "AIR")), "empty water");
        check("bottle_water".equals(AgentFinishActions.fillMode("GLASS_BOTTLE", "WATER")), "bottle");
        check(AgentFinishActions.fillMode("DIRT", "WATER").isEmpty(), "dirt cannot fill");

        check(AgentFinishActions.moveDirection("north") && AgentFinishActions.moveDirection("forward")
                && !AgentFinishActions.moveDirection("up"), "move directions, no fly");
        int[] north = AgentFinishActions.moveDelta("north", 4, 0);
        check(north[0] == 0 && north[1] == 0 && north[2] == -4, "north is -z");
        int[] east = AgentFinishActions.moveDelta("east", 3, 0);
        check(east[0] == 3 && east[2] == 0, "east is +x");
        int[] fwd = AgentFinishActions.moveDelta("forward", 2, 0);
        check(fwd[0] == 0 && fwd[2] == 2, "yaw 0 forward is +z");
        try {
            AgentFinishActions.moveDelta("up", 2, 0);
            throw new AssertionError("up must be refused");
        } catch (IllegalArgumentException expected) {
            check(expected.getMessage() != null && expected.getMessage().toLowerCase().contains("direction"),
                    "up error: " + expected.getMessage());
        }

        int[] away = AgentFinishActions.retreatDelta(0, 0, 10, 0, 8);
        check(away[0] < 0 && away[1] == 0, "retreat is opposite the threat");

        check(AgentFinishActions.anvilMerge(null, null) == null, "anvil null");

        check(AgentActionCatalog.advertised("give") && AgentActionCatalog.advertised("trade")
                && AgentActionCatalog.advertised("brew") && !AgentActionCatalog.advertised("command"),
                "catalog finish: give on, command off");

        String version = AgentVersion.fromPluginYml();
        check("0.7.1".equals(version), "plugin.yml version: " + version);

        System.out.println("PASS: give/throw/drink/tame/fill/move/retreat helpers, command stays off, 0.7.1");
    }

    private static void check(boolean ok, String message) {
        if (!ok) throw new AssertionError(message);
    }
}
