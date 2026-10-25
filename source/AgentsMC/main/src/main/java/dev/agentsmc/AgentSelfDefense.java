package dev.agentsmc;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import org.bukkit.Location;
import org.bukkit.entity.Entity;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.entity.Projectile;
import org.bukkit.projectiles.ProjectileSource;

import net.citizensnpcs.api.npc.NPC;

/**
 * Automatic self-defense for every agent. A hostile that damages the body or
 * comes within {@link #RANGE} blocks is fought (sprint, best weapon) or fled
 * if health is under {@link #FLEE_HEALTH}, or immediately when unarmed against
 * a ranged mob. Flee and the walk home use {@code goto} (same A* as a player
 * walk) so a 2-block face or stair does not freeze the body.
 * An active goto/follow/protect/harvest
 * is paused (not cancelled) and resumed when the threat is killed, gone, fled,
 * or given up as unreachable. Player-issued fight/kill is exclusive. One
 * {@code engaged} event per threat. Explicit {@code goto}/{@code stop} override
 * proximity for {@link #OVERRIDE_MS}.
 */
final class AgentSelfDefense {
    static final double RANGE = 4;
    static final double FLEE_HEALTH = 12;
    /** Armed melee vs a melee mob fights down to this; below it, retreat. */
    static final double CRITICAL_HEALTH = 6;
    static final double FLEE_BLOCKS = 10;
    static final double RANGED_FLEE_BLOCKS = 16;
    static final int EVENT_LIMIT = 12;
    static final long IGNORE_MS = 20_000;
    static final long OVERRIDE_MS = 15_000;
    static final String REASON = "self_defense";

    static final class Session {
        String status = "idle", threat = "", trigger = "", weapon = "", reason = "";
        UUID target, pendingDamager, lastEngaged, heldFollowTarget, ignoreTarget;
        String pendingType = "", pendingTrigger = "";
        boolean ownedFight, heldGoto, heldFollow, heldProtect, heldHarvest;
        int engagements, flees, kills;
        long ignoreUntil, overrideUntil;
        Location home;
        int fleeNoPathX = Integer.MIN_VALUE, fleeNoPathY, fleeNoPathZ;
        final Deque<Map<String, Object>> events = new ArrayDeque<>();
    }

    private static final Map<UUID, Session> sessions = new HashMap<>();

    static boolean owning(NPC npc) {
        Session s = sessions.get(npc.getUniqueId());
        return s != null && s.ownedFight;
    }

    static boolean active(NPC npc) {
        Session s = sessions.get(npc.getUniqueId());
        return s != null && Set.of("fighting", "fleeing", "returning").contains(s.status);
    }

    /** Player-issued fight/kill is exclusive. Goto/follow/protect/harvest are paused instead. */
    static boolean actionRunning(boolean playerFight, boolean combatOwned, boolean protectActive, boolean chopping) {
        return playerFight && !combatOwned;
    }

    static boolean exclusiveAction(NPC npc) {
        return AgentCombat.busy(npc) && !owning(npc);
    }

    static boolean holding(NPC npc) {
        Session s = sessions.get(npc.getUniqueId());
        return s != null && (s.heldGoto || s.heldFollow || s.heldProtect || s.heldHarvest);
    }

    /** One engaged event per threat id; a new mob may log again. */
    static boolean skipEngaged(UUID already, UUID threat) {
        return threat != null && threat.equals(already);
    }

    static boolean shouldRespond(boolean actionRunning, boolean damagedByHostile, boolean hostileWithinRange) {
        return shouldEngage(actionRunning, damagedByHostile, hostileWithinRange, false, false);
    }

    /**
     * Damage always fights back. Unreachable (ignored) and player-overridden
     * threats are skipped until the timer elapses, unless they land a hit.
     */
    static boolean shouldEngage(boolean actionRunning, boolean damaged, boolean inRange,
                                boolean ignored, boolean overridden) {
        if (actionRunning) return false;
        if (damaged) return true;
        if (overridden || ignored) return false;
        return inRange;
    }

    static boolean ignored(UUID threat, UUID skip, long until, long now) {
        return threat != null && threat.equals(skip) && now < until;
    }

    static boolean overridden(long until, long now) {
        return now < until;
    }

    static boolean gaveUp(String status, String reason) {
        if (reason != null && reason.toLowerCase().contains("unreachable")) return true;
        if ("failed".equals(status) && reason != null && reason.toLowerCase().contains("timed out")) return true;
        return false;
    }

    static String choose(double health) {
        return choose(health, false, false);
    }

    static String choose(double health, boolean unarmed, boolean ranged) {
        if (unarmed && ranged) return "flee";
        if (health < CRITICAL_HEALTH) return "flee";
        if (!unarmed && !ranged) return "fight";
        return health < FLEE_HEALTH ? "flee" : "fight";
    }

    /** After the threat is gone: resume paused work, else goto the pre-flee stand. */
    static String afterSafe(boolean heldWork, boolean hasHome) {
        if (heldWork) return "resume";
        if (hasHome) return "return";
        return "idle";
    }

    static boolean keepFleeGoto(boolean navMoving) {
        return navMoving;
    }

    static boolean sameCell(int ax, int ay, int az, int bx, int by, int bz) {
        return ax == bx && ay == by && az == bz;
    }

    static boolean armed(String weapon) {
        if (weapon == null || weapon.isBlank()) return false;
        String n = weapon.toUpperCase();
        if (n.equals("AIR") || n.equals("HAND")) return false;
        if (n.contains("PICKAXE")) return false;
        return n.contains("SWORD") || n.contains("AXE") || n.contains("BOW")
                || n.contains("CROSSBOW") || n.contains("TRIDENT") || n.contains("MACE");
    }

    static boolean rangedType(String type) {
        if (type == null) return false;
        return Set.of("SKELETON", "STRAY", "BOGGED", "PILLAGER", "PIGLIN", "WITHER_SKELETON",
                "DROWNED", "ILLUSIONER", "BLAZE", "GHAST", "SHULKER", "WITCH", "PARCHED")
                .contains(type.toUpperCase());
    }

    /** Prefer a standable cell behind a solid block; else away from the threat. */
    static int[] coverOrAway(int hx, int hy, int hz, int tx, int ty, int tz, int min, int max, CoverWorld world) {
        int[] best = null;
        int bestScore = Integer.MIN_VALUE;
        for (int x = hx - max; x <= hx + max; x++) {
            for (int z = hz - max; z <= hz + max; z++) {
                int distSq = (x - hx) * (x - hx) + (z - hz) * (z - hz);
                if (distSq < min * min || distSq > max * max) continue;
                for (int y = hy - 1; y <= hy + 1; y++) {
                    if (world == null || !world.standable(x, y, z)) continue;
                    if (y - hy >= 2) continue;
                    int away = (x - hx) * (hx - tx) + (z - hz) * (hz - tz);
                    int cover = losBlocked(x, y + 1, z, tx, ty + 1, tz, world) ? 40 : 0;
                    int score = cover * 100 + away - distSq;
                    if (score > bestScore) {
                        bestScore = score;
                        best = new int[]{x, y, z};
                    }
                }
            }
        }
        if (best != null) return best;
        int dx = hx - tx, dz = hz - tz;
        if (dx == 0 && dz == 0) dx = 1;
        double len = Math.hypot(dx, dz);
        return new int[]{hx + (int) Math.round(dx / len * min), hy, hz + (int) Math.round(dz / len * min)};
    }

    static boolean losBlocked(int x0, int y0, int z0, int x1, int y1, int z1, CoverWorld world) {
        if (world == null) return false;
        int steps = Math.max(Math.abs(x1 - x0), Math.max(Math.abs(y1 - y0), Math.abs(z1 - z0)));
        if (steps < 2) return false;
        for (int i = 1; i < steps; i++) {
            int x = x0 + (x1 - x0) * i / steps;
            int y = y0 + (y1 - y0) * i / steps;
            int z = z0 + (z1 - z0) * i / steps;
            if (world.solid(x, y, z)) return true;
        }
        return false;
    }

    interface CoverWorld {
        boolean standable(int x, int y, int z);
        boolean solid(int x, int y, int z);
    }

    static boolean hostileWithin(double dist, double range) {
        return Double.isFinite(dist) && dist <= range + 1e-9;
    }

    static String triggerOf(boolean damagedByHostile, boolean hostileWithinRange) {
        if (damagedByHostile) return "damage";
        if (hostileWithinRange) return "proximity";
        return "";
    }

    static Map<String, Object> event(String type, String threat, String trigger, double health, long time) {
        Map<String, Object> e = new LinkedHashMap<>();
        e.put("type", type);
        e.put("threat", threat == null ? "" : threat);
        if (trigger != null && !trigger.isBlank()) e.put("trigger", trigger);
        e.put("health", Math.round(health * 10) / 10.0);
        e.put("time", time);
        return e;
    }

    static Map<String, Object> snapshot(NPC npc) {
        Session s = sessions.get(npc.getUniqueId());
        Map<String, Object> r = new LinkedHashMap<>();
        if (s == null) {
            r.put("active", false);
            r.put("status", "idle");
            r.put("events", List.of());
            return r;
        }
        r.put("active", active(npc));
        r.put("status", s.status);
        r.put("threat", s.threat);
        r.put("trigger", s.trigger);
        r.put("weapon", s.weapon);
        r.put("engagements", s.engagements);
        r.put("flees", s.flees);
        r.put("kills", s.kills);
        if (!s.reason.isEmpty()) r.put("reason", s.reason);
        long now = System.currentTimeMillis();
        if (s.ignoreTarget != null && now < s.ignoreUntil) {
            r.put("ignored", s.ignoreTarget.toString());
            r.put("ignoreMs", s.ignoreUntil - now);
        }
        if (now < s.overrideUntil) r.put("overrideMs", s.overrideUntil - now);
        r.put("events", new ArrayList<>(s.events));
        return r;
    }

    static void tick(NPC npc) {
        if (!AgentsMC.bodyUp(npc) || !(npc.getEntity() instanceof Player p)) {
            Session dead = sessions.get(npc.getUniqueId());
            if (dead != null && active(npc)) {
                dead.status = "failed";
                dead.reason = "Body despawned";
                dead.ownedFight = false;
            }
            return;
        }
        Session s = sessions.computeIfAbsent(npc.getUniqueId(), id -> new Session());
        finishOwnedFight(npc, s);
        // A leftover player-fight "busy" must not freeze an in-progress flee (fb-20).
        if (exclusiveAction(npc)) {
            if ("returning".equals(s.status)) finishReturn(s);
            if (!"fleeing".equals(s.status)) return;
        }
        if ("returning".equals(s.status)) {
            LivingEntity back = resolveThreat(p, s);
            if (back != null) {
                String weapon = weaponOf(p, s);
                if ("flee".equals(choose(p.getHealth(), !armed(weapon), rangedType(typeName(back)))))
                    flee(npc, p, back, s, s.pendingTrigger.isEmpty() ? "proximity" : s.pendingTrigger);
                else engage(npc, p, back, s, s.pendingTrigger.isEmpty() ? "proximity" : s.pendingTrigger);
                return;
            }
            if (!AgentNavigation.moving(npc)) finishReturn(s);
            return;
        }
        if ("fleeing".equals(s.status)) {
            LivingEntity still = currentThreat(p, s);
            boolean rangedUnarmed = still != null && !armed(weaponOf(p, s)) && rangedType(typeName(still));
            double dist = still == null ? 99 : still.getLocation().distance(p.getLocation());
            boolean covered = still != null && losBlocked(
                    p.getLocation().getBlockX(), p.getEyeLocation().getBlockY(), p.getLocation().getBlockZ(),
                    still.getLocation().getBlockX(), still.getEyeLocation().getBlockY(), still.getLocation().getBlockZ(),
                    coverWorld(p));
            if (still == null || (dist > RANGE + 6 && (covered || !rangedUnarmed))) {
                s.lastEngaged = null;
                try { p.setSprinting(false); } catch (RuntimeException ignored) {}
                becomeSafe(npc, s, p);
            } else if (!rangedUnarmed && p.getHealth() >= FLEE_HEALTH && hostileWithin(dist, RANGE)) {
                engage(npc, p, still, s, s.trigger.isEmpty() ? "proximity" : s.trigger);
            } else {
                driveFlee(npc, p, still, s);
            }
            return;
        }
        LivingEntity threat = resolveThreat(p, s);
        if (threat == null) {
            if ("fighting".equals(s.status) && !s.ownedFight) s.status = "idle";
            if (!active(npc)) resumeHeld(npc, s, p);
            return;
        }
        String trigger = s.pendingTrigger.isEmpty() ? triggerOf(false, true) : s.pendingTrigger;
        s.pendingTrigger = "";
        String weapon = weaponOf(p, s);
        if ("flee".equals(choose(p.getHealth(), !armed(weapon), rangedType(typeName(threat))))) {
            flee(npc, p, threat, s, trigger);
            return;
        }
        engage(npc, p, threat, s, trigger);
    }

    static void onDamaged(NPC npc, Entity damager) {
        LivingEntity src = attacker(damager);
        if (src == null || !AgentProtect.hostile(src)) return;
        Session s = sessions.computeIfAbsent(npc.getUniqueId(), id -> new Session());
        s.pendingDamager = src.getUniqueId();
        s.pendingType = typeName(src);
        s.pendingTrigger = "damage";
        double health = 20;
        try {
            if (AgentsMC.bodyUp(npc) && npc.getEntity() instanceof Player p) health = p.getHealth();
        } catch (RuntimeException ignored) {}
        record(s, "damaged", s.pendingType, "damage", health, src.getUniqueId());
    }

    static LivingEntity attacker(Entity damager) {
        if (damager == null) return null;
        Entity src = damager;
        try {
            if (damager instanceof Projectile projectile) {
                ProjectileSource shooter = projectile.getShooter();
                if (shooter instanceof Entity e) src = e;
            }
        } catch (RuntimeException ignored) {}
        return src instanceof LivingEntity living && living.isValid() && !living.isDead() ? living : null;
    }

    /** Drop owned self-defense so a player fight/follow/harvest can take over. */
    static void release(NPC npc) {
        disarm(npc, false);
    }

    /** Stop owned fight and ignore proximity for {@link #OVERRIDE_MS} so goto/stop can proceed. */
    static void yieldToPlayer(NPC npc) {
        disarm(npc, true);
    }

    static void stop(NPC npc) {
        disarm(npc, true);
    }

    private static void disarm(NPC npc, boolean override) {
        if (npc == null) return;
        Session s = sessions.computeIfAbsent(npc.getUniqueId(), id -> new Session());
        if (s.ownedFight || (override && AgentCombat.busy(npc))) {
            AgentCombat.stop(npc, override ? "Overridden by player action" : "Released");
            s.ownedFight = false;
        }
        long now = System.currentTimeMillis();
        if (override) {
            long until = now + OVERRIDE_MS;
            s.overrideUntil = until;
            if (s.target != null) {
                s.ignoreTarget = s.target;
                s.ignoreUntil = Math.max(s.ignoreUntil, until);
            }
            s.reason = "Overridden";
        }
        s.status = "idle";
        s.ownedFight = false;
        s.heldGoto = false;
        s.heldFollow = false;
        s.heldProtect = false;
        s.heldHarvest = false;
        s.heldFollowTarget = null;
        s.home = null;
        s.fleeNoPathX = Integer.MIN_VALUE;
        AgentNavigation.dropParked(npc);
    }

    static void forget(UUID id) {
        sessions.remove(id);
        AgentNavigation.dropParked(id);
    }

    private static void finishOwnedFight(NPC npc, Session s) {
        if (!s.ownedFight || AgentCombat.active(npc)) return;
        String status = AgentCombat.statusOf(npc);
        String reason = AgentCombat.reason(npc);
        UUID threat = s.target;
        int gained = AgentCombat.absorb(npc);
        s.ownedFight = false;
        if (gained > 0) {
            s.kills += gained;
            record(s, "killed", s.threat, s.trigger, healthOf(npc), threat);
            s.lastEngaged = null;
        } else if (gaveUp(status, reason)) {
            long until = System.currentTimeMillis() + IGNORE_MS;
            s.ignoreTarget = threat;
            s.ignoreUntil = until;
            record(s, "gave_up", s.threat, s.trigger, healthOf(npc), threat);
            s.lastEngaged = null;
        }
        if ("fighting".equals(s.status)) {
            s.status = "idle";
            s.reason = gained > 0 ? "" : (reason == null || reason.isBlank() ? "Fight ended" : reason);
        }
    }

    private static void engage(NPC npc, Player p, LivingEntity threat, Session s, String trigger) {
        UUID id = threat.getUniqueId();
        boolean same = id.equals(s.target) && ("fighting".equals(s.status) || s.ownedFight);
        if (!same) pauseHeld(npc, s);
        s.status = "fighting";
        s.threat = typeName(threat);
        s.trigger = trigger;
        s.target = id;
        s.reason = "";
        s.ownedFight = true;
        s.weapon = "";
        if (!skipEngaged(s.lastEngaged, id)) {
            s.engagements++;
            record(s, "engaged", s.threat, trigger, p.getHealth(), id);
            s.lastEngaged = id;
        }
        if (!same || !AgentCombat.active(npc)) {
            try { AgentCombat.fight(npc, p, threat, false); } catch (RuntimeException ignored) {}
        }
        try { s.weapon = AgentCombat.equipWeapon(p); } catch (RuntimeException ignored) {}
        AgentFocus.note(npc, "selfDefense", threat);
    }

    private static void flee(NPC npc, Player p, LivingEntity from, Session s, String trigger) {
        UUID id = from.getUniqueId();
        boolean already = "fleeing".equals(s.status) && id.equals(s.target);
        if (s.ownedFight) {
            s.ownedFight = false;
            AgentCombat.stop(npc);
        }
        if (!already && !"returning".equals(s.status)) {
            pauseHeld(npc, s);
            if (s.home == null) s.home = p.getLocation().clone();
            AgentNavigation.park(npc);
        }
        s.status = "fleeing";
        s.threat = typeName(from);
        s.trigger = trigger;
        s.target = id;
        boolean ranged = rangedType(s.threat);
        s.reason = !armed(weaponOf(p, s)) && ranged
                ? "Unarmed vs ranged; fled behind cover"
                : "Health below " + (int) FLEE_HEALTH + "; fled";
        if (!already) {
            s.flees++;
            record(s, "fled", s.threat, trigger, p.getHealth(), id);
            s.lastEngaged = null;
        }
        driveFlee(npc, p, from, s);
    }

    private static String weaponOf(Player p, Session s) {
        try {
            String w = AgentCombat.equipWeapon(p);
            if (w != null && !w.isBlank()) {
                s.weapon = w;
                return w;
            }
        } catch (RuntimeException ignored) {}
        return s.weapon == null ? "" : s.weapon;
    }

    private static CoverWorld coverWorld(Player p) {
        AgentSafeSpawn.Probe probe = AgentNavigation.worldProbe(p.getWorld());
        return new CoverWorld() {
            public boolean standable(int x, int y, int z) { return AgentSafeSpawn.standable(x, y, z, probe); }
            public boolean solid(int x, int y, int z) { return probe.floor(x, y, z) || !probe.open(x, y, z); }
        };
    }

    private static void driveFlee(NPC npc, Player p, LivingEntity from, Session s) {
        if (from == null || keepFleeGoto(AgentNavigation.moving(npc))) return;
        boolean ranged = rangedType(typeName(from));
        int span = (int) Math.round(ranged ? RANGED_FLEE_BLOCKS : FLEE_BLOCKS);
        Location here = p.getLocation(), threat = from.getLocation();
        int[] cell = coverOrAway(here.getBlockX(), here.getBlockY(), here.getBlockZ(),
                threat.getBlockX(), threat.getBlockY(), threat.getBlockZ(),
                8, span, coverWorld(p));
        Location dest = new Location(here.getWorld(), cell[0] + 0.5, cell[1], cell[2] + 0.5, here.getYaw(), here.getPitch());
        if (AgentNavigation.tooHighToJump(here.getY(), dest.getY(), AgentPathfinder.WALK))
            dest.setY(here.getY());
        int dx = AgentSafeSpawn.blockCoord(dest.getX()), dy = AgentSafeSpawn.blockCoord(dest.getY()),
                dz = AgentSafeSpawn.blockCoord(dest.getZ());
        if (sameCell(here.getBlockX(), here.getBlockY(), here.getBlockZ(), dx, dy, dz)) return;
        if (sameCell(s.fleeNoPathX, s.fleeNoPathY, s.fleeNoPathZ, dx, dy, dz)) return;
        try { p.setSprinting(true); } catch (RuntimeException ignored) {}
        try {
            AgentNavigation.start(npc, dest, false);
            s.fleeNoPathX = Integer.MIN_VALUE;
        } catch (RuntimeException failed) {
            s.fleeNoPathX = dx;
            s.fleeNoPathY = dy;
            s.fleeNoPathZ = dz;
        }
        AgentFocus.note(npc, "selfDefense", dest);
    }

    private static void becomeSafe(NPC npc, Session s, Player p) {
        String next = afterSafe(s.heldGoto || s.heldFollow || s.heldProtect || s.heldHarvest, s.home != null);
        if ("resume".equals(next)) {
            s.status = "idle";
            s.reason = "";
            resumeHeld(npc, s, p);
            return;
        }
        if ("return".equals(next)) {
            s.status = "returning";
            s.reason = "Returning to stand";
            s.threat = "";
            s.target = null;
            try {
                AgentNavigation.start(npc, s.home, false);
            } catch (RuntimeException failed) {
                finishReturn(s);
            }
            return;
        }
        s.status = "idle";
        s.reason = "";
        s.home = null;
    }

    private static void finishReturn(Session s) {
        s.status = "idle";
        s.reason = "";
        s.home = null;
        s.fleeNoPathX = Integer.MIN_VALUE;
    }

    static void pauseHeld(NPC npc, Session s) {
        if (s.heldGoto || s.heldFollow || s.heldProtect || s.heldHarvest) return;
        if (AgentNavigation.pause(npc, REASON)) s.heldGoto = true;
        if (AgentCombat.following(npc)) {
            s.heldFollow = true;
            s.heldFollowTarget = AgentCombat.targetId(npc);
            AgentCombat.park(npc);
        }
        if (AgentProtect.active(npc)) {
            AgentProtect.suspend(npc);
            s.heldProtect = true;
        }
        if (AgentBehaviors.chopping(npc)) {
            AgentBehaviors.suspendChop(npc);
            s.heldHarvest = true;
        }
    }

    static void resumeHeld(NPC npc, Session s, Player p) {
        if (s == null || !(s.heldGoto || s.heldFollow || s.heldProtect || s.heldHarvest)) return;
        boolean follow = s.heldFollow;
        UUID followId = s.heldFollowTarget;
        boolean protect = s.heldProtect;
        boolean harvest = s.heldHarvest;
        boolean go = s.heldGoto;
        s.heldFollow = false;
        s.heldFollowTarget = null;
        s.heldProtect = false;
        s.heldHarvest = false;
        s.heldGoto = false;
        if (follow && followId != null && p != null) {
            AgentNavigation.dropParked(npc);
            try {
                Entity e = p.getWorld().getEntity(followId);
                if (e != null && e.isValid() && !e.isDead()) AgentCombat.follow(npc, p, e);
            } catch (RuntimeException ignored) {}
            return;
        }
        if (protect) {
            AgentNavigation.dropParked(npc);
            AgentProtect.unsuspend(npc);
            return;
        }
        if (go) {
            AgentNavigation.unpark(npc);
            AgentNavigation.resume(npc);
        }
        if (harvest) AgentBehaviors.unsuspendChop(npc);
    }

    private static LivingEntity resolveThreat(Player p, Session s) {
        long now = System.currentTimeMillis();
        LivingEntity pending = null;
        if (s.pendingDamager != null) {
            try {
                Entity e = p.getWorld().getEntity(s.pendingDamager);
                if (e instanceof LivingEntity living && living.isValid() && !living.isDead() && AgentProtect.hostile(living))
                    pending = living;
            } catch (RuntimeException ignored) {}
            s.pendingDamager = null;
        }
        if (pending != null) {
            s.overrideUntil = 0;
            if (pending.getUniqueId().equals(s.ignoreTarget)) {
                s.ignoreTarget = null;
                s.ignoreUntil = 0;
            }
            return pending;
        }
        if (overridden(s.overrideUntil, now)) return null;
        UUID skip = ignored(s.ignoreTarget, s.ignoreTarget, s.ignoreUntil, now) ? s.ignoreTarget : null;
        return nearestHostile(p, RANGE, skip);
    }

    private static LivingEntity currentThreat(Player p, Session s) {
        if (s.target != null) {
            try {
                Entity e = p.getWorld().getEntity(s.target);
                if (e instanceof LivingEntity living && living.isValid() && !living.isDead() && AgentProtect.hostile(living))
                    return living;
            } catch (RuntimeException ignored) {}
        }
        return nearestHostile(p, RANGE + 4);
    }

    static LivingEntity nearestHostile(Player p, double range) {
        return nearestHostile(p, range, null);
    }

    static LivingEntity nearestHostile(Player p, double range, UUID skip) {
        if (p == null || p.getWorld() == null) return null;
        LivingEntity best = null;
        double score = Double.MAX_VALUE;
        try {
            for (Entity e : p.getNearbyEntities(range, range, range)) {
                if (!(e instanceof LivingEntity living) || living.equals(p) || !living.isValid() || living.isDead()) continue;
                if (!AgentProtect.hostile(living)) continue;
                if (skip != null && skip.equals(living.getUniqueId())) continue;
                double d = living.getLocation().distance(p.getLocation());
                if (!hostileWithin(d, range) || d >= score) continue;
                score = d;
                best = living;
            }
        } catch (RuntimeException ignored) {}
        return best;
    }

    private static void record(Session s, String type, String threat, String trigger, double health, UUID threatId) {
        if ("engaged".equals(type) && skipEngaged(s.lastEngaged, threatId)) return;
        s.events.addLast(event(type, threat, trigger, health, System.currentTimeMillis()));
        while (s.events.size() > EVENT_LIMIT) s.events.removeFirst();
    }

    private static String typeName(Entity e) {
        try { return e.getType().name(); } catch (RuntimeException ignored) { return ""; }
    }

    private static double healthOf(NPC npc) {
        try {
            if (AgentsMC.bodyUp(npc) && npc.getEntity() instanceof Player p) return p.getHealth();
        } catch (RuntimeException ignored) {}
        return 0;
    }

    private AgentSelfDefense() {}
}
