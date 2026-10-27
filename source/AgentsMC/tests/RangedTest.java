package dev.agentsmc;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** Offline bow/crossbow aim, draw, consume, LOS, craft, and catalog contracts. */
public final class RangedTest {
    public static void main(String[] args) {
        check(AgentRanged.RANGE_MIN == 6 && AgentRanged.RANGE_MAX == 24, "band 6-24");
        check(AgentRanged.MELEE == 4, "melee switch at 4");
        check(AgentRanged.BOW_DRAW_MS == 1_000 && AgentRanged.CROSSBOW_DRAW_MS == 1_250, "draw times");
        check(AgentRanged.drawMs("BOW") == 1_000 && AgentRanged.drawMs("crossbow") == 1_250, "drawMs by weapon");
        check(AgentRanged.charge(0, "BOW") == 0 && AgentRanged.charge(500, "BOW") == 0.5, "half charge");
        check(AgentRanged.charge(1_000, "BOW") == 1 && AgentRanged.charge(2_000, "BOW") == 1, "full charge capped");
        check(AgentRanged.charge(1_250, "CROSSBOW") == 1, "crossbow full");
        check(Math.abs(AgentRanged.speed("BOW", 1) - 3.0) < 1e-9, "bow full speed");
        check(Math.abs(AgentRanged.speed("BOW", 0.5) - 0.75) < 1e-9, "bow charge^2");
        check(AgentRanged.speed("CROSSBOW", 0.99) == 0, "crossbow uncharged");
        check(Math.abs(AgentRanged.speed("CROSSBOW", 1) - 3.15) < 1e-9, "crossbow full speed");
        check(AgentRanged.drop(0) == 0 && Math.abs(AgentRanged.drop(10) - 2.5) < 1e-9, "drop 0.5*g*t^2");

        double[] still = AgentRanged.aim(0, 0, 0, 12, 0, 0, 0, 0, 0, 3.0);
        check(still[1] > 0, "gravity raises aim: vy=" + still[1]);
        check(Math.abs(Math.hypot(Math.hypot(still[0], still[1]), still[2]) - 3.0) < 1e-6, "aim is muzzle length");
        double[] strafe = AgentRanged.aim(0, 0, 0, 12, 0, 0, 0, 0, 0.4, 3.0);
        check(strafe[2] > 0.1, "lead aims ahead of a strafing target: vz=" + strafe[2]);
        double[] close = AgentRanged.aim(0, 0, 0, 12, 0, 0, -0.4, 0, 0, 3.0);
        double[] far = AgentRanged.aim(0, 0, 0, 12, 0, 0, 0.4, 0, 0, 3.0);
        check(far[1] > close[1], "receding target needs more gravity compensation");
        double[] up = AgentRanged.aim(0, 0, 0, 0, 6, 0, 0, 0, 0, 3.0);
        check(up[1] > 0, "upward aim");

        check(!AgentRanged.consumesArrow("BOW", true), "Infinity bow keeps the arrow");
        check(AgentRanged.consumesArrow("BOW", false), "plain bow consumes");
        check(AgentRanged.consumesArrow("CROSSBOW", true), "crossbow always consumes");
        check(AgentRanged.hasInfinity(List.of("minecraft:infinity")) && AgentRanged.hasInfinity(List.of("ARROW_INFINITE")), "infinity keys");
        check(!AgentRanged.hasInfinity(List.of("power", "flame")), "other enchants");

        check(AgentRanged.inBand(6) && AgentRanged.inBand(24) && !AgentRanged.inBand(5.9) && !AgentRanged.inBand(24.1), "band edges");
        check(AgentRanged.preferMelee(4) && AgentRanged.preferMelee(0) && !AgentRanged.preferMelee(4.1), "melee at 4");
        check(AgentRanged.canUse(true, 1, false, 10, true), "ready at 10 with an arrow");
        check(!AgentRanged.canUse(true, 0, true, 10, true), "Infinity still needs one arrow");
        check(!AgentRanged.canUse(true, 1, false, 5, true), "too close for ranged");
        check(!AgentRanged.canUse(true, 1, false, 10, false), "no LOS");
        check(!AgentRanged.canUse(false, 8, false, 12, true), "no weapon");

        check(AgentRanged.blockedBy(0.5, 1.5, 0.5, 10.5, 1.5, 0.5, 5, 1, 0), "wall blocks LOS");
        check(!AgentRanged.blockedBy(0.5, 1.5, 0.5, 10.5, 1.5, 0.5, 5, 5, 0), "high block misses the ray");
        check(!AgentRanged.blockedBy(0.5, 1.5, 0.5, 2.5, 1.5, 0.5, 8, 1, 0), "block past the target");

        check(AgentRanged.ARROW.yield == 4, "arrow yield 4");
        check(AgentRanged.BOW.inputs.size() == 6 && AgentRanged.BOW.inputs.contains("STRING"), "bow 3 stick + 3 string");
        check(AgentRanged.CROSSBOW.inputs.contains("TRIPWIRE_HOOK") && AgentRanged.CROSSBOW.inputs.contains("IRON_INGOT"), "crossbow extras");
        check(AgentRanged.craftSpec("minecraft:arrow") == AgentRanged.ARROW && AgentRanged.craftSpec("BOW") == AgentRanged.BOW, "craftSpec");
        check(AgentRanged.craftSpec("stick") == null, "unknown recipe");
        Map<String, Integer> inv = new HashMap<>();
        inv.put("FLINT", 2); inv.put("STICK", 2); inv.put("FEATHER", 2);
        check(AgentRanged.canCraft("ARROW", inv, true), "arrow craft with table");
        check(!AgentRanged.canCraft("ARROW", inv, false), "arrow needs a table");
        check(AgentRanged.leftover(inv, AgentRanged.ARROW.inputs) != null, "one arrow craft leftover");
        Map<String, Integer> after = AgentRanged.leftover(inv, AgentRanged.ARROW.inputs);
        after = AgentRanged.leftover(after, AgentRanged.ARROW.inputs);
        check(after != null && after.getOrDefault("FLINT", 0) == 0, "two arrow crafts empty flint");
        check(AgentRanged.leftover(Map.of("FLINT", 1, "STICK", 1), AgentRanged.ARROW.inputs) == null, "missing feather");
        Map<String, Integer> bowInv = new HashMap<>();
        bowInv.put("STICK", 3); bowInv.put("STRING", 3);
        check(AgentRanged.canCraft("bow", bowInv, true), "bow fallback");
        Map<String, Integer> xb = new HashMap<>();
        xb.put("STICK", 3); xb.put("IRON_INGOT", 1); xb.put("STRING", 2); xb.put("TRIPWIRE_HOOK", 1);
        check(AgentRanged.canCraft("CROSSBOW", xb, true), "crossbow fallback");

        check(AgentRanged.isBow("minecraft:bow") && AgentRanged.isCrossbow("CROSSBOW") && AgentRanged.isRangedWeapon("bow"), "weapon names");
        check(AgentRanged.isArrow("ARROW") && AgentRanged.isArrow("spectral_arrow") && AgentRanged.isArrow("tipped_arrow"), "ammo names");

        check(AgentActionCatalog.advertised("shoot") && AgentActionCatalog.executable("shoot"), "shoot advertised");
        check(AgentActionCatalog.advertisedNames().contains("shoot"), "advertisedNames has shoot");
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> all = (List<Map<String, Object>>) AgentActionCatalog.snapshot().get("actions");
        Map<String, Object> shoot = all.stream().filter(m -> "shoot".equals(m.get("action"))).findFirst().orElseThrow();
        String fields = String.valueOf(shoot.get("fields")).toLowerCase();
        check(fields.contains("target") && fields.contains("uuid") && fields.contains("x") && fields.contains("24"),
                "shoot fields: " + shoot.get("fields"));
        String fight = String.valueOf(all.stream().filter(m -> "fight".equals(m.get("action"))).findFirst().orElseThrow().get("description")).toLowerCase();
        check(fight.contains("bow") && fight.contains("6-24"), "fight mentions ranged");

        String version = AgentVersion.fromPluginYml();
        check("0.7.7".equals(version), "plugin.yml version: " + version);
        check("0.7.7".equals(AgentVersion.current(null)), "status version fallback");
        check(AgentVersion.parseVersion("name: AgentsMC\nversion: 0.7.7\n").equals("0.7.7"), "parse version");

        System.out.println("PASS: ranged aim/lead/gravity, draw/charge/speed, Infinity consume, LOS, 6-24/melee-4, craft bow/arrow/crossbow, shoot catalog, 0.7.4");
    }

    private static void check(boolean ok, String message) {
        if (!ok) throw new AssertionError(message);
    }
}

