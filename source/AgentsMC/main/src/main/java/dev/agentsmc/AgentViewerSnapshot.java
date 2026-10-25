package dev.agentsmc;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.profile.PlayerTextures;

/** Bounded, read-only render snapshots. No chunk loads, profile requests or inventory writes. */
final class AgentViewerSnapshot {
    static final int RADIUS = 8;
    static final int BELOW = 3;
    static final int ABOVE = 7;
    static final int MAX_BLOCKS = (RADIUS * 2 + 1) * (RADIUS * 2 + 1) * (BELOW + ABOVE + 1);
    /**
     * Site Zod capture-bounds inclusive Y/XZ. A tall datapack world (maxHeight 2032)
     * must still not send minY/maxY outside this or POST /viewer returns HTTP 400
     * {@code Invalid capture bounds}.
     */
    static final int SCHEMA_MIN_Y = -64, SCHEMA_MAX_Y = 320;
    static final int SCHEMA_MIN_XZ = -30_000_000, SCHEMA_MAX_XZ = 30_000_000;

    /**
     * Inclusive capture box. {@code worldMaxHeight} is Bukkit's exclusive max (320 in
     * a vanilla overworld). Always {@code min<=max} and inside world ∩ schema.
     */
    static int[] captureBounds(int cx, int cy, int cz, int worldMinHeight, int worldMaxHeight) {
        int yLo = Math.max(worldMinHeight, SCHEMA_MIN_Y);
        int yHi = Math.min(worldMaxHeight - 1, SCHEMA_MAX_Y);
        if (yLo > yHi) {
            int pin = clampInt(cy, SCHEMA_MIN_Y, SCHEMA_MAX_Y);
            yLo = yHi = pin;
        }
        int minY = Math.max(yLo, cy - BELOW);
        int maxY = Math.min(yHi, cy + ABOVE);
        if (minY > maxY) {
            int pin = clampInt(cy, yLo, yHi);
            minY = maxY = pin;
        }
        int minX = clampInt(cx - RADIUS, SCHEMA_MIN_XZ, SCHEMA_MAX_XZ);
        int maxX = clampInt(cx + RADIUS, SCHEMA_MIN_XZ, SCHEMA_MAX_XZ);
        if (minX > maxX) minX = maxX = clampInt(cx, SCHEMA_MIN_XZ, SCHEMA_MAX_XZ);
        int minZ = clampInt(cz - RADIUS, SCHEMA_MIN_XZ, SCHEMA_MAX_XZ);
        int maxZ = clampInt(cz + RADIUS, SCHEMA_MIN_XZ, SCHEMA_MAX_XZ);
        if (minZ > maxZ) minZ = maxZ = clampInt(cz, SCHEMA_MIN_XZ, SCHEMA_MAX_XZ);
        return new int[]{minX, maxX, minY, maxY, minZ, maxZ};
    }

    static boolean inSchemaY(int y) { return y >= SCHEMA_MIN_Y && y <= SCHEMA_MAX_Y; }

    static int clampInt(int v, int lo, int hi) {
        if (lo > hi) return v;
        return Math.max(lo, Math.min(hi, v));
    }

    static Map<String, Object> capture(Player player) {
        if (!Bukkit.isPrimaryThread()) throw new IllegalStateException("Viewer capture requires the server thread");
        Location location = player.getLocation();
        World world = player.getWorld();
        int cx = location.getBlockX(), cy = location.getBlockY(), cz = location.getBlockZ();
        int[] box = captureBounds(cx, cy, cz, world.getMinHeight(), world.getMaxHeight());
        int minX = box[0], maxX = box[1], minY = box[2], maxY = box[3], minZ = box[4], maxZ = box[5];
        List<Map<String, Object>> blocks = new ArrayList<>();
        List<Map<String, Object>> columns = new ArrayList<>();
        for (int x = minX; x <= maxX; x++) {
            for (int z = minZ; z <= maxZ; z++) {
                // Never ask Bukkit for a block in a chunk that is not already loaded.
                if (!world.isChunkLoaded(x >> 4, z >> 4)) continue;
                columns.add(Map.of("x", x, "z", z));
                for (int y = minY; y <= maxY; y++) {
                    if (y < world.getMinHeight() || y >= world.getMaxHeight() || !inSchemaY(y)) continue;
                    Block block = world.getBlockAt(x, y, z);
                    if (block.getType().isAir()) continue;
                    blocks.add(block(block));
                }
            }
        }
        Map<String, Object> agent = new LinkedHashMap<>(point(location));
        agent.put("uuid", player.getUniqueId().toString());
        agent.put("name", player.getName());
        agent.put("pose", player.getPose().name().toLowerCase(Locale.ROOT));
        agent.put("sneaking", player.isSneaking());
        agent.put("sprinting", player.isSprinting());
        agent.put("swimming", player.isSwimming());
        agent.put("onGround", player.isOnGround());
        agent.put("velocity", Map.of("x", player.getVelocity().getX(), "y", player.getVelocity().getY(), "z", player.getVelocity().getZ()));
        PlayerTextures textures = player.getPlayerProfile().getTextures();
        Map<String, Object> skin = new LinkedHashMap<>();
        skin.put("url", textures.getSkin() == null ? null : textures.getSkin().toExternalForm());
        skin.put("model", textures.getSkinModel().name().toLowerCase(Locale.ROOT));
        skin.put("capeUrl", textures.getCape() == null ? null : textures.getCape().toExternalForm());
        agent.put("skin", skin);
        agent.put("equipment", equipment(player));
        Map<String, Object> result = new LinkedHashMap<>();
        // Legacy fields retain their meaning. Extra v2 fields are consumed by the future Site renderer.
        result.put("world", world.getName());
        result.put("camera", point(player.getEyeLocation()));
        result.put("agent", agent);
        result.put("blocks", blocks);
        result.put("capturedAt", System.currentTimeMillis());
        result.put("viewerVersion", 2);
        result.put("minecraftVersion", Bukkit.getMinecraftVersion());
        result.put("bounds", Map.of("minX", minX, "maxX", maxX, "minY", minY, "maxY", maxY, "minZ", minZ, "maxZ", maxZ));
        result.put("loadedColumns", columns);
        result.put("environment", Map.of("dimension", world.getEnvironment().name().toLowerCase(Locale.ROOT),
                "time", world.getTime(), "fullTime", world.getFullTime(),
                "timeOfDay", AgentWorldTime.timeOfDay(world.getTime()),
                "dayNumber", AgentWorldTime.dayNumber(world.getFullTime()),
                "storm", world.hasStorm(), "thunder", world.isThundering(),
                "minHeight", world.getMinHeight(), "maxHeight", world.getMaxHeight()));
        return result;
    }

    private static Map<String, Object> block(Block block) {
        // Full canonical block-data string includes facing, half, shape, waterlogged, age, etc.
        return Map.of("x", block.getX(), "y", block.getY(), "z", block.getZ(),
                "type", block.getType().name().toLowerCase(Locale.ROOT),
                "state", block.getBlockData().getAsString(),
                "skyLight", (int) block.getLightFromSky(), "blockLight", (int) block.getLightFromBlocks(),
                "biome", block.getBiome().getKey().toString());
    }

    private static Map<String, Object> point(Location location) {
        return Map.of("x", location.getX(), "y", location.getY(), "z", location.getZ(),
                "yaw", (double) location.getYaw(), "pitch", (double) location.getPitch());
    }

    private static Map<String, Object> equipment(Player player) {
        Map<String, Object> equipment = new LinkedHashMap<>();
        equipment.put("mainHand", item(player.getInventory().getItemInMainHand()));
        equipment.put("offHand", item(player.getInventory().getItemInOffHand()));
        equipment.put("helmet", item(player.getInventory().getHelmet()));
        equipment.put("chestplate", item(player.getInventory().getChestplate()));
        equipment.put("leggings", item(player.getInventory().getLeggings()));
        equipment.put("boots", item(player.getInventory().getBoots()));
        return equipment;
    }

    private static Map<String, Object> item(ItemStack item) {
        if (item == null || item.getType().isAir()) return null;
        return Map.of("type", item.getType().getKey().toString(), "amount", item.getAmount(), "glint", !item.getEnchantments().isEmpty());
    }

    private AgentViewerSnapshot() {}
}
