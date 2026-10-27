package dev.agentsmc;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** 0.7.7: scan metadata (bounds, counts, truncation), edge detection and move hints. Pure logic for tests. */
final class AgentScan {
    static final int DEFAULT_RADIUS = 16, DEFAULT_LIMIT = 20, MAX_LIMIT = 100, SUMMARY_TOP = 30;

    /** Directions (east/west/up/down/south/north) in which a match lies within one block of the scan sphere's edge. */
    static Set<String> edgeDirections(int dx, int dy, int dz, int radius) {
        Set<String> dirs = new LinkedHashSet<>();
        double d = Math.sqrt((double) dx * dx + (double) dy * dy + (double) dz * dz);
        if (d < radius - 1 - 1e-9) return dirs;
        int ax = Math.abs(dx), ay = Math.abs(dy), az = Math.abs(dz), max = Math.max(ax, Math.max(ay, az));
        if (max == 0) return dirs;
        // name every axis carrying at least half the dominant offset
        if (ax * 2 >= max && ax > 0) dirs.add(dx > 0 ? "east" : "west");
        if (az * 2 >= max && az > 0) dirs.add(dz > 0 ? "south" : "north");
        if (ay * 2 >= max && ay > 0) dirs.add(dy > 0 ? "up" : "down");
        return dirs;
    }

    static Map<String, Object> bounds(int cx, int cy, int cz, int radius, int minY, int maxY) {
        Map<String, Object> b = new LinkedHashMap<>();
        b.put("minX", cx - radius); b.put("maxX", cx + radius);
        b.put("minY", Math.max(minY, cy - radius)); b.put("maxY", Math.min(maxY - 1, cy + radius));
        b.put("minZ", cz - radius); b.put("maxZ", cz + radius);
        return b;
    }

    static String hint(boolean truncated, Set<String> edges, int radius, int limit, int unloadedColumns) {
        List<String> parts = new ArrayList<>();
        if (truncated || !edges.isEmpty()) {
            String dir = edges.isEmpty() ? "the matches" : String.join("/", edges);
            String what = truncated && !edges.isEmpty() ? "results cut off and matches reach the scan edge"
                    : truncated ? "results cut off" : "matches reach the scan edge";
            String raise = truncated ? (radius < 32 ? "raise limit (max " + MAX_LIMIT + ") or radius" : "raise limit (max " + MAX_LIMIT + ")")
                    : (radius < 32 ? "raise radius (max 32)" : "rescan from there");
            parts.add(what + "; move toward " + dir + " or " + raise + " to see more");
        }
        if (unloadedColumns > 0) parts.add(unloadedColumns + " columns were in unloaded chunks and not checked");
        return parts.isEmpty() ? null : String.join(". ", parts);
    }

    /** Standard metadata block shared by every scan result. */
    static Map<String, Object> meta(int cx, int cy, int cz, int radius, int minY, int maxY, long checked, int total, int returned,
                                    boolean truncated, Set<String> edges, int limit, int unloadedColumns) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("radius", radius);
        Map<String, Object> c = new LinkedHashMap<>(); c.put("x", cx); c.put("y", cy); c.put("z", cz); m.put("center", c);
        m.put("bounds", bounds(cx, cy, cz, radius, minY, maxY));
        m.put("shape", "sphere");
        m.put("blocksChecked", checked);
        m.put("total", total);
        m.put("returned", returned);
        m.put("limit", limit);
        m.put("truncated", truncated);
        m.put("edgeDirections", new ArrayList<>(edges));
        if (unloadedColumns > 0) m.put("unloadedColumns", unloadedColumns);
        String h = hint(truncated, edges, radius, limit, unloadedColumns);
        if (h != null) m.put("hint", h);
        return m;
    }

    /** Material counts, most common first, top N; others folded into "otherTypes". */
    static List<Map<String, Object>> summary(Map<String, Integer> counts, int top) {
        List<Map.Entry<String, Integer>> es = new ArrayList<>(counts.entrySet());
        es.sort((a, b) -> b.getValue() != a.getValue().intValue() ? b.getValue() - a.getValue() : a.getKey().compareTo(b.getKey()));
        List<Map<String, Object>> out = new ArrayList<>();
        for (var e : es.subList(0, Math.min(top, es.size()))) out.add(new LinkedHashMap<>(Map.of("material", e.getKey(), "count", e.getValue())));
        return out;
    }

    private AgentScan() {}
}
