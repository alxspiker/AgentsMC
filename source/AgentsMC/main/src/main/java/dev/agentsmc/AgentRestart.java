package dev.agentsmc;

import java.util.LinkedHashSet;
import java.util.Set;

import org.bukkit.entity.Player;

import com.google.gson.JsonObject;

import net.citizensnpcs.api.npc.NPC;

/**
 * 0.6.20: plugin enable/reload leaves every agent idle. In-flight follow,
 * protect, fight, goto, and harvest are not restored. Bridge queue entries
 * seen on the first poll after boot are cancelled with {@link #REASON}.
 */
final class AgentRestart {
    static final String REASON = "server_restart";

    static JsonObject cancelled(String action) {
        JsonObject r = new JsonObject();
        r.addProperty("ok", false);
        if (action != null && !action.isBlank()) r.addProperty("action", action);
        r.addProperty("cancelled", true);
        r.addProperty("status", "cancelled");
        r.addProperty("reason", REASON);
        r.addProperty("error", REASON);
        return r;
    }

    /** Remember the Site queue as it first appears after boot. Later IDs are fresh. */
    static Set<String> captureQueue(Set<String> existing, Set<String> queuedNow) {
        if (existing != null) return existing;
        return queuedNow == null ? Set.of() : Set.copyOf(queuedNow);
    }

    static boolean stale(Set<String> firstPollIds, String id) {
        return firstPollIds != null && id != null && firstPollIds.contains(id);
    }

    /** Replay a finished receipt; cancel a stale incomplete; execute only fresh IDs. */
    static String disposition(boolean hasCompletedResult, boolean stale) {
        if (hasCompletedResult) return "replay";
        if (stale) return "cancel";
        return "execute";
    }

    static void idle(NPC npc) {
        if (npc == null) return;
        AgentProtect.clearPersisted(npc);
        AgentProtect.stop(npc);
        AgentBehaviors.cancel(npc);
        AgentCombat.stop(npc);
        AgentSelfDefense.stop(npc);
        AgentNavigation.stop(npc);
        try { npc.getNavigator().cancelNavigation(); } catch (RuntimeException ignored) {}
        try {
            if (npc.getEntity() instanceof Player p) {
                p.setSprinting(false);
                p.setSneaking(false);
            }
        } catch (RuntimeException ignored) {}
    }

    static Set<String> copyIds(Iterable<String> ids) {
        LinkedHashSet<String> out = new LinkedHashSet<>();
        if (ids != null) for (String id : ids) if (id != null && !id.isBlank()) out.add(id);
        return Set.copyOf(out);
    }

    private AgentRestart() {}
}
