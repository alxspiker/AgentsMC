package dev.agentsmc;

import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.entity.Entity;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Mob;
import org.bukkit.entity.Monster;
import org.bukkit.entity.Player;
import org.bukkit.metadata.MetadataValue;

import net.citizensnpcs.api.npc.NPC;

/**
 * Server-side bodyguard. Follows a named player at 2–4 blocks with the same
 * goto A* as a walk, holds on an overlook or no_path, fights hostiles in
 * radius, and never teleports. Protect does not resume after plugin enable
 * or server restart; persisted targets are cleared on disable.
 */
final class AgentProtect {
    static final String KEY = "agentsmc-protect";
    static final int DEFAULT_RADIUS = 16, MIN_RADIUS = 1, MAX_RADIUS = 32;
    static final double HOLD_MIN = 2, HOLD_MAX = 4, SPRINT_AT = 8;
    static final double RECOVER_HEALTH = 10;
    static final double CHASE_MARGIN = 4;
    static final double RETREAT_SPEED = 0.42;

    static final class Guard {
        String player = "";
        int radius = DEFAULT_RADIUS, kills;
        String state = "following", lastThreat = "", reason = "";
        boolean loaded, suspended, followNoPath;
        long lastFollowPathAt;
        Location lastFollowAt;
        Guard() {}
        Guard(String player, int radius, int kills) {
            this.player = player;
            this.radius = clampRadius(radius);
            this.kills = Math.max(0, kills);
        }
    }

    private static final Map<UUID, Guard> guards = new HashMap<>();

    static boolean active(NPC npc) {
        Guard g = guards.get(npc.getUniqueId());
        return g != null && running(g.state);
    }

    static boolean running(String state) {
        return state != null && SetOf.running.contains(state);
    }

    private static final class SetOf {
        static final java.util.Set<String> running = java.util.Set.of("following", "fighting", "chasing", "eating", "retreating", "waiting");
    }

    static Map<String, Object> start(NPC npc, Player body, Player ward, int radius) {
        if (body != null && ward != null && body.equals(ward))
            throw new IllegalArgumentException("Cannot protect self");
        if (!livingWard(ward))
            throw new IllegalArgumentException(offlineMessage(ward == null ? "that player" : ward.getName()));
        if (body.getWorld() == null || !body.getWorld().equals(ward.getWorld()))
            throw new IllegalArgumentException("Protect target must be in the same world");
        AgentNavigation.release(npc);
        AgentCombat.stop(npc);
        Guard g = new Guard(ward.getName(), radius, 0);
        g.loaded = true;
        g.state = "following";
        guards.put(npc.getUniqueId(), g);
        persist(npc, g);
        holdNear(npc, body, ward, body.getLocation().distance(ward.getLocation()));
        AgentFocus.note(npc, "protect", ward);
        Map<String, Object> r = snapshot(npc);
        r.put("state", "following");
        r.put("note", "Guarding " + ward.getName() + " within " + g.radius + " blocks; stop ends it");
        return r;
    }

    static void stop(NPC npc) {
        Guard g = guards.get(npc.getUniqueId());
        if (g != null && running(g.state)) {
            g.state = "stopped";
            g.reason = "Explicit stop";
        }
        clearPersisted(npc);
    }

    /** Drop in-memory guard and the NPC data key so restart cannot resume protect. */
    static void clearPersisted(NPC npc) {
        if (npc == null) return;
        guards.remove(npc.getUniqueId());
        persistClear(npc);
    }

    static Map<String, Object> snapshot(NPC npc) {
        Guard g = guard(npc);
        Map<String, Object> r = new LinkedHashMap<>();
        if (g == null || g.player.isEmpty()) {
            r.put("active", false);
            r.put("state", "idle");
            return r;
        }
        r.put("active", running(g.state));
        r.put("target", g.player);
        r.put("state", g.state);
        r.put("kills", g.kills);
        r.put("radius", g.radius);
        r.put("lastThreat", g.lastThreat);
        if (!g.reason.isEmpty()) r.put("reason", g.reason);
        return r;
    }

    static void suspend(NPC npc) {
        Guard g = guards.get(npc.getUniqueId());
        if (g == null) return;
        g.suspended = true;
        try { npc.getNavigator().cancelNavigation(); } catch (RuntimeException ignored) {}
    }
    static void unsuspend(NPC npc) {
        Guard g = guards.get(npc.getUniqueId());
        if (g != null) g.suspended = false;
    }
    static boolean suspended(NPC npc) {
        Guard g = guards.get(npc.getUniqueId());
        return g != null && g.suspended;
    }
    static void tick(NPC npc) {
        Guard g = guard(npc);
        if (g == null || !running(g.state) || g.suspended || AgentSelfDefense.holding(npc)) return;
        if (!AgentsMC.bodyUp(npc) || !(npc.getEntity() instanceof Player body)) {
            g.state = "failed";
            g.reason = "Body despawned";
            return;
        }
        Player ward;
        try { ward = findWard(body, g.player); }
        catch (IllegalArgumentException e) {
            g.state = "waiting";
            g.reason = e.getMessage() == null || e.getMessage().isBlank() ? offlineMessage(g.player) : e.getMessage();
            try { npc.getNavigator().cancelNavigation(); } catch (RuntimeException ignored) {}
            body.setSprinting(false);
            return;
        }
        if (!livingWard(ward)) {
            g.state = "waiting";
            g.reason = offlineMessage(g.player);
            try { npc.getNavigator().cancelNavigation(); } catch (RuntimeException ignored) {}
            body.setSprinting(false);
            return;
        }
        if (body.getWorld() == null || !body.getWorld().equals(ward.getWorld())) {
            g.state = "failed";
            g.reason = "Protect target changed world";
            persistClear(npc);
            return;
        }
        LivingEntity threat = pickThreat(body, ward, g.radius);
        if (AgentCombat.active(npc)) {
            UUID tid = AgentCombat.targetId(npc);
            Entity raw = tid == null ? null : body.getWorld().getEntity(tid);
            double distWard = raw != null && raw.getWorld() != null && raw.getWorld().equals(ward.getWorld())
                    ? raw.getLocation().distance(ward.getLocation()) : Double.POSITIVE_INFINITY;
            if (raw == null || !raw.isValid() || raw.isDead() || abandonChase(distWard, g.radius)) {
                AgentCombat.stop(npc, "Threat left guard radius");
                int left = AgentCombat.absorb(npc);
                if (left > 0) { g.kills += left; persist(npc, g); }
                g.reason = "Threat left guard radius";
            } else {
                g.state = AgentCombat.busy(npc) ? "fighting" : "chasing";
                return;
            }
        }
        int gained = AgentCombat.absorb(npc);
        if (gained > 0) { g.kills += gained; persist(npc, g); }
        boolean ate = false;
        if (body.getHealth() < AgentCombat.RETREAT_HEALTH) ate = AgentSurvivalActions.tryEat(body);
        g.state = nextState(g.state, body.getHealth(), false, false, false, threat != null, ate);
        if (isRetreat(g.state)) {
            if (ate) g.reason = "";
            else if (!AgentSurvivalActions.hasEdible(body)) g.reason = "Health low; no food in inventory";
            if (threat != null) backOff(npc, body, threat);
            AgentFocus.note(npc, "protect", threat != null ? threat : ward);
            return;
        }
        if (threat != null) {
            g.lastThreat = threat.getType().name();
            g.state = "fighting";
            persist(npc, g);
            try { AgentCombat.fight(npc, body, threat); } catch (RuntimeException ignored) {}
            return;
        }
        g.state = "following";
        if (g.reason.startsWith("Threat left") || g.reason.startsWith("Health low")) g.reason = "";
        double dist = body.getLocation().distance(ward.getLocation());
        holdNear(npc, body, ward, dist);
        AgentFocus.note(npc, "protect", ward);
    }

    /** Retreat while hurt and a threat is present; stay down until health reaches {@link #RECOVER_HEALTH}. */
    static boolean keepRetreat(double health, boolean retreating, boolean threatPresent) {
        if (!threatPresent) return false;
        if (health < AgentCombat.RETREAT_HEALTH) return true;
        return retreating && health < RECOVER_HEALTH;
    }

    static boolean isRetreat(String state) {
        return "retreating".equals(state) || "eating".equals(state);
    }

    /** Leave a chase once the mob is past the ward by radius plus a small margin. */
    static boolean abandonChase(double distFromWard, int radius) {
        return Double.isFinite(distFromWard) && distFromWard > radius + CHASE_MARGIN + 1e-9;
    }

    static String nextState(String state, double health, boolean combatActive, boolean combatBusy,
                            boolean abandon, boolean threat, boolean ate) {
        if (combatActive && !abandon) return combatBusy ? "fighting" : "chasing";
        if (keepRetreat(health, isRetreat(state), threat)) return ate ? "eating" : "retreating";
        if (threat) return "fighting";
        return "following";
    }

    /** Human player or spawned agent body. Agents are Citizens player NPCs, not "offline". */
    static Player findWard(Player self, String name) {
        if (name == null || name.isBlank()) return null;
        try {
            org.bukkit.entity.Entity e = AgentTargets.findNamed(self, name);
            if (e instanceof Player p && livingWard(p)) return p;
        } catch (IllegalArgumentException e) { throw e; }
        catch (RuntimeException ignored) {}
        try {
            Player human = AgentTargets.findHuman(name);
            if (livingWard(human)) return human;
        } catch (RuntimeException ignored) {}
        return findPlayer(name);
    }

    static Player findPlayer(String name) {
        if (name == null || name.isBlank()) return null;
        try {
            Player exact = Bukkit.getPlayerExact(name);
            if (livingWard(exact)) return exact;
            for (Player p : Bukkit.getOnlinePlayers())
                if (livingWard(p) && AgentTargets.namesEqual(p.getName(), name)) return p;
        } catch (Throwable ignored) {}
        return null;
    }

    static boolean livingWard(Player p) {
        if (p == null) return false;
        try { if (p.isDead() || !p.isValid()) return false; } catch (RuntimeException ignored) { return false; }
        try { if (p.isOnline()) return true; } catch (RuntimeException ignored) {}
        return isNpc(p);
    }

    static boolean isNpc(Player p) {
        if (p == null) return false;
        try {
            if (p.hasMetadata("NPC")) {
                for (MetadataValue v : p.getMetadata("NPC")) if (v != null && v.asBoolean()) return true;
            }
        } catch (RuntimeException ignored) {}
        return false;
    }

    static boolean onlineHuman(Player p) {
        return p != null && p.isOnline() && !isNpc(p);
    }

    static String offlineMessage(String name) {
        return AgentTargets.missing(name == null || name.isBlank() ? "that player" : name);
    }

    static int clampRadius(int radius) {
        if (radius < MIN_RADIUS) return DEFAULT_RADIUS;
        return Math.min(MAX_RADIUS, radius);
    }

    /** close <2, hold 2..4, walk 4..8, sprint >8. */
    static String followPace(double dist) {
        if (dist < HOLD_MIN) return "close";
        if (dist <= HOLD_MAX) return "hold";
        if (dist <= SPRINT_AT) return "walk";
        return "sprint";
    }

    static boolean hostileType(String type) {
        if (type == null || type.isBlank()) return false;
        String t = type.toUpperCase();
        return t.contains("ZOMBIE") || t.contains("SKELETON") || t.contains("CREEPER") || t.contains("SPIDER")
                || t.contains("ENDERMAN") || t.contains("WITCH") || t.contains("SLIME") || t.contains("MAGMA")
                || t.contains("PHANTOM") || t.contains("GHAST") || t.contains("BLAZE") || t.contains("GUARDIAN")
                || t.contains("SHULKER") || t.contains("WITHER") || t.equals("ENDER_DRAGON") || t.contains("HOGLIN")
                || t.contains("PIGLIN") || t.contains("PILLAGER") || t.contains("VINDICATOR") || t.contains("RAVAGER")
                || t.contains("EVOKER") || t.contains("VEX") || t.contains("DROWNED") || t.contains("HUSK")
                || t.contains("STRAY") || t.contains("BOGGED") || t.contains("BREEZE") || t.contains("WARDEN")
                || t.contains("SILVERFISH") || t.contains("ENDERMITE") || t.contains("GUARDIAN");
    }

    static boolean shouldEngage(boolean hostile, boolean targetsWard, boolean targetsAgent,
                                double distWard, double distAgent, int radius) {
        if (!hostile) return false;
        if (distWard > radius && distAgent > radius) return false;
        return targetsWard || targetsAgent || distWard <= 8 || distAgent <= 8;
    }

    static String encode(String player, int radius, int kills) {
        return player + ";" + clampRadius(radius) + ";" + Math.max(0, kills);
    }

    static Guard decode(String stored) {
        if (stored == null || stored.isBlank()) return null;
        String[] p = stored.split(";", -1);
        if (p.length < 2 || p[0].isBlank()) return null;
        int radius = DEFAULT_RADIUS, kills = 0;
        try { radius = Integer.parseInt(p[1]); } catch (NumberFormatException ignored) {}
        if (p.length >= 3) try { kills = Integer.parseInt(p[2]); } catch (NumberFormatException ignored) {}
        return new Guard(p[0], radius, kills);
    }

    private static Guard guard(NPC npc) {
        return guards.get(npc.getUniqueId());
    }

    private static void persist(NPC npc, Guard g) {
        try { npc.data().setPersistent(KEY, encode(g.player, g.radius, g.kills)); } catch (RuntimeException ignored) {}
    }

    private static void persistClear(NPC npc) {
        try { npc.data().remove(KEY); } catch (RuntimeException ignored) {}
    }

    private static LivingEntity pickThreat(Player agent, Player ward, int radius) {
        LivingEntity best = null;
        double score = Double.MAX_VALUE;
        for (Entity e : ward.getNearbyEntities(radius, radius, radius)) {
            LivingEntity living = asThreat(e, agent, ward, radius);
            if (living == null) continue;
            double d = Math.min(living.getLocation().distanceSquared(ward.getLocation()), living.getLocation().distanceSquared(agent.getLocation()));
            if (targets(living, ward) || targets(living, agent)) d -= 400;
            if (d < score) { score = d; best = living; }
        }
        if (best != null) return best;
        for (Entity e : agent.getNearbyEntities(radius, radius, radius)) {
            LivingEntity living = asThreat(e, agent, ward, radius);
            if (living == null) continue;
            double d = living.getLocation().distanceSquared(agent.getLocation());
            if (d < score) { score = d; best = living; }
        }
        return best;
    }

    private static LivingEntity asThreat(Entity e, Player agent, Player ward, int radius) {
        if (!(e instanceof LivingEntity living) || !living.isValid() || living.isDead()) return null;
        if (living.equals(agent) || living.equals(ward)) return null;
        if (!hostile(living)) return null;
        double dW = living.getLocation().distance(ward.getLocation());
        double dA = living.getLocation().distance(agent.getLocation());
        if (!shouldEngage(true, targets(living, ward), targets(living, agent), dW, dA, radius)) return null;
        return living;
    }

    static boolean hostile(Entity e) {
        if (e == null) return false;
        try { if (e instanceof Monster) return true; } catch (Throwable ignored) {}
        try { return hostileType(e.getType().name()); } catch (RuntimeException ignored) { return false; }
    }

    static boolean targets(LivingEntity mob, Entity who) {
        if (mob == null || who == null) return false;
        try {
            if (mob instanceof Mob m) {
                Entity t = m.getTarget();
                return t != null && t.equals(who);
            }
        } catch (RuntimeException ignored) {}
        return false;
    }

    private static void backOff(NPC npc, Player body, LivingEntity from) {
        try { npc.getNavigator().cancelNavigation(); } catch (RuntimeException ignored) {}
        org.bukkit.util.Vector away = body.getLocation().toVector().subtract(from.getLocation().toVector());
        if (away.lengthSquared() < 1e-4) away = new org.bukkit.util.Vector(1, 0, 0);
        away.setY(0).normalize();
        try { body.setSprinting(true); } catch (RuntimeException ignored) {}
        try {
            org.bukkit.util.Vector v = body.getVelocity();
            body.setVelocity(new org.bukkit.util.Vector(away.getX() * RETREAT_SPEED, Math.max(0, v.getY()), away.getZ() * RETREAT_SPEED));
        } catch (RuntimeException ignored) {}
    }

    private static void holdNear(NPC npc, Player body, Player ward, double dist) {
        Location dest = ward.getLocation();
        double dx = dest.getX() - body.getX(), dy = dest.getY() - body.getY(), dz = dest.getZ() - body.getZ();
        AgentDoors.openToward(npc, body.getLocation(), dest);
        Guard g = guards.get(npc.getUniqueId());
        if (AgentCombat.followHold(dist, dx, dy, dz)) {
            if (g != null) g.followNoPath = false;
            if (AgentNavigation.moving(npc)) AgentNavigation.release(npc);
            try { npc.getNavigator().cancelNavigation(); } catch (RuntimeException ignored) {}
            body.setSprinting(false);
            npc.faceLocation(dest);
            return;
        }
        boolean sprint = "sprint".equals(followPace(dist));
        boolean navigating = AgentNavigation.moving(npc);
        double moved = 0;
        try {
            if (g != null && g.lastFollowAt != null && dest.getWorld() != null && dest.getWorld().equals(g.lastFollowAt.getWorld()))
                moved = dest.distance(g.lastFollowAt);
        } catch (RuntimeException ignored) {}
        long since = g == null ? AgentCombat.FOLLOW_RETRY_MS : System.currentTimeMillis() - g.lastFollowPathAt;
        boolean noPath = g != null && g.followNoPath;
        if (!AgentCombat.followShouldRepath(navigating, dist, dx, dy, dz, moved, since, noPath)) {
            body.setSprinting(navigating && sprint);
            return;
        }
        body.setSprinting(sprint);
        try {
            AgentNavigation.start(npc, dest, false);
            if (g != null) {
                g.followNoPath = false;
                g.lastFollowPathAt = System.currentTimeMillis();
                g.lastFollowAt = dest.clone();
            }
        } catch (RuntimeException e) {
            if (g != null) {
                g.followNoPath = true;
                g.lastFollowPathAt = System.currentTimeMillis();
                g.lastFollowAt = dest.clone();
                g.reason = e.getMessage() == null || e.getMessage().isBlank() ? AgentPathfinder.NO_PATH : e.getMessage();
            }
            try { npc.getNavigator().cancelNavigation(); } catch (RuntimeException ignored) {}
            body.setSprinting(false);
            try { npc.faceLocation(dest); } catch (RuntimeException ignored) {}
        }
    }

    private AgentProtect() {}
}
