package dev.agentsmc;

import java.util.List;
import java.util.Map;

/** Offline build_plan preflight / verify / stair mirror. */
public final class BuildPlanTest {
    public static void main(String[] args) {
        AgentBuildPlan.Cell stair = AgentBuildPlan.parseCell("oak_stairs -16 81 -67 facing=east half=bottom");
        check(stair.x == -16 && stair.y == 81 && stair.z == -67, "parse xyz");
        check("oak_stairs".equals(stair.material) && "east".equals(stair.facing) && "bottom".equals(stair.half),
                "parse stair fields");
        List<AgentBuildPlan.Cell> plan = AgentBuildPlan.parsePlan(
                "oak_stairs -16 81 -67 facing=east half=bottom; torch -13 86 -66 facing=south");
        check(plan.size() == 2, "two-cell plan");

        AgentBuildPlan.Cell mirrored = AgentBuildPlan.mirror(stair, "x", -8, false);
        check(mirrored.x == 0 && mirrored.z == -67 && "east".equals(mirrored.facing),
                "mirror X keeps facing so twin stairs climb the same way: " + mirrored.facing + " x=" + mirrored.x);
        AgentBuildPlan.Cell flipped = AgentBuildPlan.mirror(stair, "x", -8, true);
        check(flipped.x == 0 && "west".equals(flipped.facing), "flipFacing turns east into west");

        check("exact".equals(AgentBuildPlan.arrivalKind(0.4)) && "near".equals(AgentBuildPlan.arrivalKind(1.6))
                && "out".equals(AgentBuildPlan.arrivalKind(2.5)), "arrival bands");
        check(AgentBuildPlan.bodyOverlaps(-13, 86, -66, -13, 86, -66), "feet overlap");
        check(AgentBuildPlan.bodyOverlaps(-13, 85, -66, -13, 86, -66), "head overlap");
        check(!AgentBuildPlan.bodyOverlaps(-12, 86, -66, -13, 86, -66), "beside is not overlap");

        int[] stand = AgentBuildPlan.standFor(-13, 86, -66, -13, 86, -66, plan,
                (x, y, z) -> y == 86 && Math.abs(x + 12) <= 1 && z == -65);
        check(stand != null && stand[0] == -13 && stand[1] == 86 && stand[2] == -65,
                "aside is the nearest standable neighbour (1 north of the torch): "
                + (stand == null ? "null" : stand[0] + "," + stand[1] + "," + stand[2]));

        Map<String, Object> missing = AgentBuildPlan.verifyCell(
                AgentBuildPlan.parseCell("oak_stairs -16 81 -67 facing=east half=bottom"),
                "AIR", "minecraft:air");
        check("missing".equals(missing.get("fail")) && Boolean.FALSE.equals(missing.get("ok")), "air is missing: " + missing);
        Map<String, Object> wrongFace = AgentBuildPlan.verifyCell(
                AgentBuildPlan.parseCell("oak_stairs -16 81 -67 facing=east half=bottom"),
                "oak_stairs", "minecraft:oak_stairs[facing=west,half=bottom,shape=straight]");
        check("wrong_facing".equals(wrongFace.get("fail")), "west stair is wrong_facing: " + wrongFace);
        Map<String, Object> ok = AgentBuildPlan.verifyCell(
                AgentBuildPlan.parseCell("oak_stairs -16 81 -67 facing=east half=bottom"),
                "oak_stairs", "minecraft:oak_stairs[facing=east,half=bottom,shape=straight]");
        check(Boolean.TRUE.equals(ok.get("ok")), "matching stair: " + ok);
        check("east".equals(AgentBuildPlan.dataProp("minecraft:oak_stairs[facing=east,half=bottom]", "facing")),
                "dataProp facing");

        java.util.function.IntPredicate floorOnly = i -> i == 0;
        Map<String, String> neighbors = Map.of("floor", "STONE", "north", "AIR", "south", "AIR",
                "west", "AIR", "east", "AIR", "ceiling", "AIR");
        Map<String, Object> torch = AgentBuildPlan.preflightCell(
                AgentBuildPlan.parseCell("torch -13 86 -66 facing=south"), floorOnly, "AIR", neighbors);
        check(Boolean.FALSE.equals(torch.get("supported")), "south torch on AIR north is not supported: " + torch);
        check(String.valueOf(torch.get("error")).contains("AIR"), "preflight error names AIR: " + torch);

        check(AgentActionCatalog.advertised("build_plan") && AgentActionCatalog.advertised("preflight"),
                "build_plan advertised");
        check(AgentActionProgram.concurrentSafeAction("build_plan"), "build_plan is concurrent-safe");
        System.out.println("PASS: build_plan parse/mirror/verify/aside, torch support refuse, 0.7.3");
    }

    private static void check(boolean ok, String message) {
        if (!ok) throw new AssertionError(message);
    }
}
