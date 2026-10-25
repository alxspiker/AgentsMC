package dev.agentsmc;

import java.util.List;
import java.util.Map;

/** Offline vanilla hunger math, eat-when-full rule, persist, and viewer focus helpers. */
public final class SurvivalTest {
    public static void main(String[] args) {
        check(Math.abs(AgentHunger.movement(400, false, false) - 4.0f) < 1e-5, "walk 400m = 4 exhaustion");
        check(Math.abs(AgentHunger.movement(40, true, false) - 4.0f) < 1e-5, "sprint 40m = 4 exhaustion");
        check(Math.abs(AgentHunger.movement(400, false, true) - 4.0f) < 1e-5, "swim 400m = 4 exhaustion");
        check(Math.abs(AgentHunger.JUMP * 80 - 4.0f) < 1e-5, "80 jumps = 4 exhaustion");
        check(Math.abs(AgentHunger.SPRINT_JUMP * 20 - 4.0f) < 1e-5, "20 sprint-jumps = 4 exhaustion");
        check(Math.abs(AgentHunger.BREAK * 800 - 4.0f) < 1e-5, "800 breaks = 4 exhaustion");
        check(Math.abs(AgentHunger.ATTACK * 40 - 4.0f) < 1e-5, "40 attacks = 4 exhaustion");

        AgentHunger.Step sat = AgentHunger.step(20, 5f, 4.0f, 0, 20, 20, 2, true);
        check(sat.food == 20 && Math.abs(sat.saturation - 4f) < 1e-4 && sat.exhaustion == 0, "exhaustion drains saturation first: " + sat.saturation);

        AgentHunger.Step food = AgentHunger.step(20, 0f, 4.0f, 0, 20, 20, 2, true);
        check(food.food == 19 && food.saturation == 0, "then food");

        AgentHunger.Step peaceful = AgentHunger.step(20, 0f, 4.0f, 0, 20, 20, 0, true);
        check(peaceful.food == 20, "peaceful does not drain food");

        int f = 18;
        float s = 0, e = 0;
        int timer = 0;
        AgentHunger.Step regen = null;
        for (int i = 0; i < 80; i++) {
            regen = AgentHunger.step(f, s, e, timer, 19, 20, 2, true);
            f = regen.food;
            s = regen.saturation;
            e = regen.exhaustion;
            timer = regen.foodTimer;
        }
        check(regen != null && regen.heal >= 1f - 1e-5 && Math.abs(regen.exhaustion - 6f) < 1e-4, "regen at food>=18 after 80 ticks");

        f = 20;
        s = 6f;
        e = 0;
        timer = 0;
        AgentHunger.Step fast = null;
        for (int i = 0; i < 10; i++) {
            fast = AgentHunger.step(f, s, e, timer, 19, 20, 2, true);
            f = fast.food;
            s = fast.saturation;
            e = fast.exhaustion;
            timer = fast.foodTimer;
        }
        check(fast != null && Math.abs(fast.heal - 1f) < 1e-4, "saturation regen at food 20 after 10 ticks");

        f = 0;
        s = 0;
        e = 0;
        timer = 0;
        AgentHunger.Step starve = null;
        for (int i = 0; i < 80; i++) {
            starve = AgentHunger.step(f, s, e, timer, 20, 20, 3, true);
            f = starve.food;
            s = starve.saturation;
            e = starve.exhaustion;
            timer = starve.foodTimer;
        }
        check(starve != null && starve.starve >= 1f - 1e-5, "starvation damage at food 0");

        AgentHunger.Step easyFloor = AgentHunger.step(0, 0, 0, 79, 10, 20, 1, true);
        check(easyFloor.starve == 0, "easy difficulty does not starve at 10 health");

        AgentHunger.Step normalFloor = AgentHunger.step(0, 0, 0, 79, 1, 20, 2, true);
        check(normalFloor.starve == 0, "normal difficulty does not starve at 1 health");

        check(!AgentHunger.mayEat(20, false), "Food level is full");
        check(AgentHunger.mayEat(20, true), "golden apples etc. may eat when full");
        check(AgentHunger.mayEat(19, false), "hungry may eat");

        AgentHunger.Stored dec = AgentHunger.decode(AgentHunger.encode(17, 3.5f, 1.25f, 40));
        check(dec.food == 17 && Math.abs(dec.saturation - 3.5f) < 1e-4 && Math.abs(dec.exhaustion - 1.25f) < 1e-4 && dec.foodTimer == 40, "hunger persist round-trip");
        AgentHunger.Stored fresh = AgentHunger.decode("");
        check(fresh.food == 20 && Math.abs(fresh.saturation - 5f) < 1e-4 && fresh.exhaustion == 0, "default persist is vanilla spawn hunger");
        AgentHunger.Stored reset = AgentHunger.decode(AgentHunger.encodeReset());
        check(reset.food == 20 && Math.abs(reset.saturation - 5f) < 1e-4 && reset.exhaustion == 0 && reset.foodTimer == 0, "death respawn resets hunger");

        check(AgentHunger.armorExhaustion(org.bukkit.event.entity.EntityDamageEvent.DamageCause.ENTITY_ATTACK), "melee exhausts");
        check(!AgentHunger.armorExhaustion(org.bukkit.event.entity.EntityDamageEvent.DamageCause.FALL), "fall does not exhaust");
        check(!AgentHunger.armorExhaustion(org.bukkit.event.entity.EntityDamageEvent.DamageCause.STARVATION), "starve does not exhaust");
        Map<String, Object> dmg = AgentHunger.damageView("FALL", 4.5, 1000L);
        check("FALL".equals(dmg.get("cause")) && Double.valueOf(4.5).equals(dmg.get("amount")) && Long.valueOf(1000L).equals(dmg.get("time")), "lastDamage shape");

        check(Math.abs(AgentFocus.lerpAngle(10, 30, 0.5f) - 20) < 1e-3, "look lerp");
        check(Math.abs(AgentFocus.lerpAngle(170, -170, 1f) + 170) < 1e-2, "look lerp wrap");
        float[] east = AgentFocus.lookAngles(0, 64, 0, 10, 64, 0);
        check(Math.abs(east[0] + 90) < 1e-2 && Math.abs(east[1]) < 1e-2, "look east yaw=" + east[0]);
        Map<String, Object> focus = AgentFocus.view(1.5, 64.5, -2.5, "dig", 123L);
        check("dig".equals(focus.get("action")) && Double.valueOf(1.5).equals(focus.get("x")) && Long.valueOf(123L).equals(focus.get("since")), "focus shape");

        check(AgentReach.inReach(0.5, 65.62, 0.5, 5, 64, 0), "5-block AABB inclusive");
        check(!AgentReach.inReach(0.5, 65.62, 0.5, 6, 64, 0), "6-block AABB out of reach");
        check(AgentReach.inReach(0.5, 65.62, 0.5, 4, 67, 3), "center >5 but nearest face still in 5.0");
        check(AgentReach.nearest(0.5, 65.62, 0.5, 4, 67, 3) <= 5.0, "nearest face <= 5.0");
        check(Math.sqrt(4 * 4 + 1.88 * 1.88 + 3 * 3) > 5.0, "sanity: block center is beyond 5");
        // Skeleton ~0.6x1.99 at 3.2 center: nearest AABB is 2.9, inside melee 3.0
        check(AgentReach.inAttackReach(0, 65.62, 0, 2.9, 65, -0.3, 3.5, 66.99, 0.3), "3.2-center skeleton still in 3.0 AABB");
        check(AgentReach.nearestBox(0, 65.62, 0, 2.9, 65, -0.3, 3.5, 66.99, 0.3) <= 3.0, "nearest box <= 3.0");
        check(!AgentReach.inAttackReach(0, 65.62, 0, 3.8, 65, -0.3, 4.4, 66.99, 0.3), "4.1-center skeleton out of 3.0 AABB");
        check("IRON_SWORD".equals(AgentCombat.bestWeapon(List.of("IRON_PICKAXE", "STONE_AXE", "IRON_SWORD"))), "sword before axe/pick");
        check("NETHERITE_AXE".equals(AgentCombat.bestWeapon(List.of("DIAMOND_PICKAXE", "NETHERITE_AXE"))), "axe before pick");
        check("DIAMOND_PICKAXE".equals(AgentCombat.bestWeapon(List.of("STICK", "DIAMOND_PICKAXE"))), "pickaxe when it is the only tool");
        check(AgentCombat.SPRINT >= 1.3f, "chase speed modifier");
        check(AgentCombat.weaponScore("WOODEN_SWORD") > AgentCombat.weaponScore("NETHERITE_AXE"), "any sword beats any axe");
        check(AgentReach.craftsFor(8, 4) == 2, "8 torches from 4-yield recipe is 2 crafts");
        check(AgentReach.craftsFor(5, 4) == 2, "ceil amount/yield");
        check(AgentReach.scanInRadius(32, 0, 0, 32), "scan radius edge inclusive");
        check(!AgentReach.scanInRadius(32, 32, 0, 32), "scan must not use Chebyshev box");
        check(!AgentReach.scanInRadius(22, 22, 22, 32), "d≈38 must not match radius 32");
        check(AgentBehaviors.parseSteps("[]", "world").isEmpty(), "map set accepts empty steps");
        check(AgentCombat.chaseFail(true, false, false, 10_000) == null, "in-reach chase is not unreachable");
        check("target unreachable".equals(AgentCombat.chaseFail(false, false, true, 0)), "no path / not navigating");
        check("target unreachable".equals(AgentCombat.chaseFail(false, true, false, 0)), "canNavigateTo false");
        check("target unreachable".equals(AgentCombat.chaseFail(false, true, true, 5000)), "5s stall");
        check(AgentCombat.chaseFail(false, true, true, 4999) == null, "chase still progressing");
        check("target unreachable".equals(AgentCombat.chaseFail(false, true, true, 0, 0, 5000)), "0 hits / no close for 5s");
        check(AgentCombat.chaseFail(false, true, true, 100, 3, 8000) == null, "hits are chase progress");
        check(AgentCombat.noChaseProgress(0, 5000) && !AgentCombat.noChaseProgress(0, 4999), "give-up clock");
        check(AgentProtection.inside(0, 0, 0, 0, 16), "spawn cell is protected");
        check(AgentProtection.inside(16, 0, 0, 0, 16), "radius edge inclusive");
        check(AgentProtection.inside(16, 16, 0, 0, 16), "Chebyshev square corner is protected");
        check(AgentProtection.inside(12, 12, 0, 0, 16), "euclidean ~17 is still inside the square of 16");
        check(!AgentProtection.inside(17, 0, 0, 0, 16), "outside radius");
        check(!AgentProtection.inside(17, 17, 0, 0, 16), "outside square corner");
        check(!AgentProtection.inside(0, 0, 0, 0, 0), "radius 0 disables protection");
        check(AgentProtection.message(16).equals("spawn protection: blocked within 16 blocks of spawn"), AgentProtection.message(16));
        check(AgentDoors.handOpen("oak_door") && !AgentDoors.handOpen("IRON_DOOR"), "door use is not a build");
        check(AgentDoors.mayClose(false) && !AgentDoors.mayClose(true), "villagers do not slam spawn-protect doors");
        check(AgentDoors.leftBehind(2, 64, 0, 0, 64, 0) && !AgentDoors.leftBehind(1, 64, 0, 0, 64, 0),
                "close after leaving the doorway");
        check(AgentProtection.containerUse("chest") && AgentProtection.containerUse("TRAPPED_CHEST"),
                "chest inspect is not a build");
        check(AgentSurvivalActions.tableNeeded("oak_door (OAK_DOOR)", false, true).contains("oak_door")
                && AgentSurvivalActions.tableNeeded("oak_door (OAK_DOOR)", false, true).contains("CRAFTING_TABLE in inventory"),
                "craft table error");
        check(AgentSurvivalActions.tableNeeded("oak_planks (OAK_PLANKS)", false, false).contains("no CRAFTING_TABLE in inventory"), "no table in inventory");
        check(AgentBehaviors.dropRank("OAK_LOG", 4, List.of("OAK_LOG")) < AgentBehaviors.dropRank("BONE", 1, List.of("OAK_LOG")), "prefer just-mined over nearer junk");
        check(AgentBehaviors.dropRank("OAK_LOG", 1, List.of()) < AgentBehaviors.dropRank("BONE", 4, List.of()), "nearest drop when nothing was just mined");
        check(AgentReach.occupies(0, 66, 22, 0, 67, 22) && AgentReach.occupies(0, 66, 22, 0, 66, 22), "feet/head are occupied");
        check(!AgentReach.occupies(0, 66, 22, 0, 67, 24), "target two blocks away is not occupied");
        check(!AgentReach.occupies(0, 66, 24, 0, 67, 25), "adjacent cobble is not the agent's body");
        check(AgentIds.publicId("8f3a1c2e-4b5d-6789-abcd-ef0123456789").equals("8f3a1c2e4b5d6789abcdef0123456789"), "publicId strips dashes");
        check(AgentIds.same("8f3a1c2e-4b5d-6789-abcd-ef0123456789", "8F3A1C2E4B5D6789ABCDEF0123456789"), "same ignores dashes/case");

        check(AgentBehaviors.TRAPPED.contains("trapped in protected area"), "trapped diagnostic");
        check("close".equals(AgentProtect.followPace(1.5)) && "hold".equals(AgentProtect.followPace(3))
                && "walk".equals(AgentProtect.followPace(6)) && "sprint".equals(AgentProtect.followPace(12)), "protect follow bands");
        check(AgentProtect.clampRadius(0)==16 && AgentProtect.clampRadius(8)==8 && AgentProtect.clampRadius(99)==32, "protect radius");
        check(AgentProtect.offlineMessage("Steve").equals(AgentTargets.missing("Steve")), "offline protect error");
        check(AgentProtect.offlineMessage("Helper").toLowerCase().contains("helper")
                && !AgentProtect.offlineMessage("Helper").toLowerCase().contains("offline"),
                "protect miss names the agent, not offline: " + AgentProtect.offlineMessage("Helper"));
        check(!AgentProtect.livingWard(null), "null is not a living ward");
        Map<String, Integer> cobbleOnly = new java.util.LinkedHashMap<>();
        cobbleOnly.put("COBBLESTONE", 2);
        String swordNeed = AgentCraft.shortage("STONE_SWORD", java.util.List.of("COBBLESTONE", "COBBLESTONE", "STICK"), cobbleOnly);
        check(swordNeed.contains("STICK") && swordNeed.contains("have 0") && swordNeed.toUpperCase().contains("STONE_SWORD"),
                "craft shortage names stick: " + swordNeed);
        check(AgentReach.touching(3, 65, 0, 3, 65, 1) && AgentReach.touching(3, 65, 0, 4, 65, 0),
                "adjacent chest is touching");
        check(!AgentReach.touching(0, 65, 0, 3, 65, 0), "3 blocks away is not touching");
        check(AgentBehaviors.obstructedMessage(null).toLowerCase().contains("obstructed"), "null obstruction message");
        check(AgentProtect.hostileType("ZOMBIE") && AgentProtect.hostileType("SKELETON") && !AgentProtect.hostileType("COW"), "hostile types");
        check(AgentProtect.shouldEngage(true, true, false, 12, 20, 16), "engage targeting mob inside radius");
        check(!AgentProtect.shouldEngage(true, true, false, 20, 20, 16), "ignore targeting mob outside radius");
        check(!AgentProtect.shouldEngage(true, false, false, 20, 20, 16), "ignore distant non-targeting mob");
        check(AgentProtect.shouldEngage(true, false, false, 5, 12, 16), "engage approaching mob");
        check(!AgentProtect.shouldEngage(false, true, false, 3, 3, 16), "ignore non-hostile");
        check(AgentProtect.encode("Steve", 16, 2).equals("Steve;16;2"), "protect persist");
        AgentProtect.Guard decoded=AgentProtect.decode("Steve;16;2");
        check(decoded!=null&&decoded.player.equals("Steve")&&decoded.radius==16&&decoded.kills==2, "protect restore");
        check(AgentProtect.decode("")==null && AgentProtect.running("following") && !AgentProtect.running("stopped"), "protect states");
        check(AgentProtect.keepRetreat(5, false, true) && !AgentProtect.keepRetreat(8, true, false), "protect retreat hysteresis");
        check(AgentProtect.abandonChase(21, 16) && !AgentProtect.abandonChase(19, 16), "protect abandon past radius+4");
        check(AgentSurvivalActions.readyToEat(0, 1) && !AgentSurvivalActions.readyToEat(1, 1 + 1000), "eat cooldown");
        check("cancel".equals(AgentRestart.disposition(false, true)) && "execute".equals(AgentRestart.disposition(false, false)), "restart queue disposition");

        com.google.gson.JsonObject smelt = new com.google.gson.JsonObject();
        smelt.addProperty("action", "smelt");
        try {
            AgentSurvivalActions.fields(smelt, java.util.List.of(), "action", "agent", "world", "x", "y", "z", "material", "amount");
            throw new AssertionError("smelt without x/y/z should fail");
        } catch (IllegalArgumentException smeltErr) {
            check(smeltErr.getMessage().contains("'x'") && !smeltErr.getMessage().contains("'world'"),
                    "smelt missing field names x not world: " + smeltErr.getMessage());
        }
        com.google.gson.JsonObject craftMat = com.google.gson.JsonParser.parseString("{\"action\":\"craft\",\"material\":\"spruce_planks\"}").getAsJsonObject();
        check(AgentCraft.outputField(craftMat).equals("spruce_planks") && AgentCraft.amount(craftMat) == 1,
                "craft material alias and default amount");
        com.google.gson.JsonObject craftCount = com.google.gson.JsonParser.parseString("{\"action\":\"craft\",\"item\":\"BREAD\",\"count\":2}").getAsJsonObject();
        check(AgentCraft.amount(craftCount) == 2, "craft count is an amount alias");
        com.google.gson.JsonObject craftBoth = com.google.gson.JsonParser.parseString("{\"action\":\"craft\",\"item\":\"BREAD\",\"amount\":3,\"count\":9}").getAsJsonObject();
        check(AgentCraft.amount(craftBoth) == 3, "amount wins over count");
        try {
            AgentCraft.outputField(com.google.gson.JsonParser.parseString("{\"action\":\"craft\"}").getAsJsonObject());
            throw new AssertionError("craft without item/material should fail");
        } catch (IllegalArgumentException craftErr) {
            check(craftErr.getMessage().toLowerCase().contains("item") || craftErr.getMessage().toLowerCase().contains("material"),
                    "craft field error: " + craftErr.getMessage());
        }
        AgentCraft.Spec planks = AgentCraft.vanilla("SPRUCE_PLANKS");
        check(planks != null && planks.yield == 4 && AgentCraft.matchesInput("SPRUCE_LOG", planks.inputs.get(0)),
                "spruce log -> 4 planks");
        Map<String, Integer> logs = new java.util.LinkedHashMap<>();
        logs.put("SPRUCE_LOG", 3);
        check(AgentCraft.maxCrafts(logs, planks) == 3 && AgentCraft.produced(12, 4, 3) == 12, "3 logs craft 12 planks");
        AgentCraft.Spec chest = AgentCraft.vanilla("chest");
        Map<String, Integer> wood = new java.util.LinkedHashMap<>();
        wood.put("SPRUCE_PLANKS", 8);
        check(chest != null && chest.table && AgentCraft.maxCrafts(wood, chest) == 1
                && AgentCraft.produced(1, 1, 1) == 1, "8 planks -> 1 chest");
        check(AgentCraft.produced(1, 1, 1) == 1 && AgentCraft.produced(1, 1, 2) == 1, "requested 1 pickaxe is 1 craft");
        AgentCraft.Spec wooden = AgentCraft.vanilla("WOODEN_SWORD");
        check(wooden != null && !wooden.table && wooden.yield == 1 && wooden.inputs.size() == 3,
                "fb-25 wooden sword is a 2-plank+stick inventory craft");
        Map<String, Integer> bench = new java.util.LinkedHashMap<>();
        bench.put("SPRUCE_PLANKS", 6);
        bench.put("STICK", 4);
        check(AgentCraft.maxCrafts(bench, wooden) == 3 && AgentCraft.produced(1, 1, 3) == 1,
                "6 planks + 4 sticks craft 3 wooden swords");
        String woodenNeed = AgentCraft.shortage("WOODEN_SWORD", wooden.inputs, cobbleOnly);
        check(woodenNeed.contains("PLANKS") && woodenNeed.toUpperCase().contains("WOODEN_SWORD"),
                "wooden sword shortage names planks: " + woodenNeed);

        System.out.println("PASS: vanilla hunger, reach, weapons, spawn protection, fight unreachable, craft table error, collect rank, dashless id, protect, trapped, smelt/craft fields, craft shortage, touch");
    }

    private static void check(boolean ok, String message) {
        if (!ok) throw new AssertionError(message);
    }
}
