package dev.agentsmc;

/** Offline protect retreat hysteresis, eat cooldown, open descent, chase radius. */
public final class ProtectTest {
    public static void main(String[] args) {
        check(AgentProtect.RECOVER_HEALTH == 10, "hysteresis recover at 10");
        check(AgentProtect.CHASE_MARGIN == 4, "chase abandon margin");
        check(AgentCombat.RETREAT_HEALTH == 6, "enter retreat under 6");

        check(AgentProtect.keepRetreat(5.9, false, true), "enter retreat under 6 with threat");
        check(!AgentProtect.keepRetreat(6, false, true), "6 does not enter retreat");
        check(AgentProtect.keepRetreat(8, true, true), "hysteresis holds while threat and health < 10");
        check(AgentProtect.keepRetreat(9.9, true, true), "still recovering");
        check(!AgentProtect.keepRetreat(10, true, true), "recovered at 10");
        check(!AgentProtect.keepRetreat(8, true, false), "threat gone ends retreat");
        check(!AgentProtect.keepRetreat(5, false, false), "no threat: do not keep retreating");
        check(AgentProtect.isRetreat("retreating") && AgentProtect.isRetreat("eating"), "retreat states");
        check(!AgentProtect.isRetreat("following"), "following is not retreat");

        check(!"following".equals(AgentProtect.nextState("eating", 5, false, false, false, true, false)),
                "must not reset retreat to following while still hurt and threatened");
        check("retreating".equals(AgentProtect.nextState("following", 5, false, false, false, true, false)), "enter retreat");
        check("eating".equals(AgentProtect.nextState("retreating", 5, false, false, false, true, true)), "eat while retreating");
        check("retreating".equals(AgentProtect.nextState("retreating", 8, false, false, false, true, false)), "hysteresis");
        check("following".equals(AgentProtect.nextState("retreating", 8, false, false, false, false, false)), "threat gone");
        check("fighting".equals(AgentProtect.nextState("following", 12, false, false, false, true, false)), "healthy fight");
        check("chasing".equals(AgentProtect.nextState("following", 12, true, false, false, true, false)), "stay on chase");
        check("following".equals(AgentProtect.nextState("chasing", 12, true, false, true, false, false)), "abandon then follow");

        check(AgentSurvivalActions.EAT_TICKS == 32 && AgentSurvivalActions.EAT_MS == 1600, "vanilla eat duration 32 ticks");
        check(AgentSurvivalActions.readyToEat(0, 1000), "first bite");
        check(!AgentSurvivalActions.readyToEat(1000, 1000 + 1599), "still chewing");
        check(AgentSurvivalActions.readyToEat(1000, 1000 + 1600), "ready after 32 ticks");
        check(AgentSurvivalActions.shouldEat(19, false), "hungry may eat");
        check(!AgentSurvivalActions.shouldEat(20, false), "full regular food does not help");
        check(AgentSurvivalActions.shouldEat(20, true), "always-edible at full is appropriate");

        check(!AgentProtect.abandonChase(16, 16), "at radius stay");
        check(!AgentProtect.abandonChase(20, 16), "within margin stay");
        check(AgentProtect.abandonChase(20.01, 16), "past radius+4 abandon");
        check(!AgentProtect.abandonChase(8, 16), "inside radius");

        check(AgentNavigation.refuseProtectedPitReason(true).contains("pit"), "enclosed pit still refused");
        check(AgentNavigation.refuseProtectedPitReason(false) == null, "open descent of 2+ blocks is allowed");
        check(AgentCombat.followHold(8.25, 2, 8, 0), "protect hold on hill overlook");
        check(!AgentCombat.followShouldRepath(false, 12, 10, 6, 0, 0, 500, true), "protect no_path waits");

        String version = AgentVersion.fromPluginYml();
        check("0.7.4".equals(version), "plugin.yml version: " + version);

        System.out.println("PASS: protect retreat hysteresis, eat 32-tick cooldown, open downhill, chase radius+4, overlook hold, 0.7.4");
    }

    private static void check(boolean ok, String message) {
        if (!ok) throw new AssertionError(message);
    }
}

