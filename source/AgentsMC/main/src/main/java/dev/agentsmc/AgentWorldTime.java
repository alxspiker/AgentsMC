package dev.agentsmc;

import java.util.LinkedHashMap;
import java.util.Map;

import org.bukkit.GameRules;
import org.bukkit.World;

/**
 * Minecraft world clock for agents. Uses the world's tick counters, not sky
 * light, so an indoor survey or observe still knows dawn/day/dusk/night.
 */
final class AgentWorldTime {
    static final int DAY_LENGTH = 24000;
    /** Vanilla beds become usable around this tick on a clear night. */
    static final int SLEEP_START = 12542;
    /** Vanilla morning after a night's sleep. */
    static final int SLEEP_END = 23460;
    static final int DAWN = 23000, DAY = 1000, DUSK = 12000, NIGHT = 13000;
    /** Tick 0 is 6:00 AM on the Minecraft clock; 12000 is 6:00 PM. */
    static final int SUNRISE = 0, SUNSET = 12000;

    static long wrap(long ticks) {
        return Math.floorMod(ticks, DAY_LENGTH);
    }

    static long dayNumber(long fullTime) {
        return Math.floorDiv(fullTime, DAY_LENGTH);
    }

    /** dawn 23000–999, day 1000–11999, dusk 12000–12999, night 13000–22999. */
    static String timeOfDay(long time) {
        long t = wrap(time);
        if (t >= DAWN || t < DAY) return "dawn";
        if (t < DUSK) return "day";
        if (t < NIGHT) return "dusk";
        return "night";
    }

    static boolean sleepable(long time, boolean thunder) {
        long t = wrap(time);
        return thunder || (t >= SLEEP_START && t < SLEEP_END);
    }

    /**
     * Clock hours from {@code (worldTime / 1000 + 6) % 24}. 19595 → 1:35 AM
     * (not 2:35). Minutes are floored so agents do not round the wrong way.
     */
    static int[] clock(long time) {
        double hours = (wrap(time) / 1000.0 + 6.0) % 24.0;
        if (hours < 0) hours += 24.0;
        int hour = (int) hours;
        int minute = (int) ((hours - hour) * 60.0);
        if (minute >= 60) {
            minute = 0;
            hour = (hour + 1) % 24;
        }
        return new int[]{hour, minute};
    }

    static String time24(long time) {
        int[] hm = clock(time);
        return String.format("%02d:%02d", hm[0], hm[1]);
    }

    static String time12(long time) {
        int[] hm = clock(time);
        int h12 = hm[0] % 12;
        if (h12 == 0) h12 = 12;
        return h12 + String.format(":%02d %s", hm[1], hm[0] < 12 ? "AM" : "PM");
    }

    /** Ticks until {@code target} (0 when already there; wraps the day). */
    static long ticksUntil(long now, long target) {
        long delta = wrap(target) - wrap(now);
        return delta < 0 ? delta + DAY_LENGTH : delta;
    }

    static Map<String, Object> view(long time, long fullTime, boolean daylightCycle,
                                    boolean storm, boolean thunder) {
        long worldTime = wrap(time);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("worldTime", worldTime);
        out.put("time24", time24(worldTime));
        out.put("time12", time12(worldTime));
        out.put("timeOfDay", timeOfDay(worldTime));
        out.put("dayNumber", dayNumber(fullTime));
        out.put("fullTime", fullTime);
        out.put("daylightCycle", daylightCycle);
        if (!daylightCycle) out.put("frozen", true);
        out.put("sleepable", sleepable(worldTime, thunder));
        out.put("ticksUntilSunrise", ticksUntil(worldTime, SUNRISE));
        long untilSunset = ticksUntil(worldTime, SUNSET);
        boolean afterSunset = worldTime >= SUNSET;
        out.put("afterSunset", afterSunset);
        out.put("ticksUntilSunset", afterSunset ? 0L : untilSunset);
        if (afterSunset) out.put("ticksUntilNextSunset", untilSunset);
        out.put("storm", storm);
        out.put("thunder", thunder);
        return out;
    }

    static Map<String, Object> view(World world) {
        boolean cycle = true;
        try {
            Boolean rule = world.getGameRuleValue(GameRules.ADVANCE_TIME);
            if (rule != null) cycle = rule;
        } catch (RuntimeException ignored) {
            // Older stubs / missing gamerule: treat as cycling.
        }
        return view(world.getTime(), world.getFullTime(), cycle, world.hasStorm(), world.isThundering());
    }

    private AgentWorldTime() {}
}
