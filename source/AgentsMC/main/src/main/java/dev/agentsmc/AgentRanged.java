package dev.agentsmc;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

import org.bukkit.FluidCollisionMode;
import org.bukkit.Location;
import org.bukkit.enchantments.Enchantment;
import org.bukkit.entity.AbstractArrow;
import org.bukkit.entity.Arrow;
import org.bukkit.entity.Entity;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.entity.SpectralArrow;
import org.bukkit.inventory.ItemStack;
import org.bukkit.util.RayTraceResult;
import org.bukkit.util.Vector;

import net.citizensnpcs.api.npc.NPC;

/**
 * Bow/crossbow shooting: 1s-class draw, gravity/lead aim, real projectile
 * with the NPC as shooter, vanilla arrow consume (Infinity on bows only).
 */
final class AgentRanged {
    static final double MELEE = 4;
    static final double RANGE_MIN = 6;
    static final double RANGE_MAX = 24;
    static final long BOW_DRAW_MS = 1_000;
    static final long CROSSBOW_DRAW_MS = 1_250;
    static final double BOW_SPEED = 3.0;
    static final double CROSSBOW_SPEED = 3.15;
    static final double GRAVITY = 0.05;

    static final class RecipeSpec {
        final String output;
        final int yield;
        final List<String> inputs;
        RecipeSpec(String output, int yield, List<String> inputs) {
            this.output = output;
            this.yield = yield;
            this.inputs = List.copyOf(inputs);
        }
    }

    static final RecipeSpec BOW = new RecipeSpec("BOW", 1,
            List.of("STICK", "STICK", "STICK", "STRING", "STRING", "STRING"));
    static final RecipeSpec ARROW = new RecipeSpec("ARROW", 4,
            List.of("FLINT", "STICK", "FEATHER"));
    static final RecipeSpec CROSSBOW = new RecipeSpec("CROSSBOW", 1,
            List.of("STICK", "STICK", "STICK", "IRON_INGOT", "STRING", "STRING", "TRIPWIRE_HOOK"));

    static final class Shot {
        boolean ok;
        boolean consumed;
        String weapon = "", ammo = "";
        double speed;
        double[] velocity = {0, 0, 0};
        String error = "";
    }

    static final class Stats {
        String weapon = "", lastTarget = "";
        int arrows, shots;
        boolean infinity, drawing, ready;
        double charge;
        long lastShotAt, drawAt;
    }

    private static final Map<UUID, Stats> stats = new HashMap<>();

    static String bare(String raw) {
        if (raw == null) return "";
        String n = raw.trim().toLowerCase(Locale.ROOT);
        if (n.startsWith("minecraft:")) n = n.substring("minecraft:".length());
        return n.replace('-', '_');
    }

    static boolean isBow(String raw) { return bare(raw).equals("bow"); }
    static boolean isCrossbow(String raw) { return bare(raw).equals("crossbow"); }
    static boolean isRangedWeapon(String raw) { return isBow(raw) || isCrossbow(raw); }
    static boolean isArrow(String raw) {
        String n = bare(raw);
        return n.equals("arrow") || n.equals("spectral_arrow") || n.equals("tipped_arrow")
                || n.endsWith("_arrow");
    }

    static boolean hasInfinity(Iterable<String> enchantKeys) {
        if (enchantKeys == null) return false;
        for (String key : enchantKeys) {
            String n = bare(key);
            if (n.equals("infinity") || n.equals("arrow_infinite")) return true;
        }
        return false;
    }

    static boolean consumesArrow(String weapon, boolean infinity) {
        return !isBow(weapon) || !infinity;
    }

    static boolean inBand(double dist) {
        return Double.isFinite(dist) && dist >= RANGE_MIN - 1e-9 && dist <= RANGE_MAX + 1e-9;
    }

    static boolean preferMelee(double nearestBox) {
        return Double.isFinite(nearestBox) && nearestBox <= MELEE + 1e-9;
    }

    static long drawMs(String weapon) {
        return isCrossbow(weapon) ? CROSSBOW_DRAW_MS : BOW_DRAW_MS;
    }

    static double charge(long drawnMs, String weapon) {
        if (drawnMs <= 0) return 0;
        return Math.min(1, drawnMs / (double) drawMs(weapon));
    }

    static double speed(String weapon, double charge) {
        double cap = isCrossbow(weapon) ? CROSSBOW_SPEED : BOW_SPEED;
        double c = Math.max(0, Math.min(1, charge));
        if (isCrossbow(weapon)) return c >= 1 ? cap : 0;
        return cap * c * c;
    }

    /** Extra aim height so a full-speed arrow lands at the target after {@code ticks} of gravity. */
    static double drop(double ticks) {
        if (ticks <= 0) return 0;
        return 0.5 * GRAVITY * ticks * ticks;
    }

    /**
     * Velocity {@code [vx,vy,vz]} from the eye toward a moving target. Lead uses
     * {@code targetVel} in blocks/tick; Y is raised by {@link #drop} so gravity lands it.
     */
    static double[] aim(double eyeX, double eyeY, double eyeZ,
                        double tx, double ty, double tz,
                        double tvx, double tvy, double tvz,
                        double muzzle) {
        if (muzzle < 1e-6) return new double[]{0, 0, 0};
        double dx = tx - eyeX, dy = ty - eyeY, dz = tz - eyeZ;
        double t = Math.hypot(Math.hypot(dx, dz), dy) / muzzle;
        for (int i = 0; i < 6; i++) {
            double lx = dx + tvx * t, ly = dy + tvy * t + drop(t), lz = dz + tvz * t;
            double dist = Math.hypot(Math.hypot(lx, lz), ly);
            t = dist / muzzle;
        }
        double lx = dx + tvx * t, ly = dy + tvy * t + drop(t), lz = dz + tvz * t;
        double dist = Math.hypot(Math.hypot(lx, lz), ly);
        if (dist < 1e-9) return new double[]{0, muzzle, 0};
        return new double[]{lx / dist * muzzle, ly / dist * muzzle, lz / dist * muzzle};
    }

    static boolean canUse(boolean hasWeapon, int arrows, boolean infinity, double dist, boolean los) {
        if (!hasWeapon || !los || !inBand(dist)) return false;
        if (infinity) return arrows >= 1;
        return arrows >= 1;
    }

    /** Segment vs unit block AABB. Used for line-of-sight tests without a live world. */
    static boolean blockedBy(double x0, double y0, double z0, double x1, double y1, double z1,
                             int bx, int by, int bz) {
        return segmentHitsAabb(x0, y0, z0, x1, y1, z1, bx, by, bz, bx + 1, by + 1, bz + 1);
    }

    static boolean segmentHitsAabb(double x0, double y0, double z0, double x1, double y1, double z1,
                                   double minX, double minY, double minZ, double maxX, double maxY, double maxZ) {
        double dx = x1 - x0, dy = y1 - y0, dz = z1 - z0;
        double tmin = 0, tmax = 1;
        if (!slab(x0, dx, minX, maxX, tmin, tmax)) return false;
        tmin = slabT0(x0, dx, minX, maxX, tmin);
        tmax = slabT1(x0, dx, minX, maxX, tmax);
        if (tmin > tmax) return false;
        if (!slab(y0, dy, minY, maxY, tmin, tmax)) return false;
        tmin = slabT0(y0, dy, minY, maxY, tmin);
        tmax = slabT1(y0, dy, minY, maxY, tmax);
        if (tmin > tmax) return false;
        if (!slab(z0, dz, minZ, maxZ, tmin, tmax)) return false;
        tmin = slabT0(z0, dz, minZ, maxZ, tmin);
        tmax = slabT1(z0, dz, minZ, maxZ, tmax);
        return tmin <= tmax && tmax >= 0 && tmin <= 1;
    }

    private static boolean slab(double p, double d, double min, double max, double tmin, double tmax) {
        if (Math.abs(d) < 1e-12) return p >= min && p <= max;
        double t0 = slabT0(p, d, min, max, tmin);
        double t1 = slabT1(p, d, min, max, tmax);
        return t0 <= t1;
    }
    private static double slabT0(double p, double d, double min, double max, double tmin) {
        if (Math.abs(d) < 1e-12) return tmin;
        double a = (min - p) / d, b = (max - p) / d;
        return Math.max(tmin, Math.min(a, b));
    }
    private static double slabT1(double p, double d, double min, double max, double tmax) {
        if (Math.abs(d) < 1e-12) return tmax;
        double a = (min - p) / d, b = (max - p) / d;
        return Math.min(tmax, Math.max(a, b));
    }

    static RecipeSpec craftSpec(String raw) {
        String n = bare(raw);
        if (n.equals("bow")) return BOW;
        if (n.equals("arrow")) return ARROW;
        if (n.equals("crossbow")) return CROSSBOW;
        return null;
    }

    static boolean canCraft(String output, Map<String, Integer> inv, boolean table) {
        RecipeSpec spec = craftSpec(output);
        if (spec == null || !table) return false;
        return leftover(inv, spec.inputs) != null;
    }

    static Map<String, Integer> leftover(Map<String, Integer> inv, List<String> inputs) {
        if (inv == null) return null;
        Map<String, Integer> have = new HashMap<>();
        for (Map.Entry<String, Integer> e : inv.entrySet())
            have.put(e.getKey().toUpperCase(Locale.ROOT), e.getValue() == null ? 0 : e.getValue());
        for (String in : inputs) {
            String k = in.toUpperCase(Locale.ROOT);
            int n = have.getOrDefault(k, 0) - 1;
            if (n < 0) return null;
            have.put(k, n);
        }
        return have;
    }

    static boolean hasWeapon(Player p) { return weaponSlot(p) >= 0; }

    static int arrowCount(Player p) {
        if (p == null) return 0;
        int n = 0;
        try {
            for (ItemStack it : p.getInventory().getStorageContents()) {
                if (it != null && isArrow(it.getType().name())) n += it.getAmount();
            }
        } catch (RuntimeException ignored) {}
        return n;
    }

    static boolean hasAmmo(Player p) { return arrowCount(p) >= 1; }

    static boolean infinityOn(ItemStack bow) {
        if (bow == null || !isBow(bow.getType().name())) return false;
        try {
            for (Enchantment e : bow.getEnchantments().keySet()) {
                if (hasInfinity(List.of(e.getKey().getKey()))) return true;
            }
        } catch (RuntimeException ignored) {}
        return false;
    }

    static String heldWeapon(Player p) {
        try {
            ItemStack hand = p.getInventory().getItemInMainHand();
            if (hand != null && isRangedWeapon(hand.getType().name())) return hand.getType().name();
        } catch (RuntimeException ignored) {}
        return "";
    }

    static int weaponSlot(Player p) {
        if (p == null) return -1;
        try {
            for (int i = 0; i < 36; i++) {
                ItemStack it = p.getInventory().getItem(i);
                if (it != null && isRangedWeapon(it.getType().name())) return i;
            }
        } catch (RuntimeException ignored) {}
        return -1;
    }

    static String equip(Player p) {
        int slot = weaponSlot(p);
        if (slot < 0) return "";
        try {
            if (slot <= 8) p.getInventory().setHeldItemSlot(slot);
            else {
                int held = p.getInventory().getHeldItemSlot();
                ItemStack hand = p.getInventory().getItem(held), weapon = p.getInventory().getItem(slot);
                p.getInventory().setItem(held, weapon);
                p.getInventory().setItem(slot, hand);
            }
            ItemStack hand = p.getInventory().getItemInMainHand();
            return hand == null ? "" : hand.getType().name();
        } catch (RuntimeException ignored) { return ""; }
    }

    static boolean lineOfSight(Player p, Location to) {
        if (p == null || to == null || to.getWorld() == null || p.getWorld() == null) return false;
        if (!p.getWorld().equals(to.getWorld())) return false;
        Location eye = p.getEyeLocation();
        Vector d = to.toVector().subtract(eye.toVector());
        double len = d.length();
        if (len < 0.2) return true;
        try {
            RayTraceResult hit = p.getWorld().rayTraceBlocks(eye, d, len - 0.15, FluidCollisionMode.NEVER, true);
            return hit == null || hit.getHitBlock() == null;
        } catch (RuntimeException e) {
            return true;
        }
    }

    static Shot fire(Player p, Location dest, Vector targetVel) {
        Shot shot = new Shot();
        if (p == null || dest == null) { shot.error = "No shooter or destination"; return shot; }
        String weapon = equip(p);
        if (weapon.isEmpty()) { shot.error = "No bow or crossbow in inventory"; return shot; }
        ItemStack hand = p.getInventory().getItemInMainHand();
        boolean infinity = infinityOn(hand);
        if (!hasAmmo(p)) { shot.error = "No arrows in inventory"; return shot; }
        Location eye = p.getEyeLocation();
        double dist = eye.distance(dest);
        if (dist > RANGE_MAX + 1e-6) { shot.error = "Target is out of bow range (" + Math.round(dist * 10) / 10.0 + " > " + (int) RANGE_MAX + ")"; return shot; }
        if (!lineOfSight(p, dest)) { shot.error = "No line of sight to the target"; return shot; }
        Vector tv = targetVel == null ? new Vector() : targetVel;
        double muzzle = speed(weapon, 1);
        shot.velocity = aim(eye.getX(), eye.getY(), eye.getZ(), dest.getX(), dest.getY(), dest.getZ(),
                tv.getX(), tv.getY(), tv.getZ(), muzzle);
        shot.speed = muzzle;
        shot.weapon = weapon;
        shot.ammo = peekAmmo(p);
        boolean consume = consumesArrow(weapon, infinity);
        try {
            Class<? extends AbstractArrow> type = "SPECTRAL_ARROW".equals(shot.ammo) ? SpectralArrow.class : Arrow.class;
            AbstractArrow arrow = p.launchProjectile(type, new Vector(shot.velocity[0], shot.velocity[1], shot.velocity[2]));
            arrow.setShooter(p);
            arrow.setPickupStatus(AbstractArrow.PickupStatus.ALLOWED);
        } catch (RuntimeException e) {
            shot.error = "Could not spawn arrow: " + (e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage());
            return shot;
        }
        if (consume && !takeArrow(p)) { shot.error = "Arrow was not consumed"; return shot; }
        shot.consumed = consume;
        shot.ok = true;
        return shot;
    }

    static String peekAmmo(Player p) {
        int slot = ammoSlot(p, true);
        if (slot < 0) slot = ammoSlot(p, false);
        if (slot < 0) return "";
        try { return p.getInventory().getItem(slot).getType().name(); }
        catch (RuntimeException e) { return ""; }
    }

    static boolean takeArrow(Player p) {
        int slot = ammoSlot(p, true);
        if (slot < 0) slot = ammoSlot(p, false);
        if (slot < 0) return false;
        try {
            ItemStack s = p.getInventory().getItem(slot);
            if (s == null || !isArrow(s.getType().name())) return false;
            s.setAmount(s.getAmount() - 1);
            p.getInventory().setItem(slot, s.getAmount() > 0 ? s : null);
            return true;
        } catch (RuntimeException e) { return false; }
    }

    private static int ammoSlot(Player p, boolean regularOnly) {
        try {
            for (int i = 0; i < 36; i++) {
                ItemStack it = p.getInventory().getItem(i);
                if (it == null || it.getAmount() < 1) continue;
                String n = it.getType().name();
                if (regularOnly) { if (bare(n).equals("arrow")) return i; }
                else if (isArrow(n)) return i;
            }
        } catch (RuntimeException ignored) {}
        return -1;
    }

    static Stats of(UUID id) { return stats.computeIfAbsent(id, k -> new Stats()); }

    static void noteShot(UUID id, Shot shot, String target) {
        Stats s = of(id);
        if (shot != null && shot.ok) {
            s.shots++;
            s.lastShotAt = System.currentTimeMillis();
            s.drawing = false;
            s.drawAt = 0;
            s.charge = 0;
            if (shot.weapon != null) s.weapon = shot.weapon;
        }
        if (target != null) s.lastTarget = target;
    }

    static void noteDraw(UUID id, String weapon, long startedAt, long now) {
        Stats s = of(id);
        s.drawing = true;
        s.drawAt = startedAt;
        s.weapon = weapon == null ? "" : weapon;
        s.charge = charge(now - startedAt, weapon);
    }

    static void clearDraw(UUID id) {
        Stats s = stats.get(id);
        if (s == null) return;
        s.drawing = false;
        s.drawAt = 0;
        s.charge = 0;
    }

    static Map<String, Object> snapshot(NPC npc, Player p) {
        Map<String, Object> r = new LinkedHashMap<>();
        Stats s = npc == null ? new Stats() : of(npc.getUniqueId());
        if (p != null) {
            s.weapon = heldWeapon(p);
            if (s.weapon.isEmpty()) {
                int slot = weaponSlot(p);
                if (slot >= 0) {
                    try { s.weapon = p.getInventory().getItem(slot).getType().name(); }
                    catch (RuntimeException ignored) {}
                }
            }
            s.arrows = arrowCount(p);
            try {
                ItemStack hand = p.getInventory().getItemInMainHand();
                if (hand == null || !isBow(hand.getType().name())) {
                    int slot = weaponSlot(p);
                    hand = slot >= 0 ? p.getInventory().getItem(slot) : hand;
                }
                s.infinity = infinityOn(hand);
            } catch (RuntimeException ignored) {}
            s.ready = canUse(isRangedWeapon(s.weapon), s.arrows, s.infinity, RANGE_MIN, true) || (isRangedWeapon(s.weapon) && s.arrows >= 1);
        }
        r.put("ready", s.ready && isRangedWeapon(s.weapon) && s.arrows >= 1);
        r.put("weapon", s.weapon);
        r.put("arrows", s.arrows);
        r.put("infinity", s.infinity);
        r.put("drawing", s.drawing);
        r.put("charge", Math.round(s.charge * 100) / 100.0);
        r.put("shots", s.shots);
        r.put("lastTarget", s.lastTarget);
        if (s.lastShotAt > 0) r.put("lastShotAt", s.lastShotAt);
        return r;
    }

    static void forget(UUID id) { stats.remove(id); }

    static boolean useRanged(Player p, LivingEntity target) {
        if (p == null || target == null) return false;
        if (!hasWeapon(p) || !hasAmmo(p)) return false;
        Location dest = aimPoint(target);
        double dist = p.getEyeLocation().distance(dest);
        if (preferMelee(AgentReach.nearest(p.getEyeLocation(), target))) return false;
        return canUse(true, arrowCount(p), infinityOn(heldBow(p)), dist, lineOfSight(p, dest));
    }

    static Location aimPoint(LivingEntity target) {
        try { return target.getEyeLocation(); }
        catch (RuntimeException e) { return target.getLocation().add(0, 1.0, 0); }
    }

    private static ItemStack heldBow(Player p) {
        try {
            ItemStack hand = p.getInventory().getItemInMainHand();
            if (hand != null && isRangedWeapon(hand.getType().name())) return hand;
            int slot = weaponSlot(p);
            return slot < 0 ? null : p.getInventory().getItem(slot);
        } catch (RuntimeException e) { return null; }
    }

    static Vector velocityOf(Entity e) {
        try { return e.getVelocity().clone(); }
        catch (RuntimeException ignored) { return new Vector(); }
    }

    private AgentRanged() {}
}
