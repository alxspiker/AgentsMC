package dev.agentsmc;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import org.bukkit.Location;
import org.bukkit.block.Block;
import org.bukkit.block.Furnace;
import org.bukkit.entity.HumanEntity;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.inventory.FurnaceExtractEvent;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryMoveItemEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;

/**
 * fb-27: furnace contents were taken with no trace. Remember the last few touches of every furnace
 * (agent smelt/fuel/transfer, player clicks and output extraction, hoppers, breaking) so
 * inspect_container can say who emptied it. In memory only; cleared on restart.
 */
final class AgentFurnaceLog implements Listener {
    static final int KEEP = 12;
    private static final Map<String, Deque<Map<String, Object>>> LOG = new ConcurrentHashMap<>();

    static String key(String world, int x, int y, int z) { return world + ":" + x + "," + y + "," + z; }

    static String key(Location l) {
        return l == null || l.getWorld() == null ? null : key(l.getWorld().getName(), l.getBlockX(), l.getBlockY(), l.getBlockZ());
    }

    static void record(String key, String who, String kind, String item, int amount, long at) {
        if (key == null) return;
        Deque<Map<String, Object>> q = LOG.computeIfAbsent(key, k -> new ArrayDeque<>());
        Map<String, Object> e = new LinkedHashMap<>();
        e.put("at", at); e.put("by", who == null ? "unknown" : who); e.put("kind", kind);
        if (item != null) { e.put("item", item); e.put("amount", amount); }
        synchronized (q) {
            q.addLast(e);
            while (q.size() > KEEP) q.removeFirst();
        }
    }

    static void record(Location l, String who, String kind, ItemStack s) {
        record(key(l), who, kind, s == null ? null : s.getType().name(), s == null ? 0 : s.getAmount(), System.currentTimeMillis());
    }

    static List<Map<String, Object>> history(String key) {
        Deque<Map<String, Object>> q = key == null ? null : LOG.get(key);
        if (q == null) return List.of();
        synchronized (q) { return new ArrayList<>(q); }
    }

    static void clear() { LOG.clear(); }

    /** Live furnace status for inspect_container. */
    static Map<String, Object> status(Block b) {
        Map<String, Object> out = new LinkedHashMap<>();
        if (!(b.getState() instanceof Furnace f)) return out;
        out.put("burnTime", (int) f.getBurnTime());
        out.put("cookTime", (int) f.getCookTime());
        out.put("cookTimeTotal", f.getCookTimeTotal());
        out.put("lit", f.getBurnTime() > 0);
        out.put("slots", "0 input, 1 fuel, 2 result");
        out.put("history", history(key(b.getLocation())));
        return out;
    }

    private static Location furnaceOf(Inventory inv) {
        if (inv == null) return null;
        try { return inv.getHolder() instanceof Furnace f ? f.getLocation() : null; }
        catch (RuntimeException e) { return null; }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void extract(FurnaceExtractEvent e) {
        record(e.getBlock().getLocation(), e.getPlayer().getName(), "extract_result",
                new ItemStack(e.getItemType(), Math.max(1, e.getItemAmount())));
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void click(InventoryClickEvent e) {
        Location at = furnaceOf(e.getView().getTopInventory());
        if (at == null) return;
        HumanEntity who = e.getWhoClicked();
        boolean top = e.getClickedInventory() != null && e.getClickedInventory().equals(e.getView().getTopInventory());
        if (!top && !e.isShiftClick()) return;
        ItemStack s = e.getCurrentItem();
        record(at, who.getName(), "click_" + e.getAction().name().toLowerCase() + (top ? "_slot" + e.getSlot() : ""), s);
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void hopper(InventoryMoveItemEvent e) {
        Location from = furnaceOf(e.getSource());
        if (from != null) record(from, "hopper", "hopper_out", e.getItem());
        Location to = furnaceOf(e.getDestination());
        if (to != null) record(to, "hopper", "hopper_in", e.getItem());
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void broken(BlockBreakEvent e) {
        if (e.getBlock().getState() instanceof Furnace)
            record(e.getBlock().getLocation(), e.getPlayer().getName(), "broken", null);
    }
}
