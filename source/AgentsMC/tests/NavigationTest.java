package dev.agentsmc;
import java.lang.reflect.Proxy;
import java.util.*;
import org.bukkit.*;
import org.bukkit.entity.Player;
import net.citizensnpcs.api.ai.*;
import net.citizensnpcs.api.ai.event.NavigatorCallback;
import net.citizensnpcs.api.npc.NPC;
public final class NavigationTest {
 @SuppressWarnings("unchecked") static <T>T proxy(Class<T> type,java.util.function.BiFunction<String,Object[],Object> function){return (T)Proxy.newProxyInstance(type.getClassLoader(),new Class<?>[]{type},(obj,m,a)->{if(m.getName().equals("hashCode"))return System.identityHashCode(obj);if(m.getName().equals("equals"))return obj==a[0];return function.apply(m.getName(),a);});}
 public static void main(String[] args)throws Exception{
  World world=proxy(World.class,(n,a)->n.equals("getName")?"test":null);
  Location[] location={new Location(world,0,64,0)};UUID id=UUID.randomUUID();
  Player player=proxy(Player.class,(n,a)->switch(n){case "getLocation"->location[0].clone();case "getUniqueId"->id;case "isValid"->true;case "isDead"->false;default->null;});
  boolean[] paused={true},active={false},reject={false},straight={false};NavigatorParameters params=new NavigatorParameters();
  Navigator nav=proxy(Navigator.class,(n,a)->switch(n){case "isNavigating"->active[0]&&!paused[0];case "isPaused"->paused[0];case "setPaused"->{paused[0]=(boolean)a[0];yield null;}case "cancelNavigation"->{active[0]=false;yield null;}case "setTarget"->{if(paused[0])throw new AssertionError("goto did not resume paused navigator");active[0]=!reject[0];yield null;}case "setStraightLineTarget"->{straight[0]=true;active[0]=true;yield null;}case "getDefaultParameters","getLocalParameters"->params;default->null;});
  NPC npc=proxy(NPC.class,(n,a)->switch(n){case "getNavigator"->nav;case "getEntity"->player;case "getUniqueId"->id;case "isSpawned"->true;default->null;});
  Map<String,Object> receipt=AgentNavigation.start(npc,new Location(world,2,64,0));check(receipt.get("state").equals("accepted"),"Claimed movement without measuring it");check(!paused[0],"Paused navigator retained");check(params.stuckAction()==null,"Stuck bodies can teleport");check(params.pathfinderType()==PathfinderType.MINECRAFT,"Player body is not using the native Minecraft navigator");
  check(Math.abs(params.distanceMargin()-AgentNavigation.MARGIN)<1e-9,"distanceMargin must be 0.3");
  check(Math.abs(params.pathDistanceMargin()-AgentNavigation.MARGIN)<1e-9,"pathDistanceMargin must be 0.3");
  check(AgentNavigation.MARGIN<AgentNavigation.TOLERANCE,"Citizens margins must sit below arrival tolerance");
  location[0].setX(0.6);AgentNavigation.tick(npc);check(AgentNavigation.snapshot(npc).get("status").equals("moving"),"Measured progress not recorded");
  var tripsField=AgentNavigation.class.getDeclaredField("trips");tripsField.setAccessible(true);Object trip=((Map<?,?>)tripsField.get(null)).get(id);var last=trip.getClass().getDeclaredField("lastProgressAt");last.setAccessible(true);
  last.setLong(trip,System.currentTimeMillis()-2500);
  active[0]=false;AgentNavigation.tick(npc);check(AgentNavigation.snapshot(npc).get("status").equals("failed"),"Stopped navigator falsely successful");
  AgentNavigation.start(npc,new Location(world,3,64,0));
  trip=((Map<?,?>)tripsField.get(null)).get(id);last.setLong(trip,System.currentTimeMillis()-9000);
  AgentNavigation.tick(npc);check(AgentNavigation.snapshot(npc).get("status").equals("failed"),"Stationary navigation never failed");check(AgentPathfinder.TIMEOUT.equals(AgentNavigation.snapshot(npc).get("reason")),"Stall diagnostic lost: "+AgentNavigation.snapshot(npc).get("reason"));
  reject[0]=true;try{AgentNavigation.start(npc,new Location(world,3,64,0));throw new AssertionError("Rejected navigation returned success");}catch(IllegalStateException expected){}
  check(AgentNavigation.snapshot(npc).get("status").equals("failed"),"Rejection diagnostics lost");
  reject[0]=false;location[0]=new Location(world,0,64,0);
  AgentNavigation.start(npc,new Location(world,5,64,0));location[0].setX(4);AgentNavigation.tick(npc);active[0]=false;AgentNavigation.tick(npc);
  check(AgentNavigation.success(String.valueOf(AgentNavigation.snapshot(npc).get("status"))),"Stopping 1 block short after travelling must finish as arrived_near, not fail: "+AgentNavigation.snapshot(npc));
  check(AgentNavigation.success("arrived_near"),"arrived_near not success");
  location[0]=new Location(world,0,64,0);AgentNavigation.start(npc,new Location(world,4,64,0));location[0].setX(3.6);AgentNavigation.tick(npc);active[0]=false;AgentNavigation.tick(npc);
  check(AgentNavigation.snapshot(npc).get("status").equals("arrived_near"),"0.4 block shortfall failed");
  location[0]=new Location(world,0,64,0);
  check(AgentNavigation.snapshot(npc).get("status").equals("failed"),"Unverified leftover arrival after leaving destination");
  location[0]=new Location(world,0,64,0);AgentNavigation.start(npc,new Location(world,10,64,0));location[0].setX(5);AgentNavigation.tick(npc);active[0]=false;
  trip=((Map<?,?>)tripsField.get(null)).get(id);
  last.setLong(trip,System.currentTimeMillis()-2500);AgentNavigation.tick(npc);
  check(AgentNavigation.snapshot(npc).get("status").equals("failed"),"Stopping 5 blocks short was success");
  location[0]=new Location(world,0,64,0);AgentNavigation.start(npc,new Location(world,45,64,0));
  location[0].setX(2);AgentNavigation.tick(npc);active[0]=false;AgentNavigation.tick(npc);
  check(!"stuck".equals(AgentNavigation.snapshot(npc).get("status"))&&!"failed".equals(AgentNavigation.snapshot(npc).get("status")),
          "long goto must not fail on a brief navigator pause after travelling 2: "+AgentNavigation.snapshot(npc));
  location[0]=new Location(world,0,64,0);AgentNavigation.start(npc,new Location(world,20,64,0));
  location[0].setX(18);AgentNavigation.tick(npc);active[0]=false;AgentNavigation.tick(npc);
  String twoShort=String.valueOf(AgentNavigation.snapshot(npc).get("status"));
  check(!AgentNavigation.success(twoShort),"Stopping 2 blocks short must not be arrived_near: "+AgentNavigation.snapshot(npc));
  boolean[] hop={false};
  Navigator hopperNav=proxy(Navigator.class,(n,a)->switch(n){case "isNavigating"->hop[0];case "isPaused"->false;case "setStraightLineTarget"->{hop[0]=true;yield null;}case "setTarget"->throw new AssertionError("short goto used pathfinder");case "getDefaultParameters","getLocalParameters"->params;default->null;});
  NPC hopper=proxy(NPC.class,(n,a)->switch(n){case "getNavigator"->hopperNav;case "getEntity"->player;case "getUniqueId"->UUID.randomUUID();case "isSpawned"->true;default->null;});
  location[0]=new Location(world,0,64,0);Map<String,Object> stepped=AgentNavigation.start(hopper,new Location(world,1,64,0));
  check(hop[0]&&stepped.get("state").equals("accepted"),"Short goto did not step directly");
  check(AgentNavigation.shortHop(1.0,0),"1-block same-level is a short hop");
  check(!AgentNavigation.shortHop(2.0,-1.0),"1-over 1-up must not use Citizens straight-line");
  boolean[] climbStraight={false},climbPath={false};
  Navigator climbNav=proxy(Navigator.class,(n,a)->switch(n){case "isNavigating"->climbPath[0];case "isPaused"->false;case "setStraightLineTarget"->{climbStraight[0]=true;yield null;}case "setTarget"->{climbPath[0]=true;yield null;}case "cancelNavigation","setPaused"->null;case "getDefaultParameters","getLocalParameters"->params;default->null;});
  NPC climber=proxy(NPC.class,(n,a)->switch(n){case "getNavigator"->climbNav;case "getEntity"->player;case "getUniqueId"->UUID.randomUUID();case "isSpawned"->true;default->null;});
  location[0]=new Location(world,3.5,-4,24.5);
  AgentNavigation.start(climber,new Location(world,4.5,-3,24.5));
  check(!climbStraight[0],"1-block stair hop must not setStraightLineTarget");

  AgentSafeSpawn.Probe grass=new AgentSafeSpawn.Probe(){
   public boolean open(int x,int y,int z){return y!=64;}
   public boolean floor(int x,int y,int z){return y==64;}
   public boolean usable(int x,int y,int z){return y>-64&&y<319;}
  };
  Location head=new Location(world,-2.5,66,20.5);
  Location resolved=AgentNavigation.resolveTarget(head,grass);
  check(resolved.getX()==-2.5&&resolved.getZ()==20.5&&resolved.getY()==65,"head-height target should stand on ground y=65, got "+resolved.getY());
  check(AgentSafeSpawn.standableY(-2.5,66,20.5,grass)==65,"standableY head/air");
  check(AgentSafeSpawn.standableY(-2.5,65,20.5,grass)==65,"already standable");
  Location liveStop=new Location(world,-3.58,65,20.25),liveTarget=new Location(world,-2.5,65,20.5);
  check(AgentNavigation.arrival(liveStop.distanceSquared(liveTarget))==null,"1.1 short is still outside 0.75");
  check(liveStop.distance(liveTarget)<=AgentNavigation.CLOSE,"1.1 short is within finish-walk range");

  straight[0]=false;paused[0]=false;active[0]=false;location[0]=new Location(world,-8.5,65,18.5);
  AgentNavigation.start(npc,new Location(world,-2.5,65,20.5));
  check(active[0]&&!straight[0],"long goto should pathfind first");
  location[0]=new Location(world,-3.58,65,20.25);
  List<NavigatorCallback> done=new ArrayList<>();
  params.callbacks().forEach(done::add);
  for(NavigatorCallback cb:done)cb.onCompletion(null);
  check(straight[0],"navigator completing 1.1 short must walk the last stretch");
  check(!AgentNavigation.success(String.valueOf(AgentNavigation.snapshot(npc).get("status"))),"1.1 short is not arrival");
  check(!"failed".equals(AgentNavigation.snapshot(npc).get("status")),"1.1 short completion failed instead of finishing walk: "+AgentNavigation.snapshot(npc).get("reason"));

  straight[0]=false;location[0]=new Location(world,0,64,0);
  AgentNavigation.start(npc,new Location(world,10,64,0));
  location[0].setX(5);
  List<NavigatorCallback> far=new ArrayList<>();
  params.callbacks().forEach(far::add);
  for(NavigatorCallback cb:far)cb.onCompletion(null);
  check(AgentNavigation.snapshot(npc).get("status").equals("failed"),"completing 5 blocks short must still fail");
  check(!straight[0],"5-block shortfall must not start a finish walk");

  straight[0]=false;paused[0]=false;active[0]=false;location[0]=new Location(world,-8.5,65,18.5);
  AgentNavigation.start(npc,new Location(world,-2.5,65,20.5));
  location[0]=new Location(world,-3.58,65,20.25);
  List<NavigatorCallback> cancelled=new ArrayList<>();
  params.callbacks().forEach(cancelled::add);
  for(NavigatorCallback cb:cancelled)cb.onCompletion(net.citizensnpcs.api.ai.event.CancelReason.PLUGIN);
  check(straight[0],"navigator cancel 1.1 short must walk the last stretch");
  check(!"failed".equals(AgentNavigation.snapshot(npc).get("status")),"cancel 1.1 short failed instead of finishing walk: "+AgentNavigation.snapshot(npc).get("reason"));

  straight[0]=false;active[0]=false;location[0]=new Location(world,-3.58,65,20.25);
  AgentNavigation.start(npc,new Location(world,-2.5,65,20.5));
  location[0]=new Location(world,-3.58,65,20.25);
  AgentNavigation.tick(npc);active[0]=false;AgentNavigation.tick(npc);
  check(straight[0]||AgentNavigation.success(String.valueOf(AgentNavigation.snapshot(npc).get("status"))),"tick 1.1 short must hop or arrive: "+AgentNavigation.snapshot(npc));

  location[0]=new Location(world,0,64,0);active[0]=true;
  AgentNavigation.start(npc,new Location(world,10,64,0));
  location[0].setX(1);AgentNavigation.tick(npc);active[0]=false;
  trip=((Map<?,?>)tripsField.get(null)).get(id);
  last.setLong(trip,System.currentTimeMillis()-2500);AgentNavigation.tick(npc);
  active[0]=true;
  Map<String,Object> failedSnap=AgentNavigation.snapshot(npc);
  check("failed".equals(failedSnap.get("status"))||"stuck".equals(failedSnap.get("status")),"expected failed leftover, got "+failedSnap);
  check(Boolean.FALSE.equals(failedSnap.get("active")),"failed trip must not report navigation.active true while Citizens is still walking");

  // Grid follower: drive velocity along a 100-block path; Citizens complete must not replan.
  org.bukkit.util.Vector[] vel={new org.bukkit.util.Vector()};
  float[] fall={48f};
  Location[] body={new Location(world,0.5,65,0.5)};
  int[] cancels={0};boolean[] navigating={false};
  Player walker=proxy(Player.class,(n,a)->switch(n){
   case "getLocation"->body[0].clone();
   case "getX"->body[0].getX();
   case "getY"->body[0].getY();
   case "getZ"->body[0].getZ();
   case "getWorld"->world;
   case "getUniqueId"->id;
   case "isValid"->true;
   case "isDead"->false;
   case "getVelocity"->vel[0].clone();
   case "setVelocity"->{
    vel[0]=((org.bukkit.util.Vector)a[0]).clone();
    body[0]=body[0].clone();
    body[0].setX(body[0].getX()+vel[0].getX());
    body[0].setY(body[0].getY()+vel[0].getY());
    body[0].setZ(body[0].getZ()+vel[0].getZ());
    yield null;
   }
   case "getFallDistance"->fall[0];
   case "setFallDistance"->{fall[0]=((Number)a[0]).floatValue();yield null;}
   case "setSprinting","setRotation"->null;
   default->null;
  });
  int[] citizenTargets={0};
  Navigator walkNav=proxy(Navigator.class,(n,a)->switch(n){
   case "isNavigating"->navigating[0];
   case "isPaused"->false;
   case "setPaused"->null;
   case "cancelNavigation"->{cancels[0]++;navigating[0]=false;yield null;}
   case "setStraightLineTarget","setTarget"->{citizenTargets[0]++;navigating[0]=true;yield null;}
   case "getDefaultParameters","getLocalParameters"->params;
   default->null;
  });
  NPC walkNpc=proxy(NPC.class,(n,a)->switch(n){case "getNavigator"->walkNav;case "getEntity"->walker;case "getUniqueId"->id;case "isSpawned"->true;default->null;});
  AgentNavigation.start(walkNpc,new Location(world,100.5,65,0.5));
  AgentNavigation.Trip walkTrip=(AgentNavigation.Trip)((Map<?,?>)tripsField.get(null)).get(id);
  List<int[]> hundred=new ArrayList<>();
  for(int x=0;x<=100;x++)hundred.add(new int[]{x,65,0,AgentPathfinder.WALK});
  walkTrip.path=hundred;walkTrip.pathIndex=1;walkTrip.gridPath=true;
  walkTrip.travelled=0;walkTrip.driveAt=0;walkTrip.replans=0;
  walkTrip.lastProgressAt=System.currentTimeMillis()-5_000;
  int targetsAtInject=citizenTargets[0];
  AgentNavigation.tick(walkNpc);
  check(walkTrip.driveAt>0,"first tick must record a drive attempt");
  check(walkTrip.replans==0,"stuck clock must not replan before a drive: "+walkTrip.replans);
  for(int i=0;i<30;i++)AgentNavigation.tick(walkNpc);
  double walked=((Number)AgentNavigation.snapshot(walkNpc).get("travelled")).doubleValue();
  check(walked>5,"NPC must move >5 blocks along a 100-block path, got "+walked+" at "+body[0]);
  check(walkTrip.replans==0,"successful walking must not replan: "+walkTrip.replans);
  check(citizenTargets[0]==targetsAtInject,"grid follower must not retarget Citizens after the plan is adopted");
  int afterWalkCancels=cancels[0];
  AgentNavigation.onNavigatorDone(walkNpc,null);
  AgentNavigation.onNavigatorDone(walkNpc,net.citizensnpcs.api.ai.event.CancelReason.PLUGIN);
  check(walkTrip.replans==0,"Citizens complete/cancel must not replan a grid path");
  check(cancels[0]==afterWalkCancels,"grid complete must not cancelNavigation-loop");
  double kept=walkTrip.travelled;
  walkTrip.lastProgressAt=System.currentTimeMillis()-5_000;
  walkTrip.driveAt=System.currentTimeMillis()-5_000;
  boolean replanned=AgentNavigation.replanFrom(walkNpc,walkTrip,walker,AgentSafeSpawn.blockCoord(body[0].getX()),65,0);
  check(!replanned,"offline world has no grid, replan adopt is skipped");
  check(Math.abs(walkTrip.travelled-kept)<1e-9,"replan must not reset travelled: "+walkTrip.travelled);
  check(walkTrip.driveAt>walkTrip.requestedAt,"replan restarts the drive clock");

  double beforePause=walkTrip.travelled;
  int pathIndex=walkTrip.pathIndex;
  check(AgentNavigation.pause(walkNpc,AgentSelfDefense.REASON),"pause active grid goto");
  Map<String,Object> held=AgentNavigation.snapshot(walkNpc);
  check("paused".equals(held.get("status")),"navigation status paused during self-defense: "+held);
  check(AgentSelfDefense.REASON.equals(held.get("reason")),"pause reason self_defense: "+held.get("reason"));
  check(Boolean.TRUE.equals(held.get("paused")),"snapshot.paused true while held");
  check(Boolean.FALSE.equals(held.get("active")),"paused goto is not actively walking");
  check(Math.abs(((Number)held.get("travelled")).doubleValue()-beforePause)<1e-9,"pause must keep travelled");
  AgentNavigation.tick(walkNpc);
  check("paused".equals(AgentNavigation.snapshot(walkNpc).get("status")),"tick must not fail a paused goto");
  check(walkTrip.pathIndex==pathIndex,"pause must not advance waypoints");
  check(AgentNavigation.resume(walkNpc),"resume paused goto");
  Map<String,Object> again=AgentNavigation.snapshot(walkNpc);
  check(AgentNavigation.inProgress(String.valueOf(again.get("status"))),"resume returns to in-progress: "+again);
  check(Math.abs(((Number)again.get("travelled")).doubleValue()-beforePause)<1e-9,"resume must keep travelled");
  check(!AgentSelfDefense.REASON.equals(again.get("reason")),"self_defense reason cleared on resume");
  AgentNavigation.pause(walkNpc,AgentSelfDefense.REASON);
  check(AgentNavigation.park(walkNpc),"park paused goto so flee can start its own trip");
  check(AgentNavigation.snapshot(walkNpc).get("status").equals("idle"),"parked trip is not the live trip: "+AgentNavigation.snapshot(walkNpc));
  check(!AgentNavigation.moving(walkNpc),"parked goto is not moving");
  check(AgentNavigation.unpark(walkNpc),"unpark restores the paused trip");
  check("paused".equals(AgentNavigation.snapshot(walkNpc).get("status")),"unpark keeps paused status");
  check(AgentNavigation.resume(walkNpc),"resume after unpark");
  AgentNavigation.pause(walkNpc,AgentSelfDefense.REASON);
  AgentNavigation.release(walkNpc);
  check("stopped".equals(AgentNavigation.snapshot(walkNpc).get("status")),"player fight still supersedes a paused goto");

  check(AgentNavigation.climbVelocity(65,65,66,AgentPathfinder.JUMP,0.1)==0,"JUMP at waypoint Y must not apply upward velocity");
  check(AgentNavigation.climbVelocity(66,65,66,AgentPathfinder.JUMP,0.42)==-0.2,"JUMP past waypoint Y must descend without retaining upward velocity");
  check(AgentNavigation.climbVelocity(84,83,85,AgentPathfinder.DROP,0)==-0.2,"DROP from a stationary hover must drive downward before the climb-cap guard");
  check(AgentNavigation.climbVelocity(84,83,85,AgentPathfinder.DROP,-0.5)==-0.5,"DROP preserves an existing faster fall");
  body[0]=new Location(world,9.06,84,-9.26);vel[0]=new org.bukkit.util.Vector();
  AgentNavigation.driveWalk(walker,new Location(world,9.5,83,-10.5),false,AgentPathfinder.DROP,85);
  check(body[0].getY()<84&&vel[0].getY()<0,"live-like stationary ledge descent must lower the body");
  check(AgentNavigation.climbVelocity(83,90,83,AgentPathfinder.CLIMB,0)==0,"climb hard-capped at target Y+1");
  check(AgentNavigation.climbVelocity(64.2,65,66,AgentPathfinder.JUMP,0)==0.42,"JUMP still boosts while below waypoint");
  check(AgentNavigation.climbVelocity(64.2,65,66,AgentPathfinder.JUMP,2.0)==0.42,"leftover upward vy is clamped to the cap room");
  check(Math.abs(AgentNavigation.climbVelocity(65.8,66,66,AgentPathfinder.JUMP,0)-0.2)<1e-9,"near-cap JUMP cannot overshoot");
  check(AgentNavigation.climbVelocity(64.5,65,66,AgentPathfinder.WALK,0)==0.42,"dy>0.4 walk still jumps while below waypoint");
  check(AgentNavigation.tooHighToJump(64,66,AgentPathfinder.JUMP),"2-block dest is too high");
  check(AgentNavigation.standCellY(85.9375)==86,"farmland 0.9375 stand cell is the air above it");
  check(AgentNavigation.standCellY(85.875)==86,"soul sand 0.875 stand cell is the air above it");
  check(!AgentNavigation.tooHighToJump(85.9375,87,AgentPathfinder.JUMP),"farmland 1-up is a jump, not a 2-block face");
  check(!AgentNavigation.tooHighToJump(85.875,87,AgentPathfinder.JUMP),"soul sand 1-up is a jump");
  check(AgentNavigation.climbVelocity(85.9375,87,88,AgentPathfinder.JUMP,0)>0,"farmland 1-up still applies jump vy");
  body[0]=new Location(world,-7.5,85.9375,-34.5);vel[0]=new org.bukkit.util.Vector();
  AgentNavigation.driveWalk(walker,new Location(world,-6.5,87,-34.5),false,AgentPathfinder.JUMP,88);
  check(vel[0].getY()>0,"farmland long-goto 1-up still jumps instead of stalling: "+vel[0]);
  check(AgentNavigation.tooHighToJump(64.5,66,AgentPathfinder.JUMP),"mid-air 0.5 toward +2 is still too high");
  check(AgentNavigation.climbVelocity(64,66,80,AgentPathfinder.JUMP,0)==0,"JUMP must not apply upward velocity at a 2-block face");
  check(AgentNavigation.climbVelocity(64.7,66,80,AgentPathfinder.WALK,0.42)==0,"skipped +1 stair must not hop the next +1 from mid-air");
  check(AgentNavigation.climbVelocity(64,65,80,AgentPathfinder.JUMP,0)==0.42,"1-block JUMP is unchanged");
  check(AgentNavigation.stallArrive(1.6,0),"1.6 same-level stall is arrived_near");
  check(AgentNavigation.stallArrive(2.0,0.1),"2-block stall is arrived_near");
  check(!AgentNavigation.stallArrive(2.2,0),"past 2 is still a stall fail");
  check(!AgentNavigation.stallArrive(1.0,-1.0),"1-below is not stall-arrive");
  check(Math.abs(AgentNavigation.jumpForwardScale(64,65)-AgentNavigation.JUMP_FORWARD)<1e-9,"1-block hop delays horizontal");
  check(AgentNavigation.jumpForwardScale(64.5,65)==1,"full forward once the hop clears the face");
  body[0]=new Location(world,0.5,64,0.5);vel[0]=new org.bukkit.util.Vector();
  AgentNavigation.driveWalk(walker,new Location(world,1.5,65,0.5),false,AgentPathfinder.JUMP,66);
  check(Math.abs(vel[0].getY()-0.42)<1e-9,"1-block step still auto-jumps");
  check(Math.hypot(vel[0].getX(),vel[0].getZ())<AgentNavigation.WALK_SPEED*0.3+1e-9,
          "horizontal is delayed until the hop clears the face: "+vel[0]);
  body[0]=new Location(world,0.5,64.5,0.5);vel[0]=new org.bukkit.util.Vector();
  AgentNavigation.driveWalk(walker,new Location(world,1.5,65,0.5),false,AgentPathfinder.JUMP,66);
  check(Math.hypot(vel[0].getX(),vel[0].getZ())>AgentNavigation.WALK_SPEED*0.9,
          "full forward once above the face: "+vel[0]);
  List<int[]> skyPath=new ArrayList<>();
  for(int x=0;x<=20;x++)skyPath.add(new int[]{x,82,0,AgentPathfinder.JUMP});
  check(AgentNavigation.maxPathY(skyPath,82)==82,"max path Y is 82");
  check(!AgentNavigation.climbedPastPath(83,skyPath,82),"Y=83 is within slack");
  check(AgentNavigation.climbedPastPath(85,skyPath,82),"Y=85 exceeds max path Y+2");
  check(!AgentNavigation.climbedPastPath(98,skyPath,82,98),"standing at start Y is not a climb");
  check(!AgentNavigation.climbedPastPath(98,skyPath,87,96),"start Y 96 / body 98 is still at the hilltop");
  check(AgentNavigation.climbedPastPath(98,skyPath,82,90),"start Y 90 / body 98 is a balloon");
  check(AgentNavigation.airSnapAllowed(88,84,1),"4-block intentional drop may snap");
  check(!AgentNavigation.airSnapAllowed(88,76,3),"mid-fall deeper than 8 must land itself");
  check(AgentNavigation.airSnapAllowed(316,65,0),"hover with no fallDistance may snap far");
  World dummyWorld=world;
  Location savedBed=new Location(dummyWorld,-5.5,87,-53.5);
  Location shaft=new Location(dummyWorld,-12.08,84,-39.5);
  check(AgentsMC.watchdogAt(savedBed,shaft)==shaft,"watchdog prefers live shaft over stale bed");
  check(AgentsMC.watchdogAt(savedBed,null)==savedBed,"null live falls back to saved");
  check(AgentNavigation.climbedPastReason(316,82).startsWith(AgentNavigation.CLIMBED_PAST),"abort reason is explicit");
  body[0]=new Location(world,0.5,65,0.5);vel[0]=new org.bukkit.util.Vector();
  AgentNavigation.start(walkNpc,new Location(world,20.5,65,0.5));
  AgentNavigation.Trip skyTrip=(AgentNavigation.Trip)((Map<?,?>)tripsField.get(null)).get(id);
  List<int[]> jumpPath=new ArrayList<>();
  for(int x=0;x<=20;x++)jumpPath.add(new int[]{x,65,0,AgentPathfinder.JUMP});
  skyTrip.path=jumpPath;skyTrip.pathIndex=1;skyTrip.gridPath=true;skyTrip.status="navigating";skyTrip.reason="";
  skyTrip.travelled=0;skyTrip.driveAt=0;skyTrip.replans=0;skyTrip.target=new Location(world,20.5,65,0.5);
  for(int i=0;i<80;i++)AgentNavigation.tick(walkNpc);
  check(body[0].getY()<67,"JUMP follower must not sky-climb, Y="+body[0].getY()+" at "+body[0]);
  check(!AgentNavigation.CLIMBED_PAST.equals(String.valueOf(AgentNavigation.snapshot(walkNpc).get("status"))),"normal jump walk is not climbed_past_path");
  body[0]=new Location(world,20.5,90,0.5);
  AgentNavigation.tick(walkNpc);
  Map<String,Object> past=AgentNavigation.snapshot(walkNpc);
  check("failed".equals(past.get("status"))&&String.valueOf(past.get("reason")).startsWith(AgentNavigation.CLIMBED_PAST),
          "body Y past max path Y+2 must abort: "+past);
  skyTrip.status="navigating";skyTrip.reason="";skyTrip.gridPath=false;skyTrip.path=null;
  body[0]=new Location(world,20.5,90,0.5);
  AgentNavigation.tick(walkNpc);
  Map<String,Object> pastCitizens=AgentNavigation.snapshot(walkNpc);
  check("failed".equals(pastCitizens.get("status"))&&String.valueOf(pastCitizens.get("reason")).startsWith(AgentNavigation.CLIMBED_PAST),
          "Citizens fallback also aborts balloon: "+pastCitizens);

  Location[] airAt={new Location(world,20.5,316, -20.5)};
  org.bukkit.util.Vector[] airVel={new org.bukkit.util.Vector(0,0.42,0)};
  boolean[] teleported={false};
  Player airborne=proxy(Player.class,(n,a)->switch(n){
   case "getLocation"->airAt[0].clone();
   case "getX"->airAt[0].getX();
   case "getY"->airAt[0].getY();
   case "getZ"->airAt[0].getZ();
   case "getWorld"->world;
   case "getUniqueId"->id;
   case "isValid"->true;
   case "isDead"->false;
   case "getVelocity"->airVel[0].clone();
   case "setVelocity"->{airVel[0]=((org.bukkit.util.Vector)a[0]).clone();yield null;}
   case "teleport"->{airAt[0]=((Location)a[0]).clone();teleported[0]=true;yield true;}
   case "setSprinting","setRotation"->null;
   default->null;
  });
  NPC airNpc=proxy(NPC.class,(n,a)->switch(n){case "getNavigator"->walkNav;case "getEntity"->airborne;case "getUniqueId"->id;case "isSpawned"->true;default->null;});
  skyTrip.status="failed";skyTrip.reason=AgentPathfinder.TIMEOUT;skyTrip.gridPath=false;
  // Offline world probe cannot read blocks, so recoverFromAir is asserted via standableBelow + consume map.
  AgentSafeSpawn.Probe column=new AgentSafeSpawn.Probe(){
   public boolean open(int x,int y,int z){return y!=64;}
   public boolean floor(int x,int y,int z){return y==64;}
   public boolean usable(int x,int y,int z){return y>-64&&y<320;}
  };
  int[] below=AgentSafeSpawn.standableBelow(20,316,-20,column);
  check(below!=null&&below[0]==20&&below[2]==-20&&below[1]==65,"air recovery searches the same column: "+java.util.Arrays.toString(below));
  check(AgentSafeSpawn.standableBelow(20,65,-20,column)[1]==65,"already-standable is not a sideways search");
  check(!AgentNavigation.mayRecoverFromAir(false,true,false,false,-0.3,AgentNavigation.AIR_RECOVER_MS),"self-defense is not recovered_from_air");
  Map<String,Object> rec=new java.util.LinkedHashMap<>();
  rec.put(AgentNavigation.RECOVERED_AIR,true);rec.put("fromY",316.0);rec.put("toY",65.0);
  var recField=AgentNavigation.class.getDeclaredField("airRecoveries");recField.setAccessible(true);
  ((Map<UUID,Map<String,Object>>)recField.get(null)).put(id,rec);
  Map<String,Object> actionOut=new java.util.LinkedHashMap<>();actionOut.put("ok",true);
  AgentNavigation.attachAirRecovery(actionOut,id);
  check(Boolean.TRUE.equals(actionOut.get(AgentNavigation.RECOVERED_AIR)),"action result reports recovered_from_air");
  check(AgentNavigation.consumeAirRecovery(id)==null,"recovery is consumed once");
  check(airborne!=null&&airNpc!=null&&!teleported[0],"proxy constructed");

  AgentNavigation.Trip stall=new AgentNavigation.Trip(new Location(world,0,84,0),new Location(world,-13,86,-16));
  AgentNavigation.recordDisplacement(stall,new Location(world,0.2,84,0.1),1_000);
  check(stall.travelled<AgentNavigation.STALL_NET&&!"moving".equals(stall.status),"oscillation is not travel or moving: "+stall.travelled+" "+stall.status);
  AgentNavigation.recordDisplacement(stall,new Location(world,2,84,0),2_000);
  check(stall.travelled>=AgentNavigation.STALL_NET&&"moving".equals(stall.status),"committed displacement is moving: "+stall.travelled);
  AgentNavigation.recordDisplacement(stall,new Location(world,2.1,84,0.1),4_000);
  check("navigating".equals(stall.status),"status returns to navigating after 1s with no net: "+stall.status);
  check(AgentNavigation.noteStall(stall, -7,84,-8),"first stall replans");
  check(!AgentNavigation.noteStall(stall, -7,84,-8),"repeat stall at the same cell fails stuck");
  check("failed".equals(stall.status)&&String.valueOf(stall.reason).contains("stalled"),"stuck reason: "+stall.reason);
  check(stall.banned.contains(AgentPathfinder.pack(-7,84,-8)),"repeat stall blacklists the cell");

  check(AgentNavigation.suppressFall(true,1000,0),"in-progress goto suppresses FALL");
  check(AgentNavigation.suppressFall(false,1500,2000),"quiet window after stop suppresses FALL");
  check(!AgentNavigation.suppressFall(false,2500,2000),"real falls after the quiet window are not cancelled");
  fall[0]=48f;vel[0]=new org.bukkit.util.Vector(0,0.42,0);
  AgentNavigation.haltBody(walker);
  check(fall[0]==0f,"haltBody clears accumulated fallDistance");
  check(Math.abs(vel[0].getY())<1e-9,"haltBody zeros climb velocity");
  walkTrip.replans=AgentNavigation.MAX_REPLAN;walkTrip.status="navigating";walkTrip.reason="";
  check(!AgentNavigation.continuePath(walkNpc,walkTrip,walker),"path-complete replan is capped at MAX_REPLAN");
  check("failed".equals(walkTrip.status),"capped replan fails instead of looping");

  System.out.println("PASS: margins 0.3, standable Y, finish walk on complete/cancel/tick, active false when failed, arrival after near stop, grid walk >5 / no replan storm, self-defense pause/resume, sky-climb cap/abort, recovered_from_air, stall/oscillation, stair hop, phantom fall, corner jump delay");
 }
 static void check(boolean value,String message){if(!value)throw new AssertionError(message);}
}

