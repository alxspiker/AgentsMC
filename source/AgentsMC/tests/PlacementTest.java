package dev.agentsmc;

import com.google.gson.JsonParser;

/** Offline placement rules: identifiers, replaceable targets, support faces, hotbar selection. */
public final class PlacementTest {
    public static void main(String[] args) {
        check(AgentPlacement.blockKey("DIRT").equals("dirt"), "Plain upper-case material rejected");
        check(AgentPlacement.blockKey("minecraft:dirt").equals("dirt"), "Namespaced key rejected");
        check(AgentPlacement.blockKey("Oak_Planks").equals("oak_planks"), "Mixed case rejected");
        for (String bad : new String[]{"", "minecraft:", "dirt stone", "../x", "other:dirt"})
            try { AgentPlacement.blockKey(bad); throw new AssertionError("Accepted " + bad); } catch (IllegalArgumentException expected) {}
        for (String plant : new String[]{"SHORT_GRASS", "TALL_GRASS", "POPPY", "DANDELION", "SNOW", "AIR"})
            check(AgentPlacement.replaceable(plant, false), plant + " not replaceable");
        check(!AgentPlacement.replaceable("STONE", false) && !AgentPlacement.replaceable("SNOW_BLOCK", false), "Solid block treated as replaceable");
        check(AgentPlacement.replaceable("SOMETHING_NEW", true), "Server replaceable flag ignored");
        check(AgentPlacement.supportFace(i -> true) == 0, "Floor not preferred");
        check(AgentPlacement.supportFace(i -> i == 3) == 3, "Side face over air not accepted as support");
        check(AgentPlacement.supportFace(i -> i == 5) == 5, "Ceiling not accepted as support");
        check(AgentPlacement.supportFace(i -> false) == -1, "Floating placement accepted");
        String[] hotbar = {"stone", "", "dirt", "", "", "", "", "", "dirt"};
        check(AgentPlacement.hotbarSlot(hotbar, 0, "dirt") == 2, "Did not select first hotbar slot holding block");
        check(AgentPlacement.hotbarSlot(hotbar, 8, "dirt") == 8, "Did not keep already-held matching slot");
        check(AgentPlacement.hotbarSlot(hotbar, 0, "sand") == -1, "Selected a missing block");
        var step = AgentActionProgram.normalize(JsonParser.parseString("{\"action\":\"place\",\"x\":1,\"y\":64,\"z\":2,\"block\":\"DIRT\"}").getAsJsonObject(), "world");
        check(step.get("block").getAsString().equals("DIRT") && step.get("world").getAsString().equals("world"), "Program place step lost block field");
        var queued = BridgeProtocol.action("place", JsonParser.parseString("{\"x\":1,\"y\":64,\"z\":2,\"block\":\"DIRT\"}").getAsJsonObject(), "world");
        check(queued.get("block").getAsString().equals("dirt"), "Queued place did not forward normalized block");
        check(AgentPlacement.supportFace(i -> true, 4) == 4, "Preferred wall not used");
        check(AgentPlacement.supportFace(i -> i == 0, 4) == 0, "Missing preferred wall did not fall back to floor");
        check(AgentPlacement.placedBlock("oak_sign", 0).equals("oak_sign"), "Standing sign on floor");
        check(AgentPlacement.placedBlock("oak_sign", 1).equals("oak_wall_sign"), "Wall sign conversion");
        check(AgentPlacement.placedBlock("oak_hanging_sign", 5).equals("oak_hanging_sign"), "Hanging sign on ceiling");
        check(AgentPlacement.placedBlock("oak_hanging_sign", 2).equals("oak_wall_hanging_sign"), "Wall hanging sign");
        check(AgentPlacement.placedBlock("torch", 0).equals("torch") && AgentPlacement.placedBlock("torch", 3).equals("wall_torch"), "Torch wall conversion");
        check(AgentPlacement.placedBlock("soul_torch", 4).equals("soul_wall_torch"), "Soul torch wall conversion");
        try { AgentPlacement.placedBlock("oak_sign", 5); throw new AssertionError("Standing sign on ceiling"); } catch (IllegalArgumentException expected) {}
        try { AgentPlacement.placedBlock("torch", 5); throw new AssertionError("Torch on ceiling"); } catch (IllegalArgumentException expected) {}
        check(java.util.Arrays.equals(AgentPlacement.signLines("a\nb\nc"), new String[]{"a","b","c"}), "Sign lines");
        try { AgentPlacement.signLines("1\n2\n3\n4\n5"); throw new AssertionError("5 sign lines accepted"); } catch (IllegalArgumentException expected) {}
        check(AgentPlacement.skipBodyOverlap("oak_wall_sign") && AgentPlacement.skipBodyOverlap("wall_torch")
                && AgentPlacement.skipBodyOverlap("oak_door") && AgentPlacement.skipBodyOverlap("red_bed"), "Non-solid overlap skip");
        check(AgentPlacement.extraAbove("oak_door") && AgentPlacement.extraAhead("red_bed") && !AgentPlacement.extraAbove("torch"), "Door/bed extra blocks");
        check("south".equals(AgentPlacement.attachedFacing(1)) && AgentPlacement.wallIndex("east") == 4, "Support facing");
        check(AgentPlacement.preferredSupport("oak_sign", "east", true) == 3
                && "east".equals(AgentPlacement.attachedFacing(3))
                && "oak_wall_sign".equals(AgentPlacement.placedBlock("oak_sign", 3)),
                "explicit east wall sign attaches west and faces east");
        check(AgentPlacement.preferredSupport("oak_sign", "east", false) == 4
                && "west".equals(AgentPlacement.attachedFacing(4)),
                "look-east without facing still clicks the east wall");
        check(AgentPlacement.preferredSupport("oak_wall_sign", "east", true) == 3
                && AgentPlacement.preferredSupport("spruce_sign", "west", true) == 4,
                "wall-sign item and spruce_sign use opposite support");
        check(AgentPlacement.supportFace(i -> i == 3, AgentPlacement.preferredSupport("oak_sign", "east", true)) == 3,
                "only-west wall + facing east is a wall sign, not the floor");
        check(AgentPlacement.supportFace(i -> i == 0, AgentPlacement.preferredSupport("oak_sign", "east", true)) == 0
                && "oak_sign".equals(AgentPlacement.placedBlock("oak_sign", 0)),
                "no opposite wall still stands on the floor");
        check(AgentPlacement.preferredSupport("torch", "south", true) == 1
                && "south".equals(AgentPlacement.attachedFacing(1)),
                "torch facing south attaches to the north neighbour");
        check(AgentPlacement.attachSupport("torch", "south", true, i -> i == 1) == 1, "solid north wall accepts south torch");
        check(AgentPlacement.attachSupport("torch", "south", true, i -> i == 0) == -1,
                "explicit south torch must not fall back to the floor and float");
        check(AgentPlacement.attachSupport("torch", "south", false, i -> i == 0) == 0,
                "no facing still places a floor torch");
        check(AgentPlacement.nearestWall(i -> i == 4) == 4, "nearest wall is east");
        String miss = AgentPlacement.missingSupport(-13, 86, -66, "south", "torch", 1, "AIR", 4);
        check(miss.contains("-13,86,-67") && miss.toLowerCase().contains("air") && miss.contains("east"),
                "missing support names the AIR cell and nearest wall: " + miss);
        check("south".equals(AgentPlacement.directionalFacing("wall_torch", "south", 1, true)),
                "matching support keeps facing south");
        check("north".equals(AgentPlacement.directionalFacing("wall_torch", "south", 2, true)),
                "wall torch facing follows the real support, not a floating requested facing");
        check(AgentPlacement.preferredSupport("ladder", "south", true) == 1, "ladder facing south attaches north");
        check(AgentPlacement.preferredSupport("stone_button", "east", true) == 3, "button facing east attaches west");
        check(AgentPlacement.preferredSupport("lantern", "up", true) == 5 && AgentPlacement.preferredSupport("lantern", "down", true) == 0,
                "lantern up is ceiling, down is floor");
        check(AgentPlacement.exactAttach("torch", "south") && AgentPlacement.wallFacingFollowsSupport("wall_torch"),
                "torch is an exact-attach wall item");
        check("north".equals(AgentPlacement.resolveFacing("NORTH", "east")), "absolute facing");
        check("west".equals(AgentPlacement.resolveFacing("left", "north")), "left of north is west");
        check("east".equals(AgentPlacement.resolveFacing("back", "west")), "back of west is east");
        check("up".equals(AgentPlacement.resolveFacing("up", "south")) && "down".equals(AgentPlacement.resolveFacing("down", "south")), "up/down facing");
        check(AgentPlacement.horizontal("east") && !AgentPlacement.horizontal("up"), "horizontal facing");
        check("x".equals(AgentPlacement.axisOf("east")) && "y".equals(AgentPlacement.axisOf("up")) && "z".equals(AgentPlacement.axisOf("north")), "log axis");
        check(Math.abs(AgentPlacement.yawOf("east") + 90f) < 1e-3 && Math.abs(AgentPlacement.yawOf("south")) < 1e-3, "cardinal yaw");
        check("west".equals(AgentPlacement.facingFromYaw(90f)) && "north".equals(AgentPlacement.facingFromYaw(180f)), "yaw to facing");
        try { AgentPlacement.resolveFacing("northwest", "south"); throw new AssertionError("diagonal facing accepted"); }
        catch (IllegalArgumentException expected) { check(expected.getMessage().toLowerCase().contains("facing"), "bad facing names the field"); }
        try { AgentPlacement.requireHorizontal("red_bed", "up"); throw new AssertionError("bed facing up"); }
        catch (IllegalArgumentException expected) { check(expected.getMessage().toLowerCase().contains("bed"), "bed needs horizontal facing"); }
        AgentPlacement.requireHorizontal("red_bed", "east");
        AgentPlacement.requireHorizontal("oak_planks", "up");
        check(AgentPlacement.sameFacing("n", "north", "south") && !AgentPlacement.sameFacing("east", "west", "south"), "facing aliases match");
        var withText = AgentActionProgram.normalize(JsonParser.parseString("{\"action\":\"place\",\"x\":1,\"y\":64,\"z\":2,\"block\":\"oak_sign\",\"text\":\"hello\\nworld\"}").getAsJsonObject(), "world");
        check(withText.get("text").getAsString().equals("hello\nworld"), "Program place step lost sign text");
        var withFacing = AgentActionProgram.normalize(JsonParser.parseString("{\"action\":\"place\",\"x\":1,\"y\":64,\"z\":2,\"block\":\"red_bed\",\"facing\":\"east\"}").getAsJsonObject(), "world");
        check("east".equals(withFacing.get("facing").getAsString()), "Program place step lost facing");
        System.out.println("PASS: place accepts DIRT/minecraft:dirt, signs/torches/doors/beds, facing, sign text lines, any solid face, auto-selects hotbar slot");
    }
    private static void check(boolean ok, String message) { if (!ok) throw new AssertionError(message); }
}
