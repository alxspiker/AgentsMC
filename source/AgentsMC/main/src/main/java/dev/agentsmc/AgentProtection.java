package dev.agentsmc;

import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.block.BlockState;
import org.bukkit.entity.Player;
import org.bukkit.event.Event;
import org.bukkit.event.block.Action;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;

/**
 * Agents are never operators. World edits use the same spawn-protection radius
 * and Bukkit events as a survival player so vanilla and WorldGuard apply.
 */
final class AgentProtection {
    static String message(int radius) {
        return "spawn protection: blocked within " + radius + " blocks of spawn";
    }

    /**
     * Vanilla spawn protection is a Chebyshev square in XZ (max(|dx|,|dz|) ≤ radius),
     * not a Euclidean circle. {@code radius <= 0} never blocks.
     */
    static boolean inside(int x, int z, int spawnX, int spawnZ, int radius) {
        if (radius <= 0) return false;
        return Math.max(Math.abs(x - spawnX), Math.abs(z - spawnZ)) <= radius;
    }

    static int radius() {
        try { return Math.max(0, Bukkit.getSpawnRadius()); }
        catch (Throwable ignored) { return 0; }
    }

    static boolean blocked(Block b) {
        return b != null && blocked(b.getWorld(), b.getX(), b.getZ());
    }

    static boolean blocked(Location at) {
        return at != null && blocked(at.getWorld(), AgentSafeSpawn.blockCoord(at.getX()), AgentSafeSpawn.blockCoord(at.getZ()));
    }

    static boolean blocked(World world, int x, int z) {
        if (world == null) return false;
        Location spawn = world.getSpawnLocation();
        return inside(x, z, spawn.getBlockX(), spawn.getBlockZ(), radius());
    }

    /** Never treat the body as op for protection plugins or vanilla spawn checks. */
    static void asSurvival(Player p) {
        if (p == null) return;
        try { if (p.isOp()) p.setOp(false); } catch (Throwable ignored) {}
    }

    static void requireEdit(Player p, Block b) {
        asSurvival(p);
        if (blocked(b)) throw new IllegalArgumentException(message(radius()));
    }

    static boolean breakBlock(Player p, Block b) {
        asSurvival(p);
        requireEdit(p, b);
        BlockBreakEvent ev = new BlockBreakEvent(b, p);
        try { Bukkit.getPluginManager().callEvent(ev); } catch (Throwable ignored) {}
        if (ev.isCancelled()) {
            if (blocked(b)) throw new IllegalArgumentException(message(radius()));
            return false;
        }
        return p.breakBlock(b);
    }

    static void requirePlace(Player p, Block b, BlockPlaceEvent event) {
        asSurvival(p);
        requireEdit(p, b);
        try { Bukkit.getPluginManager().callEvent(event); } catch (Throwable ignored) {}
        if (event.isCancelled() || !event.canBuild()) {
            if (blocked(b)) throw new IllegalArgumentException(message(radius()));
            throw new IllegalArgumentException("server protection rejected the place");
        }
    }

    /** Door/gate/trapdoor use is not a build. Spawn protection still blocks break/place/dig. */
    static boolean openableUse(Block b) {
        return b != null && AgentDoors.handOpen(b.getType().name());
    }

    /** Chest/furnace/barrel open is not a build. Vanilla spawn protection allows it. */
    static boolean containerUse(String raw) {
        if (raw == null || raw.isBlank()) return false;
        String n = raw.trim().toLowerCase(java.util.Locale.ROOT);
        if (n.startsWith("minecraft:")) n = n.substring("minecraft:".length());
        return n.equals("chest") || n.equals("trapped_chest") || n.equals("ender_chest") || n.endsWith("_chest")
                || n.equals("barrel") || n.equals("hopper") || n.contains("shulker_box")
                || n.equals("furnace") || n.equals("blast_furnace") || n.equals("smoker")
                || n.equals("dispenser") || n.equals("dropper") || n.equals("brewing_stand") || n.equals("lectern");
    }

    static boolean containerUse(Block b) {
        if (b == null) return false;
        try { if (b.getState() instanceof org.bukkit.block.Container) return true; } catch (RuntimeException ignored) {}
        return containerUse(b.getType().name());
    }

    static boolean interact(Player p, Block b, Action action) {
        asSurvival(p);
        if (!openableUse(b) && !containerUse(b)) requireEdit(p, b);
        ItemStack hand = p.getInventory().getItemInMainHand();
        PlayerInteractEvent event = new PlayerInteractEvent(p, action, hand, b, BlockFace.UP, EquipmentSlot.HAND);
        Bukkit.getPluginManager().callEvent(event);
        if (event.isCancelled() || event.useInteractedBlock() == Event.Result.DENY) {
            if ((openableUse(b) || containerUse(b)) && blocked(b)) return true;
            if (blocked(b)) throw new IllegalArgumentException(message(radius()));
            return false;
        }
        return true;
    }

    static BlockPlaceEvent placeEvent(Block placed, BlockState previous, Block against, ItemStack hand, Player p) {
        return new BlockPlaceEvent(placed, previous, against, hand == null ? new ItemStack(org.bukkit.Material.AIR) : hand.clone(),
                p, true, EquipmentSlot.HAND);
    }

    private AgentProtection() {}
}
