package dev.agentsmc;

import java.util.List;
import java.util.Map;

import com.google.gson.JsonObject;

/** Offline heightmap / walkmap: compact grids, pathfinder-matching reachability. */
public final class PerceptionTest {
    public static void main(String[] args) {
        check(AgentActionCatalog.advertised("heightmap") && AgentActionCatalog.advertised("walkmap"), "advertised");
        check(AgentPerception.surfaceKind("grass_block") == 'g' && AgentPerception.surfaceKind("STONE") == 's'
                && AgentPerception.surfaceKind("sand") == 'd' && AgentPerception.surfaceKind("water") == 'w'
                && AgentPerception.surfaceKind("lava") == 'l' && AgentPerception.surfaceKind("oak_leaves") == 't'
                && AgentPerception.surfaceKind("powder_snow") == 'n' && AgentPerception.surfaceKind("oak_log") == 't'
                && AgentPerception.surfaceKind("chest") == 'o', "surface kinds");
        check(AgentPerception.heightChar(0) == '0' && AgentPerception.heightChar(2) == '2'
                && AgentPerception.heightChar(10) == 'a' && AgentPerception.heightChar(-1) == 'A'
                && AgentPerception.heightChar(-10) == 'J', "height chars");

        PathfinderTest.MapGrid flat = new PathfinderTest.MapGrid(-64, 320);
        flat.fillFloor(-20, 20, -20, 20, 64, "grass_block");
        Map<String, Object> hm = AgentPerception.heightmap(flat, 0, 0, 65, 4, 0, 0, 80, 50);
        String surface = (String) hm.get("surface");
        String heights = (String) hm.get("heights");
        String[] rows = surface.split("\n");
        check(rows.length == 9 && rows[0].length() == 9, "heightmap size: " + rows.length + "x" + (rows.length == 0 ? 0 : rows[0].length()));
        check(((Number) hm.get("originX")).intValue() == -4 && ((Number) hm.get("originZ")).intValue() == -4, "origin");
        check(((Number) hm.get("yBase")).intValue() == 65, "yBase");
        check(rows[4].charAt(4) == '*', "agent marked: " + rows[4]);
        check(rows[0].chars().filter(c -> c == 'g').count() == 9, "grass row: " + rows[0]);
        check(heights.split("\n")[4].charAt(4) == 'A', "surface is 64 vs yBase 65 → -1: " + heights.split("\n")[4]);
        check(Boolean.TRUE.equals(hm.get("complete")), "flat complete");

        PathfinderTest.MapGrid mixed = new PathfinderTest.MapGrid(-64, 320);
        mixed.fillFloor(-8, 8, -8, 8, 64, "stone");
        mixed.set(2, 66, 0, "sand");
        mixed.set(3, 64, 0, "water"); mixed.set(3, 65, 0, "water");
        mixed.set(4, 65, 0, "lava");
        mixed.set(5, 66, 0, "oak_leaves");
        mixed.set(6, 65, 0, "powder_snow");
        Map<String, Object> kinds = AgentPerception.heightmap(mixed, 0, 0, 65, 8, 0, 0, 80, 50);
        String mid = ((String) kinds.get("surface")).split("\n")[8];
        check(mid.charAt(8) == '*', "mixed agent: " + mid);
        check(mid.charAt(10) == 'd', "sand: " + mid);
        check(mid.charAt(11) == 'w', "water: " + mid);
        check(mid.charAt(12) == 'l', "lava: " + mid);
        check(mid.charAt(13) == 't', "leaves: " + mid);
        check(mid.charAt(14) == 'n', "snow: " + mid);
        check(((String) kinds.get("heights")).split("\n")[8].charAt(10) == '1', "sand +1");

        PathfinderTest.MapGrid hole = new PathfinderTest.MapGrid(-64, 320);
        hole.fillFloor(-4, 4, -4, 4, 64, "stone");
        hole.unloaded.add("0,0");
        Map<String, Object> miss = AgentPerception.heightmap(hole, 0, 0, 65, 2, 0, 0, 80, 50);
        check(Boolean.FALSE.equals(miss.get("complete")) && ((Number) miss.get("unloaded")).intValue() > 0, "unloaded marked");

        PathfinderTest.MapGrid walk = new PathfinderTest.MapGrid(-64, 320);
        walk.fillFloor(-16, 16, -16, 16, 64, "stone");
        Map<String, Object> wm = AgentPerception.walkmap(walk, 0, 65, 0, 3, 61, 69, false);
        String[] wrows = ((String) wm.get("grid")).split("\n");
        check(wrows.length == 7 && wrows[0].length() == 7, "walkmap size");
        check(wrows[3].charAt(3) == '@', "walk agent: " + wrows[3]);
        int plus = 0;
        for (String r : wrows) for (char c : r.toCharArray()) if (c == '+') plus++;
        check(plus == 48 && ((Number) wm.get("reachable")).intValue() == 49, "flat reachable: plus=" + plus + " " + wm.get("reachable"));
        check(((Number) wm.get("reachableDig")).intValue() == 0, "no dig");

        PathfinderTest.MapGrid cliff = new PathfinderTest.MapGrid(-64, 320);
        cliff.fillFloor(-4, 1, -4, 4, 70, "stone");
        cliff.fillFloor(3, 8, -4, 4, 65, "stone");
        Map<String, Object> cm = AgentPerception.walkmap(cliff, 0, 71, 0, 6, 67, 75, false);
        String cMid = ((String) cm.get("grid")).split("\n")[6];
        check(cMid.charAt(6) == '@', "cliff agent: " + cMid);
        check(cMid.charAt(8) == 'v', "5-block drop is cliff: " + cMid);
        AgentPathfinder.Options noDig = new AgentPathfinder.Options();
        noDig.dig = false; noDig.build = false;
        AgentPathfinder.Result fall = AgentPathfinder.plan(0, 71, 0, 6, 66, 0, cliff, noDig);
        check(fall.fail != null, "pathfinder agrees 5-block drop is not a walk: " + fall.fail);

        PathfinderTest.MapGrid hazard = new PathfinderTest.MapGrid(-64, 320);
        hazard.fillFloor(-6, 6, -6, 6, 64, "stone");
        for (int z = -2; z <= 2; z++) {
            hazard.set(3, 65, z, "powder_snow");
            hazard.set(3, 66, z, "powder_snow");
        }
        Map<String, Object> hz = AgentPerception.walkmap(hazard, 0, 65, 0, 4, 61, 69, false);
        String hMid = ((String) hz.get("grid")).split("\n")[4];
        check(hMid.charAt(7) == '!', "powder snow hazard: " + hMid);

        PathfinderTest.MapGrid water = new PathfinderTest.MapGrid(-64, 320);
        water.fillFloor(-6, 6, -6, 6, 64, "stone");
        for (int z = -5; z <= 5; z++) {
            water.set(2, 65, z, "stone");
            water.set(2, 66, z, "stone");
        }
        for (int z = -1; z <= 1; z++) for (int y = 64; y <= 65; y++) water.set(4, y, z, "water");
        Map<String, Object> wt = AgentPerception.walkmap(water, 0, 65, 0, 5, 61, 69, false);
        String wMid = ((String) wt.get("grid")).split("\n")[5];
        check(wMid.charAt(5) == '@', "water agent: " + wMid);
        check(wMid.charAt(9) == '~', "isolated water behind wall: " + wMid);

        PathfinderTest.MapGrid wall = new PathfinderTest.MapGrid(-64, 320);
        wall.fillFloor(-8, 8, -8, 8, 64, "stone");
        for (int z = -3; z <= 3; z++) for (int y = 61; y <= 70; y++) wall.set(2, y, z, "stone");
        Map<String, Object> blocked = AgentPerception.walkmap(wall, 0, 65, 0, 4, 61, 69, false);
        String bMid = ((String) blocked.get("grid")).split("\n")[4];
        check(bMid.charAt(6) == '#', "full stone pillar blocked: " + bMid);

        PathfinderTest.MapGrid thin = new PathfinderTest.MapGrid(-64, 320);
        thin.fillFloor(-8, 8, -8, 8, 64, "stone");
        for (int z = -3; z <= 3; z++) {
            thin.set(2, 65, z, "stone");
            thin.set(2, 66, z, "stone");
        }
        Map<String, Object> mined = AgentPerception.walkmap(thin, 0, 65, 0, 4, 61, 69, true);
        String mMid = ((String) mined.get("grid")).split("\n")[4];
        check(mMid.indexOf('*') >= 0, "dig reaches 1x2 stone: " + mMid);
        check(((Number) mined.get("reachableDig")).intValue() > 0, "reachableDig > 0");

        PathfinderTest.MapGrid agree = new PathfinderTest.MapGrid(-64, 320);
        agree.fillFloor(-8, 8, -8, 8, 64, "stone");
        Map<String, Object> ag = AgentPerception.walkmap(agree, 0, 65, 0, 5, 61, 69, false);
        String aMid = ((String) ag.get("grid")).split("\n")[5];
        AgentPathfinder.Options walkOnly = new AgentPathfinder.Options();
        walkOnly.dig = false; walkOnly.build = false;
        AgentPathfinder.Result near = AgentPathfinder.plan(0, 65, 0, 3, 65, 0, agree, walkOnly);
        check(near.fail == null && aMid.charAt(8) == '+', "pathfinder-reachable cell is +: " + aMid + " fail=" + near.fail);

        JsonObject bad = new JsonObject();
        try {
            AgentPerception.optionalInt(bad, "radius", 16, 1, 32);
            bad.addProperty("radius", 99);
            AgentPerception.optionalInt(bad, "radius", 16, 1, 32);
            throw new AssertionError("radius 99 accepted");
        } catch (IllegalArgumentException expected) {
            check(expected.getMessage().contains("1..32"), "radius error: " + expected.getMessage());
        }

        JsonObject height = new JsonObject();
        height.addProperty("action", "heightmap");
        height.addProperty("radius", 8.0);
        height.addProperty("x", "100");
        height.addProperty("z", 200);
        JsonObject heightNorm = AgentActionProgram.normalize(height, "world");
        check(heightNorm.has("world"), "documented x/z injects world");
        heightNorm.addProperty("agent", "helper");
        AgentSurvivalActions.fields(heightNorm, java.util.List.of("radius", "x", "z"), "action", "agent");
        check(AgentPerception.optionalInt(heightNorm, "radius", 16, 1, 32) == 8, "float radius");
        check(AgentPerception.optionalInt(heightNorm, "x", 0, Integer.MIN_VALUE / 4, Integer.MAX_VALUE / 4) == 100, "string x");
        check(AgentPerception.optionalInt(heightNorm, "z", 0, Integer.MIN_VALUE / 4, Integer.MAX_VALUE / 4) == 200, "int z");

        JsonObject walkPayload = new JsonObject();
        walkPayload.addProperty("radius", "12");
        walkPayload.addProperty("ymin", 61.0);
        walkPayload.addProperty("ymax", "69");
        walkPayload.addProperty("dig", "true");
        JsonObject walkIn = BridgeProtocol.action("walkmap", walkPayload, "world");
        walkIn.addProperty("agent", "helper");
        AgentSurvivalActions.fields(walkIn, java.util.List.of("radius", "ymin", "ymax", "dig"), "action", "agent");
        check(AgentPerception.optionalInt(walkIn, "radius", 12, 1, 24) == 12, "walk radius string");
        check(AgentPerception.optionalInt(walkIn, "ymin", 0, -128, 320) == 61, "float ymin");
        check(AgentPerception.optionalInt(walkIn, "ymax", 0, -128, 320) == 69, "string ymax");
        check(AgentPerception.optionalFlag(walkIn, "dig", false), "string dig true");

        // fb-3: walkmap on open grass. Bukkit names are AIR/GRASS_BLOCK
        // (uppercase). Flood used to start at head-height air and mark the plains all-#.
        AgentPerception.NameGrid bukkitGrass = grassColumn(7, 82, -9, 8, 80, 88, true);
        check(bukkitGrass.name(7, 84, -9).equals("air") && bukkitGrass.name(7, 82, -9).equals("grass_block"),
                "NameGrid lowercases Bukkit AIR/GRASS_BLOCK");
        check(bukkitGrass.open(7, 84, -9) && bukkitGrass.open(7, 83, -9) && bukkitGrass.floor(7, 82, -9),
                "uppercase AIR is open, GRASS_BLOCK is floor");
        check(bukkitGrass.standable(7, 83, -9) && !bukkitGrass.standable(7, 84, -9),
                "stand on grass is feet Y 83, not head 84");
        Map<String, Object> live = AgentPerception.walkmap(bukkitGrass, 7, 84, -9, 8, 80, 88, false);
        String[] liveRows = ((String) live.get("grid")).split("\n");
        int hashes = 0, reached = 0;
        for (String r : liveRows) for (char c : r.toCharArray()) { if (c == '#') hashes++; if (c == '+') reached++; }
        check(reached > 50 && ((Number) live.get("reachable")).intValue() > 50,
                "open grass walkmap reachable, not 0: plus=" + reached + " reachable=" + live.get("reachable") + " grid=\n" + live.get("grid"));
        check(hashes < 10, "open grass is not an all-# grid: hash=" + hashes + "\n" + live.get("grid"));
        check(liveRows[8].charAt(8) == '@', "agent marked at center: " + liveRows[8]);
        check(Integer.valueOf(83).equals(live.get("feetY")), "flood origin snapped to standable 83: " + live.get("feetY"));

        PathfinderTest.MapGrid airOverGrass = new PathfinderTest.MapGrid(-64, 320);
        airOverGrass.fillFloor(-16, 16, -16, 16, 82, "grass_block");
        Map<String, Object> head = AgentPerception.walkmap(airOverGrass, 0, 84, 0, 8, 80, 88, false);
        check(((Number) head.get("reachable")).intValue() > 50, "head-height over grass still floods: " + head.get("reachable"));
        check(Integer.valueOf(83).equals(head.get("feetY")), "MapGrid feetY snap: " + head.get("feetY"));

        JsonObject unknown = heightNorm.deepCopy();
        unknown.addProperty("foo", 1);
        try {
            AgentSurvivalActions.fields(unknown, java.util.List.of("radius", "x", "z"), "action", "agent");
            throw new AssertionError("unknown field accepted");
        } catch (IllegalArgumentException expected) {
            check(expected.getMessage().contains("foo"), "unknown field named: " + expected.getMessage());
        }

        PathfinderTest.MapGrid room = new PathfinderTest.MapGrid(-64, 320);
        room.fillFloor(-2, 2, -2, 2, 86, "stone");
        for (int wx = -2; wx <= 2; wx++) {
            room.set(wx, 87, 1, "stone");
            room.set(wx, 88, 1, "stone");
        }
        Map<String, Object> box = AgentPerception.scanBox(room, -1, 87, -1, 1, 88, 1, null);
        check(Boolean.TRUE.equals(box.get("complete")), "loaded box is complete");
        check(Long.valueOf(18).equals(box.get("cells")) || Integer.valueOf(18).equals(box.get("cells")),
                "3x2x3 volume: " + box.get("cells"));
        @SuppressWarnings("unchecked")
        var solids = (java.util.List<Map<String, Object>>) box.get("solids");
        check(solids != null && solids.size() == 6, "six stone wall cells in the 3x2 slice: " + solids);
        check(solids.stream().anyMatch(s -> Integer.valueOf(0).equals(s.get("x")) && Integer.valueOf(1).equals(s.get("z"))),
                "wall at z=1 is listed, not air: " + solids);
        @SuppressWarnings("unchecked")
        var coverage = (Map<String, Object>) box.get("coverage");
        check(Boolean.TRUE.equals(coverage.get("complete")) && ((Number) coverage.get("solid")).intValue() == 6,
                "coverage: " + coverage);

        Map<String, Object> pathOk = AgentPerception.scanBox(room, -2, 87, -2, 2, 88, 2,
                new Integer[]{0, 87, -1, 0, 87, -1});
        @SuppressWarnings("unchecked")
        var same = (Map<String, Object>) pathOk.get("path");
        check(Boolean.TRUE.equals(same.get("reachable")), "same-cell path: " + same);

        Map<String, Object> walled = AgentPerception.scanBox(room, -2, 87, -2, 2, 88, 2,
                new Integer[]{0, 87, -1, 0, 87, 2});
        @SuppressWarnings("unchecked")
        var wallPath = (Map<String, Object>) walled.get("path");
        check(Boolean.FALSE.equals(wallPath.get("reachable")), "stone wall blocks A*: " + wallPath);
        @SuppressWarnings("unchecked")
        var hit = (Map<String, Object>) wallPath.get("blocked");
        check(hit != null && "STONE".equals(hit.get("material")), "first blocking cell is STONE: " + wallPath);

        PathfinderTest.MapGrid partial = new PathfinderTest.MapGrid(-64, 320);
        partial.fillFloor(14, 17, 0, 0, 86, "stone");
        partial.unloaded.add("1,0");
        Map<String, Object> gap = AgentPerception.scanBox(partial, 14, 87, 0, 17, 87, 0, null);
        check(Boolean.FALSE.equals(gap.get("complete")), "unloaded cells are not complete: " + gap);
        check(((Number) gap.get("unloaded")).intValue() == 2, "x=16,17 are chunk 1 and unloaded: " + gap);
        @SuppressWarnings("unchecked")
        var gapLayers = (java.util.List<Map<String, Object>>) gap.get("layers");
        String gapRow = String.valueOf(((java.util.List<?>) gapLayers.get(0).get("grid")).get(0));
        check(gapRow.contains("?") && !gapRow.contains("AIR"), "unloaded is ?, never AIR: " + gapRow);
        @SuppressWarnings("unchecked")
        var gapCoverage = (Map<String, Object>) gap.get("coverage");
        check(Boolean.FALSE.equals(gapCoverage.get("complete")) && ((Number) gapCoverage.get("unloaded")).intValue() == 2,
                "coverage unloaded: " + gapCoverage);

        check(AgentPerception.boxVolume(0, 15, 0, 15, 0, 15) == 4096, "16^3 is the cap");
        check(AgentPerception.boxVolume(0, 100000, 0, 100000, 0, 100000) > AgentPerception.BOX_CAP,
                "overflowing volume still exceeds the cap");
        check(AgentPerception.BOX_CAP == 4096, "box cap is 4096");
        try {
            AgentPerception.scanBoxLive(null, null);
            throw new AssertionError("scan_box without body should fail");
        } catch (IllegalArgumentException expected) {
            check(expected.getMessage().toLowerCase().contains("scan_box")
                    || expected.getMessage().toLowerCase().contains("body"),
                    "scan_box live error: " + expected.getMessage());
        }

        check(AgentActionCatalog.advertised("scan_box") && AgentActionCatalog.advertised("region"),
                "scan_box and region advertised");
        check(AgentActionProgram.concurrentSafeAction("scan_box") && AgentActionProgram.concurrentSafeAction("region"),
                "scan_box is concurrent-safe");

        System.out.println("PASS: heightmap/walkmap compact grids, surface kinds, cliff/hazard/dig, pathfinder agreement, documented params, fb-3 grass flood, scan_box coverage");
    }

    /** ymin-1..ymax+2 box with a grass floor, matching walkmapLive snapshot bounds. */
    static AgentPerception.NameGrid grassColumn(int ax, int floorY, int az, int radius, int ymin, int ymax, boolean bukkitCase) {
        int x0 = ax - radius, z0 = az - radius, y0 = ymin - 1;
        int x1 = ax + radius, z1 = az + radius, y1 = ymax + 2;
        int sx = x1 - x0 + 1, sy = y1 - y0 + 1, sz = z1 - z0 + 1;
        String[] cells = new String[sx * sy * sz];
        String air = bukkitCase ? "AIR" : "air";
        String grass = bukkitCase ? "GRASS_BLOCK" : "grass_block";
        java.util.Arrays.fill(cells, air);
        for (int z = z0; z <= z1; z++) for (int x = x0; x <= x1; x++) {
            int i = (x - x0) + sx * ((floorY - y0) + sy * (z - z0));
            cells[i] = grass;
        }
        return new AgentPerception.NameGrid(cells, x0, y0, z0, sx, sy, sz, -64, 320);
    }

    static void check(boolean ok, String message) {
        if (!ok) throw new AssertionError(message);
    }
}
