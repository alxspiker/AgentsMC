package dev.agentsmc;

import java.util.List;
import java.util.Map;

/** fb-27: furnace touches are remembered so inspect_container can explain an emptied furnace. */
public final class FurnaceLogTest {
    public static void main(String[] args) {
        AgentFurnaceLog.clear();
        String k = AgentFurnaceLog.key("world", -44, 63, -39);
        AgentFurnaceLog.record(k, "Farmer", "fuel", "COAL", 1, 1L);
        AgentFurnaceLog.record(k, "Farmer", "smelt", "RAW_IRON", 3, 2L);
        AgentFurnaceLog.record(k, ".Haley1707", "extract_result", "IRON_INGOT", 3, 3L);
        List<Map<String, Object>> h = AgentFurnaceLog.history(k);
        if (h.size() != 3 || !"extract_result".equals(h.get(2).get("kind")) || !".Haley1707".equals(h.get(2).get("by")))
            throw new AssertionError("history order/attribution: " + h);
        for (int i = 0; i < 30; i++) AgentFurnaceLog.record(k, "hopper", "hopper_out", "IRON_INGOT", 1, 10L + i);
        if (AgentFurnaceLog.history(k).size() != AgentFurnaceLog.KEEP) throw new AssertionError("cap");
        if (!AgentFurnaceLog.history(AgentFurnaceLog.key("world", 0, 0, 0)).isEmpty()) throw new AssertionError("other furnace");
        System.out.println("PASS: furnace touch log (agent smelt/fuel, extract attribution, hopper, cap 12), 0.7.0");
    }
}
