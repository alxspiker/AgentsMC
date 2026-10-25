package dev.agentsmc;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.OfflinePlayer;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.block.data.type.Bed;
import org.bukkit.block.data.type.RespawnAnchor;
import org.bukkit.entity.Player;

import net.citizensnpcs.api.CitizensAPI;
import net.citizensnpcs.api.npc.NPC;

/**
 * Bed / respawn-anchor spawn for agent bodies. Persisted on the NPC so death
 * respawns there even if the Citizens player entity forgets vanilla spawn.
 */
final class AgentRespawn {
    static final String KEY = "agentsmc-respawn";
    static final String AGENT_ID = "agentsmc-id";
    static final long CAMP_WINDOW_MS = 20_000;
    static final int CAMP_DEATHS = 2;
    static final int CAMP_RADIUS = 8;
    static final int RESPAWN_NODAMAGE_TICKS = 100;
    private static final Map<UUID, long[]> recentDeaths = new ConcurrentHashMap<>();
    /** Vanilla human bed/anchor homes (not NPC KEY). Live online scan overlays this. */
    private static final Map<UUID, Claim> humans = new ConcurrentHashMap<>();

    static final class Point {
        String world = "", kind = "bed";
        double x, y, z;
        float yaw, pitch;
    }

    static boolean isBed(String material) {
        if (material == null) return false;
        String k = material.toUpperCase(Locale.ROOT);
        return k.endsWith("_BED") || k.equals("BED");
    }

    static boolean isAnchor(String material) {
        return material != null && material.equalsIgnoreCase("RESPAWN_ANCHOR");
    }

    static String encode(String world, double x, double y, double z, float yaw, float pitch, String kind) {
        return (world == null ? "" : world) + ";" + x + ";" + y + ";" + z + ";" + yaw + ";" + pitch + ";"
                + (kind == null || kind.isBlank() ? "bed" : kind);
    }

    static Point parse(String raw) {
        if (raw == null || raw.isBlank()) return null;
        String[] p = raw.split(";");
        if (p.length < 6 || p[0].isBlank()) return null;
        try {
            Point out = new Point();
            out.world = p[0];
            out.x = Double.parseDouble(p[1]);
            out.y = Double.parseDouble(p[2]);
            out.z = Double.parseDouble(p[3]);
            out.yaw = Float.parseFloat(p[4]);
            out.pitch = Float.parseFloat(p[5]);
            out.kind = p.length > 6 && !p[6].isBlank() ? p[6] : "bed";
            return out;
        } catch (NumberFormatException e) { return null; }
    }

    /**
     * Persisted bed/anchor wins unless it is the death camp. Otherwise world spawn.
     * Vanilla NPC respawn is ignored when it sits on the death location (the
     * skeleton nest loop). Death location is last resort only.
     */
    static Location choose(Location persisted, Location vanilla, Location worldSpawn) {
        return choose(persisted, vanilla, worldSpawn, null);
    }
    static Location choose(Location persisted, Location vanilla, Location worldSpawn, Location deathAt) {
        if (usable(persisted) && !sameCamp(persisted, deathAt, CAMP_RADIUS)) return persisted;
        if (usable(worldSpawn)) return worldSpawn;
        if (usable(vanilla) && !sameCamp(vanilla, deathAt, CAMP_RADIUS)) return vanilla;
        return usable(worldSpawn) ? worldSpawn : vanilla;
    }

    /**
     * Voluntary {@code respawn} action: persisted bed/anchor, else a vanilla bed
     * that is not world spawn, else the starter spawn.
     */
    static Location chooseHome(Location persisted, Location vanilla, Location worldSpawn) {
        if (usable(persisted)) return persisted;
        if (usable(vanilla) && !sameCamp(vanilla, worldSpawn, 2)) return vanilla;
        if (usable(worldSpawn)) return worldSpawn;
        return vanilla;
    }

    /** Kind shown on the action result: bed, anchor, or world_spawn. */
    static String pickKind(boolean persisted, String persistedKind, boolean vanillaBed, boolean worldSpawn) {
        if (persisted) return persistedKind == null || persistedKind.isBlank() ? "bed" : persistedKind;
        if (vanillaBed) return "bed";
        return worldSpawn ? "world_spawn" : "bed";
    }

    static String kindOf(Location chosen, Location persisted, String persistedKind, Location worldSpawn) {
        if (usable(persisted) && sameCamp(chosen, persisted, 1.5))
            return persistedKind == null || persistedKind.isBlank() ? "bed" : persistedKind;
        if (usable(worldSpawn) && sameCamp(chosen, worldSpawn, 2)) return "world_spawn";
        return "bed";
    }
    static boolean sameCamp(Location a, Location b, double radius) {
        if (!usable(a) || !usable(b) || a.getWorld() == null || b.getWorld() == null) return false;
        try { if (!a.getWorld().equals(b.getWorld())) return false; }
        catch (RuntimeException ignored) { return false; }
        return sameCamp(a.getX(), a.getY(), a.getZ(), b.getX(), b.getY(), b.getZ(), radius);
    }
    static boolean sameCamp(double ax, double ay, double az, double bx, double by, double bz, double radius) {
        return Math.max(Math.abs(ax - bx), Math.abs(az - bz)) <= radius && Math.abs(ay - by) <= radius;
    }
    /** True when this body has died {@link #CAMP_DEATHS} times in {@link #CAMP_WINDOW_MS} near {@code at}. */
    static boolean repeatCamp(UUID id, Location at, long now) {
        if (id == null || !usable(at)) return false;
        long[] prev = recentDeaths.get(id);
        int bx = AgentSafeSpawn.blockCoord(at.getX()), by = AgentSafeSpawn.blockCoord(at.getY()), bz = AgentSafeSpawn.blockCoord(at.getZ());
        if (prev != null && now - prev[0] <= CAMP_WINDOW_MS
                && prev[2] == bx && prev[3] == by && prev[4] == bz) {
            prev[0] = now;
            prev[1]++;
            return prev[1] >= CAMP_DEATHS;
        }
        recentDeaths.put(id, new long[]{now, 1, bx, by, bz});
        return false;
    }
    static void forget(UUID id) { if (id != null) recentDeaths.remove(id); }

    static boolean usable(Location loc) {
        return loc != null && loc.getWorld() != null;
    }

    static Map<String, Object> view(Point p) {
        if (p == null) return null;
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("world", p.world);
        m.put("x", Math.round(p.x * 10) / 10.0);
        m.put("y", Math.round(p.y * 10) / 10.0);
        m.put("z", Math.round(p.z * 10) / 10.0);
        m.put("kind", p.kind);
        return m;
    }

    static Map<String, Object> view(NPC npc, Player player) {
        Point stored = npc != null && npc.data() != null ? parse(AgentErrors.text(npc.data().get(KEY, ""))) : null;
        if (stored != null) return view(stored);
        if (player != null) {
            try {
                Location r = player.getRespawnLocation();
                if (usable(r))
                    return view(parse(encode(r.getWorld().getName(), r.getX(), r.getY(), r.getZ(),
                            r.getYaw(), r.getPitch(), "bed")));
            } catch (Throwable ignored) {}
        }
        return null;
    }

    static Location location(NPC npc) {
        if (npc == null || npc.data() == null) return null;
        Point p = parse(AgentErrors.text(npc.data().get(KEY, "")));
        if (p == null) return null;
        World w = Bukkit.getWorld(p.world);
        if (w == null) return null;
        return new Location(w, p.x, p.y, p.z, p.yaw, p.pitch);
    }

    static void remember(NPC npc, Location loc, String kind) {
        if (npc == null || !usable(loc)) return;
        npc.data().setPersistent(KEY, encode(loc.getWorld().getName(), loc.getX(), loc.getY(), loc.getZ(),
                loc.getYaw(), loc.getPitch(), kind));
    }

    static void apply(Player player, Location loc) {
        if (player == null || !usable(loc)) return;
        try { player.setRespawnLocation(loc, true); } catch (Throwable ignored) {}
        try { player.setBedSpawnLocation(loc, true); } catch (Throwable ignored) {}
    }
    static void applyNoDamage(Player player) {
        if (player == null) return;
        try { player.setNoDamageTicks(RESPAWN_NODAMAGE_TICKS); } catch (Throwable ignored) {}
        try { player.setFireTicks(0); } catch (Throwable ignored) {}
    }

    static void restore(NPC npc, Player player) {
        Location loc = location(npc);
        if (loc != null) apply(player, loc);
    }

    /** Right-click or sleep: set spawn like a real player. Anchor only when charged. */
    static boolean claim(NPC npc, Player player, Block block) {
        if (npc == null || player == null || block == null) return false;
        Material type = block.getType();
        String kind;
        if (isBed(type.name())) kind = "bed";
        else if (isAnchor(type.name())) {
            kind = "anchor";
            try {
                if (block.getBlockData() instanceof RespawnAnchor anchor && anchor.getCharges() <= 0) return false;
            } catch (RuntimeException ignored) {}
        } else return false;
        Location loc = block.getLocation().add(0.5, 0.5, 0.5);
        loc.setYaw(player.getLocation().getYaw());
        loc.setPitch(player.getLocation().getPitch());
        remember(npc, loc, kind);
        apply(player, loc);
        return true;
    }

    static final class Claim {
        String agentId = "", name = "", kind = "bed", world = "";
        int x, y, z;
        boolean player;
        UUID uuid;
    }

    static int blockX(double x) { return (int) Math.floor(x); }

    /** True when (x,y,z) is the stored cell or the other half of that bed. */
    static boolean covers(String world, int x, int y, int z,
                          String storedWorld, double sx, double sy, double sz, int[] otherHalf) {
        if (world == null || storedWorld == null || !world.equals(storedWorld)) return false;
        int px = blockX(sx), py = blockX(sy), pz = blockX(sz);
        if (px == x && py == y && pz == z) return true;
        return otherHalf != null && otherHalf.length >= 3
                && px == otherHalf[0] && py == otherHalf[1] && pz == otherHalf[2];
    }

    static int[] otherHalf(Block block) {
        if (block == null) return null;
        try {
            if (block.getBlockData() instanceof Bed bed) {
                BlockFace face = bed.getFacing();
                int dx = face.getModX(), dz = face.getModZ();
                if (bed.getPart() == Bed.Part.FOOT) return new int[]{block.getX() + dx, block.getY(), block.getZ() + dz};
                return new int[]{block.getX() - dx, block.getY(), block.getZ() - dz};
            }
        } catch (RuntimeException ignored) {}
        return null;
    }

    static String stealError(String owner) {
        String who = owner == null || owner.isBlank() ? "another agent or player" : owner;
        return "Bed already claimed by " + who + "; pick an unclaimed bed so they can still respawn there";
    }

    static Map<String, Object> claimView(Claim c, String selfId) {
        Map<String, Object> out = new LinkedHashMap<>();
        if (c == null) {
            out.put("claimed", false);
            return out;
        }
        out.put("claimed", true);
        out.put("mine", !c.player && AgentIds.same(c.agentId, selfId));
        Map<String, Object> by = new LinkedHashMap<>();
        by.put("name", c.name);
        if (c.player) {
            by.put("player", true);
            if (c.uuid != null) by.put("uuid", c.uuid.toString());
        } else {
            by.put("agentId", c.agentId);
        }
        by.put("kind", c.kind);
        out.put("claimedBy", by);
        return out;
    }

    static Claim fromNpc(NPC npc, Point p) {
        if (npc == null || p == null) return null;
        Claim c = new Claim();
        c.agentId = AgentIds.publicId(npc.data().get(AGENT_ID));
        if (c.agentId == null) c.agentId = "";
        c.name = npc.getName() == null ? "" : npc.getName();
        c.kind = p.kind == null || p.kind.isBlank() ? "bed" : p.kind;
        c.world = p.world;
        c.x = blockX(p.x);
        c.y = blockX(p.y);
        c.z = blockX(p.z);
        return c;
    }

    static boolean isHuman(Player player) {
        if (player == null) return false;
        try {
            return !CitizensAPI.hasImplementation() || !CitizensAPI.getNPCRegistry().isNPC(player);
        } catch (Throwable ignored) { return true; }
    }

    /** World spawn is not a claimed bed. */
    static boolean starterSpawn(Location loc) {
        if (!usable(loc) || loc.getWorld() == null) return true;
        try { return sameCamp(loc, loc.getWorld().getSpawnLocation(), 2); }
        catch (RuntimeException ignored) { return false; }
    }

    static Block snapClaimBlock(Location loc) {
        if (loc == null || loc.getWorld() == null) return null;
        try {
            Block b = loc.getBlock();
            if (isBed(b.getType().name()) || isAnchor(b.getType().name())) return b;
            int[][] near = {{0, -1, 0}, {1, 0, 0}, {-1, 0, 0}, {0, 0, 1}, {0, 0, -1}, {0, 1, 0}};
            for (int[] o : near) {
                Block n = b.getRelative(o[0], o[1], o[2]);
                if (isBed(n.getType().name()) || isAnchor(n.getType().name())) return n;
            }
        } catch (RuntimeException ignored) {}
        return null;
    }

    static boolean chunkLoaded(Location loc) {
        if (loc == null || loc.getWorld() == null) return false;
        try { return loc.getWorld().isChunkLoaded(loc.getBlockX() >> 4, loc.getBlockZ() >> 4); }
        catch (RuntimeException ignored) { return false; }
    }

    static Claim fromHuman(String name, UUID id, Location loc, String kindHint) {
        if (id == null || !usable(loc) || starterSpawn(loc)) return null;
        Block bed = snapClaimBlock(loc);
        Location use = loc;
        String kind = kindHint == null || kindHint.isBlank() ? "bed" : kindHint;
        if (bed != null) {
            use = bed.getLocation().add(0.5, 0.5, 0.5);
            kind = isAnchor(bed.getType().name()) ? "anchor" : "bed";
        } else if (chunkLoaded(loc)) {
            return null;
        }
        if (use.getWorld() == null) return null;
        Claim c = new Claim();
        c.player = true;
        c.uuid = id;
        c.agentId = "";
        c.name = name == null ? "" : name;
        c.kind = kind;
        c.world = use.getWorld().getName();
        c.x = blockX(use.getX());
        c.y = blockX(use.getY());
        c.z = blockX(use.getZ());
        return c;
    }

    static Claim fromHuman(Player player) {
        if (!isHuman(player)) return null;
        Location r = null;
        try { r = player.getRespawnLocation(); } catch (Throwable ignored) {}
        return fromHuman(player.getName(), player.getUniqueId(), r, "bed");
    }

    static void rememberHuman(String name, UUID id, Location loc, String kind) {
        Claim c = fromHuman(name, id, loc, kind);
        if (id == null) return;
        if (c == null) humans.remove(id);
        else humans.put(id, c);
    }

    static void refreshHuman(Player player) {
        if (!isHuman(player)) return;
        Claim c = fromHuman(player);
        if (c == null) humans.remove(player.getUniqueId());
        else humans.put(player.getUniqueId(), c);
    }

    static void forgetHuman(UUID id) { if (id != null) humans.remove(id); }

    static Location offlineHome(OfflinePlayer off) {
        if (off == null) return null;
        try { return off.getRespawnLocation(false); }
        catch (Throwable ignored) {
            try { return off.getRespawnLocation(); } catch (Throwable ignored2) { return null; }
        }
    }

    static void loadHumans() {
        try {
            for (OfflinePlayer off : Bukkit.getOfflinePlayers()) {
                if (off == null || off.getUniqueId() == null) continue;
                Player online = off.getPlayer();
                if (online != null) { refreshHuman(online); continue; }
                Claim c = fromHuman(off.getName(), off.getUniqueId(), offlineHome(off), "bed");
                if (c != null) humans.put(off.getUniqueId(), c);
            }
        } catch (Throwable ignored) {}
        try {
            for (Player p : Bukkit.getOnlinePlayers()) refreshHuman(p);
        } catch (Throwable ignored) {}
    }

    static List<Claim> npcClaims() {
        List<Claim> out = new ArrayList<>();
        try {
            if (!CitizensAPI.hasImplementation()) return out;
            for (NPC n : CitizensAPI.getNPCRegistry()) {
                if (n == null || n.data() == null || !n.data().has(AGENT_ID)) continue;
                Point p = parse(AgentErrors.text(n.data().get(KEY, "")));
                if (p == null) continue;
                Claim c = fromNpc(n, p);
                if (c != null && !c.world.isBlank()) out.add(c);
            }
        } catch (Throwable ignored) {}
        return out;
    }

    static List<Claim> humanClaims() {
        Map<UUID, Claim> live = new LinkedHashMap<>(humans);
        try {
            for (Player p : Bukkit.getOnlinePlayers()) {
                if (!isHuman(p)) continue;
                Claim c = fromHuman(p);
                if (c != null) { live.put(p.getUniqueId(), c); humans.put(p.getUniqueId(), c); }
                else { live.remove(p.getUniqueId()); humans.remove(p.getUniqueId()); }
            }
        } catch (Throwable ignored) {}
        return new ArrayList<>(live.values());
    }

    static List<Claim> allClaims() {
        List<Claim> out = npcClaims();
        out.addAll(humanClaims());
        return out;
    }

    static Claim occupant(String world, int x, int y, int z, int[] otherHalf) {
        for (Claim c : allClaims()) {
            if (covers(world, x, y, z, c.world, c.x + 0.5, c.y + 0.5, c.z + 0.5, otherHalf))
                return c;
        }
        return null;
    }

    static Claim occupant(Block block) {
        if (block == null || block.getWorld() == null) return null;
        return occupant(block.getWorld().getName(), block.getX(), block.getY(), block.getZ(), otherHalf(block));
    }

    static String selfId(NPC npc) {
        return npc == null || npc.data() == null ? null : AgentIds.publicId(npc.data().get(AGENT_ID));
    }

    /** Sleep / set_spawn / right-click: refuse another agent or player's claimed bed. Own bed is fine. */
    static void requireUnclaimedOrMine(NPC npc, Block block) {
        if (block == null) return;
        if (!isBed(block.getType().name()) && !isAnchor(block.getType().name())) return;
        Claim c = occupant(block);
        if (c == null) return;
        if (AgentIds.same(c.agentId, selfId(npc))) return;
        throw new IllegalArgumentException(stealError(c.name));
    }

    static void attach(Map<String, Object> out, Block block, NPC npc) {
        if (out == null || block == null) return;
        if (!isBed(block.getType().name()) && !isAnchor(block.getType().name())) return;
        out.putAll(claimView(occupant(block), selfId(npc)));
    }

    static void annotateObjects(List<Map<String, Object>> objects, String world, NPC npc) {
        if (objects == null || world == null) return;
        String me = selfId(npc);
        for (Map<String, Object> obj : objects) {
            if (obj == null) continue;
            String type = String.valueOf(obj.get("type"));
            if (!isBed(type) && !isAnchor(type) && !"bed".equals(type) && !"respawn_anchor".equals(type))
                continue;
            Object pos = obj.get("position");
            if (!(pos instanceof List<?> xyz) || xyz.size() < 3) continue;
            int x = ((Number) xyz.get(0)).intValue();
            int y = ((Number) xyz.get(1)).intValue();
            int z = ((Number) xyz.get(2)).intValue();
            int[] half = null;
            try {
                World w = Bukkit.getWorld(world);
                if (w != null) half = otherHalf(w.getBlockAt(x, y, z));
            } catch (Throwable ignored) {}
            obj.putAll(claimView(occupant(world, x, y, z, half), me));
        }
    }

    static List<Map<String, Object>> nearby(Player player, NPC npc, int radius) {
        List<Map<String, Object>> out = new ArrayList<>();
        if (player == null || player.getWorld() == null || radius < 1) return out;
        String world = player.getWorld().getName();
        int ax = player.getLocation().getBlockX(), ay = player.getLocation().getBlockY(), az = player.getLocation().getBlockZ();
        String me = selfId(npc);
        for (Claim c : allClaims()) {
            if (!world.equals(c.world)) continue;
            int dx = c.x - ax, dy = c.y - ay, dz = c.z - az;
            if (Math.max(Math.max(Math.abs(dx), Math.abs(dz)), Math.abs(dy)) > radius) continue;
            Map<String, Object> row = claimView(c, me);
            row.put("x", c.x);
            row.put("y", c.y);
            row.put("z", c.z);
            row.put("kind", c.kind);
            row.put("d", Math.round(Math.hypot(dx, dz) * 10) / 10.0);
            out.add(row);
        }
        return out;
    }

    private AgentRespawn() {}
}
