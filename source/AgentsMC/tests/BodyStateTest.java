package dev.agentsmc;

import static dev.agentsmc.AgentBodyState.Status.*;

/** Body presence classification used by spawn guard, list, heartbeat and auto-respawn. */
public final class BodyStateTest {
    public static void main(String[] a) {
        check(AgentBodyState.classify(false,false,false,false)==ABSENT,"no entity");
        check(AgentBodyState.classify(true,true,true,true)==LIVE,"live");
        check(AgentBodyState.classify(true,false,true,true)==NEEDS_RESYNC,"visible but Citizens says not spawned");
        check(AgentBodyState.classify(true,true,false,true)==STALE,"dead entity");
        check(AgentBodyState.classify(true,true,true,false)==LIVE,"unloaded chunk still online");
        check(AgentBodyState.present(NEEDS_RESYNC)&&AgentBodyState.present(LIVE)&&!AgentBodyState.present(STALE)&&!AgentBodyState.present(ABSENT),"present");
        System.out.println("PASS: body state distinguishes live, resync, stale and absent bodies");
    }
    static void check(boolean c,String m){if(!c)throw new AssertionError(m);}
}
