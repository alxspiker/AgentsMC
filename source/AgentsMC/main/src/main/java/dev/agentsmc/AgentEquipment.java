package dev.agentsmc;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;

/**
 * Player inventory slots 0–40: storage 0–35, boots 36, leggings 37,
 * chest 38, helmet 39, offhand 40. {@code equip} auto-wears armor,
 * elytra, carved pumpkins, shields and torches, and unequips worn slots.
 */
final class AgentEquipment {
    static final int HOTBAR_MAX = 8, STORAGE_MAX = 35;
    static final int BOOTS = 36, LEGGINGS = 37, CHEST = 38, HELMET = 39, OFFHAND = 40, LAST = 40;
    /** Sentinel: select the hotbar instead of wearing. */
    static final int SELECT_HOTBAR = -2;
    /** Sentinel: move a worn item back into storage. */
    static final int UNEQUIP = -3;

    static boolean inRange(int slot) { return slot >= 0 && slot <= LAST; }
    static boolean storage(int slot) { return slot >= 0 && slot <= STORAGE_MAX; }
    static boolean worn(int slot) { return slot >= BOOTS && slot <= OFFHAND; }

    static String name(int slot) {
        return switch (slot) {
            case BOOTS -> "boots";
            case LEGGINGS -> "leggings";
            case CHEST -> "chestplate";
            case HELMET -> "helmet";
            case OFFHAND -> "offhand";
            default -> slot <= HOTBAR_MAX ? "hotbar" : "storage";
        };
    }

    static String rangeError() {
        return "slot must be 0..40 (0-8 hotbar, 9-35 storage, 36 boots, 37 leggings, 38 chest, 39 helmet, 40 offhand)";
    }

    static int parseDest(String raw) {
        if (raw == null || raw.isBlank()) return -1;
        String n = raw.trim().toLowerCase(Locale.ROOT).replace('-', '_');
        return switch (n) {
            case "boots", "feet", "36" -> BOOTS;
            case "leggings", "legs", "37" -> LEGGINGS;
            case "chestplate", "chest", "elytra", "38" -> CHEST;
            case "helmet", "head", "pumpkin", "39" -> HELMET;
            case "offhand", "off_hand", "shield", "40" -> OFFHAND;
            case "hand", "mainhand", "main_hand" -> SELECT_HOTBAR;
            default -> -1;
        };
    }

    static String bare(String material) {
        if (material == null) return "";
        String n = material.trim().toLowerCase(Locale.ROOT);
        return n.startsWith("minecraft:") ? n.substring("minecraft:".length()) : n;
    }

    /** Armor / elytra / pumpkin → body slot; shield / torch → offhand; else -1. */
    static int wearSlot(String material) {
        String n = bare(material);
        if (n.isEmpty()) return -1;
        if (n.endsWith("_helmet") || n.equals("turtle_helmet") || n.equals("carved_pumpkin")
                || n.endsWith("_head") || n.endsWith("_skull") || n.equals("player_head"))
            return HELMET;
        if (n.endsWith("_chestplate") || n.equals("elytra")) return CHEST;
        if (n.endsWith("_leggings")) return LEGGINGS;
        if (n.endsWith("_boots")) return BOOTS;
        if (n.equals("shield") || n.equals("torch") || n.equals("soul_torch") || n.equals("redstone_torch")
                || n.equals("lantern") || n.equals("soul_lantern") || n.equals("totem_of_undying"))
            return OFFHAND;
        return -1;
    }

    /**
     * Where {@code equip} should put the item in {@code from}.
     * {@link #UNEQUIP} worn slot with no explicit dest; {@link #SELECT_HOTBAR}
     * for a non-wearable hotbar item (legacy select).
     */
    static int destination(int from, String material, String requestedTo) {
        int explicit = parseDest(requestedTo);
        if (explicit == SELECT_HOTBAR) return SELECT_HOTBAR;
        if (explicit >= 0) return explicit;
        if (worn(from)) return UNEQUIP;
        int auto = wearSlot(material);
        if (auto >= 0) return auto;
        if (from >= 0 && from <= HOTBAR_MAX) return SELECT_HOTBAR;
        return -1;
    }

    /**
     * Hotbar slot to hold. Storage {@code to=hand} moves into an empty hotbar
     * slot, or swaps with the current held slot.
     */
    static int handSlot(int from, int held, boolean[] hotbarOccupied) {
        if (from >= 0 && from <= HOTBAR_MAX) return from;
        if (hotbarOccupied != null)
            for (int i = 0; i <= HOTBAR_MAX && i < hotbarOccupied.length; i++)
                if (!hotbarOccupied[i]) return i;
        return Math.max(0, Math.min(HOTBAR_MAX, held));
    }

    static boolean[] occupiedHotbar(PlayerInventory inv) {
        boolean[] occ = new boolean[HOTBAR_MAX + 1];
        if (inv == null) return occ;
        for (int i = 0; i <= HOTBAR_MAX; i++) {
            ItemStack s = inv.getItem(i);
            occ[i] = s != null && !s.getType().isAir();
        }
        return occ;
    }

    static boolean allowedIn(String material, int dest, boolean explicit) {
        if (dest == OFFHAND && explicit) return true;
        int auto = wearSlot(material);
        if (dest == OFFHAND) return auto == OFFHAND;
        return auto == dest;
    }

    static int firstEmpty(boolean[] occupied) {
        if (occupied == null) return -1;
        for (int i = 0; i < occupied.length && i <= STORAGE_MAX; i++) if (!occupied[i]) return i;
        return -1;
    }

    static Map<String, Object> equip(Player player, int from, String requestedTo) {
        if (!inRange(from)) throw new IllegalArgumentException(rangeError());
        PlayerInventory inv = player.getInventory();
        ItemStack src = inv.getItem(from);
        boolean empty = src == null || src.getType().isAir();
        int dest = destination(from, empty ? "" : src.getType().name(), requestedTo);
        if (dest == UNEQUIP) {
            if (empty) throw new IllegalArgumentException("Nothing equipped in " + name(from));
            int back = firstEmpty(inv);
            if (back < 0) throw new IllegalArgumentException("Inventory is full; cannot unequip " + name(from));
            inv.setItem(back, src.clone());
            inv.setItem(from, null);
            return result("unequipped", true, "from", from, "fromName", name(from), "to", back, "item", item(src));
        }
        if (dest == SELECT_HOTBAR) {
            if (empty) throw new IllegalArgumentException("Empty slot " + from);
            int hand = handSlot(from, inv.getHeldItemSlot(), occupiedHotbar(inv));
            if (from != hand) {
                ItemStack there = inv.getItem(hand);
                inv.setItem(hand, src.clone());
                inv.setItem(from, there == null || there.getType().isAir() ? null : there.clone());
            }
            inv.setHeldItemSlot(hand);
            Map<String, Object> out = result("equipped", true, "from", from, "to", hand, "toName", "hand",
                    "hand", item(inv.getItemInMainHand()), "worn", false);
            return out;
        }
        if (dest < 0 || !inRange(dest))
            throw new IllegalArgumentException((empty ? "Empty slot " + from : src.getType().name())
                    + " is not wearable armor, elytra, carved pumpkin, shield, or torch");
        if (empty) throw new IllegalArgumentException("Empty slot " + from);
        boolean explicit = requestedTo != null && !requestedTo.isBlank();
        if (!allowedIn(src.getType().name(), dest, explicit))
            throw new IllegalArgumentException(src.getType().name() + " cannot be worn in " + name(dest));
        if (from == dest)
            return result("equipped", true, "already", true, "from", from, "to", dest, "toName", name(dest), "item", item(src));
        ItemStack there = inv.getItem(dest);
        inv.setItem(dest, src.clone());
        inv.setItem(from, there == null || there.getType().isAir() ? null : there.clone());
        Map<String, Object> out = result("equipped", true, "from", from, "to", dest, "toName", name(dest), "item", item(src));
        if (there != null && !there.getType().isAir()) out.put("swapped", item(there));
        return out;
    }

    static List<Object> items(ItemStack[] contents) {
        List<Object> items = new ArrayList<>();
        if (contents == null) return items;
        for (int i = 0; i < contents.length && i <= LAST; i++) add(items, i, contents[i]);
        return items;
    }

    static List<Object> items(Player player) {
        return items(player.getInventory().getContents());
    }

    static Map<String, Object> view(Player player) {
        PlayerInventory inv = player.getInventory();
        Map<String, Object> eq = new LinkedHashMap<>();
        eq.put("helmet", item(inv.getHelmet()));
        eq.put("chestplate", item(inv.getChestplate()));
        eq.put("leggings", item(inv.getLeggings()));
        eq.put("boots", item(inv.getBoots()));
        eq.put("offhand", item(inv.getItemInOffHand()));
        eq.put("mainHand", item(inv.getItemInMainHand()));
        eq.put("heldSlot", inv.getHeldItemSlot());
        return eq;
    }

    /** Pad/trim a persisted 41-slot snapshot so armor (36-39) and offhand (40) survive restore. */
    static ItemStack[] restoreSlots(ItemStack[] saved, int size) {
        int n = Math.max(size, LAST + 1);
        ItemStack[] full = new ItemStack[n];
        if (saved == null) return full;
        for (int i = 0; i < n && i < saved.length; i++)
            full[i] = saved[i] == null || saved[i].getType().isAir() ? null : saved[i];
        return full;
    }

    static boolean hasWorn(ItemStack[] contents) {
        if (contents == null) return false;
        for (int s : new int[]{BOOTS, LEGGINGS, CHEST, HELMET, OFFHAND}) {
            if (s < contents.length && contents[s] != null && !contents[s].getType().isAir()) return true;
        }
        return false;
    }

    static void applyWorn(PlayerInventory inv, ItemStack[] full) {
        if (inv == null || full == null || full.length <= OFFHAND) return;
        try {
            inv.setBoots(full[BOOTS]);
            inv.setLeggings(full[LEGGINGS]);
            inv.setChestplate(full[CHEST]);
            inv.setHelmet(full[HELMET]);
            inv.setItemInOffHand(full[OFFHAND]);
        } catch (RuntimeException ignored) {}
    }

    static Map<String, Object> view(ItemStack[] contents, int heldSlot) {
        Map<String, Object> eq = new LinkedHashMap<>();
        eq.put("helmet", stackAt(contents, HELMET));
        eq.put("chestplate", stackAt(contents, CHEST));
        eq.put("leggings", stackAt(contents, LEGGINGS));
        eq.put("boots", stackAt(contents, BOOTS));
        eq.put("offhand", stackAt(contents, OFFHAND));
        eq.put("mainHand", stackAt(contents, Math.max(0, Math.min(HOTBAR_MAX, heldSlot))));
        eq.put("heldSlot", heldSlot);
        return eq;
    }

    private static void add(List<Object> items, int slot, ItemStack stack) {
        if (stack == null || stack.getType().isAir()) return;
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("slot", slot);
        row.put("material", stack.getType().name());
        row.put("amount", stack.getAmount());
        if (worn(slot)) row.put("name", name(slot));
        items.add(row);
    }

    private static Object stackAt(ItemStack[] contents, int slot) {
        if (contents == null || slot < 0 || slot >= contents.length) return null;
        return item(contents[slot]);
    }

    private static Object item(ItemStack stack) {
        if (stack == null || stack.getType().isAir()) return null;
        return result("material", stack.getType().name(), "amount", stack.getAmount());
    }

    private static int firstEmpty(PlayerInventory inv) {
        for (int i = 0; i <= STORAGE_MAX; i++) {
            ItemStack s = inv.getItem(i);
            if (s == null || s.getType().isAir()) return i;
        }
        return -1;
    }

    private static Map<String, Object> result(Object... pairs) {
        Map<String, Object> out = new LinkedHashMap<>();
        for (int i = 0; i < pairs.length; i += 2) out.put((String) pairs[i], pairs[i + 1]);
        return out;
    }

    private AgentEquipment() {}
}
