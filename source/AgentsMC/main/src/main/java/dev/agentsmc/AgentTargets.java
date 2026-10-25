package dev.agentsmc;

import java.util.Locale;
import java.util.UUID;

import org.bukkit.Bukkit;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;

import com.google.gson.JsonObject;

import net.citizensnpcs.api.CitizensAPI;
import net.citizensnpcs.api.npc.NPC;

/**
 * Case-insensitive player/agent lookup for follow, say, and whisper.
 * Every miss is a specific, non-empty error.
 */
final class AgentTargets {
    static String strip(String raw) {
        if (raw == null) return "";
        return org.bukkit.ChatColor.stripColor(raw).replaceAll("§.", "").trim();
    }

    static boolean namesEqual(String have, String want) {
        String a = strip(have), b = strip(want);
        return !a.isBlank() && !b.isBlank() && a.equalsIgnoreCase(b);
    }

    static String first(JsonObject o, String... keys) {
        if (o == null) return "";
        for (String key : keys) {
            if (!o.has(key) || o.get(key).isJsonNull() || !o.get(key).isJsonPrimitive()) continue;
            String v = o.get(key).getAsString();
            if (v != null && !v.isBlank()) return v.trim();
        }
        return "";
    }

    static String selector(JsonObject o) {
        return first(o, "uuid", "target", "player", "to", "name");
    }

    static boolean looksLikeUuid(String raw) {
        String s = strip(raw);
        if (s.length() == 36 && s.charAt(8) == '-') {
            try { UUID.fromString(s); return true; } catch (IllegalArgumentException e) { return false; }
        }
        String d = s.replace("-", "");
        return d.length() == 32 && d.matches("[0-9a-fA-F]{32}");
    }

    static UUID parseUuid(String raw) {
        String s = strip(raw);
        if (s.length() == 32 && s.indexOf('-') < 0) s = AgentEvents.hyphenated(s);
        try { return UUID.fromString(s); }
        catch (RuntimeException e) { throw new IllegalArgumentException(invalidUuid(raw)); }
    }

    static String missing(String want) {
        return "No online player or agent named '" + strip(want) + "'";
    }

    static String followNeed() {
        return "follow requires uuid, target, player, or name (a player or agent display name)";
    }

    static String sayNeedRecipient() {
        return "say recipient not found; pass player, to, or name (a player or agent display name)";
    }

    static String invalidUuid(String raw) {
        return "Invalid entity UUID '" + strip(raw) + "'";
    }

    static String requireError(String error) {
        if (error == null || error.isBlank()) return "Action failed (no detail reported)";
        return error;
    }

    /** Online human or spawned agent matching display name or public id. */
    static Entity findNamed(Player self, String want) {
        String name = strip(want);
        if (name.isBlank()) return null;
        try {
            for (Player online : Bukkit.getOnlinePlayers()) {
                if (self != null && online.equals(self)) continue;
                if (!namesEqual(online.getName(), name)) continue;
                if (CitizensAPI.getNPCRegistry().isNPC(online)) {
                    NPC n = CitizensAPI.getNPCRegistry().getNPC(online);
                    if (n != null && n.data().has("agentsmc-id")) return online;
                    continue;
                }
                return online;
            }
        } catch (RuntimeException ignored) {}
        try {
            for (NPC n : CitizensAPI.getNPCRegistry()) {
                if (n == null || !n.data().has("agentsmc-id")) continue;
                if (self != null && n.getEntity() == self) continue;
                String id = AgentIds.publicId(n.data().get("agentsmc-id"));
                if (!namesEqual(n.getName(), name) && !AgentIds.same(id, name) && !namesEqual(id, name)) continue;
                if (n.isSpawned() && n.getEntity() != null && !n.getEntity().isDead()) return n.getEntity();
                throw new IllegalArgumentException("Agent '" + n.getName() + "' is not spawned");
            }
        } catch (IllegalArgumentException e) { throw e; }
        catch (RuntimeException ignored) {}
        return null;
    }

    static NPC findAgent(NPC from, String want) {
        String name = strip(want);
        if (name.isBlank()) return null;
        try {
            for (NPC n : CitizensAPI.getNPCRegistry()) {
                if (n == null || !n.data().has("agentsmc-id") || n == from) continue;
                String id = AgentIds.publicId(n.data().get("agentsmc-id"));
                if (namesEqual(n.getName(), name) || AgentIds.same(id, name) || namesEqual(id, name)) return n;
            }
        } catch (RuntimeException ignored) {}
        return null;
    }

    static Player findHuman(String want) {
        String name = strip(want);
        if (name.isBlank()) return null;
        try {
            for (Player online : Bukkit.getOnlinePlayers()) {
                if (CitizensAPI.getNPCRegistry().isNPC(online)) continue;
                if (namesEqual(online.getName(), name)) return online;
            }
        } catch (RuntimeException ignored) {}
        return null;
    }

    static String kindOf(Entity e) {
        if (e == null) return "";
        try {
            if (CitizensAPI.getNPCRegistry().isNPC(e)) {
                NPC n = CitizensAPI.getNPCRegistry().getNPC(e);
                if (n != null && n.data().has("agentsmc-id")) return "agent";
            }
        } catch (RuntimeException ignored) {}
        return e instanceof Player ? "player" : e.getType().name().toLowerCase(Locale.ROOT);
    }

    private AgentTargets() {}
}
