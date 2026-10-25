package dev.agentsmc;

import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

import org.bukkit.entity.Player;
import org.bukkit.entity.Pose;

import net.citizensnpcs.api.npc.NPC;
import net.citizensnpcs.trait.EntityPoseTrait;
import net.citizensnpcs.trait.SleepTrait;
import net.citizensnpcs.util.NMS;

/**
 * Citizens can leave a player-NPC in {@code Pose.SLEEPING} after {@code Player.sleep}
 * even when the body is walking. Wake must clear SleepTrait, EntityPoseTrait, the
 * Bukkit pose, and the NMS metadata so other viewers stop seeing a lying body.
 */
final class AgentSleep {
    static final String STANDING = "standing";
    static final String SLEEPING = "sleeping";
    static final Set<String> WAKE_ACTIONS = Set.of(
            "goto", "follow", "move", "stop", "jump", "climb", "swim", "sprint",
            "fight", "kill", "protect", "retreat", "harvest", "dig", "break", "place",
            "collect", "pickup", "portal", "use_portal", "respawn", "mount", "dismount", "attack", "survey", "walkmap");

    static boolean shouldWakeOnAction(String action) {
        return action != null && WAKE_ACTIONS.contains(action.toLowerCase(Locale.ROOT));
    }

    static boolean lying(String pose, boolean bukkitSleeping, boolean traitSleeping) {
        if (bukkitSleeping || traitSleeping) return true;
        return pose != null && pose.equalsIgnoreCase(SLEEPING);
    }

    static String displayPose(String pose, boolean sleeping) {
        if (sleeping) return SLEEPING;
        if (pose == null || pose.isBlank()) return STANDING;
        return pose.toLowerCase(Locale.ROOT);
    }

    static Map<String, Object> view(String pose, boolean bukkitSleeping, boolean traitSleeping) {
        boolean sleep = lying(pose, bukkitSleeping, traitSleeping);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("pose", displayPose(pose, sleep));
        out.put("sleeping", sleep);
        return out;
    }

    static Map<String, Object> view(Player player, NPC npc) {
        String pose = null;
        boolean bukkit = false, trait = false;
        if (player != null) {
            try { pose = player.getPose().name(); } catch (RuntimeException ignored) {}
            try { bukkit = player.isSleeping(); } catch (RuntimeException ignored) {}
        }
        if (npc != null) {
            try {
                if (npc.hasTrait(SleepTrait.class)) trait = npc.getOrAddTrait(SleepTrait.class).isSleeping();
            } catch (RuntimeException ignored) {}
            if (pose == null) {
                try {
                    EntityPoseTrait held = npc.getOrAddTrait(EntityPoseTrait.class);
                    if (held.getPose() != null) pose = held.getPose().name();
                } catch (RuntimeException ignored) {}
            }
        }
        return view(pose, bukkit, trait);
    }

    static void attach(Map<String, Object> dest, Player player, NPC npc) {
        dest.putAll(view(player, npc));
    }

    /** True when the body is (or is still shown as) lying down. */
    static boolean lying(Player player, NPC npc) {
        Map<String, Object> v = view(player, npc);
        return Boolean.TRUE.equals(v.get("sleeping"));
    }

    /**
     * Fully clear the sleeping pose. Safe to call when already standing.
     * Returns whether the body looked asleep before the clear.
     */
    static boolean stand(NPC npc, Player player) {
        boolean was = lying(player, npc);
        if (npc != null) {
            try {
                if (npc.hasTrait(SleepTrait.class)) npc.getOrAddTrait(SleepTrait.class).setSleeping(null);
            } catch (RuntimeException ignored) {}
            try { npc.getOrAddTrait(EntityPoseTrait.class).setPose(EntityPoseTrait.EntityPose.STANDING); }
            catch (RuntimeException ignored) {}
        }
        if (player != null) {
            try { if (player.isSleeping()) player.wakeup(false); } catch (RuntimeException ignored) {}
            try { NMS.sleep(player, false); } catch (Throwable ignored) {}
            try { player.setPose(Pose.STANDING); } catch (RuntimeException ignored) {}
            try { NMS.setPose(player, EntityPoseTrait.EntityPose.STANDING); } catch (Throwable ignored) {}
            try { NMS.markPoseDirty(player); } catch (Throwable ignored) {}
        }
        if (npc != null) {
            try { npc.getOrAddTrait(EntityPoseTrait.class).setPose(null); } catch (RuntimeException ignored) {}
        }
        return was;
    }

    /**
     * Head-half block of a bed given either half: FOOT + FACING step (NORTH -z, SOUTH +z, EAST +x, WEST -x).
     * Vanilla lays the head on the sleeping position and orients the body by FACING, so the foot renders reversed.
     */
    static int[] bedHead(int x, int y, int z, String facing, boolean isHead) {
        if (isHead || facing == null) return new int[]{x, y, z};
        switch (facing.toUpperCase(Locale.ROOT)) {
            case "NORTH": return new int[]{x, y, z - 1};
            case "SOUTH": return new int[]{x, y, z + 1};
            case "EAST": return new int[]{x + 1, y, z};
            case "WEST": return new int[]{x - 1, y, z};
            default: return new int[]{x, y, z};
        }
    }

    /**
     * Body yaw for a lying body. Without a client-side bed position the vanilla renderer rotates a SLEEPING
     * body by its body yaw exactly where it would use sleepDirectionToRotation(bed FACING), so match that table
     * (SOUTH 90, WEST 0, NORTH 270, EAST 180). 0.6.78 used the standing-look table and lay 90 degrees off.
     */
    static float bedYaw(String facing) {
        switch (facing == null ? "" : facing.toUpperCase(Locale.ROOT)) {
            case "SOUTH": return 90f;
            case "WEST": return 0f;
            case "NORTH": return 270f;
            case "EAST": return 180f;
            default: return 0f;
        }
    }

    /** Vanilla: beds work from 12542 to 23459 (or in a thunderstorm). Outside that, a lying body should get up. */
    static boolean shouldAutoWake(long worldTime, boolean thundering) {
        if (thundering) return false;
        long t = ((worldTime % 24000) + 24000) % 24000;
        return t < 12542 || t > 23459;
    }

    /**
     * Ordered stand-up candidates around a bed (head at hx,hz; foot one step back against FACING):
     * the two long sides of the head, then of the foot, then past the foot end, then past the head end.
     * Same floor Y as the bed block. Returns the first cell the predicate accepts, else null.
     */
    static int[] wakeSpot(int hx, int hy, int hz, String facing, java.util.function.Predicate<int[]> standable) {
        int fx = 0, fz = 0;
        switch (facing == null ? "" : facing.toUpperCase(Locale.ROOT)) {
            case "NORTH": fz = -1; break;
            case "SOUTH": fz = 1; break;
            case "EAST": fx = 1; break;
            case "WEST": fx = -1; break;
            default: fz = 1;
        }
        int sx = -fz, sz = fx; // perpendicular
        int footX = hx - fx, footZ = hz - fz;
        int[][] order = {
                {hx + sx, hz + sz}, {hx - sx, hz - sz},
                {footX + sx, footZ + sz}, {footX - sx, footZ - sz},
                {footX - fx, footZ - fz}, {hx + fx, hz + fz},
                {footX - fx + sx, footZ - fz + sz}, {footX - fx - sx, footZ - fz - sz},
                {hx + fx + sx, hz + fz + sz}, {hx + fx - sx, hz + fz - sz}};
        for (int dy : new int[]{0, 1, -1})
            for (int[] c : order) {
                int[] cell = {c[0], hy + dy, c[1]};
                if (standable.test(cell)) return cell;
            }
        return null;
    }

    /** Live: HEAD half of the bed at {@code at}. */
    static org.bukkit.Location headOf(org.bukkit.Location at) {
        if (at == null || at.getWorld() == null) return at;
        try {
            org.bukkit.block.Block b = at.getBlock();
            if (b.getBlockData() instanceof org.bukkit.block.data.type.Bed bed) {
                int[] h = bedHead(b.getX(), b.getY(), b.getZ(), bed.getFacing().name(),
                        bed.getPart() == org.bukkit.block.data.type.Bed.Part.HEAD);
                org.bukkit.Location head = new org.bukkit.Location(at.getWorld(), h[0], h[1], h[2]);
                if (head.getBlock().getBlockData() instanceof org.bukkit.block.data.type.Bed) return head;
            }
            return b.getLocation();
        } catch (RuntimeException e) {
            return at;
        }
    }

    private static String facingAt(org.bukkit.Location at) {
        try {
            if (at != null && at.getBlock().getBlockData() instanceof org.bukkit.block.data.type.Bed bed) return bed.getFacing().name();
        } catch (RuntimeException ignored) {}
        return null;
    }

    private static boolean standableLive(org.bukkit.World w, int[] c) {
        org.bukkit.block.Block feet = w.getBlockAt(c[0], c[1], c[2]);
        org.bukkit.block.Block head = feet.getRelative(0, 1, 0);
        org.bukkit.block.Block below = feet.getRelative(0, -1, 0);
        return feet.isPassable() && !feet.isLiquid() && head.isPassable() && !head.isLiquid()
                && below.getType().isSolid() && !(below.getBlockData() instanceof org.bukkit.block.data.type.Bed)
                && !feet.getType().name().contains("LAVA") && !feet.getType().name().contains("FIRE");
    }

    /**
     * Get a lying body up and put it on a free standing cell beside its bed (never inside a block).
     * Falls back to standing on top of the bed head. Returns the new location, or null if not lying.
     */
    static org.bukkit.Location wakeBesideBed(NPC npc, Player player) {
        if (npc == null || player == null || !lying(player, npc)) return null;
        org.bukkit.Location bed = traitBed(npc);
        if (bed == null) {
            try { if (player.isSleeping()) bed = player.getBedLocation(); } catch (RuntimeException | LinkageError ignored) {}
        }
        stand(npc, player);
        if (bed == null || bed.getWorld() == null) return player.getLocation();
        org.bukkit.Location head = headOf(bed);
        String facing = facingAt(head);
        org.bukkit.World w = head.getWorld();
        int[] spot = wakeSpot(head.getBlockX(), head.getBlockY(), head.getBlockZ(), facing, c -> standableLive(w, c));
        org.bukkit.Location to = spot == null
                ? new org.bukkit.Location(w, head.getBlockX() + 0.5, head.getBlockY() + 0.5625, head.getBlockZ() + 0.5)
                : new org.bukkit.Location(w, spot[0] + 0.5, spot[1], spot[2] + 0.5);
        to.setYaw(player.getLocation().getYaw());
        try { npc.teleport(to, org.bukkit.event.player.PlayerTeleportEvent.TeleportCause.PLUGIN); }
        catch (RuntimeException e) { try { player.teleport(to); } catch (RuntimeException ignored) {} }
        return to;
    }

    /** Autonomy tick: wake at dawn / whenever beds stop working, beside the bed. */
    static boolean autoWake(NPC npc, Player player) {
        if (npc == null || player == null || !lying(player, npc)) return false;
        org.bukkit.World w = player.getWorld();
        if (!shouldAutoWake(w.getTime(), w.isThundering())) return false;
        return wakeBesideBed(npc, player) != null;
    }

    /** SleepTrait persists its bed location privately; read it to repair bodies restored from a FOOT position. */
    private static org.bukkit.Location traitBed(NPC npc) {
        try {
            if (!npc.hasTrait(SleepTrait.class)) return null;
            java.lang.reflect.Field f = SleepTrait.class.getDeclaredField("at");
            f.setAccessible(true);
            Object v = f.get(npc.getOrAddTrait(SleepTrait.class));
            return v instanceof org.bukkit.Location l ? l : null;
        } catch (ReflectiveOperationException | RuntimeException e) {
            return null;
        }
    }

    /**
     * Called every autonomy tick: a body asleep (e.g. restored after restart) on a FOOT position is moved to the
     * HEAD half; a lying body is turned to the bed FACING and the pose is refreshed to viewers.
     */
    static void fixOrientation(NPC npc, Player player) {
        if (npc == null || player == null) return;
        if (autoWake(npc, player)) return;
        org.bukkit.Location at = traitBed(npc);
        if (at == null || at.getWorld() == null) return;
        org.bukkit.Location head = headOf(at);
        if (head != null && (head.getBlockX() != at.getBlockX() || head.getBlockY() != at.getBlockY() || head.getBlockZ() != at.getBlockZ()
                || at.getX() != at.getBlockX() || at.getZ() != at.getBlockZ())) {
            markAsleep(npc, head);
            return;
        }
        String facing = facingAt(at);
        if (facing == null || !lying(player, npc)) return;
        float yaw = bedYaw(facing);
        if (Math.abs(((player.getLocation().getYaw() - yaw) % 360 + 540) % 360 - 180) < 1f) return;
        try { player.setRotation(yaw, 0f); } catch (RuntimeException ignored) {}
        try { NMS.setHeadAndBodyYaw(player, yaw); } catch (Throwable ignored) {}
        try { NMS.markPoseDirty(player); } catch (Throwable ignored) {}
    }

    static void markAsleep(NPC npc, org.bukkit.Location bed) {
        if (npc == null || bed == null) return;
        bed = headOf(bed);
        try { npc.getOrAddTrait(SleepTrait.class).setSleeping(bed); } catch (RuntimeException ignored) {}
        try { npc.getOrAddTrait(EntityPoseTrait.class).setPose(EntityPoseTrait.EntityPose.SLEEPING); }
        catch (RuntimeException ignored) {}
    }

    private AgentSleep() {}
}
