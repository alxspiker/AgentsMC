package dev.agentsmc;

import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

import org.bukkit.Location;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.util.Vector;

import net.citizensnpcs.api.npc.NPC;

/**
 * Real-time System-1 micro-autonomy that keeps an agent body alive, responsive,
 * and visually attentive at server-tick frequency while the LLM (System-2) is
 * thinking or while a long navigation path is running:
 * <ul>
 *   <li>Water buoyancy reflex so an idle/thinking agent in water treads water instead of sinking and drowning.</li>
 *   <li>Proactive upkeep eating (with the 32-tick vanilla cooldown) when hungry or hurt outside exclusive melee swings.</li>
 *   <li>Passive nearby drop sweep within 2.0 blocks every 0.5s without waiting for an LLM {@code pickup} call.</li>
 *   <li>Idle social/entity gaze ({@link AgentFocus}) toward nearby speakers or human players when no active work focus is fresh.</li>
 * </ul>
 */
final class AgentAutonomy {
    static final String KEY = "agentsmc-autonomy";
    static final int AUTO_EAT_FOOD = 14;
    static final double AUTO_EAT_HURT_HEALTH = 16.0;
    static final double PASSIVE_PICKUP_RADIUS = 2.0;
    static final double SOCIAL_GAZE_RADIUS = 12.0;
    static final long FOCUS_IDLE_MS = 3_000;
    static final long PICKUP_INTERVAL_MS = 500;
    static final long GAZE_INTERVAL_MS = 500;
    static final double WATER_FLOAT_VY = 0.04;

    static final class State {
        String mode = "auto";
        boolean loaded;
        int eaten;
        int pickedUp;
        String lastReflex = "";
        long lastReflexAt;
        long lastPickupAt;
        long lastGazeAt;
    }

    private static final Map<UUID, State> states = new HashMap<>();

    static void resetForTest() {
        states.clear();
    }

    static boolean validMode(String mode) {
        if (mode == null || mode.isBlank()) return false;
        return switch (mode.trim().toLowerCase(java.util.Locale.ROOT)) {
            case "auto", "autonomy", "night_home", "none", "passive", "off" -> true;
            default -> false;
        };
    }

    static String normalizeMode(String raw) {
        if (raw == null || raw.isBlank()) return "auto";
        String m = raw.trim().toLowerCase(java.util.Locale.ROOT);
        if ("autonomy".equals(m)) return "auto";
        if ("off".equals(m)) return "passive";
        return validMode(m) ? m : "auto";
    }

    static boolean activeForMode(String mode) {
        String m = normalizeMode(mode);
        return !"passive".equals(m);
    }

    /**
     * fb-20: agents eat instantly, so any lingering item-use (raised hand) other than a deliberate shield
     * is a stuck state that loops the client eating/chewing animation and sound. Clear it.
     */
    static boolean staleItemUse(boolean handRaised, String activeMaterial) {
        return handRaised && !"SHIELD".equals(activeMaterial);
    }

    static void clearStaleItemUse(Player p) {
        try {
            org.bukkit.inventory.ItemStack active = p.getActiveItem();
            String material = active == null ? null : active.getType().name();
            if (staleItemUse(p.isHandRaised(), material)) p.clearActiveItem();
        } catch (RuntimeException | LinkageError ignored) {}
    }

    /** True when the body should automatically eat one food item during idle/walking upkeep. */
    static boolean shouldAutoEat(String mode, boolean combatSwinging, double health, double maxHealth, int food) {
        if (!activeForMode(mode) || combatSwinging) return false;
        if (food <= 0 || health <= 0) return false;
        if (food <= AUTO_EAT_FOOD) return true;
        return health + 1e-4 < Math.min(maxHealth, AUTO_EAT_HURT_HEALTH) && food < AgentHunger.FOOD_MAX;
    }

    /**
     * True when the body is in water and should apply gentle upward buoyancy so it
     * does not sink and drown while waiting on LLM generation.
     */
    static boolean shouldFloatInWater(boolean inWater, boolean insideVehicle, Double targetY, double bodyY) {
        if (!inWater || insideVehicle) return false;
        if (targetY != null && Double.isFinite(targetY) && targetY < bodyY - 0.5) return false;
        return true;
    }

    /** Upward velocity applied while treading water. */
    static double waterBuoyancyVy(double currentVy) {
        return Math.max(currentVy, WATER_FLOAT_VY);
    }

    /** True when no recent work focus is active and social gaze may look at a nearby player. */
    static boolean shouldUpdateSocialGaze(String mode, Map<String, Object> currentFocus, long now) {
        if (!activeForMode(mode) || "none".equals(normalizeMode(mode))) return false;
        if (currentFocus == null) return true;
        String action = String.valueOf(currentFocus.getOrDefault("action", ""));
        Object sinceObj = currentFocus.get("since");
        long since = sinceObj instanceof Number n ? n.longValue() : 0L;
        if ("social".equals(action) || "idle_look".equals(action)) return now - since >= GAZE_INTERVAL_MS;
        return now - since >= FOCUS_IDLE_MS;
    }

    static State state(NPC npc) {
        if (npc == null) return new State();
        State s = states.computeIfAbsent(npc.getUniqueId(), k -> new State());
        if (!s.loaded) {
            s.loaded = true;
            try {
                if (npc.data() != null) {
                    Object saved = npc.data().get(KEY, null);
                    if (saved instanceof String str && !str.isBlank()) {
                        s.mode = normalizeMode(str);
                    } else {
                        Object beh = npc.data().get(AgentBehaviors.BEHAVIOR_KEY, null);
                        if (beh instanceof String b && !b.isBlank()) s.mode = normalizeMode(b);
                    }
                }
            } catch (RuntimeException ignored) {}
        }
        return s;
    }

    static void setMode(NPC npc, String mode) {
        if (npc == null) return;
        State s = state(npc);
        s.mode = normalizeMode(mode);
        try {
            if (npc.data() != null) npc.data().setPersistent(KEY, s.mode);
        } catch (RuntimeException ignored) {}
    }

    static Map<String, Object> snapshot(NPC npc) {
        State s = state(npc);
        String mode = normalizeMode(s.mode);
        boolean active = activeForMode(mode);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("mode", mode);
        out.put("active", active);
        out.put("autoEat", active);
        out.put("autoPickup", active);
        out.put("socialGaze", active && !"none".equals(mode));
        out.put("waterFloat", true);
        out.put("eaten", s.eaten);
        out.put("pickedUp", s.pickedUp);
        if (!s.lastReflex.isEmpty()) {
            out.put("lastReflex", s.lastReflex);
            out.put("lastReflexAt", s.lastReflexAt);
        }
        return out;
    }

    static void tick(NPC npc, Player p) {
        if (npc == null || p == null || !AgentsMC.bodyUp(npc)) return;
        State s = state(npc);
        long now = System.currentTimeMillis();

        // 1. Anti-drown water buoyancy reflex (always active unless diving toward a deeper waypoint).
        try {
            Location navTarget = AgentNavigation.currentTarget(npc);
            Double targetY = navTarget == null ? null : navTarget.getY();
            if (shouldFloatInWater(p.isInWater(), p.isInsideVehicle(), targetY, p.getLocation().getY())) {
                Vector v = p.getVelocity();
                double vy = waterBuoyancyVy(v.getY());
                if (vy > v.getY() + 1e-4) {
                    p.setVelocity(new Vector(v.getX(), vy, v.getZ()));
                    noteReflex(s, "water_float", now);
                }
            }
        } catch (RuntimeException ignored) {}

        clearStaleItemUse(p);
        try { AgentSleep.fixOrientation(npc, p); } catch (RuntimeException ignored) {}

        if (!activeForMode(s.mode)) return;

        // 2. Proactive upkeep auto-eat (uses 32-tick vanilla cooldown in AgentSurvivalActions.tryEat).
        try {
            boolean swinging = AgentCombat.busy(npc) && AgentReach.inAttackReach(p, currentCombatEntity(p, npc));
            double maxHealth = 20.0;
            try {
                var attr = p.getAttribute(org.bukkit.attribute.Attribute.MAX_HEALTH);
                if (attr != null) maxHealth = attr.getValue();
            } catch (RuntimeException ignored) {}
            if (shouldAutoEat(s.mode, swinging, p.getHealth(), maxHealth, p.getFoodLevel())) {
                if (AgentSurvivalActions.tryEat(p)) {
                    s.eaten++;
                    noteReflex(s, "auto_eat", now);
                }
            }
        } catch (RuntimeException ignored) {}

        // 3. Passive nearby drop sweep every 500ms within 2.0 blocks.
        if (now - s.lastPickupAt >= PICKUP_INTERVAL_MS) {
            s.lastPickupAt = now;
            try {
                int picked = AgentsMC.collectNearby(p, PASSIVE_PICKUP_RADIUS);
                if (picked > 0) {
                    s.pickedUp += picked;
                    noteReflex(s, "auto_pickup:" + picked, now);
                }
            } catch (RuntimeException ignored) {}
        }

        // 4. Natural social/entity gaze while waiting on the LLM (when no active work target is fresh).
        if (now - s.lastGazeAt >= GAZE_INTERVAL_MS && !AgentNavigation.active(npc) && !AgentBehaviors.busy(npc)) {
            s.lastGazeAt = now;
            try {
                if (shouldUpdateSocialGaze(s.mode, AgentFocus.view(npc), now)) {
                    Entity lookTarget = findSocialGazeTarget(p);
                    if (lookTarget != null) {
                        AgentFocus.note(npc, "social", lookTarget);
                    }
                }
            } catch (RuntimeException ignored) {}
        }
    }

    private static Entity currentCombatEntity(Player p, NPC npc) {
        try {
            UUID tid = AgentCombat.targetId(npc);
            return tid == null || p.getWorld() == null ? null : p.getWorld().getEntity(tid);
        } catch (RuntimeException e) {
            return null;
        }
    }

    private static Entity findSocialGazeTarget(Player self) {
        Entity best = null;
        double bestDistSq = SOCIAL_GAZE_RADIUS * SOCIAL_GAZE_RADIUS;
        for (Entity e : self.getNearbyEntities(SOCIAL_GAZE_RADIUS, 6, SOCIAL_GAZE_RADIUS)) {
            if (e == null || e == self || !e.isValid() || e.isDead()) continue;
            if (!(e instanceof Player)) continue;
            double dSq = e.getLocation().distanceSquared(self.getLocation());
            if (dSq <= bestDistSq) {
                bestDistSq = dSq;
                best = e;
            }
        }
        return best;
    }

    private static void noteReflex(State s, String reflex, long now) {
        s.lastReflex = reflex;
        s.lastReflexAt = now;
    }

    static void forget(UUID id) {
        if (id != null) states.remove(id);
    }

    private AgentAutonomy() {}
}
