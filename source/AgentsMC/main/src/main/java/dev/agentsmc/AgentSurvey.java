package dev.agentsmc;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.entity.Player;

import com.google.gson.JsonObject;

/**
 * Semantic room survey. Flood-fills with the same walk/jump/drop rules as
 * {@code goto}/{@code walkmap}, then classifies enclosure, walls, doors, and
 * workstations. Never loads chunks; unknown is not blocked.
 */
final class AgentSurvey {
    static final int RADIUS_DEFAULT = 8, RADIUS_MAX = 12;
    static final int Y_BELOW = 2, Y_ABOVE = 6, Y_SPAN_MAX = 16;
    static final int CELL_BUDGET = 256, DEST_BUDGET = 48;
    /** Air blocks a player occupies. A typical finished room is one more (3). */
    static final int WALK_HEADROOM = AgentPathfinder.BODY_BLOCKS;
    static final int TYPICAL_HEADROOM = 3;
    static final String HEADROOM_LEGEND = "digits = air blocks from stand Y up to (not including) the ceiling; 2 is the walk minimum, 3 is a typical room; * open sky, . not in room, ? unloaded";
    private static final int[][] CARD = {{1, 0}, {-1, 0}, {0, 1}, {0, -1}};

    static Map<String, Object> surveyLive(Player player, JsonObject input) {
        return surveyLive(player, input, null);
    }

    static Map<String, Object> surveyLive(Player player, JsonObject input, net.citizensnpcs.api.npc.NPC npc) {
        Location at = player.getLocation();
        int radius = AgentPerception.optionalInt(input, "radius", RADIUS_DEFAULT, 1, RADIUS_MAX);
        int ay = at.getBlockY();
        int ymin = ay - Y_BELOW, ymax = ay + Y_ABOVE;
        World world = at.getWorld();
        AgentPerception.NameGrid box = AgentPerception.snapshot(world,
                at.getBlockX() - radius, ymin - 1, at.getBlockZ() - radius,
                at.getBlockX() + radius, ymax + 4, at.getBlockZ() + radius);
        Map<String, Object> out = survey(box, at.getBlockX(), ay, at.getBlockZ(), radius, ymin, ymax);
        out.put("world", world.getName());
        out.put("time", AgentWorldTime.view(world));
        Object roomObj = out.get("room");
        if (roomObj instanceof Map<?, ?> room) {
            @SuppressWarnings("unchecked")
            List<Map<String, Object>> objects = (List<Map<String, Object>>) ((Map<String, Object>) room).get("objects");
            AgentRespawn.annotateObjects(objects, world.getName(), npc);
            AgentSigns.annotateObjects(objects, world);
        }
        // fb-22: include nearby players/agents so a survey does not need a follow-up observe.
        List<Map<String, Object>> nearby = new java.util.ArrayList<>();
        try {
            for (Player other : world.getPlayers()) {
                if (other == player) continue;
                Location o = other.getLocation();
                if (!nearbyForSurvey(o.getX() - at.getX(), o.getY() - at.getY(), o.getZ() - at.getZ(), radius)) continue;
                Map<String, Object> e = new LinkedHashMap<>();
                e.put("name", other.getName());
                e.put("kind", net.citizensnpcs.api.CitizensAPI.getNPCRegistry().isNPC(other) ? "agent" : "player");
                e.put("x", o.getBlockX()); e.put("y", o.getBlockY()); e.put("z", o.getBlockZ());
                e.put("distance", Math.round(o.distance(at) * 10.0) / 10.0);
                nearby.add(e);
            }
        } catch (RuntimeException ignored) {}
        out.put("players", nearby);
        return out;
    }

    /** Players/agents within the survey radius plus 8 horizontally and 16 vertically. */
    static boolean nearbyForSurvey(double dx, double dy, double dz, int radius) {
        double r = radius + 8;
        return Math.abs(dx) <= r && Math.abs(dz) <= r && Math.abs(dy) <= 16;
    }

    static Map<String, Object> survey(AgentPathfinder.Grid grid, int ax, int ay, int az,
                                      int radius, int ymin, int ymax) {
        int minX = ax - radius, maxX = ax + radius, minZ = az - radius, maxZ = az + radius;
        int startY = AgentPerception.floodStartY(grid, ax, ay, az, ymin, ymax);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("action", "survey");
        out.put("position", List.of(ax, startY == Integer.MIN_VALUE ? ay : startY, az));
        out.put("radius", radius);
        if (startY != Integer.MIN_VALUE && startY != ay) out.put("feetY", startY);

        Set<Long> room = new LinkedHashSet<>();
        List<Map<String, Object>> exits = new ArrayList<>();
        List<Map<String, Object>> unknown = new ArrayList<>();
        Set<String> seenExit = new LinkedHashSet<>();
        boolean[] hitBound = {false}, hitUnloaded = {false}, truncated = {false};
        if (startY != Integer.MIN_VALUE) {
            floodRoom(grid, ax, startY, az, ymin, ymax, minX, maxX, minZ, maxZ,
                    room, exits, unknown, seenExit, hitBound, hitUnloaded, truncated);
        }

        int floorY = startY == Integer.MIN_VALUE ? ay : startY;
        int ceilingY = Integer.MIN_VALUE;
        int roofed = 0, openSky = 0, caveAir = 0;
        int minRX = Integer.MAX_VALUE, maxRX = Integer.MIN_VALUE;
        int minRZ = Integer.MAX_VALUE, maxRZ = Integer.MIN_VALUE;
        Set<String> walls = new LinkedHashSet<>();
        Map<Long, int[]> columns = new LinkedHashMap<>();
        for (long packed : room) {
            int[] c = unpack(packed);
            int x = c[0], y = c[1], z = c[2];
            if (y < floorY) floorY = y;
            if (x < minRX) minRX = x;
            if (x > maxRX) maxRX = x;
            if (z < minRZ) minRZ = z;
            if (z > maxRZ) maxRZ = z;
            if (AgentPathfinder.bare(grid.name(x, y, z)).equals("cave_air")
                    || AgentPathfinder.bare(grid.name(x, y + 1, z)).equals("cave_air"))
                caveAir++;
            int ceil = ceilingAt(grid, x, y, z, ymax + 4);
            if (ceil != Integer.MIN_VALUE) {
                roofed++;
                if (ceilingY == Integer.MIN_VALUE || ceil < ceilingY) ceilingY = ceil;
            } else openSky++;
            long xz = packXZ(x, z);
            int[] col = columns.get(xz);
            if (col == null || y < col[0]) columns.put(xz, new int[]{y, ceil});
            for (int[] d : CARD) {
                int nx = x + d[0], nz = z + d[1];
                if (!grid.loaded(nx, y, nz)) continue;
                String n = AgentPathfinder.bare(grid.name(nx, y, nz));
                if (n.isEmpty() || airName(n) || AgentPathfinder.doorWalkName(n)) continue;
                if (!grid.open(nx, y, nz)) walls.add(wallName(n));
            }
        }
        if (room.isEmpty()) {
            minRX = ax; maxRX = ax; minRZ = az; maxRZ = az;
        }

        String environment = classify(room.size(), roofed, openSky, caveAir,
                minRX, maxRX, minRZ, maxRZ, startY);
        classifyExits(grid, room, exits, ymin, ymax, minX, maxX, minZ, maxZ);
        List<Map<String, Object>> objects = objectsIn(grid, room, minX, maxX, ymin, ymax, minZ, maxZ);

        Map<String, Object> roomOut = new LinkedHashMap<>();
        roomOut.put("floorY", floorY);
        if (ceilingY != Integer.MIN_VALUE) roomOut.put("ceilingY", ceilingY);
        roomOut.put("walls", new ArrayList<>(walls));
        Map<String, Object> bounds = new LinkedHashMap<>();
        bounds.put("minX", minRX); bounds.put("maxX", maxRX);
        bounds.put("minY", floorY);
        bounds.put("maxY", ceilingY == Integer.MIN_VALUE ? (room.isEmpty() ? ay : floorY + 1) : ceilingY);
        bounds.put("minZ", minRZ); bounds.put("maxZ", maxRZ);
        roomOut.put("bounds", bounds);
        roomOut.put("cells", room.size());
        roomOut.put("footprint", footprint(grid, room, exits, ax, az, minRX, maxRX, minRZ, maxRZ, floorY));
        Map<String, Object> head = headroom(columns, minRX, maxRX, minRZ, maxRZ, floorY);
        roomOut.put("headroom", head);
        @SuppressWarnings("unchecked")
        List<String> warnings = (List<String>) head.get("warnings");
        if (warnings != null && !warnings.isEmpty()) roomOut.put("warnings", warnings);
        roomOut.put("exits", exits);
        roomOut.put("objects", objects);

        out.put("environment", environment);
        out.put("room", roomOut);
        out.put("knownExitCount", exits.size());
        if (!unknown.isEmpty()) out.put("unknown", unknown);
        out.put("complete", !hitBound[0] && !hitUnloaded[0] && !truncated[0] && startY != Integer.MIN_VALUE);
        out.put("truncated", truncated[0]);
        out.put("unloaded", hitUnloaded[0]);
        return out;
    }

    private static void floodRoom(AgentPathfinder.Grid grid, int ax, int ay, int az,
                                  int ymin, int ymax, int minX, int maxX, int minZ, int maxZ,
                                  Set<Long> room, List<Map<String, Object>> exits,
                                  List<Map<String, Object>> unknown, Set<String> seenExit,
                                  boolean[] hitBound, boolean[] hitUnloaded, boolean[] truncated) {
        ArrayDeque<int[]> q = new ArrayDeque<>();
        if (ay < ymin || ay > ymax || !grid.usable(ax, ay, az) || !grid.standable(ax, ay, az)) return;
        room.add(AgentPathfinder.pack(ax, ay, az));
        q.add(new int[]{ax, ay, az});
        while (!q.isEmpty()) {
            int[] c = q.removeFirst();
            int x = c[0], y = c[1], z = c[2];
            for (int[] d : CARD) {
                int nx = x + d[0], nz = z + d[1];
                if (nx < minX || nx > maxX || nz < minZ || nz > maxZ) {
                    hitBound[0] = true;
                    addUnknown(unknown, seenExit, x, y, z, "bound");
                    continue;
                }
                if (!grid.loaded(nx, y, nz)) {
                    hitUnloaded[0] = true;
                    addUnknown(unknown, seenExit, nx, y, nz, "unloaded");
                }
            }
            AgentPathfinder.walkSteps(x, y, z, grid, false, minX, maxX, minZ, maxZ, (nx, ny, nz, move) -> {
                if (ny < ymin || ny > ymax) return;
                long key = AgentPathfinder.pack(nx, ny, nz);
                if (room.contains(key)) return;
                String destName = AgentPathfinder.bare(grid.name(nx, ny, nz));
                String hereName = AgentPathfinder.bare(grid.name(x, y, z));
                boolean destDoor = AgentPathfinder.doorWalkName(destName);
                boolean hereDoor = AgentPathfinder.doorWalkName(hereName);
                if (destDoor || hereDoor) {
                    int dx = destDoor ? nx : x, dy = destDoor ? ny : y, dz = destDoor ? nz : z;
                    addExit(exits, seenExit, doorType(destDoor ? destName : hereName), dx, dy, dz, true);
                    return;
                }
                if (narrowOpening(grid, x, y, z, nx, ny, nz)) {
                    addExit(exits, seenExit, "opening", nx, ny, nz, true);
                    return;
                }
                if (leavingEnclosure(grid, x, y, z, nx, ny, nz, ymax)) {
                    addExit(exits, seenExit, "opening", nx, ny, nz, true);
                    return;
                }
                if (room.size() >= CELL_BUDGET) {
                    truncated[0] = true;
                    return;
                }
                room.add(key);
                q.add(new int[]{nx, ny, nz});
            });
        }
    }

    private static void classifyExits(AgentPathfinder.Grid grid, Set<Long> room,
                                      List<Map<String, Object>> exits, int ymin, int ymax,
                                      int minX, int maxX, int minZ, int maxZ) {
        for (Map<String, Object> exit : exits) {
            @SuppressWarnings("unchecked")
            List<Number> pos = (List<Number>) exit.get("position");
            int x = pos.get(0).intValue(), y = pos.get(1).intValue(), z = pos.get(2).intValue();
            Set<Long> beyond = new LinkedHashSet<>();
            AgentPathfinder.walkSteps(x, y, z, grid, false, minX, maxX, minZ, maxZ, (nx, ny, nz, move) -> {
                if (ny < ymin || ny > ymax) return;
                if (room.contains(AgentPathfinder.pack(nx, ny, nz))) return;
                if (AgentPathfinder.doorWalkName(grid.name(nx, ny, nz)) && (nx != x || nz != z)) return;
                beyond.add(AgentPathfinder.pack(nx, ny, nz));
            });
            if (beyond.isEmpty()) {
                for (int[] d : CARD) {
                    int nx = x + d[0], nz = z + d[1];
                    if (room.contains(AgentPathfinder.pack(nx, y, nz))) continue;
                    if (grid.loaded(nx, y, nz) && grid.standable(nx, y, nz))
                        beyond.add(AgentPathfinder.pack(nx, y, nz));
                }
            }
            exit.put("destination", classifyBeyond(grid, room, beyond, ymin, ymax, minX, maxX, minZ, maxZ));
        }
    }

    private static String classifyBeyond(AgentPathfinder.Grid grid, Set<Long> room, Set<Long> seeds,
                                         int ymin, int ymax, int minX, int maxX, int minZ, int maxZ) {
        if (seeds.isEmpty()) return "unknown";
        Set<Long> dest = new LinkedHashSet<>();
        ArrayDeque<int[]> q = new ArrayDeque<>();
        for (long p : seeds) {
            if (dest.size() >= DEST_BUDGET) break;
            dest.add(p);
            q.add(unpack(p));
        }
        boolean hitUnknown = false;
        while (!q.isEmpty() && dest.size() < DEST_BUDGET) {
            int[] c = q.removeFirst();
            int x = c[0], y = c[1], z = c[2];
            AgentPathfinder.walkSteps(x, y, z, grid, false, minX, maxX, minZ, maxZ, (nx, ny, nz, move) -> {
                if (ny < ymin || ny > ymax) return;
                long key = AgentPathfinder.pack(nx, ny, nz);
                if (room.contains(key) || dest.contains(key)) return;
                if (AgentPathfinder.doorWalkName(grid.name(nx, ny, nz))) return;
                dest.add(key);
                q.add(new int[]{nx, ny, nz});
            });
            for (int[] d : CARD) {
                int nx = x + d[0], nz = z + d[1];
                if (nx < minX || nx > maxX || nz < minZ || nz > maxZ || !grid.loaded(nx, y, nz))
                    hitUnknown = true;
            }
        }
        int roofed = 0, openSky = 0, caveAir = 0;
        int minX2 = Integer.MAX_VALUE, maxX2 = Integer.MIN_VALUE;
        int minZ2 = Integer.MAX_VALUE, maxZ2 = Integer.MIN_VALUE;
        for (long p : dest) {
            int[] c = unpack(p);
            if (c[0] < minX2) minX2 = c[0];
            if (c[0] > maxX2) maxX2 = c[0];
            if (c[2] < minZ2) minZ2 = c[2];
            if (c[2] > maxZ2) maxZ2 = c[2];
            if (AgentPathfinder.bare(grid.name(c[0], c[1], c[2])).equals("cave_air")) caveAir++;
            if (ceilingAt(grid, c[0], c[1], c[2], ymax + 4) != Integer.MIN_VALUE) roofed++;
            else openSky++;
        }
        String kind = classify(dest.size(), roofed, openSky, caveAir, minX2, maxX2, minZ2, maxZ2, 0);
        if (kind.equals("unknown") && hitUnknown) return "unknown";
        if (kind.equals("enclosed_room")) return "connected_room";
        return kind;
    }

    static String classify(int cells, int roofed, int openSky, int caveAir,
                           int minX, int maxX, int minZ, int maxZ, int startY) {
        if (cells == 0 && startY == Integer.MIN_VALUE) return "unknown";
        if (cells == 0) return "unknown";
        int w = Math.max(1, maxX - minX + 1), d = Math.max(1, maxZ - minZ + 1);
        int minSide = Math.min(w, d), maxSide = Math.max(w, d);
        boolean enclosed = roofed * 10 >= cells * 7;
        if (!enclosed) return "outdoors";
        if (minSide <= 3 && maxSide >= 5) return "passage";
        if (caveAir > 0 || (maxSide >= 8 && cells * 2 < w * d)) return "cave";
        return "enclosed_room";
    }

    private static List<Map<String, Object>> objectsIn(AgentPathfinder.Grid grid, Set<Long> room,
                                                       int minX, int maxX, int ymin, int ymax, int minZ, int maxZ) {
        List<Map<String, Object>> objects = new ArrayList<>();
        Set<String> seen = new LinkedHashSet<>();
        for (int z = minZ; z <= maxZ; z++) for (int x = minX; x <= maxX; x++) for (int y = ymin; y <= ymax; y++) {
            if (!grid.loaded(x, y, z)) continue;
            String n = AgentPathfinder.bare(grid.name(x, y, z));
            if (!AgentPathfinder.workstationName(n) && !AgentRespawn.isBed(n) && !AgentSigns.isSign(n)) continue;
            if (!inOrBeside(room, x, y, z)) continue;
            String id = x + "," + y + "," + z;
            if (!seen.add(id)) continue;
            Map<String, Object> obj = new LinkedHashMap<>();
            obj.put("type", objectType(n));
            obj.put("position", List.of(x, y, z));
            objects.add(obj);
        }
        return objects;
    }

    static boolean inOrBeside(Set<Long> room, int x, int y, int z) {
        if (room.contains(AgentPathfinder.pack(x, y, z))) return true;
        for (int dy = -1; dy <= 1; dy++) for (int[] d : CARD) {
            if (room.contains(AgentPathfinder.pack(x + d[0], y + dy, z + d[1]))) return true;
        }
        return false;
    }

    /** 1-wide 1x2 gap in an otherwise solid wall (same walk body as goto). */
    static boolean narrowOpening(AgentPathfinder.Grid grid, int x, int y, int z, int nx, int ny, int nz) {
        int dx = nx - x, dz = nz - z;
        if (Math.abs(dx) + Math.abs(dz) != 1 || Math.abs(ny - y) > 1) return false;
        int px = -dz, pz = dx;
        boolean hereWalls = solid(grid, x + px, y, z + pz) && solid(grid, x - px, y, z - pz);
        boolean destWalls = solid(grid, nx + px, ny, nz + pz) && solid(grid, nx - px, ny, nz - pz);
        // Walking along a 1-wide hall is not an exit. Entering or leaving that
        // gap from a wider space is the doorway.
        return hereWalls != destWalls;
    }

    private static boolean leavingEnclosure(AgentPathfinder.Grid grid, int x, int y, int z,
                                            int nx, int ny, int nz, int ymax) {
        int hereCeil = ceilingAt(grid, x, y, z, ymax + 4);
        if (hereCeil == Integer.MIN_VALUE) return false;
        int destCeil = ceilingAt(grid, nx, ny, nz, ymax + 4);
        return destCeil == Integer.MIN_VALUE;
    }

    /**
     * Per-column clearance. Headroom is air blocks from stand Y up to (not
     * including) the ceiling, so a 65-stand / 68-ceiling room is 3.
     */
    static Map<String, Object> headroom(Map<Long, int[]> columns,
                                        int minX, int maxX, int minZ, int maxZ, int floorY) {
        Map<String, Object> out = new LinkedHashMap<>();
        if (minX > maxX || minZ > maxZ) {
            out.put("min", 0); out.put("max", 0); out.put("warnings", List.of());
            return out;
        }
        int w = maxX - minX + 1, d = maxZ - minZ + 1;
        StringBuilder clear = new StringBuilder(d * (w + 1));
        StringBuilder ceils = new StringBuilder(d * (w + 1));
        StringBuilder floors = new StringBuilder(d * (w + 1));
        int minH = Integer.MAX_VALUE, maxH = Integer.MIN_VALUE;
        int minStand = Integer.MAX_VALUE, maxStand = Integer.MIN_VALUE;
        int minCeil = Integer.MAX_VALUE, maxCeil = Integer.MIN_VALUE;
        int[] counts = new int[16];
        for (int row = 0; row < d; row++) {
            if (row > 0) { clear.append('\n'); ceils.append('\n'); floors.append('\n'); }
            int z = minZ + row;
            for (int col = 0; col < w; col++) {
                int x = minX + col;
                int[] colY = columns.get(packXZ(x, z));
                if (colY == null) {
                    clear.append('.'); ceils.append('.'); floors.append('.');
                    continue;
                }
                int stand = colY[0], ceil = colY[1];
                if (stand < minStand) minStand = stand;
                if (stand > maxStand) maxStand = stand;
                floors.append(AgentPerception.heightChar(stand - floorY));
                if (ceil == Integer.MIN_VALUE) {
                    clear.append('*'); ceils.append('*');
                    continue;
                }
                int air = Math.max(0, ceil - stand);
                if (air < minH) minH = air;
                if (air > maxH) maxH = air;
                if (ceil < minCeil) minCeil = ceil;
                if (ceil > maxCeil) maxCeil = ceil;
                if (air < counts.length) counts[air]++;
                clear.append(air <= 9 ? (char) ('0' + air) : AgentPerception.heightChar(air));
                ceils.append(AgentPerception.heightChar(ceil - floorY));
            }
        }
        int mode = 0, modeN = 0;
        for (int h = 0; h < counts.length; h++) if (counts[h] > modeN) { modeN = counts[h]; mode = h; }
        List<Map<String, Object>> shortCells = new ArrayList<>();
        int target = maxH > 0 ? maxH : TYPICAL_HEADROOM;
        if (minH < maxH) {
            for (int row = 0; row < d && shortCells.size() < 16; row++) {
                int z = minZ + row;
                for (int col = 0; col < w && shortCells.size() < 16; col++) {
                    int x = minX + col;
                    int[] colY = columns.get(packXZ(x, z));
                    if (colY == null || colY[1] == Integer.MIN_VALUE) continue;
                    int air = colY[1] - colY[0];
                    if (air >= target) continue;
                    Map<String, Object> cell = new LinkedHashMap<>();
                    cell.put("position", List.of(x, colY[0], z));
                    cell.put("floorY", colY[0]);
                    cell.put("ceilingY", colY[1]);
                    cell.put("headroom", air);
                    cell.put("shortBy", target - air);
                    shortCells.add(cell);
                }
            }
        }
        List<String> warnings = new ArrayList<>();
        boolean unevenFloor = maxStand != Integer.MIN_VALUE && maxStand > minStand;
        boolean unevenCeiling = maxCeil != Integer.MIN_VALUE && maxCeil > minCeil;
        if (unevenFloor)
            warnings.add("uneven_floor: stand Y " + minStand + ".." + maxStand);
        if (unevenCeiling)
            warnings.add("uneven_ceiling: ceiling Y " + minCeil + ".." + maxCeil
                    + (shortCells.isEmpty() ? "" : "; " + shortCells.size() + " cell"
                    + (shortCells.size() == 1 ? "" : "s") + " short"));
        if (minH != Integer.MAX_VALUE && maxH != Integer.MIN_VALUE && minH < maxH)
            warnings.add("short_headroom: min " + minH + ", max " + maxH
                    + " (player needs " + WALK_HEADROOM + "; typical room is " + TYPICAL_HEADROOM + ")");
        if (minH < maxH && maxH > 0)
            warnings.add("incomplete: " + shortCells.size() + " cell"
                    + (shortCells.size() == 1 ? "" : "s") + " "
                    + (maxH - minH) + " block short of the rest of the room");
        if (minH != Integer.MAX_VALUE && minH < WALK_HEADROOM)
            warnings.add("low_headroom: minimum clearance " + minH + " (cannot walk)");

        out.put("min", minH == Integer.MAX_VALUE ? 0 : minH);
        out.put("max", maxH == Integer.MIN_VALUE ? 0 : maxH);
        if (modeN > 0) out.put("mode", mode);
        out.put("unevenFloor", unevenFloor);
        out.put("unevenCeiling", unevenCeiling);
        out.put("uniform", !unevenFloor && !unevenCeiling && (minH == maxH || minH == Integer.MAX_VALUE));
        out.put("originX", minX);
        out.put("originZ", minZ);
        out.put("grid", clear.toString());
        out.put("ceilings", ceils.toString());
        out.put("floors", floors.toString());
        out.put("legend", HEADROOM_LEGEND);
        out.put("heightScale", AgentPerception.HEIGHT_SCALE);
        if (!shortCells.isEmpty()) out.put("short", shortCells);
        out.put("warnings", warnings);
        return out;
    }

    static long packXZ(int x, int z) {
        return (((long) x) << 32) ^ (z & 0xffffffffL);
    }

    static int ceilingAt(AgentPathfinder.Grid grid, int x, int y, int z, int hi) {
        for (int cy = y + 2; cy <= hi; cy++) {
            if (!grid.loaded(x, cy, z)) return Integer.MIN_VALUE;
            String n = AgentPathfinder.bare(grid.name(x, cy, z));
            if (airName(n) || AgentPathfinder.doorWalkName(n) || AgentPathfinder.walkThroughName(n)) continue;
            if (!grid.open(x, cy, z)) return cy;
        }
        return Integer.MIN_VALUE;
    }

    static boolean solid(AgentPathfinder.Grid grid, int x, int y, int z) {
        if (!grid.loaded(x, y, z)) return false;
        String n = AgentPathfinder.bare(grid.name(x, y, z));
        if (airName(n) || AgentPathfinder.doorWalkName(n) || AgentPathfinder.walkThroughName(n)) return false;
        if (AgentRespawn.isBed(n) || AgentSigns.isSign(n)) return false;
        return !grid.open(x, y, z);
    }

    private static boolean airName(String n) {
        return n.equals("air") || n.equals("cave_air") || n.equals("void_air");
    }

    static String doorType(String raw) {
        String n = AgentPathfinder.bare(raw);
        if (n.endsWith("_fence_gate") || n.equals("fence_gate")) return "gate";
        return "door";
    }

    static String objectType(String raw) {
        String n = AgentPathfinder.bare(raw);
        if (n.equals("trapped_chest") || n.equals("ender_chest") || n.endsWith("_chest")) return "chest";
        if (n.contains("shulker_box")) return "shulker_box";
        if (n.endsWith("_anvil") || n.equals("anvil")) return "anvil";
        if (AgentRespawn.isBed(n)) return "bed";
        if (AgentSigns.isSign(n)) return "sign";
        return n;
    }

    static String wallName(String raw) {
        String n = AgentPathfinder.bare(raw);
        if (n.endsWith("_log") || n.equals("log")) return "log";
        if (n.endsWith("_planks")) return "planks";
        return n;
    }

    private static void addExit(List<Map<String, Object>> exits, Set<String> seen,
                                String type, int x, int y, int z, boolean reachable) {
        String id = type + ":" + x + "," + y + "," + z;
        if (!seen.add(id)) return;
        Map<String, Object> exit = new LinkedHashMap<>();
        exit.put("type", type);
        exit.put("position", List.of(x, y, z));
        exit.put("reachable", reachable);
        exits.add(exit);
    }

    private static void addUnknown(List<Map<String, Object>> unknown, Set<String> seen,
                                   int x, int y, int z, String reason) {
        String id = "unknown:" + x + "," + z + ":" + reason;
        if (!seen.add(id)) return;
        Map<String, Object> u = new LinkedHashMap<>();
        u.put("position", List.of(x, y, z));
        u.put("reason", reason);
        unknown.add(u);
    }

    private static Map<String, Object> footprint(AgentPathfinder.Grid grid, Set<Long> room,
                                                 List<Map<String, Object>> exits,
                                                 int ax, int az, int minX, int maxX, int minZ, int maxZ, int floorY) {
        Map<String, Object> fp = new LinkedHashMap<>();
        if (room.isEmpty()) {
            fp.put("originX", ax); fp.put("originZ", az); fp.put("grid", "@");
            return fp;
        }
        int w = maxX - minX + 1, d = maxZ - minZ + 1;
        Set<Long> doorXZ = new LinkedHashSet<>();
        for (Map<String, Object> exit : exits) {
            @SuppressWarnings("unchecked")
            List<Number> pos = (List<Number>) exit.get("position");
            doorXZ.add((((long) pos.get(0).intValue()) << 32) ^ (pos.get(2).intValue() & 0xffffffffL));
        }
        StringBuilder gridOut = new StringBuilder(d * (w + 1));
        for (int row = 0; row < d; row++) {
            if (row > 0) gridOut.append('\n');
            int z = minZ + row;
            for (int col = 0; col < w; col++) {
                int x = minX + col;
                if (x == ax && z == az) { gridOut.append('@'); continue; }
                long xz = (((long) x) << 32) ^ (z & 0xffffffffL);
                if (doorXZ.contains(xz)) { gridOut.append('D'); continue; }
                if (reachedXZ(room, x, z)) { gridOut.append('+'); continue; }
                if (solid(grid, x, floorY, z) || solid(grid, x, floorY + 1, z)) { gridOut.append('#'); continue; }
                gridOut.append('.');
            }
        }
        fp.put("originX", minX);
        fp.put("originZ", minZ);
        fp.put("legend", "@ agent, + room, D exit, # wall, . other");
        fp.put("grid", gridOut.toString());
        return fp;
    }

    private static boolean reachedXZ(Set<Long> room, int x, int z) {
        for (long p : room) {
            int[] c = unpack(p);
            if (c[0] == x && c[2] == z) return true;
        }
        return false;
    }

    static int[] unpack(long packed) {
        int y = (int) (packed & 0xFFF) - 2048;
        int z = (int) ((packed >>> 12) & 0x3FFFFFF) - 30_000_000;
        int x = (int) (packed >>> 38) - 30_000_000;
        return new int[]{x, y, z};
    }

    private AgentSurvey() {}
}
