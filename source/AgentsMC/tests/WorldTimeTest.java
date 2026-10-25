package dev.agentsmc;

import java.util.Map;

/** Offline world-clock classification. No sky or World required. */
public final class WorldTimeTest {
    public static void main(String[] args) {
        check("dawn".equals(AgentWorldTime.timeOfDay(0)), "sunrise is dawn");
        check("dawn".equals(AgentWorldTime.timeOfDay(999)), "late dawn");
        check("day".equals(AgentWorldTime.timeOfDay(1000)), "morning is day");
        check("day".equals(AgentWorldTime.timeOfDay(6000)), "noon is day");
        check("day".equals(AgentWorldTime.timeOfDay(11999)), "late day");
        check("dusk".equals(AgentWorldTime.timeOfDay(12000)), "sunset is dusk");
        check("dusk".equals(AgentWorldTime.timeOfDay(12999)), "late dusk");
        check("night".equals(AgentWorldTime.timeOfDay(13000)), "night start");
        check("night".equals(AgentWorldTime.timeOfDay(18000)), "midnight is night");
        check("night".equals(AgentWorldTime.timeOfDay(22999)), "late night");
        check("dawn".equals(AgentWorldTime.timeOfDay(23000)), "pre-sunrise is dawn");
        check("dawn".equals(AgentWorldTime.timeOfDay(23999)), "wrap dawn");
        check("day".equals(AgentWorldTime.timeOfDay(25000)), "wrap 25000 → 1000 day");

        check(AgentWorldTime.dayNumber(0) == 0, "day 0");
        check(AgentWorldTime.dayNumber(23999) == 0, "end of day 0");
        check(AgentWorldTime.dayNumber(24000) == 1, "day 1");
        check(AgentWorldTime.dayNumber(48000) == 2, "day 2");
        check(AgentWorldTime.wrap(-1) == 23999, "negative wrap");

        check(AgentWorldTime.sleepable(12542, false), "beds at night");
        check(AgentWorldTime.sleepable(18000, false), "beds at midnight");
        check(!AgentWorldTime.sleepable(23460, false), "beds close at dawn");
        check(!AgentWorldTime.sleepable(1000, false), "no beds at day");
        check(AgentWorldTime.sleepable(6000, true), "thunder lets you sleep");

        check("06:00".equals(AgentWorldTime.time24(0)) && "6:00 AM".equals(AgentWorldTime.time12(0)),
                "tick 0 is 6:00 AM: " + AgentWorldTime.time24(0) + " " + AgentWorldTime.time12(0));
        check("12:00".equals(AgentWorldTime.time24(6000)) && "12:00 PM".equals(AgentWorldTime.time12(6000)),
                "tick 6000 is noon");
        check("18:00".equals(AgentWorldTime.time24(12000)) && "6:00 PM".equals(AgentWorldTime.time12(12000)),
                "tick 12000 is 6:00 PM");
        check("00:00".equals(AgentWorldTime.time24(18000)) && "12:00 AM".equals(AgentWorldTime.time12(18000)),
                "tick 18000 is midnight");
        check("01:35".equals(AgentWorldTime.time24(19595)) && "1:35 AM".equals(AgentWorldTime.time12(19595)),
                "19595 must be 1:35 AM not 2:35: " + AgentWorldTime.time24(19595) + " " + AgentWorldTime.time12(19595));
        check(AgentWorldTime.ticksUntil(0, 0) == 0, "already sunrise");
        check(AgentWorldTime.ticksUntil(0, 12000) == 12000, "sunrise to sunset");
        check(AgentWorldTime.ticksUntil(19595, 0) == 24000 - 19595, "19595 until 6:00 AM");
        check(AgentWorldTime.ticksUntil(19595, 12000) == 24000 - 19595 + 12000, "19595 until next 6:00 PM");

        Map<String, Object> noon = AgentWorldTime.view(6000, 30000, true, false, false);
        check(Long.valueOf(6000).equals(noon.get("worldTime")), "worldTime: " + noon.get("worldTime"));
        check("12:00".equals(noon.get("time24")) && "12:00 PM".equals(noon.get("time12")), "noon clock: " + noon);
        check(Long.valueOf(30000).equals(noon.get("fullTime")), "fullTime");
        check(Long.valueOf(1).equals(noon.get("dayNumber")), "dayNumber from fullTime");
        check("day".equals(noon.get("timeOfDay")), "timeOfDay");
        check(Boolean.TRUE.equals(noon.get("daylightCycle")), "cycling");
        check(!noon.containsKey("frozen"), "cycling is not frozen");
        check(Boolean.FALSE.equals(noon.get("sleepable")), "day is not sleepable");
        check(Long.valueOf(18000).equals(noon.get("ticksUntilSunrise")), "noon → sunrise: " + noon.get("ticksUntilSunrise"));
        check(Long.valueOf(6000).equals(noon.get("ticksUntilSunset")), "noon → sunset: " + noon.get("ticksUntilSunset"));

        Map<String, Object> late = AgentWorldTime.view(19595, 24000 + 19595, true, false, false);
        check("01:35".equals(late.get("time24")) && "1:35 AM".equals(late.get("time12")),
                "Hero 19595 clock: " + late);
        check("night".equals(late.get("timeOfDay")), "19595 is night");
        check(Boolean.TRUE.equals(late.get("sleepable")), "1:35 AM is sleepable");
        check(Long.valueOf(4405).equals(late.get("ticksUntilSunrise")), "4405 ticks to 6:00 AM: " + late.get("ticksUntilSunrise"));
        check(Boolean.TRUE.equals(late.get("afterSunset")), "1:35 AM is after sunset");
        check(Long.valueOf(0).equals(late.get("ticksUntilSunset")), "after sunset is 0 not wrap: " + late.get("ticksUntilSunset"));
        check(Long.valueOf(24000 - 19595 + 12000).equals(late.get("ticksUntilNextSunset")),
                "ticksUntilNextSunset wraps to next 6 PM: " + late.get("ticksUntilNextSunset"));

        Map<String, Object> dusk = AgentWorldTime.view(12917, 24000 + 12917, true, false, false);
        check("18:55".equals(dusk.get("time24")) && "6:55 PM".equals(dusk.get("time12")),
                "12917 is 6:55 PM: " + dusk);
        check(Boolean.TRUE.equals(dusk.get("afterSunset")) && Long.valueOf(0).equals(dusk.get("ticksUntilSunset")),
                "6:55 PM ticksUntilSunset is 0 not ~23075: " + dusk);

        Map<String, Object> afternoon = AgentWorldTime.view(9496, 24000 + 9496, true, false, false);
        check("15:29".equals(afternoon.get("time24")) && Boolean.FALSE.equals(afternoon.get("afterSunset")),
                "3:29 PM is before sunset: " + afternoon);
        check(Long.valueOf(2504).equals(afternoon.get("ticksUntilSunset")),
                "3:29 PM is 2504 ticks to 6 PM: " + afternoon.get("ticksUntilSunset"));
        check(!afternoon.containsKey("ticksUntilNextSunset"), "before sunset has no next-sunset wrap");

        Map<String, Object> frozen = AgentWorldTime.view(18000, 18000, false, false, false);
        check("night".equals(frozen.get("timeOfDay")), "frozen night still classifies");
        check(Boolean.FALSE.equals(frozen.get("daylightCycle")), "cycle off");
        check(Boolean.TRUE.equals(frozen.get("frozen")), "frozen flag");
        check(Boolean.TRUE.equals(frozen.get("sleepable")), "frozen night is still sleepable");

        check(AgentActionCatalog.advertised("time") && AgentActionCatalog.executable("time"),
                "time action advertised");
        check(!AgentActionCatalog.advertised("command"), "command stays off");
        check(AgentActionProgram.concurrentSafeAction("time"), "time is concurrent-safe");
        check(AgentActionProgram.concurrentSafeAction("observe"), "observe stays concurrent");

        String version = AgentVersion.fromPluginYml();
        check("0.7.4".equals(version), "plugin.yml version: " + version);
        System.out.println("PASS: worldTime/time24/time12, 19595 is 1:35 AM, sunset 0 after dusk, frozen daylight, 0.7.4");
    }

    private static void check(boolean ok, String message) {
        if (!ok) throw new AssertionError(message);
    }
}
