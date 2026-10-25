package dev.agentsmc;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.PriorityQueue;
import java.util.Set;

/**
 * Baritone-style A* over the block grid. Walk, jump-1, safe drop-3 (deeper
 * into water), swim, climb. A player is 1.8 blocks tall: stand/walk needs a
 * 1x2 air column (thin slabs/snow in the head cell are not headroom). Does not
 * jump a 2-block face; those are walked around to a 1-step. Avoids powder snow,
 * lava, fire, cactus and berry bushes. Optional 1–2 soft-block digs and
 * pillar/bridge. Never teleports except the same-column air snap and a
 * cratered-door STOOP (same teleport class).
 */
final class AgentPathfinder {
    static final String NO_PATH = "no_path";
    static final String BLOCKED = "blocked_by_protection";
    static final String UNLOADED = "unloaded_chunks";
    static final String TIMEOUT = "timeout";
    static final int SEGMENT = 48;
    static final int MAX_EXPAND = 20_000;
    static final int MAX_DROP = 3;
    static final int MAX_WATER_DROP = 12;
    /** Player hitbox is 1.8; feet+head must both be clear. */
    static final int BODY_BLOCKS = 2;
    /** Extra A* cost for standing at the foot of a +2 face so the search walks around. */
    static final double FACE_HUG = 28;
    static final int WALK = 0, JUMP = 1, DROP = 2, SWIM = 3, CLIMB = 4, DIG = 5, PILLAR = 6, BRIDGE = 7, STOOP = 8;
    /** Horiz/vert range to step into a doorway when the stoop was cratered. */
    static final int STOOP_H = 6, STOOP_UP = 2, STOOP_DOWN = 3;
    /** Extra A* cost per mined block. Was 8, which let a connected shaft flood 20k walk cells before a 15-block tunnel. */
    static final double DIG_BLOCK = 1.2;
    static final double DIG_SHAFT = 2.8;
    private static final int[][] DIRS = {{1,0},{-1,0},{0,1},{0,-1},{1,1},{1,-1},{-1,1},{-1,-1}};
    private static final double SQRT2 = 1.414213562;

    interface Grid {
        boolean open(int x, int y, int z);
        boolean floor(int x, int y, int z);
        boolean usable(int x, int y, int z);
        boolean water(int x, int y, int z);
        boolean climbable(int x, int y, int z);
        boolean hazard(int x, int y, int z);
        boolean soft(int x, int y, int z);
        boolean protectedCell(int x, int y, int z);
        boolean loaded(int x, int y, int z);
        /** Block name for perception maps (air when unknown). */
        default String name(int x, int y, int z) { return "air"; }
        /** Collision height of a thin surface in this cell (snow/carpet/bottom slab), else 0. */
        default double surface(int x, int y, int z) { return 0; }
        /** Feet Y while standing in cell (x,y,z). Snow layers sit at y+(layers-1)/8, carpets y+0.0625, bottom slabs y+0.5. */
        default double standY(int x, int y, int z) {
            double s = surface(x, y, z);
            return (s > 0 && s < 0.999) ? y + s : y;
        }
        default boolean standable(int x, int y, int z) {
            if (!usable(x, y, z) || hazard(x, y, z) || hazard(x, y - 1, z)) return false;
            if (!open(x, y, z) || !headClear(this, x, y + 1, z)) return false;
            if (foliageName(name(x, y, z))) return false;
            if (floor(x, y - 1, z) && !hazard(x, y - 1, z)) return true;
            double s = surface(x, y, z);
            if (s > 0 && s < 0.999) return true;
            if (water(x, y, z) || water(x, y - 1, z)) return true;
            return climbable(x, y, z) || climbable(x, y - 1, z);
        }
        /** Blocks the planner may mine when {@code dig} is on. Defaults to {@link #soft}. */
        default boolean diggable(int x, int y, int z) { return soft(x, y, z); }
    }

    static final class Options {
        boolean dig = false, build = false;
        int segment = SEGMENT, maxExpand = MAX_EXPAND;
        final Set<Long> penalized = new HashSet<>();
        /** Hard-avoid cells that stalled twice. Start cell is still expanded from. */
        final Set<Long> banned = new HashSet<>();
    }

    static final class Result {
        final List<int[]> path;
        final String fail;
        final String note;
        final boolean segment;
        final int expands;
        Result(List<int[]> path, String fail, String note, boolean segment, int expands) {
            this.path = path; this.fail = fail; this.note = note; this.segment = segment; this.expands = expands;
        }
        static Result ok(List<int[]> path, boolean segment, int expands) {
            return new Result(path, null, segment ? "segment toward goal" : "full path", segment, expands);
        }
        static Result fail(String code, String note, int expands) {
            return new Result(List.of(), code, note, false, expands);
        }
    }

    private static final class Node implements Comparable<Node> {
        final int x, y, z, move;
        final double g, f;
        final Node parent;
        Node(int x, int y, int z, int move, double g, double f, Node parent) {
            this.x = x; this.y = y; this.z = z; this.move = move; this.g = g; this.f = f; this.parent = parent;
        }
        public int compareTo(Node o) {
            int c = Double.compare(f, o.f);
            return c != 0 ? c : Double.compare(g, o.g);
        }
    }

    static boolean hazardName(String raw) {
        String n = bare(raw);
        return n.equals("powder_snow") || n.equals("lava") || n.equals("fire") || n.equals("soul_fire")
                || n.equals("campfire") || n.equals("soul_campfire") || n.equals("magma_block")
                || n.equals("cactus") || n.equals("sweet_berry_bush") || n.equals("wither_rose");
    }
    static boolean waterName(String raw) {
        String n = bare(raw);
        return n.equals("water") || n.equals("bubble_column") || n.equals("kelp") || n.equals("kelp_plant")
                || n.equals("seagrass") || n.equals("tall_seagrass");
    }
    static boolean climbName(String raw) {
        String n = bare(raw);
        return n.equals("ladder") || n.equals("vine") || n.endsWith("_vines") || n.endsWith("_vine")
                || n.equals("scaffolding") || n.equals("twisting_vines") || n.equals("weeping_vines");
    }
    static boolean softName(String raw) {
        if (hazardName(raw)) return false;
        String n = bare(raw);
        return n.equals("dirt") || n.equals("grass_block") || n.equals("coarse_dirt") || n.equals("rooted_dirt")
                || n.equals("podzol") || n.equals("mycelium") || n.equals("dirt_path") || n.equals("farmland")
                || n.equals("sand") || n.equals("red_sand") || n.equals("gravel") || n.equals("clay")
                || n.equals("snow") || n.equals("snow_block") || n.equals("mud") || n.equals("soul_sand")
                || n.equals("soul_soil") || n.equals("moss_block") || n.equals("short_grass") || n.equals("tall_grass")
                || n.equals("fern") || n.equals("large_fern") || n.equals("dead_bush");
    }
    /** Player workstations a tunnel must path around, never mine. */
    static boolean workstationName(String raw) {
        String n = bare(raw);
        return n.equals("crafting_table") || n.equals("furnace") || n.equals("blast_furnace") || n.equals("smoker")
                || n.equals("chest") || n.equals("trapped_chest") || n.equals("ender_chest") || n.equals("barrel")
                || n.equals("hopper") || n.equals("anvil") || n.endsWith("_anvil") || n.equals("loom")
                || n.equals("cartography_table") || n.equals("fletching_table") || n.equals("smithing_table")
                || n.equals("grindstone") || n.equals("stonecutter") || n.equals("enchanting_table")
                || n.equals("brewing_stand") || n.equals("lectern") || n.equals("composter")
                || n.endsWith("_chest") || n.contains("shulker_box") || n.equals("beacon")
                || n.equals("respawn_anchor");
    }
    /** Terrain a {@code dig:true} goto may mine, including stone. Not bedrock, barriers, or workstations. */
    static boolean diggableName(String raw) {
        if (softName(raw) || hazardName(raw) || waterName(raw)) return softName(raw);
        if (workstationName(raw)) return false;
        String n = bare(raw);
        if (n.isEmpty() || n.equals("air") || n.equals("cave_air") || n.equals("void_air")) return false;
        if (n.equals("bedrock") || n.equals("barrier") || n.equals("command_block") || n.equals("chain_command_block")
                || n.equals("repeating_command_block") || n.equals("end_portal") || n.equals("end_portal_frame")
                || n.equals("nether_portal") || n.equals("reinforced_deepslate") || n.equals("obsidian")
                || n.equals("crying_obsidian") || n.equals("structure_block") || n.equals("jigsaw"))
            return false;
        if (foliageName(raw) && (n.endsWith("_leaves") || n.contains("leaves"))) return true;
        return n.equals("stone") || n.equals("cobblestone") || n.equals("granite") || n.equals("diorite")
                || n.equals("andesite") || n.equals("tuff") || n.equals("calcite") || n.equals("dripstone_block")
                || n.equals("deepslate") || n.equals("cobbled_deepslate")
                || n.contains("deepslate") || n.endsWith("_ore") || n.equals("netherrack") || n.equals("basalt")
                || n.equals("smooth_basalt") || n.equals("blackstone") || n.equals("end_stone")
                || n.equals("sandstone") || n.equals("red_sandstone") || n.equals("terracotta")
                || n.endsWith("_terracotta") || n.equals("packed_mud") || n.equals("mossy_cobblestone");
    }
    /** Leaves and logs: solid ceiling/floor, never jump/stand headroom. */
    static boolean foliageName(String raw) {
        String n = bare(raw);
        return n.endsWith("_leaves") || n.contains("leaves") || n.equals("leaf_litter")
                || n.endsWith("_log") || n.equals("log") || n.endsWith("_wood") || n.equals("wood")
                || n.equals("mangrove_roots") || n.equals("mushroom_stem");
    }
    /** Snow layers (not snow_block / powder_snow), carpets, and slabs — passable/standable with fractional height. */
    static boolean thinName(String raw) {
        String n = bare(raw);
        return n.equals("snow") || n.equals("moss_carpet") || n.endsWith("_carpet") || n.endsWith("_slab")
                || partialName(n) > 0;
    }
    /**
     * Other partial-height blocks a body stands on top of inside their own cell
     * (fb-39/40): daylight detector 0.375, stonecutter 0.5625, pale moss carpet etc.
     * Returns collision height, or 0 when not a known partial block.
     */
    static double partialName(String raw) {
        String n = bare(raw);
        return switch (n) {
            case "daylight_detector" -> 0.375;
            case "stonecutter" -> 0.5625;
            case "pale_moss_carpet" -> 0.0625;
            default -> 0;
        };
    }
    static boolean carpetName(String raw) {
        String n = bare(raw);
        return n.equals("moss_carpet") || n.endsWith("_carpet");
    }
    static boolean slabName(String raw) {
        return bare(raw).endsWith("_slab");
    }
    /**
     * Non-solid plants a player walks through: mushrooms, flowers, short grass,
     * ferns, saplings. Not mushroom blocks/stems or leaves.
     */
    static boolean walkThroughName(String raw) {
        String n = bare(raw);
        if (n.isEmpty()) return false;
        if (AgentPlacement.replaceable(n, false)) return true;
        if (n.equals("brown_mushroom") || n.equals("red_mushroom")
                || n.equals("crimson_fungus") || n.equals("warped_fungus")) return true;
        if (n.endsWith("_sapling") || n.equals("sapling") || n.equals("bamboo_sapling")) return true;
        if (n.equals("sugar_cane") || n.equals("reeds")) return true;
        return n.endsWith("_mushroom") && !n.contains("block") && !n.contains("stem");
    }

    /**
     * Wooden doors and fence gates a body can open and walk through.
     * Iron doors stay closed unless already open (live Grid checks BlockData).
     */
    static boolean doorWalkName(String raw) {
        String n = bare(raw);
        if (n.equals("iron_door") || n.equals("iron_trapdoor")) return false;
        return n.endsWith("_door") || n.equals("door") || n.endsWith("_fence_gate") || n.equals("fence_gate");
    }

    /** World/walkmap open: plants, thin surfaces, and wooden doors are walkable even when Bukkit marks them solid. */
    static boolean cellOpen(String raw, boolean liquid, boolean passable, boolean collidable, boolean thin) {
        if (hazardName(raw)) return false;
        if (walkThroughName(raw) || thin || doorWalkName(raw)) return true;
        if (waterName(raw) || climbName(raw)) return true;
        if (foliageName(raw)) return false;
        return !liquid && (passable || !collidable);
    }

    /** World/walkmap floor: plants are never a floor (they sit on one). */
    static boolean cellFloor(String raw, boolean liquid, boolean passable, boolean solid, boolean thin, double surface) {
        if (hazardName(raw) || waterName(raw) || walkThroughName(raw)) return false;
        if (thin && surface < 0.999) return false;
        return !liquid && !passable && solid;
    }

    /**
     * Thin surfaces a player walks through (snow layers 1–7, carpets, bottom slabs).
     * 8-layer snow is a full block; top/double slabs are full floors.
     */
    static boolean thinPassable(String raw, int snowLayers, boolean bottomSlab) {
        String n = bare(raw);
        if (n.equals("snow")) return snowLayers > 0 && snowLayers <= 8;
        if (carpetName(n)) return true;
        if (slabName(n)) return bottomSlab;
        return partialName(n) > 0;
    }
    static boolean thinPassable(org.bukkit.block.Block block) {
        if (block == null) return false;
        return thinPassable(block.getType().name(), snowLayersOf(block), bottomSlabOf(block));
    }
    /** Collision height in the cell. Snow = (layers-1)/8 (1 layer = 0), carpet = 0.0625, bottom slab = 0.5, full block = 1. */
    static double surfaceHeight(String raw, int snowLayers, boolean bottomSlab) {
        String n = bare(raw);
        if (n.equals("snow")) {
            // Vanilla SnowLayerBlock collision is (layers-1)/8: 1 layer has NO collision
            // (feet stay at y), 8 layers = 0.875. 0.7.3: was layers/8, so every 1-layer
            // snow waypoint expected feet at y+0.125 and arrival never fired (per-block stutter).
            int layers = snowLayers < 1 ? 1 : Math.min(8, snowLayers);
            return (layers - 1) * 0.125;
        }
        if (carpetName(n)) return 0.0625;
        if (slabName(n)) return bottomSlab ? 0.5 : 1;
        return partialName(n);
    }
    static double surfaceHeight(String raw) { return surfaceHeight(raw, 1, true); }
    static double surfaceOf(org.bukkit.block.Block block) {
        if (block == null) return 0;
        return surfaceHeight(block.getType().name(), snowLayersOf(block), bottomSlabOf(block));
    }
    static double standY(Grid grid, int x, int y, int z) {
        return grid == null ? y : grid.standY(x, y, z);
    }

    static long pack(int x, int y, int z) {
        return ((long) (x + 30_000_000) << 38) | ((long) (z + 30_000_000) << 12) | ((y + 2048) & 0xFFF);
    }

    static double heuristic(int x, int y, int z, int gx, int gy, int gz) {
        int dx = Math.abs(gx - x), dz = Math.abs(gz - z), dy = Math.abs(gy - y);
        int ortho = Math.min(dx, dz), straight = Math.max(dx, dz) - ortho;
        return ortho * SQRT2 + straight + dy * 1.1;
    }

    static Result plan(int sx, int sy, int sz, int gx, int gy, int gz, Grid grid, Options opt) {
        if (opt == null) opt = new Options();
        if (!grid.loaded(sx, sy, sz) || !grid.usable(sx, sy, sz))
            return Result.fail(UNLOADED, "Start chunk is not loaded", 0);
        if (!grid.loaded(gx, gy, gz))
            return Result.fail(UNLOADED, "Goal chunk is not loaded", 0);
        if (!tryStand(grid, sx, sy, sz)) {
            int[] snap = snapGoal(sx, sy, sz, grid, null);
            if (snap != null) { sx = snap[0]; sy = snap[1]; sz = snap[2]; }
        }
        int[] goal = snapGoal(gx, gy, gz, grid, opt);
        if (goal == null)
            return Result.fail(NO_PATH, "No standable cell at the goal", 0);
        gx = goal[0]; gy = goal[1]; gz = goal[2];
        final int tgx = gx, tgy = gy, tgz = gz;
        final int startY = sy;
        final Options options = opt;
        if (grid.protectedCell(tgx, tgy, tgz) && AgentSafeSpawn.trapped(tgx, tgy, tgz, AgentSafeSpawn.WALK, probe(grid)) && !options.build)
            return Result.fail(BLOCKED, "Goal is a protected pit and building is prohibited", 0);
        if (sx == tgx && sy == tgy && sz == tgz)
            return Result.ok(List.of(new int[]{sx, sy, sz, WALK}), false, 0);

        int cheb = Math.max(Math.abs(tgx - sx), Math.abs(tgz - sz));
        boolean far = cheb > options.segment || Math.abs(tgy - sy) > options.segment;
        int window = searchWindow(cheb, far, options.segment);
        int yPad = Math.max(8, Math.abs(tgy - sy) + 6);
        double startH = heuristic(sx, sy, sz, tgx, tgy, tgz);

        PriorityQueue<Node> open = new PriorityQueue<>();
        Map<Long, Double> best = new HashMap<>();
        Node start = new Node(sx, sy, sz, WALK, 0, startH, null);
        open.add(start);
        best.put(pack(sx, sy, sz), 0d);
        Node reached = null, closer = start, around = start;
        int expands = 0;
        boolean sawUnloaded = false, sawProtectedBuild = false;

        while (!open.isEmpty() && expands < opt.maxExpand) {
            Node cur = open.poll();
            Double known = best.get(pack(cur.x, cur.y, cur.z));
            if (known != null && cur.g > known + 1e-9) continue;
            expands++;
            if (cur.x == tgx && cur.y == tgy && cur.z == tgz) { reached = cur; break; }
            if (heuristic(cur.x, cur.y, cur.z, tgx, tgy, tgz) + 1e-9 < heuristic(closer.x, closer.y, closer.z, tgx, tgy, tgz))
                closer = cur;
            if (!unclimbableFace(grid, cur.x, cur.y, cur.z, tgx, tgy, tgz)
                    && !progressDead(grid, cur.x, cur.y, cur.z, tgx, tgy, tgz)
                    && heuristic(cur.x, cur.y, cur.z, tgx, tgy, tgz) + 1e-9
                    < heuristic(around.x, around.y, around.z, tgx, tgy, tgz))
                around = cur;
            if (Math.max(Math.abs(cur.x - sx), Math.abs(cur.z - sz)) >= window) continue;
            if (Math.abs(cur.y - sy) > yPad && Math.abs(cur.y - tgy) > yPad) continue;

            // pillar in place
            if (options.build && !grid.protectedCell(cur.x, cur.y, cur.z)
                    && grid.open(cur.x, cur.y + 1, cur.z) && grid.open(cur.x, cur.y + 2, cur.z)
                    && !grid.hazard(cur.x, cur.y + 1, cur.z)) {
                offer(open, best, cur, cur.x, cur.y + 1, cur.z, PILLAR, 12, tgx, tgy, tgz, options);
            } else if (options.build && grid.protectedCell(cur.x, cur.y, cur.z)) {
                sawProtectedBuild = true;
            }

            int minX = sx - window, maxX = sx + window, minZ = sz - window, maxZ = sz + window;
            int flags = walkSteps(cur.x, cur.y, cur.z, grid, options.dig, minX, maxX, minZ, maxZ, (nx, ny, nz, move) -> {
                if (move != DIG && !(nx == tgx && ny == tgy && nz == tgz)
                        && (move == DROP || move == JUMP)
                        && (pocketCell(grid, nx, ny, nz)
                        || faceTowardGoal(grid, nx, ny, nz, tgx, tgy, tgz)))
                    return;
                boolean goalCell = nx == tgx && ny == tgy && nz == tgz;
                // fb-39/40: a +1 cell is not a jump when the real feet heights differ by
                // more than vanilla jump height (full block -> 3+ snow layers above).
                if (move == JUMP && grid.standY(nx, ny, nz) - grid.standY(cur.x, cur.y, cur.z) > JUMP_REACH) return;
                // fb-43: never drop into an enclosed 1-wide hole unless it is the goal.
                if (!goalCell && move == DROP && holeCell(grid, nx, ny, nz)) return;
                double cost = stepCost(cur.x, cur.y, cur.z, nx, ny, nz, move, grid);
                if (unclimbableFace(grid, nx, ny, nz, tgx, tgy, tgz)) cost += FACE_HUG;
                // fb-43: prefer ground-level routes near the start/target height over a
                // mountain ridge. Each block above both (plus 1 slop) costs extra.
                int above = ny - Math.max(startY, tgy) - 1;
                if (above > 0) cost += ALTITUDE * above;
                if (!goalCell && holeCell(grid, nx, ny, nz)) cost += HOLE_COST;
                offer(open, best, cur, nx, ny, nz, move, cost, tgx, tgy, tgz, options);
            });
            if ((flags & UNLOADED_NEAR) != 0) sawUnloaded = true;
            if ((flags & PROTECTED_DIG) != 0) sawProtectedBuild = true;

            for (int[] d : DIRS) {
                int nx = cur.x + d[0], nz = cur.z + d[1];
                if (Math.max(Math.abs(nx - sx), Math.abs(nz - sz)) > window) continue;
                double step = (d[0] != 0 && d[1] != 0) ? SQRT2 : 1;
                if (options.build && !grid.protectedCell(nx, cur.y - 1, nz) && !grid.protectedCell(nx, cur.y, nz)
                        && grid.open(nx, cur.y, nz) && grid.open(nx, cur.y + 1, nz)
                        && grid.open(nx, cur.y - 1, nz) && !grid.floor(nx, cur.y - 1, nz)
                        && !grid.hazard(nx, cur.y, nz) && !grid.water(nx, cur.y - 1, nz))
                    offer(open, best, cur, nx, cur.y, nz, BRIDGE, step + 12, tgx, tgy, tgz, options);
                else if (options.build && grid.protectedCell(nx, cur.y, nz)) sawProtectedBuild = true;
            }
        }

        if (reached != null) return Result.ok(reconstruct(reached), false, expands);
        boolean budget = expands >= opt.maxExpand;
        // A cratered doorway in range is a STOOP, not a walk to the rim that
        // then fails no_path (fb-24). A connected A* path still wins above.
        int[] stoop = stoopGoal(sx, sy, sz, tgx, tgy, tgz, grid);
        if (stoop != null) {
            List<int[]> hop = new ArrayList<>();
            hop.add(new int[]{sx, sy, sz, WALK});
            hop.add(new int[]{stoop[0], stoop[1], stoop[2], STOOP});
            return Result.ok(hop, false, expands);
        }
        // Partial path even on a short hop: walk around a 2-block face instead of
        // failing at its foot. Never hand back a segment that hugs the +2 rim,
        // walks to the lip of an unsafe dry drop, or is a 3-expand stub.
        if (around != start && heuristic(around.x, around.y, around.z, tgx, tgy, tgz) + 0.5 < startH
                && around.y - tgy <= MAX_DROP
                && !unclimbableFace(grid, around.x, around.y, around.z, tgx, tgy, tgz)
                && !progressDead(grid, around.x, around.y, around.z, tgx, tgy, tgz)
                && keepAround(budget, far, expands, heuristic(around.x, around.y, around.z, tgx, tgy, tgz), startH))
            return Result.ok(reconstruct(around), true, expands);
        if (closer != start && heuristic(closer.x, closer.y, closer.z, tgx, tgy, tgz) + 0.5 < startH
                && !unclimbableFace(grid, closer.x, closer.y, closer.z, tgx, tgy, tgz)
                && !progressDead(grid, closer.x, closer.y, closer.z, tgx, tgy, tgz)
                && closer.y - tgy <= MAX_DROP
                && keepAround(budget, far, expands, heuristic(closer.x, closer.y, closer.z, tgx, tgy, tgz), startH))
            return Result.ok(reconstruct(closer), true, expands);
        if (sawUnloaded && closer == start) return Result.fail(UNLOADED, "Path enters unloaded chunks", expands);
        if (sawProtectedBuild && closer == start) return Result.fail(BLOCKED, "Only remaining moves are protected dig/build", expands);
        if (expands >= options.maxExpand)
            return Result.fail(NO_PATH, "No route found within search budget (" + expands + " cells); not a time timeout", expands);
        return Result.fail(NO_PATH, "No walk/jump/swim/climb route (drops<=3, water drops<=12)", expands);
    }

    /** Vanilla jump apex is ~1.25; feet-height gaps above this are not a JUMP. */
    static final double JUMP_REACH = 1.25;
    /** Per-block cost above max(startY, goalY)+1 (fb-43). */
    static final double ALTITUDE = 0.8;
    /** Extra cost to enter a 1-wide hole cell on the way to somewhere else. */
    static final double HOLE_COST = 20;

    /**
     * Enclosed 1-wide hole (fb-43): a standable cell whose four cardinal neighbours
     * at feet height are all closed, so the only way out is up. Doorways and 1x2
     * tunnels have an open side and are not holes.
     */
    static boolean holeCell(Grid grid, int x, int y, int z) {
        if (grid == null || !tryStand(grid, x, y, z)) return false;
        if (grid.water(x, y, z) || grid.climbable(x, y, z)) return false;
        int[][] card = {{1,0},{-1,0},{0,1},{0,-1}};
        for (int[] d : card) if (grid.open(x + d[0], y, z + d[1])) return false;
        return true;
    }

    /**
     * Way out of an enclosed 1-2 deep hole (fb-43): the first cardinal neighbour
     * that is standable 1 or 2 blocks up, preferring the side toward the goal.
     * Returns {x, y, z} of that stand, or null when the body is not in a hole.
     */
    static int[] holeExit(Grid grid, int x, int y, int z, int gx, int gz) {
        if (!holeCell(grid, x, y, z)) return null;
        if (!grid.open(x, y + 2, z)) return null;
        List<int[]> card = new ArrayList<>(List.of(new int[]{1,0}, new int[]{-1,0}, new int[]{0,1}, new int[]{0,-1}));
        card.sort(java.util.Comparator.comparingDouble(d -> Math.hypot(gx - (x + d[0]), gz - (z + d[1]))));
        for (int up = 1; up <= 2; up++) {
            if (up == 2 && !grid.open(x, y + 3, z)) break;
            for (int[] d : card) {
                int nx = x + d[0], nz = z + d[1], ny = y + up;
                if (tryStand(grid, nx, ny, nz) && !grid.hazard(nx, ny, nz)) return new int[]{nx, ny, nz};
            }
        }
        return null;
    }

    static boolean longLeg(List<int[]> path, int from) {
        if (path == null || from < 0 || from >= path.size() - 1) return false;
        int dx = Integer.signum(path.get(from + 1)[0] - path.get(from)[0]);
        int dz = Integer.signum(path.get(from + 1)[2] - path.get(from)[2]);
        int run = 0;
        for (int i = from; i < path.size() - 1; i++) {
            int[] a = path.get(i), b = path.get(i + 1);
            if (b[1] != a[1]) break;
            if (Integer.signum(b[0] - a[0]) != dx || Integer.signum(b[2] - a[2]) != dz) break;
            run += Math.max(Math.abs(b[0] - a[0]), Math.abs(b[2] - a[2]));
        }
        return run >= 8;
    }

    static AgentSafeSpawn.Probe probe(Grid grid) {
        return new AgentSafeSpawn.Probe() {
            public boolean open(int x, int y, int z) { return grid.open(x, y, z); }
            public boolean floor(int x, int y, int z) { return grid.floor(x, y, z); }
            public boolean usable(int x, int y, int z) { return grid.usable(x, y, z); }
        };
    }

    static Grid fromWorld(org.bukkit.World world) {
        return new Grid() {
            private org.bukkit.block.Block at(int x, int y, int z) { return world.getBlockAt(x, y, z); }
            public boolean loaded(int x, int y, int z) {
                try { return world.isChunkLoaded(x >> 4, z >> 4); }
                catch (RuntimeException e) { return false; }
            }
            public boolean usable(int x, int y, int z) {
                try {
                    return y - 1 >= world.getMinHeight() && y + 1 < world.getMaxHeight() && loaded(x, y, z)
                            && world.getWorldBorder().isInside(new org.bukkit.Location(world, x + .5, y, z + .5));
                } catch (RuntimeException e) { return false; }
            }
            public boolean open(int x, int y, int z) {
                org.bukkit.block.Block b = at(x, y, z);
                org.bukkit.Material m = b.getType();
                if (doorWalk(b)) return true;
                return cellOpen(m.name(), b.isLiquid(), b.isPassable(), m.isCollidable(), thinPassable(b));
            }
            public boolean floor(int x, int y, int z) {
                org.bukkit.block.Block b = at(x, y, z);
                return cellFloor(b.getType().name(), b.isLiquid(), b.isPassable(), b.getType().isSolid(),
                        thinPassable(b), surfaceOf(b));
            }
            public double surface(int x, int y, int z) {
                org.bukkit.block.Block b = at(x, y, z);
                double s = surfaceOf(b);
                return s < 0.999 ? s : 0;
            }
            public String name(int x, int y, int z) { return at(x, y, z).getType().name(); }
            public boolean water(int x, int y, int z) { return waterName(at(x, y, z).getType().name()); }
            public boolean climbable(int x, int y, int z) { return climbName(at(x, y, z).getType().name()); }
            public boolean hazard(int x, int y, int z) { return hazardName(at(x, y, z).getType().name()); }
            public boolean soft(int x, int y, int z) { return softName(at(x, y, z).getType().name()); }
            public boolean diggable(int x, int y, int z) {
                org.bukkit.Material m = at(x, y, z).getType();
                try { if (m.getHardness() < 0) return false; } catch (RuntimeException ignored) {}
                return diggableName(m.name()) && !hazardName(m.name());
            }
            public boolean protectedCell(int x, int y, int z) {
                try { return AgentProtection.blocked(world, x, z); }
                catch (RuntimeException e) { return false; }
            }
        };
    }

    /** Load chunks covering start→goal plus pad so a 20–40 block goto is not an instant no_path. */
    static int loadCorridor(org.bukkit.World world, int sx, int sz, int gx, int gz, int pad) {
        if (world == null) return 0;
        int loaded = 0;
        int minX = Math.min(sx, gx) - pad, maxX = Math.max(sx, gx) + pad;
        int minZ = Math.min(sz, gz) - pad, maxZ = Math.max(sz, gz) + pad;
        for (int cx = minX >> 4; cx <= maxX >> 4; cx++)
            for (int cz = minZ >> 4; cz <= maxZ >> 4; cz++) {
                try { world.getChunkAt(cx, cz); loaded++; }
                catch (RuntimeException ignored) {}
            }
        return loaded;
    }

    static Grid tryWorld(org.bukkit.World world) {
        if (world == null) return null;
        try {
            if (world.getBlockAt(0, 64, 0) == null) return null;
            return fromWorld(world);
        } catch (RuntimeException e) {
            return null;
        }
    }

    static final int UNLOADED_NEAR = 1, PROTECTED_DIG = 2;
    interface StepSink { void accept(int x, int y, int z, int move); }

    /**
     * Same walk/jump/drop/swim/climb and optional dig steps the A* expander uses.
     * Pillar and bridge are build moves and are not included.
     */
    static int walkSteps(int x, int y, int z, Grid grid, boolean dig, StepSink sink) {
        return walkSteps(x, y, z, grid, dig, Integer.MIN_VALUE / 4, Integer.MAX_VALUE / 4,
                Integer.MIN_VALUE / 4, Integer.MAX_VALUE / 4, sink);
    }

    static int walkSteps(int x, int y, int z, Grid grid, boolean dig,
                         int minX, int maxX, int minZ, int maxZ, StepSink sink) {
        int flags = 0;
        for (int[] d : DIRS) {
            int nx = x + d[0], nz = z + d[1];
            if (nx < minX || nx > maxX || nz < minZ || nz > maxZ) continue;
            if (!grid.loaded(nx, y, nz)) { flags |= UNLOADED_NEAR; continue; }
            if (!clearCorner(x, y, z, d[0], d[1], grid)) continue;

            if (tryStand(grid, nx, y, nz))
                sink.accept(nx, y, nz, inWater(grid, x, y, z) || inWater(grid, nx, y, nz) ? SWIM : WALK);
            // Dest tryStand already requires dest 1x2 headroom. Do not also require
            // current y+2 open: that blocked JUMP up a self-dug 1x2 staircase (ceiling
            // at the current cell) while a player still steps up into the next 1x2.
            // Cardinal only: diagonal JUMP used clearCorner at y+1 (air above the
            // step) and cut solid ground corners, slamming the body into the face.
            // Foliage at current y+2 is not a 1x2 stair: jumpForwardScale holds the
            // body in-column so vy hits the leaf canopy (Hero under a tree).
            if ((d[0] == 0 || d[1] == 0) && tryStand(grid, nx, y + 1, nz)
                    && clearCorner(x, y + 1, z, d[0], d[1], grid)
                    && !canopyBlocksJump(grid, x, y, z))
                sink.accept(nx, y + 1, nz, JUMP);

            for (int drop = 1; drop <= MAX_WATER_DROP; drop++) {
                int ny = y - drop;
                if (!columnOpen(nx, y - 1, ny, nz, grid)) break;
                if (!tryStand(grid, nx, ny, nz)) continue;
                boolean waterLand = grid.water(nx, ny, nz) || grid.water(nx, ny - 1, nz);
                if (drop > MAX_DROP && !waterLand) continue;
                if (grid.hazard(nx, ny, nz) || grid.hazard(nx, ny - 1, nz)) continue;
                sink.accept(nx, ny, nz, waterLand ? SWIM : DROP);
                break;
            }

            if (grid.climbable(x, y, z) || grid.climbable(nx, y, nz) || grid.climbable(x, y + 1, z)) {
                if (tryStand(grid, nx, y + 1, nz) || (grid.climbable(nx, y + 1, nz) && grid.open(nx, y + 1, nz) && grid.open(nx, y + 2, nz)))
                    sink.accept(nx, y + 1, nz, CLIMB);
                if (tryStand(grid, nx, y - 1, nz) || (grid.climbable(nx, y - 1, nz) && grid.open(nx, y - 1, nz)))
                    sink.accept(nx, y - 1, nz, CLIMB);
            }

            if (dig) {
                int dug = digCount(grid, nx, y, nz);
                if (dug > 0 && dug <= 2 && !grid.protectedCell(nx, y, nz)
                        && (grid.floor(nx, y - 1, nz) || grid.water(nx, y, nz) || grid.water(nx, y - 1, nz))
                        && !grid.hazard(nx, y, nz) && !grid.hazard(nx, y - 1, nz))
                    sink.accept(nx, y, nz, DIG);
                else if (dug > 0 && grid.protectedCell(nx, y, nz)) flags |= PROTECTED_DIG;
                int downY = y - 1;
                int stair = digCount(grid, nx, downY, nz);
                if (stair > 0 && stair <= 2 && !grid.protectedCell(nx, downY, nz)
                        && grid.floor(nx, downY - 1, nz) && !grid.hazard(nx, downY, nz) && !grid.hazard(nx, downY - 1, nz))
                    sink.accept(nx, downY, nz, DIG);
                int upY = y + 1;
                int up = digCount(grid, nx, upY, nz);
                if (up > 0 && up <= 2 && !grid.protectedCell(nx, upY, nz)
                        && grid.floor(nx, y, nz) && !grid.hazard(nx, upY, nz) && !grid.hazard(nx, y, nz))
                    sink.accept(nx, upY, nz, DIG);
            }
        }
        if (grid.climbable(x, y, z) || grid.climbable(x, y - 1, z)) {
            if (grid.open(x, y + 1, z) && grid.open(x, y + 2, z) && !grid.hazard(x, y + 1, z))
                sink.accept(x, y + 1, z, CLIMB);
            if (grid.open(x, y - 1, z) && !grid.hazard(x, y - 1, z))
                sink.accept(x, y - 1, z, CLIMB);
        }
        if (dig && grid.floor(x, y - 1, z) && grid.diggable(x, y - 1, z)
                && !grid.protectedCell(x, y - 1, z) && grid.floor(x, y - 2, z)
                && (grid.open(x, y, z) || digCount(grid, x, y, z) <= 2)
                && !grid.hazard(x, y - 1, z)
                && !grid.hazard(x, y - 2, z))
            sink.accept(x, y - 1, z, DIG);
        if (inWater(grid, x, y, z)) {
            if (grid.open(x, y + 1, z) && (grid.water(x, y + 1, z) || tryStand(grid, x, y + 1, z)))
                sink.accept(x, y + 1, z, SWIM);
            if (grid.water(x, y - 1, z) || tryStand(grid, x, y - 1, z))
                sink.accept(x, y - 1, z, SWIM);
        }
        return flags;
    }

    static double stepCost(int x, int y, int z, int nx, int ny, int nz, int move, Grid grid) {
        int adx = Math.abs(nx - x), adz = Math.abs(nz - z);
        double step = (adx != 0 && adz != 0) ? SQRT2 : 1;
        return switch (move) {
            case JUMP -> step + 0.6;
            case DROP -> step + 0.25 * (y - ny);
            case CLIMB -> (adx + adz == 0) ? 1.3 : 1.4;
            case SWIM -> (adx + adz == 0) ? 1.3 : step;
            case DIG -> {
                if (adx + adz == 0) yield DIG_SHAFT;
                int dug = digCount(grid, nx, ny, nz);
                yield ny < y ? step + DIG_BLOCK * dug + 0.4 : step + DIG_BLOCK * dug;
            }
            default -> step;
        };
    }

    private static void offer(PriorityQueue<Node> open, Map<Long, Double> best, Node parent,
                              int x, int y, int z, int move, double cost, int gx, int gy, int gz, Options opt) {
        long k = pack(x, y, z);
        if (opt.banned.contains(k) && parent != null) return;
        double g = parent.g + cost + (opt.penalized.contains(k) ? 40 : 0);
        Double prev = best.get(k);
        if (prev != null && g >= prev - 1e-9) return;
        best.put(k, g);
        open.add(new Node(x, y, z, move, g, g + heuristic(x, y, z, gx, gy, gz), parent));
    }

    /** Chebyshev search radius. Short hops stay local so an unreachable hut does not flood 20k caves. */
    static int searchWindow(int cheb, boolean far, int segment) {
        if (segment < 1) segment = SEGMENT;
        if (far) return segment;
        return Math.max(24, cheb + 16);
    }

    /** Live door: wooden doors/gates always; iron only when already open. */
    static boolean doorWalk(org.bukkit.block.Block b) {
        if (b == null) return false;
        String n = bare(b.getType().name());
        if (n.equals("iron_door") || n.equals("iron_trapdoor")) {
            try {
                if (b.getBlockData() instanceof org.bukkit.block.data.Openable o) return o.isOpen();
            } catch (RuntimeException ignored) {}
            return false;
        }
        return doorWalkName(n);
    }

    /**
     * Keep a partial around-path. A 3-expand stub on a short hop is not a path.
     * A 20k-budget miss that already closed to within 8 is a real approach.
     */
    static boolean keepAround(boolean budget, boolean far, int expands, double aroundH, double startH) {
        if (aroundH + 0.5 >= startH) return false;
        if (!budget || far) return true;
        return expands >= 64 && aroundH <= 8;
    }

    /** Doorway or chest-front the body can step into when A* cannot (cratered village stoop). */
    static boolean stoopTarget(Grid grid, int x, int y, int z) {
        if (grid == null || !tryStand(grid, x, y, z)) return false;
        if (doorWalkName(grid.name(x, y, z))) return true;
        for (int[] d : DIRS) {
            if (d[0] != 0 && d[1] != 0) continue;
            if (doorWalkName(grid.name(x + d[0], y, z + d[1]))) return true;
            if (workstationName(grid.name(x + d[0], y, z + d[1]))) return true;
        }
        return workstationName(grid.name(x, y, z));
    }

    static boolean canStoop(int sx, int sy, int sz, int gx, int gy, int gz, Grid grid) {
        if (grid == null || !tryStand(grid, gx, gy, gz)) return false;
        if (!stoopTarget(grid, gx, gy, gz) && !stoopTarget(grid, sx, sy, sz)) return false;
        int cheb = Math.max(Math.abs(gx - sx), Math.abs(gz - sz));
        int dy = gy - sy;
        return cheb <= STOOP_H && dy >= -STOOP_DOWN && dy <= STOOP_UP;
    }

    static int[] stoopGoal(int sx, int sy, int sz, int gx, int gy, int gz, Grid grid) {
        if (canStoop(sx, sy, sz, gx, gy, gz, grid)) return new int[]{gx, gy, gz};
        int[] best = null;
        double score = Double.MAX_VALUE;
        for (int dx = -2; dx <= 2; dx++) for (int dz = -2; dz <= 2; dz++) for (int dy = -STOOP_DOWN; dy <= STOOP_UP; dy++) {
            int x = gx + dx, y = gy + dy, z = gz + dz;
            if (!canStoop(sx, sy, sz, x, y, z, grid)) continue;
            double d = dx * dx + dy * dy * 1.1 + dz * dz;
            if (d < score) { score = d; best = new int[]{x, y, z}; }
        }
        return best;
    }

    /** Standing at (x,y,z) hugs an unclimbable +2 face of the goal. Walk around. */
    static boolean unclimbableFace(int x, int y, int z, int gx, int gy, int gz) {
        return Math.max(Math.abs(x - gx), Math.abs(z - gz)) <= 1 && gy - y >= 2;
    }

    /** Goal-adjacent +2 face, or an intermediate +2 face toward the goal (rim pocket). */
    static boolean unclimbableFace(Grid grid, int x, int y, int z, int gx, int gy, int gz) {
        return unclimbableFace(x, y, z, gx, gy, gz) || faceTowardGoal(grid, x, y, z, gx, gy, gz);
    }

    /**
     * Cardinal neighbor is a +2 stand with no +1 step, and that column is closer
     * to the goal. The walker cannot mount it; a far goto must not hug it.
     */
    static boolean faceTowardGoal(Grid grid, int x, int y, int z, int gx, int gy, int gz) {
        if (grid == null) return false;
        double h = heuristic(x, y, z, gx, gy, gz);
        for (int[] d : DIRS) {
            if (d[0] != 0 && d[1] != 0) continue;
            int nx = x + d[0], nz = z + d[1];
            if (heuristic(nx, y, nz, gx, gy, gz) + 1e-9 >= h && !(nx == gx && nz == gz)) continue;
            if (tryStand(grid, nx, y + 1, nz)) continue;
            if (tryStand(grid, nx, y + 2, nz)) return true;
        }
        return false;
    }

    /**
     * No walk/jump/climb/swim/drop from here that closes on the goal. A closer
     * fallback into this cell is a stall (Helper crater-rim pocket).
     */
    static boolean progressDead(Grid grid, int x, int y, int z, int gx, int gy, int gz) {
        if (grid == null || (x == gx && y == gy && z == gz)) return false;
        double h = heuristic(x, y, z, gx, gy, gz);
        boolean[] progress = {false};
        walkSteps(x, y, z, grid, false, (nx, ny, nz, move) -> {
            if (move == DIG) return;
            if (heuristic(nx, ny, nz, gx, gy, gz) + 1e-9 < h) progress[0] = true;
        });
        return !progress[0];
    }

    /**
     * Head cell for a 1.8-tall player: must be open air (or water/plant), not a
     * thin slab/snow/carpet and not leaves/logs. A 1-block hole is not a walk.
     */
    static boolean headClear(Grid grid, int x, int y, int z) {
        if (grid == null || !grid.open(x, y, z)) return false;
        if (foliageName(grid.name(x, y, z))) return false;
        double s = grid.surface(x, y, z);
        return s <= 0 || s >= 0.999;
    }

    /** Jump/stand headroom: air, not leaves or logs. */
    static boolean headroomOpen(Grid grid, int x, int y, int z) {
        return headClear(grid, x, y, z);
    }

    /**
     * Leaf/log ceiling in the jump arc (current y+2). Stone/dirt 1x2 stair
     * ceilings are not foliage, so a self-dug tunnel still offers JUMP.
     */
    static boolean canopyBlocksJump(Grid grid, int x, int y, int z) {
        return grid != null && foliageName(grid.name(x, y + 2, z));
    }

    /**
     * Forest depression under a leaf/log ceiling: no climb/jump out and at most one
     * same-level walk. Stone 1x2 tunnels are not pockets. The goal cell is still
     * allowed by the expander.
     */
    static boolean pocketCell(Grid grid, int x, int y, int z) {
        if (grid == null || !tryStand(grid, x, y, z)) return false;
        boolean canopy = foliageName(grid.name(x, y + 1, z)) || foliageName(grid.name(x, y + 2, z));
        if (!canopy) return false;
        boolean jumpClear = headroomOpen(grid, x, y + 2, z);
        int[] walks = {0}, ups = {0};
        walkSteps(x, y, z, grid, false, (nx, ny, nz, move) -> {
            if (move == DROP || move == DIG || move == PILLAR || move == BRIDGE) return;
            if (move == JUMP) {
                if (jumpClear) ups[0]++;
                return;
            }
            if (move == CLIMB && ny > y) { ups[0]++; return; }
            if (move == WALK || move == SWIM) {
                walks[0]++;
                if (ny > y) ups[0]++;
            }
        });
        return ups[0] == 0 && walks[0] <= 1;
    }

    private static boolean tryStand(Grid grid, int x, int y, int z) {
        return grid.usable(x, y, z) && grid.standable(x, y, z);
    }

    private static boolean inWater(Grid grid, Node n) { return inWater(grid, n.x, n.y, n.z); }
    private static boolean inWater(Grid grid, int x, int y, int z) {
        return grid.water(x, y, z) || grid.water(x, y - 1, z);
    }

    private static boolean columnOpen(int x, int fromY, int toY, int z, Grid grid) {
        int lo = Math.min(fromY, toY), hi = Math.max(fromY, toY);
        for (int y = lo; y <= hi; y++) {
            if (!grid.open(x, y, z) && !grid.water(x, y, z)) return false;
            if (grid.hazard(x, y, z)) return false;
        }
        return true;
    }

    private static boolean clearCorner(int x, int y, int z, int dx, int dz, Grid grid) {
        if (dx == 0 || dz == 0) return true;
        boolean a = grid.open(x + dx, y, z) && grid.open(x + dx, y + 1, z);
        boolean b = grid.open(x, y, z + dz) && grid.open(x, y + 1, z + dz);
        return a || b;
    }

    private static int digCount(Grid grid, int x, int y, int z) {
        int n = 0;
        if (!grid.open(x, y, z)) {
            if (!grid.diggable(x, y, z)) return 99;
            n++;
        }
        if (!grid.open(x, y + 1, z)) {
            if (!grid.diggable(x, y + 1, z)) return 99;
            n++;
        }
        return n;
    }

    static boolean canDigStand(Grid grid, int x, int y, int z) {
        if (grid == null || !grid.usable(x, y, z) || grid.protectedCell(x, y, z)) return false;
        if (grid.hazard(x, y, z) || grid.hazard(x, y - 1, z)) return false;
        if (!grid.floor(x, y - 1, z) && !grid.water(x, y, z) && !grid.water(x, y - 1, z)) return false;
        int dug = digCount(grid, x, y, z);
        return dug > 0 && dug <= 2;
    }

    private static int[] snapGoal(int gx, int gy, int gz, Grid grid, Options opt) {
        if (grid.standable(gx, gy, gz)) return new int[]{gx, gy, gz};
        if (opt != null && opt.dig && canDigStand(grid, gx, gy, gz)) return new int[]{gx, gy, gz};
        if (grid.standable(gx, gy + 1, gz)) return new int[]{gx, gy + 1, gz};
        for (int dy = -1; dy >= -3; dy--) if (grid.standable(gx, gy + dy, gz)) return new int[]{gx, gy + dy, gz};
        int[] best = null; double score = Double.MAX_VALUE;
        for (int dx = -2; dx <= 2; dx++) for (int dz = -2; dz <= 2; dz++) for (int dy = -3; dy <= 2; dy++) {
            int x = gx + dx, y = gy + dy, z = gz + dz;
            boolean stand = grid.standable(x, y, z) || (opt != null && opt.dig && canDigStand(grid, x, y, z));
            if (!stand) continue;
            double d = dx * dx + dy * dy * 1.1 + dz * dz;
            if (d < score) { score = d; best = new int[]{x, y, z}; }
        }
        return best;
    }

    private static List<int[]> reconstruct(Node end) {
        List<int[]> path = new ArrayList<>();
        for (Node n = end; n != null; n = n.parent) path.add(new int[]{n.x, n.y, n.z, n.move});
        Collections.reverse(path);
        return path;
    }

    private static int snowLayersOf(org.bukkit.block.Block block) {
        try {
            org.bukkit.block.data.BlockData data = block.getBlockData();
            if (data instanceof org.bukkit.block.data.type.Snow snow) return snow.getLayers();
        } catch (RuntimeException ignored) {}
        return 1;
    }
    private static boolean bottomSlabOf(org.bukkit.block.Block block) {
        try {
            org.bukkit.block.data.BlockData data = block.getBlockData();
            if (data instanceof org.bukkit.block.data.type.Slab slab)
                return slab.getType() == org.bukkit.block.data.type.Slab.Type.BOTTOM;
        } catch (RuntimeException ignored) {}
        return true;
    }

    static String bare(String raw) {
        if (raw == null) return "";
        String n = raw.trim().toLowerCase(Locale.ROOT);
        if (n.startsWith("minecraft:")) n = n.substring("minecraft:".length());
        return n;
    }

    private AgentPathfinder() {}
}
