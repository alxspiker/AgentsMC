package dev.agentsmc;

import java.util.List;
import java.util.Map;

import com.google.gson.JsonObject;

/** Offline survey / worldmap: enclosed rooms, doors, objects, unknown ≠ blocked. */
public final class SurveyTest {
    public static void main(String[] args) {
        check(AgentActionCatalog.advertised("survey") && AgentActionCatalog.advertised("worldmap"),
                "survey and worldmap advertised");
        check(!AgentActionCatalog.advertised("command"), "command stays off");
        check(AgentActionProgram.concurrentSafeAction("survey")
                && AgentActionProgram.concurrentSafeAction("worldmap"), "observe-class concurrent");

        hillsideRoom();
        outdoorField();
        oneBlockHole();
        chestBehindWall();
        unknownFrontier();
        passage();
        persistRoute();
        documentedFields();
        shortExtension();

        if (!AgentSurvey.nearbyForSurvey(10, 0, -12, 8) || AgentSurvey.nearbyForSurvey(17, 0, 0, 8) || AgentSurvey.nearbyForSurvey(0, 20, 0, 8))
            throw new AssertionError("fb-22: survey players window is radius+8 horizontal, 16 vertical");
        System.out.println("PASS: survey enclosed room/door/objects, headroom grid, incomplete excavation warning, 1-block hole is not an exit, chest behind wall stays out, unknown ≠ blocked, worldmap persist+route, 0.7.3");
    }

    /** 5x5 stone room, spruce door to grass, chest + crafting table inside. */
    static void hillsideRoom() {
        PathfinderTest.MapGrid g = boxedRoom();
        g.set(2, 65, -1, "chest");
        g.set(-2, 65, -1, "crafting_table");
        g.set(1, 65, 1, "red_bed");
        g.set(-2, 66, 2, "oak_wall_sign");
        Map<String, Object> s = AgentSurvey.survey(g, 0, 65, 0, 8, 63, 71);
        check("enclosed_room".equals(s.get("environment")), "hillside environment: " + s.get("environment"));
        @SuppressWarnings("unchecked")
        Map<String, Object> room = (Map<String, Object>) s.get("room");
        check(Integer.valueOf(65).equals(room.get("floorY")), "floorY: " + room.get("floorY"));
        check(Integer.valueOf(68).equals(room.get("ceilingY")), "ceilingY: " + room.get("ceilingY"));
        @SuppressWarnings("unchecked")
        List<String> walls = (List<String>) room.get("walls");
        check(walls.contains("stone"), "stone walls: " + walls);
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> exits = (List<Map<String, Object>>) room.get("exits");
        check(exits.size() == 1, "one door exit: " + exits);
        check("door".equals(exits.get(0).get("type")), "exit type: " + exits.get(0));
        check(Boolean.TRUE.equals(exits.get(0).get("reachable")), "door reachable");
        check("outdoors".equals(exits.get(0).get("destination")), "door leads outdoors: " + exits.get(0));
        @SuppressWarnings("unchecked")
        List<Number> doorAt = (List<Number>) exits.get(0).get("position");
        check(doorAt.get(0).intValue() == 0 && doorAt.get(2).intValue() == 3, "door at 0,65,3: " + doorAt);
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> objects = (List<Map<String, Object>>) room.get("objects");
        check(hasObject(objects, "chest", 2, 65, -1), "chest in room: " + objects);
        check(hasObject(objects, "crafting_table", -2, 65, -1), "table in room: " + objects);
        check(hasObject(objects, "bed", 1, 65, 1), "bed in room: " + objects);
        check(hasObject(objects, "sign", -2, 66, 2), "sign in room: " + objects);
        check(Integer.valueOf(1).equals(s.get("knownExitCount")), "knownExitCount: " + s.get("knownExitCount"));
        check(((Number) room.get("cells")).intValue() >= 20 && ((Number) room.get("cells")).intValue() <= 30,
                "room cells stay inside the walls: " + room.get("cells"));
        @SuppressWarnings("unchecked")
        Map<String, Object> head = (Map<String, Object>) room.get("headroom");
        check(Integer.valueOf(3).equals(head.get("min")) && Integer.valueOf(3).equals(head.get("max")),
                "uniform 3-high room: " + head.get("min") + ".." + head.get("max"));
        check(Boolean.TRUE.equals(head.get("uniform")) && Boolean.FALSE.equals(head.get("unevenCeiling")),
                "finished room is uniform: " + head);
        @SuppressWarnings("unchecked")
        List<String> warn = (List<String>) head.get("warnings");
        check(warn == null || warn.isEmpty(), "no warnings on a finished room: " + warn);
        String grid = String.valueOf(head.get("grid"));
        check(grid.contains("3") && !grid.contains("2"), "headroom grid is all 3s: \n" + grid);
    }

    static void outdoorField() {
        PathfinderTest.MapGrid g = new PathfinderTest.MapGrid(-64, 320);
        g.fillFloor(-20, 20, -20, 20, 64, "grass_block");
        Map<String, Object> s = AgentSurvey.survey(g, 0, 65, 0, 8, 63, 71);
        check("outdoors".equals(s.get("environment")), "open grass is outdoors: " + s.get("environment"));
        @SuppressWarnings("unchecked")
        Map<String, Object> room = (Map<String, Object>) s.get("room");
        check(room.get("ceilingY") == null, "no ceiling outdoors: " + room.get("ceilingY"));
        @SuppressWarnings("unchecked")
        Map<String, Object> head = (Map<String, Object>) room.get("headroom");
        check(String.valueOf(head.get("grid")).contains("*"), "outdoors headroom is sky: \n" + head.get("grid"));
        check(Boolean.FALSE.equals(head.get("unevenCeiling")), "open sky is not an uneven ceiling");
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> exits = (List<Map<String, Object>>) room.get("exits");
        check(exits.isEmpty(), "no doors on a flat field: " + exits);
    }

    /** 1-block hole is not a 1x2 walk, so it is not an exit. */
    static void oneBlockHole() {
        PathfinderTest.MapGrid g = boxedRoom();
        g.set(3, 65, 0, "air");
        g.set(3, 66, 0, "stone");
        Map<String, Object> s = AgentSurvey.survey(g, 0, 65, 0, 8, 63, 71);
        @SuppressWarnings("unchecked")
        Map<String, Object> room = (Map<String, Object>) s.get("room");
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> exits = (List<Map<String, Object>>) room.get("exits");
        boolean hole = exits.stream().anyMatch(e -> {
            @SuppressWarnings("unchecked")
            List<Number> p = (List<Number>) e.get("position");
            return p.get(0).intValue() == 3 && p.get(2).intValue() == 0;
        });
        check(!hole, "1-block hole must not be an exit: " + exits);
        check(exits.size() == 1 && "door".equals(exits.get(0).get("type")), "only the door remains: " + exits);
    }

    static void chestBehindWall() {
        PathfinderTest.MapGrid g = boxedRoom();
        g.set(2, 65, -1, "chest");
        g.set(5, 65, 0, "chest");
        Map<String, Object> s = AgentSurvey.survey(g, 0, 65, 0, 8, 63, 71);
        @SuppressWarnings("unchecked")
        Map<String, Object> room = (Map<String, Object>) s.get("room");
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> objects = (List<Map<String, Object>>) room.get("objects");
        check(hasObject(objects, "chest", 2, 65, -1), "same-room chest: " + objects);
        check(!hasObject(objects, "chest", 5, 65, 0), "behind-wall chest is not in this room: " + objects);
    }

    static void unknownFrontier() {
        PathfinderTest.MapGrid field = new PathfinderTest.MapGrid(-64, 320);
        field.fillFloor(-32, 32, -16, 16, 64, "grass_block");
        field.unloaded.add("1,0");
        Map<String, Object> s = AgentSurvey.survey(field, 14, 65, 0, 8, 63, 71);
        check(Boolean.TRUE.equals(s.get("unloaded")) || s.get("unknown") != null,
                "unloaded is unknown, not blocked: " + s.get("environment") + " " + s.get("unloaded") + " " + s.get("unknown"));
        check(!"blocked".equals(s.get("environment")), "unloaded must not be presumed blocked");

        PathfinderTest.MapGrid edge = boxedRoom();
        Map<String, Object> tight = AgentSurvey.survey(edge, 0, 65, 0, 2, 63, 71);
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> unknown = (List<Map<String, Object>>) tight.get("unknown");
        check(unknown != null && !unknown.isEmpty(), "radius edge reports unknown, not a wall: " + tight.get("unknown"));
        check(Boolean.FALSE.equals(tight.get("complete")), "scan cap is incomplete: " + tight.get("complete"));
    }

    static void passage() {
        PathfinderTest.MapGrid g = new PathfinderTest.MapGrid(-64, 320);
        g.fillFloor(-4, 4, -12, 12, 64, "stone");
        for (int z = -7; z <= 7; z++) {
            g.set(-1, 65, z, "stone"); g.set(-1, 66, z, "stone");
            g.set(1, 65, z, "stone"); g.set(1, 66, z, "stone");
            g.set(0, 68, z, "stone");
        }
        for (int x = -1; x <= 1; x++) {
            g.set(x, 65, -7, "stone"); g.set(x, 66, -7, "stone");
            g.set(x, 65, 7, x == 0 ? "oak_door" : "stone");
            g.set(x, 66, 7, x == 0 ? "oak_door" : "stone");
        }
        Map<String, Object> s = AgentSurvey.survey(g, 0, 65, 0, 8, 63, 71);
        @SuppressWarnings("unchecked")
        Map<String, Object> room = (Map<String, Object>) s.get("room");
        @SuppressWarnings("unchecked")
        Map<String, Object> bounds = (Map<String, Object>) room.get("bounds");
        int w = ((Number) bounds.get("maxX")).intValue() - ((Number) bounds.get("minX")).intValue() + 1;
        int d = ((Number) bounds.get("maxZ")).intValue() - ((Number) bounds.get("minZ")).intValue() + 1;
        check(Math.min(w, d) <= 3 && Math.max(w, d) >= 5,
                "corridor footprint is long and thin: " + bounds + " env=" + s.get("environment"));
        check("passage".equals(s.get("environment")) || "enclosed_room".equals(s.get("environment")),
                "roofed corridor is passage or enclosed: " + s.get("environment") + " " + bounds);
    }

    static void persistRoute() {
        PathfinderTest.MapGrid room = boxedRoom();
        room.set(2, 65, -1, "chest");
        room.set(-2, 65, -1, "crafting_table");
        Map<String, Object> inside = AgentSurvey.survey(room, 0, 65, 0, 8, 63, 71);
        String json = AgentWorldMap.remember("", "world", inside);
        check(AgentWorldMap.roomCount(json) == 1, "first survey stores a room");

        PathfinderTest.MapGrid field = new PathfinderTest.MapGrid(-64, 320);
        field.fillFloor(-20, 20, -20, 20, 64, "grass_block");
        for (int x = -3; x <= 3; x++) for (int z = -3; z <= 3; z++) {
            field.set(x, 65, z, "stone"); field.set(x, 66, z, "stone"); field.set(x, 68, z, "stone");
        }
        for (int x = -2; x <= 2; x++) for (int z = -2; z <= 2; z++) {
            field.set(x, 65, z, "air"); field.set(x, 66, z, "air");
        }
        field.set(0, 65, 3, "spruce_door"); field.set(0, 66, 3, "spruce_door");
        Map<String, Object> outside = AgentSurvey.survey(field, 0, 65, 8, 8, 63, 71);
        check("outdoors".equals(outside.get("environment")), "outside survey: " + outside.get("environment"));
        json = AgentWorldMap.remember(json, "world", outside);
        check(AgentWorldMap.roomCount(json) == 2, "second survey is a new place, not a merge: " + json);

        Map<String, Object> status = AgentWorldMap.status(json, "world", 0, 65, 0, "chest", "outdoors");
        check(Integer.valueOf(2).equals(status.get("roomCount")), "status rooms: " + status.get("roomCount"));
        check("room-1".equals(status.get("currentRoom")), "standing in the hillside room: " + status.get("currentRoom"));
        @SuppressWarnings("unchecked")
        List<String> route = (List<String>) status.get("route");
        check(Boolean.TRUE.equals(status.get("routeFound")) && route != null && route.size() >= 2,
                "chest → outdoors route: " + route + " " + status);
        check(route.contains("room-1") && (route.contains("outdoors") || route.contains("room-2")),
                "route names the room and the outside: " + route);

        String cleared = "";
        Map<String, Object> empty = AgentWorldMap.status(cleared, "world", 0, 65, 0, null, null);
        check(Integer.valueOf(0).equals(empty.get("roomCount")), "clear empties the graph");
    }

    /** Extension ceiling one block lower than the rest of the room. */
    static void shortExtension() {
        PathfinderTest.MapGrid g = boxedRoom();
        g.set(2, 67, 2, "stone");
        Map<String, Object> s = AgentSurvey.survey(g, 0, 65, 0, 8, 63, 71);
        @SuppressWarnings("unchecked")
        Map<String, Object> room = (Map<String, Object>) s.get("room");
        @SuppressWarnings("unchecked")
        Map<String, Object> head = (Map<String, Object>) room.get("headroom");
        check(Integer.valueOf(2).equals(head.get("min")) && Integer.valueOf(3).equals(head.get("max")),
                "short column drops min headroom: " + head.get("min") + ".." + head.get("max"));
        check(Boolean.TRUE.equals(head.get("unevenCeiling")), "uneven ceiling flagged: " + head);
        @SuppressWarnings("unchecked")
        List<String> warnings = (List<String>) (room.containsKey("warnings") ? room.get("warnings") : head.get("warnings"));
        check(warnings != null && warnings.stream().anyMatch(w -> w.contains("incomplete") || w.contains("short")),
                "incomplete excavation warning: " + warnings);
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> shorts = (List<Map<String, Object>>) head.get("short");
        check(shorts != null && shorts.stream().anyMatch(c -> {
            @SuppressWarnings("unchecked")
            List<Number> p = (List<Number>) c.get("position");
            return p.get(0).intValue() == 2 && p.get(2).intValue() == 2
                    && Integer.valueOf(2).equals(c.get("headroom"))
                    && Integer.valueOf(1).equals(c.get("shortBy"));
        }), "short cell at 2,65,2: " + shorts);
        String grid = String.valueOf(head.get("grid"));
        check(grid.contains("2") && grid.contains("3"), "grid shows 2 and 3: \n" + grid);
        check(Integer.valueOf(67).equals(room.get("ceilingY")), "overall ceilingY is the min: " + room.get("ceilingY"));
    }

    static void documentedFields() {
        JsonObject survey = new JsonObject();
        survey.addProperty("action", "survey");
        survey.addProperty("radius", 8.0);
        survey.addProperty("agent", "hero");
        AgentSurvivalActions.fields(survey, java.util.List.of("radius"), "action", "agent");
        check(AgentPerception.optionalInt(survey, "radius", 8, 1, 12) == 8, "survey radius");
        JsonObject map = new JsonObject();
        map.addProperty("action", "worldmap");
        map.addProperty("mode", "status");
        map.addProperty("from", "chest");
        map.addProperty("to", "outdoors");
        map.addProperty("agent", "hero");
        AgentSurvivalActions.fields(map, java.util.List.of("mode", "from", "to"), "action", "agent");
        try {
            JsonObject bad = survey.deepCopy();
            bad.addProperty("foo", 1);
            AgentSurvivalActions.fields(bad, java.util.List.of("radius"), "action", "agent");
            throw new AssertionError("unknown field accepted");
        } catch (IllegalArgumentException expected) {
            check(expected.getMessage().contains("foo"), "unknown field: " + expected.getMessage());
        }
    }

    /** Interior -2..2,-2..2; walls at ±3; spruce door at 0,65,3; ceiling 68. */
    static PathfinderTest.MapGrid boxedRoom() {
        PathfinderTest.MapGrid g = new PathfinderTest.MapGrid(-64, 320);
        g.fillFloor(-16, 16, -16, 16, 64, "stone");
        for (int x = -3; x <= 3; x++) for (int z = -3; z <= 3; z++) {
            boolean wall = x == -3 || x == 3 || z == -3 || z == 3;
            if (!wall) continue;
            g.set(x, 65, z, "stone");
            g.set(x, 66, z, "stone");
        }
        for (int x = -3; x <= 3; x++) for (int z = -3; z <= 3; z++) g.set(x, 68, z, "stone");
        g.set(0, 65, 3, "spruce_door");
        g.set(0, 66, 3, "spruce_door");
        return g;
    }

    static boolean hasObject(List<Map<String, Object>> objects, String type, int x, int y, int z) {
        for (Map<String, Object> o : objects) {
            if (!type.equals(o.get("type"))) continue;
            @SuppressWarnings("unchecked")
            List<Number> p = (List<Number>) o.get("position");
            if (p.get(0).intValue() == x && p.get(1).intValue() == y && p.get(2).intValue() == z) return true;
        }
        return false;
    }

    static void check(boolean ok, String message) {
        if (!ok) throw new AssertionError(message);
    }
}
