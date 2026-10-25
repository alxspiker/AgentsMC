package dev.agentsmc;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.logging.Logger;

import org.bukkit.BanList;
import org.bukkit.Bukkit;
import org.bukkit.Difficulty;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.OfflinePlayer;
import org.bukkit.World;
import org.bukkit.attribute.Attribute;
import org.bukkit.block.Block;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Entity;
import org.bukkit.entity.EntityType;
import org.bukkit.entity.Enemy;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.event.player.PlayerTeleportEvent;
import org.bukkit.inventory.ItemStack;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import net.citizensnpcs.api.CitizensAPI;
import net.citizensnpcs.api.npc.NPC;

/**
 * 0.7.2 op actions: console-granted agents may run a fixed, allow-listed set of admin
 * subcommands. There is no stop command and no raw console passthrough.
 */
final class AgentOp {
    static final String AGENT_ID = "agentsmc-id";
    static final int MAX_FILL = 32768;
    static final int MAX_KILLMOBS_RADIUS = 64;
    static final int DEFAULT_KILLMOBS_RADIUS = 32;
    static final int MAX_GIVE = 64 * 36;
    static final int RESTART_DELAY_TICKS = 60;
    static final List<String> SUBCOMMANDS = List.of("give", "tp", "heal", "feed", "gamemode", "time", "weather", "difficulty",
            "setspawn", "clearinv", "kill", "summon", "killmobs", "fill", "op", "deop", "ban", "pardon", "kick", "restart");

    /* ---------- persistent grant list (plugins/AgentsMC/ops.yml) ---------- */
    private static final Set<String> granted = new TreeSet<>();
    private static File file;

    static synchronized void attach(File f) {
        file = f;
        granted.clear();
        if (f != null && f.isFile()) {
            try {
                for (String line : java.nio.file.Files.readAllLines(f.toPath())) {
                    String t = line.trim();
                    if (!t.startsWith("- ")) continue;
                    String id = t.substring(2).trim().replace("'", "").replace("\"", "").toLowerCase(Locale.ROOT);
                    if (id.matches("[a-z0-9][a-z0-9_-]{0,31}")) granted.add(id);
                }
            } catch (IOException e) { throw new IllegalStateException("Could not read " + f.getName() + ": " + e.getMessage()); }
        }
    }
    static synchronized void resetForTest() { granted.clear(); file = null; }
    static synchronized boolean isOp(String agentId) { return agentId != null && granted.contains(agentId.toLowerCase(Locale.ROOT)); }
    static synchronized List<String> ops() { return new ArrayList<>(granted); }
    static synchronized boolean grant(String agentId) {
        if (agentId == null || !agentId.toLowerCase(Locale.ROOT).matches("[a-z0-9][a-z0-9_-]{0,31}")) throw new IllegalArgumentException("Invalid agent id: " + agentId); boolean c = granted.add(agentId.toLowerCase(Locale.ROOT)); save(); return c; }
    static synchronized boolean revoke(String agentId) { boolean c = granted.remove(agentId.toLowerCase(Locale.ROOT)); save(); return c; }
    private static void save() {
        if (file == null) return;
        StringBuilder y = new StringBuilder("# AgentsMC op agents. Manage with /agentsmc op|deop|ops from the console.\nop-agents:\n");
        for (String id : granted) y.append("  - ").append(id).append('\n');
        try { file.getParentFile().mkdirs(); java.nio.file.Files.writeString(file.toPath(), y.toString()); }
        catch (IOException e) { throw new IllegalStateException("Could not save " + file.getName() + ": " + e.getMessage()); }
    }

    /* ---------- pure parsing helpers (unit tested) ---------- */
    static List<String> args(JsonObject input) {
        if (!input.has("args")) return List.of();
        JsonElement a = input.get("args");
        List<String> out = new ArrayList<>();
        if (a.isJsonArray()) { for (JsonElement e : a.getAsJsonArray()) out.add(e.getAsString()); }
        else if (a.isJsonPrimitive()) { String s = a.getAsString().trim(); if (!s.isEmpty()) out.addAll(Arrays.asList(s.split("\\s+"))); }
        else throw new IllegalArgumentException("args must be a string (space separated) or an array of strings");
        return out;
    }
    static String subcommand(JsonObject input) {
        if (!input.has("command") || !input.get("command").isJsonPrimitive()) throw new IllegalArgumentException("op requires command (one of " + String.join(", ", SUBCOMMANDS) + ")");
        String c = input.get("command").getAsString().trim().toLowerCase(Locale.ROOT);
        if (c.startsWith("/")) c = c.substring(1);
        if (c.equals("stop")) throw new IllegalArgumentException("stop is not available to agents");
        if (!SUBCOMMANDS.contains(c)) throw new IllegalArgumentException("Unknown op command: " + c + " (allowed: " + String.join(", ", SUBCOMMANDS) + ")");
        return c;
    }
    static long timeTicks(String v) {
        switch (v.toLowerCase(Locale.ROOT)) {
            case "day": return 1000; case "noon": return 6000; case "night": return 13000; case "midnight": return 18000;
            default:
                long t;
                try { t = Long.parseLong(v); } catch (NumberFormatException e) { throw new IllegalArgumentException("time must be day, night, noon, midnight or ticks 0..23999"); }
                if (t < 0 || t > 23999) throw new IllegalArgumentException("time ticks must be 0..23999");
                return t;
        }
    }
    static long volume(int x1, int y1, int z1, int x2, int y2, int z2) {
        return (long) (Math.abs(x2 - x1) + 1) * (Math.abs(y2 - y1) + 1) * (Math.abs(z2 - z1) + 1);
    }
    static int killRadius(List<String> a) {
        if (a.isEmpty()) return DEFAULT_KILLMOBS_RADIUS;
        int r = integer(a.get(0), "radius");
        if (r < 1) throw new IllegalArgumentException("radius must be at least 1");
        return Math.min(r, MAX_KILLMOBS_RADIUS);
    }
    static int integer(String s, String what) {
        try { return Integer.parseInt(s); } catch (NumberFormatException e) { throw new IllegalArgumentException(what + " must be an integer: " + s); }
    }
    static double number(String s, String what) {
        try { double d = Double.parseDouble(s); if (!Double.isFinite(d)) throw new NumberFormatException(); return d; }
        catch (NumberFormatException e) { throw new IllegalArgumentException(what + " must be a number: " + s); }
    }
    static String playerName(String s) {
        if (s == null || !s.matches("[A-Za-z0-9_]{1,16}")) throw new IllegalArgumentException("Invalid player name: " + s);
        return s;
    }
    private static void need(List<String> a, int n, String usage) { if (a.size() < n) throw new IllegalArgumentException("usage: " + usage); }
    private static String rest(List<String> a, int from) { return a.size() > from ? String.join(" ", a.subList(from, a.size())) : null; }

    /* ---------- execution (server thread) ---------- */
    static Map<String, Object> execute(AgentsMC plugin, NPC self, JsonObject input) {
        String agentId = AgentErrors.text(self.data().get(AGENT_ID, ""));
        String cmd = input.has("command") && input.get("command").isJsonPrimitive() ? input.get("command").getAsString() : "?";
        List<String> a = List.of();
        Map<String, Object> out;
        try {
            if (!isOp(agentId)) throw new IllegalStateException("Agent " + agentId + " is not opped; a server console must run /agentsmc op " + agentId);
            cmd = subcommand(input);
            a = args(input);
            out = run(plugin, self, agentId, cmd, a);
            out.put("ok", true);
        } catch (RuntimeException e) {
            out = new LinkedHashMap<>();
            out.put("ok", false);
            out.put("error", e.getMessage() == null ? e.toString() : e.getMessage());
        }
        out.put("action", "op");
        out.put("command", cmd);
        out.put("args", a);
        log(plugin.getLogger(), agentId, cmd, a, out);
        return out;
    }
    static void log(Logger log, String agent, String cmd, List<String> a, Map<String, Object> out) {
        boolean ok = Boolean.TRUE.equals(out.get("ok"));
        log.info("[op] agent=" + agent + " command=" + cmd + (a.isEmpty() ? "" : " args=" + String.join(" ", a))
                + " result=" + (ok ? "ok " + out.getOrDefault("message", "") : "error " + out.get("error")));
    }

    private static Map<String, Object> msg(String m) { Map<String, Object> o = new LinkedHashMap<>(); o.put("message", m); return o; }

    private static Map<String, Object> run(AgentsMC plugin, NPC self, String agentId, String cmd, List<String> a) {
        Entity selfBody = self.getEntity();
        World world = selfBody != null ? selfBody.getWorld() : Bukkit.getWorlds().get(0);
        switch (cmd) {
            case "give": {
                need(a, 2, "give <target> <item> [amount]");
                Player t = targetPlayer(a.get(0));
                Material m = Material.matchMaterial(a.get(1));
                if (m == null || !m.isItem() || m.isAir()) throw new IllegalArgumentException("Unknown item: " + a.get(1));
                int amount = a.size() >= 3 ? integer(a.get(2), "amount") : 1;
                if (amount < 1 || amount > MAX_GIVE) throw new IllegalArgumentException("amount must be 1.." + MAX_GIVE);
                int left = amount, dropped = 0;
                while (left > 0) {
                    int n = Math.min(left, m.getMaxStackSize());
                    for (ItemStack rest : t.getInventory().addItem(new ItemStack(m, n)).values()) { t.getWorld().dropItemNaturally(t.getLocation(), rest); dropped += rest.getAmount(); }
                    left -= n;
                }
                return msg("gave " + amount + " " + m.getKey().getKey() + " to " + t.getName() + (dropped > 0 ? " (" + dropped + " dropped, inventory full)" : ""));
            }
            case "tp": {
                need(a, 2, "tp <agent|player> [to] <x y z | player | agent>");
                Entity who = targetEntity(a.get(0));
                List<String> d = a.get(1).equalsIgnoreCase("to") ? a.subList(2, a.size()) : a.subList(1, a.size());
                Location dest;
                if (d.size() >= 3) {
                    double x = number(d.get(0), "x"), y = number(d.get(1), "y"), z = number(d.get(2), "z");
                    World w = who.getWorld();
                    if (y < w.getMinHeight() || y > w.getMaxHeight() || Math.abs(x) > 29_999_984 || Math.abs(z) > 29_999_984) throw new IllegalArgumentException("Destination is outside the world");
                    dest = new Location(w, x, y, z, who.getLocation().getYaw(), who.getLocation().getPitch());
                } else if (d.size() == 1) dest = targetEntity(d.get(0)).getLocation();
                else throw new IllegalArgumentException("usage: tp <agent|player> [to] <x y z | player | agent>");
                NPC npc = CitizensAPI.getNPCRegistry().getNPC(who);
                if (npc != null) npc.teleport(dest, PlayerTeleportEvent.TeleportCause.PLUGIN);
                else who.teleport(dest);
                return msg("teleported " + who.getName() + " to " + dest.getWorld().getName() + " " + Math.round(dest.getX()) + " " + Math.round(dest.getY()) + " " + Math.round(dest.getZ()));
            }
            case "heal": {
                need(a, 1, "heal <target>");
                LivingEntity t = (LivingEntity) targetEntity(a.get(0));
                var max = t.getAttribute(Attribute.MAX_HEALTH);
                t.setHealth(max == null ? 20 : max.getValue());
                t.setFireTicks(0);
                return msg("healed " + t.getName());
            }
            case "feed": {
                need(a, 1, "feed <target>");
                Player t = targetPlayer(a.get(0));
                t.setFoodLevel(20); t.setSaturation(20f); t.setExhaustion(0f);
                NPC npc = CitizensAPI.getNPCRegistry().getNPC(t);
                if (npc != null) try { AgentHunger.save(npc); } catch (RuntimeException ignored) {}
                return msg("fed " + t.getName());
            }
            case "gamemode": {
                need(a, 2, "gamemode <agent|player> <survival|creative|adventure|spectator>");
                Player t = targetPlayer(a.get(0));
                GameMode g;
                try { g = GameMode.valueOf(a.get(1).toUpperCase(Locale.ROOT)); } catch (IllegalArgumentException e) { throw new IllegalArgumentException("gamemode must be survival, creative, adventure or spectator"); }
                t.setGameMode(g);
                return msg("set " + t.getName() + " to " + g.name().toLowerCase(Locale.ROOT));
            }
            case "time": {
                need(a, 1, "time <day|night|ticks>");
                long t = timeTicks(a.get(0));
                world.setTime(t);
                return msg("set time in " + world.getName() + " to " + t);
            }
            case "weather": {
                need(a, 1, "weather <clear|rain|thunder>");
                switch (a.get(0).toLowerCase(Locale.ROOT)) {
                    case "clear" -> { world.setStorm(false); world.setThundering(false); }
                    case "rain" -> { world.setStorm(true); world.setThundering(false); }
                    case "thunder" -> { world.setStorm(true); world.setThundering(true); }
                    default -> throw new IllegalArgumentException("weather must be clear, rain or thunder");
                }
                return msg("set weather in " + world.getName() + " to " + a.get(0).toLowerCase(Locale.ROOT));
            }
            case "difficulty": {
                need(a, 1, "difficulty <peaceful|easy|normal|hard>");
                Difficulty d;
                try { d = Difficulty.valueOf(a.get(0).toUpperCase(Locale.ROOT)); } catch (IllegalArgumentException e) { throw new IllegalArgumentException("difficulty must be peaceful, easy, normal or hard"); }
                for (World w : Bukkit.getWorlds()) w.setDifficulty(d);
                return msg("set difficulty to " + d.name().toLowerCase(Locale.ROOT));
            }
            case "setspawn": {
                need(a, 1, "setspawn <agent|player> [x y z]");
                Player t = targetPlayer(a.get(0));
                Location loc = a.size() >= 4
                        ? new Location(t.getWorld(), number(a.get(1), "x"), number(a.get(2), "y"), number(a.get(3), "z"))
                        : t.getLocation().clone();
                NPC npc = CitizensAPI.getNPCRegistry().getNPC(t);
                if (npc != null) AgentRespawn.remember(npc, loc, "op");
                t.setRespawnLocation(loc, true);
                return msg("set spawn of " + t.getName() + " to " + loc.getWorld().getName() + " " + Math.round(loc.getX()) + " " + Math.round(loc.getY()) + " " + Math.round(loc.getZ()));
            }
            case "clearinv": {
                need(a, 1, "clearinv <target>");
                Player t = targetPlayer(a.get(0));
                t.getInventory().clear();
                return msg("cleared inventory of " + t.getName());
            }
            case "kill": {
                need(a, 1, "kill <target>");
                LivingEntity t = (LivingEntity) targetEntity(a.get(0));
                t.setHealth(0);
                return msg("killed " + t.getName());
            }
            case "summon": {
                need(a, 1, "summon <mob> [x y z]");
                EntityType type;
                try { type = EntityType.valueOf(a.get(0).toUpperCase(Locale.ROOT).replace("MINECRAFT:", "")); }
                catch (IllegalArgumentException e) { throw new IllegalArgumentException("Unknown mob: " + a.get(0)); }
                if (!type.isSpawnable() || !type.isAlive()) throw new IllegalArgumentException("Cannot summon " + a.get(0));
                Location loc = a.size() >= 4
                        ? new Location(world, number(a.get(1), "x"), number(a.get(2), "y"), number(a.get(3), "z"))
                        : (selfBody != null ? selfBody.getLocation() : world.getSpawnLocation());
                Entity e = world.spawnEntity(loc, type);
                return msg("summoned " + type.name().toLowerCase(Locale.ROOT) + " at " + Math.round(loc.getX()) + " " + Math.round(loc.getY()) + " " + Math.round(loc.getZ()) + " (entity " + e.getEntityId() + ")");
            }
            case "killmobs": {
                if (selfBody == null) throw new IllegalStateException("Body is not spawned");
                int r = killRadius(a);
                int n = 0;
                for (Entity e : selfBody.getNearbyEntities(r, r, r)) {
                    if (!(e instanceof Enemy) || e instanceof Player || CitizensAPI.getNPCRegistry().isNPC(e)) continue;
                    ((LivingEntity) e).setHealth(0); n++;
                }
                Map<String, Object> o = msg("killed " + n + " hostile mobs within " + r);
                o.put("killed", n); o.put("radius", r);
                return o;
            }
            case "fill": {
                need(a, 7, "fill <x1 y1 z1 x2 y2 z2> <block>");
                int x1 = integer(a.get(0), "x1"), y1 = integer(a.get(1), "y1"), z1 = integer(a.get(2), "z1");
                int x2 = integer(a.get(3), "x2"), y2 = integer(a.get(4), "y2"), z2 = integer(a.get(5), "z2");
                long v = volume(x1, y1, z1, x2, y2, z2);
                if (v > MAX_FILL) throw new IllegalArgumentException("fill volume " + v + " exceeds " + MAX_FILL);
                Material m = Material.matchMaterial(a.get(6));
                if (m == null || !m.isBlock()) throw new IllegalArgumentException("Unknown block: " + a.get(6));
                int ylo = Math.max(Math.min(y1, y2), world.getMinHeight()), yhi = Math.min(Math.max(y1, y2), world.getMaxHeight() - 1);
                int n = 0;
                for (int x = Math.min(x1, x2); x <= Math.max(x1, x2); x++)
                    for (int y = ylo; y <= yhi; y++)
                        for (int z = Math.min(z1, z2); z <= Math.max(z1, z2); z++) {
                            Block b = world.getBlockAt(x, y, z);
                            if (b.getType() != m) { b.setType(m, false); n++; }
                        }
                Map<String, Object> o = msg("filled " + n + " of " + v + " blocks with " + m.getKey().getKey());
                o.put("changed", n); o.put("volume", v);
                return o;
            }
            case "op", "deop": {
                need(a, 1, cmd + " <player>");
                String name = realPlayerName(a.get(0));
                OfflinePlayer p = Bukkit.getOfflinePlayer(name);
                p.setOp(cmd.equals("op"));
                return msg((cmd.equals("op") ? "opped " : "deopped ") + name);
            }
            case "ban": {
                need(a, 1, "ban <player> [reason]");
                String name = realPlayerName(a.get(0));
                String reason = rest(a, 1);
                OfflinePlayer p = Bukkit.getOfflinePlayer(name);
                p.ban(reason == null ? "Banned by an operator." : reason, (java.util.Date) null, "AgentsMC agent " + agentId);
                if (p.getPlayer() != null) p.getPlayer().kickPlayer(reason == null ? "You are banned from this server." : reason);
                return msg("banned " + name + (reason == null ? "" : ": " + reason));
            }
            case "pardon": {
                need(a, 1, "pardon <player>");
                String name = realPlayerName(a.get(0));
                @SuppressWarnings("deprecation")
                BanList<?> list = Bukkit.getBanList(BanList.Type.NAME);
                list.pardon(name);
                return msg("pardoned " + name);
            }
            case "kick": {
                need(a, 1, "kick <player> [reason]");
                String name = realPlayerName(a.get(0));
                Player p = Bukkit.getPlayerExact(name);
                if (p == null || CitizensAPI.getNPCRegistry().isNPC(p)) throw new IllegalArgumentException("Player is not online: " + name);
                String reason = rest(a, 1);
                p.kickPlayer(reason == null ? "Kicked by an operator." : reason);
                return msg("kicked " + name + (reason == null ? "" : ": " + reason));
            }
            case "restart": return restart(plugin);
            default: throw new IllegalArgumentException("Unknown op command: " + cmd);
        }
    }

    /** Restart only when spigot.yml settings.restart-script points at an existing file; never a bare shutdown. */
    static String restartScript() {
        try {
            YamlConfiguration spigot = Bukkit.spigot().getSpigotConfig();
            String script = spigot == null ? null : spigot.getString("settings.restart-script", null);
            if (script == null || script.isBlank()) return null;
            String first = script.trim().split("\\s+")[0];
            return new File(first).isFile() ? script : null;
        } catch (RuntimeException | LinkageError e) { return null; }
    }
    private static Map<String, Object> restart(AgentsMC plugin) {
        String script = restartScript();
        if (script == null) throw new IllegalStateException("restart is not available on this host: no restart script is configured (spigot.yml settings.restart-script must point to an existing file)");
        Bukkit.getScheduler().runTaskLater(plugin, () -> Bukkit.spigot().restart(), RESTART_DELAY_TICKS);
        Map<String, Object> o = msg("server restarting in " + (RESTART_DELAY_TICKS / 20) + "s via " + script);
        o.put("restartInTicks", RESTART_DELAY_TICKS);
        return o;
    }

    /* ---------- target resolution: online players, then agents (Citizens NPCs) ---------- */
    /** 0.7.3: "/agentsmc op Intern Steve" — join args[1..] into one name (names may contain spaces). */
    static String joinName(String[] args) {
        if (args == null || args.length < 2) return "";
        return String.join(" ", java.util.Arrays.copyOfRange(args, 1, args.length)).trim().replaceAll("\\s+", " ");
    }
    static NPC agentByName(String name) {
        NPC hit = null;
        for (NPC npc : CitizensAPI.getNPCRegistry()) {
            if (!npc.data().has(AGENT_ID)) continue;
            String id = AgentErrors.text(npc.data().get(AGENT_ID));
            if (AgentIds.same(name, id) || npc.getName().equalsIgnoreCase(name)) {
                if (hit != null && hit != npc) throw new IllegalArgumentException("Ambiguous agent name: " + name);
                hit = npc;
            }
        }
        return hit;
    }
    static Entity targetEntity(String name) {
        Player p = Bukkit.getPlayerExact(name);
        if (p != null && !CitizensAPI.getNPCRegistry().isNPC(p)) return p;
        NPC npc = agentByName(name);
        if (npc != null) {
            if (!npc.isSpawned() || npc.getEntity() == null) throw new IllegalStateException("Agent " + name + " is not spawned");
            return npc.getEntity();
        }
        throw new IllegalArgumentException("No online player or agent named " + name);
    }
    static Player targetPlayer(String name) {
        Entity e = targetEntity(name);
        if (!(e instanceof Player p)) throw new IllegalArgumentException(name + " has no player body");
        return p;
    }
    /** op/deop/ban/pardon/kick act on real Minecraft accounts only, never on agents or their bridge access. */
    static String realPlayerName(String name) {
        playerName(name);
        if (agentByName(name) != null) throw new IllegalArgumentException(name + " is an agent; op/deop/ban/pardon/kick only apply to real players (agent op is console-only: /agentsmc op|deop)");
        return name;
    }

    private AgentOp() {}
}
