package dev.agentsmc;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockPlaceEvent;

/**
 * 0.7.7: in-memory, bounded log of blocks placed or broken by players and agents in the last
 * {@link #WINDOW_MS}. scan recent:true reads it so an agent can see what someone just built.
 */
public final class AgentBlockLog implements Listener {
    static final long WINDOW_MS = 30L * 60L * 1000L;
    static final int KEEP = 4096;

    record Change(String world, int x, int y, int z, String kind, String block, String by, long time) {}

    private static final ArrayDeque<Change> LOG = new ArrayDeque<>();

    static synchronized void record(String world, int x, int y, int z, String kind, String block, String by, long time) {
        LOG.addLast(new Change(world, x, y, z, kind, block, by, time));
        while (LOG.size() > KEEP) LOG.removeFirst();
        prune(time);
    }

    private static void prune(long now) {
        while (!LOG.isEmpty() && now - LOG.peekFirst().time() > WINDOW_MS) LOG.removeFirst();
    }

    static synchronized int size() { return LOG.size(); }
    static synchronized void clear() { LOG.clear(); }

    /** Changes inside the sphere, newest first, at most limit. Returns {changes,total,truncated}. */
    static synchronized Map<String, Object> near(String world, int cx, int cy, int cz, int radius, int limit, long now) {
        prune(now);
        List<Map<String, Object>> all = new ArrayList<>();
        Iterator<Change> it = LOG.descendingIterator();
        while (it.hasNext()) {
            Change c = it.next();
            if (!c.world().equals(world) || now - c.time() > WINDOW_MS) continue;
            int dx = c.x() - cx, dy = c.y() - cy, dz = c.z() - cz;
            if (!AgentReach.scanInRadius(dx, dy, dz, radius)) continue;
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("x", c.x()); m.put("y", c.y()); m.put("z", c.z());
            m.put("kind", c.kind()); m.put("block", c.block()); m.put("by", c.by());
            m.put("time", c.time()); m.put("agoSeconds", Math.max(0, (now - c.time()) / 1000));
            all.add(m);
        }
        Map<String, Object> r = new LinkedHashMap<>();
        r.put("changes", new ArrayList<>(all.subList(0, Math.min(limit, all.size()))));
        r.put("total", all.size());
        r.put("returned", Math.min(limit, all.size()));
        r.put("truncated", all.size() > limit);
        r.put("windowMinutes", WINDOW_MS / 60000);
        return r;
    }

    private static String who(Player p) { return p == null ? "unknown" : p.getName(); }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void placed(BlockPlaceEvent e) {
        try {
            var b = e.getBlockPlaced();
            record(b.getWorld().getName(), b.getX(), b.getY(), b.getZ(), "placed", b.getType().name(), who(e.getPlayer()), System.currentTimeMillis());
        } catch (RuntimeException ignored) {}
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void broken(BlockBreakEvent e) {
        try {
            var b = e.getBlock();
            record(b.getWorld().getName(), b.getX(), b.getY(), b.getZ(), "broken", b.getType().name(), who(e.getPlayer()), System.currentTimeMillis());
        } catch (RuntimeException ignored) {}
    }
}
