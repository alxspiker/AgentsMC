package dev.agentsmc;

/** Offline plant targeting and seed mapping (fb-4). */
public final class PlantTest {
    public static void main(String[] args) {
        check("WHEAT".equals(AgentPlant.cropOf("wheat_seeds"))
                && "CARROTS".equals(AgentPlant.cropOf("CARROT"))
                && "NETHER_WART".equals(AgentPlant.cropOf("nether_wart")), "crop map");
        check(AgentPlant.plantable("WHEAT_SEEDS") && !AgentPlant.plantable("DIAMOND_SWORD"), "plantable seeds only");
        check(AgentPlant.isSoil("FARMLAND") && AgentPlant.isSoil("soul_sand") && !AgentPlant.isSoil("DIRT"), "soil");
        check(AgentPlant.soilOk("WHEAT", "FARMLAND") && !AgentPlant.soilOk("WHEAT", "SOUL_SAND")
                && AgentPlant.soilOk("NETHER_WART", "SOUL_SAND"), "soil vs crop");

        int[] above = AgentPlant.cropCell(-7, 85, -32, "FARMLAND", false);
        check(above != null && above[0] == -7 && above[1] == 86 && above[2] == -32,
                "farmland plants in the air above: " + java.util.Arrays.toString(above));
        int[] air = AgentPlant.cropCell(-7, 86, -32, "AIR", true);
        check(air != null && air[1] == 86, "empty air plants here");
        check(AgentPlant.cropCell(-7, 85, -32, "DIRT", false) == null, "dirt is not a crop cell");

        String occupiedAir = AgentPlant.occupiedError("DIRT", -7, 85, -32, false);
        check(occupiedAir.contains("farmland") && occupiedAir.contains("-7,85,-32"),
                "occupied air names farmland: " + occupiedAir);
        String occupiedCrop = AgentPlant.occupiedError("WHEAT", -7, 86, -32, true);
        check(occupiedCrop.contains("-7,86,-32") && occupiedCrop.toLowerCase().contains("farmland")
                && occupiedCrop.contains("WHEAT"), "occupied above farmland: " + occupiedCrop);

        String sword = AgentPlant.unsupportedHeld("DIAMOND_SWORD");
        check(sword.contains("DIAMOND_SWORD") && sword.toLowerCase().contains("wheat_seeds")
                && sword.toLowerCase().contains("select"), "held sword names seeds: " + sword);

        String version = AgentVersion.fromPluginYml();
        check("0.7.5".equals(version), "plugin.yml version: " + version);
        System.out.println("PASS: plant farmland retarget, held-seed errors, 0.7.4");
    }

    private static void check(boolean ok, String message) {
        if (!ok) throw new AssertionError(message);
    }
}
