package dev.agentsmc;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.entity.Player;

import com.google.gson.JsonObject;

/**
 * Compact environment maps. World reads stay on the server thread and never
 * load chunks; flood-fill and string encoding run on an in-memory name box.
 */
final class AgentPerception {
    static final int HEIGHT_DEFAULT = 16, HEIGHT_MAX = 32, HEIGHT_SCAN = 48;
    static final int WALK_DEFAULT = 12, WALK_MAX = 24, Y_DEFAULT = 4, Y_SPAN_MAX = 16;
    /** Bounded excavation scan. Never loads chunks. */
    static final int BOX_CAP = 4096;
    static final String HEIGHT_LEGEND = "g grass/dirt, s stone, d sand, w water, l lava, t leaves/tree, n snow/powder snow, o other, * agent, . none/unloaded";
    static final String HEIGHT_SCALE = "0 same as yBase; 1-9 +1..+9; a-z +10..+35; A-Z -1..-26; ! above +35; ? below -26; . no surface";
    static final String WALK_LEGEND = "@ agent, + reachable, * reachable-with-dig, . standable, ~ liquid, ! hazard, v drop/cliff, # blocked, ? unloaded";

    static Map<String, Object> heightmapLive(Player player, JsonObject input) {
        Location at = player.getLocation();
        int radius = optionalInt(input, "radius", HEIGHT_DEFAULT, 1, HEIGHT_MAX);
        int cx = optionalInt(input, "x", at.getBlockX(), Integer.MIN_VALUE / 4, Integer.MAX_VALUE / 4);
        int cz = optionalInt(input, "z", at.getBlockZ(), Integer.MIN_VALUE / 4, Integer.MAX_VALUE / 4);
        return heightmapWorld(at.getWorld(), cx, cz, at.getBlockY(), radius, at.getBlockX(), at.getBlockZ());
    }

    static Map<String, Object> walkmapLive(Player player, JsonObject input) {
        Location at = player.getLocation();
        int radius = optionalInt(input, "radius", WALK_DEFAULT, 1, WALK_MAX);
        int ay = at.getBlockY();
        int ymin = optionalInt(input, "ymin", ay - Y_DEFAULT, ay - 32, ay + 32);
        int ymax = optionalInt(input, "ymax", ay + Y_DEFAULT, ay - 32, ay + 32);
        if (ymin > ymax) throw new IllegalArgumentException("ymin must be <= ymax");
        if (ymax - ymin > Y_SPAN_MAX) throw new IllegalArgumentException("ymin..ymax span must be at most " + Y_SPAN_MAX);
        boolean dig = optionalFlag(input, "dig", false);
        World world = at.getWorld();
        NameGrid box = snapshot(world, at.getBlockX() - radius, ymin - 1, at.getBlockZ() - radius,
                at.getBlockX() + radius, ymax + 2, at.getBlockZ() + radius);
        Map<String, Object> out = walkmap(box, at.getBlockX(), ay, at.getBlockZ(), radius, ymin, ymax, dig);
        out.put("world", world.getName());
        return out;
    }

    static Map<String, Object> scanBoxLive(Player player, JsonObject input) {
        if (player == null || player.getWorld() == null) throw new IllegalArgumentException("scan_box requires a spawned body");
        int minX = requiredInt(input, "minX");
        int minY = requiredInt(input, "minY");
        int minZ = requiredInt(input, "minZ");
        int maxX = requiredInt(input, "maxX");
        int maxY = requiredInt(input, "maxY");
        int maxZ = requiredInt(input, "maxZ");
        Integer[] path = pathEnds(input);
        World world = player.getWorld();
        int loX = Math.min(minX, maxX), hiX = Math.max(minX, maxX);
        int loY = Math.min(minY, maxY), hiY = Math.max(minY, maxY);
        int loZ = Math.min(minZ, maxZ), hiZ = Math.max(minZ, maxZ);
        long volume = boxVolume(loX, hiX, loY, hiY, loZ, hiZ);
        if (volume > BOX_CAP)
            throw new IllegalArgumentException("scan_box volume " + volume + " exceeds cap " + BOX_CAP
                    + " (shrink min/max so (dx+1)*(dy+1)*(dz+1) <= " + BOX_CAP + ")");
        int snapMinX = loX, snapMaxX = hiX, snapMinY = loY, snapMaxY = hiY, snapMinZ = loZ, snapMaxZ = hiZ;
        if (path != null) {
            snapMinX = Math.min(snapMinX, Math.min(path[0], path[3]));
            snapMaxX = Math.max(snapMaxX, Math.max(path[0], path[3]));
            snapMinY = Math.min(snapMinY, Math.min(path[1], path[4]));
            snapMaxY = Math.max(snapMaxY, Math.max(path[1], path[4]));
            snapMinZ = Math.min(snapMinZ, Math.min(path[2], path[5]));
            snapMaxZ = Math.max(snapMaxZ, Math.max(path[2], path[5]));
        }
        // A* needs the floor under feet and two headroom cells; pad does not count against BOX_CAP.
        NameGrid box = snapshot(world, snapMinX - 1, snapMinY - 1, snapMinZ - 1,
                snapMaxX + 1, snapMaxY + 2, snapMaxZ + 1);
        Map<String, Object> out = scanBox(box, loX, loY, loZ, hiX, hiY, hiZ, path);
        out.put("world", world.getName());
        out.put("action", "scan_box");
        return out;
    }

    static Integer[] pathEnds(JsonObject input) {
        boolean any = input != null && (input.has("fromX") || input.has("fromY") || input.has("fromZ")
                || input.has("toX") || input.has("toY") || input.has("toZ"));
        if (!any) return null;
        return new Integer[]{
                requiredInt(input, "fromX"), requiredInt(input, "fromY"), requiredInt(input, "fromZ"),
                requiredInt(input, "toX"), requiredInt(input, "toY"), requiredInt(input, "toZ")
        };
    }

    static long boxVolume(int minX, int maxX, int minY, int maxY, int minZ, int maxZ) {
        long dx = (long) maxX - minX + 1;
        long dy = (long) maxY - minY + 1;
        long dz = (long) maxZ - minZ + 1;
        if (dx <= 0 || dy <= 0 || dz <= 0) return 0;
        if (dx > BOX_CAP || dy > BOX_CAP || dz > BOX_CAP) return Long.MAX_VALUE;
        if (dx > Long.MAX_VALUE / dy) return Long.MAX_VALUE;
        long area = dx * dy;
        if (area > Long.MAX_VALUE / dz) return Long.MAX_VALUE;
        return area * dz;
    }

    /**
     * Compact per-layer material grids plus a non-air list. Unloaded cells are
     * {@code ?}, never silently AIR. Optional A* connectivity uses the same
     * walk/jump/drop rules as goto.
     */
    static Map<String, Object> scanBox(AgentPathfinder.Grid grid, int minX, int minY, int minZ,
                                       int maxX, int maxY, int maxZ, Integer[] pathEnds) {
        int sizeX = maxX - minX + 1, sizeY = maxY - minY + 1, sizeZ = maxZ - minZ + 1;
        long volume = (long) sizeX * sizeY * sizeZ;
        List<Map<String, Object>> layers = new ArrayList<>();
        List<Map<String, Object>> solids = new ArrayList<>();
        int scanned = 0, air = 0, solid = 0, unloaded = 0;
        boolean complete = true;
        for (int y = minY; y <= maxY; y++) {
            List<String> rows = new ArrayList<>(sizeZ);
            for (int z = minZ; z <= maxZ; z++) {
                StringBuilder row = new StringBuilder();
                for (int x = minX; x <= maxX; x++) {
                    if (x > minX) row.append(' ');
                    if (!grid.loaded(x, y, z)) {
                        row.append('?');
                        unloaded++;
                        complete = false;
                        continue;
                    }
                    scanned++;
                    String name = AgentPathfinder.bare(grid.name(x, y, z));
                    if (name.isEmpty()) name = "air";
                    if (isAir(name)) {
                        row.append('.');
                        air++;
                    } else {
                        row.append(name.toUpperCase(java.util.Locale.ROOT));
                        solid++;
                        Map<String, Object> cell = new LinkedHashMap<>();
                        cell.put("x", x);
                        cell.put("y", y);
                        cell.put("z", z);
                        cell.put("material", name.toUpperCase(java.util.Locale.ROOT));
                        solids.add(cell);
                    }
                }
                rows.add(row.toString());
            }
            Map<String, Object> layer = new LinkedHashMap<>();
            layer.put("y", y);
            layer.put("grid", rows);
            layers.add(layer);
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("action", "scan_box");
        out.put("minX", minX);
        out.put("minY", minY);
        out.put("minZ", minZ);
        out.put("maxX", maxX);
        out.put("maxY", maxY);
        out.put("maxZ", maxZ);
        out.put("sizeX", sizeX);
        out.put("sizeY", sizeY);
        out.put("sizeZ", sizeZ);
        out.put("cells", volume);
        out.put("cap", BOX_CAP);
        out.put("complete", complete);
        out.put("unloaded", unloaded);
        Map<String, Object> coverage = new LinkedHashMap<>();
        coverage.put("scanned", scanned);
        coverage.put("air", air);
        coverage.put("solid", solid);
        coverage.put("unloaded", unloaded);
        coverage.put("complete", complete);
        out.put("coverage", coverage);
        out.put("layers", layers);
        out.put("solids", solids);
        out.put("legend", ". air, ? unloaded (not air), NAME solid material; rows are +z, cols are +x");
        if (pathEnds != null) out.put("path", connectivity(grid, pathEnds[0], pathEnds[1], pathEnds[2],
                pathEnds[3], pathEnds[4], pathEnds[5]));
        return out;
    }

    static Map<String, Object> connectivity(AgentPathfinder.Grid grid, int fx, int fy, int fz, int tx, int ty, int tz) {
        Map<String, Object> path = new LinkedHashMap<>();
        path.put("from", Map.of("x", fx, "y", fy, "z", fz));
        path.put("to", Map.of("x", tx, "y", ty, "z", tz));
        AgentPathfinder.Result plan = AgentPathfinder.plan(fx, fy, fz, tx, ty, tz, grid, new AgentPathfinder.Options());
        boolean reached = plan.fail == null && plan.path != null && !plan.path.isEmpty();
        if (reached) {
            int[] last = plan.path.get(plan.path.size() - 1);
            reached = last[0] == tx && last[2] == tz && Math.abs(last[1] - ty) <= 1;
        }
        if (plan.segment) reached = false;
        path.put("reachable", reached);
        path.put("steps", plan.path == null ? 0 : plan.path.size());
        if (plan.note != null && !plan.note.isEmpty()) path.put("note", plan.note);
        if (plan.fail != null) path.put("fail", plan.fail);
        if (!reached) {
            int[] blocked = firstBlocked(grid, fx, fy, fz, tx, ty, tz);
            if (blocked != null) {
                Map<String, Object> cell = new LinkedHashMap<>();
                cell.put("x", blocked[0]);
                cell.put("y", blocked[1]);
                cell.put("z", blocked[2]);
                cell.put("material", grid.loaded(blocked[0], blocked[1], blocked[2])
                        ? AgentPathfinder.bare(grid.name(blocked[0], blocked[1], blocked[2])).toUpperCase(java.util.Locale.ROOT)
                        : "UNLOADED");
                path.put("blocked", cell);
            }
        }
        return path;
    }

    /** First solid or unloaded cell on the axis-aligned walk from {@code from} toward {@code to}. */
    static int[] firstBlocked(AgentPathfinder.Grid grid, int x, int y, int z, int gx, int gy, int gz) {
        int guard = Math.abs(gx - x) + Math.abs(gy - y) + Math.abs(gz - z) + 2;
        int cx = x, cy = y, cz = z;
        while (guard-- > 0 && (cx != gx || cy != gy || cz != gz)) {
            if (cx != gx) cx += Integer.compare(gx, cx);
            else if (cz != gz) cz += Integer.compare(gz, cz);
            else cy += Integer.compare(gy, cy);
            if (!grid.loaded(cx, cy, cz)) return new int[]{cx, cy, cz};
            String n = AgentPathfinder.bare(grid.name(cx, cy, cz));
            if (!isAir(n) && !grid.open(cx, cy, cz)) return new int[]{cx, cy, cz};
        }
        if (!grid.loaded(gx, gy, gz)) return new int[]{gx, gy, gz};
        if (!isAir(AgentPathfinder.bare(grid.name(gx, gy, gz))) && !grid.open(gx, gy, gz))
            return new int[]{gx, gy, gz};
        return null;
    }

    static boolean isAir(String name) {
        String n = AgentPathfinder.bare(name);
        return n.isEmpty() || n.equals("air") || n.equals("cave_air") || n.equals("void_air");
    }

    static Map<String, Object> heightmapWorld(World world, int cx, int cz, int yBase, int radius, int agentX, int agentZ) {
        int size = radius * 2 + 1;
        int originX = cx - radius, originZ = cz - radius;
        int minY = world.getMinHeight(), maxY = world.getMaxHeight() - 1;
        int fromY = Math.min(maxY, yBase + HEIGHT_SCAN);
        int toY = Math.max(minY, yBase - HEIGHT_SCAN);
        StringBuilder heights = new StringBuilder(size * (size + 1));
        StringBuilder types = new StringBuilder(size * (size + 1));
        boolean complete = true;
        int unloaded = 0;
        for (int row = 0; row < size; row++) {
            if (row > 0) { heights.append('\n'); types.append('\n'); }
            int z = originZ + row;
            for (int col = 0; col < size; col++) {
                int x = originX + col;
                if (!chunkLoaded(world, x, z)) {
                    heights.append('.'); types.append('.'); complete = false; unloaded++;
                    continue;
                }
                int y = highest(world, x, z, fromY, toY);
                if (y == Integer.MIN_VALUE) { heights.append('.'); types.append('.'); continue; }
                heights.append(heightChar(y - yBase));
                char kind = surfaceKind(world.getBlockAt(x, y, z).getType().name());
                types.append(x == agentX && z == agentZ ? '*' : kind);
            }
        }
        Map<String, Object> out = envelope("heightmap", world.getName(), originX, originZ, yBase, radius, size, complete);
        out.put("heights", heights.toString());
        out.put("surface", types.toString());
        out.put("unloaded", unloaded);
        out.put("legend", HEIGHT_LEGEND);
        out.put("heightScale", HEIGHT_SCALE);
        out.put("agentX", agentX);
        out.put("agentZ", agentZ);
        return out;
    }

    static Map<String, Object> heightmap(AgentPathfinder.Grid grid, int cx, int cz, int yBase, int radius,
                                         int agentX, int agentZ, int fromY, int toY) {
        int size = radius * 2 + 1;
        int originX = cx - radius, originZ = cz - radius;
        StringBuilder heights = new StringBuilder(size * (size + 1));
        StringBuilder types = new StringBuilder(size * (size + 1));
        boolean complete = true;
        int unloaded = 0;
        for (int row = 0; row < size; row++) {
            if (row > 0) { heights.append('\n'); types.append('\n'); }
            int z = originZ + row;
            for (int col = 0; col < size; col++) {
                int x = originX + col;
                if (!grid.loaded(x, yBase, z)) {
                    heights.append('.'); types.append('.'); complete = false; unloaded++;
                    continue;
                }
                int y = topY(grid, x, z, fromY, toY);
                if (y == Integer.MIN_VALUE) { heights.append('.'); types.append('.'); continue; }
                heights.append(heightChar(y - yBase));
                char kind = surfaceKind(grid.name(x, y, z));
                types.append(x == agentX && z == agentZ ? '*' : kind);
            }
        }
        Map<String, Object> out = envelope("heightmap", null, originX, originZ, yBase, radius, size, complete);
        out.put("heights", heights.toString());
        out.put("surface", types.toString());
        out.put("unloaded", unloaded);
        out.put("legend", HEIGHT_LEGEND);
        out.put("heightScale", HEIGHT_SCALE);
        out.put("agentX", agentX);
        out.put("agentZ", agentZ);
        return out;
    }

    static Map<String, Object> walkmap(AgentPathfinder.Grid grid, int ax, int ay, int az, int radius,
                                       int ymin, int ymax, boolean dig) {
        int size = radius * 2 + 1;
        int originX = ax - radius, originZ = az - radius;
        int minX = originX, maxX = ax + radius, minZ = originZ, maxZ = az + radius;
        Set<Long> walk = new HashSet<>();
        Set<Long> dug = new HashSet<>();
        int startY = floodStartY(grid, ax, ay, az, ymin, ymax);
        if (startY != Integer.MIN_VALUE) {
            flood(grid, ax, startY, az, ymin, ymax, minX, maxX, minZ, maxZ, false, walk);
            if (dig) {
                flood(grid, ax, startY, az, ymin, ymax, minX, maxX, minZ, maxZ, true, dug);
                dug.removeAll(walk);
            }
        }
        StringBuilder gridOut = new StringBuilder(size * (size + 1));
        StringBuilder heights = new StringBuilder(size * (size + 1));
        boolean complete = true;
        int unloaded = 0;
        for (int row = 0; row < size; row++) {
            if (row > 0) { gridOut.append('\n'); heights.append('\n'); }
            int z = originZ + row;
            for (int col = 0; col < size; col++) {
                int x = originX + col;
                if (!columnLoaded(grid, x, ymin, ymax, z)) {
                    gridOut.append('?'); heights.append('.'); complete = false; unloaded++;
                    continue;
                }
                int stand = standY(grid, x, z, ymin, ymax, ay);
                heights.append(stand == Integer.MIN_VALUE ? '.' : heightChar(stand - ay));
                gridOut.append(mark(grid, x, z, ax, ay, az, ymin, ymax, stand, walk, dug));
            }
        }
        Map<String, Object> out = envelope("walkmap", null, originX, originZ, ay, radius, size, complete);
        out.put("ymin", ymin);
        out.put("ymax", ymax);
        out.put("dig", dig);
        out.put("grid", gridOut.toString());
        out.put("heights", heights.toString());
        out.put("legend", WALK_LEGEND);
        out.put("reachable", walk.size());
        out.put("reachableDig", dug.size());
        out.put("unloaded", unloaded);
        out.put("agentX", ax);
        out.put("agentY", ay);
        out.put("agentZ", az);
        if (startY != Integer.MIN_VALUE && startY != ay) out.put("feetY", startY);
        return out;
    }

    static char surfaceKind(String raw) {
        String n = bare(raw);
        if (n.isEmpty() || n.equals("air") || n.equals("cave_air") || n.equals("void_air")) return '.';
        if (n.equals("lava")) return 'l';
        if (AgentPathfinder.waterName(n)) return 'w';
        if (n.equals("snow") || n.equals("snow_block") || n.equals("powder_snow")
                || n.equals("ice") || n.equals("packed_ice") || n.equals("blue_ice") || n.equals("frosted_ice"))
            return 'n';
        if (n.contains("leaves") || n.endsWith("_log") || n.endsWith("_wood") || n.endsWith("_stem")
                || n.equals("bamboo") || n.contains("mushroom_block") || n.equals("mangrove_roots")
                || n.equals("azalea") || n.equals("flowering_azalea"))
            return 't';
        if (n.equals("sand") || n.equals("red_sand") || n.contains("sandstone") || n.equals("gravel")) return 'd';
        if (n.equals("grass_block") || n.equals("dirt") || n.equals("coarse_dirt") || n.equals("rooted_dirt")
                || n.equals("podzol") || n.equals("mycelium") || n.equals("dirt_path") || n.equals("farmland")
                || n.equals("mud") || n.equals("short_grass") || n.equals("tall_grass") || n.equals("moss_block"))
            return 'g';
        if (n.equals("stone") || n.equals("cobblestone") || n.equals("granite") || n.equals("diorite")
                || n.equals("andesite") || n.equals("tuff") || n.equals("deepslate") || n.contains("deepslate")
                || n.endsWith("_ore") || n.equals("bedrock") || n.equals("netherrack") || n.equals("basalt")
                || n.equals("smooth_basalt") || n.equals("blackstone") || n.equals("end_stone")
                || n.equals("calcite") || n.equals("dripstone_block") || n.equals("mossy_cobblestone"))
            return 's';
        return 'o';
    }

    static char heightChar(int rel) {
        if (rel == 0) return '0';
        if (rel >= 1 && rel <= 9) return (char) ('0' + rel);
        if (rel >= 10 && rel <= 35) return (char) ('a' + (rel - 10));
        if (rel <= -1 && rel >= -26) return (char) ('A' + (-rel - 1));
        return rel > 35 ? '!' : '?';
    }

    static int topY(AgentPathfinder.Grid grid, int x, int z, int fromY, int toY) {
        int hi = Math.max(fromY, toY), lo = Math.min(fromY, toY);
        for (int y = hi; y >= lo; y--) {
            if (!grid.loaded(x, y, z)) return Integer.MIN_VALUE;
            if (isSurface(grid.name(x, y, z))) return y;
        }
        return Integer.MIN_VALUE;
    }

    /** Nearest standable feet Y in the window. Live bodies often report head/air Y over grass. */
    static int floodStartY(AgentPathfinder.Grid grid, int ax, int ay, int az, int ymin, int ymax) {
        if (ay >= ymin && ay <= ymax && grid.standable(ax, ay, az)) return ay;
        int best = Integer.MIN_VALUE, dist = Integer.MAX_VALUE;
        for (int y = ymin; y <= ymax; y++) {
            if (!grid.standable(ax, y, az)) continue;
            int d = Math.abs(y - ay);
            if (d < dist) { dist = d; best = y; }
        }
        return best;
    }

    private static void flood(AgentPathfinder.Grid grid, int ax, int ay, int az, int ymin, int ymax,
                              int minX, int maxX, int minZ, int maxZ, boolean dig, Set<Long> out) {
        ArrayDeque<long[]> q = new ArrayDeque<>();
        if (ay >= ymin && ay <= ymax && grid.usable(ax, ay, az) && grid.standable(ax, ay, az)) {
            if (out.add(AgentPathfinder.pack(ax, ay, az))) q.add(new long[]{ax, ay, az});
        }
        while (!q.isEmpty()) {
            long[] c = q.removeFirst();
            int x = (int) c[0], y = (int) c[1], z = (int) c[2];
            AgentPathfinder.walkSteps(x, y, z, grid, dig, minX, maxX, minZ, maxZ, (nx, ny, nz, move) -> {
                if (ny < ymin || ny > ymax) return;
                if (out.add(AgentPathfinder.pack(nx, ny, nz))) q.add(new long[]{nx, ny, nz});
            });
        }
    }

    private static char mark(AgentPathfinder.Grid grid, int x, int z, int ax, int ay, int az,
                             int ymin, int ymax, int stand, Set<Long> walk, Set<Long> dug) {
        if (x == ax && z == az) return '@';
        if (reached(walk, x, z, ymin, ymax)) return '+';
        if (reached(dug, x, z, ymin, ymax)) return '*';
        if (hazardColumn(grid, x, z, ymin, ymax, stand == Integer.MIN_VALUE ? ay : stand)) return '!';
        if (liquidColumn(grid, x, z, ymin, ymax, stand)) return '~';
        if (stand != Integer.MIN_VALUE) return '.';
        if (solidColumn(grid, x, z, ymin, ymax)) return '#';
        if (cliff(grid, x, z, ymin, ymax)) return 'v';
        return '#';
    }

    private static boolean reached(Set<Long> set, int x, int z, int ymin, int ymax) {
        for (int y = ymin; y <= ymax; y++) if (set.contains(AgentPathfinder.pack(x, y, z))) return true;
        return false;
    }

    private static int standY(AgentPathfinder.Grid grid, int x, int z, int ymin, int ymax, int preferY) {
        if (preferY >= ymin && preferY <= ymax && grid.standable(x, preferY, z)) return preferY;
        for (int y = ymax; y >= ymin; y--) if (grid.standable(x, y, z)) return y;
        return Integer.MIN_VALUE;
    }

    private static boolean hazardColumn(AgentPathfinder.Grid grid, int x, int z, int ymin, int ymax, int y) {
        int lo = Math.max(ymin, y - 1), hi = Math.min(ymax + 1, y + 1);
        for (int iy = lo; iy <= hi; iy++) if (grid.hazard(x, iy, z)) return true;
        return false;
    }

    private static boolean liquidColumn(AgentPathfinder.Grid grid, int x, int z, int ymin, int ymax, int stand) {
        if (stand != Integer.MIN_VALUE) return grid.water(x, stand, z) || grid.water(x, stand - 1, z);
        for (int y = ymin; y <= ymax; y++) if (grid.water(x, y, z)) return true;
        return false;
    }

    private static boolean solidColumn(AgentPathfinder.Grid grid, int x, int z, int ymin, int ymax) {
        for (int y = ymin; y <= ymax; y++)
            if (grid.open(x, y, z) && grid.open(x, y + 1, z)) return false;
        return true;
    }

    private static boolean cliff(AgentPathfinder.Grid grid, int x, int z, int ymin, int ymax) {
        int[][] dirs = {{1, 0}, {-1, 0}, {0, 1}, {0, -1}};
        for (int[] d : dirs) {
            int nx = x + d[0], nz = z + d[1];
            int from = Integer.MIN_VALUE;
            for (int y = ymax; y >= ymin; y--) if (grid.standable(nx, y, nz)) { from = y; break; }
            if (from == Integer.MIN_VALUE) continue;
            boolean safe = false;
            for (int drop = 1; drop <= AgentPathfinder.MAX_WATER_DROP; drop++) {
                int ny = from - drop;
                if (!grid.standable(x, ny, z)) continue;
                boolean water = grid.water(x, ny, z) || grid.water(x, ny - 1, z);
                if (drop <= AgentPathfinder.MAX_DROP || water) { safe = true; break; }
            }
            if (!safe) return true;
        }
        return false;
    }

    private static boolean columnLoaded(AgentPathfinder.Grid grid, int x, int ymin, int ymax, int z) {
        return grid.loaded(x, ymin, z) && grid.loaded(x, ymax, z);
    }

    static NameGrid snapshot(World world, int x0, int y0, int z0, int x1, int y1, int z1) {
        int sx = x1 - x0 + 1, sy = y1 - y0 + 1, sz = z1 - z0 + 1;
        String[] cells = new String[sx * sy * sz];
        int minH = world.getMinHeight(), maxH = world.getMaxHeight();
        for (int z = z0; z <= z1; z++) for (int x = x0; x <= x1; x++) {
            boolean loaded = chunkLoaded(world, x, z);
            for (int y = y0; y <= y1; y++) {
                int i = (x - x0) + sx * ((y - y0) + sy * (z - z0));
                if (!loaded || y < minH || y >= maxH) continue;
                cells[i] = world.getBlockAt(x, y, z).getType().name();
            }
        }
        return new NameGrid(cells, x0, y0, z0, sx, sy, sz, minH, maxH);
    }

    static final class NameGrid implements AgentPathfinder.Grid {
        final String[] cells;
        final int x0, y0, z0, sx, sy, sz, minY, maxY;
        NameGrid(String[] cells, int x0, int y0, int z0, int sx, int sy, int sz, int minY, int maxY) {
            this.cells = cells; this.x0 = x0; this.y0 = y0; this.z0 = z0;
            this.sx = sx; this.sy = sy; this.sz = sz; this.minY = minY; this.maxY = maxY;
        }
        private int idx(int x, int y, int z) {
            int ix = x - x0, iy = y - y0, iz = z - z0;
            if (ix < 0 || iy < 0 || iz < 0 || ix >= sx || iy >= sy || iz >= sz) return -1;
            return ix + sx * (iy + sy * iz);
        }
        public String name(int x, int y, int z) {
            int i = idx(x, y, z);
            if (i < 0 || cells[i] == null) return "air";
            String n = cells[i].trim().toLowerCase(java.util.Locale.ROOT);
            return n.startsWith("minecraft:") ? n.substring("minecraft:".length()) : n;
        }
        public boolean loaded(int x, int y, int z) {
            int i = idx(x, y, z);
            return i >= 0 && cells[i] != null;
        }
        public boolean usable(int x, int y, int z) {
            return y - 1 >= minY && y + 1 < maxY && loaded(x, y, z);
        }
        public boolean open(int x, int y, int z) {
            String n = name(x, y, z);
            if (AgentPathfinder.hazardName(n)) return false;
            if (AgentPathfinder.waterName(n) || AgentPathfinder.climbName(n)) return true;
            if (AgentPathfinder.thinPassable(n, 1, true)) return true;
            if (AgentPathfinder.walkThroughName(n) || AgentPathfinder.doorWalkName(n)) return true;
            return n.equals("air") || n.equals("cave_air") || n.equals("void_air")
                    || n.equals("short_grass") || n.equals("tall_grass") || n.equals("torch") || n.equals("wall_torch");
        }
        public boolean floor(int x, int y, int z) {
            String n = name(x, y, z);
            if (AgentPathfinder.hazardName(n) || AgentPathfinder.waterName(n) || n.equals("air")
                    || n.equals("cave_air") || n.equals("void_air")) return false;
            if (AgentPathfinder.thinPassable(n, 1, true) && AgentPathfinder.surfaceHeight(n) < 0.999) return false;
            return !open(x, y, z);
        }
        public double surface(int x, int y, int z) {
            double s = AgentPathfinder.surfaceHeight(name(x, y, z));
            return s < 0.999 ? s : 0;
        }
        public boolean water(int x, int y, int z) { return AgentPathfinder.waterName(name(x, y, z)); }
        public boolean climbable(int x, int y, int z) { return AgentPathfinder.climbName(name(x, y, z)); }
        public boolean hazard(int x, int y, int z) { return AgentPathfinder.hazardName(name(x, y, z)); }
        public boolean soft(int x, int y, int z) { return AgentPathfinder.softName(name(x, y, z)); }
        public boolean diggable(int x, int y, int z) {
            String n = name(x, y, z);
            return AgentPathfinder.softName(n) || AgentPathfinder.diggableName(n);
        }
        public boolean protectedCell(int x, int y, int z) { return false; }
    }

    private static Map<String, Object> envelope(String action, String world, int originX, int originZ,
                                                int yBase, int radius, int size, boolean complete) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("action", action);
        if (world != null) out.put("world", world);
        out.put("originX", originX);
        out.put("originZ", originZ);
        out.put("yBase", yBase);
        out.put("radius", radius);
        out.put("size", size);
        out.put("complete", complete);
        return out;
    }

    private static int highest(World world, int x, int z, int fromY, int toY) {
        try {
            int y = world.getHighestBlockYAt(x, z);
            if (y >= toY && y <= fromY && isSurface(world.getBlockAt(x, y, z).getType().name())) return y;
        } catch (RuntimeException ignored) {}
        for (int y = fromY; y >= toY; y--) {
            if (isSurface(world.getBlockAt(x, y, z).getType().name())) return y;
        }
        return Integer.MIN_VALUE;
    }

    private static boolean chunkLoaded(World world, int x, int z) {
        try { return world.isChunkLoaded(x >> 4, z >> 4); }
        catch (RuntimeException e) { return false; }
    }

    private static boolean isSurface(String raw) {
        String n = bare(raw);
        return !n.isEmpty() && !n.equals("air") && !n.equals("cave_air") && !n.equals("void_air");
    }

    private static String bare(String raw) {
        if (raw == null) return "";
        String n = raw.trim().toLowerCase(java.util.Locale.ROOT);
        return n.startsWith("minecraft:") ? n.substring("minecraft:".length()) : n;
    }

    static int requiredInt(JsonObject input, String key) {
        if (input == null || !input.has(key) || input.get(key).isJsonNull())
            throw new IllegalArgumentException("Missing field '" + key + "' for action scan_box");
        return optionalInt(input, key, 0, Integer.MIN_VALUE / 4, Integer.MAX_VALUE / 4);
    }

    static int optionalInt(JsonObject input, String key, int fallback, int lo, int hi) {
        if (input == null || !input.has(key) || input.get(key).isJsonNull()) return fallback;
        try {
            double n = input.get(key).isJsonPrimitive() && input.get(key).getAsJsonPrimitive().isNumber()
                    ? input.get(key).getAsDouble() : Double.parseDouble(input.get(key).getAsString().trim());
            if (!Double.isFinite(n)) throw new IllegalArgumentException(key + " must be an integer");
            long r = Math.round(n);
            if (r < lo || r > hi) throw new IllegalArgumentException(key + " must be " + lo + ".." + hi);
            return (int) r;
        } catch (RuntimeException e) {
            if (e instanceof IllegalArgumentException iae && e.getMessage() != null && e.getMessage().contains("must be"))
                throw iae;
            throw new IllegalArgumentException(key + " must be an integer");
        }
    }

    static boolean optionalFlag(JsonObject input, String key, boolean fallback) {
        if (input == null || !input.has(key) || input.get(key).isJsonNull()) return fallback;
        if (input.get(key).isJsonPrimitive() && input.get(key).getAsJsonPrimitive().isBoolean())
            return input.get(key).getAsBoolean();
        if (input.get(key).isJsonPrimitive() && input.get(key).getAsJsonPrimitive().isNumber())
            return input.get(key).getAsDouble() != 0;
        String s = input.get(key).getAsString().trim().toLowerCase(java.util.Locale.ROOT);
        if (s.equals("true") || s.equals("yes") || s.equals("1")) return true;
        if (s.equals("false") || s.equals("no") || s.equals("0")) return false;
        throw new IllegalArgumentException(key + " must be boolean");
    }

    private AgentPerception() {}
}
