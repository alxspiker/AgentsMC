package dev.agentsmc;

import java.util.List;
import java.util.Map;
import java.util.Set;

/** 0.7.7: scan metadata, edge hints, summary and recent block changes. */
public final class ScanTest {
    static void check(boolean ok, String m) { if (!ok) throw new AssertionError(m); }
    public static void main(String[] args) {
        check(AgentScan.edgeDirections(1, 0, 0, 16).isEmpty(), "interior");
        check(AgentScan.edgeDirections(16, 0, 0, 16).equals(Set.of("east")), "east edge");
        check(AgentScan.edgeDirections(-11, 0, -11, 16).containsAll(Set.of("west", "north")), "diagonal edge");
        check(AgentScan.edgeDirections(0, -15, 0, 16).equals(Set.of("down")), "down edge");

        Map<String, Object> m = AgentScan.meta(10, 64, -5, 16, -64, 320, 1234, 50, 20, true, Set.of("east"), 20, 0);
        Map<?, ?> b = (Map<?, ?>) m.get("bounds");
        check(b.get("minX").equals(-6) && b.get("maxX").equals(26) && b.get("minY").equals(48) && b.get("maxY").equals(80)
                && b.get("minZ").equals(-21) && b.get("maxZ").equals(11), "bounds: " + b);
        check(m.get("radius").equals(16) && m.get("blocksChecked").equals(1234L) && m.get("total").equals(50)
                && m.get("returned").equals(20) && m.get("truncated").equals(true), "meta: " + m);
        String hint = String.valueOf(m.get("hint"));
        check(hint.contains("results cut off") && hint.contains("move toward east") && hint.contains("limit"), "hint: " + hint);
        check(AgentScan.meta(0, 64, 0, 8, -64, 320, 10, 2, 2, false, Set.of(), 20, 0).get("hint") == null, "no hint when complete");
        check(String.valueOf(AgentScan.meta(0, 64, 0, 8, -64, 320, 10, 2, 2, false, Set.of("north"), 20, 3).get("hint")).contains("raise radius"), "edge hint");
        check(AgentScan.bounds(0, 315, 0, 16, -64, 320).get("maxY").equals(319), "clip to world");

        List<Map<String, Object>> s = AgentScan.summary(Map.of("STONE", 500, "DIRT", 80, "GRASS_BLOCK", 30), 2);
        check(s.size() == 2 && s.get(0).get("material").equals("STONE") && s.get(1).get("count").equals(80), "summary: " + s);

        AgentBlockLog.clear();
        long now = 10_000_000L;
        AgentBlockLog.record("world", 5, 64, 0, "placed", "COBBLESTONE", "Haley", now - 1000);
        AgentBlockLog.record("world", 6, 64, 0, "placed", "COBBLESTONE", "Haley", now - 500);
        AgentBlockLog.record("world", 200, 64, 0, "broken", "STONE", "Far", now);
        AgentBlockLog.record("world_nether", 5, 64, 0, "placed", "COBBLESTONE", "Other", now);
        AgentBlockLog.record("world", 5, 65, 0, "placed", "DIRT", "Old", now - AgentBlockLog.WINDOW_MS - 1);
        Map<String, Object> r = AgentBlockLog.near("world", 0, 64, 0, 16, 20, now);
        List<?> ch = (List<?>) r.get("changes");
        check(ch.size() == 2 && ((Map<?, ?>) ch.get(0)).get("x").equals(6) && ((Map<?, ?>) ch.get(0)).get("by").equals("Haley")
                && ((Map<?, ?>) ch.get(0)).get("block").equals("COBBLESTONE"), "recent newest first: " + ch);
        check(AgentBlockLog.near("world", 0, 64, 0, 16, 1, now).get("truncated").equals(true), "recent truncated");
        for (int i = 0; i < AgentBlockLog.KEEP + 50; i++) AgentBlockLog.record("world", i, 0, 0, "placed", "DIRT", "x", now);
        check(AgentBlockLog.size() == AgentBlockLog.KEEP, "bounded");
        AgentBlockLog.clear();
        System.out.println("PASS: scan bounds/meta/edge hints, summary, recent block log (window, world, cap), 0.7.7");
    }
}
