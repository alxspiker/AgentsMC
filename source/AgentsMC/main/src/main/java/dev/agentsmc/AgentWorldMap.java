package dev.agentsmc;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

/**
 * Persistent room-and-passage graph. Each {@code survey} merges into this map
 * on the NPC. Unknown is never stored as blocked.
 */
final class AgentWorldMap {
    static final int MAX_ROOMS = 32;

    static String remember(String prev, String world, Map<String, Object> survey) {
        JsonObject map = parse(prev);
        JsonArray rooms = map.has("rooms") && map.get("rooms").isJsonArray()
                ? map.getAsJsonArray("rooms") : new JsonArray();
        JsonObject incoming = fromSurvey(world, survey);
        if (incoming == null) return write(map, rooms);
        attachPosition(incoming, survey);
        int match = findOverlap(rooms, incoming);
        if (match >= 0) rooms.set(match, merge(rooms.get(match).getAsJsonObject(), incoming));
        else {
            if (rooms.size() >= MAX_ROOMS) rooms.remove(0);
            incoming.addProperty("id", nextId(rooms));
            rooms.add(incoming);
        }
        linkExits(rooms);
        return write(map, rooms);
    }

    static Map<String, Object> status(String json, String world, int x, int y, int z,
                                      String from, String to) {
        JsonObject map = parse(json);
        JsonArray rooms = map.has("rooms") && map.get("rooms").isJsonArray()
                ? map.getAsJsonArray("rooms") : new JsonArray();
        List<Map<String, Object>> listed = new ArrayList<>();
        List<Map<String, Object>> connections = new ArrayList<>();
        String current = null;
        int bestArea = Integer.MAX_VALUE;
        for (JsonElement el : rooms) {
            if (!el.isJsonObject()) continue;
            JsonObject room = el.getAsJsonObject();
            listed.add(summarize(room));
            String id = str(room, "id");
            if (contains(room, world, x, y, z)) {
                int area = areaOf(room);
                if (area < bestArea) { bestArea = area; current = id; }
            }
            if (!room.has("exits") || !room.get("exits").isJsonArray()) continue;
            for (JsonElement ex : room.getAsJsonArray("exits")) {
                if (!ex.isJsonObject()) continue;
                JsonObject exit = ex.getAsJsonObject();
                if (!exit.has("to")) continue;
                Map<String, Object> link = new LinkedHashMap<>();
                link.put("from", id);
                link.put("to", str(exit, "to"));
                link.put("type", str(exit, "type"));
                if (exit.has("position") && exit.get("position").isJsonArray())
                    link.put("position", ints(exit.getAsJsonArray("position")));
                connections.add(link);
            }
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("action", "worldmap");
        out.put("rooms", listed);
        out.put("roomCount", listed.size());
        if (current != null) out.put("currentRoom", current);
        out.put("connections", connections);
        if (from != null && to != null) {
            List<String> route = route(rooms, world, x, y, z, from, to);
            out.put("route", route);
            out.put("routeFound", !route.isEmpty());
        }
        return out;
    }

    static int roomCount(String json) {
        JsonObject map = parse(json);
        if (!map.has("rooms") || !map.get("rooms").isJsonArray()) return 0;
        return map.getAsJsonArray("rooms").size();
    }

    static List<String> route(String json, String world, int x, int y, int z, String from, String to) {
        JsonObject map = parse(json);
        JsonArray rooms = map.has("rooms") && map.get("rooms").isJsonArray()
                ? map.getAsJsonArray("rooms") : new JsonArray();
        return route(rooms, world, x, y, z, from, to);
    }

    private static List<String> route(JsonArray rooms, String world, int x, int y, int z,
                                      String from, String to) {
        String start = resolve(rooms, world, x, y, z, from);
        String goal = resolve(rooms, world, x, y, z, to);
        if (start == null || goal == null) return List.of();
        if (start.equals(goal)) return List.of(start);
        Map<String, List<String>> adj = new LinkedHashMap<>();
        for (JsonElement el : rooms) {
            if (!el.isJsonObject()) continue;
            JsonObject room = el.getAsJsonObject();
            String id = str(room, "id");
            adj.computeIfAbsent(id, k -> new ArrayList<>());
            if (!room.has("exits") || !room.get("exits").isJsonArray()) continue;
            for (JsonElement ex : room.getAsJsonArray("exits")) {
                if (!ex.isJsonObject()) continue;
                JsonObject exit = ex.getAsJsonObject();
                String dest = exit.has("to") ? str(exit, "to") : str(exit, "destination");
                if (dest == null || dest.isBlank() || dest.equals("unknown")) continue;
                adj.computeIfAbsent(id, k -> new ArrayList<>()).add(dest);
                adj.computeIfAbsent(dest, k -> new ArrayList<>());
            }
        }
        ArrayDeque<String> q = new ArrayDeque<>();
        Map<String, String> prev = new LinkedHashMap<>();
        q.add(start);
        prev.put(start, start);
        while (!q.isEmpty()) {
            String cur = q.removeFirst();
            if (cur.equals(goal)) break;
            for (String n : adj.getOrDefault(cur, List.of())) {
                if (prev.containsKey(n)) continue;
                prev.put(n, cur);
                q.add(n);
            }
        }
        if (!prev.containsKey(goal)) return List.of();
        ArrayList<String> path = new ArrayList<>();
        for (String at = goal; at != null; at = prev.get(at)) {
            path.add(0, at);
            if (at.equals(start)) break;
        }
        return path;
    }

    private static String resolve(JsonArray rooms, String world, int x, int y, int z, String token) {
        if (token == null || token.isBlank()) return null;
        String t = token.trim().toLowerCase();
        if (t.equals("here")) {
            String best = null;
            int bestArea = Integer.MAX_VALUE;
            for (JsonElement el : rooms) {
                if (!el.isJsonObject()) continue;
                JsonObject room = el.getAsJsonObject();
                if (!contains(room, world, x, y, z)) continue;
                int area = areaOf(room);
                if (area < bestArea) { bestArea = area; best = str(room, "id"); }
            }
            return best;
        }
        if (t.equals("outdoors") || t.equals("outside")) {
            for (JsonElement el : rooms) {
                if (!el.isJsonObject()) continue;
                JsonObject room = el.getAsJsonObject();
                if ("outdoors".equals(str(room, "environment"))) return str(room, "id");
            }
            return "outdoors";
        }
        for (JsonElement el : rooms) {
            if (!el.isJsonObject()) continue;
            JsonObject room = el.getAsJsonObject();
            if (t.equalsIgnoreCase(str(room, "id"))) return str(room, "id");
            if (hasObjectOrExit(room, t)) return str(room, "id");
        }
        return null;
    }

    private static boolean hasObjectOrExit(JsonObject room, String type) {
        for (String key : List.of("objects", "exits")) {
            if (!room.has(key) || !room.get(key).isJsonArray()) continue;
            for (JsonElement el : room.getAsJsonArray(key)) {
                if (el.isJsonObject() && type.equalsIgnoreCase(str(el.getAsJsonObject(), "type")))
                    return true;
            }
        }
        return false;
    }

    @SuppressWarnings("unchecked")
    private static JsonObject fromSurvey(String world, Map<String, Object> survey) {
        if (survey == null) return null;
        Object roomObj = survey.get("room");
        if (!(roomObj instanceof Map<?, ?> roomMap)) return null;
        JsonObject room = new JsonObject();
        if (world != null) room.addProperty("world", world);
        Object env = survey.get("environment");
        if (env != null) room.addProperty("environment", String.valueOf(env));
        putInt(room, "floorY", roomMap.get("floorY"));
        putInt(room, "ceilingY", roomMap.get("ceilingY"));
        Object bounds = roomMap.get("bounds");
        if (bounds instanceof Map<?, ?> b) {
            JsonObject jb = new JsonObject();
            for (String k : List.of("minX", "maxX", "minY", "maxY", "minZ", "maxZ"))
                putInt(jb, k, b.get(k));
            room.add("bounds", jb);
        }
        JsonArray walls = new JsonArray();
        if (roomMap.get("walls") instanceof List<?> list)
            for (Object w : list) if (w != null) walls.add(String.valueOf(w));
        room.add("walls", walls);
        room.add("objects", copyPoints((List<Map<String, Object>>) asMaps(roomMap.get("objects"))));
        room.add("exits", copyExits((List<Map<String, Object>>) asMaps(roomMap.get("exits"))));
        return room;
    }

    private static JsonArray copyPoints(List<Map<String, Object>> items) {
        JsonArray out = new JsonArray();
        if (items == null) return out;
        for (Map<String, Object> item : items) {
            JsonObject o = new JsonObject();
            if (item.get("type") != null) o.addProperty("type", String.valueOf(item.get("type")));
            o.add("position", jsonInts(item.get("position")));
            out.add(o);
        }
        return out;
    }

    private static JsonArray copyExits(List<Map<String, Object>> items) {
        JsonArray out = new JsonArray();
        if (items == null) return out;
        for (Map<String, Object> item : items) {
            JsonObject o = new JsonObject();
            if (item.get("type") != null) o.addProperty("type", String.valueOf(item.get("type")));
            o.add("position", jsonInts(item.get("position")));
            if (item.get("destination") != null)
                o.addProperty("destination", String.valueOf(item.get("destination")));
            if (item.get("to") != null) o.addProperty("to", String.valueOf(item.get("to")));
            if (item.get("reachable") instanceof Boolean b) o.addProperty("reachable", b);
            out.add(o);
        }
        return out;
    }

    private static void attachPosition(JsonObject incoming, Map<String, Object> survey) {
        Object pos = survey.get("position");
        if (pos instanceof List<?> list && list.size() >= 3) {
            JsonArray a = new JsonArray();
            for (int i = 0; i < 3; i++)
                if (list.get(i) instanceof Number n) a.add(n.intValue());
            if (a.size() == 3) incoming.add("position", a);
        }
    }

    private static int findOverlap(JsonArray rooms, JsonObject incoming) {
        JsonObject a = incoming.has("bounds") && incoming.get("bounds").isJsonObject()
                ? incoming.getAsJsonObject("bounds") : null;
        String world = str(incoming, "world");
        String env = str(incoming, "environment");
        int floor = incoming.has("floorY") ? incoming.get("floorY").getAsInt() : Integer.MIN_VALUE;
        int[] here = incoming.has("position") && incoming.get("position").isJsonArray()
                ? ints(incoming.getAsJsonArray("position")) : null;
        int best = -1;
        int bestScore = 0;
        for (int i = 0; i < rooms.size(); i++) {
            if (!rooms.get(i).isJsonObject()) continue;
            JsonObject room = rooms.get(i).getAsJsonObject();
            if (world != null && room.has("world") && !world.equals(str(room, "world"))) continue;
            if (floor != Integer.MIN_VALUE && room.has("floorY")
                    && Math.abs(room.get("floorY").getAsInt() - floor) > 1) continue;
            boolean sameEnv = env == null || env.equals(str(room, "environment"));
            boolean standingIn = here != null && here.length >= 3
                    && contains(room, world, here[0], here[1], here[2]);
            JsonObject b = room.has("bounds") && room.get("bounds").isJsonObject()
                    ? room.getAsJsonObject("bounds") : null;
            int area = overlapArea(a, b);
            if (!standingIn && !sameEnv) continue;
            if (!standingIn && area <= 0) continue;
            int score = standingIn ? area + 1000 : area;
            if (score > bestScore) { bestScore = score; best = i; }
        }
        return bestScore > 0 ? best : -1;
    }

    private static int overlapArea(JsonObject a, JsonObject b) {
        if (a == null || b == null) return 0;
        int x0 = Math.max(num(a, "minX"), num(b, "minX"));
        int x1 = Math.min(num(a, "maxX"), num(b, "maxX"));
        int z0 = Math.max(num(a, "minZ"), num(b, "minZ"));
        int z1 = Math.min(num(a, "maxZ"), num(b, "maxZ"));
        if (x1 < x0 || z1 < z0) return 0;
        return (x1 - x0 + 1) * (z1 - z0 + 1);
    }

    private static JsonObject merge(JsonObject prev, JsonObject incoming) {
        JsonObject out = prev.deepCopy();
        for (String k : List.of("environment", "world", "floorY", "ceilingY")) {
            if (incoming.has(k)) out.add(k, incoming.get(k));
        }
        if (incoming.has("bounds")) out.add("bounds", incoming.get("bounds"));
        if (incoming.has("walls")) out.add("walls", incoming.get("walls"));
        out.add("objects", mergePoints(arr(prev, "objects"), arr(incoming, "objects")));
        out.add("exits", mergePoints(arr(prev, "exits"), arr(incoming, "exits")));
        if (prev.has("id")) out.add("id", prev.get("id"));
        return out;
    }

    private static JsonArray mergePoints(JsonArray prev, JsonArray incoming) {
        JsonArray out = new JsonArray();
        Set<String> seen = new LinkedHashSet<>();
        for (JsonArray src : List.of(incoming, prev)) {
            for (JsonElement el : src) {
                if (!el.isJsonObject()) continue;
                JsonObject o = el.getAsJsonObject();
                String id = str(o, "type") + ":" + posKey(o);
                if (!seen.add(id)) continue;
                out.add(o);
            }
        }
        return out;
    }

    private static void linkExits(JsonArray rooms) {
        for (JsonElement el : rooms) {
            if (!el.isJsonObject()) continue;
            JsonObject room = el.getAsJsonObject();
            String id = str(room, "id");
            if (!room.has("exits") || !room.get("exits").isJsonArray()) continue;
            for (JsonElement ex : room.getAsJsonArray("exits")) {
                if (!ex.isJsonObject()) continue;
                JsonObject exit = ex.getAsJsonObject();
                String dest = str(exit, "destination");
                if ("outdoors".equals(dest)) {
                    String other = findEnvironment(rooms, "outdoors", id);
                    if (other != null) exit.addProperty("to", other);
                    else exit.addProperty("to", "outdoors");
                    continue;
                }
                String to = matchExit(rooms, room, exit, id);
                if (to != null) exit.addProperty("to", to);
            }
        }
    }

    private static String findEnvironment(JsonArray rooms, String environment, String skip) {
        for (JsonElement el : rooms) {
            if (!el.isJsonObject()) continue;
            JsonObject room = el.getAsJsonObject();
            if (skip != null && skip.equals(str(room, "id"))) continue;
            if (environment.equals(str(room, "environment"))) return str(room, "id");
        }
        return null;
    }

    private static String matchExit(JsonArray rooms, JsonObject from, JsonObject exit, String skip) {
        int[] pos = exit.has("position") && exit.get("position").isJsonArray()
                ? ints(exit.getAsJsonArray("position")) : null;
        String dest = str(exit, "destination");
        for (JsonElement el : rooms) {
            if (!el.isJsonObject()) continue;
            JsonObject room = el.getAsJsonObject();
            String id = str(room, "id");
            if (id == null || id.equals(skip)) continue;
            if (pos != null && contains(room, str(from, "world"), pos[0], pos.length > 1 ? pos[1] : 0,
                    pos.length > 2 ? pos[2] : 0))
                return id;
            if (pos != null && nearExit(room, pos)) return id;
            if (dest != null && dest.equals(str(room, "environment")) && dest.equals("connected_room"))
                return id;
        }
        return null;
    }

    private static boolean nearExit(JsonObject room, int[] pos) {
        if (!room.has("exits") || !room.get("exits").isJsonArray()) return false;
        for (JsonElement el : room.getAsJsonArray("exits")) {
            if (!el.isJsonObject() || !el.getAsJsonObject().has("position")) continue;
            int[] other = ints(el.getAsJsonObject().getAsJsonArray("position"));
            if (other.length < 3 || pos.length < 3) continue;
            if (Math.max(Math.abs(other[0] - pos[0]), Math.abs(other[2] - pos[2])) <= 2
                    && Math.abs(other[1] - pos[1]) <= 2)
                return true;
        }
        return false;
    }

    private static int areaOf(JsonObject room) {
        if (!room.has("bounds") || !room.get("bounds").isJsonObject()) return Integer.MAX_VALUE;
        JsonObject b = room.getAsJsonObject("bounds");
        int w = Math.max(1, num(b, "maxX") - num(b, "minX") + 1);
        int d = Math.max(1, num(b, "maxZ") - num(b, "minZ") + 1);
        return w * d;
    }

    private static boolean contains(JsonObject room, String world, int x, int y, int z) {
        if (world != null && room.has("world") && !world.equals(str(room, "world"))) return false;
        if (!room.has("bounds") || !room.get("bounds").isJsonObject()) return false;
        JsonObject b = room.getAsJsonObject("bounds");
        if (x < num(b, "minX") || x > num(b, "maxX")) return false;
        if (z < num(b, "minZ") || z > num(b, "maxZ")) return false;
        if (room.has("floorY") && Math.abs(room.get("floorY").getAsInt() - y) > 3) return false;
        return true;
    }

    private static Map<String, Object> summarize(JsonObject room) {
        Map<String, Object> out = new LinkedHashMap<>();
        if (room.has("id")) out.put("id", str(room, "id"));
        if (room.has("environment")) out.put("environment", str(room, "environment"));
        if (room.has("world")) out.put("world", str(room, "world"));
        if (room.has("floorY")) out.put("floorY", room.get("floorY").getAsInt());
        if (room.has("ceilingY")) out.put("ceilingY", room.get("ceilingY").getAsInt());
        if (room.has("bounds") && room.get("bounds").isJsonObject()) {
            JsonObject b = room.getAsJsonObject("bounds");
            Map<String, Object> bounds = new LinkedHashMap<>();
            for (String k : List.of("minX", "maxX", "minY", "maxY", "minZ", "maxZ"))
                if (b.has(k)) bounds.put(k, num(b, k));
            out.put("bounds", bounds);
        }
        List<String> objects = new ArrayList<>();
        if (room.has("objects") && room.get("objects").isJsonArray())
            for (JsonElement el : room.getAsJsonArray("objects"))
                if (el.isJsonObject() && el.getAsJsonObject().has("type"))
                    objects.add(str(el.getAsJsonObject(), "type"));
        out.put("objects", objects);
        List<Map<String, Object>> exits = new ArrayList<>();
        if (room.has("exits") && room.get("exits").isJsonArray()) {
            for (JsonElement el : room.getAsJsonArray("exits")) {
                if (!el.isJsonObject()) continue;
                JsonObject e = el.getAsJsonObject();
                Map<String, Object> ex = new LinkedHashMap<>();
                if (e.has("type")) ex.put("type", str(e, "type"));
                if (e.has("destination")) ex.put("destination", str(e, "destination"));
                if (e.has("to")) ex.put("to", str(e, "to"));
                if (e.has("position") && e.get("position").isJsonArray())
                    ex.put("position", ints(e.getAsJsonArray("position")));
                exits.add(ex);
            }
        }
        out.put("exits", exits);
        return out;
    }

    private static JsonObject parse(String json) {
        if (json == null || json.isBlank() || json.equals("null")) return new JsonObject();
        try {
            JsonElement el = JsonParser.parseString(json);
            return el.isJsonObject() ? el.getAsJsonObject() : new JsonObject();
        } catch (RuntimeException e) {
            return new JsonObject();
        }
    }

    private static String write(JsonObject map, JsonArray rooms) {
        map.addProperty("version", 1);
        map.add("rooms", rooms);
        return map.toString();
    }

    private static String nextId(JsonArray rooms) {
        int max = 0;
        for (JsonElement el : rooms) {
            if (!el.isJsonObject()) continue;
            String id = str(el.getAsJsonObject(), "id");
            if (id != null && id.startsWith("room-")) {
                try { max = Math.max(max, Integer.parseInt(id.substring("room-".length()))); }
                catch (NumberFormatException ignored) {}
            }
        }
        return "room-" + (max + 1);
    }

    private static JsonArray arr(JsonObject o, String key) {
        return o.has(key) && o.get(key).isJsonArray() ? o.getAsJsonArray(key) : new JsonArray();
    }

    private static String str(JsonObject o, String key) {
        if (o == null || !o.has(key) || o.get(key).isJsonNull()) return null;
        try { return o.get(key).getAsString(); } catch (RuntimeException e) { return null; }
    }

    private static int num(JsonObject o, String key) {
        if (o == null || !o.has(key)) return 0;
        try { return o.get(key).getAsInt(); } catch (RuntimeException e) { return 0; }
    }

    private static void putInt(JsonObject o, String key, Object value) {
        if (value instanceof Number n) o.addProperty(key, n.intValue());
    }

    private static int[] ints(JsonArray a) {
        int[] out = new int[a.size()];
        for (int i = 0; i < a.size(); i++) out[i] = a.get(i).getAsInt();
        return out;
    }

    private static List<Integer> ints(int[] a) {
        List<Integer> out = new ArrayList<>();
        for (int n : a) out.add(n);
        return out;
    }

    private static JsonArray jsonInts(Object value) {
        JsonArray a = new JsonArray();
        if (value instanceof List<?> list) {
            for (Object n : list) if (n instanceof Number num) a.add(num.intValue());
        }
        return a;
    }

    private static String posKey(JsonObject o) {
        if (!o.has("position") || !o.get("position").isJsonArray()) return "";
        JsonArray a = o.getAsJsonArray("position");
        return a.toString();
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> asMaps(Object value) {
        if (!(value instanceof List<?> list)) return List.of();
        List<Map<String, Object>> out = new ArrayList<>();
        for (Object o : list) if (o instanceof Map<?, ?> m) out.add((Map<String, Object>) m);
        return out;
    }

    private AgentWorldMap() {}
}
