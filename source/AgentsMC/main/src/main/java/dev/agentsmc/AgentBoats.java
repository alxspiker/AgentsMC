package dev.agentsmc;

import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.entity.AbstractHorse;
import org.bukkit.entity.Boat;
import org.bukkit.entity.Entity;
import org.bukkit.entity.EntityType;
import org.bukkit.entity.Minecart;
import org.bukkit.entity.Player;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;
import org.bukkit.util.Vector;

import net.citizensnpcs.api.npc.NPC;

/**
 * Boat items spawn entities (they are not blocks). Mount/dismount ride
 * boats, minecarts and horses. Goto while seated in a boat steers by
 * setting velocity and yaw each tick at sprint-like speed.
 */
final class AgentBoats {
    /** Blocks per tick; player sprint is ~0.28, vanilla paddling is a bit faster. */
    static final double SPEED = 0.36;
    static final double RANGE = 5;

    static boolean isBoatItem(String key) {
        String k = bare(key);
        return k.endsWith("_boat") || k.equals("boat") || k.equals("chest_boat")
                || k.equals("bamboo_raft") || k.equals("bamboo_chest_raft")
                || k.endsWith("_raft") || k.endsWith("_chest_raft");
    }

    static boolean isBoatType(String type) {
        String t = bare(type).replace('-', '_');
        return t.endsWith("_boat") || t.equals("boat") || t.equals("chest_boat")
                || t.equals("bamboo_raft") || t.equals("bamboo_chest_raft")
                || t.endsWith("_raft") || t.endsWith("_chest_raft");
    }

    static boolean isMinecartType(String type) {
        return bare(type).contains("minecart");
    }

    static boolean isHorseType(String type) {
        String t = bare(type);
        return t.equals("horse") || t.equals("donkey") || t.equals("mule")
                || t.equals("skeleton_horse") || t.equals("zombie_horse")
                || t.equals("camel");
    }

    static boolean isRideableType(String type) {
        return isBoatType(type) || isMinecartType(type) || isHorseType(type);
    }

    static String entityTypeName(String itemKey) {
        return bare(itemKey).toUpperCase(Locale.ROOT);
    }

    static String itemForEntity(String type) {
        String t = bare(type);
        if (t.equals("boat") || t.equals("chest_boat")) return t.equals("chest_boat") ? "oak_chest_boat" : "oak_boat";
        return t;
    }

    static String kind(String type) {
        if (isBoatType(type)) return "boat";
        if (isMinecartType(type)) return "minecart";
        if (isHorseType(type)) return "horse";
        return "vehicle";
    }

    static boolean isUuid(String raw) {
        if (raw == null) return false;
        try { UUID.fromString(raw.trim()); return true; }
        catch (IllegalArgumentException e) { return false; }
    }

    /** Empty, nearest, boat, minecart, horse, a type name, or a UUID. */
    static String normalizeTarget(String raw) {
        if (raw == null || raw.isBlank()) return "nearest";
        String t = raw.trim();
        if (isUuid(t)) return t;
        String key = t.toLowerCase(Locale.ROOT);
        if (key.startsWith("minecraft:")) key = key.substring("minecraft:".length());
        if (key.equals("nearest") || key.equals("any") || key.equals("closest")) return "nearest";
        return key.replace('-', '_');
    }

    static boolean matchesFilter(String type, String filter) {
        String want = normalizeTarget(filter);
        if (want.equals("nearest")) return isRideableType(type);
        if (want.equals("boat")) return isBoatType(type);
        if (want.equals("minecart")) return isMinecartType(type);
        if (want.equals("horse")) return isHorseType(type);
        String have = bare(type);
        return have.equals(want) || have.equals(want.replace('-', '_'));
    }

    static boolean isWaterName(String material) {
        String m = bare(material);
        return m.equals("water") || m.equals("bubble_column") || m.equals("kelp") || m.equals("kelp_plant")
                || m.equals("seagrass") || m.equals("tall_seagrass");
    }

    static boolean isLavaName(String material) {
        return bare(material).equals("lava");
    }

    static boolean isAirName(String material) {
        String m = bare(material);
        return m.equals("air") || m.equals("cave_air") || m.equals("void_air");
    }

    /**
     * Boats may be placed on water, on a solid block, or in air/replaceable
     * space whose block below is water or solid.
     */
    static boolean canPlaceOn(String target, boolean targetLiquid, boolean targetSolid, boolean targetReplaceable,
                              String below, boolean belowLiquid, boolean belowSolid) {
        if (isLavaName(target) || isLavaName(below)) return false;
        if (isWaterName(target) || (targetLiquid && !isLavaName(target))) return true;
        if (targetSolid && !isAirName(target)) return true;
        if (isAirName(target) || targetReplaceable)
            return isWaterName(below) || (belowLiquid && !isLavaName(below)) || belowSolid;
        return false;
    }

    /** Offset from the target block origin to the entity spawn point. */
    static double[] spawnOffset(boolean targetWater, boolean targetSolid, boolean belowWater) {
        if (targetWater) return new double[]{0.5, 0.1, 0.5};
        if (targetSolid) return new double[]{0.5, 1.0, 0.5};
        if (belowWater) return new double[]{0.5, 0.0, 0.5};
        return new double[]{0.5, 0.0, 0.5};
    }

    /** {@code [vx, vz, yawDegrees]} toward the XZ target at {@code speed} blocks/tick. */
    static double[] velocity(double x, double z, double tx, double tz, double speed) {
        double dx = tx - x, dz = tz - z;
        double dist = Math.hypot(dx, dz);
        if (dist < 1e-9) return new double[]{0, 0, 0};
        double yaw = Math.toDegrees(Math.atan2(-dx, dz));
        return new double[]{dx / dist * speed, dz / dist * speed, yaw};
    }

    static double horizontal(double x, double z, double tx, double tz) {
        return Math.hypot(tx - x, tz - z);
    }

    /**
     * Terminal boat-goto status, or {@code null} to keep steering.
     * Shore before the destination is {@code blocked_shore} unless already
     * within {@link AgentNavigation#CLOSE}.
     */
    static String settle(double remain, boolean waterHere, boolean waterAhead) {
        if (remain <= AgentNavigation.EXACT) return "arrived";
        if (remain <= AgentNavigation.TOLERANCE) return "arrived_near";
        if (!waterHere || !waterAhead) {
            if (remain <= AgentNavigation.CLOSE) return "arrived_near";
            return "blocked_shore";
        }
        return null;
    }

    static boolean terminal(String status) {
        return "arrived".equals(status) || "arrived_near".equals(status) || "blocked_shore".equals(status);
    }

    static boolean shouldDismount(String status, boolean waterHere) {
        return "blocked_shore".equals(status) || (!waterHere && terminal(status));
    }

    static boolean ridingBoat(Player p) {
        if (p == null) return false;
        try {
            Entity v = p.getVehicle();
            return isBoatEntity(v);
        } catch (RuntimeException e) {
            return false;
        }
    }

    static boolean isBoatEntity(Entity e) {
        if (e == null) return false;
        try {
            if (e instanceof Boat) return true;
        } catch (Throwable ignored) {}
        try {
            return isBoatType(e.getType().name());
        } catch (RuntimeException ignored) {
            return false;
        }
    }

    static boolean isRideableEntity(Entity e) {
        if (e == null) return false;
        try {
            if (e instanceof Boat || e instanceof Minecart || e instanceof AbstractHorse) return true;
        } catch (Throwable ignored) {}
        try {
            return isRideableType(e.getType().name());
        } catch (RuntimeException ignored) {
            return false;
        }
    }

    static Map<String, Object> view(Player p) {
        if (p == null) return null;
        Entity v;
        try { v = p.getVehicle(); } catch (RuntimeException e) { return null; }
        if (v == null) return null;
        Map<String, Object> r = new LinkedHashMap<>();
        r.put("type", v.getType().name());
        r.put("uuid", v.getUniqueId().toString());
        r.put("kind", kind(v.getType().name()));
        r.put("x", v.getX());
        r.put("y", v.getY());
        r.put("z", v.getZ());
        r.put("passengers", v.getPassengers().size());
        return r;
    }

    static Entity resolveMount(Player p, String target, double range) {
        String want = normalizeTarget(target);
        if (isUuid(want)) {
            Entity e = Bukkit.getEntity(UUID.fromString(want));
            if (e == null || e.isDead() || e == p || e.getWorld() == null || !e.getWorld().equals(p.getWorld()))
                throw new IllegalArgumentException("Entity unavailable");
            if (e.getLocation().distanceSquared(p.getLocation()) > range * range)
                throw new IllegalArgumentException("Entity out of range");
            if (!isRideableEntity(e))
                throw new IllegalArgumentException("Target is not a boat, minecart, or horse");
            return e;
        }
        Entity best = null;
        double bestD = range * range;
        for (Entity e : p.getNearbyEntities(range, range, range)) {
            if (e == p || e.isDead() || !matchesFilter(e.getType().name(), want)) continue;
            double d = e.getLocation().distanceSquared(p.getLocation());
            if (d <= bestD) { bestD = d; best = e; }
        }
        return best;
    }

    static Map<String, Object> place(Player player, NPC npc, Block block, ItemStack hand) {
        return place(player, npc, block, hand, null);
    }

    static Map<String, Object> place(Player player, NPC npc, Block block, ItemStack hand, String facing) {
        String itemKey = hand.getType().getKey().getKey();
        Block below = block.getRelative(BlockFace.DOWN);
        boolean targetWater = isWaterBlock(block);
        boolean belowWater = isWaterBlock(below);
        boolean targetSolid = !block.isEmpty() && !block.isLiquid() && block.getType().isSolid();
        boolean belowSolid = !below.isEmpty() && !below.isLiquid() && below.getType().isSolid();
        boolean replaceable = AgentPlacement.replaceable(block.getType().name(), block.isReplaceable());
        if (!canPlaceOn(block.getType().name(), block.isLiquid(), targetSolid, replaceable,
                below.getType().name(), below.isLiquid(), belowSolid))
            throw new IllegalArgumentException("Boats place on water or a solid block (target is "
                    + block.getType().name() + " at " + block.getX() + "," + block.getY() + "," + block.getZ() + ")");
        EntityType type;
        try { type = EntityType.valueOf(entityTypeName(itemKey)); }
        catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("Cannot spawn a boat entity for " + itemKey);
        }
        double[] off = spawnOffset(targetWater, targetSolid && !targetWater, belowWater);
        float yaw = facing != null && AgentPlacement.horizontal(facing)
                ? AgentPlacement.yawOf(facing) : player.getLocation().getYaw();
        Location spawn = new Location(block.getWorld(), block.getX() + off[0], block.getY() + off[1], block.getZ() + off[2],
                yaw, 0);
        Entity boat = block.getWorld().spawnEntity(spawn, type);
        if (boat == null || !boat.isValid())
            throw new IllegalStateException("World refused to spawn " + type.name());
        ItemStack left = hand.clone();
        left.setAmount(hand.getAmount() - 1);
        player.getInventory().setItemInMainHand(left.getAmount() > 0 ? left : null);
        if (npc != null) AgentFocus.note(npc, "place", spawn);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("spawned", type.name());
        out.put("uuid", boat.getUniqueId().toString());
        out.put("x", boat.getX());
        out.put("y", boat.getY());
        out.put("z", boat.getZ());
        out.put("consumed", itemKey);
        out.put("remaining", left.getAmount());
        if (facing != null) out.put("facing", facing.toUpperCase(Locale.ROOT));
        out.put("note", "Spawned boat entity and consumed one item");
        return out;
    }

    static Entity boatNear(Player player, Location at, double radius) {
        if (at == null || at.getWorld() == null) return null;
        World world = at.getWorld();
        Entity best = null;
        double bestD = radius * radius;
        Location center = at.clone();
        if (center.getX() == Math.rint(center.getX()) && center.getZ() == Math.rint(center.getZ()))
            center.add(center.getX() == (int) center.getX() ? 0.5 : 0, 0, center.getZ() == (int) center.getZ() ? 0.5 : 0);
        try {
            for (Entity e : world.getNearbyEntities(center, radius, radius, radius)) {
                if (!isBoatEntity(e) || e.isDead()) continue;
                if (player != null && e.getWorld() != null && !e.getWorld().equals(player.getWorld())) continue;
                double d = e.getLocation().distanceSquared(center);
                if (d <= bestD) { bestD = d; best = e; }
            }
        } catch (RuntimeException ignored) {}
        return best;
    }

    static Map<String, Object> pickup(Player player, NPC npc, Entity boat) {
        if (!isBoatEntity(boat)) throw new IllegalArgumentException("Target is not a boat");
        if (player != null && boat.getWorld() != null && player.getWorld() != null
                && !boat.getWorld().equals(player.getWorld()))
            throw new IllegalArgumentException("Boat is in another world");
        if (player != null && !AgentReach.inAttackReach(player, boat)
                && boat.getLocation().distanceSquared(player.getLocation()) > RANGE * RANGE)
            throw new IllegalArgumentException("Boat out of range");
        String itemKey = itemForEntity(boat.getType().name());
        Material material = Material.matchMaterial(itemKey);
        if (material == null || !material.isItem())
            throw new IllegalArgumentException("No inventory item for " + boat.getType().name());
        int returned = 0;
        if (boat instanceof InventoryHolder holder) {
            for (ItemStack stack : holder.getInventory().getContents()) {
                if (stack == null || stack.getType().isAir()) continue;
                returned += stack.getAmount();
                Map<Integer, ItemStack> extra = player.getInventory().addItem(stack.clone());
                extra.values().forEach(left -> player.getWorld().dropItemNaturally(player.getLocation(), left));
            }
            holder.getInventory().clear();
        }
        for (Entity passenger : boat.getPassengers()) passenger.leaveVehicle();
        boat.remove();
        ItemStack item = new ItemStack(material, 1);
        Map<Integer, ItemStack> extra = player.getInventory().addItem(item);
        extra.values().forEach(left -> player.getWorld().dropItemNaturally(player.getLocation(), left));
        if (npc != null) AgentFocus.note(npc, "break", boat);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("picked", material.name());
        out.put("uuid", boat.getUniqueId().toString());
        out.put("chestItems", returned);
        out.put("note", "Picked the boat back up as an item");
        return out;
    }

    static boolean waterAt(Location loc) {
        if (loc == null || loc.getWorld() == null) return false;
        Block here = loc.getBlock();
        Block below = here.getRelative(BlockFace.DOWN);
        return isWaterBlock(here) || isWaterBlock(below);
    }

    static boolean waterAhead(Location from, Location to) {
        if (from == null || from.getWorld() == null) return false;
        double dx = to.getX() - from.getX(), dz = to.getZ() - from.getZ();
        double dist = Math.hypot(dx, dz);
        if (dist < 1e-9) return waterAt(from);
        Location next = from.clone();
        next.setX(from.getX() + dx / dist);
        next.setZ(from.getZ() + dz / dist);
        return waterAt(next);
    }

    static boolean isWaterBlock(Block b) {
        if (b == null) return false;
        Material m = b.getType();
        return m == Material.WATER || m == Material.BUBBLE_COLUMN || (b.isLiquid() && m != Material.LAVA)
                || isWaterName(m.name());
    }

    static void steer(Entity boat, Player rider, Location toward) {
        if (boat == null || toward == null) return;
        Location here = boat.getLocation();
        double[] v = velocity(here.getX(), here.getZ(), toward.getX(), toward.getZ(), SPEED);
        float yaw = (float) v[2];
        try { boat.setRotation(yaw, here.getPitch()); } catch (RuntimeException ignored) {}
        if (rider != null) {
            try { rider.setRotation(yaw, rider.getLocation().getPitch()); } catch (RuntimeException ignored) {}
        }
        double keepY = 0;
        try { keepY = Math.max(-0.04, Math.min(0.08, boat.getVelocity().getY())); } catch (RuntimeException ignored) {}
        boat.setVelocity(new Vector(v[0], keepY, v[1]));
    }

    static void halt(Entity boat) {
        if (boat == null) return;
        try { boat.setVelocity(new Vector(0, Math.min(0, boat.getVelocity().getY()), 0)); }
        catch (RuntimeException ignored) {}
    }

    private static String bare(String raw) {
        if (raw == null) return "";
        String key = raw.trim().toLowerCase(Locale.ROOT);
        if (key.startsWith("minecraft:")) key = key.substring("minecraft:".length());
        return key.replace('-', '_');
    }

    private AgentBoats() {}
}
