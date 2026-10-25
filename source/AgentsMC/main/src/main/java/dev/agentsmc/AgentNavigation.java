package dev.agentsmc;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.entity.Player;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Item;
import org.bukkit.util.Vector;
import net.citizensnpcs.api.ai.Navigator;
import net.citizensnpcs.api.ai.PathfinderType;
import net.citizensnpcs.api.ai.event.CancelReason;
import net.citizensnpcs.api.npc.NPC;

/** Tracks accepted requests separately from measured movement. Stuck bodies dig (with a usable tool), step/jump, or pillar; they never teleport except a same-column snap-down when airborne with no path, and a cratered-door STOOP of the same class. */
final class AgentNavigation {
    /** Exact arrival, verified near-arrival (~1 block), short-hop range, Citizens stop margins, and finish-walk range. */
    static final double EXACT=.25, TOLERANCE=.75, SHORT=1.5, MARGIN=.3, CLOSE=3.5, NEAR=1.0;
    /** Stall within this of the dest is arrived_near, not a failed goto. */
    static final double STALL_ARRIVE=2.0;
    /** Vanilla auto-step is 0.6; a full block below the target is not arrival. */
    static final double STEP_UP=.6;
    /** Feet must be on the stair, not mid-air below, or the next JUMP is a 2-block hop. */
    static final double ON_STEP=.05;
    /** Soul sand 0.875 / farmland and dirt path 0.9375 sit just under the next integer. */
    static final double SHORT_FLOOR=.87;
    /** Vanilla jump height. A 2-block face cannot be mounted. */
    static final double MAX_JUMP_UP=1.25;
    /** Horizontal scale while a +1 hop is still below the step lip. Vanilla auto-step is 0.6. */
    static final double JUMP_FORWARD=.2, JUMP_CLEAR=.55;
    static final double SPIN_TRAVEL=4, SPIN_NET=.75, STALL_NET=.5;
    static final long FALL_QUIET_MS=1_000;
    static final String CLIMBED_PAST="climbed_past_path";
    static final String RECOVERED_AIR="recovered_from_air";
    static final double CLIMB_PAST_SLACK=2;
    /** Mid-fall same-column snap stays this short; a hover (no fallDistance) may snap farther. */
    static final double AIR_SNAP_DROP=8;
    /** Jump/knockback is ~0.4s; require a real hang before snapping down. */
    static final long AIR_RECOVER_MS=1_500;
    /** 0.7.4: surface to breathe at or below this share of max air; dive again once air is back above RESUME_AIR. */
    static final double LOW_AIR=.3,RESUME_AIR=.95;
    /** Upward swim velocity while surfacing or floating idle. */
    static final double SWIM_UP=.3,FLOAT_UP=.12;
    static final String SURFACING="surfacingForAir";
    private static final Set<UUID> surfacing=ConcurrentHashMap.newKeySet();
    static boolean success(String status){return "arrived".equals(status)||"arrived_near".equals(status);}
    static String arrival(double distanceSquared){return distanceSquared<=EXACT*EXACT?"arrived":distanceSquared<=TOLERANCE*TOLERANCE?"arrived_near":null;}
    static String arrival(Location here,Location target){
        if(here==null||target==null)return null;
        if(here.getY()<target.getY()-STEP_UP)return null;
        return arrival(here.distanceSquared(target));
    }
    static boolean inProgress(String status){return Set.of("navigating","moving","stuck").contains(status);}
    static boolean held(String status){return "paused".equals(status);}
    static boolean settled(String status){return success(status)||"failed".equals(status)||"stopped".equals(status)||"idle".equals(status)||"blocked_shore".equals(status);}
    static final long STUCK_MS=2_000, STALL_MS=3_000, DIG_PROGRESS_MS=5_000, TRIP_LIMIT_MS=90_000;
    /** Applied each navigation tick (every 2 server ticks). Vanilla walk ~0.22, sprint ~0.28 per game tick. */
    static final double WALK_SPEED=.22, SPRINT_SPEED=.28, WAYPOINT_H=.9, WAYPOINT_V=1.25;
    private static final Map<UUID,Trip> trips=new HashMap<>();
    /** Paused player goto stashed while idle self-defense flees or returns. */
    private static final Map<UUID,Trip> parked=new HashMap<>();
    private static final Map<UUID,UUID> collectionTargets=new HashMap<>();
    private static final Map<UUID,Map<String,Object>> airRecoveries=new ConcurrentHashMap<>();
    private static final Map<UUID,Long> fallQuiet=new ConcurrentHashMap<>();
    private static final Map<UUID,Long> airborneSince=new ConcurrentHashMap<>();
    static class Trip {
        String id=UUID.randomUUID().toString(),status="navigating",reason="";
        Location start,last,target,stallAt;long requestedAt,lastProgressAt,driveAt,pausedAt,lastStallCell;double travelled;boolean dig,finishing,finishScheduled,boat,gridPath,segment;int unstuck,pathIndex,replans,stallRepeats;String lastUnstick="",heldStatus="";
        List<int[]> path;Set<Long> penalized=new HashSet<>();Set<Long> banned=new HashSet<>();
        Trip(Location current,Location target){this.start=current.clone();this.last=current.clone();this.stallAt=current.clone();this.target=target.clone();requestedAt=lastProgressAt=System.currentTimeMillis();}
    }
    static final int MAX_UNSTICK=8, MAX_REPLAN=32;
    static boolean active(NPC npc){Trip t=trips.get(npc.getUniqueId());return t!=null&&inProgress(t.status)||collectionTargets.containsKey(npc.getUniqueId());}
    /** True while the current (not parked) trip is still walking. */
    static boolean moving(NPC npc){
        if(npc==null)return false;
        Trip t=trips.get(npc.getUniqueId());
        return t!=null&&inProgress(t.status);
    }
    /** Move the current trip aside so a flee/return goto can use the same pathfinder. */
    static boolean park(NPC npc){
        if(npc==null)return false;
        Trip t=trips.remove(npc.getUniqueId());
        if(t==null)return false;
        parked.put(npc.getUniqueId(),t);
        return true;
    }
    static boolean unpark(NPC npc){
        if(npc==null)return false;
        Trip t=parked.remove(npc.getUniqueId());
        if(t==null)return false;
        trips.put(npc.getUniqueId(),t);
        return true;
    }
    static void dropParked(NPC npc){if(npc!=null)parked.remove(npc.getUniqueId());}
    static void dropParked(UUID id){if(id!=null)parked.remove(id);}
    static Map<String,Object> start(NPC npc,Location target){return start(npc,target,false,true);}
    static Map<String,Object> start(NPC npc,Location target,boolean dig){return start(npc,target,dig,true);}
    static Map<String,Object> start(NPC npc,Location target,boolean dig,boolean snapStandable){
        collectionTargets.remove(npc.getUniqueId());
        Navigator nav=npc.getNavigator();Player player=(Player)npc.getEntity();
        AgentSleep.stand(npc,player);
        if(AgentBoats.ridingBoat(player))return startBoat(npc,player,target);
        if(snapStandable)target=dig?resolveDigTarget(target):resolveTarget(target);
        refuseProtectedPit(player.getLocation(),target);
        noteWorld(npc,player.getWorld());
        // fb-43: a body sitting in a 1-2 deep 1-wide hole climbs out before planning.
        String climbed=null;
        if(target.getWorld()!=null&&target.getWorld().equals(player.getWorld())){
            int tx=AgentSafeSpawn.blockCoord(target.getX()),ty=AgentSafeSpawn.blockCoord(target.getY()),tz=AgentSafeSpawn.blockCoord(target.getZ());
            Location h=player.getLocation();
            boolean sameCell=tx==h.getBlockX()&&tz==h.getBlockZ()&&Math.abs(ty-AgentSafeSpawn.blockCoord(h.getY()))<=2;
            if(!sameCell&&escapeHole(player,target))climbed="climbed out of hole";
        }
        Trip trip=new Trip(player.getLocation(),target);if(climbed!=null)trip.lastUnstick=climbed;trip.dig=dig;trips.put(npc.getUniqueId(),trip);
        double initial=player.getLocation().distanceSquared(target);
        if(initial<=EXACT*EXACT){nav.cancelNavigation();trip.status="arrived";return snapshot(npc);}
        if(adoptPath(npc,trip,player,false)){
            Map<String,Object> planned=new LinkedHashMap<>(snapshot(npc));planned.put("state","accepted");
            planned.put("note","Grid path accepted; observe.navigation reports arrived, arrived_near, or no_path/blocked_by_protection/unloaded_chunks/timeout");
            return planned;
        }
        if(trip.status.equals("failed"))throw new IllegalStateException(trip.reason.isEmpty()?AgentPathfinder.NO_PATH:trip.reason);
        nav.cancelNavigation();nav.setPaused(false);
        applyMargins(nav);
        try{AgentDoors.openToward(npc,player.getLocation(),target);}catch(RuntimeException ignored){}
        if(shortHop(initial,player.getLocation().getY()-target.getY())){
            try{nav.setStraightLineTarget(target);}catch(RuntimeException ignored){}
            if(!nav.isNavigating()){
                if(initial<=EXACT*EXACT){trip.status="arrived";return snapshot(npc);}
                if(dig){trip.status="stuck";trip.reason="Short goto did not start; trying escape";
                    Map<String,Object> result=new LinkedHashMap<>(snapshot(npc));result.put("state","accepted");result.put("note","Short goto did not start; escaping then retrying");return result;}
                trip.status="failed";trip.reason="Navigator did not start the short goto ("+Math.round(Math.sqrt(initial)*10)/10.0+" blocks away; arrival tolerance "+TOLERANCE+")";
                throw new IllegalStateException(trip.reason);
            }
            applyMargins(nav);
            attachCallback(npc,trip);
            Map<String,Object> result=new LinkedHashMap<>(snapshot(npc));result.put("state","accepted");result.put("note","Short goto: stepping directly toward target");return result;
        }
        nav.setTarget(target);
        applyMargins(nav);
        if(!nav.isNavigating()){
            try{AgentDoors.openToward(npc,player.getLocation(),target);}catch(RuntimeException ignored){}
            nav.setTarget(target);
            applyMargins(nav);
        }
        if(!nav.isNavigating()&&dig&&!nav.isPaused()){trip.status="stuck";trip.reason="Navigator rejected target from current spot; trying escape";
            Map<String,Object> result=new LinkedHashMap<>(snapshot(npc));result.put("state","accepted");result.put("note","Pathfinder found no route from here; escaping (dig/step/pillar) then retrying");return result;}
        if(!nav.isNavigating()){trip.status="failed";trip.reason=nav.isPaused()?"Navigator remained paused after goto":"Navigator rejected target before it started";throw new IllegalStateException(trip.reason);}
        attachCallback(npc,trip);
        Map<String,Object> result=new LinkedHashMap<>(snapshot(npc));result.put("state","accepted");result.put("note","Navigation accepted; observe.navigation reports measured progress, arrival or failure");return result;
    }
    static Map<String,Object> collect(NPC npc,Item item){
        Map<String,Object> result=start(npc,item.getLocation(),false,false);collectionTargets.put(npc.getUniqueId(),item.getUniqueId());return result;
    }
    static Map<String,Object> startBoat(NPC npc,Player player,Location target){
        Navigator nav=npc.getNavigator();
        try{nav.cancelNavigation();}catch(RuntimeException ignored){}
        Trip trip=new Trip(player.getLocation(),target.clone());trip.boat=true;trip.dig=false;trips.put(npc.getUniqueId(),trip);
        double remain=AgentBoats.horizontal(player.getX(),player.getZ(),target.getX(),target.getZ());
        String done=AgentBoats.settle(remain,AgentBoats.waterAt(player.getLocation()),true);
        if(done!=null){trip.status=done;if(AgentBoats.shouldDismount(done,AgentBoats.waterAt(player.getLocation())))try{player.leaveVehicle();}catch(RuntimeException ignored){}
            Map<String,Object> result=new LinkedHashMap<>(snapshot(npc));result.put("state",done);return result;}
        Map<String,Object> result=new LinkedHashMap<>(snapshot(npc));result.put("state","accepted");result.put("mode","boat");
        result.put("note","Steering boat toward target over water; observe.navigation reports arrived, arrived_near or blocked_shore");return result;
    }
    static boolean adoptPath(NPC npc,Trip trip,Player player,boolean replan){
        AgentPathfinder.Grid grid=AgentPathfinder.tryWorld(player.getWorld());
        if(grid==null)return false;
        AgentPathfinder.Options opt=new AgentPathfinder.Options();
        opt.dig=trip.dig;opt.build=trip.dig&&!AgentProtection.blocked(player.getLocation());
        opt.penalized.addAll(trip.penalized);opt.banned.addAll(trip.banned);
        int sx=AgentSafeSpawn.blockCoord(player.getX()),sy=AgentSafeSpawn.blockCoord(player.getY()),sz=AgentSafeSpawn.blockCoord(player.getZ());
        int gx=AgentSafeSpawn.blockCoord(trip.target.getX()),gy=AgentSafeSpawn.blockCoord(trip.target.getY()),gz=AgentSafeSpawn.blockCoord(trip.target.getZ());
        try{AgentPathfinder.loadCorridor(player.getWorld(),sx,sz,gx,gz,24);}catch(RuntimeException ignored){}
        AgentPathfinder.Result plan=AgentPathfinder.plan(sx,sy,sz,gx,gy,gz,grid,opt);
        if(plan.fail!=null){
            trip.status="failed";trip.reason=plan.fail+(plan.note==null||plan.note.isBlank()?"":": "+plan.note)
                    +" (goal "+gx+","+gy+","+gz+")";
            if(plan.note!=null&&!plan.note.isEmpty())trip.lastUnstick=plan.note;
            try{npc.getNavigator().cancelNavigation();}catch(RuntimeException ignored){}
            return false;
        }
        if(plan.path==null||plan.path.size()<2)return false;
        trip.path=plan.path;trip.pathIndex=1;trip.gridPath=true;trip.segment=plan.segment;
        trip.status="navigating";trip.reason="";
        if(replan){trip.replans++;trip.lastUnstick="replan";}
        // Drop any leftover Citizens hop *after* gridPath is set so navComplete cannot replan.
        try{npc.getNavigator().cancelNavigation();}catch(RuntimeException ignored){}
        try{npc.getNavigator().setPaused(false);}catch(RuntimeException ignored){}
        followWaypoint(npc,trip,player);
        return true;
    }
    static void followWaypoint(NPC npc,Trip trip,Player player){
        if(trip.path==null||trip.pathIndex>=trip.path.size())return;
        int[] wp=trip.path.get(trip.pathIndex);
        Location to=waypointLocation(player,wp);
        boolean sprint=AgentPathfinder.longLeg(trip.path,trip.pathIndex-1);
        try{player.setSprinting(sprint);}catch(RuntimeException ignored){}
        if(wp[3]==AgentPathfinder.DIG){
            try{String did=AgentBehaviors.mineWaypoint(player,wp);if(did!=null)trip.lastUnstick=did;}catch(RuntimeException ignored){}
            if(trip.driveAt<=0)trip.driveAt=System.currentTimeMillis();
            if(!AgentBehaviors.waypointOpen(player,wp)){
                if(trip.lastUnstick==null||trip.lastUnstick.isEmpty())trip.lastUnstick="dig blocked";
                return;
            }
        }else if(wp[3]==AgentPathfinder.PILLAR||wp[3]==AgentPathfinder.BRIDGE){
            try{String did=AgentBehaviors.unstick(npc,player,to,trip.target.getY()+1);if(did!=null)trip.lastUnstick=did;}catch(RuntimeException ignored){}
        }
        try{AgentDoors.openToward(npc,player.getLocation(),to);}catch(RuntimeException ignored){}
        if(wp[3]==AgentPathfinder.STOOP && tryStoop(player,to))return;
        // Drive the body ourselves. Do not cancel/retarget Citizens (that fires
        // NavigationCompleteEvent and used to replan 32 times before the first step).
        driveWalk(player,to,sprint,wp[3],trip.target.getY()+1);
        if(trip.driveAt<=0)trip.driveAt=System.currentTimeMillis();
    }
    static boolean replanFrom(NPC npc,Trip trip,Player player,int px,int py,int pz){
        if(trip.replans>=MAX_REPLAN){trip.status="failed";trip.reason=AgentPathfinder.TIMEOUT;return false;}
        trip.penalized.add(AgentPathfinder.pack(px,py,pz));
        double keepTravel=trip.travelled;long keepProgress=trip.lastProgressAt;
        boolean ok=adoptPath(npc,trip,player,true);
        trip.travelled=keepTravel;trip.lastProgressAt=keepProgress;
        trip.driveAt=System.currentTimeMillis();
        return ok;
    }
    /** Stuck only after a drive was attempted. Replans must not start this clock from request time. */
    static boolean stuckDue(long now,long lastProgressAt,long driveAt,long stuckMs){
        if(driveAt<=0)return false;
        return now-driveAt>=stuckMs&&now-lastProgressAt>=stuckMs;
    }
    static boolean reachedWaypoint(double x,double y,double z,int[] wp,double standY){
        if(wp==null)return false;
        double wd=Math.hypot(x-(wp[0]+.5),z-(wp[2]+.5));
        if(wd>WAYPOINT_H)return false;
        int move=wp.length>3?wp[3]:AgentPathfinder.WALK;
        // DROP/DIG-down: still on the ledge is not arrival. Vanilla walks off and falls.
        if((move==AgentPathfinder.DROP||move==AgentPathfinder.DIG)&&y>standY+0.4)return false;
        // JUMP/step-up: still below the stair is not the waypoint (fb-10). Mid-air
        // 0.6 below used to count as arrival and the next JUMP became a 2-block hop.
        if(y<standY-ON_STEP)return false;
        return y<=standY+WAYPOINT_V;
    }
    static Location waypointLocation(Player player,int[] wp){
        World world=player.getWorld();
        double stand=wp[1];
        try{
            AgentPathfinder.Grid grid=AgentPathfinder.tryWorld(world);
            if(grid!=null)stand=grid.standY(wp[0],wp[1],wp[2]);
            else stand=wp[1]+AgentPathfinder.surfaceHeight("air");
        }catch(RuntimeException ignored){}
        Location here=player.getLocation();
        return new Location(world,wp[0]+.5,stand,wp[2]+.5,here.getYaw(),here.getPitch());
    }
    static void driveWalk(Player player,Location to,boolean sprint,int move){
        driveWalk(player,to,sprint,move,to==null?Double.POSITIVE_INFINITY:to.getY()+1);
    }
    static void driveWalk(Player player,Location to,boolean sprint,int move,double climbCapY){
        if(player==null||to==null)return;
        Location here=player.getLocation();
        double speed=sprint?SPRINT_SPEED:WALK_SPEED;
        double scale=jumpForwardScale(here.getY(),to.getY());
        double[] v=AgentBoats.velocity(here.getX(),here.getZ(),to.getX(),to.getZ(),speed*scale);
        float yaw=(float)v[2];
        try{player.setRotation(yaw,here.getPitch());}catch(RuntimeException ignored){}
        try{player.setSprinting(sprint);}catch(RuntimeException ignored){}
        double vy=0;
        try{vy=player.getVelocity().getY();}catch(RuntimeException ignored){}
        vy=climbVelocity(here.getY(),to.getY(),climbCapY,move,vy);
        Water w=water(player);
        if(w.feet){
            boolean up=updateSurfacing(player,w);
            vy=swimVy(w.feet,w.head,up,here.getY(),to.getY(),vy);
        }
        try{player.setVelocity(new Vector(v[0],vy,v[1]));}catch(RuntimeException ignored){}
        // Jumping via setVelocity does not reset vanilla fallDistance the way a
        // player jump does; clear it or repeated climb attempts accumulate and
        // apply as phantom FALL when navigation stops.
        if(vy>0)clearFall(player);
    }
    /**
     * True when dest is more than one block above the current stand cell.
     * Vanilla cannot mount a 2-block face; ladders (CLIMB) are the exception.
     * Uses the stand cell, not mid-air Y, so a skipped stair does not hop +2.
     */
    static boolean tooHighToJump(double hereY,double destY,int move){
        if(move==AgentPathfinder.CLIMB||move==AgentPathfinder.STOOP)return false;
        if(destY-hereY>MAX_JUMP_UP)return true;
        // Stand cell uses the same slop as waypoint arrival so landing at 11.96
        // can still jump to 13, but mid-air 11.5 toward 13 cannot.
        // fb-39/40: feet on a snow layer / carpet / bottom slab sit above the
        // integer cell (88.125 on 1-layer snow). Measure from the real feet height,
        // not the cell, or a snow-to-snow +1 step reads as a 1.125 hop and stalls.
        double base=Math.max(standCellY(hereY),hereY);
        return destY-base>1.0001;
    }
    /**
     * Integer stand cell for a 1-block face check. Short-block floors
     * (farmland 0.9375, dirt path, soul sand 0.875) occupy the cell below the
     * feet; treating that as the stand cell made a 1-up look like a 2-block hop
     * and stalled long farm→base gotos.
     */
    static double standCellY(double feetY){
        double floor=Math.floor(feetY);
        if(feetY-floor>=SHORT_FLOOR-1e-9)return floor+1;
        return Math.floor(feetY+ON_STEP+1e-3);
    }
    /**
     * One-block steps auto-jump. Scale horizontal until the body is above the
     * face so the walker does not slam a dirt corner before {@code vy} clears ~0.6.
     */
    static double jumpForwardScale(double hereY,double destY){
        double dy=destY-hereY;
        if(dy>JUMP_CLEAR&&dy<=MAX_JUMP_UP)return JUMP_FORWARD;
        return 1;
    }
    /**
     * Vertical drive for one tick. Never applies upward velocity at or above the
     * next waypoint Y, never above {@code climbCapY} (typically target Y + 1),
     * and never toward a dest more than one block above the current stand cell.
     * Existing upward velocity is clamped to the remaining room under the cap so
     * a leftover jump/knockback cannot balloon past the path.
     */
    static double climbVelocity(double hereY,double waypointY,double climbCapY,int move,double currentVy){
        double dy=waypointY-hereY;
        // Drive a descent before applying the ascent cap. Citizens can otherwise idle with vy=0 above a ledge.
        if(dy < -0.15)return Math.min(currentVy,-0.2);
        if(tooHighToJump(hereY,waypointY,move))return Math.min(currentVy,0);
        double cap=Math.min(waypointY,climbCapY);
        if(hereY>=cap)return Math.min(currentVy,0);
        double room=Math.max(0,cap-hereY);
        double maxUp=Math.min(0.42,room);
        if(move==AgentPathfinder.JUMP||dy>0.4){
            if(currentVy<0.2)return maxUp;
            return Math.min(currentVy,maxUp);
        }
        if(move==AgentPathfinder.CLIMB&&dy>0.1)return Math.min(Math.max(currentVy,0.2),maxUp);
        return currentVy>0?Math.min(currentVy,maxUp):currentVy;
    }
    static double maxPathY(List<int[]> path,double targetY){
        double max=targetY;
        if(path!=null)for(int[] wp:path)if(wp!=null&&wp.length>1)max=Math.max(max,wp[1]);
        return max;
    }
    static boolean climbedPastPath(double bodyY,List<int[]> path,double targetY){
        return climbedPastPath(bodyY,path,targetY,Double.NEGATIVE_INFINITY);
    }
    /** Body still at/near the start height is a descent, not a ballooned climb. */
    static boolean climbedPastPath(double bodyY,List<int[]> path,double targetY,double startY){
        if(bodyY<=startY+CLIMB_PAST_SLACK+1e-9)return false;
        return bodyY>maxPathY(path,targetY)+CLIMB_PAST_SLACK;
    }
    static String climbedPastReason(double bodyY,double maxY){
        return CLIMBED_PAST+": body Y "+Math.round(bodyY*10)/10.0+" exceeds max path Y "+Math.round(maxY*10)/10.0+" + "+(int)CLIMB_PAST_SLACK;
    }
    private static boolean abortIfClimbedPast(NPC npc,Trip trip,Player player,Location current){
        if(trip==null||current==null||trip.boat)return false;
        if(!climbedPastPath(current.getY(),trip.path,trip.target.getY(),trip.start==null?Double.NEGATIVE_INFINITY:trip.start.getY()))return false;
        trip.status="failed";
        trip.reason=climbedPastReason(current.getY(),maxPathY(trip.path,trip.target.getY()));
        try{npc.getNavigator().cancelNavigation();}catch(RuntimeException ignored){}
        haltBody(player);
        trySnapDown(player);
        return true;
    }
    /** Best-effort same-column snap after a balloon abort so the body does not freeze mid-air. */
    static boolean trySnapDown(Player player){
        if(player==null)return false;
        Location here;
        try{here=player.getLocation();}catch(RuntimeException ignored){return false;}
        if(here==null||here.getWorld()==null)return false;
        AgentSafeSpawn.Probe probe;
        try{probe=worldProbe(here.getWorld());}catch(RuntimeException ignored){return false;}
        int bx=AgentSafeSpawn.blockCoord(here.getX()),by=AgentSafeSpawn.blockCoord(here.getY()),bz=AgentSafeSpawn.blockCoord(here.getZ());
        int[] below=AgentSafeSpawn.standableBelow(bx,by,bz,probe);
        if(below==null||below[0]!=bx||below[2]!=bz||below[1]>=by)return false;
        Location dest=new Location(here.getWorld(),below[0]+.5,below[1],below[2]+.5,here.getYaw(),here.getPitch());
        try{player.teleport(dest);player.setVelocity(new Vector(0,0,0));}catch(RuntimeException failed){return false;}
        return true;
    }
    /**
     * fb-43: out of an enclosed 1-wide hole 1-2 blocks deep. One up is a jump
     * vanilla cannot always land from a cramped column; two up cannot be jumped
     * at all. Hop onto the lip toward the target (same teleport class as STOOP,
     * at most 1 block sideways and 2 up). Returns true when the body moved.
     */
    static boolean escapeHole(Player player,Location target){
        if(player==null||target==null)return false;
        Location here;
        try{here=player.getLocation();}catch(RuntimeException ignored){return false;}
        if(here.getWorld()==null)return false;
        AgentPathfinder.Grid grid=AgentPathfinder.tryWorld(here.getWorld());
        if(grid==null)return false;
        int bx=AgentSafeSpawn.blockCoord(here.getX()),by=AgentSafeSpawn.blockCoord(here.getY()),bz=AgentSafeSpawn.blockCoord(here.getZ());
        int[] exit;
        try{exit=AgentPathfinder.holeExit(grid,bx,by,bz,AgentSafeSpawn.blockCoord(target.getX()),AgentSafeSpawn.blockCoord(target.getZ()));}
        catch(RuntimeException ignored){return false;}
        if(exit==null)return false;
        Location dest=new Location(here.getWorld(),exit[0]+.5,grid.standY(exit[0],exit[1],exit[2]),exit[2]+.5,here.getYaw(),here.getPitch());
        try{player.teleport(dest);player.setVelocity(new Vector(0,0,0));clearFall(player);}
        catch(RuntimeException failed){return false;}
        return true;
    }
    /**
     * Step into a doorway when the stoop was cratered (spawn-protect cannot place
     * the missing dirt). Same teleport class as the airborne column snap; only for
     * a STOOP waypoint within {@link AgentPathfinder#STOOP_H} x {@link AgentPathfinder#STOOP_UP}.
     */
    static boolean tryStoop(Player player,Location dest){
        if(player==null||dest==null||dest.getWorld()==null)return false;
        Location here;
        try{here=player.getLocation();}catch(RuntimeException ignored){return false;}
        if(here.getWorld()==null||!here.getWorld().equals(dest.getWorld()))return false;
        double horiz=Math.hypot(dest.getX()-here.getX(),dest.getZ()-here.getZ());
        double dy=dest.getY()-here.getY();
        if(horiz>AgentPathfinder.STOOP_H+0.6||dy<-AgentPathfinder.STOOP_DOWN-0.6||dy>AgentPathfinder.STOOP_UP+0.6)return false;
        try{player.teleport(dest);player.setVelocity(new Vector(0,0,0));clearFall(player);}
        catch(RuntimeException failed){return false;}
        return true;
    }
    /**
     * Offline stand-in for a live NPC walking a planned path. Same reach/speed
     * rules as {@link #driveWalk}; used to assert a 100-block path actually moves.
     */
    static double simulateTravel(List<int[]> path,double x,double y,double z,int ticks){
        return simulateTravel(path,x,y,z,ticks,null);
    }
    static double simulateTravel(List<int[]> path,double x,double y,double z,int ticks,AgentPathfinder.Grid grid){
        if(path==null||path.size()<2)return 0;
        int idx=1;double travelled=0;
        for(int t=0;t<ticks&&idx<path.size();t++){
            int[] wp=path.get(idx);
            double stand=grid==null?wp[1]:grid.standY(wp[0],wp[1],wp[2]);
            if(reachedWaypoint(x,y,z,wp,stand)){idx++;if(idx>=path.size())break;continue;}
            boolean sprint=AgentPathfinder.longLeg(path,idx-1);
            double speed=sprint?SPRINT_SPEED:WALK_SPEED;
            double tx=wp[0]+.5,tz=wp[2]+.5;
            double dx=tx-x,dz=tz-z,h=Math.hypot(dx,dz);
            if(h>1e-9){
                double scale=jumpForwardScale(y,stand);
                double step=Math.min(speed*scale,h);
                x+=dx/h*step;z+=dz/h*step;travelled+=step;
            }
            if(stand>y+0.4){
                double cap=stand;
                y=Math.min(cap,y+climbVelocity(y,stand,cap,wp.length>3?wp[3]:AgentPathfinder.WALK,0.42));
                if(y>stand)y=stand;
            }
            else if(stand<y-0.05)y=Math.max(stand,y-0.2);
            else y=stand;
        }
        return travelled;
    }
    /** Last world each body was seen in (fb-37). */
    private static final Map<UUID,String> lastWorld=new ConcurrentHashMap<>();
    /** Last world-change record per body, reported in observe.navigation. */
    private static final Map<UUID,Map<String,Object>> worldChanges=new ConcurrentHashMap<>();
    /**
     * fb-37: notice a portal / tp / respawn into another world. A goto target from
     * the old world is stale: drop it (and any parked trip) so nothing walks toward
     * old-world coordinates in the new dimension. Returns true when the world changed.
     */
    static boolean noteWorld(NPC npc,World world){
        if(npc==null||world==null)return false;
        String now=world.getName();String before=lastWorld.put(npc.getUniqueId(),now);
        if(before==null||before.equals(now))return false;
        Map<String,Object> change=new LinkedHashMap<>();
        change.put("from",before);change.put("to",now);change.put("at",System.currentTimeMillis());
        Trip t=trips.get(npc.getUniqueId());
        if(t!=null&&inProgress(t.status)){
            change.put("clearedTarget",AgentPresentation.point(t.target));
            t.status="failed";t.reason=worldChangedReason(before,now);
            try{npc.getNavigator().cancelNavigation();}catch(RuntimeException ignored){}
            try{if(npc.getEntity() instanceof Player p)haltBody(p);}catch(RuntimeException ignored){}
        }
        if(parked.remove(npc.getUniqueId())!=null)change.put("droppedParked",true);
        collectionTargets.remove(npc.getUniqueId());
        worldChanges.put(npc.getUniqueId(),change);
        return true;
    }
    static String worldChangedReason(String from,String to){
        return "world_changed: body moved from "+from+" to "+to+"; stale target cleared, send a new goto in "+to;
    }
    static Map<String,Object> lastWorldChange(NPC npc){return npc==null?null:worldChanges.get(npc.getUniqueId());}
    static void tick(NPC npc){
        if(AgentsMC.bodyUp(npc)&&npc.getEntity()!=null)noteWorld(npc,npc.getEntity().getWorld());
        Trip trip=trips.get(npc.getUniqueId());if(trip==null||(!inProgress(trip.status)&&!collectionTargets.containsKey(npc.getUniqueId())))return;
        if(!AgentsMC.bodyUp(npc)||!(npc.getEntity() instanceof Player)){trip.status="failed";trip.reason="Body despawned";return;}
        AgentSleep.stand(npc,(Player)npc.getEntity());
        Location current=npc.getEntity().getLocation();long now=System.currentTimeMillis();
        AgentsMC.rememberLocation(npc);
        if(trip.boat){tickBoat(npc,trip,(Player)npc.getEntity(),current,now);return;}
        if(trip.gridPath){tickPath(npc,trip,(Player)npc.getEntity(),current,now);return;}
        clearFall((Player)npc.getEntity());
        if("stuck".equals(trip.status)){
            if(arrival(current,trip.target)!=null){trip.status="arrived_near";trip.reason="";return;}
            String did=AgentBehaviors.unstick(npc,(Player)npc.getEntity(),trip.target);trip.unstuck++;
            if(did==null||"trapped".equals(did)||did.startsWith("trapped")){
                trip.status="failed";
                trip.reason=did!=null&&(did.equals("trapped")||did.startsWith("trapped"))?AgentBehaviors.TRAPPED
                        :"Stuck: cannot dig a breakable block with an owned tool, cannot step/jump to an adjacent standable cell, and cannot pillar without sealing the agent in"+(trip.reason.isEmpty()?"":" ("+trip.reason+")");
                return;
            }
            trip.lastUnstick=did;trip.status="navigating";trip.lastProgressAt=now;trip.last=current.clone();
            org.bukkit.Bukkit.getScheduler().runTaskLater(AgentsMC.INSTANCE,()->{if(trips.get(npc.getUniqueId())==trip&&"navigating".equals(trip.status)&&AgentsMC.bodyUp(npc)){applyMargins(npc.getNavigator());npc.getNavigator().setTarget(trip.target);applyMargins(npc.getNavigator());attachCallback(npc,trip);}},did.equals("pillar")?12:2);
            return;
        }
        UUID collectId=collectionTargets.get(npc.getUniqueId());
        if(collectId!=null){
            Entity target=current.getWorld().getEntity(collectId);
            if(!(target instanceof Item item)||!item.isValid()){collectionTargets.remove(npc.getUniqueId());npc.getNavigator().cancelNavigation();trip.status="arrived";trip.reason="Drop is no longer present";return;}
            if(current.distanceSquared(item.getLocation())<=2.25){
                int before=item.getItemStack().getAmount();int picked=AgentsMC.collectNearby((Player)npc.getEntity(),1.5);
                if(item.isValid()&&item.getItemStack().getAmount()>=before){collectionTargets.remove(npc.getUniqueId());npc.getNavigator().cancelNavigation();trip.status="failed";trip.reason="Inventory is full; item remains on the ground";return;}
                if(item.isValid()){collectionTargets.remove(npc.getUniqueId());trip.status="failed";trip.reason="Inventory is full; part of the drop remains on the ground";return;}
                Item next=current.getWorld().getNearbyEntities(current,16,8,16).stream().filter(Item.class::isInstance).map(Item.class::cast).filter(Item::isValid).min(Comparator.comparingDouble(drop->current.distanceSquared(drop.getLocation()))).orElse(null);
                if(next==null){collectionTargets.remove(npc.getUniqueId());npc.getNavigator().cancelNavigation();trip.status="arrived";trip.reason="Collected nearby drops";return;}
                collectionTargets.put(npc.getUniqueId(),next.getUniqueId());trip.target=next.getLocation();trip.last= current.clone();trip.lastProgressAt=now;trip.status="navigating";npc.getNavigator().setTarget(next.getLocation());
            }
        }
        if(!current.getWorld().equals(trip.target.getWorld())){npc.getNavigator().cancelNavigation();trip.status="failed";trip.reason=worldChangedReason(trip.target.getWorld()==null?"?":trip.target.getWorld().getName(),current.getWorld().getName());return;}
        if(abortIfClimbedPast(npc,trip,(Player)npc.getEntity(),current))return;
        if(fellIntoProtectedPit(npc,trip,current))return;
        recordDisplacement(trip,current,now);
        double net=current.distance(trip.start);
        if(spinningInPlace(trip.travelled,net)){
            failMotion(npc,trip,"stuck: no net progress (travelled "+Math.round(trip.travelled*10)/10.0+", net "+Math.round(net*10)/10.0+")");
            return;
        }
        double remaining=current.distanceSquared(trip.target);String done=arrival(current,trip.target);
        if("arrived".equals(done)){npc.getNavigator().cancelNavigation();trip.status="arrived";trip.reason="";}
        else if(done!=null&&(!npc.getNavigator().isNavigating()||now-trip.lastProgressAt>1500)){npc.getNavigator().cancelNavigation();trip.status="arrived_near";trip.reason="Stopped within "+TOLERANCE+" blocks of target";}
        else if(trip.finishScheduled){/* Citizens is finishing stopNavigating; the deferred hop runs next tick */}
        else if(!npc.getNavigator().isNavigating()){
            double remain=Math.sqrt(remaining);
            if(nearEnough(current,trip.target,trip.travelled)){npc.getNavigator().cancelNavigation();trip.status="arrived_near";trip.reason="Stopped within "+Math.round(remain*10)/10.0+" blocks after travelling "+Math.round(trip.travelled*10)/10.0;return;}
            if(trip.travelled>=1&&tryFinish(npc,trip,current))return;
            if(now-trip.lastProgressAt<2000&&trip.travelled>0.5)return;
            if(trip.dig&&trip.unstuck<MAX_UNSTICK&&now-trip.lastProgressAt>2000){trip.status="stuck";if(trip.reason.isEmpty())trip.reason="Navigator stopped before arrival";}
            else {trip.status="failed";if(trip.reason.isEmpty())trip.reason=npc.getNavigator().isPaused()?"Navigator paused":"Navigator stopped before arrival";}
        }
        else if(now-trip.lastProgressAt>STUCK_MS){npc.getNavigator().cancelNavigation();trip.status="failed";trip.reason=AgentPathfinder.TIMEOUT;}
        if(trip.status.equals("failed"))collectionTargets.remove(npc.getUniqueId());
    }
    static void stop(NPC npc){
        collectionTargets.remove(npc.getUniqueId());
        try{npc.getNavigator().cancelNavigation();}catch(RuntimeException ignored){}
        Trip t=trips.get(npc.getUniqueId());if(t!=null){t.status="stopped";t.reason="Explicit stop";}
        try{
            if(npc.getEntity() instanceof Player p){
                if(AgentBoats.ridingBoat(p))AgentBoats.halt(p.getVehicle());
                haltBody(p);
            }
        }catch(RuntimeException ignored){}
    }
    private static void tickBoat(NPC npc,Trip trip,Player player,Location current,long now){
        Entity boat=player.getVehicle();
        if(!AgentBoats.isBoatEntity(boat)){trip.status="failed";trip.reason="Not riding a boat";return;}
        if(!current.getWorld().equals(trip.target.getWorld())){AgentBoats.halt(boat);trip.status="failed";trip.reason=worldChangedReason(trip.target.getWorld()==null?"?":trip.target.getWorld().getName(),current.getWorld().getName());return;}
        boolean waterHere=AgentBoats.waterAt(boat.getLocation());
        boolean waterAhead=AgentBoats.waterAhead(boat.getLocation(),trip.target);
        double remain=AgentBoats.horizontal(boat.getX(),boat.getZ(),trip.target.getX(),trip.target.getZ());
        String done=AgentBoats.settle(remain,waterHere,waterAhead);
        double delta=current.distance(trip.last);trip.travelled+=delta;trip.last=current.clone();if(delta>.015){trip.lastProgressAt=now;trip.status="moving";}
        if(done==null){
            AgentBoats.steer(boat,player,trip.target);
            if(now-trip.lastProgressAt>8000){AgentBoats.halt(boat);trip.status="failed";trip.reason="No measured boat movement for 8 seconds";}
            return;
        }
        AgentBoats.halt(boat);
        trip.status=done;
        if("blocked_shore".equals(done))trip.reason="Shore blocks the remaining water route";
        else trip.reason="arrived_near".equals(done)?"Stopped within "+Math.round(remain*10)/10.0+" blocks of target":"";
        if(AgentBoats.shouldDismount(done,waterHere)){
            try{player.leaveVehicle();}catch(RuntimeException ignored){}
        }
    }
    private static void tickPath(NPC npc,Trip trip,Player player,Location current,long now){
        clearFall(player);
        if(now-trip.requestedAt>TRIP_LIMIT_MS){failMotion(npc,trip,AgentPathfinder.TIMEOUT);return;}
        if(!current.getWorld().equals(trip.target.getWorld())){failMotion(npc,trip,worldChangedReason(trip.target.getWorld()==null?"?":trip.target.getWorld().getName(),current.getWorld().getName()));return;}
        if(abortIfClimbedPast(npc,trip,player,current))return;
        if(fellIntoProtectedPit(npc,trip,current))return;
        recordDisplacement(trip,current,now);
        double net=current.distance(trip.start);
        if(spinningInPlace(trip.travelled,net)){
            failMotion(npc,trip,"stuck: no net progress (travelled "+Math.round(trip.travelled*10)/10.0+", net "+Math.round(net*10)/10.0+")");
            return;
        }
        String done=arrival(current,trip.target);
        if("arrived".equals(done)){npc.getNavigator().cancelNavigation();trip.status="arrived";trip.reason="";return;}
        if(done!=null&&(now-trip.lastProgressAt>800||!npc.getNavigator().isNavigating())){npc.getNavigator().cancelNavigation();trip.status="arrived_near";trip.reason="Stopped within "+TOLERANCE+" blocks of target";return;}
        if(nearEnough(current,trip.target,trip.travelled)&&trip.path!=null&&trip.pathIndex>=trip.path.size()){trip.status="arrived_near";trip.reason="";return;}
        if(trip.path!=null&&trip.pathIndex<trip.path.size()){
            int[] wp=trip.path.get(trip.pathIndex);
            double stand=wp[1];
            try{AgentPathfinder.Grid grid=AgentPathfinder.tryWorld(player.getWorld());if(grid!=null)stand=grid.standY(wp[0],wp[1],wp[2]);}catch(RuntimeException ignored){}
            if(reachedWaypoint(current.getX(),current.getY(),current.getZ(),wp,stand)){
                trip.pathIndex++;trip.lastProgressAt=now;
                if(trip.pathIndex>=trip.path.size()){
                    if(trip.segment){
                        if(!continuePath(npc,trip,player))return;
                        if(trip.gridPath)return;
                    }
                    if(nearEnough(current,trip.target,trip.travelled)||arrival(current,trip.target)!=null){trip.status=arrival(current,trip.target)==null?"arrived_near":arrival(current,trip.target);trip.reason="";return;}
                    if(!continuePath(npc,trip,player))return;
                    return;
                }
            }
            followWaypoint(npc,trip,player);
        }
        if(trip.dig && trip.path!=null && trip.pathIndex<trip.path.size()
                && trip.path.get(trip.pathIndex).length>3 && trip.path.get(trip.pathIndex)[3]==AgentPathfinder.DIG
                && trip.driveAt>0 && now-trip.lastProgressAt>=DIG_PROGRESS_MS){
            int px=AgentSafeSpawn.blockCoord(current.getX()),py=AgentSafeSpawn.blockCoord(current.getY()),pz=AgentSafeSpawn.blockCoord(current.getZ());
            failMotion(npc,trip,"stuck: dig no tunnel progress in 5s at "+px+","+py+","+pz
                    +(trip.lastUnstick.isEmpty()?"":" ("+trip.lastUnstick+")"));
            return;
        }
        if(stuckDue(now,trip.lastProgressAt,trip.driveAt,STALL_MS)){
            if(stallArrive(current,trip.target)){
                trip.status="arrived_near";
                trip.reason="Stopped within "+Math.round(current.distance(trip.target)*10)/10.0+" blocks of target";
                haltBody(player);
                return;
            }
            int px=AgentSafeSpawn.blockCoord(current.getX()),py=AgentSafeSpawn.blockCoord(current.getY()),pz=AgentSafeSpawn.blockCoord(current.getZ());
            if(escapeHole(player,trip.target)){trip.lastUnstick="climbed out of hole";trip.lastProgressAt=now;trip.driveAt=now;trip.stallAt=null;replanFrom(npc,trip,player,px,py,pz);return;}
            if(!noteStall(trip,px,py,pz)){haltBody(player);return;}
            try{String did=AgentBehaviors.unstick(npc,player,trip.target,trip.target.getY()+1,trip.dig);if(did!=null)trip.lastUnstick=did;}catch(RuntimeException ignored){}
            if(!replanFrom(npc,trip,player,px,py,pz)&&"failed".equals(trip.status)){haltBody(player);return;}
        }
    }
    /** Path-complete replan is capped; otherwise a stalled climb replans every tick (~301/60s). */
    static boolean continuePath(NPC npc,Trip trip,Player player){
        if(trip.replans>=MAX_REPLAN){
            failMotion(npc,trip,AgentPathfinder.TIMEOUT);
            return false;
        }
        boolean ok=adoptPath(npc,trip,player,true);
        if(!ok&&"failed".equals(trip.status))haltBody(player);
        return ok;
    }
    static void failMotion(NPC npc,Trip trip,String reason){
        trip.status="failed";
        trip.reason=reason==null||reason.isEmpty()?AgentPathfinder.TIMEOUT:reason;
        try{npc.getNavigator().cancelNavigation();}catch(RuntimeException ignored){}
        try{if(npc.getEntity() instanceof Player p)haltBody(p);}catch(RuntimeException ignored){}
    }
    static void clearFall(Player player){
        if(player==null)return;
        try{player.setFallDistance(0f);}catch(RuntimeException ignored){}
    }
    static void haltBody(Player player){
        if(player==null)return;
        try{player.setVelocity(new Vector(0,0,0));}catch(RuntimeException ignored){}
        clearFall(player);
        try{
            UUID id=player.getUniqueId();
            if(id!=null)fallQuiet.put(id,System.currentTimeMillis()+FALL_QUIET_MS);
        }catch(RuntimeException ignored){}
    }
    /** True while a goto is driving or for a beat after it stops, so jump-attempts do not apply FALL. */
    static boolean suppressFall(NPC npc){
        if(npc==null)return false;
        Trip t=trips.get(npc.getUniqueId());
        if(t!=null&&(inProgress(t.status)||held(t.status)))return true;
        Long until=fallQuiet.get(npc.getUniqueId());
        return until!=null&&System.currentTimeMillis()<until;
    }
    static boolean suppressFall(boolean navigating,long now,long quietUntil){
        return navigating||quietUntil>0&&now<quietUntil;
    }
    /** Same-level short hops may use Citizens straight-line. A 1-block step-up must use the grid. */
    static boolean shortHop(double distSq,double dy){
        return distSq<SHORT*SHORT&&Math.abs(dy)<STEP_UP;
    }

    /**
     * Count committed displacement only. Oscillation around {@code stallAt} is not
     * travel and must not set status {@code moving}.
     */
    static void recordDisplacement(Trip trip,Location current,long now){
        if(trip.stallAt==null)trip.stallAt=current.clone();
        double shift=current.distance(trip.stallAt);
        trip.last=current.clone();
        if(shift>=STALL_NET){
            trip.travelled+=shift;
            trip.stallAt=current.clone();
            trip.lastProgressAt=now;
            trip.status="moving";
            return;
        }
        if(now-trip.lastProgressAt>=1_000&&inProgress(trip.status)&&!"stuck".equals(trip.status))
            trip.status="navigating";
    }

    /** First stall at a cell replans with a penalty. Repeat stall blacklists and fails. */
    static boolean noteStall(Trip trip,int px,int py,int pz){
        long cell=AgentPathfinder.pack(px,py,pz);
        if(cell==trip.lastStallCell)trip.stallRepeats++;
        else{trip.lastStallCell=cell;trip.stallRepeats=1;}
        trip.penalized.add(cell);
        if(trip.stallRepeats>=2)trip.banned.add(cell);
        if(trip.stallRepeats>=2){
            trip.status="failed";
            trip.reason="stuck: stalled at "+px+","+py+","+pz+" (no net move for "+(STALL_MS/1000)+"s)";
            return false;
        }
        return true;
    }
    /** Pause an in-progress goto without cancelling it. Self-defense uses reason {@code self_defense}. */
    static boolean pause(NPC npc,String reason){
        Trip t=trips.get(npc.getUniqueId());
        if(t==null)return false;
        if(held(t.status)){
            if(reason!=null&&!reason.isBlank())t.reason=reason;
            return true;
        }
        if(!inProgress(t.status))return false;
        t.heldStatus=t.status;
        t.status="paused";
        t.reason=reason==null||reason.isBlank()?"self_defense":reason;
        t.pausedAt=System.currentTimeMillis();
        try{npc.getNavigator().cancelNavigation();}catch(RuntimeException ignored){}
        try{if(npc.getEntity() instanceof Player p)haltBody(p);}catch(RuntimeException ignored){}
        return true;
    }
    /** Resume a paused goto from the current cell, keeping travelled/path. */
    static boolean resume(NPC npc){
        Trip t=trips.get(npc.getUniqueId());
        if(t==null||!held(t.status))return false;
        long now=System.currentTimeMillis();
        if(t.pausedAt>0){t.requestedAt+=now-t.pausedAt;t.pausedAt=0;}
        String next=t.heldStatus==null||t.heldStatus.isEmpty()?"navigating":t.heldStatus;
        t.status=inProgress(next)?next:"navigating";
        t.heldStatus="";
        t.reason="";
        t.lastProgressAt=now;
        t.driveAt=0;
        if(!AgentsMC.bodyUp(npc)||!(npc.getEntity() instanceof Player player))return true;
        if(t.gridPath)followWaypoint(npc,t,player);
        else if(!t.boat){
            Navigator nav=npc.getNavigator();
            try{nav.setPaused(false);}catch(RuntimeException ignored){}
            applyMargins(nav);
            try{nav.setTarget(t.target);}catch(RuntimeException ignored){}
            applyMargins(nav);
            attachCallback(npc,t);
        }
        return true;
    }
    static boolean paused(NPC npc){
        Trip t=trips.get(npc.getUniqueId());
        return t!=null&&held(t.status);
    }
    /** End an in-progress or paused goto so observe.navigation.active is false (player fight / follow taking over). */
    static void release(NPC npc){
        collectionTargets.remove(npc.getUniqueId());
        Trip t=trips.get(npc.getUniqueId());
        if(t!=null&&(inProgress(t.status)||held(t.status))){t.status="stopped";t.reason="Superseded";}
        if(!AgentCombat.active(npc))try{npc.getNavigator().cancelNavigation();}catch(RuntimeException ignored){}
        try{if(npc.getEntity() instanceof Player p)haltBody(p);}catch(RuntimeException ignored){}
    }
    /** Destination of an in-progress goto/collect, or null. */
    static Location currentTarget(NPC npc){Trip t=trips.get(npc.getUniqueId());return t!=null&&inProgress(t.status)?t.target.clone():null;}
    static Map<String,Object> snapshot(NPC npc){Trip t=trips.get(npc.getUniqueId());Map<String,Object> r=new LinkedHashMap<>();
        boolean live=t!=null&&inProgress(t.status);
        r.put("active",live&&AgentsMC.bodyUp(npc)&&(t!=null&&(t.boat||t.gridPath)||npc.getNavigator().isNavigating()));
        r.put("paused",t!=null&&held(t.status)||npc.getNavigator().isPaused());
        if(t!=null){if(!t.boat)verifyArrival(npc,t);r.put("requestId",t.id);r.put("status",t.status);r.put("reason",t.reason);r.put("target",AgentPresentation.point(t.target));r.put("requestedAt",t.requestedAt);r.put("lastProgressAt",t.lastProgressAt);r.put("travelled",t.travelled);try{if(AgentsMC.bodyUp(npc)&&npc.getEntity()!=null&&t.target!=null)r.put("remaining",Math.round(npc.getEntity().getLocation().distance(t.target)*10)/10.0);}catch(RuntimeException ignored){}r.put("collecting",collectionTargets.containsKey(npc.getUniqueId()));if(t.target!=null&&t.target.getWorld()!=null)r.put("targetWorld",t.target.getWorld().getName());if(t.boat)r.put("mode","boat");if(t.gridPath){r.put("mode","path");r.put("replans",t.replans);if(t.path!=null)r.put("waypoints",t.path.size());}if(t.dig){r.put("unstuck",t.unstuck);if(!t.lastUnstick.isEmpty())r.put("lastUnstick",t.lastUnstick);}}else r.put("status","idle");
        try{if(AgentsMC.bodyUp(npc)&&npc.getEntity()!=null){World w=npc.getEntity().getWorld();noteWorld(npc,w);r.put("world",w.getName());r.put("environment",w.getEnvironment().name());}}catch(RuntimeException ignored){}
        Map<String,Object> moved=lastWorldChange(npc);if(moved!=null)r.put("worldChange",moved);
        if(surfacing(npc.getUniqueId()))r.put(SURFACING,true);
        Map<String,Object> air=peekAirRecovery(npc.getUniqueId());
        if(air!=null)r.putAll(air);
        return r;}
    static Map<String,Object> peekAirRecovery(UUID id){return id==null?null:airRecoveries.get(id);}
    static Map<String,Object> consumeAirRecovery(UUID id){return id==null?null:airRecoveries.remove(id);}
    static void attachAirRecovery(Map<String,Object> result,UUID id){
        Map<String,Object> air=consumeAirRecovery(id);
        if(result!=null&&air!=null)result.putAll(air);
    }
    /**
     * Same-column snap-down when the body is airborne with no in-progress path.
     * Never searches sideways, never skips through solid into a cave, and never
     * fires on jump/knockback or during combat. Returns a result map when a snap
     * happened, else null.
     */
    static Map<String,Object> recoverFromAir(NPC npc,Player player){
        if(npc==null||player==null)return null;
        UUID id=npc.getUniqueId();
        try{if(AgentBoats.ridingBoat(player)){clearAirborne(id);return null;}}catch(RuntimeException ignored){}
        Trip t=trips.get(id);
        if(t!=null&&(inProgress(t.status)||held(t.status))){clearAirborne(id);return null;}
        boolean combat=false;
        try{combat=AgentBehaviors.busy(npc);}catch(RuntimeException ignored){}
        Location here;
        try{here=player.getLocation();}catch(RuntimeException ignored){return null;}
        if(here==null||here.getWorld()==null)return null;
        boolean onGround=false;double vy=0;
        try{onGround=player.isOnGround();}catch(RuntimeException ignored){}
        try{vy=player.getVelocity().getY();}catch(RuntimeException ignored){}
        AgentSafeSpawn.Probe probe;
        try{probe=worldProbe(here.getWorld());}catch(RuntimeException ignored){return null;}
        int bx=AgentSafeSpawn.blockCoord(here.getX()),by=AgentSafeSpawn.blockCoord(here.getY()),bz=AgentSafeSpawn.blockCoord(here.getZ());
        // 0.7.4: floating on or swimming in water is not airborne. Water cells are "open"
        // to the probe, so the old check snapped swimmers to the seabed and they drowned.
        boolean wet=false;
        try{
            World world=here.getWorld();
            wet=player.isInWater()||waterColumn(AgentBoats.isWaterBlock(world.getBlockAt(bx,by,bz)),
                    AgentBoats.isWaterBlock(world.getBlockAt(bx,by-1,bz)),AgentBoats.isWaterBlock(world.getBlockAt(bx,by-2,bz)));
        }catch(RuntimeException ignored){}
        if(wet){clearAirborne(id);return null;}
        boolean openHere=true,supported=false,groundClose=false;
        try{
            openHere=probe.open(bx,by,bz);
            supported=AgentSafeSpawn.supported(bx,by,bz,probe);
            groundClose=AgentSafeSpawn.groundWithin(here.getY(),bx,bz,probe);
        }catch(RuntimeException ignored){return null;}
        long now=System.currentTimeMillis();
        long since=airborneSince.getOrDefault(id,0L);
        long airMs=since<=0?0:now-since;
        if(!mayRecoverFromAir(onGround,combat,!openHere,supported,groundClose,vy,airMs)){
            if(onGround||combat||!openHere||supported||groundClose||vy>0.1)clearAirborne(id);
            else if(since<=0)airborneSince.put(id,now);
            return null;
        }
        int[] below=AgentSafeSpawn.standableBelow(bx,by,bz,probe);
        if(below==null||below[0]!=bx||below[2]!=bz||below[1]>=by){clearAirborne(id);return null;}
        Location dest=new Location(here.getWorld(),below[0]+.5,below[1],below[2]+.5,here.getYaw(),here.getPitch());
        double fall=0;
        try{fall=player.getFallDistance();}catch(RuntimeException ignored){}
        if(!airSnapAllowed(here.getY(),dest.getY(),fall)){clearAirborne(id);return null;}
        try{player.teleport(dest);player.setVelocity(new Vector(0,0,0));}catch(RuntimeException failed){return null;}
        clearAirborne(id);
        Map<String,Object> rec=new LinkedHashMap<>();
        rec.put(RECOVERED_AIR,true);
        rec.put("reason","same_column_air_snap");
        rec.put("from",AgentPresentation.point(here));
        rec.put("to",AgentPresentation.point(dest));
        rec.put("fromY",here.getY());
        rec.put("toY",dest.getY());
        airRecoveries.put(id,rec);
        try{
            AgentEvents.emit(AgentEvents.agentId(npc),"nav.recovered_from_air",
                    AgentEvents.data("x",below[0]+.5,"fromY",here.getY(),"toY",dest.getY(),"z",below[2]+.5));
        }catch(RuntimeException ignored){}
        return rec;
    }
    /** Feet, the cell below, or two below is water: the body is swimming/floating, never an air snap. */
    static boolean waterColumn(boolean feet,boolean below,boolean below2){return feet||below||below2;}
    /**
     * Hysteresis for breath: start surfacing at or below {@link #LOW_AIR} of max air,
     * keep surfacing until air is back to {@link #RESUME_AIR}, then the trip may dive again.
     */
    static boolean needsAir(int air,int maxAir,boolean alreadySurfacing){
        if(maxAir<=0)return false;
        if(alreadySurfacing)return air<maxAir*RESUME_AIR;
        return air<=maxAir*LOW_AIR;
    }
    /**
     * Vertical swim velocity. Surfacing for air always rises while the head is wet.
     * Otherwise the body floats up to the surface unless the waypoint is deeper
     * (a dive the path needs), in which case the walker's descent is kept.
     */
    static double swimVy(boolean feetInWater,boolean headInWater,boolean surfacingForAir,double hereY,double waypointY,double walkVy){
        if(!feetInWater)return walkVy;
        if(surfacingForAir)return headInWater?Math.max(walkVy,SWIM_UP):Math.max(walkVy,0);
        if(waypointY<hereY-.5)return walkVy;
        if(headInWater)return Math.max(walkVy,waypointY>hereY+.4?SWIM_UP:FLOAT_UP);
        return walkVy;
    }
    record Water(boolean feet,boolean head){}
    static Water water(Player player){
        boolean feet=false,head=false;
        try{
            Location l=player.getLocation();
            feet=player.isInWater()||AgentBoats.isWaterBlock(l.getBlock());
            head=AgentBoats.isWaterBlock(player.getEyeLocation().getBlock());
        }catch(RuntimeException ignored){}
        return new Water(feet||head,head);
    }
    static boolean surfacing(UUID id){return id!=null&&surfacing.contains(id);}
    static boolean updateSurfacing(Player player,Water w){
        UUID id=player.getUniqueId();
        boolean was=surfacing.contains(id);
        int air=0,max=0;
        try{air=player.getRemainingAir();max=player.getMaximumAir();}catch(RuntimeException ignored){return was;}
        boolean now=w.feet&&needsAir(air,max,was);
        if(now&&!was){
            surfacing.add(id);
            try{AgentEvents.emit(AgentEvents.agentId(net.citizensnpcs.api.CitizensAPI.getNPCRegistry().getNPC(player)),"nav.surfacing_for_air",AgentEvents.data("air",air,"maxAir",max,"y",player.getLocation().getY()));}catch(RuntimeException ignored){}
        }else if(!now&&was)surfacing.remove(id);
        return now;
    }
    /**
     * Every 10 ticks: an idle body in water floats to the surface (no sinking to the
     * seabed), and any body low on air swims up to breathe, trip or not.
     */
    static void swimTick(NPC npc,Player player){
        if(npc==null||player==null)return;
        Water w=water(player);
        if(!w.feet){surfacing.remove(player.getUniqueId());return;}
        try{if(AgentBoats.ridingBoat(player))return;}catch(RuntimeException ignored){}
        boolean up=updateSurfacing(player,w);
        Trip t=trips.get(npc.getUniqueId());
        boolean moving=t!=null&&inProgress(t.status)&&t.gridPath;
        if(moving&&!up)return; // driveWalk owns vy on a trip
        if(!w.head&&!up)return;
        try{
            Vector v=player.getVelocity();
            double vy=swimVy(true,w.head,up,player.getLocation().getY(),player.getLocation().getY()+1,v.getY());
            player.setVelocity(new Vector(v.getX(),vy,v.getZ()));
        }catch(RuntimeException ignored){}
    }
    static void clearAirborne(UUID id){if(id!=null)airborneSince.remove(id);}
    /**
     * Jump, knockback, combat, standing, or being inside solid is not an air recovery.
     * {@code airborneMs} must reach {@link #AIR_RECOVER_MS}.
     */
    static boolean mayRecoverFromAir(boolean onGround,boolean combat,boolean inSolid,boolean supported,double vy,long airborneMs){
        return mayRecoverFromAir(onGround,combat,inSolid,supported,false,vy,airborneMs);
    }
    static boolean mayRecoverFromAir(boolean onGround,boolean combat,boolean inSolid,boolean supported,boolean groundClose,double vy,long airborneMs){
        if(onGround||combat||inSolid||supported||groundClose)return false;
        if(vy>0.1)return false;
        return airborneMs>=AIR_RECOVER_MS;
    }
    /** A short drop may snap; a mid-fall deeper than {@link #AIR_SNAP_DROP} must land itself. Hover (no fall) may snap farther. */
    static boolean airSnapAllowed(double fromY,double toY,double fallDistance){
        if(fromY-toY<=AIR_SNAP_DROP+1e-9)return true;
        return fallDistance<=1e-9;
    }
    static boolean nearEnough(double remain,double travelled){return remain<=NEAR&&travelled>=0;}
    static boolean nearEnough(double remain,double dy,double travelled){
        if(dy<-STEP_UP)return false;
        if(Math.abs(dy)>WAYPOINT_V)return false;
        return nearEnough(remain,travelled);
    }
    static boolean nearEnough(Location here,Location target,double travelled){
        if(here==null||target==null)return false;
        return nearEnough(here.distance(target),here.getY()-target.getY(),travelled);
    }
    /** Close enough that a 3s stall should finish as arrived_near, not failed. */
    static boolean stallArrive(double remain,double dy){
        if(dy<-STEP_UP||Math.abs(dy)>WAYPOINT_V)return false;
        return remain<=STALL_ARRIVE+1e-9;
    }
    static boolean stallArrive(Location here,Location target){
        if(here==null||target==null)return false;
        return stallArrive(here.distance(target),here.getY()-target.getY());
    }
    /** Jitter/replan loop: path-integral travelled without leaving the start cell. */
    static boolean spinningInPlace(double travelled,double net){return travelled>=SPIN_TRAVEL&&net<SPIN_NET;}
    /** Arrival is only reported while the live body is still within CLOSE of the target at a matching Y. */
    private static void verifyArrival(NPC npc,Trip t){
        if(!success(t.status))return;
        if(!AgentsMC.bodyUp(npc)||npc.getEntity()==null){t.status="failed";t.reason="Arrival was not verified; body is not spawned";return;}
        Location here=npc.getEntity().getLocation();
        if(here.getWorld()==null||t.target.getWorld()==null||!here.getWorld().equals(t.target.getWorld())){t.status="failed";t.reason="Arrival was not verified; body is in another world";return;}
        double dist=here.distance(t.target);
        String now=arrival(here,t.target);
        if(now!=null){t.status=now;return;}
        double dy=here.getY()-t.target.getY();
        if(stallArrive(dist,dy)){t.status="arrived_near";return;}
        if(t.gridPath&&climbedPastPath(here.getY(),t.path,t.target.getY(),t.start==null?Double.NEGATIVE_INFINITY:t.start.getY())){
            t.status="failed";t.reason=climbedPastReason(here.getY(),maxPathY(t.path,t.target.getY()));
            return;
        }
        t.status="failed";t.reason="Arrival was not verified; body is "+Math.round(dist*10)/10.0+" blocks from target";
    }
    static void applyMargins(Navigator nav){
        nav.getDefaultParameters().range(128).pathfinderType(PathfinderType.MINECRAFT).distanceMargin(MARGIN).pathDistanceMargin(MARGIN).speedModifier(1f).stuckAction(null);
        nav.getLocalParameters().distanceMargin(MARGIN).pathDistanceMargin(MARGIN).speedModifier(1f).stuckAction(null);
    }
    /** Keep a buried dig:true target; do not snap it up onto the mine floor. */
    static Location resolveDigTarget(Location requested){
        if(requested==null)return requested;
        World world=requested.getWorld();
        if(world==null)return requested.clone();
        try{
            AgentPathfinder.Grid grid=AgentPathfinder.tryWorld(world);
            int gx=AgentSafeSpawn.blockCoord(requested.getX()),gy=AgentSafeSpawn.blockCoord(requested.getY()),gz=AgentSafeSpawn.blockCoord(requested.getZ());
            if(grid!=null&&AgentPathfinder.canDigStand(grid,gx,gy,gz))return requested.clone();
        }catch(RuntimeException ignored){}
        return resolveTarget(requested);
    }
    /** Snap a head/air-height request down to the nearest standable feet Y. x/z stay as given. */
    static Location resolveTarget(Location requested){
        World world=requested.getWorld();
        if(world==null)return requested.clone();
        try{
            boolean noBuild=AgentProtection.blocked(world,AgentSafeSpawn.blockCoord(requested.getX()),AgentSafeSpawn.blockCoord(requested.getZ()));
            return resolveTarget(requested,worldProbe(world),noBuild);
        }catch(RuntimeException ignored){return requested.clone();}
    }
    static Location resolveTarget(Location requested,AgentSafeSpawn.Probe probe){
        return resolveTarget(requested,probe,false);
    }
    static Location resolveTarget(Location requested,AgentSafeSpawn.Probe probe,boolean noBuild){
        Location out=requested.clone();
        out.setY(AgentSafeSpawn.standableY(requested.getX(),requested.getY(),requested.getZ(),probe));
        if(!noBuild)return out;
        int x=AgentSafeSpawn.blockCoord(out.getX()),y=AgentSafeSpawn.blockCoord(out.getY()),z=AgentSafeSpawn.blockCoord(out.getZ());
        if(!AgentSafeSpawn.trapped(x,y,z,AgentSafeSpawn.WALK,probe))return out;
        int[] rim=AgentSafeSpawn.findWalkOut(requested.getX(),requested.getY(),requested.getZ(),6,probe);
        if(rim==null)return out;
        out.setX(rim[0]+.5);out.setY(rim[1]);out.setZ(rim[2]+.5);
        return out;
    }
    static final String TARGET_PIT="trapped in protected area: goto target is a pit with no walk-out and building is prohibited";
    /**
     * Only a truly enclosed pit is refused. An open downhill of 2+ blocks is allowed
     * even when the destination cannot climb back (wide terrace / slope).
     */
    static String refuseProtectedPitReason(boolean destTrapped){
        return destTrapped?TARGET_PIT:null;
    }
    static void refuseProtectedPit(Location from,Location target){
        try{
            if(target.getWorld()==null||!AgentProtection.blocked(target))return;
            AgentSafeSpawn.Probe probe=worldProbe(target.getWorld());
            int x=AgentSafeSpawn.blockCoord(target.getX()),y=AgentSafeSpawn.blockCoord(target.getY()),z=AgentSafeSpawn.blockCoord(target.getZ());
            String err=refuseProtectedPitReason(AgentSafeSpawn.trapped(x,y,z,AgentSafeSpawn.WALK,probe));
            if(err!=null)throw new IllegalStateException(err);
        }catch(IllegalStateException e){throw e;}
        catch(RuntimeException ignored){}
    }
    private static boolean fellIntoProtectedPit(NPC npc,Trip trip,Location current){
        try{
            if(!AgentProtection.blocked(current))return false;
            if(current.getY()>trip.start.getY()-1.9)return false;
            AgentSafeSpawn.Probe probe=worldProbe(current.getWorld());
            int x=AgentSafeSpawn.blockCoord(current.getX()),y=AgentSafeSpawn.blockCoord(current.getY()),z=AgentSafeSpawn.blockCoord(current.getZ());
            if(!AgentSafeSpawn.trapped(x,y,z,AgentSafeSpawn.WALK,probe))return false;
            String did=AgentBehaviors.unstick(npc,(Player)npc.getEntity(),trip.target);
            if(did!=null&&!"trapped".equals(did)&&!did.startsWith("trapped")){
                trip.lastUnstick=did;trip.status="navigating";trip.lastProgressAt=System.currentTimeMillis();
                return false;
            }
            npc.getNavigator().cancelNavigation();
            trip.status="failed";trip.reason=AgentBehaviors.TRAPPED;
            return true;
        }catch(RuntimeException ignored){return false;}
    }
    static AgentSafeSpawn.Probe worldProbe(World world){
        return new AgentSafeSpawn.Probe(){
            public boolean open(int x,int y,int z){Block b=world.getBlockAt(x,y,z);return AgentPathfinder.cellOpen(b.getType().name(),b.isLiquid(),b.isPassable(),b.getType().isCollidable(),AgentPathfinder.thinPassable(b));}
            public boolean floor(int x,int y,int z){Block b=world.getBlockAt(x,y,z);return AgentPathfinder.cellFloor(b.getType().name(),b.isLiquid(),b.isPassable(),b.getType().isSolid(),AgentPathfinder.thinPassable(b),AgentPathfinder.surfaceOf(b));}
            public boolean usable(int x,int y,int z){return y-1>=world.getMinHeight()&&y+1<world.getMaxHeight()&&world.isChunkLoaded(x>>4,z>>4)&&world.getWorldBorder().isInside(new Location(world,x+.5,y,z+.5));}
        };
    }
    static boolean clearApproach(Location from,Location to){
        if(from.getWorld()==null||to.getWorld()==null||!from.getWorld().equals(to.getWorld()))return false;
        if(Math.abs(from.getY()-to.getY())>1.01)return false;
        double horiz=Math.hypot(from.getX()-to.getX(),from.getZ()-to.getZ());
        if(horiz>CLOSE)return false;
        try{
            AgentSafeSpawn.Probe probe=worldProbe(from.getWorld());
            int steps=Math.max(1,(int)Math.ceil(horiz*2));
            for(int i=1;i<=steps;i++){
                double t=i/(double)steps;
                int x=AgentSafeSpawn.blockCoord(from.getX()+(to.getX()-from.getX())*t);
                int y=AgentSafeSpawn.blockCoord(from.getY()+(to.getY()-from.getY())*t);
                int z=AgentSafeSpawn.blockCoord(from.getZ()+(to.getZ()-from.getZ())*t);
                if(!probe.open(x,y,z)||!probe.open(x,y+1,z))return false;
            }
            return true;
        }catch(RuntimeException ignored){return true;}
    }
    private static void attachCallback(NPC npc,Trip trip){
        npc.getNavigator().getLocalParameters().addSingleUseCallback(reason->onNavigatorDone(npc,reason));
    }
    /**
     * Citizens fires this for both a clean finish ({@code reason == null}) and a cancel.
     * The finish walk is deferred one tick so {@code stopNavigating(null)} cannot immediately
     * tear down a hop started inside the callback.
     */
    static void onNavigatorDone(NPC npc,CancelReason reason){
        if(reason==CancelReason.REPLACE)return;
        Trip trip=trips.get(npc.getUniqueId());
        if(trip==null||!inProgress(trip.status)||trip.finishScheduled)return;
        if(!AgentsMC.bodyUp(npc)||!(npc.getEntity() instanceof Player player)){trip.status="failed";trip.reason="Navigator completed but body is not spawned";return;}
        if(trip.gridPath){
            Location here=player.getLocation();
            String done=arrival(here,trip.target);
            if(done!=null){trip.status=done;trip.reason="";return;}
            if(nearEnough(here,trip.target,trip.travelled)){trip.status="arrived_near";trip.reason="";return;}
            // Citizens completed or cancelled a leftover hop. Keep driving the same
            // waypoint; do not replan (that reset the body before it ever walked).
            return;
        }
        Location here=player.getLocation();
        Location dest=trip.target;
        String done=arrival(here,dest);
        if(done!=null){trip.status=done;trip.reason="";return;}
        double remain=here.distance(dest);
        if(nearEnough(here,dest,trip.travelled)){trip.status="arrived_near";trip.reason="Stopped within "+Math.round(remain*10)/10.0+" blocks after travelling "+Math.round(trip.travelled*10)/10.0;return;}
        if(canFinish(here,dest)){
            trip.finishScheduled=true;
            later(()->{
                if(trips.get(npc.getUniqueId())!=trip||!inProgress(trip.status))return;
                if(!AgentsMC.bodyUp(npc)||!(npc.getEntity() instanceof Player p)){trip.status="failed";trip.reason="Body despawned";return;}
                Location now=p.getLocation();
                String arrived=arrival(now,trip.target);
                if(arrived!=null){trip.status=arrived;trip.reason="";return;}
                if(nearEnough(now,trip.target,trip.travelled)){trip.status="arrived_near";trip.reason="";return;}
                if(tryFinish(npc,trip,now))return;
                if(stallArrive(now,trip.target)){
                    trip.status="arrived_near";
                    trip.reason="Stopped within "+Math.round(now.distance(trip.target)*10)/10.0+" blocks of target";
                    return;
                }
                trip.status="failed";
                trip.reason=reason==null?"Navigator completed outside arrival tolerance":"Navigator stopped before arrival";
            });
            return;
        }
        if(stallArrive(here,dest)){
            trip.status="arrived_near";
            trip.reason="Stopped within "+Math.round(remain*10)/10.0+" blocks of target";
            return;
        }
        if(reason!=null&&trip.dig&&trip.unstuck<MAX_UNSTICK){trip.status="stuck";trip.reason=reason.name();return;}
        trip.status="failed";
        trip.reason=reason==null?"Navigator completed outside arrival tolerance":reason.name();
    }
    static boolean canFinish(Location here,Location dest){
        try{return here.distance(dest)>TOLERANCE&&here.distance(dest)<=CLOSE&&clearApproach(here,dest);}
        catch(RuntimeException e){return false;}
    }
    /** One walking close-in when the pathfinder stops within CLOSE of a clear standable target. Never teleports. */
    static boolean tryFinish(NPC npc,Trip trip,Location here){
        if(trip.finishing)return false;
        Location dest=trip.target;
        if(!canFinish(here,dest))return false;
        trip.finishing=true;
        trip.finishScheduled=true;
        trip.status="navigating";
        trip.reason="";
        trip.lastUnstick="finish";
        trip.lastProgressAt=System.currentTimeMillis();
        Navigator nav=npc.getNavigator();
        applyMargins(nav);
        try{nav.setStraightLineTarget(dest);}catch(RuntimeException ignored){}
        if(!nav.isNavigating())return false;
        applyMargins(nav);
        attachCallback(npc,trip);
        return true;
    }
    static void later(Runnable task){
        try{
            if(AgentsMC.INSTANCE!=null){org.bukkit.Bukkit.getScheduler().runTask(AgentsMC.INSTANCE,task);return;}
        }catch(Throwable ignored){}
        task.run();
    }
    private AgentNavigation(){}
}

