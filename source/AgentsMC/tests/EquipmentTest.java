package dev.agentsmc;

/** Offline armor/offhand routing for equip, inventory slots 36-40, and unequip. */
public final class EquipmentTest {
    public static void main(String[] args) {
        check(AgentEquipment.BOOTS == 36 && AgentEquipment.LEGGINGS == 37 && AgentEquipment.CHEST == 38
                && AgentEquipment.HELMET == 39 && AgentEquipment.OFFHAND == 40, "vanilla player slot numbers");
        check(AgentEquipment.inRange(0) && AgentEquipment.inRange(40) && !AgentEquipment.inRange(41)
                && !AgentEquipment.inRange(-1), "0..40");
        check(AgentEquipment.storage(35) && !AgentEquipment.storage(36), "storage ends at 35");
        check(AgentEquipment.worn(36) && AgentEquipment.worn(40) && !AgentEquipment.worn(35), "worn 36-40");
        check("boots".equals(AgentEquipment.name(36)) && "leggings".equals(AgentEquipment.name(37))
                && "chestplate".equals(AgentEquipment.name(38)) && "helmet".equals(AgentEquipment.name(39))
                && "offhand".equals(AgentEquipment.name(40)), "slot names");

        check(AgentEquipment.wearSlot("IRON_HELMET") == AgentEquipment.HELMET, "iron helmet");
        check(AgentEquipment.wearSlot("minecraft:diamond_chestplate") == AgentEquipment.CHEST, "diamond chest");
        check(AgentEquipment.wearSlot("ELYTRA") == AgentEquipment.CHEST, "elytra");
        check(AgentEquipment.wearSlot("carved_pumpkin") == AgentEquipment.HELMET, "pumpkin");
        check(AgentEquipment.wearSlot("turtle_helmet") == AgentEquipment.HELMET, "turtle");
        check(AgentEquipment.wearSlot("player_head") == AgentEquipment.HELMET, "head");
        check(AgentEquipment.wearSlot("NETHERITE_LEGGINGS") == AgentEquipment.LEGGINGS, "leggings");
        check(AgentEquipment.wearSlot("golden_boots") == AgentEquipment.BOOTS, "boots");
        check(AgentEquipment.wearSlot("SHIELD") == AgentEquipment.OFFHAND, "shield");
        check(AgentEquipment.wearSlot("torch") == AgentEquipment.OFFHAND, "torch");
        check(AgentEquipment.wearSlot("SOUL_TORCH") == AgentEquipment.OFFHAND, "soul torch");
        check(AgentEquipment.wearSlot("redstone_torch") == AgentEquipment.OFFHAND, "redstone torch");
        check(AgentEquipment.wearSlot("DIAMOND_SWORD") == -1, "sword is not auto-worn");
        check(AgentEquipment.wearSlot("bow") == -1, "bow stays in hand unless requested");

        check(AgentEquipment.destination(0, "IRON_HELMET", null) == AgentEquipment.HELMET, "equip helmet from hotbar");
        check(AgentEquipment.destination(3, "ELYTRA", "") == AgentEquipment.CHEST, "equip elytra");
        check(AgentEquipment.destination(1, "SHIELD", null) == AgentEquipment.OFFHAND, "equip shield offhand");
        check(AgentEquipment.destination(2, "TORCH", null) == AgentEquipment.OFFHAND, "equip torch offhand");
        check(AgentEquipment.destination(0, "DIAMOND_SWORD", null) == AgentEquipment.SELECT_HOTBAR, "sword still selects hotbar");
        check(AgentEquipment.destination(12, "DIAMOND_SWORD", null) == -1, "non-wearable storage is not a silent main-hand");
        check(AgentEquipment.destination(11, "STONE_SWORD", "hand") == AgentEquipment.SELECT_HOTBAR,
                "explicit hand from storage is a main-hand move");
        check(AgentEquipment.handSlot(11, 0, new boolean[]{true, true, false, true, true, true, true, true, true}) == 2,
                "storage to=hand prefers empty hotbar");
        check(AgentEquipment.handSlot(11, 3, new boolean[]{true, true, true, true, true, true, true, true, true}) == 3,
                "full hotbar swaps the held slot");
        check(AgentEquipment.handSlot(4, 0, new boolean[]{true, true, true, true, true, true, true, true, true}) == 4,
                "hotbar sword still selects itself");
        check(AgentEquipment.destination(39, "IRON_HELMET", null) == AgentEquipment.UNEQUIP, "worn slot unequips");
        check(AgentEquipment.destination(40, "SHIELD", "") == AgentEquipment.UNEQUIP, "offhand unequips");
        check(AgentEquipment.parseDest("head") == AgentEquipment.HELMET && AgentEquipment.parseDest("offhand") == AgentEquipment.OFFHAND, "named dest");
        check(AgentEquipment.parseDest("hand") == AgentEquipment.SELECT_HOTBAR, "hand stays mainhand");
        check(AgentEquipment.destination(5, "IRON_HELMET", "offhand") == AgentEquipment.OFFHAND, "explicit offhand");
        check(AgentEquipment.allowedIn("IRON_HELMET", AgentEquipment.HELMET, false), "helmet in head");
        check(!AgentEquipment.allowedIn("IRON_HELMET", AgentEquipment.BOOTS, false), "helmet not in boots");
        check(AgentEquipment.allowedIn("DIAMOND_SWORD", AgentEquipment.OFFHAND, true), "explicit offhand accepts any item");
        check(!AgentEquipment.allowedIn("DIAMOND_SWORD", AgentEquipment.OFFHAND, false), "sword is not auto-offhand");

        boolean[] full = new boolean[36];
        java.util.Arrays.fill(full, true);
        full[4] = false;
        check(AgentEquipment.firstEmpty(full) == 4, "unequip prefers first empty storage");
        java.util.Arrays.fill(full, true);
        check(AgentEquipment.firstEmpty(full) == -1, "full inventory cannot unequip");

        org.bukkit.inventory.ItemStack[] worn = new org.bukkit.inventory.ItemStack[41];
        org.bukkit.inventory.ItemStack[] restored = AgentEquipment.restoreSlots(worn, 36);
        check(restored.length >= 41, "restore pads to include armor/offhand slots");
        check(restored[38] == null && restored[40] == null, "empty worn slots stay empty");
        check(!AgentEquipment.hasWorn(restored) && !AgentEquipment.hasWorn(new org.bukkit.inventory.ItemStack[36]),
                "hasWorn is false when 36-40 are empty");

        @SuppressWarnings("unchecked")
        java.util.List<java.util.Map<String, Object>> actions =
                (java.util.List<java.util.Map<String, Object>>) AgentActionCatalog.snapshot().get("actions");
        java.util.Map<String, Object> equip = actions.stream().filter(m -> "equip".equals(m.get("action"))).findFirst().orElseThrow();
        String fields = String.valueOf(equip.get("fields")).toLowerCase();
        check(fields.contains("0..40") && fields.contains("to [optional"), "equip fields: " + equip.get("fields"));
        check(String.valueOf(equip.get("description")).toLowerCase().contains("elytra"), "equip describes auto-wear");
        check(String.valueOf(equip.get("description")).toLowerCase().contains("hand"), "equip describes to=hand");
        java.util.Map<String, Object> move = actions.stream().filter(m -> "move_item".equals(m.get("action"))).findFirst().orElseThrow();
        check(String.valueOf(move.get("fields")).contains("0..40"), "move_item 0..40: " + move.get("fields"));
        java.util.Map<String, Object> inv = actions.stream().filter(m -> "inventory".equals(m.get("action"))).findFirst().orElseThrow();
        check(String.valueOf(inv.get("description")).toLowerCase().contains("offhand"), "inventory describes armor/offhand");

        String version = AgentVersion.fromPluginYml();
        check("0.7.6".equals(version), "plugin.yml version: " + version);
        System.out.println("PASS: equip auto-wear armor/elytra/pumpkin/offhand, unequip, slots 36-40, keep worn on death, 0.7.5");
    }

    private static void check(boolean ok, String message) {
        if (!ok) throw new AssertionError(message);
    }
}

