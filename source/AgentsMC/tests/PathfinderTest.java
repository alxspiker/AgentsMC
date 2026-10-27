package dev.agentsmc;

import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Offline Baritone-style A*: mountain/slope, pit, powder snow, river. */
public final class PathfinderTest {
    static final class MapGrid implements AgentPathfinder.Grid {
        final Map<String, String> blocks = new HashMap<>();
        final Set<String> unloaded = new HashSet<>();
        final Set<String> prot = new HashSet<>();
        final int minY, maxY;
        MapGrid(int minY, int maxY) { this.minY = minY; this.maxY = maxY; }
        void fillFloor(int x0, int x1, int z0, int z1, int y, String mat) {
            for (int x = x0; x <= x1; x++) for (int z = z0; z <= z1; z++) set(x, y, z, mat);
        }
        void set(int x, int y, int z, String mat) { blocks.put(x + "," + y + "," + z, mat.toLowerCase()); }
        String at(int x, int y, int z) { return blocks.getOrDefault(x + "," + y + "," + z, "air"); }
        public String name(int x, int y, int z) { return at(x, y, z); }
        public boolean loaded(int x, int y, int z) { return !unloaded.contains((x >> 4) + "," + (z >> 4)); }
        public boolean usable(int x, int y, int z) { return y - 1 >= minY && y + 1 < maxY && loaded(x, y, z); }
        public boolean open(int x, int y, int z) {
            String n = at(x, y, z);
            if (AgentPathfinder.hazardName(n)) return false;
            if (AgentPathfinder.waterName(n) || AgentPathfinder.climbName(n)) return true;
            if (AgentPathfinder.thinPassable(n, 1, true)) return true;
            if (AgentPathfinder.walkThroughName(n)) return true;
            if (AgentPathfinder.doorWalkName(n)) return true;
            return n.equals("air") || n.equals("cave_air") || n.equals("torch") || n.equals("wall_torch");
        }
        public boolean floor(int x, int y, int z) {
            String n = at(x, y, z);
            if (AgentPathfinder.hazardName(n) || AgentPathfinder.waterName(n) || n.equals("air")) return false;
            if (AgentPathfinder.thinPassable(n, 1, true) && AgentPathfinder.surfaceHeight(n) < 0.999) return false;
            return n.equals("stone") || n.equals("grass_block") || n.equals("dirt") || n.equals("sand")
                    || n.equals("snow_block") || n.equals("iron_ore") || n.equals("cobblestone")
                    || n.equals("deepslate") || n.endsWith("_ore");
        }
        public double surface(int x, int y, int z) {
            double s = AgentPathfinder.surfaceHeight(at(x, y, z));
            return s < 0.999 ? s : 0;
        }
        public boolean water(int x, int y, int z) { return AgentPathfinder.waterName(at(x, y, z)); }
        public boolean climbable(int x, int y, int z) { return AgentPathfinder.climbName(at(x, y, z)); }
        public boolean hazard(int x, int y, int z) { return AgentPathfinder.hazardName(at(x, y, z)); }
        public boolean soft(int x, int y, int z) { return AgentPathfinder.softName(at(x, y, z)); }
        public boolean diggable(int x, int y, int z) {
            String n = at(x, y, z);
            return AgentPathfinder.softName(n) || AgentPathfinder.diggableName(n);
        }
        public boolean protectedCell(int x, int y, int z) { return prot.contains(x + "," + z); }
    }

    /** 0.7.3: snow layer collision is (layers-1)/8; 1 layer = air (feet on block below). */
    static void snowLayers073() {
        check(AgentPathfinder.surfaceHeight("SNOW", 1, true) == 0, "1-layer snow has no collision");
        check(AgentPathfinder.surfaceHeight("SNOW", 2, true) == 0.125, "2-layer snow 0.125");
        check(AgentPathfinder.surfaceHeight("SNOW", 8, true) == 0.875, "8-layer snow 0.875");
        check(AgentPathfinder.thinPassable("SNOW", 1, true) && AgentPathfinder.thinPassable("SNOW", 8, true), "snow passable");
        check(!AgentPathfinder.cellFloor("SNOW", false, false, true, true, 0), "1-layer snow is not a floor");
        // Live repro: cobble at y87, 1-layer snow at y88 (feet cell).
        MapGrid g = new MapGrid(0, 128);
        for (int x = -6; x <= 6; x++) for (int z = -6; z <= 6; z++) { g.set(x, 87, z, "cobblestone"); g.set(x, 88, z, "snow"); }
        check(g.standable(0, 88, 0), "standable in 1-layer snow cell over cobble");
        check(g.standY(0, 88, 0) == 88.0, "feet y on 1-layer snow is 88.0: " + g.standY(0, 88, 0));
        AgentPathfinder.Result r = AgentPathfinder.plan(0, 88, 0, 5, 88, 3, g, new AgentPathfinder.Options());
        check(r.fail == null && r.path != null, "plan across snow: " + r.fail);
        for (int[] wp : r.path) {
            check(wp[1] == 88, "waypoint y stays 88: " + java.util.Arrays.toString(wp));
            // Agent standing exactly on the cobble (feet 88.0) at the node center must count as arrived.
            check(AgentNavigation.reachedWaypoint(wp[0] + .5, 88.0, wp[2] + .5, wp, g.standY(wp[0], wp[1], wp[2])),
                    "arrived on snow node " + java.util.Arrays.toString(wp));
        }
        // Old bug: standY 88.125 made feet 88.0 fail reachedWaypoint (y < standY - ON_STEP).
        check(!AgentNavigation.reachedWaypoint(.5, 88.0, .5, new int[]{0, 88, 0}, 88.125), "regression guard: old standY rejected arrival");
        System.out.println("PASS: 0.7.3 snow layers: 1 layer = air, (layers-1)/8 collision, arrival on snow nodes");
    }

    /** 0.7.4: swim along the water surface, never snap swimmers to the seabed, surface for air. */
    static void water074() {
        // Live repro: sea floor y55, water y56..62, air from y63; target water surface (76,62,228).
        MapGrid g = new MapGrid(-64, 320);
        for (int x = 60; x <= 80; x++) for (int z = 220; z <= 232; z++) {
            g.set(x, 55, z, "sand");
            for (int y = 56; y <= 62; y++) g.set(x, y, z, "water");
        }
        AgentPathfinder.Result r = AgentPathfinder.plan(62, 62, 226, 76, 62, 228, g, new AgentPathfinder.Options());
        check(r.fail == null && r.path != null, "plan over water: " + r.fail);
        for (int[] wp : r.path) {
            check(wp[1] >= 62, "surface swim stays at y>=62 (no seabed): " + java.util.Arrays.toString(wp));
            check(!AgentPathfinder.submerged(g, wp[0], wp[1], wp[2]), "head above water: " + java.util.Arrays.toString(wp));
        }
        check(r.path.get(r.path.size() - 1)[0] == 76 && r.path.get(r.path.size() - 1)[2] == 228, "reaches water target");
        check(AgentPathfinder.submerged(g, 70, 58, 226) && !AgentPathfinder.submerged(g, 70, 62, 226), "submerged = head cell water");
        // A dive the target needs is still allowed (seabed target).
        AgentPathfinder.Result dive = AgentPathfinder.plan(62, 62, 226, 66, 56, 226, g, new AgentPathfinder.Options());
        check(dive.fail == null && dive.path.get(dive.path.size() - 1)[1] == 56, "dive to seabed target: " + dive.fail);
        // No air snap while in or right over water (the old same_column_air_snap).
        check(AgentNavigation.waterColumn(false, true, false), "hover over water is not airborne");
        check(AgentNavigation.waterColumn(true, true, true) && !AgentNavigation.waterColumn(false, false, false), "water column");
        // Breath hysteresis: 300 max air; surface at <=90, keep surfacing until >=285.
        check(!AgentNavigation.needsAir(300, 300, false) && !AgentNavigation.needsAir(91, 300, false), "plenty of air");
        check(AgentNavigation.needsAir(90, 300, false) && AgentNavigation.needsAir(0, 300, false), "low air surfaces");
        check(AgentNavigation.needsAir(200, 300, true) && !AgentNavigation.needsAir(290, 300, true), "resume dive after refill");
        check(!AgentNavigation.needsAir(0, 0, false), "no max air, no surfacing");
        // 0.7.5 dive arrival: finish the last leg unless air is critical; surfacing near target is arrived_near.
        check(!AgentNavigation.surfaceNow(true, 80, 300, 2.6), "finish dive 2.6 short");
        check(AgentNavigation.surfaceNow(true, 25, 300, 2.6), "critical air surfaces anyway");
        check(AgentNavigation.surfaceNow(true, 80, 300, 10), "far target surfaces");
        check(!AgentNavigation.surfaceNow(false, 80, 300, 10), "plenty of air");
        check(AgentNavigation.diveArrived(true, 2.6) && !AgentNavigation.diveArrived(true, 5) && !AgentNavigation.diveArrived(false, 1), "dive arrival tolerance");
        // Vertical swim: surfacing always rises with wet head even when the waypoint is deeper.
        check(AgentNavigation.swimVy(true, true, true, 57.0, 56.0, -0.2) >= AgentNavigation.SWIM_UP, "low air ascends over a dive");
        check(AgentNavigation.swimVy(true, true, false, 57.0, 56.0, -0.2) < 0, "dive kept when air is fine");
        check(AgentNavigation.swimVy(true, true, false, 60.0, 62.0, -0.05) > 0, "submerged toward surface waypoint rises");
        check(AgentNavigation.swimVy(true, false, false, 62.2, 62.0, 0.0) == 0.0, "at surface no forced bob");
        check(AgentNavigation.swimVy(false, false, true, 64.0, 64.0, 0.0) == 0.0, "dry land unaffected");
        System.out.println("PASS: 0.7.4 water: surface swim, seabed dive on demand, no air snap over water, low-air ascend");
    }

    /** 0.7.0: snow step-up, 1-wide hole avoid/exit, portal helpers. */
    static void fb0680() {
        // fb-39/40: feet on 1-layer snow (88.125) to snow one block up (89.125) is a normal jump.
        check(!AgentNavigation.tooHighToJump(88.125, 89.125, AgentPathfinder.JUMP), "snow->snow +1 is jumpable");
        check(AgentNavigation.climbVelocity(88.125, 89.125, 90, AgentPathfinder.JUMP, 0) > 0.3, "snow->snow +1 gets jump velocity");
        check(AgentNavigation.tooHighToJump(11.5, 13, AgentPathfinder.JUMP), "mid-air 11.5 -> 13 is still too high");
        check(AgentPathfinder.surfaceHeight("daylight_detector") == 0.375 && AgentPathfinder.thinName("stonecutter"), "partial-height blocks");
        // Snow-covered cobble staircase: plan climbs it.
        MapGrid g = new MapGrid(0, 128);
        for (int x = -2; x <= 8; x++) for (int z = -2; z <= 2; z++) {
            int top = 86 + Math.max(0, Math.min(3, x - 1));
            for (int y = 80; y <= top; y++) g.set(x, y, z, "cobblestone");
            g.set(x, top + 1, z, "snow");
        }
        AgentPathfinder.Result r = AgentPathfinder.plan(0, 87, 0, 6, 90, 0, g, new AgentPathfinder.Options());
        check(r.fail == null && r.path != null && !r.segment, "snow staircase plan: " + r.fail + " " + r.note);
        // fb-43: 2-deep 1-wide hole at (0,89,0) in ground whose top is y=90.
        MapGrid h = new MapGrid(0, 128);
        for (int x = -4; x <= 4; x++) for (int z = -4; z <= 4; z++) for (int y = 85; y <= 90; y++) h.set(x, y, z, "stone");
        h.set(0, 89, 0, "air"); h.set(0, 90, 0, "air");
        check(AgentPathfinder.holeCell(h, 0, 89, 0), "1-wide hole detected");
        int[] exit = AgentPathfinder.holeExit(h, 0, 89, 0, 4, 0);
        check(exit != null && exit[1] == 91 && exit[0] == 1 && exit[2] == 0, "hole exit toward goal 2 up: " + java.util.Arrays.toString(exit));
        check(!AgentPathfinder.holeCell(h, 2, 91, 0), "open ground is not a hole");
        AgentPathfinder.Result across = AgentPathfinder.plan(-3, 91, 0, 3, 91, 0, h, new AgentPathfinder.Options());
        check(across.fail == null && across.path.stream().noneMatch(c -> c[0] == 0 && c[1] == 89 && c[2] == 0), "route does not drop into the hole");
        // fb-38 portal helpers.
        check(AgentPortal.inside(-5.4, 10.6, -6, 10) && !AgentPortal.inside(-5.5, 11.3, -6, 10), "portal inside test");
        check(AgentPortal.scale(org.bukkit.World.Environment.NORMAL, org.bukkit.World.Environment.NETHER) == 0.125
                && AgentPortal.scale(org.bukkit.World.Environment.NETHER, org.bukkit.World.Environment.NORMAL) == 8, "portal scale");
        check(AgentPortal.footY(66, y -> y >= 64 && y <= 66) == 64, "portal foot cell");
    }

    public static void main(String[] args) {
        check(!new AgentPathfinder.Options().dig && !new AgentPathfinder.Options().build,
                "planner defaults are walk-only");
        check(AgentPathfinder.hazardName("POWDER_SNOW") && AgentPathfinder.hazardName("lava")
                && AgentPathfinder.hazardName("cactus") && AgentPathfinder.hazardName("sweet_berry_bush")
                && AgentPathfinder.hazardName("fire"), "hazards");
        check(AgentPathfinder.waterName("water") && AgentPathfinder.climbName("ladder") && AgentPathfinder.softName("dirt"), "names");
        check(!AgentPathfinder.softName("powder_snow") && !AgentPathfinder.softName("sweet_berry_bush"), "hazards are not soft");

        mountain();
        slopeDrop();
        pit();
        powderSnow();
        river();
        unloaded();
        timeout();
        midRange();
        snapStart();
        stepDown();
        digStair();
        liveMine();
        longLeg();
        snowLayers();
        fb0680();
        snowLayers073();
        water074();
        longWalk();
        stuckClock();
        pocketLeaves();
        tunnelStair();
        twoBlockFace();
        ridgeAround();
        oneBlockHole();
        plants();
        cornerJump();
        chestAround();
        villageHut();
        craterStoop();
        rimPocket();
        canopyJump();
        System.out.println("PASS: pathfinder mountain/slope, 3-block drop, pit pillar, powder snow detour, river swim, 23-block open, snap start, step-down, dig stair, live mine tunnel, snow layers, 100-block walk, fail codes, leaf-ceiling pockets, 1x2 stair climb, no 2-block jump, ridge around, 1-block hole, walk-through plants, cardinal corner jump, chest around, village hut door, crater stoop, crater-rim pocket, canopy jump");
    }

    static void mountain() {
        MapGrid g = new MapGrid(-64, 320);
        // Rising 1-high stairs x=0..10, stand on y=65+x
        for (int x = 0; x <= 10; x++) {
            g.fillFloor(x, x, -2, 2, 64 + x, "stone");
        }
        AgentPathfinder.Result r = AgentPathfinder.plan(0, 65, 0, 10, 75, 0, g, new AgentPathfinder.Options());
        check(r.fail == null && r.path.size() >= 10, "mountain path: " + r.fail + " " + (r.path == null ? 0 : r.path.size()));
        check(r.path.get(r.path.size() - 1)[0] == 10 && r.path.get(r.path.size() - 1)[1] == 75, "mountain ends on summit");
        check(r.path.stream().anyMatch(p -> p[3] == AgentPathfinder.JUMP), "mountain uses jump-1");
    }

    static void slopeDrop() {
        MapGrid g = new MapGrid(-64, 320);
        g.fillFloor(-2, 2, -2, 2, 70, "stone");
        g.fillFloor(3, 8, -2, 2, 67, "stone"); // 3-block drop
        AgentPathfinder.Result r = AgentPathfinder.plan(0, 71, 0, 6, 68, 0, g, new AgentPathfinder.Options());
        check(r.fail == null, "3-block drop should be safe: " + r.fail);
        check(r.path.stream().anyMatch(p -> p[3] == AgentPathfinder.DROP || p[1] <= 68), "uses drop: " + kinds(r.path));
        MapGrid deadly = new MapGrid(-64, 320);
        deadly.fillFloor(-2, 2, -2, 2, 70, "stone");
        deadly.fillFloor(3, 8, -2, 2, 65, "stone"); // 5-block drop, no water
        AgentPathfinder.Result fall = AgentPathfinder.plan(0, 71, 0, 6, 66, 0, deadly, new AgentPathfinder.Options());
        check(fall.fail != null, "5-block dry drop is not a walk");
    }

    static void pit() {
        MapGrid g = new MapGrid(-64, 320);
        g.fillFloor(-8, 8, -8, 8, 64, "stone");
        // 2-deep 1x1 hole at 0,63,0 — remove floor at 64 and 65 air already
        g.blocks.remove("0,64,0");
        g.set(0, 62, 0, "stone");
        AgentPathfinder.Options noBuild = new AgentPathfinder.Options();
        noBuild.build = false; noBuild.dig = false;
        AgentPathfinder.Result stuck = AgentPathfinder.plan(0, 63, 0, 3, 65, 0, g, noBuild);
        check(AgentPathfinder.NO_PATH.equals(stuck.fail) || AgentPathfinder.BLOCKED.equals(stuck.fail),
                "2-deep pit without build: " + stuck.fail);
        AgentPathfinder.Options build = new AgentPathfinder.Options();
        build.build = true;
        AgentPathfinder.Result out = AgentPathfinder.plan(0, 63, 0, 3, 65, 0, g, build);
        check(out.fail == null, "pillar out of 2-deep pit: " + out.fail);
        check(out.path.stream().anyMatch(p -> p[3] == AgentPathfinder.PILLAR || p[1] >= 65), "pit path climbs: " + kinds(out.path));
        g.prot.add("0,0"); g.prot.add("3,0");
        AgentPathfinder.Options prot = new AgentPathfinder.Options();
        prot.build = false;
        AgentPathfinder.Result blocked = AgentPathfinder.plan(0, 63, 0, 3, 65, 0, g, prot);
        check(AgentPathfinder.BLOCKED.equals(blocked.fail) || AgentPathfinder.NO_PATH.equals(blocked.fail),
                "protected pit: " + blocked.fail);
    }

    static void powderSnow() {
        MapGrid g = new MapGrid(-64, 320);
        g.fillFloor(0, 16, 0, 8, 64, "stone");
        for (int x = 4; x <= 8; x++) for (int z = 0; z <= 8; z++) {
            g.set(x, 65, z, "powder_snow");
            g.set(x, 66, z, "powder_snow");
        }
        // Safe lane at z=10
        g.fillFloor(0, 16, 9, 12, 64, "stone");
        AgentPathfinder.Result r = AgentPathfinder.plan(1, 65, 2, 14, 65, 2, g, new AgentPathfinder.Options());
        check(r.fail == null, "powder snow detour: " + r.fail);
        for (int[] p : r.path)
            check(!g.hazard(p[0], p[1], p[2]) && !g.hazard(p[0], p[1] - 1, p[2]),
                    "path stepped on powder snow at " + p[0] + "," + p[1] + "," + p[2]);
        check(r.path.stream().anyMatch(p -> p[2] >= 9), "detour goes around the field");
    }

    static void river() {
        MapGrid g = new MapGrid(-64, 320);
        g.fillFloor(0, 3, -2, 2, 64, "stone");
        g.fillFloor(8, 12, -2, 2, 64, "stone");
        for (int x = 4; x <= 7; x++) for (int z = -2; z <= 2; z++) {
            g.set(x, 64, z, "stone");
            g.set(x, 65, z, "water");
            g.set(x, 66, z, "air");
        }
        AgentPathfinder.Result r = AgentPathfinder.plan(1, 65, 0, 10, 65, 0, g, new AgentPathfinder.Options());
        check(r.fail == null, "river swim: " + r.fail + " expands=" + r.expands);
        check(r.path.stream().anyMatch(p -> p[3] == AgentPathfinder.SWIM || g.water(p[0], p[1], p[2]) || g.water(p[0], p[1] - 1, p[2])),
                "path swims: " + kinds(r.path));
        check(r.path.get(r.path.size() - 1)[0] >= 8, "reaches far bank");
    }

    static void unloaded() {
        MapGrid g = new MapGrid(-64, 320);
        g.fillFloor(0, 4, 0, 4, 64, "stone");
        g.unloaded.add((40 >> 4) + "," + (0 >> 4));
        AgentPathfinder.Result r = AgentPathfinder.plan(1, 65, 1, 40, 65, 1, g, new AgentPathfinder.Options());
        check(AgentPathfinder.UNLOADED.equals(r.fail), "unloaded goal: " + r.fail);
    }

    static void timeout() {
        MapGrid g = new MapGrid(-64, 320);
        g.fillFloor(-20, 20, -20, 20, 64, "stone");
        AgentPathfinder.Options tiny = new AgentPathfinder.Options();
        tiny.maxExpand = 3;
        tiny.segment = 128;
        AgentPathfinder.Result r = AgentPathfinder.plan(0, 65, 0, 18, 65, 18, g, tiny);
        check(AgentPathfinder.NO_PATH.equals(r.fail),
                "search-budget miss is no_path, not timeout: " + r.fail + " note=" + r.note);
        check(r.note != null && r.note.toLowerCase().contains("not a time timeout"),
                "budget miss must say it is not a time timeout: " + r.note);
        check(!AgentPathfinder.TIMEOUT.equals(r.fail), "planner must not emit timeout for expand limit");
    }

    /** Builder Bot: 1.6,81,1 → 24,80,10 (~23 blocks) must plan, not instant timeout. */
    static void midRange() {
        MapGrid g = new MapGrid(-64, 320);
        g.fillFloor(-4, 32, -4, 16, 64, "stone");
        long t0 = System.nanoTime();
        AgentPathfinder.Result r = AgentPathfinder.plan(2, 65, 1, 24, 65, 10, g, new AgentPathfinder.Options());
        long ms = (System.nanoTime() - t0) / 1_000_000;
        check(r.fail == null, "23-block open plan: " + r.fail + " note=" + r.note + " expands=" + r.expands);
        check(!AgentPathfinder.TIMEOUT.equals(r.fail), "23-block open must not be timeout");
        check(r.path != null && r.path.size() >= 20, "23-block path length: " + (r.path == null ? 0 : r.path.size()));
        int[] end = r.path.get(r.path.size() - 1);
        check(end[0] == 24 && end[2] == 10, "ends at 24,65,10: " + end[0] + "," + end[1] + "," + end[2]);
        check(ms < 1000, "open 23-block plan must not take a second: " + ms + "ms expands=" + r.expands);
        check(r.expands < AgentPathfinder.MAX_EXPAND, "open 23-block must not exhaust the search budget: " + r.expands);
        double moved = AgentNavigation.simulateTravel(r.path, 1.6, 65, 1.0, 40, g);
        check(moved > 5, "23-block follower must walk: " + moved);
    }

    /** Builder Bot: feet at y=78, adjacent 2-high hole at y=77. Walk off the edge, do not sit. */
    static void stepDown() {
        MapGrid g = new MapGrid(-64, 320);
        g.fillFloor(20, 26, 5, 10, 77, "grass_block");
        g.fillFloor(20, 26, 5, 10, 76, "dirt");
        g.fillFloor(20, 26, 5, 10, 75, "stone");
        g.blocks.remove("23,77,6");
        g.set(23, 77, 6, "air");
        g.set(23, 78, 6, "air");
        check(g.standable(23, 78, 7) && g.standable(23, 77, 6), "ledge and hole are standable");
        AgentPathfinder.Options noDig = new AgentPathfinder.Options();
        noDig.dig = false; noDig.build = false;
        AgentPathfinder.Result r = AgentPathfinder.plan(23, 78, 7, 23, 77, 6, g, noDig);
        check(r.fail == null, "1-block step-down plan: " + r.fail + " " + r.note);
        check(r.path.stream().anyMatch(p -> p[3] == AgentPathfinder.DROP || p[1] == 77),
                "step-down uses DROP: " + kinds(r.path));
        check(!AgentNavigation.reachedWaypoint(23.54, 78, 6.72, new int[]{23, 77, 6, AgentPathfinder.DROP}, 77),
                "still on the ledge is not the drop waypoint");
        check(AgentNavigation.reachedWaypoint(23.5, 77.05, 6.5, new int[]{23, 77, 6, AgentPathfinder.DROP}, 77),
                "in the hole is the drop waypoint");
        double moved = AgentNavigation.simulateTravel(r.path, 23.69, 78, 7.53, 20, g);
        check(moved > 0.5, "step-down follower must walk off the edge: " + moved);
        check(!AgentNavigation.nearEnough(3.2, 2.0, 2), "2-deep shaft is not arrival");
        check(!AgentNavigation.nearEnough(2.0, 0.2, 2), "2 blocks same-level is not arrival");
        check(AgentNavigation.nearEnough(0.9, 0.2, 2), "within ~1 block is arrival");
        check(!AgentNavigation.nearEnough(1.0, -1.0, 2), "one block below the target is not arrived_near");
        check(AgentNavigation.nearEnough(0.9, 0.2, 2), "slightly above within 1 block is arrived_near");
    }

    /**
     * Builder Bot fb-10: self-dug 1x2 staircase. JUMP must not require current y+2
     * open (the tunnel ceiling). Body one block below a JUMP waypoint is not arrival.
     */
    static void tunnelStair() {
        MapGrid g = new MapGrid(-64, 320);
        for (int y = 5; y <= 18; y++) g.fillFloor(-2, 8, -2, 2, y, "stone");
        for (int x = 0; x <= 5; x++) {
            int stand = 10 + x;
            g.set(x, stand, 0, "air");
            g.set(x, stand + 1, 0, "air");
            g.set(x, stand - 1, 0, "stone");
        }
        check(g.standable(0, 10, 0) && g.standable(1, 11, 0) && g.standable(5, 15, 0), "1x2 stair stands");
        check(!AgentPathfinder.headroomOpen(g, 0, 12, 0), "current cell ceiling is stone");
        AgentPathfinder.Options noDig = new AgentPathfinder.Options();
        noDig.dig = false;
        noDig.build = false;
        AgentPathfinder.Result r = AgentPathfinder.plan(0, 10, 0, 5, 15, 0, g, noDig);
        check(r.fail == null, "1x2 stair climb plan: " + r.fail + " " + r.note);
        check(r.path.stream().anyMatch(p -> p[3] == AgentPathfinder.JUMP),
                "1x2 climb uses JUMP: " + kinds(r.path));
        int[] end = r.path.get(r.path.size() - 1);
        check(end[0] == 5 && end[1] == 15, "ends at the top step: " + kinds(r.path));
        check(!AgentNavigation.reachedWaypoint(4.5, 14, 0.5, new int[]{4, 15, 0, AgentPathfinder.JUMP}, 15),
                "body a block below a JUMP waypoint is not reached");
        check(!AgentNavigation.reachedWaypoint(4.5, 14.5, 0.5, new int[]{4, 15, 0, AgentPathfinder.JUMP}, 15),
                "mid-jump 0.5 below a JUMP waypoint is not reached");
        check(!AgentNavigation.reachedWaypoint(4.5, 14.75, 0.5, new int[]{4, 15, 0, AgentPathfinder.JUMP}, 15),
                "mid-air 0.25 below a JUMP waypoint is not reached");
        check(AgentNavigation.reachedWaypoint(4.5, 15.05, 0.5, new int[]{4, 15, 0, AgentPathfinder.JUMP}, 15),
                "on the JUMP step is reached");
        double moved = AgentNavigation.simulateTravel(r.path, 0.5, 10, 0.5, 120, g);
        check(moved > 3, "1x2 stair follower must climb: " + moved);
        AgentPathfinder.Options dig = new AgentPathfinder.Options();
        dig.dig = true;
        dig.build = false;
        g.set(3, 14, 0, "stone");
        check(!g.standable(3, 13, 0), "blocked up-step is not a walk");
        AgentPathfinder.Result mined = AgentPathfinder.plan(2, 12, 0, 3, 13, 0, g, dig);
        check(mined.fail == null, "dig:true 1-up stair: " + mined.fail);
        check(mined.path.stream().anyMatch(p -> p[3] == AgentPathfinder.DIG || p[3] == AgentPathfinder.JUMP),
                "dig-up or jump out of the blocked step: " + kinds(mined.path));
    }

    /**
     * Helper on a dirt hill: a 2-block face is not a JUMP. A* goes around on
     * 1-block stairs, and the driver must not hop +2 after skipping a stair.
     */
    static void twoBlockFace() {
        MapGrid g = new MapGrid(-64, 320);
        g.fillFloor(-2, 8, -2, 6, 64, "dirt");
        // Sheer 2-block wall at x=2, z=-1..1; top stand is y=67.
        for (int z = -1; z <= 1; z++) {
            g.set(2, 65, z, "dirt");
            g.set(2, 66, z, "dirt");
        }
        g.fillFloor(2, 5, -1, 1, 66, "dirt");
        // 1-step detour at z=4: dirt at 65 then 66 so the climb is two +1 jumps.
        g.set(1, 65, 4, "dirt");
        g.set(2, 66, 4, "dirt");
        g.set(2, 66, 3, "dirt");
        g.set(2, 66, 2, "dirt");
        java.util.List<int[]> jumps = new java.util.ArrayList<>();
        AgentPathfinder.walkSteps(1, 65, 0, g, false, (nx, ny, nz, move) -> {
            if (move == AgentPathfinder.JUMP) jumps.add(new int[]{nx, ny, nz});
        });
        check(jumps.stream().noneMatch(p -> p[1] >= 67),
                "walkSteps must not JUMP onto a 2-block face: " + java.util.Arrays.deepToString(jumps.toArray()));
        AgentPathfinder.Options noBuild = new AgentPathfinder.Options();
        noBuild.dig = false;
        noBuild.build = false;
        AgentPathfinder.Result r = AgentPathfinder.plan(0, 65, 0, 2, 67, 0, g, noBuild);
        check(r.fail == null, "2-block hill goes around on 1-steps: " + r.fail + " " + r.note);
        for (int i = 1; i < r.path.size(); i++) {
            int dy = r.path.get(i)[1] - r.path.get(i - 1)[1];
            check(dy <= 1, "path step " + i + " climbs " + dy + ": " + kinds(r.path));
        }
        check(r.path.stream().anyMatch(p -> p[3] == AgentPathfinder.JUMP),
                "detour uses jump-1: " + kinds(r.path));
        check(!AgentNavigation.tooHighToJump(65, 66, AgentPathfinder.JUMP), "1-block rise is jumpable");
        check(AgentNavigation.tooHighToJump(65, 67, AgentPathfinder.JUMP), "2-block rise is not jumpable");
        check(AgentNavigation.tooHighToJump(65.5, 67, AgentPathfinder.JUMP),
                "mid-air 1.5 below a +2 dest is not jumpable");
        check(!AgentNavigation.tooHighToJump(65.96, 67, AgentPathfinder.JUMP),
                "landed on the +1 step may jump the next +1");
        check(!AgentNavigation.tooHighToJump(65, 67, AgentPathfinder.CLIMB), "ladders may climb more than 1");
        check(AgentNavigation.climbVelocity(65, 67, 80, AgentPathfinder.JUMP, 0) == 0,
                "drive must not hop a 2-block face");
        check(AgentNavigation.climbVelocity(65.8, 67, 80, AgentPathfinder.JUMP, 0.42) == 0,
                "skipped stair must not keep jumping toward +2");
        check(AgentBehaviors.stepJumpVelocity(65, 67) == 0.42, "unstick hop stays at vanilla 0.42, not dest dy=2");
        check(AgentBehaviors.stepJumpVelocity(65, 66) == 0.42, "1-block unstick is vanilla jump");
        check(AgentBehaviors.stepJumpVelocity(65, 65) == 0, "same-level unstick does not jump");
        MapGrid pillar = new MapGrid(-64, 320);
        pillar.fillFloor(-2, 4, -2, 4, 64, "dirt");
        pillar.set(2, 65, 0, "dirt");
        pillar.set(2, 66, 0, "dirt");
        AgentPathfinder.Result isolated = AgentPathfinder.plan(0, 65, 0, 2, 67, 0, pillar, noBuild);
        check(isolated.fail != null, "isolated 2-block pillar without a 1-step is not a jump: " + isolated.fail);
        check(AgentPathfinder.unclimbableFace(2, 65, 0, 2, 67, 0), "foot of +2 pillar is a face-hug");
        check(!AgentPathfinder.unclimbableFace(2, 66, 4, 2, 67, 0), "1-below on a detour is not a face-hug");
        check(AgentPathfinder.unclimbableFace(pillar, 2, 65, 0, 2, 67, 0), "grid face-hug still sees the pillar");
        check(!AgentPathfinder.unclimbableFace(pillar, 2, 66, 4, 2, 67, 0), "grid detour is not a face-hug");
    }

    /**
     * Helper fb: short hop onto a plateau behind a 2-block ridge must walk to a
     * side 1-step, not fail stuck on the rim (spawn-protection cannot dig a stair).
     */
    static void ridgeAround() {
        MapGrid g = new MapGrid(-64, 320);
        g.fillFloor(-4, 14, -8, 14, 64, "dirt");
        for (int z = -6; z <= 6; z++) {
            g.set(2, 65, z, "dirt");
            g.set(2, 66, z, "dirt");
        }
        g.fillFloor(2, 10, -6, 6, 66, "dirt");
        g.set(1, 65, 10, "dirt");
        g.set(2, 66, 10, "dirt");
        g.set(2, 66, 9, "dirt");
        g.set(2, 66, 8, "dirt");
        g.set(2, 66, 7, "dirt");
        AgentPathfinder.Options noBuild = new AgentPathfinder.Options();
        noBuild.dig = false;
        noBuild.build = false;
        AgentPathfinder.Result r = AgentPathfinder.plan(0, 65, 0, 5, 67, 0, g, noBuild);
        check(r.fail == null, "2-block ridge is walked around: " + r.fail + " " + r.note);
        check(r.path.stream().anyMatch(p -> p[2] >= 8),
                "around-path uses the side ramp: " + kinds(r.path));
        for (int i = 1; i < r.path.size(); i++) {
            int dy = r.path.get(i)[1] - r.path.get(i - 1)[1];
            check(dy <= 1, "ridge step " + i + " climbs " + dy + ": " + kinds(r.path));
        }
        int[] end = r.path.get(r.path.size() - 1);
        check(!(end[0] == 5 && end[1] == 65 && end[2] == 0),
                "must not stop hugging the unclimbable rim: " + kinds(r.path));
    }

    /** 1.8-tall player: a 1-block-high hole is not a walk. */
    static void oneBlockHole() {
        check(AgentPathfinder.BODY_BLOCKS == 2, "player occupies two blocks");
        MapGrid g = new MapGrid(-64, 320);
        g.fillFloor(-2, 10, -3, 3, 64, "stone");
        for (int x = 1; x <= 5; x++) g.set(x, 66, 0, "stone");
        check(!g.standable(3, 65, 0), "1-block hole is not standable");
        check(!AgentPathfinder.headClear(g, 3, 66, 0), "stone in the head cell is not headroom");
        g.set(3, 66, 1, "oak_slab");
        check(!AgentPathfinder.headClear(g, 3, 66, 1), "slab in the head cell is not headroom");
        check(!g.standable(3, 65, 1), "slab ceiling is not a player walk");
        AgentPathfinder.Options noDig = new AgentPathfinder.Options();
        noDig.dig = false;
        noDig.build = false;
        AgentPathfinder.Result r = AgentPathfinder.plan(0, 65, 0, 8, 65, 0, g, noDig);
        check(r.fail == null, "1-block hole is walked around: " + r.fail);
        for (int[] p : r.path) {
            check(!(p[2] == 0 && p[0] >= 1 && p[0] <= 5 && p[1] == 65),
                    "path crawled a 1-block hole at " + p[0] + "," + p[1] + "," + p[2]);
        }
    }

    /** Surface to a cell 5 down in solid stone/dirt: dig:true must stair/shaft, not no_path. */
    static void digStair() {
        MapGrid g = new MapGrid(-64, 320);
        for (int y = 70; y <= 77; y++) g.fillFloor(18, 28, 0, 10, y, "stone");
        g.fillFloor(18, 28, 0, 10, 69, "stone");
        check(g.floor(23, 72, 3) && !g.open(23, 73, 3), "target is buried in stone");
        check(AgentPathfinder.canDigStand(g, 23, 73, 3), "buried goal is a dig stand");
        AgentPathfinder.Options noDig = new AgentPathfinder.Options();
        noDig.dig = false; noDig.build = false;
        AgentPathfinder.Result blocked = AgentPathfinder.plan(23, 78, 7, 23, 73, 3, g, noDig);
        check(blocked.fail != null, "without dig the buried goal is not a walk: " + blocked.fail);
        AgentPathfinder.Options dig = new AgentPathfinder.Options();
        dig.dig = true; dig.build = false;
        AgentPathfinder.Result r = AgentPathfinder.plan(23, 78, 7, 23, 73, 3, g, dig);
        check(r.fail == null, "dig:true stair into stone: " + r.fail + " note=" + r.note + " expands=" + r.expands);
        check(r.path.stream().anyMatch(p -> p[3] == AgentPathfinder.DIG), "path mines: " + kinds(r.path));
        check(r.path.get(r.path.size() - 1)[1] <= 73, "ends at or below the buried goal Y: " + kinds(r.path));
    }

    /**
     * 0.6.30 mine: body in a 2x1 shaft to surface,
     * crafting table/furnace in the room, buried iron at 20,69,-5.
     * DIG must tunnel instead of flooding the surface to no_path.
     */
    static void liveMine() {
        check(AgentPathfinder.workstationName("crafting_table") && AgentPathfinder.workstationName("furnace"),
                "workstations");
        check(!AgentPathfinder.diggableName("crafting_table") && !AgentPathfinder.diggableName("furnace"),
                "workstations are not tunnel blocks");
        check(AgentPathfinder.diggableName("stone") && AgentPathfinder.diggableName("iron_ore"), "stone/ore tunnel");

        MapGrid g = new MapGrid(-64, 320);
        for (int y = 64; y <= 77; y++) g.fillFloor(8, 32, -12, 18, y, "stone");
        g.fillFloor(8, 32, -12, 18, 77, "grass_block");
        for (int y = 73; y <= 77; y++) {
            g.set(23, y, 6, "air");
            g.set(23, y, 7, "air");
        }
        g.set(22, 73, 8, "air");
        g.set(22, 74, 8, "air");
        g.set(23, 73, 8, "air");
        g.set(23, 74, 8, "air");
        g.set(22, 73, 6, "crafting_table");
        g.set(21, 73, 9, "furnace");
        g.set(22, 73, 10, "torch");
        g.set(19, 69, -6, "iron_ore");
        // Jump-stair to the grass so A* can flood the surface (the 0.6.30 no_path).
        for (int i = 0; i < 5; i++) {
            int x = 23 + i, y = 73 + i;
            g.set(x, y + 2, 8, "air");
            g.set(x + 1, y + 1, 8, "air");
            g.set(x + 1, y + 2, 8, "air");
        }
        check(g.standable(22, 73, 8), "mine floor stand");
        check(g.standable(23, 73, 7), "shaft stand");
        check(g.standable(28, 78, 8), "surface via stair");
        check(AgentPathfinder.canDigStand(g, 20, 69, -5), "buried iron target is a dig stand");

        AgentPathfinder.Options noDig = new AgentPathfinder.Options();
        noDig.dig = false;
        noDig.build = false;
        AgentPathfinder.Result blocked = AgentPathfinder.plan(22, 73, 8, 20, 69, -5, g, noDig);
        check(blocked.fail != null, "without dig the buried ore is not a walk: " + blocked.fail);

        AgentPathfinder.Options dig = new AgentPathfinder.Options();
        dig.dig = true;
        dig.build = false;
        AgentPathfinder.Result r = AgentPathfinder.plan(22, 73, 8, 20, 69, -5, g, dig);
        check(r.fail == null, "live mine dig:true 15-block buried: " + r.fail + " note=" + r.note + " expands=" + r.expands);
        check(r.expands < AgentPathfinder.MAX_EXPAND, "must not exhaust the search budget: " + r.expands);
        check(r.path.stream().anyMatch(p -> p[3] == AgentPathfinder.DIG), "mines a 1x2 tunnel: " + kinds(r.path));
        check(r.path.get(r.path.size() - 1)[1] <= 69, "ends at or below buried Y: " + kinds(r.path));
        for (int[] p : r.path) {
            check(!"crafting_table".equals(g.at(p[0], p[1], p[2])), "path must not occupy the crafting table");
            check(!"furnace".equals(g.at(p[0], p[1], p[2])), "path must not occupy the furnace");
        }

        g.set(23, 73, 5, "air");
        g.set(23, 72, 5, "air");
        g.set(23, 73, 4, "air");
        g.set(23, 72, 4, "air");
        g.set(23, 71, 5, "stone");
        g.set(23, 71, 4, "stone");
        check(g.standable(23, 72, 4), "hand-dug 1x2 cell is standable");
        AgentPathfinder.Result corridor = AgentPathfinder.plan(22, 73, 8, 23, 72, 4, g, noDig);
        check(corridor.fail == null, "1x2 corridor walk: " + corridor.fail + " " + corridor.note);
        check(corridor.path.stream().noneMatch(p -> p[3] == AgentPathfinder.DIG),
                "dig:false corridor is a walk: " + kinds(corridor.path));
        double stepped = AgentNavigation.simulateTravel(corridor.path, 22.7, 73, 8.3, 40, g);
        check(stepped > 1.5, "follower must step into the 1x2 corridor: " + stepped);

        check(!AgentNavigation.nearEnough(2.0, 0, 1.1), "2 blocks + jitter is not arrived_near");
        check(AgentNavigation.nearEnough(0.9, 0, 1.1), "within ~1 block is arrived_near");
        check(AgentNavigation.spinningInPlace(20.58, 0.1), "timeout loop with no net is stuck");
        check(!AgentNavigation.spinningInPlace(2.0, 0.1), "short jitter is not stuck yet");
        check(!AgentNavigation.spinningInPlace(20.58, 8.0), "real travel is not spin");
    }

    static void snapStart() {
        MapGrid g = new MapGrid(-64, 320);
        g.fillFloor(-2, 12, -2, 6, 64, "stone");
        // Feet in air one block above the floor — live bodies often report this after a hop.
        AgentPathfinder.Result r = AgentPathfinder.plan(1, 66, 1, 10, 65, 3, g, new AgentPathfinder.Options());
        check(r.fail == null, "unstandable start must snap and plan: " + r.fail + " note=" + r.note);
        check(r.path != null && r.path.get(0)[1] == 65, "snapped start Y: " + (r.path == null ? "?" : r.path.get(0)[1]));
    }

    static void longLeg() {
        List<int[]> path = new java.util.ArrayList<>();
        for (int x = 0; x <= 12; x++) path.add(new int[]{x, 65, 0, AgentPathfinder.WALK});
        check(AgentPathfinder.longLeg(path, 0), "12-block straight is a sprint leg");
        check(!AgentPathfinder.longLeg(path.subList(0, 4), 0), "short leg is not sprint");
    }

    /** Live-like: Builder Bot on a snowy summit (snow layers on snow blocks) walking across, not up onto them. */
    static void snowLayers() {
        check(AgentPathfinder.thinName("snow") && AgentPathfinder.thinName("white_carpet")
                && AgentPathfinder.thinName("oak_slab") && AgentPathfinder.thinName("moss_carpet"), "thin names");
        check(!AgentPathfinder.thinName("snow_block") && !AgentPathfinder.thinName("powder_snow"), "full/hazard snow is not thin");
        check(Math.abs(AgentPathfinder.surfaceHeight("snow", 1, true)) < 1e-9, "1-layer snow has no collision (0.7.3)");
        check(Math.abs(AgentPathfinder.surfaceHeight("white_carpet") - 0.0625) < 1e-9, "carpet is 1/16");
        check(Math.abs(AgentPathfinder.surfaceHeight("oak_slab", 1, true) - 0.5) < 1e-9, "bottom slab is 0.5");
        check(AgentPathfinder.thinPassable("snow", 1, true) && AgentPathfinder.thinPassable("snow", 8, true), "8-layer snow is 0.875 thin (0.7.3)");

        MapGrid g = new MapGrid(-64, 320);
        // (8,110,-16.8) analog: snow_block at 109, 1-layer snow at 110, air above.
        g.fillFloor(0, 30, -20, -10, 109, "snow_block");
        for (int x = 0; x <= 30; x++) for (int z = -20; z <= -10; z++) g.set(x, 110, z, "snow");
        check(g.open(8, 110, -16) && g.open(8, 111, -16) && g.floor(8, 109, -16), "snow layer is passable over snow_block");
        check(g.standable(8, 110, -16), "standing in the snow-layer cell");
        check(Math.abs(g.standY(8, 110, -16) - 110.0) < 1e-9, "stand Y is y (1 layer = air), not y+1: " + g.standY(8, 110, -16));
        check(!g.standable(8, 111, -16), "must not treat 1-layer snow as a full block to climb");

        AgentPathfinder.Result r = AgentPathfinder.plan(8, 110, -16, 24, 110, -16, g, new AgentPathfinder.Options());
        check(r.fail == null && r.path.size() >= 10, "snow-layer path: " + r.fail + " n=" + (r.path == null ? 0 : r.path.size()));
        for (int[] p : r.path) {
            check(p[1] == 110, "snow path stayed at feet Y=110, not 111: " + p[0] + "," + p[1] + "," + p[2]);
            check(g.open(p[0], p[1], p[2]), "path cell is walkable snow/air");
        }
        double moved = AgentNavigation.simulateTravel(r.path, 8.0, 110.0, -16.8, 40, g);
        check(moved > 5, "snow-layer follower must walk more than a jitter: " + moved);
        // Cell (8,-16) is x[8,9) z[-16,-15); feet at y on 1-layer snow is already on the waypoint.
        check(AgentNavigation.reachedWaypoint(8.4, 110.0, -15.6, new int[]{8, 110, -16, AgentPathfinder.WALK}, g.standY(8, 110, -16)),
                "first snow waypoint is reachable at y");
        check(!AgentNavigation.reachedWaypoint(8.4, 110.0, -18.5, new int[]{8, 110, -16, AgentPathfinder.WALK}, 110.0),
                "far XZ is not a reached snow waypoint");
    }

    /** A 100-block planned path must actually advance the body, not sit at 0.39 blocks. */
    static void longWalk() {
        MapGrid g = new MapGrid(-64, 320);
        g.fillFloor(0, 100, -2, 2, 64, "stone");
        AgentPathfinder.Options opt = new AgentPathfinder.Options();
        opt.segment = 128;
        AgentPathfinder.Result r = AgentPathfinder.plan(0, 65, 0, 100, 65, 0, g, opt);
        check(r.fail == null, "100-block plan: " + r.fail);
        check(r.path.size() >= 100, "100-block path waypoints: " + r.path.size());
        double moved = AgentNavigation.simulateTravel(r.path, 0.5, 65, 0.5, 40, g);
        check(moved > 5, "NPC must move >5 blocks along a 100-block path, got " + moved);
        List<int[]> constructed = new java.util.ArrayList<>();
        for (int x = 0; x <= 100; x++) constructed.add(new int[]{x, 65, 0, AgentPathfinder.WALK});
        double along = AgentNavigation.simulateTravel(constructed, 0.5, 65, 0.5, 30);
        check(along > 5, "constructed 100-block walk >5, got " + along);
    }

    static void stuckClock() {
        long now = 1_000_000L;
        check(!AgentNavigation.stuckDue(now, now - 9_000, 0, AgentNavigation.STUCK_MS),
                "stuck timer must not run before a drive is attempted");
        check(!AgentNavigation.stuckDue(now, now - 500, now - 3_000, AgentNavigation.STUCK_MS),
                "progress after drive is not stuck");
        check(AgentNavigation.stuckDue(now, now - 3_000, now - 3_000, AgentNavigation.STUCK_MS),
                "no progress for 2s after driving is stuck");
        check(!AgentNavigation.stuckDue(now, now - 3_000, now - 500, AgentNavigation.STUCK_MS),
                "replan restarts the drive clock without wiping lastProgressAt");
        check(AgentNavigation.stuckDue(now, now - 4_000, now - 4_000, AgentNavigation.STALL_MS),
                "3s with no net displacement is a stall");
    }

    /** Forested hillside: 1-deep dip under spruce leaves must not be a DROP shortcut. */
    static void pocketLeaves() {
        check(AgentPathfinder.foliageName("spruce_leaves") && AgentPathfinder.foliageName("oak_log"), "foliage names");
        check(AgentPathfinder.diggableName("oak_leaves") && !AgentPathfinder.diggableName("oak_log"),
                "leaves are tunnelable, logs are not");
        MapGrid g = new MapGrid(-64, 320);
        g.fillFloor(-4, 4, -4, 4, 64, "dirt");
        g.set(0, 64, 1, "air");
        g.set(0, 63, 1, "dirt");
        g.set(0, 66, 1, "spruce_leaves");
        check(g.standable(0, 65, 0) && g.standable(0, 65, 2), "ridge stands");
        check(g.standable(0, 64, 1), "dip under canopy is standable");
        check(!AgentPathfinder.headroomOpen(g, 0, 66, 1), "leaves block jump headroom");
        check(AgentPathfinder.pocketCell(g, 0, 64, 1), "dip with blocked jump is a pocket");
        check(!AgentPathfinder.pocketCell(g, 0, 65, 0), "open ridge is not a pocket");
        AgentPathfinder.Options opt = new AgentPathfinder.Options();
        opt.dig = false; opt.build = false;
        AgentPathfinder.Result r = AgentPathfinder.plan(0, 65, 0, 0, 65, 2, g, opt);
        check(r.fail == null, "ridge-to-ridge around a pocket: " + r.fail);
        for (int[] p : r.path)
            check(p[1] != 64 || p[2] != 1, "must not DROP into the leaf pocket: " + kinds(r.path));
        opt.banned.add(AgentPathfinder.pack(1, 65, 1));
        AgentPathfinder.Result banned = AgentPathfinder.plan(0, 65, 0, 2, 65, 0, g, opt);
        if (banned.path != null)
            for (int[] p : banned.path)
                check(!(p[0] == 1 && p[1] == 65 && p[2] == 1) || p == banned.path.get(0),
                        "banned cell is not an intermediate: " + kinds(banned.path));
    }

    static void plants() {
        check(AgentPathfinder.walkThroughName("BROWN_MUSHROOM") && AgentPathfinder.walkThroughName("red_mushroom")
                && AgentPathfinder.walkThroughName("OAK_SAPLING") && AgentPathfinder.walkThroughName("SHORT_GRASS")
                && AgentPathfinder.walkThroughName("FERN") && AgentPathfinder.walkThroughName("poppy"),
                "plants walk through");
        check(!AgentPathfinder.walkThroughName("BROWN_MUSHROOM_BLOCK") && !AgentPathfinder.walkThroughName("MUSHROOM_STEM")
                && !AgentPathfinder.walkThroughName("OAK_LEAVES") && !AgentPathfinder.walkThroughName("STONE"),
                "solid plants stay solid");
        check(AgentPathfinder.cellOpen("BROWN_MUSHROOM", false, false, true, false),
                "mushroom is open even when Bukkit marks it solid");
        check(!AgentPathfinder.cellFloor("BROWN_MUSHROOM", false, false, true, false, 1),
                "mushroom is not a floor");
        MapGrid g = new MapGrid(-64, 320);
        g.fillFloor(-2, 6, -2, 2, 64, "stone");
        g.set(0, 65, 0, "brown_mushroom");
        g.set(1, 65, 0, "poppy");
        g.set(2, 65, 0, "oak_sapling");
        g.set(3, 65, 0, "short_grass");
        AgentPathfinder.Result r = AgentPathfinder.plan(0, 65, 0, 4, 65, 0, g, new AgentPathfinder.Options());
        check(r.fail == null && r.path.size() >= 4, "walk across plants at feet: " + r.fail);
    }

    /**
     * Helper on a 1-block dirt corner: JUMP is auto, but only cardinal, and the
     * walker delays horizontal until the hop clears the face.
     */
    static void cornerJump() {
        MapGrid g = new MapGrid(-64, 320);
        g.fillFloor(-2, 4, -2, 4, 64, "dirt");
        g.set(1, 65, 1, "dirt");
        java.util.List<int[]> jumps = new java.util.ArrayList<>();
        AgentPathfinder.walkSteps(0, 65, 0, g, false, (nx, ny, nz, move) -> {
            if (move == AgentPathfinder.JUMP) jumps.add(new int[]{nx, ny, nz});
        });
        check(jumps.stream().noneMatch(p -> p[0] == 1 && p[2] == 1),
                "walkSteps must not JUMP diagonally onto a corner mound: "
                        + java.util.Arrays.deepToString(jumps.toArray()));
        java.util.List<int[]> side = new java.util.ArrayList<>();
        AgentPathfinder.walkSteps(1, 65, 0, g, false, (nx, ny, nz, move) -> {
            if (move == AgentPathfinder.JUMP) side.add(new int[]{nx, ny, nz});
        });
        check(side.stream().anyMatch(p -> p[0] == 1 && p[1] == 66 && p[2] == 1),
                "cardinal JUMP onto the 1-block step is allowed: "
                        + java.util.Arrays.deepToString(side.toArray()));
        check(Math.abs(AgentNavigation.jumpForwardScale(64, 65) - AgentNavigation.JUMP_FORWARD) < 1e-9,
                "scale 0.2 at 64→65");
        check(Math.abs(AgentNavigation.jumpForwardScale(64.4, 65) - AgentNavigation.JUMP_FORWARD) < 1e-9,
                "still scaled at 0.6 below the lip");
        check(AgentNavigation.jumpForwardScale(64.5, 65) == 1, "full forward once above the face");
        check(AgentNavigation.jumpForwardScale(65, 65) == 1, "same-level walk is unscaled");
        check(AgentNavigation.jumpForwardScale(65, 64) == 1, "drop is unscaled");
        AgentPathfinder.Options noBuild = new AgentPathfinder.Options();
        noBuild.dig = false;
        noBuild.build = false;
        AgentPathfinder.Result r = AgentPathfinder.plan(0, 65, 0, 1, 66, 1, g, noBuild);
        check(r.fail == null, "corner mound is reached by walk then cardinal jump: " + r.fail + " " + r.note);
        for (int i = 1; i < r.path.size(); i++) {
            int[] a = r.path.get(i - 1), b = r.path.get(i);
            if (b[3] == AgentPathfinder.JUMP)
                check(a[0] == b[0] || a[2] == b[2], "JUMP is cardinal: " + kinds(r.path));
        }
        check(r.path.size() >= 3, "walk then cardinal jump, not a diagonal hop: " + kinds(r.path));
        check(r.path.stream().anyMatch(p -> p[3] == AgentPathfinder.JUMP),
                "1-block corner auto-jumps: " + kinds(r.path));
        int[] last = r.path.get(r.path.size() - 1);
        check(last[0] == 1 && last[1] == 66 && last[2] == 1, "ends on the mound: " + kinds(r.path));
        double moved = AgentNavigation.simulateTravel(r.path, 0.5, 65, 0.5, 80, g);
        check(moved > 0.5, "corner hop still travels: " + moved);
    }

    /** Short hop to a chest behind cobble: walk the gap, do not exhaust 20k as no_path. */
    static void chestAround() {
        check(!AgentPathfinder.keepAround(true, false, 3, 5, 20), "3-expand stub is not a path");
        check(AgentPathfinder.keepAround(true, false, 80, 4, 12), "budget miss that closed is an approach");
        check(AgentPathfinder.keepAround(false, false, 200, 3, 10), "finished search keeps around");
        check(AgentPathfinder.keepAround(true, true, 3, 4, 20), "far budget miss keeps around");
        check(!AgentPathfinder.keepAround(true, false, 80, 9, 12), "still-far budget miss is not around");
        MapGrid g = new MapGrid(-64, 320);
        g.fillFloor(-4, 10, -4, 8, 64, "dirt");
        g.set(3, 65, 0, "chest");
        for (int z = -2; z <= 2; z++) {
            g.set(2, 65, z, "cobblestone");
            g.set(2, 66, z, "cobblestone");
        }
        AgentPathfinder.Options noBuild = new AgentPathfinder.Options();
        noBuild.dig = false;
        noBuild.build = false;
        AgentPathfinder.Result r = AgentPathfinder.plan(0, 65, 0, 3, 65, 0, g, noBuild);
        check(r.fail == null, "short chest behind cobble is walked around: " + r.fail + " " + r.note + " expands=" + r.expands);
        check(r.expands < AgentPathfinder.MAX_EXPAND, "short chest must not exhaust 20k: " + r.expands);
        check(r.path.stream().anyMatch(p -> p[2] >= 3 || p[2] <= -3),
                "around-path uses the open side: " + kinds(r.path));
    }

    /**
     * fb-23: chest inside a spawn-protected village hut. Closed spruce door must
     * be a walk, not a 20k no_path against cobble walls.
     */
    static void villageHut() {
        check(AgentPathfinder.doorWalkName("spruce_door") && AgentPathfinder.doorWalkName("oak_fence_gate"),
                "wood doors/gates are walkable");
        check(!AgentPathfinder.doorWalkName("iron_door"), "iron door stays closed");
        check(AgentPathfinder.cellOpen("spruce_door", false, false, true, false), "closed wood door is open for A*");
        check(AgentPathfinder.searchWindow(3, false, 48) == 24, "3-block hop stays local");
        check(AgentPathfinder.searchWindow(20, false, 48) == 36, "20-block hop is cheb+16");
        check(AgentPathfinder.searchWindow(60, true, 48) == 48, "far hop is a segment");
        check(AgentDoors.handOpen("SPRUCE_DOOR") && !AgentDoors.handOpen("IRON_DOOR"), "hand-open wood only");
        check(AgentDoors.mayClose(false) && !AgentDoors.mayClose(true), "villagers do not slam spawn-protect doors");
        check(AgentDoors.swing("oak_door") && AgentDoors.swing("oak_fence_gate") && !AgentDoors.swing("oak_trapdoor"),
                "close doors/gates, not trapdoors");
        check(!AgentDoors.leftBehind(0, 87, 0, 0, 87, 0) && !AgentDoors.leftBehind(1, 87, 0, 0, 87, 0),
                "in or beside the doorway is still passing");
        check(AgentDoors.leftBehind(2, 87, 0, 0, 87, 0), "two steps away is behind the door");

        MapGrid g = new MapGrid(-64, 320);
        g.fillFloor(-2, 8, -4, 4, 64, "dirt");
        for (int z = -2; z <= 2; z++) {
            g.set(2, 65, z, z == 0 ? "spruce_door" : "cobblestone");
            g.set(2, 66, z, z == 0 ? "spruce_door" : "cobblestone");
            g.prot.add("2," + z);
        }
        for (int x = 3; x <= 5; x++) {
            g.set(x, 65, -2, "cobblestone");
            g.set(x, 66, -2, "cobblestone");
            g.set(x, 65, 2, "cobblestone");
            g.set(x, 66, 2, "cobblestone");
            g.prot.add(x + ",-2");
            g.prot.add(x + ",2");
        }
        g.set(5, 65, -1, "cobblestone");
        g.set(5, 66, -1, "cobblestone");
        g.set(5, 65, 0, "cobblestone");
        g.set(5, 66, 0, "cobblestone");
        g.set(5, 65, 1, "cobblestone");
        g.set(5, 66, 1, "cobblestone");
        g.set(4, 65, 0, "chest");
        for (int x = -1; x <= 6; x++) for (int z = -3; z <= 3; z++) g.prot.add(x + "," + z);
        AgentPathfinder.Options noBuild = new AgentPathfinder.Options();
        noBuild.dig = false;
        noBuild.build = false;
        AgentPathfinder.Result r = AgentPathfinder.plan(0, 65, 0, 4, 65, 0, g, noBuild);
        check(r.fail == null, "village hut chest is reached through the door: " + r.fail + " " + r.note + " expands=" + r.expands);
        check(r.expands < AgentPathfinder.MAX_EXPAND, "hut must not exhaust 20k: " + r.expands);
        check(r.path.stream().anyMatch(p -> p[0] == 2 && p[2] == 0),
                "path uses the door cell: " + kinds(r.path));

        MapGrid sealed = new MapGrid(-64, 320);
        sealed.fillFloor(-20, 20, -20, 20, 64, "dirt");
        for (int z = -2; z <= 2; z++) {
            sealed.set(2, 65, z, "cobblestone");
            sealed.set(2, 66, z, "cobblestone");
        }
        AgentPathfinder.Result miss = AgentPathfinder.plan(0, 65, 0, 4, 65, 0, sealed, noBuild);
        check(miss.expands < AgentPathfinder.MAX_EXPAND,
                "short sealed dest must not flood 20k: " + miss.expands + " " + miss.fail);
    }

    /**
     * fb-24: door on a podium opening onto a crater. No exterior stoop, spawn
     * protection cannot place one. A* fails; STOOP steps into the doorway.
     */
    static void craterStoop() {
        check(AgentPathfinder.STOOP == 8, "STOOP move id");
        MapGrid g = new MapGrid(-64, 320);
        g.fillFloor(-6, 8, -8, 6, 64, "dirt");
        for (int x = 1; x <= 3; x++) for (int z = -2; z <= -1; z++) {
            g.set(x, 64, z, "air");
            g.set(x, 63, z, "air");
        }
        g.fillFloor(1, 4, 0, 2, 65, "cobblestone");
        for (int z = 0; z <= 2; z++) {
            g.set(1, 66, z, "cobblestone");
            g.set(1, 67, z, "cobblestone");
            g.set(4, 66, z, "cobblestone");
            g.set(4, 67, z, "cobblestone");
        }
        for (int x = 1; x <= 4; x++) {
            g.set(x, 66, 2, "cobblestone");
            g.set(x, 67, 2, "cobblestone");
            if (x != 2) {
                g.set(x, 66, 0, "cobblestone");
                g.set(x, 67, 0, "cobblestone");
            }
        }
        g.set(2, 66, 0, "spruce_door");
        g.set(2, 67, 0, "spruce_door");
        g.set(3, 66, 1, "chest");
        AgentPathfinder.Options noBuild = new AgentPathfinder.Options();
        noBuild.dig = false;
        noBuild.build = false;
        check(AgentPathfinder.canStoop(0, 65, -3, 2, 66, 0, g), "close crater start can stoop into the door");
        check(!AgentPathfinder.canStoop(0, 65, -10, 2, 66, 0, g), "far start cannot stoop");
        AgentPathfinder.Result r = AgentPathfinder.plan(0, 65, -3, 2, 66, 0, g, noBuild);
        check(r.fail == null, "cratered door is a stoop, not no_path: " + r.fail + " " + r.note);
        check(r.path.get(r.path.size() - 1)[3] == AgentPathfinder.STOOP, "last move is STOOP: " + kinds(r.path));
        check(r.path.get(r.path.size() - 1)[0] == 2 && r.path.get(r.path.size() - 1)[2] == 0,
                "stoop lands in the door: " + kinds(r.path));
        AgentPathfinder.Result far = AgentPathfinder.plan(0, 65, -10, 2, 66, 0, g, noBuild);
        check(far.fail != null || (far.path != null && far.path.get(far.path.size() - 1)[3] != AgentPathfinder.STOOP),
                "far crater start does not stoop across 10 blocks");
        check(AgentNavigation.tooHighToJump(64, 66, AgentPathfinder.JUMP), "normal jump still refuses +2");
        check(!AgentNavigation.tooHighToJump(64, 66, AgentPathfinder.STOOP), "stoop may climb +2 into a door");
        check(AgentCraft.vanilla("WOODEN_SWORD") != null && AgentCraft.vanilla("wooden_pickaxe") != null,
                "wooden tools have a vanilla fallback");
        check(AgentPathfinder.canStoop(2, 66, 0, 0, 65, -3, g), "stoop exits the cratered door downward");
        AgentPathfinder.Result out = AgentPathfinder.plan(2, 66, 0, 0, 65, -3, g, noBuild);
        check(out.fail == null, "fb-27 exit is a stoop, not blocked: " + out.fail + " " + out.note);
        check(out.path.get(out.path.size() - 1)[3] == AgentPathfinder.STOOP, "exit last move is STOOP: " + kinds(out.path));
        liveVillageDoor();
    }

    /** fb-24 hut: spruce door, chest beside it, start outside. */
    static void liveVillageDoor() {
        MapGrid g = new MapGrid(-64, 320);
        g.fillFloor(-8, 8, -8, 12, 81, "dirt");
        for (int x = -5; x <= -2; x++) for (int z = 6; z <= 9; z++) {
            g.set(x, 81, z, "cobblestone");
            g.set(x, 82, z, "cobblestone");
        }
        for (int y = 76; y <= 83; y++) {
            g.set(-4, y, 6, "air");
            g.set(-5, y, 6, "air");
            g.set(-3, y, 6, "air");
        }
        g.set(-4, 81, 6, "air");
        g.set(-4, 82, 7, "cobblestone");
        for (int z = 7; z <= 9; z++) {
            g.set(-5, 83, z, "cobblestone");
            g.set(-5, 84, z, "cobblestone");
            g.set(-2, 83, z, "cobblestone");
            g.set(-2, 84, z, "cobblestone");
        }
        for (int x = -5; x <= -2; x++) {
            g.set(x, 83, 9, "cobblestone");
            g.set(x, 84, 9, "cobblestone");
            if (x != -4) {
                g.set(x, 83, 7, "cobblestone");
                g.set(x, 84, 7, "cobblestone");
            }
        }
        g.set(-4, 83, 7, "spruce_door");
        g.set(-4, 84, 7, "spruce_door");
        g.set(-3, 83, 8, "chest");
        g.set(-4, 83, 8, "air");
        g.set(-3, 83, 7, "air");
        AgentPathfinder.Options noBuild = new AgentPathfinder.Options();
        noBuild.dig = false;
        noBuild.build = false;
        check(AgentPathfinder.canStoop(0, 82, 9, -4, 83, 7, g), "Hero's stand can stoop into the spruce door");
        AgentPathfinder.Result door = AgentPathfinder.plan(0, 82, 9, -4, 83, 7, g, noBuild);
        check(door.fail == null, "fb-24 door is a stoop, not no_path: " + door.fail + " " + door.note);
        check(door.path.get(door.path.size() - 1)[3] == AgentPathfinder.STOOP,
                "fb-24 last move is STOOP: " + kinds(door.path));
        AgentPathfinder.Result chest = AgentPathfinder.plan(0, 82, 9, -3, 83, 8, g, noBuild);
        check(chest.fail == null, "fb-24 chest dest stoops to a door-adjacent stand: " + chest.fail + " " + chest.note);
        check(AgentPathfinder.canStoop(-4, 83, 7, 0, 82, 9, g), "from the doorway, stoop exits to the outside stand");
        AgentPathfinder.Result exit = AgentPathfinder.plan(-4, 83, 7, 0, 82, 9, g, noBuild);
        check(exit.fail == null, "fb-27 hut exit is a stoop: " + exit.fail + " " + exit.note);
        check(exit.path.get(exit.path.size() - 1)[3] == AgentPathfinder.STOOP, "hut exit last move is STOOP: " + kinds(exit.path));
    }

    /**
     * Helper wood-run: crater-rim pocket . West exits
     * are 2-block faces inside spawn protection (cannot dig a stair). A far west
     * goto must walk the N 1-step instead of returning a closer/around that hugs
     * the wall. Short hops already escaped; long gotos must too.
     */
    static void rimPocket() {
        MapGrid g = new MapGrid(-64, 320);
        g.fillFloor(-2, 8, -4, 4, 64, "dirt");
        g.fillFloor(-2, 8, -8, -5, 64, "dirt");
        for (int z = -3; z <= 3; z++) {
            g.set(-3, 65, z, "dirt");
            g.set(-3, 66, z, "dirt");
        }
        g.fillFloor(-50, -3, -8, 8, 66, "dirt");
        g.set(-1, 65, -6, "dirt");
        g.set(-2, 66, -6, "dirt");
        g.set(-3, 66, -6, "dirt");
        for (int x = -8; x <= 8; x++) for (int z = -8; z <= 8; z++) g.prot.add(x + "," + z);

        AgentPathfinder.Options opt = new AgentPathfinder.Options();
        opt.dig = true;
        opt.build = false;

        check(AgentPathfinder.faceTowardGoal(g, -2, 65, 0, -50, 67, 0),
                "west rim cell has a +2 face toward the wood dest");
        check(AgentPathfinder.unclimbableFace(g, -2, 65, 0, -50, 67, 0),
                "grid face-hug sees the intermediate west wall");
        check(!AgentPathfinder.unclimbableFace(-2, 65, 0, -50, 67, 0),
                "goal-only face-hug misses an intermediate wall");
        check(AgentPathfinder.progressDead(g, -2, 65, 0, -50, 67, 0),
                "west rim has no walk/jump that closes on a west dest");
        check(!AgentPathfinder.progressDead(g, -8, 67, 0, -50, 67, 0),
                "west plateau still walks toward the dest");
        check(!AgentPathfinder.faceTowardGoal(g, 2, 66, 4, 2, 67, 0),
                "1-below detour is not a face toward the goal");

        AgentPathfinder.Result far = AgentPathfinder.plan(0, 65, 0, -50, 67, 0, g, opt);
        check(far.fail == null, "long west goto from the rim: " + far.fail + " " + far.note);
        int[] end = far.path.get(far.path.size() - 1);
        check(!(end[0] >= -2 && end[0] <= 8 && end[1] == 65 && Math.abs(end[2]) <= 3),
                "must not stall in the rim pocket: " + kinds(far.path));
        check(far.path.stream().anyMatch(p -> p[2] <= -5 || p[1] >= 66),
                "long goto uses the N 1-step: " + kinds(far.path));
        for (int i = 1; i < far.path.size(); i++) {
            int dy = far.path.get(i)[1] - far.path.get(i - 1)[1];
            check(dy <= 1, "rim step " + i + " climbs " + dy + ": " + kinds(far.path));
        }

        AgentPathfinder.Result hop = AgentPathfinder.plan(0, 65, 0, -2, 65, -6, g, opt);
        check(hop.fail == null, "short hop out of the pocket: " + hop.fail + " " + hop.note);
    }

    /**
     * Scout Bot under a tree: a 1-block grass step in front with oak leaves at
     * current y+2. JUMP would hold the body in-column (jumpForwardScale 0.2) and
     * bonk the canopy. Walk out, then cardinal JUMP from open sky. Stone 1x2
     * ceilings still offer JUMP (tunnelStair).
     */
    static void canopyJump() {
        MapGrid g = new MapGrid(-64, 320);
        g.fillFloor(-3, 4, -3, 3, 64, "dirt");
        g.set(1, 65, 0, "dirt");
        g.set(0, 67, 0, "oak_leaves");
        check(g.standable(0, 65, 0) && g.standable(1, 66, 0), "tree stand and 1-up dest");
        check(g.standable(0, 65, -1) && g.standable(1, 65, -1), "open walk-around");
        check(AgentPathfinder.canopyBlocksJump(g, 0, 65, 0), "leaves at current y+2 block JUMP");
        check(!AgentPathfinder.canopyBlocksJump(g, 1, 65, -1), "open sky still jumps");
        java.util.List<int[]> jumps = new java.util.ArrayList<>();
        AgentPathfinder.walkSteps(0, 65, 0, g, false, (nx, ny, nz, move) -> {
            if (move == AgentPathfinder.JUMP) jumps.add(new int[]{nx, ny, nz});
        });
        check(jumps.stream().noneMatch(p -> p[0] == 1 && p[1] == 66 && p[2] == 0),
                "walkSteps must not JUMP under a leaf canopy: "
                        + java.util.Arrays.deepToString(jumps.toArray()));
        AgentPathfinder.Options noDig = new AgentPathfinder.Options();
        noDig.dig = false;
        noDig.build = false;
        AgentPathfinder.Result r = AgentPathfinder.plan(0, 65, 0, 1, 66, 0, g, noDig);
        check(r.fail == null, "tree-under dest is reached by walking out: " + r.fail + " " + r.note);
        check(r.path.stream().noneMatch(p -> p[0] == 0 && p[1] == 66 && p[3] == AgentPathfinder.JUMP),
                "must not JUMP from the canopied cell: " + kinds(r.path));
        boolean jumpedFromOpen = false;
        for (int i = 1; i < r.path.size(); i++) {
            int[] a = r.path.get(i - 1), b = r.path.get(i);
            if (b[3] == AgentPathfinder.JUMP)
                check(!AgentPathfinder.canopyBlocksJump(g, a[0], a[1], a[2]),
                        "JUMP leaves a canopied cell: " + kinds(r.path));
            if (b[3] == AgentPathfinder.JUMP && (a[0] != 0 || a[2] != 0)) jumpedFromOpen = true;
        }
        check(jumpedFromOpen || r.path.stream().anyMatch(p -> p[0] == 1 && p[1] == 66),
                "walks out then climbs the 1-up: " + kinds(r.path));
        int[] end = r.path.get(r.path.size() - 1);
        check(end[0] == 1 && end[1] == 66 && end[2] == 0, "ends on the 1-up: " + kinds(r.path));
        double moved = AgentNavigation.simulateTravel(r.path, 0.5, 65, 0.5, 120, g);
        check(moved > 0.5, "canopy walk-around follower must move: " + moved);

        MapGrid stair = new MapGrid(-64, 320);
        for (int x = 0; x <= 2; x++) {
            int stand = 10 + x;
            stair.set(x, stand, 0, "air");
            stair.set(x, stand + 1, 0, "air");
            stair.set(x, stand - 1, 0, "stone");
        }
        stair.set(0, 12, 0, "stone");
        check(!AgentPathfinder.canopyBlocksJump(stair, 0, 10, 0),
                "stone 1x2 ceiling is not a foliage canopy");
        java.util.List<int[]> stairJumps = new java.util.ArrayList<>();
        AgentPathfinder.walkSteps(0, 10, 0, stair, false, (nx, ny, nz, move) -> {
            if (move == AgentPathfinder.JUMP) stairJumps.add(new int[]{nx, ny, nz});
        });
        check(stairJumps.stream().anyMatch(p -> p[0] == 1 && p[1] == 11),
                "1x2 stair still JUMP: " + java.util.Arrays.deepToString(stairJumps.toArray()));
    }

    static String kinds(List<int[]> path) {
        if (path == null) return "null";
        StringBuilder s = new StringBuilder();
        for (int[] p : path) s.append(p[0]).append(',').append(p[1]).append(',').append(p[2]).append('@').append(p[3]).append(' ');
        return s.toString();
    }

    static void check(boolean ok, String message) {
        if (!ok) throw new AssertionError(message);
    }
}
