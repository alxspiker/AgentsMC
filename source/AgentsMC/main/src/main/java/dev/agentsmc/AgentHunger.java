package dev.agentsmc;

import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

import org.bukkit.Difficulty;
import org.bukkit.GameRule;
import org.bukkit.Location;
import org.bukkit.attribute.Attribute;
import org.bukkit.damage.DamageSource;
import org.bukkit.damage.DamageType;
import org.bukkit.entity.Player;
import org.bukkit.event.entity.EntityDamageEvent;

import net.citizensnpcs.api.npc.NPC;

/**
 * Vanilla survival hunger for agent NPC bodies. Citizens player NPCs do not
 * accumulate exhaustion or run {@code FoodData.tick} unless Minecraft AI is on,
 * so walking/chopping never drained food. This applies Mojang values and the
 * saturation → food → regen / starve loop, and persists hunger across
 * non-death respawns and restarts.
 */
final class AgentHunger {
    static final String KEY = "agentsmc-hunger";
    static final String DEATHS_KEY = "agentsmc-deaths";
    static final String LAST_DAMAGE_KEY = "agentsmc-last-damage";

    static final float WALK = 0.01f;
    static final float SPRINT = 0.1f;
    static final float SWIM = 0.01f;
    static final float JUMP = 0.05f;
    static final float SPRINT_JUMP = 0.2f;
    static final float BREAK = 0.005f;
    static final float ATTACK = 0.1f;
    static final float DAMAGE = 0.1f;
    static final float THRESHOLD = 4.0f;
    static final float DEFAULT_SATURATION = 5.0f;
    static final int FOOD_MAX = 20;
    static final int REGEN_FOOD = 18;

    static final class Step {
        final int food;
        final float saturation;
        final float exhaustion;
        final int foodTimer;
        final float heal;
        final float starve;
        Step(int food, float saturation, float exhaustion, int foodTimer, float heal, float starve) {
            this.food = food;
            this.saturation = saturation;
            this.exhaustion = exhaustion;
            this.foodTimer = foodTimer;
            this.heal = heal;
            this.starve = starve;
        }
    }

    static final class Stored {
        final int food;
        final float saturation;
        final float exhaustion;
        final int foodTimer;
        Stored(int food, float saturation, float exhaustion, int foodTimer) {
            this.food = food;
            this.saturation = saturation;
            this.exhaustion = exhaustion;
            this.foodTimer = foodTimer;
        }
    }

    private static final Map<UUID, Location> lastLoc = new HashMap<>();
    private static final Map<UUID, Boolean> lastGround = new HashMap<>();
    private static final Map<UUID, Integer> timers = new HashMap<>();

    static float movement(double meters, boolean sprinting, boolean swimming) {
        if (!(meters > 0) || !Double.isFinite(meters)) return 0;
        if (sprinting) return (float) (meters * SPRINT);
        if (swimming) return (float) (meters * SWIM);
        return (float) (meters * WALK);
    }

    static float jump(boolean sprinting) { return sprinting ? SPRINT_JUMP : JUMP; }

    static boolean mayEat(int food, boolean canAlwaysEat) { return food < FOOD_MAX || canAlwaysEat; }

    /**
     * One vanilla {@code FoodData.tick}: at most one 4.0 exhaustion chunk, then
     * saturation regen (10 ticks), food&gt;=18 regen (80 ticks), or starve (80 ticks).
     * difficulty: 0 peaceful, 1 easy, 2 normal, 3 hard.
     */
    static Step step(int food, float saturation, float exhaustion, int foodTimer,
                     double health, double maxHealth, int difficulty, boolean naturalRegen) {
        food = clampFood(food);
        saturation = clampSat(saturation, food);
        if (!Float.isFinite(exhaustion) || exhaustion < 0) exhaustion = 0;
        if (foodTimer < 0) foodTimer = 0;
        if (exhaustion >= THRESHOLD) {
            exhaustion -= THRESHOLD;
            if (saturation > 0) saturation = Math.max(0, saturation - 1.0f);
            else if (difficulty > 0) food = Math.max(0, food - 1);
        }
        float heal = 0, starve = 0;
        boolean hurt = health + 1e-4 < maxHealth;
        if (naturalRegen && saturation > 0 && hurt && food >= FOOD_MAX) {
            foodTimer++;
            if (foodTimer >= 10) {
                float f = Math.min(saturation, 6.0f);
                heal = f / 6.0f;
                exhaustion += f;
                foodTimer = 0;
            }
        } else if (naturalRegen && food >= REGEN_FOOD && hurt) {
            foodTimer++;
            if (foodTimer >= 80) {
                heal = 1.0f;
                exhaustion += 6.0f;
                foodTimer = 0;
            }
        } else if (food <= 0) {
            foodTimer++;
            if (foodTimer >= 80) {
                if (health > 10 || difficulty >= 3 || (health > 1 && difficulty == 2)) starve = 1.0f;
                foodTimer = 0;
            }
        } else {
            foodTimer = 0;
        }
        return new Step(food, saturation, exhaustion, foodTimer, heal, starve);
    }

    static String encode(int food, float saturation, float exhaustion, int foodTimer) {
        return clampFood(food) + ";" + saturation + ";" + exhaustion + ";" + Math.max(0, foodTimer);
    }

    static String encodeReset() { return encode(FOOD_MAX, DEFAULT_SATURATION, 0, 0); }

    static Stored decode(String raw) {
        if (raw == null || raw.isBlank()) return new Stored(FOOD_MAX, DEFAULT_SATURATION, 0, 0);
        String[] p = raw.split(";", -1);
        if (p.length < 3) return new Stored(FOOD_MAX, DEFAULT_SATURATION, 0, 0);
        try {
            int food = clampFood(Integer.parseInt(p[0]));
            float sat = Float.parseFloat(p[1]);
            float exh = Float.parseFloat(p[2]);
            int timer = p.length > 3 ? Integer.parseInt(p[3]) : 0;
            if (!Float.isFinite(sat)) sat = DEFAULT_SATURATION;
            if (!Float.isFinite(exh) || exh < 0) exh = 0;
            return new Stored(food, clampSat(sat, food), exh, Math.max(0, timer));
        } catch (NumberFormatException e) {
            return new Stored(FOOD_MAX, DEFAULT_SATURATION, 0, 0);
        }
    }

    static Map<String, Object> damageView(String cause, double amount, long time) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("cause", cause == null || cause.isBlank() ? "UNKNOWN" : cause);
        m.put("amount", amount);
        m.put("time", time);
        return m;
    }

    static void exhaust(Player p, float amount) {
        if (p == null || !Float.isFinite(amount) || amount <= 0) return;
        p.setExhaustion(Math.max(0, p.getExhaustion() + amount));
    }

    static void tick(NPC npc, Player p) {
        UUID id = npc.getUniqueId();
        Location now = p.getLocation();
        Location prev = lastLoc.put(id, now.clone());
        boolean ground = p.isOnGround();
        Boolean wasGround = lastGround.put(id, ground);
        if (prev != null && prev.getWorld() != null && prev.getWorld().equals(now.getWorld())) {
            double dx = now.getX() - prev.getX(), dz = now.getZ() - prev.getZ();
            double dist = Math.hypot(dx, dz);
            if (dist > 1e-4 && dist < 8)
                exhaust(p, movement(dist, p.isSprinting(), p.isSwimming() || p.isInWater()));
            if (Boolean.TRUE.equals(wasGround) && !ground && now.getY() > prev.getY() + 0.2)
                exhaust(p, jump(p.isSprinting()));
        }
        int difficulty = difficulty(p.getWorld().getDifficulty());
        Boolean regen = p.getWorld().getGameRuleValue(GameRule.NATURAL_REGENERATION);
        double max = maxHealth(p);
        int timer = timers.getOrDefault(id, 0);
        Step s = step(p.getFoodLevel(), p.getSaturation(), p.getExhaustion(), timer,
                p.getHealth(), max, difficulty, regen == null || regen);
        timers.put(id, s.foodTimer);
        p.setFoodLevel(s.food);
        p.setSaturation(s.saturation);
        p.setExhaustion(s.exhaustion);
        if (s.heal > 0) p.setHealth(Math.min(max, p.getHealth() + s.heal));
        if (s.starve > 0 && p.getHealth() > 0)
            p.damage(s.starve, DamageSource.builder(DamageType.STARVE).build());
    }

    static boolean save(NPC npc) {
        if (!(npc.getEntity() instanceof Player p) || p.isDead()) return false;
        String encoded = encode(p.getFoodLevel(), p.getSaturation(), p.getExhaustion(),
                timers.getOrDefault(npc.getUniqueId(), 0));
        if (encoded.equals(AgentErrors.text(npc.data().get(KEY, "")))) return false;
        npc.data().setPersistent(KEY, encoded);
        return true;
    }

    static void restore(NPC npc) {
        if (!(npc.getEntity() instanceof Player p)) return;
        Stored s = decode(npc.data().has(KEY) ? AgentErrors.text(npc.data().get(KEY)) : "");
        p.setFoodLevel(s.food);
        p.setSaturation(s.saturation);
        p.setExhaustion(s.exhaustion);
        timers.put(npc.getUniqueId(), s.foodTimer);
    }

    static void applyFood(Player p, int food) {
        int next = clampFood(food);
        p.setFoodLevel(next);
        p.setSaturation(clampSat(p.getSaturation(), next));
    }

    static void resetAfterDeath(NPC npc) {
        npc.data().setPersistent(KEY, encodeReset());
        timers.remove(npc.getUniqueId());
        lastLoc.remove(npc.getUniqueId());
        lastGround.remove(npc.getUniqueId());
    }

    static void applyReset(Player p) {
        p.setFoodLevel(FOOD_MAX);
        p.setSaturation(DEFAULT_SATURATION);
        p.setExhaustion(0);
    }

    static void onDeath(NPC npc, EntityDamageEvent last) {
        incrementDeaths(npc);
        if (last != null) recordDamage(npc, last.getCause().name(), last.getFinalDamage());
        resetAfterDeath(npc);
    }

    static void recordDamage(NPC npc, String cause, double amount) {
        npc.data().setPersistent(LAST_DAMAGE_KEY, cause + ";" + amount + ";" + System.currentTimeMillis());
    }

    static void incrementDeaths(NPC npc) {
        npc.data().setPersistent(DEATHS_KEY, deaths(npc) + 1);
    }

    static int deaths(NPC npc) {
        Object v = npc.data().get(DEATHS_KEY, 0);
        return v instanceof Number n ? n.intValue() : 0;
    }

    static Map<String, Object> lastDamageView(NPC npc) {
        Object v = npc.data().get(LAST_DAMAGE_KEY, null);
        if (!(v instanceof String raw) || raw.isBlank()) return null;
        String[] p = raw.split(";", 3);
        if (p.length < 3) return null;
        try {
            return damageView(p[0], Double.parseDouble(p[1]), Long.parseLong(p[2]));
        } catch (NumberFormatException e) {
            return null;
        }
    }

    /** Vanilla: only damage normally reduced by armor costs 0.1 exhaustion. */
    static boolean armorExhaustion(EntityDamageEvent.DamageCause cause) {
        if (cause == null) return false;
        return switch (cause) {
            case ENTITY_ATTACK, ENTITY_SWEEP_ATTACK, PROJECTILE, CONTACT, THORNS,
                    BLOCK_EXPLOSION, ENTITY_EXPLOSION, FALLING_BLOCK -> true;
            default -> false;
        };
    }

    static void forget(UUID id) {
        lastLoc.remove(id);
        lastGround.remove(id);
        timers.remove(id);
    }

    private static int difficulty(Difficulty d) {
        if (d == null) return 2;
        return switch (d) {
            case PEACEFUL -> 0;
            case EASY -> 1;
            case NORMAL -> 2;
            case HARD -> 3;
        };
    }

    private static double maxHealth(Player p) {
        var attr = p.getAttribute(Attribute.MAX_HEALTH);
        return attr == null ? 20 : attr.getValue();
    }

    private static int clampFood(int food) { return Math.min(FOOD_MAX, Math.max(0, food)); }
    private static float clampSat(float sat, int food) { return Math.min(Math.max(0, sat), food); }

    private AgentHunger() {}
}
