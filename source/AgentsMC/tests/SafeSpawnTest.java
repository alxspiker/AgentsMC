package dev.agentsmc;

import java.util.*;

/** Safe spawn search over a synthetic world. */
public final class SafeSpawnTest {
    static final Set<String> solid = new HashSet<>(), plants = new HashSet<>();
    static String k(int x,int y,int z){return x+","+y+","+z;}
    static final AgentSafeSpawn.Probe P = new AgentSafeSpawn.Probe() {
        public boolean open(int x,int y,int z){return !solid.contains(k(x,y,z));}
        public boolean floor(int x,int y,int z){return solid.contains(k(x,y,z));}
        public boolean usable(int x,int y,int z){return y>-64&&y<319;}
    };
    public static void main(String[] a) {
        for(int x=-30;x<=10;x++)for(int z=0;z<=30;z++)solid.add(k(x,64,z)); // grass y64
        // enclosed 1x1 cell at (-9,65,19): dirt walls, roof y67
        for(int y=65;y<=66;y++)for(int[] d:new int[][]{{-10,19},{-8,19},{-9,18},{-9,20}})solid.add(k(d[0],y,d[1]));
        solid.add(k(-9,67,19));
        plants.add(k(-17,65,16)); // short grass: non-colliding, so open
        int[] r=AgentSafeSpawn.find(-8.3,65,19.5,3,P);check(r!=null&&r[1]==65,"enclosed cell not found");
        r=AgentSafeSpawn.find(-8.3,64,19.5,3,P);check(r!=null&&r[1]==65,"y=64 request must stand on grass at 65");
        r=AgentSafeSpawn.find(-16.5,64,16.5,3,P);check(Arrays.equals(r,new int[]{-17,65,16}),"open grass: "+Arrays.toString(r));
        r=AgentSafeSpawn.find(-16.5,63.9999,16.5,3,P);check(r!=null&&r[1]==65,"float drift");
        r=AgentSafeSpawn.find(-16.5,63,16.5,3,P);check(r!=null&&r[1]==65,"feet in grass must move up, got "+Arrays.toString(r));
        check(AgentSafeSpawn.blockCoord(-8.3)==-9&&AgentSafeSpawn.blockCoord(63.9999)==64,"floor coords");
        check(AgentSafeSpawn.find(0,200,0,3,P)==null,"midair accepted");
        check(AgentSafeSpawn.standableY(-2.5,66,20.5,P)==65,"head-height 66 over grass stands at 65");
        check(AgentSafeSpawn.standableY(-2.5,65,20.5,P)==65,"already-standable y kept");
        check(AgentSafeSpawn.standableY(-2.5,64,20.5,P)==65,"feet in floor move up to standable");
        solid.add(k(-2,69,20));
        check(AgentSafeSpawn.standableY(-1.5,70,20.5,P)==70,"standable roof y kept, not snapped down");
        check(AgentSafeSpawn.standableY(-2.5,64,20.5,P)==65,"must not snap two cells up from floor");

        // 2-deep 1x1 hole at -7,64,26 (Steve at -6.5,64,26.7): rim at y=66, no 1-block climb.
        for(int x=-12;x<=-2;x++)for(int z=22;z<=32;z++){
            for(int y=0;y<=63;y++)solid.add(k(x,y,z));
            if(!(x==-7&&z==26))solid.add(k(x,65,z));
        }
        for(int[] d:new int[][]{{-8,26},{-6,26},{-7,25},{-7,27},{-8,25},{-6,25},{-8,27},{-6,27}})
            solid.add(k(d[0],64,d[1]));
        solid.remove(k(-7,64,26));solid.remove(k(-7,65,26));
        check(AgentSafeSpawn.standable(-7,64,26,P),"pit floor is standable");
        check(AgentSafeSpawn.standable(-6,66,26,P),"rim is standable");
        check(!AgentSafeSpawn.canClimb(-7,64,26,AgentSafeSpawn.WALK,P),"cannot jump 2 out of a 1x1 hole");
        check(AgentSafeSpawn.trapped(-7,64,26,AgentSafeSpawn.WALK,P),"2-deep hole is trapped without building");
        check(AgentSafeSpawn.walkStep(-6.5,64,26.7,0,26,AgentSafeSpawn.WALK,P)==null,"no build-free first step out of 2-deep hole");
        check(AgentSafeSpawn.standableY(-6.5,66,26.7,P)==64,"head-height over the hole snaps into the pit");
        int[] rim=AgentSafeSpawn.findWalkOut(-6.5,66,26.7,6,P);
        check(rim!=null&&!AgentSafeSpawn.trapped(rim[0],rim[1],rim[2],AgentSafeSpawn.WALK,P),"rim search must leave the pit: "+Arrays.toString(rim));
        org.bukkit.Location pit=new org.bukkit.Location(null,-6.5,66,26.7);
        org.bukkit.Location safe=AgentNavigation.resolveTarget(pit,P,true);
        check(!AgentSafeSpawn.trapped(AgentSafeSpawn.blockCoord(safe.getX()),AgentSafeSpawn.blockCoord(safe.getY()),AgentSafeSpawn.blockCoord(safe.getZ()),AgentSafeSpawn.WALK,P),
                "noBuild resolve must not keep the pit: "+safe);
        solid.add(k(-6,64,26));solid.remove(k(-6,65,26)); // staircase: stand on the step at y=65
        check(AgentSafeSpawn.standable(-6,65,26,P),"stair step standable");
        check(AgentSafeSpawn.canClimb(-7,64,26,AgentSafeSpawn.WALK,P),"stairs are a build-free exit");
        check(!AgentSafeSpawn.trapped(-7,64,26,AgentSafeSpawn.WALK,P),"stairs mean not trapped");
        int[] step=AgentSafeSpawn.walkStep(-6.5,64,26.7,0,26,AgentSafeSpawn.WALK,P);
        check(step!=null&&step[0]==-6&&step[1]==65,"walk-out first step is the stair: "+Arrays.toString(step));

        // Open 3-block drop onto a wide terrace: not enclosed, descending walk must work.
        for (int x = 20; x <= 28; x++) for (int z = 40; z <= 48; z++) {
            int stand = x < 24 ? 70 : 67;
            solid.add(k(x, stand - 1, z));
            for (int y = 0; y < stand - 1; y++) solid.add(k(x, y, z));
        }
        check(AgentSafeSpawn.standable(22, 70, 44, P), "upper terrace standable");
        check(AgentSafeSpawn.standable(26, 67, 44, P), "lower terrace standable");
        check(!AgentSafeSpawn.trapped(26, 67, 44, AgentSafeSpawn.WALK, P), "open lower terrace is not enclosed");
        check(AgentNavigation.refuseProtectedPitReason(AgentSafeSpawn.trapped(26, 67, 44, AgentSafeSpawn.WALK, P)) == null,
                "open 3-block descent must not be refused as trapped");
        int[] sky = AgentSafeSpawn.standableBelow(-17, 316, 16, P);
        check(sky != null && sky[0] == -17 && sky[2] == 16 && sky[1] == 65, "standableBelow snaps the same column from sky: " + Arrays.toString(sky));
        check(AgentSafeSpawn.standableBelow(100, 200, 100, P) == null, "void column must not invent a far landing");

        surfaceAboveCave();
        hillsideStallCave();
        craterRespawn();

        System.out.println("PASS: safe spawn finds enclosed cell, open grass, fixes feet-in-block and float drift, standable Y, protected-area pit walk-out, open downhill, standableBelow, surface-above-cave, hillside stall");
    }

    /** Live failure: world spawn stayed at y=82 while repeated explosions lowered its floor to y=75. */
    static void craterRespawn() {
        AgentSafeSpawn.Probe crater = new AgentSafeSpawn.Probe() {
            public boolean open(int x,int y,int z){return y>75;}
            public boolean floor(int x,int y,int z){return y<=75;}
            public boolean usable(int x,int y,int z){return y>-64&&y<319;}
        };
        check(AgentSafeSpawn.find(0,82,0,3,crater)==null,"old local cube must reproduce missing spawn ground");
        int[] safe=AgentSafeSpawn.find(0,82,0,3,AgentsMC.RESPAWN_VERTICAL_RADIUS,crater);
        check(safe!=null&&safe[1]==76,"lifecycle respawn finds crater floor: "+Arrays.toString(safe));
        check(AgentSafeSpawn.standable(safe[0],safe[1],safe[2],crater),"respawn feet/head clear and supported");
        check(AgentSafeSpawn.find(0,100,0,3,AgentsMC.RESPAWN_VERTICAL_RADIUS,crater)==null,"outside configured vertical search must fail visibly");
    }

    /**
     * Builder Bot 0.6.37: jump/knockback at spawn (~y 82) must not snap through
     * dirt/stone into a cave at y -24. The first standable cell in an open column
     * is the grass; solid ground stops the search.
     */
    static void surfaceAboveCave() {
        int x = 40, z = 50;
        for (int y = -64; y <= 81; y++) {
            if (y == -24 || y == -23) continue;
            solid.add(k(x, y, z));
        }
        check(AgentSafeSpawn.standable(x, 82, z, P), "surface stand on grass y=81");
        check(AgentSafeSpawn.standable(x, -24, z, P), "cave under spawn is standable");
        check(AgentSafeSpawn.supported(x, 82, z, P), "standing on spawn is supported");
        check(AgentSafeSpawn.supported(x, 81, z, P), "clipped into the grass block is still supported");
        int[] jump = AgentSafeSpawn.standableBelow(x, 83, z, P);
        check(jump != null && jump[1] == 82, "jump above spawn lands on the surface, not the cave: " + Arrays.toString(jump));
        check(AgentSafeSpawn.standableBelow(x, 82, z, P)[1] == 82, "already on the surface stays there");
        check(AgentSafeSpawn.standableBelow(x, 80, z, P) == null, "inside dirt must not tunnel into the cave");
        check(AgentSafeSpawn.standableBelow(x, -23, z, P)[1] == -24, "open cave air still lands on the cave floor");
        check(!AgentNavigation.mayRecoverFromAir(true, false, false, false, -0.08, 5_000), "onGround is not air recovery");
        check(!AgentNavigation.mayRecoverFromAir(false, true, false, false, -0.4, 5_000), "combat knockback is not air recovery");
        check(!AgentNavigation.mayRecoverFromAir(false, false, false, true, 0, 5_000), "supported body is not air recovery");
        check(!AgentNavigation.mayRecoverFromAir(false, false, true, false, 0, 5_000), "inside solid is not air recovery");
        check(!AgentNavigation.mayRecoverFromAir(false, false, false, false, 0.42, 5_000), "ascending jump is not air recovery");
        check(!AgentNavigation.mayRecoverFromAir(false, false, false, false, -0.2, 400), "brief hang is not air recovery");
        check(AgentNavigation.mayRecoverFromAir(false, false, false, false, -0.2, AgentNavigation.AIR_RECOVER_MS),
                "long hang in open air may recover");
    }

    /**
     * Builder Bot 0.6.37 addendum: goto stalled at 25,79,4 then scan returned
     * recovered_from_air from y 78.94 to y 30 through stone. Body was 0.06 off
     * the hillside, not in a void.
     */
    static void hillsideStallCave() {
        int x = 25, z = 4;
        for (int y = -64; y <= 78; y++) {
            if (y == 30 || y == 31) continue;
            solid.add(k(x, y, z));
        }
        check(AgentSafeSpawn.standable(x, 79, z, P), "hillside stand at 79");
        check(AgentSafeSpawn.standable(x, 30, z, P), "cave at y 30 is standable");
        check(AgentSafeSpawn.groundWithin(78.94, x, z, P), "0.06 into the hillside is ground, not air");
        check(AgentSafeSpawn.supported(x, 78, z, P), "clipped into the hillside block is supported");
        check(AgentSafeSpawn.standableBelow(x, 78, z, P) == null, "inside the hillside must not tunnel to y 30");
        int[] fromStand = AgentSafeSpawn.standableBelow(x, 79, z, P);
        check(fromStand != null && fromStand[1] == 79, "open-column from the hillside is the hillside: " + Arrays.toString(fromStand));
        check(!AgentNavigation.mayRecoverFromAir(false, false, true, true, true, 0, 5_000),
                "hillside stall + scan must not recover_from_air");
        check(!AgentSafeSpawn.groundWithin(316, x, z, P), "sky column with no nearby floor is airborne");
    }

    static void check(boolean c,String m){if(!c)throw new AssertionError(m);}
}

