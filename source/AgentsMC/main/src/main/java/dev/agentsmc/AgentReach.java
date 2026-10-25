package dev.agentsmc;

import org.bukkit.Location;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.entity.Player;
import org.bukkit.util.Vector;

/** Vanilla-like 5.0 block reach and 3.0 melee reach: nearest point on the AABB, inclusive. */
final class AgentReach {
    static final double LIMIT = 5.0, ATTACK = 3.0;

    static double nearest(double eyeX, double eyeY, double eyeZ, int bx, int by, int bz) {
        double x = clamp(eyeX, bx, bx + 1);
        double y = clamp(eyeY, by, by + 1);
        double z = clamp(eyeZ, bz, bz + 1);
        return Math.sqrt((eyeX - x) * (eyeX - x) + (eyeY - y) * (eyeY - y) + (eyeZ - z) * (eyeZ - z));
    }

    static double nearest(Location eye, Block b) {
        return nearest(eye.getX(), eye.getY(), eye.getZ(), b.getX(), b.getY(), b.getZ());
    }

    static boolean inReach(double eyeX, double eyeY, double eyeZ, int bx, int by, int bz) {
        return nearest(eyeX, eyeY, eyeZ, bx, by, bz) <= LIMIT + 1e-9;
    }

    static boolean inReach(Location eye, Block b) {
        return eye != null && b != null && inReach(eye.getX(), eye.getY(), eye.getZ(), b.getX(), b.getY(), b.getZ());
    }

    static double nearestBox(double px, double py, double pz, double minX, double minY, double minZ, double maxX, double maxY, double maxZ) {
        double x = clamp(px, minX, maxX), y = clamp(py, minY, maxY), z = clamp(pz, minZ, maxZ);
        return Math.sqrt((px - x) * (px - x) + (py - y) * (py - y) + (pz - z) * (pz - z));
    }

    static boolean inAttackReach(double px, double py, double pz, double minX, double minY, double minZ, double maxX, double maxY, double maxZ) {
        return nearestBox(px, py, pz, minX, minY, minZ, maxX, maxY, maxZ) <= ATTACK + 1e-9;
    }

    static double nearest(Location from, org.bukkit.entity.Entity e) {
        if (from == null || e == null) return Double.POSITIVE_INFINITY;
        try {
            org.bukkit.util.BoundingBox box = e.getBoundingBox();
            return nearestBox(from.getX(), from.getY(), from.getZ(), box.getMinX(), box.getMinY(), box.getMinZ(), box.getMaxX(), box.getMaxY(), box.getMaxZ());
        } catch (RuntimeException ignored) {
            return from.getWorld() != null && e.getWorld() != null && from.getWorld().equals(e.getWorld()) ? from.distance(e.getLocation()) : Double.POSITIVE_INFINITY;
        }
    }

    static boolean inAttackReach(org.bukkit.entity.Player p, org.bukkit.entity.Entity e) {
        return p != null && e != null && nearest(p.getEyeLocation(), e) <= ATTACK + 1e-9;
    }

    static boolean adjacent(Block a, Block b) {
        if (a == null || b == null) return false;
        return Math.abs(a.getX() - b.getX()) + Math.abs(a.getY() - b.getY()) + Math.abs(a.getZ() - b.getZ()) == 1;
    }

    /** Feet or head within Chebyshev 1 of the block: touching, so LOS is not required. */
    static boolean touching(int px, int py, int pz, int bx, int by, int bz) {
        for (int dy = 0; dy <= 1; dy++) {
            int cx = Math.abs(px - bx), cy = Math.abs(py + dy - by), cz = Math.abs(pz - bz);
            if (Math.max(cx, Math.max(cy, cz)) <= 1) return true;
        }
        return false;
    }
    static boolean touching(Location feet, Block b) {
        if (feet == null || b == null) return false;
        return touching(AgentSafeSpawn.blockCoord(feet.getX()), AgentSafeSpawn.blockCoord(feet.getY()),
                AgentSafeSpawn.blockCoord(feet.getZ()), b.getX(), b.getY(), b.getZ());
    }

    static boolean scanInRadius(int dx, int dy, int dz, int radius) {
        return Math.sqrt((double) dx * dx + (double) dy * dy + (double) dz * dz) <= radius + 1e-9;
    }

    static int craftsFor(int requested, int yield) {
        if (requested < 1 || yield < 1) return -1;
        return (requested + yield - 1) / yield;
    }

    /** True when a ray to any exposed face (center + corners) hits the target first. */
    static boolean visibleFace(Player p, Block b) {
        Location eye = p.getEyeLocation();
        if (!inReach(eye, b)) return false;
        for (Location probe : faceProbes(b)) if (hitsTarget(p, b, probe)) return true;
        return false;
    }

    static java.util.List<Location> faceProbes(Block b) {
        java.util.List<Location> probes = new java.util.ArrayList<>();
        Location c = b.getLocation().add(0.5, 0.5, 0.5);
        probes.add(c);
        for (BlockFace f : new BlockFace[]{BlockFace.UP, BlockFace.DOWN, BlockFace.NORTH, BlockFace.SOUTH, BlockFace.EAST, BlockFace.WEST}) {
            if (b.getRelative(f).getType().isOccluding()) continue;
            double ox = f.getModX() * 0.51, oy = f.getModY() * 0.51, oz = f.getModZ() * 0.51;
            double[] u = {-0.45, 0, 0.45};
            for (double a : u) for (double d : u) {
                if (f == BlockFace.UP || f == BlockFace.DOWN) probes.add(c.clone().add(a, oy, d));
                else if (f == BlockFace.NORTH || f == BlockFace.SOUTH) probes.add(c.clone().add(a, d, oz));
                else probes.add(c.clone().add(ox, a, d));
            }
        }
        return probes;
    }

    /** True when the hit is the agent's own feet or head cell — not an obstruction. */
    static boolean occupies(int feetX, int feetY, int feetZ, int hitX, int hitY, int hitZ) {
        return hitX == feetX && hitZ == feetZ && (hitY == feetY || hitY == feetY + 1);
    }

    static boolean occupies(Player p, Block hit) {
        if (p == null || hit == null) return false;
        Block feet = p.getLocation().getBlock();
        return occupies(feet.getX(), feet.getY(), feet.getZ(), hit.getX(), hit.getY(), hit.getZ());
    }

    static boolean hitsTarget(Player p, Block b, Location probe) {
        Location origin = p.getEyeLocation();
        Vector dest = probe.toVector();
        for (int hop = 0; hop < 4; hop++) {
            Vector d = dest.clone().subtract(origin.toVector());
            if (d.lengthSquared() < 1e-8) return true;
            org.bukkit.util.RayTraceResult hit = p.getWorld().rayTraceBlocks(origin, d, d.length() + 0.05,
                    org.bukkit.FluidCollisionMode.NEVER, true);
            if (hit == null || hit.getHitBlock() == null) return true;
            if (b.equals(hit.getHitBlock())) return true;
            if (!occupies(p, hit.getHitBlock())) return false;
            if (hit.getHitPosition() == null) return false;
            origin = hit.getHitPosition().toLocation(p.getWorld()).add(d.normalize().multiply(0.06));
        }
        return false;
    }

    private static double clamp(double v, double lo, double hi) {
        return Math.min(hi, Math.max(lo, v));
    }

    private AgentReach() {}
}
