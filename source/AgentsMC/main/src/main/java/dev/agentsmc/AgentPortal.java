package dev.agentsmc;

import com.google.gson.JsonObject;
import net.citizensnpcs.api.npc.NPC;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.entity.Player;
import org.bukkit.event.player.PlayerTeleportEvent;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * fb-38 {@code use_portal}: walk fully into the nearest portal block (goto stops
 * 0.75 short, outside the frame), hold still inside it, wait for the world change
 * with a timeout, and report the new world and position. If vanilla does not move
 * a Citizens body after the vanilla wait, the plugin sends it through the same
 * portal (scaled nether coordinates, nearest existing portal) unless vanillaOnly.
 */
final class AgentPortal {
    static final int RADIUS_DEFAULT = 8, RADIUS_MAX = 16, TIMEOUT_DEFAULT = 20, TIMEOUT_MIN = 5, TIMEOUT_MAX = 60;
    /** Vanilla survival portal wait is 80 ticks; give it a little more before the fallback. */
    static final long VANILLA_WAIT_MS = 4_500;
    /** Feet within this horizontal distance of the portal column center count as inside. */
    static final double INSIDE_H = 0.3;

    static final class Session {
        final String id; final UUID npc; final String fromWorld; final Location portal; final long startedAt, deadline;
        final boolean vanillaOnly; final String kind;
        String phase = "approach"; long insideSince;
        Session(String id, UUID npc, String fromWorld, Location portal, long startedAt, long deadline, boolean vanillaOnly, String kind) {
            this.id = id; this.npc = npc; this.fromWorld = fromWorld; this.portal = portal; this.startedAt = startedAt;
            this.deadline = deadline; this.vanillaOnly = vanillaOnly; this.kind = kind;
        }
    }
    private static final Map<UUID, Session> sessions = new ConcurrentHashMap<>();
    private static final Map<String, Map<String, Object>> results = new ConcurrentHashMap<>();
    private static final Map<String, Long> deadlines = new ConcurrentHashMap<>();

    private AgentPortal() {}

    static boolean portalName(String material) { return AgentFinishActions.portalBlock(material); }

    /** Lowest portal block in a column: the cell the feet stand in. */
    static int footY(int y, java.util.function.IntPredicate portalAt) {
        int foot = y;
        for (int i = 0; i < 24 && portalAt.test(foot - 1); i++) foot--;
        return foot;
    }

    /** Pure: horizontal inside test for the portal column center. */
    static boolean inside(double x, double z, int bx, int bz) {
        return Math.hypot(x - (bx + .5), z - (bz + .5)) <= INSIDE_H;
    }

    /** Nether <-> overworld coordinate scale (vanilla 8). */
    static double scale(World.Environment from, World.Environment to) {
        if (from == World.Environment.NORMAL && to == World.Environment.NETHER) return 1.0 / 8;
        if (from == World.Environment.NETHER && to == World.Environment.NORMAL) return 8;
        return 1;
    }

    static Map<String, Object> start(NPC npc, Player p, JsonObject o) {
        AgentSurvivalActions.fields(o, List.of("world", "x", "y", "z", "radius", "timeout", "vanillaOnly", "background"), "action", "agent");
        int radius = o.has("radius") ? AgentSurvivalActions.num(o, "radius", 1, RADIUS_MAX) : RADIUS_DEFAULT;
        int timeout = o.has("timeout") ? AgentSurvivalActions.num(o, "timeout", TIMEOUT_MIN, TIMEOUT_MAX) : TIMEOUT_DEFAULT;
        boolean vanillaOnly = o.has("vanillaOnly") && o.get("vanillaOnly").isJsonPrimitive()
                && (o.get("vanillaOnly").getAsJsonPrimitive().isBoolean() ? o.get("vanillaOnly").getAsBoolean() : "true".equalsIgnoreCase(o.get("vanillaOnly").getAsString()));
        Block dest;
        if (o.has("x") && o.has("y") && o.has("z")) {
            dest = AgentSurvivalActions.target(p, o, false);
            AgentSurvivalActions.check(portalName(dest.getType().name()), "Target is not a portal block (" + dest.getType().name() + ")");
        } else {
            dest = nearest(p, radius);
            AgentSurvivalActions.check(dest != null, "No nether or end portal within " + radius + " blocks");
        }
        World w = dest.getWorld();
        int fy = footY(dest.getY(), y -> y >= w.getMinHeight() && portalName(w.getBlockAt(dest.getX(), y, dest.getZ()).getType().name()));
        Block foot = w.getBlockAt(dest.getX(), fy, dest.getZ());
        AgentBehaviors.cancel(npc);
        AgentSelfDefense.release(npc);
        AgentNavigation.noteWorld(npc, p.getWorld());
        long now = System.currentTimeMillis();
        String id = "portal-" + UUID.randomUUID().toString().substring(0, 8);
        Location center = foot.getLocation().add(0.5, 0, 0.5);
        Session s = new Session(id, npc.getUniqueId(), p.getWorld().getName(), center, now, now + timeout * 1000L, vanillaOnly,
                foot.getType().name());
        Session old = sessions.put(npc.getUniqueId(), s);
        if (old != null) finish(old, fail(old, "superseded by a new use_portal"));
        deadlines.put(id, s.deadline);
        if (inside(p.getLocation().getX(), p.getLocation().getZ(), foot.getX(), foot.getZ())
                && Math.abs(p.getLocation().getY() - fy) < 1.5) {
            s.phase = "inside"; s.insideSince = now;
        } else {
            // snapStandable=false: a portal cell is the destination itself, not a stand to snap off of.
            try { AgentNavigation.start(npc, center, false, false); } catch (RuntimeException ignored) {}
        }
        Map<String, Object> r = new LinkedHashMap<>();
        r.put("ok", true); r.put("state", "accepted"); r.put("portalId", id); r.put("portal", kind(s));
        r.put("portalBlock", Map.of("x", foot.getX(), "y", foot.getY(), "z", foot.getZ()));
        r.put("fromWorld", s.fromWorld); r.put("timeout", timeout);
        r.put("note", "Walking fully into the portal and waiting for the world change; inside a steps program this waits for the new world or the timeout");
        return r;
    }

    private static String kind(Session s) { return s.kind; }

    private static Block nearest(Player p, int radius) {
        Location at = p.getLocation(); World w = p.getWorld();
        Block best = null; double score = Double.MAX_VALUE;
        for (int dx = -radius; dx <= radius; dx++)
            for (int dz = -radius; dz <= radius; dz++)
                for (int dy = -3; dy <= 4; dy++) {
                    int x = at.getBlockX() + dx, y = at.getBlockY() + dy, z = at.getBlockZ() + dz;
                    if (y < w.getMinHeight() || y >= w.getMaxHeight() || !w.isChunkLoaded(x >> 4, z >> 4)) continue;
                    Block b = w.getBlockAt(x, y, z);
                    if (!portalName(b.getType().name())) continue;
                    double d = b.getLocation().add(.5, .5, .5).distanceSquared(at);
                    if (d < score) { score = d; best = b; }
                }
        return best;
    }

    /** Every server tick for each spawned agent body. */
    static void tick(NPC npc, Player p) {
        Session s = sessions.get(npc.getUniqueId());
        if (s == null) return;
        long now = System.currentTimeMillis();
        Location here = p.getLocation();
        if (here.getWorld() != null && !here.getWorld().getName().equals(s.fromWorld)) {
            AgentNavigation.noteWorld(npc, here.getWorld());
            finish(s, arrived(s, p, s.phase.equals("plugin") ? "plugin_fallback" : "vanilla"));
            return;
        }
        if (now > s.deadline) {
            AgentNavigation.stop(npc);
            finish(s, fail(s, "timeout: still in " + s.fromWorld + " after " + (s.deadline - s.startedAt) / 1000 + "s at "
                    + Math.round(here.getX() * 10) / 10.0 + "," + Math.round(here.getY() * 10) / 10.0 + "," + Math.round(here.getZ() * 10) / 10.0
                    + " (portal " + s.portal.getBlockX() + "," + s.portal.getBlockY() + "," + s.portal.getBlockZ() + ", phase " + s.phase + ")"));
            return;
        }
        int bx = s.portal.getBlockX(), bz = s.portal.getBlockZ();
        boolean in = inside(here.getX(), here.getZ(), bx, bz) && Math.abs(here.getY() - s.portal.getY()) < 1.5;
        if (s.phase.equals("approach")) {
            double h = Math.hypot(here.getX() - s.portal.getX(), here.getZ() - s.portal.getZ());
            Map<String, Object> nav = AgentNavigation.snapshot(npc);
            String st = String.valueOf(nav.get("status"));
            if (in || h <= 1.6 && !AgentNavigation.inProgress(st)) {
                AgentNavigation.stop(npc);
                s.phase = "inside"; s.insideSince = in ? now : 0;
            } else if ("failed".equals(st) && h > 1.6) {
                finish(s, fail(s, "could not reach the portal: " + nav.get("reason")));
                return;
            }
        }
        if (s.phase.equals("inside")) {
            if (!in) {
                // Walk the last ~1 block ourselves; goto counts 0.75 short as arrived.
                AgentNavigation.driveWalk(p, s.portal, false, AgentPathfinder.WALK);
                return;
            }
            AgentNavigation.haltBody(p);
            if (s.insideSince == 0) s.insideSince = now;
            if (!s.vanillaOnly && now - s.insideSince >= VANILLA_WAIT_MS) {
                s.phase = "plugin";
                Location to = destination(p, s);
                if (to == null) { finish(s, fail(s, "no destination world for " + s.kind + " from " + s.fromWorld)); return; }
                PlayerTeleportEvent.TeleportCause cause = s.kind.contains("END")
                        ? PlayerTeleportEvent.TeleportCause.END_PORTAL : PlayerTeleportEvent.TeleportCause.NETHER_PORTAL;
                // Citizens despawns/respawns a body across worlds; the world check above
                // reports arrival on a later tick.
                try { npc.teleport(to, cause); }
                catch (RuntimeException e) { finish(s, fail(s, "portal teleport failed (" + cause + "): " + e.getMessage())); }
            }
        }
    }

    /** Fallback destination: scaled coordinates, nearest existing portal within 16, else a safe stand. */
    static Location destination(Player p, Session s) {
        World from = p.getWorld();
        World.Environment want;
        if (s.kind.contains("END")) want = from.getEnvironment() == World.Environment.THE_END ? World.Environment.NORMAL : World.Environment.THE_END;
        else want = from.getEnvironment() == World.Environment.NETHER ? World.Environment.NORMAL : World.Environment.NETHER;
        World to = pick(from, want);
        if (to == null) return null;
        if (want == World.Environment.THE_END) {
            Location platform = new Location(to, 100.5, 49, 0.5);
            return AgentsMC.safeStandingOrWorldSpawn(platform);
        }
        if (s.kind.contains("END")) return AgentsMC.safeStandingOrWorldSpawn(to.getSpawnLocation());
        double k = scale(from.getEnvironment(), want);
        int x = (int) Math.floor(s.portal.getX() * k), z = (int) Math.floor(s.portal.getZ() * k);
        int y = Math.max(to.getMinHeight() + 1, Math.min(to.getMaxHeight() - 2, s.portal.getBlockY()));
        Location existing = nearPortal(to, x, y, z, 16);
        if (existing != null) return existing;
        return AgentsMC.safeStandingOrWorldSpawn(new Location(to, x + .5, y, z + .5));
    }

    private static World pick(World from, World.Environment want) {
        String base = from.getName().replaceAll("_(nether|the_end)$", "");
        String name = want == World.Environment.NETHER ? base + "_nether" : want == World.Environment.THE_END ? base + "_the_end" : base;
        World named = Bukkit.getWorld(name);
        if (named != null && named.getEnvironment() == want) return named;
        for (World w : Bukkit.getWorlds()) if (w.getEnvironment() == want) return w;
        return null;
    }

    private static Location nearPortal(World w, int cx, int cy, int cz, int r) {
        Location best = null; double score = Double.MAX_VALUE;
        int lo = Math.max(w.getMinHeight(), cy - 48), hi = Math.min(w.getMaxHeight() - 1, cy + 48);
        for (int x = cx - r; x <= cx + r; x++)
            for (int z = cz - r; z <= cz + r; z++) {
                w.getChunkAt(x >> 4, z >> 4);
                for (int y = lo; y <= hi; y++) {
                    if (!portalName(w.getBlockAt(x, y, z).getType().name())) continue;
                    if (portalName(w.getBlockAt(x, y - 1, z).getType().name())) continue;
                    double d = (x - cx) * (x - cx) + (z - cz) * (z - cz) + (y - cy) * (y - cy) * 0.25;
                    if (d < score) { score = d; best = new Location(w, x + .5, y, z + .5); }
                }
            }
        return best;
    }

    private static Map<String, Object> arrived(Session s, Player p, String via) {
        Location l = p.getLocation();
        Map<String, Object> r = new LinkedHashMap<>();
        r.put("ok", true); r.put("action", "use_portal"); r.put("state", "arrived"); r.put("status", "arrived");
        r.put("portalId", s.id); r.put("fromWorld", s.fromWorld); r.put("world", l.getWorld().getName());
        r.put("environment", l.getWorld().getEnvironment().name());
        r.put("position", Map.of("x", l.getX(), "y", l.getY(), "z", l.getZ()));
        r.put("via", via); r.put("seconds", Math.round((System.currentTimeMillis() - s.startedAt) / 100.0) / 10.0);
        return r;
    }

    private static Map<String, Object> fail(Session s, String why) {
        Map<String, Object> r = new LinkedHashMap<>();
        r.put("ok", false); r.put("action", "use_portal"); r.put("state", "failed"); r.put("status", "failed");
        r.put("portalId", s.id); r.put("fromWorld", s.fromWorld); r.put("world", s.fromWorld); r.put("error", why);
        return r;
    }

    private static void finish(Session s, Map<String, Object> r) {
        sessions.remove(s.npc, s);
        results.put(s.id, r);
        if (results.size() > 64) results.keySet().stream().findFirst().ifPresent(results::remove);
    }

    static Map<String, Object> snapshot(NPC npc) {
        Session s = npc == null ? null : sessions.get(npc.getUniqueId());
        if (s == null) return null;
        return Map.of("portalId", s.id, "phase", s.phase, "fromWorld", s.fromWorld);
    }

    /** Finished result for an id, or null while pending. Thread-safe for the bridge thread. */
    static Map<String, Object> result(String id) { return id == null ? null : results.get(id); }
    static long deadline(String id) { Long d = deadlines.get(id); return d == null ? 0 : d; }

    static void forget(UUID npc) { if (npc != null) sessions.remove(npc); }

    /** Bridge-thread wait for a use_portal step to settle. */
    static Map<String, Object> await(Map<String, Object> accepted) throws InterruptedException {
        String id = String.valueOf(accepted.get("portalId"));
        long until = deadline(id) + 3_000;
        while (System.currentTimeMillis() < until) {
            Map<String, Object> r = result(id);
            if (r != null) return r;
            Thread.sleep(100);
        }
        Map<String, Object> r = new LinkedHashMap<>(accepted);
        r.put("ok", false); r.put("state", "timeout"); r.put("error", "use_portal did not settle before its timeout");
        return r;
    }
}
