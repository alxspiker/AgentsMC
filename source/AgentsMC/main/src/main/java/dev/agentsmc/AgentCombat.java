package dev.agentsmc;

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
import org.bukkit.inventory.ItemStack;
import org.bukkit.util.Vector;

import net.citizensnpcs.api.ai.Navigator;
import net.citizensnpcs.api.npc.NPC;

/**
 * Sprint-chase and background melee. Follow/fight never teleport.
 * Follow walks with the same goto A* as a player walk (no Citizens entity
 * re-path every tick). Fight chase still uses entity targeting.
 */
final class AgentCombat {
    static final float SPRINT = 1.3f;
    static final long LIMIT_MS = 30_000;
    static final long GIVE_UP_MS = 5_000;
    static final long FOLLOW_RETRY_MS = 2_000;
    static final double CLOSE_BLOCKS = 0.25;
    static final double RETREAT_HEALTH = 6;
    static final double FOLLOW_HOLD_MIN = 2, FOLLOW_HOLD_MAX = 4, FOLLOW_SPRINT_AT = 8, FOLLOW_RETARGET = 2;
    static final String[] RANKS = {"WOODEN", "STONE", "COPPER", "GOLDEN", "IRON", "DIAMOND", "NETHERITE"};

    private static final Map<UUID, Bout> bouts = new HashMap<>();

    static final class Bout {
        UUID target;
        boolean fight;
        String status = "following", reason = "", weapon = "";
        int hits, kills, shots, arrowsUsed;
        double damageTaken, lastHealth, startDist = Double.POSITIVE_INFINITY, bestDist = Double.POSITIVE_INFINITY;
        long startedAt = System.currentTimeMillis(), lastProgressAt = System.currentTimeMillis(), lastCloseAt = System.currentTimeMillis(), drawAt, lastPathAt;
        boolean drawing, noPath;
        org.bukkit.Location last;
    }

    static boolean busy(NPC npc) {
        Bout b = bouts.get(npc.getUniqueId());
        return b != null && b.fight && Set.of("fighting", "chasing", "aiming").contains(b.status);
    }

    static boolean active(NPC npc) {
        Bout b = bouts.get(npc.getUniqueId());
        return b != null && Set.of("following", "fighting", "chasing", "aiming").contains(b.status);
    }

    static Map<String, Object> follow(NPC npc, Player p, Entity target) {
        AgentNavigation.release(npc);
        Bout b = new Bout();
        b.target = target.getUniqueId();
        b.fight = false;
        b.status = "following";
        b.lastHealth = p.getHealth();
        b.last = target.getLocation().clone();
        bouts.put(npc.getUniqueId(), b);
        double startDist;
        try { startDist = p.getLocation().distance(target.getLocation()); }
        catch (RuntimeException e) { startDist = Double.POSITIVE_INFINITY; }
        applyFollow(npc, p, target, followSprint(startDist));
        AgentFocus.note(npc, "follow", target);
        Map<String, Object> r = snapshot(npc);
        r.put("following", target.getUniqueId().toString());
        r.put("state", "following");
        return r;
    }

    static boolean following(NPC npc) {
        Bout b = bouts.get(npc.getUniqueId());
        return b != null && !b.fight && Set.of("following", "fighting", "chasing").contains(b.status);
    }
    static UUID targetId(NPC npc) {
        Bout b = bouts.get(npc.getUniqueId());
        return b == null ? null : b.target;
    }
    /** Drop a follow bout so self-defense can fight without "Explicit stop". */
    static void park(NPC npc) {
        Bout b = bouts.get(npc.getUniqueId());
        if (b != null && !b.fight) bouts.remove(npc.getUniqueId());
    }
    static Map<String, Object> fight(NPC npc, Player p, LivingEntity target) {
        return fight(npc, p, target, true);
    }
    static Map<String, Object> fight(NPC npc, Player p, LivingEntity target, boolean supersedeNav) {
        if (supersedeNav) AgentNavigation.release(npc);
        Bout b = new Bout();
        b.target = target.getUniqueId();
        b.fight = true;
        b.status = "fighting";
        b.lastHealth = p.getHealth();
        b.last = p.getLocation().clone();
        try { b.startDist = p.getLocation().distance(target.getLocation()); }
        catch (RuntimeException e) { b.startDist = Double.POSITIVE_INFINITY; }
        b.bestDist = b.startDist;
        b.lastCloseAt = b.startedAt;
        b.weapon = equipWeapon(p);
        bouts.put(npc.getUniqueId(), b);
        applyChase(npc, p, target);
        AgentFocus.note(npc, "fight", target);
        Map<String, Object> r = snapshot(npc);
        r.put("state", "fighting");
        r.put("note", "Bow/crossbow at 6-24 with line of sight, else sprint to melee; observe.fight and observe.ranged report shots");
        return r;
    }

    /** Take finished-bout kills and drop the bout so protect can resume. */
    static int absorb(NPC npc) {
        Bout b = bouts.get(npc.getUniqueId());
        if (b == null || active(npc)) return 0;
        int k = b.kills;
        bouts.remove(npc.getUniqueId());
        return k;
    }

    static void stop(NPC npc) {
        stop(npc, "Explicit stop");
    }
    static void stop(NPC npc, String reason) {
        Bout b = bouts.get(npc.getUniqueId());
        if (b != null && Set.of("following", "fighting", "chasing", "aiming").contains(b.status)) {
            b.status = "stopped";
            b.reason = reason == null || reason.isBlank() ? "Explicit stop" : reason;
        }
        if (npc != null) AgentRanged.clearDraw(npc.getUniqueId());
        try { if (npc != null) npc.getNavigator().cancelNavigation(); } catch (RuntimeException ignored) {}
        if (AgentsMC.bodyUp(npc) && npc.getEntity() instanceof Player p) p.setSprinting(false);
    }

    static Map<String, Object> snapshot(NPC npc) {
        Bout b = bouts.get(npc.getUniqueId());
        Map<String, Object> r = new LinkedHashMap<>();
        if (b == null) {
            r.put("status", "idle");
            r.put("active", false);
            return r;
        }
        r.put("status", b.status);
        r.put("active", active(npc));
        r.put("target", b.target.toString());
        r.put("hits", b.hits);
        r.put("kills", b.kills);
        r.put("shots", b.shots);
        r.put("arrowsUsed", b.arrowsUsed);
        r.put("drawing", b.drawing);
        r.put("damageTaken", Math.round(b.damageTaken * 10) / 10.0);
        if (!b.weapon.isEmpty()) r.put("weapon", b.weapon);
        if (!b.reason.isEmpty()) r.put("reason", b.reason);
        return r;
    }

    static String reason(NPC npc) {
        Bout b = bouts.get(npc.getUniqueId());
        return b == null || b.reason == null ? "" : b.reason;
    }

    static String statusOf(NPC npc) {
        Bout b = bouts.get(npc.getUniqueId());
        return b == null ? "idle" : b.status;
    }

    static void tick(NPC npc) {
        Bout b = bouts.get(npc.getUniqueId());
        if (b == null || !Set.of("following", "fighting", "chasing", "aiming").contains(b.status)) return;
        if (!AgentsMC.bodyUp(npc) || !(npc.getEntity() instanceof Player p)) {
            b.status = "failed";
            b.reason = "Body despawned";
            return;
        }
        double hp = p.getHealth();
        if (hp < b.lastHealth) b.damageTaken += b.lastHealth - hp;
        b.lastHealth = hp;
        Entity raw = p.getWorld().getEntity(b.target);
        if (!(raw instanceof LivingEntity target) || !target.isValid() || target.isDead()) {
            if (b.fight) {
                b.kills++;
                b.status = "killed";
                b.reason = "";
            } else {
                b.status = "failed";
                b.reason = "Follow target is gone";
            }
            p.setSprinting(false);
            npc.getNavigator().cancelNavigation();
            return;
        }
        if (!b.fight) {
            tickFollow(npc, p, target, b);
            return;
        }
        if (b.fight && System.currentTimeMillis() - b.startedAt > LIMIT_MS) {
            b.status = "failed";
            b.reason = "Fight timed out after 30s";
            p.setSprinting(false);
            npc.getNavigator().cancelNavigation();
            return;
        }
        if (b.fight && hp < RETREAT_HEALTH) {
            retreat(npc, p, target, b);
            return;
        }
        if (b.fight && AgentRanged.useRanged(p, target)) {
            if (tickRanged(npc, p, target, b)) return;
        } else if (b.fight) {
            b.drawing = false;
            b.drawAt = 0;
            AgentRanged.clearDraw(npc.getUniqueId());
        }
        if (b.fight && (AgentReach.inAttackReach(p, target) || AgentRanged.preferMelee(AgentReach.nearest(p.getEyeLocation(), target)))) {
            npc.getNavigator().cancelNavigation();
            p.setSprinting(false);
            b.status = "fighting";
            b.drawing = false;
            b.drawAt = 0;
            b.weapon = equipWeapon(p);
            npc.faceLocation(target.getLocation());
            AgentFocus.note(npc, "fight", target);
            if (AgentReach.inAttackReach(p, target) && readyToSwing(p)) {
                p.attack(target);
                AgentHunger.exhaust(p, AgentHunger.ATTACK);
                b.hits++;
                if (target.isDead() || !target.isValid()) {
                    b.kills++;
                    b.status = "killed";
                    b.reason = "";
                }
            }
            if (AgentReach.inAttackReach(p, target)) return;
        }
        if (b.fight && !"aiming".equals(b.status)) b.status = "chasing";
        Location here = p.getLocation();
        long now = System.currentTimeMillis();
        if (b.last == null || here.distance(b.last) > 0.015) {
            b.lastProgressAt = now;
            b.last = here.clone();
        }
        applyChase(npc, p, target);
        if (b.fight && !AgentReach.inAttackReach(p, target)) {
            double dist;
            try { dist = p.getLocation().distance(target.getLocation()); }
            catch (RuntimeException e) { dist = Double.POSITIVE_INFINITY; }
            noteClosed(b, dist, now);
            String fail = chaseFail(false, npc.getNavigator().isNavigating(), canNavigate(npc, target),
                    now - b.lastProgressAt, b.hits, now - b.lastCloseAt);
            if (fail != null) {
                b.status = "failed";
                b.reason = fail;
                p.setSprinting(false);
                npc.getNavigator().cancelNavigation();
                return;
            }
        }
        AgentFocus.note(npc, b.fight ? "fight" : "follow", target);
    }

    /** Stand, draw, and fire. Returns true while aiming or after a shot so chaseFail does not run. */
    static boolean tickRanged(NPC npc, Player p, LivingEntity target, Bout b) {
        npc.getNavigator().cancelNavigation();
        p.setSprinting(false);
        String weapon = AgentRanged.equip(p);
        if (weapon.isEmpty()) {
            b.drawing = false;
            b.drawAt = 0;
            b.status = "fighting";
            AgentRanged.clearDraw(npc.getUniqueId());
            return false;
        }
        b.status = "aiming";
        b.drawing = true;
        b.weapon = weapon;
        long now = System.currentTimeMillis();
        if (b.drawAt == 0) b.drawAt = now;
        npc.faceLocation(target.getLocation());
        AgentFocus.note(npc, "fight", target);
        AgentRanged.noteDraw(npc.getUniqueId(), weapon, b.drawAt, now);
        if (now - b.drawAt < AgentRanged.drawMs(weapon)) return true;
        AgentRanged.Shot shot = AgentRanged.fire(p, AgentRanged.aimPoint(target), AgentRanged.velocityOf(target));
        if (!shot.ok) {
            b.drawing = false;
            b.drawAt = 0;
            b.status = "fighting";
            AgentRanged.clearDraw(npc.getUniqueId());
            return false;
        }
        b.shots++;
        if (shot.consumed) b.arrowsUsed++;
        b.drawAt = 0;
        b.drawing = false;
        b.status = "fighting";
        AgentHunger.exhaust(p, AgentHunger.ATTACK);
        AgentRanged.noteShot(npc.getUniqueId(), shot, target.getUniqueId().toString());
        return true;
    }

    static String chaseFail(boolean inReach, boolean navigating, Boolean canNavigate, long idleMs) {
        return chaseFail(inReach, navigating, canNavigate, idleMs, 0, 0);
    }

    static String chaseFail(boolean inReach, boolean navigating, Boolean canNavigate, long idleMs,
                            int hits, long msSinceClose) {
        if (inReach) return null;
        if (Boolean.FALSE.equals(canNavigate)) return "target unreachable";
        if (!navigating) return "target unreachable";
        if (noChaseProgress(hits, msSinceClose)) return "target unreachable";
        if (idleMs >= GIVE_UP_MS) return "target unreachable";
        return null;
    }

    /** No hits and no closing on the target for {@link #GIVE_UP_MS}. Body jitter is not progress. */
    static boolean noChaseProgress(int hits, long msSinceClose) {
        return hits <= 0 && msSinceClose >= GIVE_UP_MS;
    }

    static boolean closedGap(double bestDist, double nowDist) {
        return Double.isFinite(nowDist) && Double.isFinite(bestDist) && nowDist + CLOSE_BLOCKS < bestDist;
    }

    static void noteClosed(Bout b, double dist, long now) {
        if (b == null || !closedGap(b.bestDist, dist)) return;
        b.bestDist = dist;
        b.lastCloseAt = now;
    }

    static Boolean canNavigate(NPC npc, Entity target) {
        try { return npc.getNavigator().canNavigateTo(target.getLocation()); }
        catch (RuntimeException ignored) { return null; }
    }

    /** close <2, hold 2..4, walk 4..8, sprint >8. */
    static String followPace(double dist) {
        if (dist < FOLLOW_HOLD_MIN) return "close";
        if (dist <= FOLLOW_HOLD_MAX) return "hold";
        if (dist <= FOLLOW_SPRINT_AT) return "walk";
        return "sprint";
    }

    static boolean followHold(double dist) {
        return dist <= FOLLOW_HOLD_MAX + 1e-9;
    }

    /** Stacked over a drop the A* walker will not take (horiz ≤4 and |dy| > MAX_DROP). */
    static boolean followOverlook(double horiz, double dy) {
        return horiz <= FOLLOW_HOLD_MAX + 1e-9 && Math.abs(dy) > AgentPathfinder.MAX_DROP + 1e-9;
    }

    /** 3D hold band, or an overlook the body cannot walk down. */
    static boolean followHold(double dist, double dx, double dy, double dz) {
        if (followHold(dist)) return true;
        return followOverlook(Math.hypot(dx, dz), dy);
    }

    /** Navigator complete is never a follow failure: hold if close, otherwise re-path. */
    static String followComplete(double dist) {
        return followComplete(dist, dist, 0, 0, false);
    }

    static String followComplete(double dist, double dx, double dy, double dz, boolean noPath) {
        if (followHold(dist, dx, dy, dz) || noPath) return "hold";
        return "repath";
    }

    static boolean followShouldRetarget(boolean navigating, boolean sameTarget, double dist, double targetMoved) {
        if (followHold(dist)) return false;
        if (!navigating || !sameTarget) return true;
        return targetMoved >= FOLLOW_RETARGET;
    }

    /**
     * Start or refresh a goto toward the follow target. Hold on overlook / 3D band.
     * After no_path, stand and face; retry when the target moves 2+ or after 2s.
     */
    static boolean followShouldRepath(boolean navigating, double dist, double dx, double dy, double dz,
                                      double targetMoved, long sincePathMs, boolean noPath) {
        if (followHold(dist, dx, dy, dz)) return false;
        if (noPath && !navigating)
            return targetMoved >= FOLLOW_RETARGET || sincePathMs >= FOLLOW_RETRY_MS;
        if (!navigating) return true;
        return targetMoved >= FOLLOW_RETARGET;
    }

    private static void tickFollow(NPC npc, Player p, LivingEntity target, Bout b) {
        b.status = "following";
        Location dest = target.getLocation();
        double dx = dest.getX() - p.getX(), dy = dest.getY() - p.getY(), dz = dest.getZ() - p.getZ();
        double dist;
        try { dist = p.getLocation().distance(dest); }
        catch (RuntimeException e) { dist = Double.POSITIVE_INFINITY; }
        AgentDoors.openToward(npc, p.getLocation(), dest);
        if (followHold(dist, dx, dy, dz)) {
            b.noPath = false;
            if (b.reason != null && (b.reason.contains(AgentPathfinder.NO_PATH) || b.reason.startsWith("no_path")))
                b.reason = "";
            if (AgentNavigation.moving(npc)) AgentNavigation.release(npc);
            try { npc.getNavigator().cancelNavigation(); } catch (RuntimeException ignored) {}
            p.setSprinting(false);
            npc.faceLocation(dest);
            AgentFocus.note(npc, "follow", target);
            return;
        }
        boolean sprint = followSprint(dist);
        applyFollow(npc, p, target, sprint);
        AgentFocus.note(npc, "follow", target);
    }

    static boolean followSprint(double dist) {
        return dist > FOLLOW_SPRINT_AT;
    }

    static void applyFollow(NPC npc, Player p, Entity target, boolean sprint) {
        Location dest = target.getLocation();
        Bout b = bouts.get(npc.getUniqueId());
        double moved = 0;
        try {
            if (b != null && b.last != null && dest.getWorld() != null && dest.getWorld().equals(b.last.getWorld()))
                moved = dest.distance(b.last);
        } catch (RuntimeException ignored) {}
        double dx = dest.getX() - p.getX(), dy = dest.getY() - p.getY(), dz = dest.getZ() - p.getZ();
        double dist;
        try { dist = p.getLocation().distance(dest); }
        catch (RuntimeException e) { dist = Double.POSITIVE_INFINITY; }
        boolean navigating = AgentNavigation.moving(npc);
        long since = b == null ? FOLLOW_RETRY_MS : System.currentTimeMillis() - b.lastPathAt;
        boolean noPath = b != null && b.noPath;
        if (!followShouldRepath(navigating, dist, dx, dy, dz, moved, since, noPath)) {
            if (followHold(dist, dx, dy, dz)) {
                p.setSprinting(false);
                try { npc.faceLocation(dest); } catch (RuntimeException ignored) {}
            } else {
                p.setSprinting(navigating && sprint);
            }
            return;
        }
        p.setSprinting(sprint);
        try {
            AgentNavigation.start(npc, dest, false);
            if (b != null) {
                b.noPath = false;
                b.lastPathAt = System.currentTimeMillis();
                b.last = dest.clone();
                b.reason = "";
            }
        } catch (RuntimeException e) {
            String why = e.getMessage() == null || e.getMessage().isBlank() ? AgentPathfinder.NO_PATH : e.getMessage();
            if (b != null) {
                b.noPath = true;
                b.lastPathAt = System.currentTimeMillis();
                b.last = dest.clone();
                b.reason = why;
            }
            try { npc.getNavigator().cancelNavigation(); } catch (RuntimeException ignored) {}
            p.setSprinting(false);
            try { npc.faceLocation(dest); } catch (RuntimeException ignored) {}
        }
    }

    static void applyChase(NPC npc, Player p, Entity target) {
        p.setSprinting(true);
        Navigator nav = npc.getNavigator();
        nav.getDefaultParameters().range(128).speedModifier(SPRINT).updatePathRate(0).distanceMargin(1.5).pathDistanceMargin(AgentNavigation.MARGIN).stuckAction(null);
        boolean same = false;
        try {
            var current = nav.getEntityTarget();
            same = nav.isNavigating() && current != null && current.getTarget() != null && current.getTarget().equals(target);
        } catch (RuntimeException ignored) {}
        if (!same) nav.setTarget(target, false);
        nav.getLocalParameters().speedModifier(SPRINT).updatePathRate(0).distanceMargin(1.5).pathDistanceMargin(AgentNavigation.MARGIN).stuckAction(null);
        p.setSprinting(true);
    }

    private static void retreat(NPC npc, Player p, LivingEntity from, Bout b) {
        b.status = "retreated";
        b.reason = "Health below 6; retreated";
        Vector away = p.getLocation().toVector().subtract(from.getLocation().toVector());
        if (away.lengthSquared() < 1e-4) away = new Vector(1, 0, 0);
        away.setY(0).normalize().multiply(10);
        Location dest = p.getLocation().clone().add(away);
        p.setSprinting(true);
        try { AgentNavigation.start(npc, dest, false); } catch (RuntimeException ignored) {}
    }

    static boolean readyToSwing(Player p) {
        try { return p.getAttackCooldown() >= 0.9f; }
        catch (RuntimeException ignored) { return true; }
    }

    /** Sword, then axe, then pickaxe; higher material rank wins within a kind. */
    static int weaponScore(String name) {
        if (name == null || name.isBlank()) return -1;
        int kind = name.endsWith("_SWORD") ? 300 : name.endsWith("_AXE") ? 200 : name.endsWith("_PICKAXE") ? 100 : -1;
        if (kind < 0) return -1;
        int rank = 0;
        for (int r = 0; r < RANKS.length; r++) if (name.startsWith(RANKS[r])) rank = r;
        return kind + rank;
    }

    static String bestWeapon(List<String> names) {
        String best = null;
        int score = -1;
        for (String name : names) {
            int s = weaponScore(name);
            if (s > score) { score = s; best = name; }
        }
        return best;
    }

    static String equipWeapon(Player p) {
        int best = -1, score = -1;
        for (int i = 0; i < 36; i++) {
            ItemStack it = p.getInventory().getItem(i);
            if (it == null || it.getType().isAir()) continue;
            int s = weaponScore(it.getType().name());
            if (s > score) { score = s; best = i; }
        }
        if (best < 0) return p.getInventory().getItemInMainHand() == null ? "" : p.getInventory().getItemInMainHand().getType().name();
        if (best <= 8) p.getInventory().setHeldItemSlot(best);
        else {
            int held = p.getInventory().getHeldItemSlot();
            ItemStack hand = p.getInventory().getItem(held), weapon = p.getInventory().getItem(best);
            p.getInventory().setItem(held, weapon);
            p.getInventory().setItem(best, hand);
        }
        ItemStack hand = p.getInventory().getItemInMainHand();
        return hand == null || hand.getType().isAir() ? "" : hand.getType().name();
    }

    private AgentCombat() {}
}
