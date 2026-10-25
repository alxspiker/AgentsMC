package dev.agentsmc;

import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

import org.bukkit.Location;
import org.bukkit.block.Block;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.util.Vector;

import net.citizensnpcs.api.npc.NPC;

/**
 * Work-target the viewer camera and the NPC body should face: the block or
 * entity the agent is acting on, or the goto destination while navigating.
 * {@code since} is the last activity time so a viewer can ease back after 5s.
 */
final class AgentFocus {
    private static final class Aim {
        double x, y, z;
        String action;
        long since;
    }

    private static final Map<UUID, Aim> aims = new HashMap<>();
    /** Per-tick blend toward the target facing; ~3–4 ticks to settle. */
    static final float TURN = 0.4f;

    static void note(NPC npc, String action, double x, double y, double z) {
        if (npc == null || action == null || action.isBlank() || !Double.isFinite(x) || !Double.isFinite(y) || !Double.isFinite(z))
            return;
        Aim a = aims.computeIfAbsent(npc.getUniqueId(), k -> new Aim());
        a.x = x;
        a.y = y;
        a.z = z;
        a.action = action;
        a.since = System.currentTimeMillis();
    }

    static void note(NPC npc, String action, Block block) {
        if (block == null) return;
        note(npc, action, block.getX() + 0.5, block.getY() + 0.5, block.getZ() + 0.5);
    }

    static void note(NPC npc, String action, Location at) {
        if (at == null) return;
        note(npc, action, at.getX(), at.getY(), at.getZ());
    }

    static void note(NPC npc, String action, Entity entity) {
        if (entity == null) return;
        Location at = entity.getLocation();
        note(npc, action, at.getX(), at.getY() + entity.getHeight() * 0.5, at.getZ());
    }

    static Map<String, Object> view(double x, double y, double z, String action, long since) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("x", x);
        m.put("y", y);
        m.put("z", z);
        m.put("action", action);
        m.put("since", since);
        return m;
    }

    static Map<String, Object> view(NPC npc) {
        Aim a = current(npc);
        return a == null ? null : view(a.x, a.y, a.z, a.action, a.since);
    }

    /** Minecraft look angles (degrees) from eye to target. Yaw: south=0, west=90. */
    static float[] lookAngles(double eyeX, double eyeY, double eyeZ, double x, double y, double z) {
        double dx = x - eyeX, dy = y - eyeY, dz = z - eyeZ;
        double horiz = Math.hypot(dx, dz);
        if (horiz < 1e-6 && Math.abs(dy) < 1e-6) return new float[]{0, 0};
        float yaw = (float) Math.toDegrees(Math.atan2(-dx, dz));
        float pitch = (float) Math.toDegrees(-Math.atan2(dy, horiz));
        if (pitch > 90) pitch = 90;
        if (pitch < -90) pitch = -90;
        return new float[]{yaw, pitch};
    }

    static float lerpAngle(float from, float to, float t) {
        float d = to - from;
        while (d > 180) d -= 360;
        while (d < -180) d += 360;
        float out = from + d * t;
        while (out > 180) out -= 360;
        while (out < -180) out += 360;
        return out;
    }

    static void tick(NPC npc, Player p) {
        refresh(npc);
        Aim a = current(npc);
        if (a == null) return;
        Location eye = p.getEyeLocation();
        if (eye.getWorld() != null) {
            Vector delta = new Vector(a.x - eye.getX(), a.y - eye.getY(), a.z - eye.getZ());
            if (delta.lengthSquared() < 1e-6) return;
        }
        float[] want = lookAngles(eye.getX(), eye.getY(), eye.getZ(), a.x, a.y, a.z);
        Location now = p.getLocation();
        float yaw = lerpAngle(now.getYaw(), want[0], TURN);
        float pitch = now.getPitch() + (want[1] - now.getPitch()) * TURN;
        if (pitch > 90) pitch = 90;
        if (pitch < -90) pitch = -90;
        p.setRotation(yaw, pitch);
    }

    static void forget(UUID id) { aims.remove(id); }

    private static void refresh(NPC npc) {
        Block log = AgentBehaviors.currentChopLog(npc);
        if (log != null) {
            note(npc, "harvest", log);
            return;
        }
        Location dest = AgentNavigation.currentTarget(npc);
        if (dest != null) note(npc, "goto", dest);
    }

    private static Aim current(NPC npc) {
        return npc == null ? null : aims.get(npc.getUniqueId());
    }

    private AgentFocus() {}
}
