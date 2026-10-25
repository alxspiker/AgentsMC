package dev.agentsmc;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.block.data.Openable;
import org.bukkit.entity.Player;

import net.citizensnpcs.api.npc.NPC;

/** Open doors and fence gates in front of a navigating body, then close them behind. */
final class AgentDoors {
    private static final Map<UUID, List<Block>> opened = new HashMap<>();

    static void tick(NPC npc, Player p) {
        Block feet = p.getLocation().getBlock();
        BlockFace facing = p.getFacing();
        if (facing == BlockFace.UP || facing == BlockFace.DOWN || facing == BlockFace.SELF) facing = BlockFace.SOUTH;
        notice(npc, feet);
        notice(npc, feet.getRelative(BlockFace.UP));
        for (Block b : ahead(feet, facing)) notice(npc, b);
        List<Block> list = opened.get(npc.getUniqueId());
        if (list == null) return;
        int px = p.getLocation().getBlockX(), py = p.getLocation().getBlockY(), pz = p.getLocation().getBlockZ();
        Iterator<Block> it = list.iterator();
        while (it.hasNext()) {
            Block b = it.next();
            if (!(b.getBlockData() instanceof Openable o) || !o.isOpen()) { it.remove(); continue; }
            if (!leftBehind(px, py, pz, b.getX(), b.getY(), b.getZ())) continue;
            if (!swing(b.getType().name())) { it.remove(); continue; }
            o.setOpen(false);
            b.setBlockData(o, true);
            it.remove();
        }
        if (list.isEmpty()) opened.remove(npc.getUniqueId());
    }

    static void forget(UUID id) { opened.remove(id); }

    /** Open wood doors/gates along a wide corridor toward a destination so pathfinding is not rejected. */
    static void openToward(NPC npc, org.bukkit.Location from, org.bukkit.Location to) {
        if (from == null || to == null || from.getWorld() == null || !from.getWorld().equals(to.getWorld())) return;
        org.bukkit.util.Vector delta = to.toVector().subtract(from.toVector());
        if (delta.lengthSquared() < 1e-4) return;
        org.bukkit.util.Vector step = delta.clone().setY(0);
        if (step.lengthSquared() < 1e-4) step = new org.bukkit.util.Vector(0, 0, 1);
        else step.normalize();
        BlockFace facing = org.bukkit.block.BlockFace.SOUTH;
        if (Math.abs(step.getX()) >= Math.abs(step.getZ())) facing = step.getX() >= 0 ? org.bukkit.block.BlockFace.EAST : org.bukkit.block.BlockFace.WEST;
        else facing = step.getZ() >= 0 ? org.bukkit.block.BlockFace.SOUTH : org.bukkit.block.BlockFace.NORTH;
        BlockFace left = rotateLeft(facing), right = rotateRight(facing);
        int steps = Math.min(48, Math.max(4, (int) Math.ceil(Math.hypot(to.getX() - from.getX(), to.getZ() - from.getZ())) + 2));
        Block feet = from.getBlock();
        for (int i = 0; i < steps; i++) {
            for (Block b : ahead(feet, facing)) openIfNeeded(npc, b);
            for (Block b : ahead(feet.getRelative(left), facing)) openIfNeeded(npc, b);
            for (Block b : ahead(feet.getRelative(right), facing)) openIfNeeded(npc, b);
            feet = feet.getRelative(facing);
        }
    }
    private static BlockFace rotateLeft(BlockFace f) {
        return switch (f) { case NORTH -> BlockFace.WEST; case WEST -> BlockFace.SOUTH; case SOUTH -> BlockFace.EAST; default -> BlockFace.NORTH; };
    }
    private static BlockFace rotateRight(BlockFace f) {
        return switch (f) { case NORTH -> BlockFace.EAST; case EAST -> BlockFace.SOUTH; case SOUTH -> BlockFace.WEST; default -> BlockFace.NORTH; };
    }

    /** Wooden doors/gates/trapdoors a hand can toggle. Iron doors stay vanilla-locked. */
    static boolean handOpen(String raw) {
        if (raw == null || raw.isBlank()) return false;
        String n = raw.trim().toUpperCase(java.util.Locale.ROOT);
        if (n.startsWith("MINECRAFT:")) n = n.substring("MINECRAFT:".length());
        if (n.equals("IRON_DOOR")) return false;
        return n.endsWith("_DOOR") || n.equals("DOOR") || n.endsWith("_GATE") || n.contains("FENCE_GATE")
                || n.endsWith("_TRAPDOOR");
    }

    /** Doors and fence gates the body should close after passing. Trapdoors stay as-is. */
    static boolean swing(String raw) {
        if (raw == null || raw.isBlank()) return false;
        String n = raw.trim().toUpperCase(java.util.Locale.ROOT);
        if (n.startsWith("MINECRAFT:")) n = n.substring("MINECRAFT:".length());
        if (n.contains("TRAPDOOR") || n.equals("IRON_DOOR")) return false;
        return n.endsWith("_DOOR") || n.equals("DOOR") || n.endsWith("_GATE") || n.contains("FENCE_GATE");
    }

    /** True when the body has left the doorway (Chebyshev > 1). Adjacent still counts as passing through. */
    static boolean leftBehind(int px, int py, int pz, int doorX, int doorY, int doorZ) {
        int cheb = Math.max(Math.max(Math.abs(px - doorX), Math.abs(pz - doorZ)), Math.abs(py - doorY));
        return cheb > 1;
    }

    /** Villagers/vanilla must not slam spawn-protection doors. Agents still close doors they passed. */
    static boolean mayClose(boolean spawnProtected) {
        return !spawnProtected;
    }

    static boolean mayClose(Block b) {
        return b != null && mayClose(AgentProtection.blocked(b));
    }

    /** Plugin toggle so a survival click in the spawn square is not reverted. */
    static boolean toggle(Block b) {
        if (b == null || !handOpen(b.getType().name())) return false;
        if (!(b.getBlockData() instanceof Openable o)) return false;
        o.setOpen(!o.isOpen());
        b.setBlockData(o, true);
        return true;
    }

    /**
     * Human right-click on a wooden door/gate in spawn protection. Cancels the vanilla
     * interact (which reverts for non-ops) so the plugin can toggle it.
     */
    static boolean playerUse(org.bukkit.event.player.PlayerInteractEvent e) {
        if (e == null || e.getAction() != org.bukkit.event.block.Action.RIGHT_CLICK_BLOCK) return false;
        if (e.getHand() != null && e.getHand() != org.bukkit.inventory.EquipmentSlot.HAND) return false;
        org.bukkit.entity.Player p = e.getPlayer();
        if (p == null || p.hasMetadata("NPC")) return false;
        Block b = e.getClickedBlock();
        if (b == null || !handOpen(b.getType().name()) || !AgentProtection.blocked(b)) return false;
        e.setCancelled(true);
        e.setUseInteractedBlock(org.bukkit.event.Event.Result.DENY);
        e.setUseItemInHand(org.bukkit.event.Event.Result.DENY);
        return true;
    }

    /** Villagers must not slam village doors inside the spawn square. */
    static boolean villagerClose(org.bukkit.event.entity.EntityChangeBlockEvent e) {
        if (e == null || e.getBlock() == null) return false;
        if (!(e.getEntity() instanceof org.bukkit.entity.Villager)) return false;
        if (!handOpen(e.getBlock().getType().name())) return false;
        if (!AgentProtection.blocked(e.getBlock())) return false;
        e.setCancelled(true);
        return true;
    }

    private static void notice(NPC npc, Block b) {
        if (b == null) return;
        if (swing(b.getType().name())) {
            if (!(b.getBlockData() instanceof Openable o)) return;
            if (!o.isOpen()) openIfNeeded(npc, b);
            else remember(npc, b);
            return;
        }
        openIfNeeded(npc, b);
    }

    private static void remember(NPC npc, Block b) {
        if (npc == null || b == null) return;
        List<Block> list = opened.computeIfAbsent(npc.getUniqueId(), k -> new ArrayList<>());
        for (Block have : list) {
            if (have.getWorld() != null && have.getWorld().equals(b.getWorld())
                    && have.getX() == b.getX() && have.getY() == b.getY() && have.getZ() == b.getZ())
                return;
        }
        list.add(b);
    }

    private static void openIfNeeded(NPC npc, Block b) {
        if (!(b.getBlockData() instanceof Openable o) || o.isOpen()) return;
        if (!handOpen(b.getType().name()) && !b.getType().name().equalsIgnoreCase("IRON_TRAPDOOR")) return;
        o.setOpen(true);
        b.setBlockData(o, true);
        if (swing(b.getType().name())) remember(npc, b);
    }

    private static List<Block> ahead(Block feet, BlockFace facing) {
        List<Block> blocks = new ArrayList<>();
        Block step = feet.getRelative(facing);
        blocks.add(step);
        blocks.add(step.getRelative(BlockFace.UP));
        blocks.add(feet.getRelative(BlockFace.UP).getRelative(facing));
        blocks.add(step.getRelative(facing));
        blocks.add(step.getRelative(facing).getRelative(BlockFace.UP));
        return blocks;
    }

    private AgentDoors() {}
}
