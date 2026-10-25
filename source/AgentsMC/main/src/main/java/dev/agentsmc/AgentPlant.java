package dev.agentsmc;

import java.util.Locale;
import java.util.Set;

/** Server-free plant targeting and seed mapping (fb-4). */
final class AgentPlant {
    static final Set<String> SEEDS = Set.of(
            "WHEAT_SEEDS", "BEETROOT_SEEDS", "CARROT", "POTATO",
            "MELON_SEEDS", "PUMPKIN_SEEDS", "NETHER_WART");

    static String cropOf(String item) {
        if (item == null) return null;
        return switch (item.trim().toUpperCase(Locale.ROOT)) {
            case "WHEAT_SEEDS" -> "WHEAT";
            case "BEETROOT_SEEDS" -> "BEETROOTS";
            case "CARROT" -> "CARROTS";
            case "POTATO" -> "POTATOES";
            case "MELON_SEEDS" -> "MELON_STEM";
            case "PUMPKIN_SEEDS" -> "PUMPKIN_STEM";
            case "NETHER_WART" -> "NETHER_WART";
            default -> null;
        };
    }

    static boolean plantable(String item) {
        return cropOf(item) != null;
    }

    static boolean isSoil(String material) {
        if (material == null) return false;
        String n = material.trim().toUpperCase(Locale.ROOT);
        return n.equals("FARMLAND") || n.equals("SOUL_SAND");
    }

    static boolean soilOk(String crop, String soil) {
        if (crop == null || soil == null) return false;
        String c = crop.trim().toUpperCase(Locale.ROOT);
        String s = soil.trim().toUpperCase(Locale.ROOT);
        if (c.equals("NETHER_WART")) return s.equals("SOUL_SAND");
        return s.equals("FARMLAND");
    }

    /**
     * Crop cell for a clicked block. Farmland/soul sand plants in the air above;
     * an empty cell plants here (soil is below).
     * @return {x,y,z} or null when the clicked cell is neither soil nor empty
     */
    static int[] cropCell(int x, int y, int z, String targetMaterial, boolean targetEmpty) {
        if (isSoil(targetMaterial)) return new int[]{x, y + 1, z};
        if (targetEmpty) return new int[]{x, y, z};
        return null;
    }

    static String occupiedError(String occupied, int x, int y, int z, boolean targetedSoil) {
        String what = occupied == null || occupied.isBlank() ? "a block" : occupied;
        if (targetedSoil)
            return "Crop cell at " + x + "," + y + "," + z + " above the farmland is occupied by " + what;
        return "Crop position " + x + "," + y + "," + z + " is occupied by " + what
                + "; target farmland or the air above it";
    }

    static String unsupportedHeld(String held) {
        String name = held == null || held.isBlank() ? "nothing" : held;
        return "Held " + name + " is not plantable; select a hotbar slot with seeds"
                + " (wheat_seeds, beetroot_seeds, carrot, potato, melon_seeds, pumpkin_seeds, nether_wart)";
    }

    static String soilError(String crop) {
        if (crop != null && crop.equalsIgnoreCase("NETHER_WART"))
            return "Nether wart needs soul sand under the crop cell";
        return "Plant needs farmland under the crop cell (or soul sand for nether wart)";
    }

    private AgentPlant() {}
}
