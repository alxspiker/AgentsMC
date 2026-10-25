package dev.agentsmc;

import java.util.Locale;
import java.util.Objects;
import java.util.Set;

/** Server-free placement rules: block identifiers, replaceable targets, support faces, and sign/torch variants. */
final class AgentPlacement {
    /** Vanilla-replaceable plants and layers that placement overwrites instead of rejecting. */
    static final Set<String> REPLACEABLE = Set.of("air", "cave_air", "void_air", "short_grass", "grass", "tall_grass",
            "fern", "large_fern", "dead_bush", "snow", "vine", "glow_lichen", "seagrass", "tall_seagrass", "light",
            "dandelion", "poppy", "blue_orchid", "allium", "azure_bluet", "red_tulip", "orange_tulip", "white_tulip",
            "pink_tulip", "oxeye_daisy", "cornflower", "lily_of_the_valley", "wither_rose", "torchflower",
            "sunflower", "lilac", "rose_bush", "peony", "pink_petals", "wildflowers", "leaf_litter", "bush",
            "short_dry_grass", "tall_dry_grass", "firefly_bush", "closed_eyeblossom", "open_eyeblossom",
            "crimson_roots", "warped_roots", "nether_sprouts", "hanging_roots", "structure_void");

    /** Neighbour offsets checked for support, below first, like a player clicking the floor. */
    static final int[][] SUPPORT_FACES = {{0,-1,0},{0,0,-1},{0,0,1},{-1,0,0},{1,0,0},{0,1,0}};

    /** Accepts DIRT, dirt, minecraft:dirt and returns the bare lowercase key, or throws. */
    static String blockKey(String raw) {
        if (raw == null) throw new IllegalArgumentException("Expected string: block");
        String key = raw.trim().toLowerCase(Locale.ROOT);
        if (key.startsWith("minecraft:")) key = key.substring("minecraft:".length());
        if (!key.matches("[a-z0-9_]{1,64}")) throw new IllegalArgumentException("Expected Minecraft block identifier: " + raw);
        return key;
    }

    static boolean replaceable(String materialName, boolean serverSaysReplaceable) {
        return serverSaysReplaceable || REPLACEABLE.contains(materialName.toLowerCase(Locale.ROOT));
    }

    /** Index into SUPPORT_FACES of the first solid neighbour, or -1. */
    static int supportFace(java.util.function.IntPredicate solidAtFace) {
        return supportFace(solidAtFace, 0);
    }

    /** Prefer `preferred` when that neighbour is solid, otherwise the default floor-first scan. */
    static int supportFace(java.util.function.IntPredicate solidAtFace, int preferred) {
        if (preferred >= 0 && preferred < SUPPORT_FACES.length && solidAtFace.test(preferred)) return preferred;
        for (int i = 0; i < SUPPORT_FACES.length; i++) if (solidAtFace.test(i)) return i;
        return -1;
    }

    /**
     * Absolute or relative facing for {@code place}. Relative keys use {@code lookFacing}
     * (the body's current look). Blank/null means "use the body facing".
     */
    static String resolveFacing(String raw, String lookFacing) {
        if (raw == null || raw.isBlank()) return null;
        String look = cardinal(lookFacing);
        return switch (raw.trim().toLowerCase(Locale.ROOT)) {
            case "north", "n" -> "north";
            case "south", "s" -> "south";
            case "east", "e" -> "east";
            case "west", "w" -> "west";
            case "up" -> "up";
            case "down" -> "down";
            case "forward" -> look;
            case "back", "backward" -> opposite(look);
            case "left" -> leftOf(look);
            case "right" -> rightOf(look);
            default -> throw new IllegalArgumentException(
                    "facing must be north, south, east, west, up, down, or forward/back/left/right");
        };
    }

    static String cardinal(String facing) {
        if (facing == null) return "south";
        return switch (facing.trim().toLowerCase(Locale.ROOT)) {
            case "north", "n" -> "north";
            case "south", "s" -> "south";
            case "east", "e" -> "east";
            case "west", "w" -> "west";
            default -> "south";
        };
    }

    static String opposite(String facing) {
        return switch (cardinal(facing)) {
            case "north" -> "south";
            case "south" -> "north";
            case "east" -> "west";
            default -> "east";
        };
    }

    static String leftOf(String facing) {
        return switch (cardinal(facing)) {
            case "north" -> "west";
            case "west" -> "south";
            case "south" -> "east";
            default -> "north";
        };
    }

    static String rightOf(String facing) {
        return opposite(leftOf(facing));
    }

    static boolean horizontal(String facing) {
        if (facing == null) return false;
        return switch (facing.toLowerCase(Locale.ROOT)) {
            case "north", "south", "east", "west" -> true;
            default -> false;
        };
    }

    static boolean sameFacing(String a, String b, String lookFacing) {
        return Objects.equals(resolveFacing(a, lookFacing), resolveFacing(b, lookFacing));
    }

    /** Minecraft yaw for a cardinal: south=0, west=90, north=180, east=-90. */
    static float yawOf(String facing) {
        return switch (cardinal(facing)) {
            case "north" -> 180f;
            case "west" -> 90f;
            case "east" -> -90f;
            default -> 0f;
        };
    }

    /** Cardinal from Minecraft yaw (0=south, 90=west). */
    static String facingFromYaw(float yaw) {
        float a = ((yaw % 360f) + 360f) % 360f;
        if (a >= 315f || a < 45f) return "south";
        if (a < 135f) return "west";
        if (a < 225f) return "north";
        return "east";
    }

    /** Log/pillar axis for a facing: east/west=x, up/down=y, north/south=z. */
    static String axisOf(String facing) {
        if (facing == null) return "y";
        return switch (facing.toLowerCase(Locale.ROOT)) {
            case "east", "west" -> "x";
            case "up", "down" -> "y";
            default -> "z";
        };
    }

    /** Beds and doors occupy a second cell along a horizontal facing. */
    static void requireHorizontal(String placedKey, String facing) {
        if (facing == null) return;
        if ((extraAhead(placedKey) || extraAbove(placedKey)) && !horizontal(facing))
            throw new IllegalArgumentException(
                    (extraAhead(placedKey) ? "Beds" : "Doors") + " need facing north, south, east, or west");
    }

    static int wallIndex(String facing) {
        if (facing == null) return 0;
        return switch (facing.toLowerCase(Locale.ROOT)) {
            case "north" -> 1;
            case "south" -> 2;
            case "west" -> 3;
            case "east" -> 4;
            default -> 0;
        };
    }

    /**
     * Ceiling for hanging signs; a wall for signs/torches; floor otherwise.
     * When {@code frontFacing} is true, {@code facing} is the front of the block
     * (a wall sign facing east attaches to the west neighbour). When false,
     * {@code facing} is the wall the body is looking at (vanilla click).
     */
    static int preferredSupport(String itemKey, String facing) {
        return preferredSupport(itemKey, facing, false);
    }

    static int preferredSupport(String itemKey, String facing, boolean frontFacing) {
        String key = itemKey.toLowerCase(Locale.ROOT);
        if (isLantern(key)) return "up".equals(facing) ? 5 : 0;
        if (isHangingSign(key) && !key.contains("wall")) return 5;
        if (isWallAttachable(key) || isSignItem(key) || isGroundTorch(key)) {
            if ("up".equals(facing)) return 5;
            if ("down".equals(facing)) return 0;
            String wall = frontFacing && horizontal(facing) ? opposite(facing) : facing;
            return wallIndex(wall);
        }
        return 0;
    }

    /**
     * Explicit facing on a torch/sign/ladder/button/lever/lantern must use that
     * attachment face. Falling back to another neighbour and then forcing the
     * requested facing is how a WALL_TORCH floats in mid-air.
     */
    static boolean exactAttach(String itemKey, String facing) {
        if (facing == null) return false;
        String key = itemKey.toLowerCase(Locale.ROOT);
        if (!(isWallAttachable(key) || isSignItem(key) || isGroundTorch(key) || isLantern(key))) return false;
        return horizontal(facing) || "up".equals(facing) || "down".equals(facing);
    }

    static int attachSupport(String itemKey, String facing, boolean explicit, java.util.function.IntPredicate solid) {
        int preferred = preferredSupport(itemKey, facing, explicit);
        if (explicit && exactAttach(itemKey, facing))
            return preferred >= 0 && solid.test(preferred) ? preferred : -1;
        return supportFace(solid, preferred);
    }

    static int nearestWall(java.util.function.IntPredicate solid) {
        for (int i = 1; i <= 4; i++) if (solid.test(i)) return i;
        return -1;
    }

    static String supportName(int face) {
        return switch (face) {
            case 0 -> "floor";
            case 1 -> "north";
            case 2 -> "south";
            case 3 -> "west";
            case 4 -> "east";
            case 5 -> "ceiling";
            default -> "none";
        };
    }

    static boolean isWallAttachable(String key) {
        String k = key.toLowerCase(Locale.ROOT);
        return isGroundTorch(k) || k.contains("wall_torch") || isSignItem(k) || isHangingSign(k)
                || k.contains("ladder") || k.endsWith("_button") || k.equals("lever")
                || k.contains("tripwire_hook") || isLantern(k);
    }

    static boolean isLantern(String key) {
        String k = key.toLowerCase(Locale.ROOT);
        return k.equals("lantern") || k.equals("soul_lantern") || k.endsWith("_lantern");
    }

    /** Wall-attached Directional facing is the side opposite the support, never a requested facing that would float. */
    static boolean wallFacingFollowsSupport(String placedKey) {
        String k = placedKey.toLowerCase(Locale.ROOT);
        return k.contains("torch") || k.contains("wall_sign") || k.contains("ladder")
                || k.endsWith("_button") || k.equals("lever") || k.contains("tripwire_hook")
                || k.contains("wall_hanging_sign");
    }

    static String directionalFacing(String placedKey, String requestedFacing, int supportFace, boolean explicit) {
        String attached = attachedFacing(supportFace);
        if (wallFacingFollowsSupport(placedKey) && attached != null) return attached;
        if (explicit && horizontal(requestedFacing)) return requestedFacing;
        return attached != null ? attached : requestedFacing;
    }

    static String missingSupport(int x, int y, int z, String facing, String item, int preferred, String found, int nearest) {
        int[] o = preferred >= 0 && preferred < SUPPORT_FACES.length ? SUPPORT_FACES[preferred] : new int[]{0, 0, 0};
        String at = (x + o[0]) + "," + (y + o[1]) + "," + (z + o[2]);
        String side = supportName(preferred);
        String msg = "facing=" + facing + " attaches to the " + side + " neighbour at " + at
                + " which is " + (found == null ? "AIR" : found.toUpperCase(Locale.ROOT))
                + " (need a solid block to hang a " + item + ")";
        if (nearest >= 1 && nearest <= 4)
            msg += "; nearest solid wall is " + supportName(nearest);
        return msg;
    }

    /** Facing of a wall-attached block placed against SUPPORT_FACES[supportIndex]. */
    static String attachedFacing(int supportIndex) {
        return switch (supportIndex) {
            case 1 -> "south";
            case 2 -> "north";
            case 3 -> "east";
            case 4 -> "west";
            default -> null;
        };
    }

    static boolean isSignItem(String key) {
        String k = key.toLowerCase(Locale.ROOT);
        return k.equals("sign") || k.endsWith("_sign");
    }
    static boolean isHangingSign(String key) {
        String k = key.toLowerCase(Locale.ROOT);
        return k.equals("hanging_sign") || k.endsWith("_hanging_sign");
    }
    static boolean isGroundTorch(String key) {
        String k = key.toLowerCase(Locale.ROOT);
        return (k.equals("torch") || k.equals("soul_torch") || k.equals("redstone_torch")
                || (k.endsWith("_torch") && !k.contains("wall")));
    }
    static boolean isPlaceableItem(String key) {
        String k = key.toLowerCase(Locale.ROOT);
        return isSignItem(k) || isGroundTorch(k) || k.contains("wall_torch")
                || k.endsWith("_bed") || k.equals("bed")
                || k.endsWith("_door") || k.equals("iron_door");
    }
    static boolean extraAbove(String placedKey) {
        String k = placedKey.toLowerCase(Locale.ROOT);
        return k.endsWith("_door") || k.equals("iron_door");
    }
    static boolean extraAhead(String placedKey) {
        String k = placedKey.toLowerCase(Locale.ROOT);
        return k.endsWith("_bed") || k.equals("bed");
    }
    /** Non-solid / two-block placeables may occupy the same cell as the agent's hitbox. */
    static boolean skipBodyOverlap(String placedKey) {
        String k = placedKey.toLowerCase(Locale.ROOT);
        return isSignItem(k) || k.contains("torch") || extraAbove(k) || extraAhead(k);
    }

    /** Block placed in the world for an inventory item against the chosen support face. */
    static String placedBlock(String itemKey, int supportFace) {
        String key = itemKey.toLowerCase(Locale.ROOT);
        if (isHangingSign(key) && !key.contains("wall")) {
            if (supportFace == 0) throw new IllegalArgumentException("Hanging signs attach under a block or to a wall, not on the floor");
            if (supportFace >= 1 && supportFace <= 4) return hangingWallKey(key);
            return key;
        }
        if (isSignItem(key) && !key.contains("wall") && !isHangingSign(key)) {
            if (supportFace == 5) throw new IllegalArgumentException("Standing signs attach to the floor or a wall, not the ceiling");
            if (supportFace >= 1 && supportFace <= 4) return wallSignKey(key);
            return key;
        }
        if (isGroundTorch(key)) {
            if (supportFace == 5) throw new IllegalArgumentException("Torches attach to the floor or a wall, not the ceiling");
            if (supportFace >= 1 && supportFace <= 4) return wallTorchKey(key);
            return key;
        }
        return key;
    }

    static String wallSignKey(String key) {
        if (key.endsWith("_sign")) return key.substring(0, key.length() - 5) + "_wall_sign";
        return key;
    }
    static String hangingWallKey(String key) {
        if (key.endsWith("_hanging_sign")) return key.substring(0, key.length() - 13) + "_wall_hanging_sign";
        return key;
    }
    static String wallTorchKey(String key) {
        if (key.equals("torch")) return "wall_torch";
        if (key.endsWith("_torch") && !key.contains("wall")) return key.substring(0, key.length() - 6) + "_wall_torch";
        return key;
    }

    /** Up to 4 sign lines from a newline-separated string. */
    static String[] signLines(String text) {
        if (text == null) throw new IllegalArgumentException("Expected string: text");
        if (text.chars().anyMatch(c -> c != '\n' && Character.isISOControl(c)))
            throw new IllegalArgumentException("text must be printable (newlines allowed)");
        if (text.length() > 256) throw new IllegalArgumentException("text must be at most 256 characters");
        String[] lines = text.split("\n", -1);
        if (lines.length > 4) throw new IllegalArgumentException("sign text allows at most 4 lines separated by \\n");
        for (String line : lines)
            if (line.length() > 384) throw new IllegalArgumentException("each sign line must be at most 384 characters");
        return lines;
    }

    /** Hotbar slot (0..8) to select for the block: current slot if it matches, else first match, else -1. */
    static int hotbarSlot(String[] hotbarKeys, int held, String key) {
        if (held >= 0 && held < hotbarKeys.length && key.equals(hotbarKeys[held])) return held;
        for (int i = 0; i < Math.min(9, hotbarKeys.length); i++) if (key.equals(hotbarKeys[i])) return i;
        return -1;
    }

    private AgentPlacement() {}
}
