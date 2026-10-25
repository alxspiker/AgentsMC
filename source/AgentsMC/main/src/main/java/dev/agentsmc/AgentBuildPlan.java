package dev.agentsmc;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.IntPredicate;

import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.data.Bisected;
import org.bukkit.block.data.Directional;
import org.bukkit.entity.Player;

import com.google.gson.JsonObject;

import net.citizensnpcs.api.npc.NPC;

/**
 * MVP preflight + post-verify for multi-block builds. Does not place or dig.
 * A {@code plan} string stays within the MCP scalar-field contract.
 */
final class AgentBuildPlan {
    static final int PLAN_CAP = 64;
    static final double EXACT = 0.6, NEAR = 2.0;

    static final class Cell {
        final int x, y, z;
        final String material;
        final String facing;
        final String half;
        Cell(int x, int y, int z, String material, String facing, String half) {
            this.x = x; this.y = y; this.z = z;
            this.material = material == null ? "" : material.toLowerCase(Locale.ROOT);
            this.facing = facing == null || facing.isBlank() ? null : facing.toLowerCase(Locale.ROOT);
            this.half = half == null || half.isBlank() ? null : half.toLowerCase(Locale.ROOT);
        }
    }

    static List<Cell> parsePlan(String plan) {
        if (plan == null || plan.isBlank()) throw new IllegalArgumentException("plan is empty");
        List<Cell> out = new ArrayList<>();
        for (String line : plan.split("[;\\n]+")) {
            String t = line.trim();
            if (t.isEmpty()) continue;
            out.add(parseCell(t));
            if (out.size() > PLAN_CAP)
                throw new IllegalArgumentException("plan has more than " + PLAN_CAP + " cells");
        }
        if (out.isEmpty()) throw new IllegalArgumentException("plan is empty");
        return out;
    }

    static Cell parseCell(String raw) {
        String[] parts = raw.trim().split("\\s+");
        if (parts.length < 4)
            throw new IllegalArgumentException("plan cell needs 'material x y z [facing=..] [half=..]': " + raw);
        String facing = null, half = null;
        for (int i = 4; i < parts.length; i++) {
            String p = parts[i];
            int eq = p.indexOf('=');
            if (eq <= 0) continue;
            String k = p.substring(0, eq).toLowerCase(Locale.ROOT);
            String v = p.substring(eq + 1).toLowerCase(Locale.ROOT);
            if (k.equals("facing") || k.equals("direction")) facing = v;
            else if (k.equals("half")) half = v;
        }
        try {
            return new Cell(Integer.parseInt(parts[1]), Integer.parseInt(parts[2]), Integer.parseInt(parts[3]),
                    AgentPlacement.blockKey(parts[0]), facing, half);
        } catch (NumberFormatException nfe) {
            throw new IllegalArgumentException("plan cell x/y/z must be integers: " + raw);
        }
    }

    /**
     * Mirror a cell across an axis through {@code origin}. Facing is kept unless
     * {@code flipFacing} — twin stairs that climb the same way must not flip
     * east/west just because the X column was mirrored.
     */
    static Cell mirror(Cell c, String axis, int origin, boolean flipFacing) {
        int x = c.x, z = c.z;
        String facing = c.facing;
        if ("x".equals(axis)) {
            x = 2 * origin - c.x;
            if (flipFacing && facing != null)
                facing = "east".equals(facing) ? "west" : "west".equals(facing) ? "east" : facing;
        } else if ("z".equals(axis)) {
            z = 2 * origin - c.z;
            if (flipFacing && facing != null)
                facing = "north".equals(facing) ? "south" : "south".equals(facing) ? "north" : facing;
        } else throw new IllegalArgumentException("mirror must be x or z");
        return new Cell(x, c.y, z, c.material, facing, c.half);
    }

    static String arrivalKind(double dist) {
        if (dist <= EXACT) return "exact";
        if (dist <= NEAR) return "near";
        return "out";
    }

    static boolean bodyOverlaps(int feetX, int feetY, int feetZ, int tx, int ty, int tz) {
        return tx == feetX && tz == feetZ && (ty == feetY || ty == feetY + 1);
    }

    static boolean occupied(int x, int y, int z, List<Cell> plan) {
        for (Cell c : plan) if (c.x == x && c.y == y && c.z == z) return true;
        return false;
    }

    static int[] standFor(int fromX, int fromY, int fromZ, int tx, int ty, int tz, List<Cell> plan,
                          Standable standable) {
        if (usableStand(fromX, fromY, fromZ, tx, ty, tz, plan, standable))
            return new int[]{fromX, fromY, fromZ};
        int[] best = null;
        int bestD = Integer.MAX_VALUE;
        for (int dy = -2; dy <= 2; dy++)
            for (int dx = -5; dx <= 5; dx++)
                for (int dz = -5; dz <= 5; dz++) {
                    int x = fromX + dx, y = fromY + dy, z = fromZ + dz;
                    if (!usableStand(x, y, z, tx, ty, tz, plan, standable)) continue;
                    int d = Math.abs(dx) + Math.abs(dy) + Math.abs(dz);
                    if (d < bestD) { bestD = d; best = new int[]{x, y, z}; }
                }
        return best;
    }

    private static boolean usableStand(int x, int y, int z, int tx, int ty, int tz, List<Cell> plan, Standable standable) {
        if (!standable.test(x, y, z)) return false;
        if (occupied(x, y, z, plan) || occupied(x, y + 1, z, plan)) return false;
        if (bodyOverlaps(x, y, z, tx, ty, tz)) return false;
        return AgentReach.inReach(x + 0.5, y + 1.62, z + 0.5, tx, ty, tz);
    }

    static String dataProp(String data, String key) {
        if (data == null || key == null) return "";
        String needle = key + "=";
        int i = data.indexOf(needle);
        if (i < 0) return "";
        int s = i + needle.length();
        int comma = data.indexOf(',', s);
        int end = data.indexOf(']', s);
        int e = data.length();
        if (comma >= 0) e = comma;
        if (end >= 0 && end < e) e = end;
        return data.substring(s, e).trim().toLowerCase(Locale.ROOT);
    }

    static Map<String, Object> verifyCell(Cell expected, String actualMaterial, String actualData) {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("x", expected.x);
        row.put("y", expected.y);
        row.put("z", expected.z);
        row.put("expected", expected.material.toUpperCase(Locale.ROOT));
        String actual = actualMaterial == null ? "UNLOADED" : AgentPlacement.blockKey(actualMaterial).toUpperCase(Locale.ROOT);
        row.put("actual", actual);
        boolean match = expected.material.equalsIgnoreCase(actual);
        if (expected.facing != null) {
            String got = dataProp(actualData, "facing");
            row.put("expectedFacing", expected.facing);
            row.put("actualFacing", got);
            if (!expected.facing.equals(got)) match = false;
        }
        if (expected.half != null) {
            String got = dataProp(actualData, "half");
            row.put("expectedHalf", expected.half);
            row.put("actualHalf", got);
            if (!expected.half.equals(got)) match = false;
        }
        row.put("ok", match);
        if (!match) {
            if ("AIR".equals(actual) || "CAVE_AIR".equals(actual) || "VOID_AIR".equals(actual) || "UNLOADED".equals(actual))
                row.put("fail", "missing");
            else if (expected.facing != null && !expected.facing.equals(dataProp(actualData, "facing")))
                row.put("fail", "wrong_facing");
            else row.put("fail", "wrong_block");
        }
        return row;
    }

    static Map<String, Object> preflightCell(Cell cell, IntPredicate solid, String targetMaterial,
                                             Map<String, String> neighbors) {
        boolean explicit = cell.facing != null;
        int face = AgentPlacement.attachSupport(cell.material, cell.facing == null ? "south" : cell.facing,
                explicit, solid);
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("x", cell.x);
        row.put("y", cell.y);
        row.put("z", cell.z);
        row.put("material", cell.material.toUpperCase(Locale.ROOT));
        if (cell.facing != null) row.put("facing", cell.facing);
        if (cell.half != null) row.put("half", cell.half);
        row.put("target", targetMaterial == null ? "UNLOADED" : targetMaterial.toUpperCase(Locale.ROOT));
        row.put("neighbors", neighbors);
        row.put("support", face < 0 ? "none" : AgentPlacement.supportName(face));
        row.put("supported", face >= 0);
        if (face >= 0) {
            String placed = AgentPlacement.placedBlock(cell.material, face);
            row.put("placeAs", placed.toUpperCase(Locale.ROOT));
            String dir = AgentPlacement.directionalFacing(placed, cell.facing, face, explicit);
            if (dir != null) row.put("placeFacing", dir);
            if (cell.half != null) row.put("placeHalf", cell.half);
        } else if (explicit) {
            row.put("error", AgentPlacement.missingSupport(cell.x, cell.y, cell.z, cell.facing, cell.material,
                    AgentPlacement.preferredSupport(cell.material, cell.facing, true),
                    neighbors.get(AgentPlacement.supportName(AgentPlacement.preferredSupport(cell.material, cell.facing, true))),
                    AgentPlacement.nearestWall(solid)));
        }
        return row;
    }

    static Map<String, Object> live(Player player, NPC npc, JsonObject input) {
        if (player == null || player.getWorld() == null)
            throw new IllegalArgumentException("build_plan requires a spawned body");
        String mode = input != null && input.has("mode") && !input.get("mode").isJsonNull()
                ? input.get("mode").getAsString().trim().toLowerCase(Locale.ROOT) : "preflight";
        if (!mode.equals("preflight") && !mode.equals("verify") && !mode.equals("stand"))
            throw new IllegalArgumentException("mode must be preflight, verify, or stand");
        List<Cell> cells;
        if (input != null && input.has("plan") && !input.get("plan").isJsonNull()
                && !input.get("plan").getAsString().isBlank())
            cells = parsePlan(input.get("plan").getAsString());
        else {
            if (input == null || !input.has("x") || !input.has("y") || !input.has("z"))
                throw new IllegalArgumentException("build_plan needs plan or x,y,z plus block/item/material");
            String mat = input.has("block") ? input.get("block").getAsString()
                    : input.has("item") ? input.get("item").getAsString()
                    : input.has("material") ? input.get("material").getAsString() : null;
            if (mat == null) throw new IllegalArgumentException("build_plan needs block, item, or material");
            String facing = input.has("facing") ? input.get("facing").getAsString()
                    : input.has("direction") ? input.get("direction").getAsString() : null;
            String half = input.has("half") ? input.get("half").getAsString() : null;
            cells = List.of(new Cell(
                    AgentPerception.requiredInt(input, "x"),
                    AgentPerception.requiredInt(input, "y"),
                    AgentPerception.requiredInt(input, "z"),
                    AgentPlacement.blockKey(mat), facing, half));
        }
        if (input != null && input.has("mirror") && !input.get("mirror").isJsonNull()) {
            String axis = input.get("mirror").getAsString().trim().toLowerCase(Locale.ROOT);
            boolean flip = input.has("flipFacing") && input.get("flipFacing").getAsBoolean();
            int origin = "x".equals(axis)
                    ? AgentPerception.optionalInt(input, "originX", 0, Integer.MIN_VALUE / 4, Integer.MAX_VALUE / 4)
                    : AgentPerception.optionalInt(input, "originZ", 0, Integer.MIN_VALUE / 4, Integer.MAX_VALUE / 4);
            List<Cell> mirrored = new ArrayList<>();
            for (Cell c : cells) mirrored.add(mirror(c, axis, origin, flip));
            cells = mirrored;
        }
        Location feet = player.getLocation();
        int fx = AgentSafeSpawn.blockCoord(feet.getX());
        int fy = AgentSafeSpawn.blockCoord(feet.getY());
        int fz = AgentSafeSpawn.blockCoord(feet.getZ());
        World world = player.getWorld();
        AgentSafeSpawn.Probe probe = AgentNavigation.worldProbe(world);
        List<Map<String, Object>> rows = new ArrayList<>();
        int ok = 0, bad = 0;
        for (Cell cell : cells) {
            Block b = world.isChunkLoaded(cell.x >> 4, cell.z >> 4) ? world.getBlockAt(cell.x, cell.y, cell.z) : null;
            if (mode.equals("verify")) {
                Map<String, Object> row = verifyCell(cell, b == null ? null : b.getType().name(),
                        b == null ? null : b.getBlockData().getAsString());
                if (b != null && b.getBlockData() instanceof Directional d)
                    row.put("actualFacing", d.getFacing().name().toLowerCase(Locale.ROOT));
                if (b != null && b.getBlockData() instanceof Bisected bis)
                    row.put("actualHalf", bis.getHalf() == Bisected.Half.TOP ? "top" : "bottom");
                rows.add(row);
                if (Boolean.TRUE.equals(row.get("ok"))) ok++; else bad++;
            } else {
                Map<String, String> neighbors = new LinkedHashMap<>();
                boolean[] solid = new boolean[6];
                for (int i = 0; i < AgentPlacement.SUPPORT_FACES.length; i++) {
                    int[] o = AgentPlacement.SUPPORT_FACES[i];
                    int nx = cell.x + o[0], ny = cell.y + o[1], nz = cell.z + o[2];
                    String name = "UNLOADED";
                    boolean isSolid = false;
                    if (world.isChunkLoaded(nx >> 4, nz >> 4)) {
                        Block n = world.getBlockAt(nx, ny, nz);
                        name = n.getType().name();
                        isSolid = !n.isEmpty() && !n.isLiquid()
                                && !AgentPlacement.replaceable(n.getType().name(), n.isReplaceable());
                    }
                    solid[i] = isSolid;
                    neighbors.put(AgentPlacement.supportName(i), name);
                }
                Map<String, Object> row = preflightCell(cell, i -> i >= 0 && i < solid.length && solid[i],
                        b == null ? "UNLOADED" : b.getType().name(), neighbors);
                row.put("bodyOverlap", bodyOverlaps(fx, fy, fz, cell.x, cell.y, cell.z));
                rows.add(row);
                if (Boolean.TRUE.equals(row.get("supported"))) ok++; else bad++;
            }
        }
        Cell first = cells.get(0);
        int[] stand = standFor(fx, fy, fz, first.x, first.y, first.z, cells,
                (x, y, z) -> AgentSafeSpawn.standable(x, y, z, probe));
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("action", "build_plan");
        out.put("mode", mode);
        out.put("cells", cells.size());
        out.put("ok", bad == 0);
        out.put("matched", ok);
        out.put("failed", bad);
        out.put("items", rows);
        out.put("bodyOverlap", bodyOverlaps(fx, fy, fz, first.x, first.y, first.z));
        double dist = Math.hypot(Math.hypot(feet.getX() - (first.x + 0.5), feet.getZ() - (first.z + 0.5)),
                feet.getY() - first.y);
        String arrival = arrivalKind(dist);
        out.put("arrival", arrival);
        out.put("arrivalExact", "exact".equals(arrival));
        out.put("arrivedNear", "near".equals(arrival));
        out.put("reachOk", AgentReach.inReach(player.getEyeLocation().getX(), player.getEyeLocation().getY(),
                player.getEyeLocation().getZ(), first.x, first.y, first.z));
        if ("near".equals(arrival) || "out".equals(arrival))
            out.put("note", "goto arrived_near is not exact arrival; reach-sensitive place/dig should stand at the aside cell first");
        if (stand != null) {
            Map<String, Object> aside = new LinkedHashMap<>();
            aside.put("x", stand[0]);
            aside.put("y", stand[1]);
            aside.put("z", stand[2]);
            aside.put("here", stand[0] == fx && stand[1] == fy && stand[2] == fz);
            out.put("stand", aside);
        }
        Integer[] path = AgentPerception.pathEnds(input);
        if (path != null) {
            out.put("path", AgentPerception.connectivity(AgentPathfinder.fromWorld(world),
                    path[0], path[1], path[2], path[3], path[4], path[5]));
        }
        out.put("deferred", List.of(
                "does not dig or place",
                "does not auto-goto the aside cell",
                "does not generate a hallway or stair run from a size; pass plan cells",
                "stair shape (inner/outer) is not computed"));
        return out;
    }

    interface Standable { boolean test(int x, int y, int z); }

    private AgentBuildPlan() {}
}
