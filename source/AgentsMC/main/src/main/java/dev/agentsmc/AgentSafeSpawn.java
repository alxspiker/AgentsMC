package dev.agentsmc;

/** Pure safe-standing search: feet+head non-colliding, floor solid; nearest cell within radius. */
final class AgentSafeSpawn {
    interface Probe { boolean open(int x, int y, int z); boolean floor(int x, int y, int z); boolean usable(int x, int y, int z); }
    private AgentSafeSpawn() {}

    /** Floor of a coordinate, tolerating float drift just below an integer (63.9999 -> 64). */
    static int blockCoord(double v) { return (int) Math.floor(v + 1e-3); }

    static int[] find(double rx, double ry, double rz, int radius, Probe probe) {
        return find(rx, ry, rz, radius, radius, probe);
    }

    /** Lifecycle respawns may need more vertical room when terrain at a saved spawn has changed. */
    static int[] find(double rx, double ry, double rz, int radius, int verticalRadius, Probe probe) {
        int bx = blockCoord(rx), by = blockCoord(ry), bz = blockCoord(rz);
        int[] best = null; double distance = Double.MAX_VALUE;
        for (int dy = -verticalRadius; dy <= verticalRadius; dy++) for (int dx = -radius; dx <= radius; dx++) for (int dz = -radius; dz <= radius; dz++) {
            int x = bx + dx, y = by + dy, z = bz + dz;
            if (!probe.usable(x, y, z) || !probe.open(x, y, z) || !probe.open(x, y + 1, z) || !probe.floor(x, y - 1, z)) continue;
            double ddx = x + .5 - rx, ddy = y - ry, ddz = z + .5 - rz, d = ddx * ddx + ddy * ddy * 1.0001 + ddz * ddz;
            if (d < distance) { distance = d; best = new int[]{x, y, z}; }
        }
        return best;
    }

    /** STUCK escape: an adjacent standable cell one step up/level/down, preferring the target direction.
     *  Never teleports. Returns {x,y,z,kind=0} or null when no adjacent step exists. `radius` is unused. */
    static int[] escape(double rx, double ry, double rz, double tx, double tz, int radius, Probe probe) {
        int bx = blockCoord(rx), by = blockCoord(ry), bz = blockCoord(rz);
        int[] best = null; double score = Double.MAX_VALUE;
        int[][] adj = {{1,0},{-1,0},{0,1},{0,-1},{1,1},{1,-1},{-1,1},{-1,-1}};
        for (int[] a : adj) for (int dy : new int[]{0, 1, -1}) {
            int x = bx + a[0], y = by + dy, z = bz + a[1];
            if (!standable(x, y, z, probe)) continue;
            if (dy == 1 && !probe.open(bx, by + 2, bz)) continue; // need headroom to jump
            if (a[0] != 0 && a[1] != 0 && !(probe.open(bx + a[0], y, bz) && probe.open(bx + a[0], y + 1, bz) || probe.open(bx, y, bz + a[1]) && probe.open(bx, y + 1, bz + a[1]))) continue;
            double s = (x + .5 - tx) * (x + .5 - tx) + (z + .5 - tz) * (z + .5 - tz) + (dy == 1 ? 4 : dy == 0 ? -0.5 : 0);
            if (s < score) { score = s; best = new int[]{x, y, z, 0}; }
        }
        return best;
    }
    static boolean standable(int x, int y, int z, Probe probe) {
        return probe.usable(x, y, z) && probe.open(x, y, z) && probe.open(x, y + 1, z) && probe.floor(x, y - 1, z);
    }

    /**
     * First standable cell at the same XZ at or below {@code y} with an open
     * column from {@code y} down to that cell. Never searches sideways and never
     * skips through solid ground into a cave.
     */
    static int[] standableBelow(int x, int y, int z, Probe probe) {
        if (probe == null) return null;
        for (int fy = y; fy >= y - 512; fy--) {
            boolean usable, open, stand;
            try { usable = probe.usable(x, fy, z); }
            catch (RuntimeException ignored) { break; }
            if (!usable && fy < y - 2) break;
            try { stand = standable(x, fy, z, probe); }
            catch (RuntimeException ignored) { return null; }
            if (stand) return new int[]{x, fy, z};
            try { open = probe.open(x, fy, z); }
            catch (RuntimeException ignored) { return null; }
            if (!open) return null;
        }
        return null;
    }

    /**
     * True when the body is standing or only clipped into the floor: a 1x2 air
     * column over a solid, or the feet cell itself is the floor with air above.
     */
    static boolean supported(int x, int y, int z, Probe probe) {
        if (probe == null) return false;
        try {
            if (standable(x, y, z, probe)) return true;
            if (probe.floor(x, y - 1, z) && probe.open(x, y, z) && probe.open(x, y + 1, z)) return true;
            if (probe.floor(x, y, z) && probe.open(x, y + 1, z) && probe.open(x, y + 2, z)) return true;
        } catch (RuntimeException ignored) { return false; }
        return false;
    }

    /**
     * True when a solid sits within {@code reach} blocks below the feet.
     * Builder Bot: y 78.94 over a hillside at 79 is not airborne.
     */
    static boolean groundWithin(double feetY, int x, int z, Probe probe) {
        return groundWithin(feetY, x, z, probe, 1.0);
    }
    static boolean groundWithin(double feetY, int x, int z, Probe probe, double reach) {
        if (probe == null) return false;
        int lo = blockCoord(feetY - reach), hi = blockCoord(feetY);
        for (int y = lo; y <= hi; y++) {
            try {
                if (probe.floor(x, y, z)) return true;
                if (!probe.open(x, y, z)) return true;
            } catch (RuntimeException ignored) { return false; }
        }
        return false;
    }

    static final int WALK = 8;
    private static final int[][] STEPS = {{1,0},{-1,0},{0,1},{0,-1},{1,1},{1,-1},{-1,1},{-1,-1}};

    /** Player-like one-block walk/jump onto an existing standable cell. Never builds. */
    static boolean canStep(int fx, int fy, int fz, int tx, int ty, int tz, Probe probe) {
        int dx = tx - fx, dy = ty - fy, dz = tz - fz;
        if (Math.abs(dx) > 1 || Math.abs(dz) > 1 || (dx == 0 && dz == 0) || dy < -1 || dy > 1) return false;
        if (!standable(tx, ty, tz, probe)) return false;
        if (dy == 1 && !probe.open(fx, fy + 2, fz)) return false;
        if (dx != 0 && dz != 0 && !(probe.open(fx + dx, ty, fz) && probe.open(fx + dx, ty + 1, fz)
                || probe.open(fx, ty, fz + dz) && probe.open(fx, ty + 1, fz + dz))) return false;
        return true;
    }

    /** True when a walk/jump path on existing blocks reaches a standable cell above {@code y}. */
    static boolean canClimb(int x, int y, int z, int radius, Probe probe) {
        return climbGoal(x, y, z, radius, probe) != null;
    }

    /**
     * A 2-deep (or deeper) hole whose rim is nearby but cannot be reached by walking or jumping.
     * Flat ground and 1-deep dips (jump-out) are not pits.
     */
    static boolean trapped(int x, int y, int z, int radius, Probe probe) {
        if (canClimb(x, y, z, radius, probe)) return false;
        int raised = 0, level = 0;
        for (int dx = -2; dx <= 2; dx++) for (int dz = -2; dz <= 2; dz++) {
            if (standable(x + dx, y, z + dz, probe)) level++;
            if (standable(x + dx, y + 2, z + dz, probe)) raised++;
        }
        return raised >= 1 && level <= 4;
    }

    /** First adjacent walk/jump toward a reachable higher cell, or null if none exists. */
    static int[] walkStep(double rx, double ry, double rz, double tx, double tz, int radius, Probe probe) {
        int sx = blockCoord(rx), sy = blockCoord(ry), sz = blockCoord(rz);
        if (!standable(sx, sy, sz, probe) && standable(sx, sy + 1, sz, probe)) sy++;
        int[] goal = climbGoal(sx, sy, sz, radius, probe);
        if (goal == null) return null;
        return firstStep(sx, sy, sz, goal[0], goal[1], goal[2], radius, probe);
    }

    /** Nearest standable cell that is not a no-climb pit, or null. */
    static int[] findWalkOut(double rx, double ry, double rz, int radius, Probe probe) {
        int bx = blockCoord(rx), by = blockCoord(ry), bz = blockCoord(rz);
        int[] best = null; double distance = Double.MAX_VALUE;
        for (int dy = -radius; dy <= radius; dy++) for (int dx = -radius; dx <= radius; dx++) for (int dz = -radius; dz <= radius; dz++) {
            int x = bx + dx, y = by + dy, z = bz + dz;
            if (!standable(x, y, z, probe) || trapped(x, y, z, WALK, probe)) continue;
            double ddx = x + .5 - rx, ddy = y - ry, ddz = z + .5 - rz, d = ddx * ddx + ddy * ddy * 1.0001 + ddz * ddz;
            if (d < distance) { distance = d; best = new int[]{x, y, z}; }
        }
        return best;
    }

    private static int[] climbGoal(int sx, int sy, int sz, int radius, Probe probe) {
        java.util.ArrayDeque<int[]> q = new java.util.ArrayDeque<>();
        java.util.HashSet<String> seen = new java.util.HashSet<>();
        q.add(new int[]{sx, sy, sz, 0});
        seen.add(sx + "," + sy + "," + sz);
        int[] best = null; double score = Double.MAX_VALUE;
        while (!q.isEmpty()) {
            int[] n = q.poll();
            int x = n[0], y = n[1], z = n[2], dist = n[3];
            if (y > sy) {
                double s = dist + (x - sx) * (x - sx) + (z - sz) * (z - sz) - (y - sy) * 8;
                if (s < score) { score = s; best = new int[]{x, y, z}; }
            }
            if (dist >= radius * 2) continue;
            for (int[] a : STEPS) for (int dy : new int[]{0, 1, -1}) {
                int nx = x + a[0], ny = y + dy, nz = z + a[1];
                if (Math.max(Math.abs(nx - sx), Math.abs(nz - sz)) > radius) continue;
                if (!canStep(x, y, z, nx, ny, nz, probe)) continue;
                if (!seen.add(nx + "," + ny + "," + nz)) continue;
                q.add(new int[]{nx, ny, nz, dist + 1});
            }
        }
        return best;
    }

    private static int[] firstStep(int sx, int sy, int sz, int gx, int gy, int gz, int radius, Probe probe) {
        if (canStep(sx, sy, sz, gx, gy, gz, probe)) return new int[]{gx, gy, gz};
        java.util.ArrayDeque<int[]> q = new java.util.ArrayDeque<>();
        java.util.HashMap<String, int[]> parent = new java.util.HashMap<>();
        q.add(new int[]{sx, sy, sz});
        parent.put(sx + "," + sy + "," + sz, null);
        while (!q.isEmpty()) {
            int[] n = q.poll();
            int x = n[0], y = n[1], z = n[2];
            if (x == gx && y == gy && z == gz) {
                int[] cur = n;
                while (true) {
                    int[] p = parent.get(cur[0] + "," + cur[1] + "," + cur[2]);
                    if (p == null || (p[0] == sx && p[1] == sy && p[2] == sz)) return new int[]{cur[0], cur[1], cur[2]};
                    cur = p;
                }
            }
            if (Math.max(Math.abs(x - sx), Math.abs(z - sz)) > radius) continue;
            for (int[] a : STEPS) for (int dy : new int[]{0, 1, -1}) {
                int nx = x + a[0], ny = y + dy, nz = z + a[1];
                if (!canStep(x, y, z, nx, ny, nz, probe)) continue;
                String k = nx + "," + ny + "," + nz;
                if (parent.containsKey(k)) continue;
                parent.put(k, n);
                q.add(new int[]{nx, ny, nz});
            }
        }
        return null;
    }

    /**
     * Keep the requested feet Y when it is already standable. Snap at most one cell
     * up when feet are inside a floor, or at most two down from head/air. Never jump
     * two cells up or pick a different floor when the request is already valid.
     */
    static double standableY(double x, double y, double z, Probe probe) {
        int bx = blockCoord(x), by = blockCoord(y), bz = blockCoord(z);
        if (standable(bx, by, bz, probe)) return by;
        if (!probe.open(bx, by, bz) && standable(bx, by + 1, bz, probe)) return by + 1;
        for (int fy = by - 1; fy >= by - 2; fy--) if (standable(bx, fy, bz, probe)) return fy;
        if (standable(bx, by + 1, bz, probe)) return by + 1;
        return y;
    }
}

