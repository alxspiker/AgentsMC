package dev.agentsmc;

import java.util.*;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.Tag;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.block.BlockState;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Item;
import org.bukkit.entity.Player;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;
import org.bukkit.util.Vector;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.citizensnpcs.api.npc.NPC;

/** 0.6.8 player-like helpers: soft-block clearing, goto unstick, tree chopping, background routines. */
final class AgentBehaviors {
    static final String ROUTINE_KEY="agentsmc-routine", BEHAVIOR_KEY="agentsmc-behavior";
    private static final Set<Material> DIGGABLE=EnumSet.noneOf(Material.class);
    static {
        for(String n:new String[]{"DIRT","GRASS_BLOCK","COARSE_DIRT","ROOTED_DIRT","PODZOL","MYCELIUM","DIRT_PATH","FARMLAND","SAND","RED_SAND","GRAVEL","CLAY","SNOW","SNOW_BLOCK","MUD","MUDDY_MANGROVE_ROOTS","SOUL_SAND","SOUL_SOIL","VINE","MOSS_BLOCK","MOSS_CARPET","SHORT_GRASS","TALL_GRASS","FERN","LARGE_FERN","DEAD_BUSH","SWEET_BERRY_BUSH","BAMBOO","SUGAR_CANE","COBWEB"}){
            Material m=Material.matchMaterial(n);if(m!=null)DIGGABLE.add(m);}
    }
    /** Leaves, grass, flowers, saplings, vines and other zero-hardness non-functional blocks. */
    static boolean softObstruction(Block b){
        Material m=b.getType();if(m.isAir()||b.isLiquid())return false;
        if(Tag.LEAVES.isTagged(m)||Tag.SAPLINGS.isTagged(m)||Tag.FLOWERS.isTagged(m))return true;
        if(b.getState() instanceof org.bukkit.block.Container||m.name().endsWith("_BED")||m==Material.TORCH||m==Material.WALL_TORCH)return false;
        return b.isReplaceable()||m==Material.VINE||m==Material.SHORT_GRASS||m==Material.TALL_GRASS||m==Material.FERN;
    }
    /** Soft terrain an agent may dig through while navigating (no ores, stone, wood, or player builds). */
    static boolean diggable(Block b){return naturalTerrain(b);}
    /** Unstick may only break naturally generated soft terrain — never player walls, planks, cobble, or stone. */
    static boolean naturalTerrain(Block b){
        if(b==null||b.isEmpty()||b.isLiquid())return false;
        return softObstruction(b)||DIGGABLE.contains(b.getType());
    }

    private static org.bukkit.util.RayTraceResult ray(Player p,Location to){
        Location origin=p.getEyeLocation();
        Vector dest=to.toVector();
        for(int hop=0;hop<4;hop++){
            Vector d=dest.clone().subtract(origin.toVector());if(d.lengthSquared()<1e-6)return null;
            org.bukkit.util.RayTraceResult hit=p.getWorld().rayTraceBlocks(origin,d,d.length(),org.bukkit.FluidCollisionMode.NEVER,true);
            if(hit==null||hit.getHitBlock()==null)return null;
            if(!AgentReach.occupies(p,hit.getHitBlock()))return hit;
            if(hit.getHitPosition()==null)return hit;
            origin=hit.getHitPosition().toLocation(p.getWorld()).add(d.normalize().multiply(0.06));
        }
        return null;
    }
    /** Any exposed-face ray that hits the target first means unobstructed. Occupied feet/head are skipped. */
    static String obstructedMessage(Block ob){
        if(ob==null)return "Target is obstructed";
        return "Target is obstructed by "+ob.getType().name()+" at "+ob.getX()+","+ob.getY()+","+ob.getZ();
    }
    static Block firstObstruction(Player p,Block b){
        if(!AgentReach.inReach(p.getEyeLocation(),b))return b;
        if(AgentReach.visibleFace(p,b))return null;
        Block first=null;
        for(Location probe:AgentReach.faceProbes(b)){
            org.bukkit.util.RayTraceResult hit=ray(p,probe);
            if(hit==null||b.equals(hit.getHitBlock()))return null;
            if(hit.getHitBlock()!=null&&!AgentReach.occupies(p,hit.getHitBlock())&&first==null)first=hit.getHitBlock();
        }
        return first;
    }
    /** Clears up to 6 soft obstructions (leaves, grass, flowers) between eye and target. Returns cleared names. Throws if still obstructed. */
    static List<String> clearPath(Player p,Block b){
        List<String> cleared=new ArrayList<>();
        for(int i=0;i<6;i++){
            Block ob=firstObstruction(p,b);
            if(ob==null)return cleared;
            if(ob.equals(b))throw new IllegalArgumentException("Target is outside five-block reach ("+
                    Math.round(AgentReach.nearest(p.getEyeLocation(),b)*10)/10.0+" blocks from eyes; max 5.0)");
            if(!softObstruction(ob)||p.getEyeLocation().distanceSquared(ob.getLocation().add(.5,.5,.5))>25)
                throw new IllegalArgumentException(obstructedMessage(ob));
            String name=ob.getType().name();
            if(!AgentProtection.breakBlock(p,ob))throw new IllegalArgumentException("Clearing obstruction "+name+" was rejected");
            AgentHunger.exhaust(p, AgentHunger.BREAK);
            cleared.add(name+"@"+ob.getX()+","+ob.getY()+","+ob.getZ());
        }
        if(firstObstruction(p,b)!=null)throw new IllegalArgumentException("Target still obstructed after clearing soft blocks");
        return cleared;
    }

    // ---------- goto unstick (no teleport) ----------
    private static final Material[] PILLAR={Material.DIRT,Material.COBBLESTONE,Material.COBBLED_DEEPSLATE,Material.NETHERRACK};
    private static final BlockFace[] HORIZONTAL={BlockFace.NORTH,BlockFace.SOUTH,BlockFace.EAST,BlockFace.WEST};
    static final String TRAPPED="trapped in protected area: no jump, step-up, or walk-out within "+AgentSafeSpawn.WALK+" blocks and building is prohibited";
    /** One unstick attempt: build-free moves first; pillar only outside protected areas. */
    static String unstick(NPC npc,Player p,Location target){
        return unstick(npc,p,target,Double.POSITIVE_INFINITY,true);
    }
    /** Pillar is refused at or above {@code maxStandY} so unstick cannot balloon to the build ceiling. */
    static String unstick(NPC npc,Player p,Location target,double maxStandY){
        return unstick(npc,p,target,maxStandY,true);
    }
    /** {@code allowDig} is goto dig:true only. A walk-only stall steps out; it does not mine a house. */
    static String unstick(NPC npc,Player p,Location target,double maxStandY,boolean allowDig){
        Location at=p.getLocation();Block feet=at.getBlock(),head=feet.getRelative(BlockFace.UP),floor=feet.getRelative(BlockFace.DOWN);
        boolean noBuild=AgentProtection.blocked(at);
        double dx=target.getX()-at.getX(),dz=target.getZ()-at.getZ();
        BlockFace towards=Math.abs(dx)>=Math.abs(dz)?(dx>0?BlockFace.EAST:BlockFace.WEST):(dz>0?BlockFace.SOUTH:BlockFace.NORTH);
        List<BlockFace> order=new ArrayList<>();order.add(towards);
        for(BlockFace f:HORIZONTAL)if(f!=towards)order.add(f);
        boolean up=target.getY()>at.getY()+.9;
        if(allowDig&&!noBuild){
            List<String> dug=new ArrayList<>();
            for(BlockFace face:order){
                Block front=feet.getRelative(face),frontHead=front.getRelative(BlockFace.UP);
                Block[] seq=up?new Block[]{frontHead,frontHead.getRelative(BlockFace.UP),head.getRelative(BlockFace.UP),front}:new Block[]{frontHead,front};
                for(Block b:seq){
                    if(b.equals(floor)||b.isPassable())continue;
                    if(b.getType().isSolid()&&b==front&&up&&frontHead.isPassable())continue;
                    if(!canMine(p,b))continue;
                    equipTool(p,b);
                    String n=b.getType().name();
                    if(AgentProtection.blocked(b))continue;
                    if(AgentProtection.breakBlock(p,b)){AgentHunger.exhaust(p, AgentHunger.BREAK);dug.add(n+"@"+face.name());}
                }
                if(!dug.isEmpty()){AgentsMC.collectNearby(p,3);return "dug "+String.join(",",dug);}
            }
        }
        String step=stepOut(npc,p,target,noBuild);if(step!=null)return step;
        String walk=walkOut(npc,p,target);if(walk!=null)return walk;
        if(noBuild)return "trapped";
        Block front=feet.getRelative(towards);
        boolean frontBlocked=!front.isPassable()&&!canMine(p,front);
        if((up||frontBlocked)&&at.getY()<maxStandY-0.05&&head.getRelative(BlockFace.UP).isPassable()&&feet.isPassable()&&floor.getType().isSolid()&&!sealsAgent(p,feet)){
            if(AgentProtection.blocked(feet))return "trapped";
            int slot=-1;for(Material m:PILLAR){slot=p.getInventory().first(m);if(slot>=0)break;}
            if(slot<0)return null;
            final int s=slot;final Block place=feet;
            p.setVelocity(new Vector(0,.42,0));
            Bukkit.getScheduler().runTaskLater(AgentsMC.INSTANCE,()->{
                ItemStack stack=p.getInventory().getItem(s);if(stack==null||stack.getAmount()<1||!place.isPassable())return;
                if(sealsAgent(p,place))return;
                if(org.bukkit.util.BoundingBox.of(place).overlaps(p.getBoundingBox()))return;
                if(AgentProtection.blocked(place))return;
                BlockState prev=place.getState();place.setType(stack.getType(),false);
                try{AgentProtection.requirePlace(p,place,AgentProtection.placeEvent(place,prev,place.getRelative(BlockFace.DOWN),stack,p));}
                catch(IllegalArgumentException rejected){prev.update(true,false);return;}
                stack.setAmount(stack.getAmount()-1);p.getInventory().setItem(s,stack.getAmount()>0?stack:null);
            },6);
            return "pillar";
        }
        return null;
    }
    static AgentSafeSpawn.Probe worldProbe(World w){
        return new AgentSafeSpawn.Probe(){
            public boolean open(int x,int y,int z){Block b=w.getBlockAt(x,y,z);return AgentPathfinder.cellOpen(b.getType().name(),b.isLiquid(),b.isPassable(),b.getType().isCollidable(),AgentPathfinder.thinPassable(b));}
            public boolean floor(int x,int y,int z){Block b=w.getBlockAt(x,y,z);return AgentPathfinder.cellFloor(b.getType().name(),b.isLiquid(),b.isPassable(),b.getType().isSolid(),AgentPathfinder.thinPassable(b),AgentPathfinder.surfaceOf(b));}
            public boolean usable(int x,int y,int z){return y-1>=w.getMinHeight()&&y+1<w.getMaxHeight()&&w.isChunkLoaded(x>>4,z>>4)&&w.getWorldBorder().isInside(new Location(w,x+.5,y,z+.5));}
        };
    }
    /** Adjacent jump-step toward the target. Never teleports. */
    static String stepOut(NPC npc,Player p,Location target){return stepOut(npc,p,target,false);}
    static String stepOut(NPC npc,Player p,Location target,boolean noBuild){
        Location at=p.getLocation();World w=at.getWorld();
        AgentSafeSpawn.Probe probe=worldProbe(w);
        int[] c=AgentSafeSpawn.escape(at.getX(),at.getY(),at.getZ(),target.getX(),target.getZ(),3,probe);
        if(c==null)return null;
        if(!noBuild&&c[1]>at.getY()+0.5 && target.getY()<=at.getY()+0.9){
            Block climbFloor=w.getBlockAt(c[0],c[1]-1,c[2]);
            if(climbFloor.getType().isSolid()&&!naturalTerrain(climbFloor))return null;
        }
        return takeStep(npc,p,c);
    }
    static String walkOut(NPC npc,Player p,Location target){
        Location at=p.getLocation();
        int[] c=AgentSafeSpawn.walkStep(at.getX(),at.getY(),at.getZ(),target.getX(),target.getZ(),AgentSafeSpawn.WALK,worldProbe(at.getWorld()));
        if(c==null)return null;
        return takeStep(npc,p,c);
    }
    /** Vanilla jump is 0.42. Never launch at destY-hereY of 2 (a 2-block face). */
    static double stepJumpVelocity(double hereY,double destY){
        if(destY-hereY>0.15)return 0.42;
        return Math.max(0,destY-hereY);
    }
    private static String takeStep(NPC npc,Player p,int[] c){
        Location at=p.getLocation();World w=at.getWorld();
        Location to=new Location(w,c[0]+.5,c[1],c[2]+.5,at.getYaw(),at.getPitch());
        double jump=stepJumpVelocity(at.getY(),c[1]);
        p.setVelocity(new Vector(c[0]+.5-at.getX(),jump,c[2]+.5-at.getZ()).multiply(0.25).setY(jump));
        try{npc.getNavigator().setStraightLineTarget(to);}catch(RuntimeException ignored){}
        return "step "+c[0]+","+c[1]+","+c[2];
    }
    static boolean canMine(Player p,Block b){
        if(AgentProtection.blocked(b))return false;
        if(!naturalTerrain(b))return false;
        if(b.getType().getHardness()<0)return false;
        if(b.getState() instanceof org.bukkit.block.Container)return false;
        String n=b.getType().name();
        if(n.contains("PORTAL")||n.contains("COMMAND")||n.equals("BARRIER")||n.equals("BEDROCK")||n.contains("SPAWNER"))return false;
        return true;
    }
    /** Planned DIG waypoints may mine stone/ore/dirt. Never workstations, containers, or unbreakables. */
    static boolean canTunnel(Player p,Block b){
        if(p==null||b==null||AgentProtection.blocked(b))return false;
        if(b.isEmpty()||b.isLiquid()||b.isPassable())return false;
        if(b.getState() instanceof org.bukkit.block.Container)return false;
        try{if(b.getType().getHardness()<0)return false;}catch(RuntimeException ignored){}
        String n=b.getType().name();
        if(AgentPathfinder.workstationName(n))return false;
        if(n.contains("PORTAL")||n.contains("COMMAND")||n.equals("BARRIER")||n.equals("BEDROCK")||n.contains("SPAWNER"))return false;
        return AgentPathfinder.diggableName(n);
    }
    static boolean passableCell(Block b){
        return b==null||b.isEmpty()||b.isLiquid()||b.isPassable();
    }
    /** True when the DIG waypoint 1x2 is already air. */
    static boolean waypointOpen(Player p,int[] wp){
        if(p==null||wp==null||p.getWorld()==null)return true;
        World w=p.getWorld();
        return passableCell(w.getBlockAt(wp[0],wp[1],wp[2]))&&passableCell(w.getBlockAt(wp[0],wp[1]+1,wp[2]));
    }
    /** Break the 1x2 (feet+head) of a DIG waypoint, plus the stair column at current Y. Never teleports. */
    static String mineWaypoint(Player p,int[] wp){
        if(p==null||wp==null||p.getWorld()==null)return null;
        World w=p.getWorld();
        List<String> dug=new ArrayList<>();
        List<Block> cells=new ArrayList<>();
        cells.add(w.getBlockAt(wp[0],wp[1]+1,wp[2]));
        cells.add(w.getBlockAt(wp[0],wp[1],wp[2]));
        Location at=p.getLocation();
        int px=at.getBlockX(),py=at.getBlockY(),pz=at.getBlockZ();
        if(wp[0]==px&&wp[2]==pz&&wp[1]<py)cells.add(w.getBlockAt(px,py-1,pz));
        if(wp[0]!=px||wp[2]!=pz){
            cells.add(w.getBlockAt(wp[0],py,wp[2]));
            cells.add(w.getBlockAt(wp[0],py+1,wp[2]));
        }
        for(Block b:cells){
            if(passableCell(b))continue;
            if(!canTunnel(p,b))continue;
            try{if(!AgentReach.inReach(p.getEyeLocation(),b))continue;}catch(RuntimeException ignored){}
            String n=b.getType().name();
            ItemStack hand;try{hand=p.getInventory().getItemInMainHand();}catch(RuntimeException e){hand=null;}
            String held=hand==null||hand.getType().isAir()?"HAND":hand.getType().name();
            if(wouldDestroy(held,n)&&toolSlot(p,"_PICKAXE")<0)continue;
            equipTool(p,b);
            try{hand=p.getInventory().getItemInMainHand();held=hand==null||hand.getType().isAir()?"HAND":hand.getType().name();}
            catch(RuntimeException ignored){}
            if(wouldDestroy(held,n))continue;
            if(AgentProtection.breakBlock(p,b)){
                noteMined(p,b.getType());
                AgentHunger.exhaust(p,AgentHunger.BREAK);
                dug.add(n+"@"+b.getX()+","+b.getY()+","+b.getZ());
            }
        }
        if(dug.isEmpty())return waypointOpen(p,wp)?null:"dig blocked";
        AgentsMC.collectNearby(p,3);
        return "mined "+String.join(",",dug);
    }
    static boolean isTool(ItemStack s){
        if(s==null||s.getType().isAir())return false;
        String n=s.getType().name();
        return n.endsWith("_PICKAXE")||n.endsWith("_AXE")||n.endsWith("_SHOVEL")||n.endsWith("_HOE")||n.endsWith("_SWORD")||n.equals("SHEARS");
    }
    static int toolSlot(Player p,String suffix){
        int best=-1,bestRank=-1;
        String[] ranks={"WOODEN","STONE","COPPER","GOLDEN","IRON","DIAMOND","NETHERITE"};
        for(int i=0;i<36;i++){
            ItemStack it=p.getInventory().getItem(i);if(it==null)continue;
            String n=it.getType().name();
            boolean match="SHEARS".equals(suffix)?n.equals("SHEARS"):n.endsWith(suffix);
            if(!match)continue;
            int rank=0;for(int r=0;r<ranks.length;r++)if(n.startsWith(ranks[r]))rank=r;
            if(rank>=bestRank){bestRank=rank;best=i;}
        }
        return best;
    }
    /** Shears/hoe/sword for leaves; tagged vanilla tool otherwise. Empty-drop blocks are still breakable. */
    static List<String> preferredTools(String typeName, String taggedSuffix) {
        List<String> out = new ArrayList<>();
        String n = typeName == null ? "" : typeName.toUpperCase(java.util.Locale.ROOT);
        if (n.contains("LEAVES") || n.equals("VINE") || n.contains("COBWEB") || n.contains("GLOW_LICHEN")) {
            out.add("SHEARS");
            out.add("_HOE");
            out.add("_SWORD");
        }
        if (taggedSuffix != null && !taggedSuffix.isBlank() && !out.contains(taggedSuffix)) out.add(taggedSuffix);
        return out;
    }
    /** Leaves/snow/plants may be cleared with no drop. Ores may not. */
    static boolean allowEmptyDrops(String typeName) {
        String n = typeName == null ? "" : typeName.toUpperCase(java.util.Locale.ROOT);
        return n.contains("LEAVES") || n.equals("VINE") || n.contains("COBWEB") || n.contains("GLOW_LICHEN")
                || n.contains("SNOW") || n.contains("GRASS") || n.equals("FERN") || n.equals("LARGE_FERN")
                || n.equals("DEAD_BUSH") || n.equals("MOSS_CARPET");
    }
    /** Wooden/gold = 0, stone = 1, copper = 2, iron = 3, diamond = 4, netherite = 5. */
    static int pickaxeRank(String toolName) {
        String n = typeName(toolName);
        if (!n.endsWith("_PICKAXE")) return -1;
        if (n.startsWith("NETHERITE")) return 5;
        if (n.startsWith("DIAMOND")) return 4;
        if (n.startsWith("IRON")) return 3;
        if (n.startsWith("COPPER")) return 2;
        if (n.startsWith("STONE")) return 1;
        if (n.startsWith("WOODEN") || n.startsWith("GOLDEN")) return 0;
        return -1;
    }
    /** Minimum pickaxe rank that drops this block. 0 = any pickaxe, -1 = not an ore. */
    static int requiredPickaxe(String blockName) {
        String n = typeName(blockName);
        if (n.contains("ANCIENT_DEBRIS") || n.contains("NETHERITE") || n.equals("OBSIDIAN") || n.equals("CRYING_OBSIDIAN"))
            return 4;
        if (n.contains("DIAMOND_ORE") || n.contains("EMERALD_ORE") || n.contains("GOLD_ORE") || n.contains("REDSTONE_ORE")
                || n.contains("GILDED"))
            return 3;
        if (n.contains("IRON_ORE") || n.contains("LAPIS") || n.contains("COPPER_ORE")) return 1;
        return -1;
    }
    static boolean toolCanHarvest(String toolName, String blockName) {
        if (allowEmptyDrops(blockName)) return true;
        int need = requiredPickaxe(blockName);
        if (need < 0) return true;
        return pickaxeRank(toolName) >= need;
    }
    static String needToolMessage(String blockName, String haveTool) {
        int need = requiredPickaxe(blockName);
        String want = need >= 4 ? "DIAMOND_PICKAXE" : need >= 3 ? "IRON_PICKAXE" : need >= 1 ? "STONE_PICKAXE" : "PICKAXE";
        String have = haveTool == null || haveTool.isBlank() || haveTool.equals("AIR") ? "HAND" : typeName(haveTool);
        String block = typeName(blockName);
        if (block.isEmpty()) block = "block";
        return "Need a " + want + " (or better) to mine " + block + "; " + have + " would destroy it with no drop";
    }
    private static String typeName(String raw) {
        if (raw == null) return "";
        String n = raw.trim().toUpperCase(java.util.Locale.ROOT);
        return n.startsWith("MINECRAFT:") ? n.substring("MINECRAFT:".length()) : n;
    }
    static boolean wouldDestroy(String toolName, String blockName) {
        return !allowEmptyDrops(blockName) && requiredPickaxe(blockName) >= 0 && !toolCanHarvest(toolName, blockName);
    }
    static void equipTool(Player p,Block b){
        String suffix=null;
        try {
            suffix=Tag.MINEABLE_PICKAXE.isTagged(b.getType())?"_PICKAXE":Tag.MINEABLE_AXE.isTagged(b.getType())?"_AXE"
                    :Tag.MINEABLE_SHOVEL.isTagged(b.getType())?"_SHOVEL":Tag.MINEABLE_HOE.isTagged(b.getType())?"_HOE":null;
        } catch (RuntimeException ignored) {}
        String typeName;
        try { typeName = b.getType().name(); } catch (RuntimeException e) { typeName = ""; }
        for (String key : preferredTools(typeName, suffix)) {
            int slot=harvestSlot(p,key,typeName);if(slot<0)continue;
            if(slot<=8){p.getInventory().setHeldItemSlot(slot);return;}
            int held=p.getInventory().getHeldItemSlot();
            ItemStack hand=p.getInventory().getItem(held),tool=p.getInventory().getItem(slot);
            p.getInventory().setItem(held,tool);p.getInventory().setItem(slot,hand);
            return;
        }
    }
    /** Best owned tool of this kind that would actually drop the block. */
    static int harvestSlot(Player p, String suffix, String blockName) {
        int best=-1,bestRank=-1;
        String[] ranks={"WOODEN","GOLDEN","STONE","COPPER","IRON","DIAMOND","NETHERITE"};
        for(int i=0;i<36;i++){
            ItemStack it=p.getInventory().getItem(i);if(it==null)continue;
            String n=it.getType().name();
            boolean match="SHEARS".equals(suffix)?n.equals("SHEARS"):n.endsWith(suffix);
            if(!match)continue;
            if(wouldDestroy(n,blockName))continue;
            int rank=0;for(int r=0;r<ranks.length;r++)if(n.startsWith(ranks[r]))rank=r;
            if(rank>=bestRank){bestRank=rank;best=i;}
        }
        return best;
    }
    /** True when placing `place` would leave the agent with no passable exit, or fill head space. */
    static boolean sealsAgent(Player p,Block place){
        Block feet=p.getLocation().getBlock(),head=feet.getRelative(BlockFace.UP);
        if(place.equals(head)||place.equals(head.getRelative(BlockFace.UP)))return true;
        if(place.equals(feet))return !head.getRelative(BlockFace.UP).isPassable();
        int exits=0;
        for(BlockFace f:new BlockFace[]{BlockFace.NORTH,BlockFace.SOUTH,BlockFace.EAST,BlockFace.WEST,BlockFace.UP}){
            Block atFeet=f==BlockFace.UP?head.getRelative(BlockFace.UP):feet.getRelative(f);
            Block atHead=f==BlockFace.UP?head.getRelative(BlockFace.UP):head.getRelative(f);
            if((atFeet.isPassable()&&!atFeet.equals(place))||(atHead.isPassable()&&!atHead.equals(place)))exits++;
        }
        return exits==0;
    }

    // ---------- tree chopping (harvest on a log) ----------
    static final class Chop{Material type;Deque<Block> logs=new ArrayDeque<>();int total,broken,skipped,tries;String status="chopping",note="";Block navFor;long navAt;int baseY;boolean suspended;}
    private static final Map<UUID,Chop> chops=new HashMap<>();
    static boolean chopping(NPC npc){Chop c=chops.get(npc.getUniqueId());return c!=null&&"chopping".equals(c.status);}
    static void suspendChop(NPC npc){Chop c=chops.get(npc.getUniqueId());if(c!=null&&"chopping".equals(c.status))c.suspended=true;}
    static void unsuspendChop(NPC npc){Chop c=chops.get(npc.getUniqueId());if(c!=null)c.suspended=false;}
    static boolean busy(NPC npc){return chopping(npc)||AgentCombat.busy(npc)||AgentProtect.active(npc)||AgentSelfDefense.active(npc);}
    /** Current log being chopped, or null. */
    static Block currentChopLog(NPC npc){Chop c=chops.get(npc.getUniqueId());return c!=null&&"chopping".equals(c.status)&&!c.logs.isEmpty()?c.logs.peek():null;}
    static Map<String,Object> startChop(NPC npc,Player p,Block start){
        Material type=start.getType();List<Block> found=new ArrayList<>();Set<Block> seen=new HashSet<>();Deque<Block> q=new ArrayDeque<>();q.add(start);seen.add(start);
        while(!q.isEmpty()&&found.size()<32){Block b=q.poll();found.add(b);
            for(int x=-1;x<=1;x++)for(int y=-1;y<=1;y++)for(int z=-1;z<=1;z++){Block n=b.getRelative(x,y,z);
                if(n.getType()==type&&seen.add(n)&&start.getWorld().isChunkLoaded(n.getX()>>4,n.getZ()>>4))q.add(n);}}
        found.sort(Comparator.comparingInt(Block::getY));
        Chop c=new Chop();c.type=type;c.logs.addAll(found);c.total=found.size();c.baseY=found.get(0).getY();chops.put(npc.getUniqueId(),c);
        Location spot=standSpot(p,start,c.baseY);
        if(!AgentReach.inReach(p.getEyeLocation(),start)){
            if(spot==null){chops.remove(npc.getUniqueId());throw new IllegalArgumentException("Harvest target is out of reach and there is no standing spot within 5 blocks of the tree");}
            AgentFocus.note(npc,"harvest",start);
            AgentNavigation.start(npc,spot,true);
            c.note="Walking to tree";
            Map<String,Object> r=chopStatus(npc);r.put("state","pathing");return r;
        }
        tickChop(npc,p,c);
        return chopStatus(npc);
    }
    static Map<String,Object> chopStatus(NPC npc){Chop c=chops.get(npc.getUniqueId());if(c==null)return null;
        Map<String,Object> r=new LinkedHashMap<>();r.put("status",c.status);r.put("log",c.type.name());r.put("total",c.total);r.put("broken",c.broken);r.put("skipped",c.skipped);r.put("remaining",c.logs.size());if(!c.note.isEmpty())r.put("note",c.note);return r;}
    static Map<String,Object> cancel(NPC npc){
        Chop c=chops.get(npc.getUniqueId());
        Map<String,Object> cancelled=null;
        if(c!=null&&"chopping".equals(c.status)){
            c.status="cancelled";
            c.note="Cancelled by a later action with "+c.broken+" of "+c.total+" logs broken ("+c.logs.size()+" remaining)";
            cancelled=chopStatus(npc);
            c.logs.clear();
        }
        AgentCombat.stop(npc);
        return cancelled;
    }
    private static final Map<UUID,List<String>> recentDrops=new HashMap<>();
    static void noteMined(Player p,org.bukkit.Material broken){
        if(p==null||broken==null)return;
        List<String> types=recentDrops.computeIfAbsent(p.getUniqueId(),k->new ArrayList<>());
        types.remove(broken.name());types.add(0,broken.name());
        if(types.size()>8)types.subList(8,types.size()).clear();
    }
    static double dropRank(String type,double distSq,List<String> prefer){
        return (prefer!=null&&prefer.contains(type)?0:1_000_000)+distSq;
    }
    static Item preferDrop(Player p,List<Item> items){
        if(items==null||items.isEmpty())return null;
        List<String> prefer=recentDrops.getOrDefault(p.getUniqueId(),List.of());
        return items.stream().filter(Item::isValid).min(Comparator.comparingDouble(it->dropRank(it.getItemStack().getType().name(),it.getLocation().distanceSquared(p.getLocation()),prefer))).orElse(items.get(0));
    }
    private static Location standSpot(Player p,Block log,int baseY){
        World w=log.getWorld();Location best=null;double bd=Double.MAX_VALUE;
        for(int r=1;r<=3;r++)for(int x=-r;x<=r;x++)for(int z=-r;z<=r;z++)for(int y=baseY-2;y<=baseY+6;y++){
            Block f=w.getBlockAt(log.getX()+x,y,log.getZ()+z);
            if(!f.isPassable()||!f.getRelative(BlockFace.UP).isPassable()||!f.getRelative(BlockFace.DOWN).getType().isSolid()||f.isLiquid())continue;
            Location l=f.getLocation().add(.5,0,.5);if(!AgentReach.inReach(l.clone().add(0,1.62,0),log))continue;
            double d=l.distanceSquared(p.getLocation());if(d<bd){bd=d;best=l;}
        }
        return best;
    }
    private static void tickChop(NPC npc,Player p,Chop c){
        for(int guard=0;guard<4&&!c.logs.isEmpty();guard++){
            Block b=c.logs.peek();
            if(b.getType()!=c.type){c.logs.poll();c.tries=0;continue;}
            if(AgentReach.inReach(p.getEyeLocation(),b)){
                try{clearPath(p,b);}catch(IllegalArgumentException blocked){if(c.tries++>3){c.logs.poll();c.skipped++;c.tries=0;c.note=blocked.getMessage();}else moveTo(npc,p,c,b);return;}
                AgentFocus.note(npc, "harvest", b);
                if(AgentProtection.breakBlock(p,b)){AgentBehaviors.noteMined(p,c.type);AgentHunger.exhaust(p, AgentHunger.BREAK);c.broken++;AgentsMC.collectNearby(p,5.5);}else{c.skipped++;c.note="Break rejected";}
                c.logs.poll();c.tries=0;return;
            }
            if(AgentNavigation.active(npc))return;
            if(c.tries++>3){c.logs.poll();c.skipped++;c.tries=0;c.note="Some logs out of reach from the ground";continue;}
            moveTo(npc,p,c,b);return;
        }
        if(c.logs.isEmpty()&&"chopping".equals(c.status)){
            c.status="done";
            Item drop=p.getNearbyEntities(8,6,8).stream().filter(Item.class::isInstance).map(Item.class::cast).filter(Item::isValid)
                    .min(Comparator.comparingDouble(i->i.getLocation().distanceSquared(p.getLocation()))).orElse(null);
            Item chosen=preferDrop(p,p.getNearbyEntities(8,6,8).stream().filter(Item.class::isInstance).map(Item.class::cast).filter(Item::isValid).toList());
            if(chosen==null)chosen=drop;
            if(chosen!=null){try{AgentNavigation.collect(npc,chosen);c.note="Collecting remaining drops";}catch(RuntimeException ignored){}}
        }
    }
    private static void moveTo(NPC npc,Player p,Chop c,Block b){
        Location spot=standSpot(p,b,Math.max(c.baseY,b.getY()));
        if(spot==null)spot=standSpot(p,b,c.baseY);
        if(spot==null){
            String climb=unstick(npc,p,b.getLocation().add(.5,1,.5));
            if(climb!=null){c.note="Climbing toward log at "+b.getX()+","+b.getY()+","+b.getZ()+" ("+climb+")";return;}
            int above=(int)Math.round(b.getY()-p.getLocation().getY());
            c.note="Log at "+b.getX()+","+b.getY()+","+b.getZ()+" is "+above+" blocks above reach; no standable cell within 5 and cannot pillar";
            if(c.tries++>3){c.logs.poll();c.skipped++;c.tries=0;}
            return;
        }
        try{AgentNavigation.start(npc,spot,true);}catch(RuntimeException e){c.note=e.getMessage();}
    }

    // ---------- routines + night_home ----------
    static final class Routine{List<JsonObject> steps=new ArrayList<>();int index;boolean nightHome;String phase="day",lastAction="",lastError="";int runs,homeTries;Location returnTo;boolean loaded;}
    private static final Map<UUID,Routine> routines=new HashMap<>();
    private static Routine routine(NPC npc){
        Routine r=routines.computeIfAbsent(npc.getUniqueId(),k->new Routine());
        if(!r.loaded){r.loaded=true;
            Object s=npc.data().get(ROUTINE_KEY,null);if(s instanceof String json&&!json.isBlank())try{for(JsonElement e:JsonParser.parseString(json).getAsJsonArray())r.steps.add(e.getAsJsonObject());}catch(RuntimeException ignored){}
            r.nightHome="night_home".equals(npc.data().get(BEHAVIOR_KEY,""));}
        return r;
    }
    static List<JsonObject> parseSteps(String json,String world){
        JsonElement el;try{el=JsonParser.parseString(json);}catch(RuntimeException e){throw new IllegalArgumentException("steps must be a JSON array string");}
        if(!el.isJsonArray())throw new IllegalArgumentException("steps must be a JSON array string");
        JsonArray a=el.getAsJsonArray();if(a.size()>24)throw new IllegalArgumentException("steps must contain 0..24 actions");
        if(a.size()==0)return new ArrayList<>();
        List<JsonObject> out=new ArrayList<>();
        for(JsonElement e:a){if(!e.isJsonObject())throw new IllegalArgumentException("Each step must be an object");
            JsonObject o=e.getAsJsonObject();AgentActionProgram.normalize(o,world);
            if("map".equals(o.get("action").getAsString()))throw new IllegalArgumentException("Routine steps cannot nest map");
            out.add(o.deepCopy());}
        return out;
    }
    /** The 'map' action: mode=set|clear|status, steps=JSON array string, behavior=night_home|none. */
    static Map<String,Object> mapAction(NPC npc,Player p,JsonObject o){
        for(String k:o.keySet())if(!Set.of("action","agent","mode","steps","behavior").contains(k))throw new IllegalArgumentException("Unknown action field: "+k);
        String mode=o.has("mode")?o.get("mode").getAsString():"status";Routine r=routine(npc);
        switch(mode){
            case "set" -> {
                if(!o.has("steps")&&!o.has("behavior"))throw new IllegalArgumentException("set needs steps and/or behavior");
                if(o.has("steps")){String json=o.get("steps").getAsString();r.steps=parseSteps(json,p.getWorld().getName());r.index=0;npc.data().setPersistent(ROUTINE_KEY,json);}
                if(o.has("behavior")){String b=o.get("behavior").getAsString();
                    if(!AgentAutonomy.validMode(b))throw new IllegalArgumentException("behavior must be night_home, auto, passive, or none");
                    r.nightHome=b.equals("night_home");npc.data().setPersistent(BEHAVIOR_KEY,b);AgentAutonomy.setMode(npc,b);}
            }
            case "clear" -> {r.steps.clear();r.index=0;r.nightHome=false;r.lastError="";npc.data().remove(ROUTINE_KEY);npc.data().remove(BEHAVIOR_KEY);AgentAutonomy.setMode(npc,"auto");cancel(npc);}
            case "status" -> {}
            default -> throw new IllegalArgumentException("mode must be set, clear or status");
        }
        Map<String,Object> s=new LinkedHashMap<>();
        s.put("steps",r.steps.size());s.put("next",r.index);s.put("runs",r.runs);s.put("nightHome",r.nightHome);s.put("phase",r.phase);
        s.put("lastAction",r.lastAction);s.put("lastError",r.lastError);Map<String,Object> chop=chopStatus(npc);if(chop!=null)s.put("chop",chop);s.put("fight",AgentCombat.snapshot(npc));s.put("selfDefense",AgentSelfDefense.snapshot(npc));
        s.put("autonomy",AgentAutonomy.snapshot(npc));
        s.put("program",AgentBridge.programSnapshot(AgentErrors.text(npc.data().get("agentsmc-id",""))));
        s.put("home",npc.data().get("agentsmc-waypoint-home","not set"));return s;
    }
    private static Location home(NPC npc){Object v=npc.data().get("agentsmc-waypoint-home",null);if(!(v instanceof String s))return null;
        try{String[] wp=s.split(":",2),c=wp[1].split(",");World w=Bukkit.getWorld(wp[0]);if(w==null)return null;return new Location(w,Integer.parseInt(c[0])+.5,Integer.parseInt(c[1]),Integer.parseInt(c[2])+.5);}catch(RuntimeException e){return null;}}
    private static Block findBed(Location home){
        World w=home.getWorld();if(w==null)return null;
        int hx=home.getBlockX(),hy=home.getBlockY(),hz=home.getBlockZ();
        for(int x=-3;x<=3;x++)for(int z=-3;z<=3;z++)for(int y=-1;y<=1;y++){
            if(!w.isChunkLoaded((hx+x)>>4,(hz+z)>>4))continue;
            Block b=w.getBlockAt(hx+x,hy+y,hz+z);
            if(b.getBlockData() instanceof org.bukkit.block.data.type.Bed)return b;
        }
        return null;
    }
    private static boolean night(World w){long t=w.getTime();return (t>=12542&&t<23460)||w.isThundering();}

    private static int ticks;
    /** Called every 2 server ticks from the AgentsMC navigation timer. */
    static void tick(AgentsMC plugin,NPC npc){
        if(!AgentsMC.bodyUp(npc)||!(npc.getEntity() instanceof Player p))return;
        Chop c=chops.get(npc.getUniqueId());
        if(c!=null&&"chopping".equals(c.status)&&!c.suspended&&(npc.getId()+ticks)%5==0)tickChop(npc,p,c);
        if((npc.getId()+ticks)%5!=0)return;
        Routine r=routine(npc);
        if(r.steps.isEmpty()&&!r.nightHome)return;
        if(busy(npc)||AgentNavigation.active(npc)||AgentCombat.active(npc))return;
        if(r.nightHome){
            boolean dark=night(p.getWorld());Location home=home(npc);
            Block bed=home==null?null:findBed(home);
            if(dark&&home!=null&&home.getWorld().equals(p.getWorld())){
                if(bed==null){r.lastError="night_home: no bed within 3 blocks of home; skipping";}
                else {
                    if(!"night".equals(r.phase)){r.phase="night";r.homeTries=0;r.returnTo=p.getLocation().clone();}
                    if(p.isSleeping())return;
                    if(p.getLocation().distanceSquared(home)>9){
                        if(r.homeTries++<4){try{AgentNavigation.start(npc,home,true);r.lastAction="night_home goto";}catch(RuntimeException e){r.lastError="night_home: "+e.getMessage();}}
                        return;
                    }
                    if(!AgentReach.inReach(p.getEyeLocation(),bed)){if(r.homeTries++<4)try{AgentNavigation.start(npc,bed.getLocation().add(.5,1,.5),true);}catch(RuntimeException ignored){}return;}
                    try{if(p.sleep(AgentSleep.headOf(bed.getLocation()),false)){AgentSleep.markAsleep(npc,bed.getLocation());r.lastAction="night_home sleeping";r.lastError="";}else r.lastError="night_home: cannot sleep now (monsters nearby?)";}catch(RuntimeException e){r.lastError="night_home: "+e.getMessage();}
                    return;
                }
            }
            if(!dark&&"night".equals(r.phase)){
                r.phase="day";AgentSleep.stand(npc,p);
                if(r.returnTo!=null&&r.returnTo.getWorld().equals(p.getWorld())&&r.returnTo.distanceSquared(p.getLocation())>4){try{AgentNavigation.start(npc,r.returnTo,true);r.lastAction="night_home return";}catch(RuntimeException ignored){}}
                r.returnTo=null;return;
            }
            if(dark&&home==null)r.lastError="night_home: waypoint 'home' not set";
        }
        if(r.steps.isEmpty())return;
        JsonObject step=r.steps.get(r.index%r.steps.size()).deepCopy();r.index=(r.index+1)%r.steps.size();if(r.index==0)r.runs++;
        r.lastAction=step.get("action").getAsString();
        try{Map<String,Object> out=plugin.routineStep(npc,step);if(Boolean.FALSE.equals(out.get("ok")))r.lastError=r.lastAction+": "+out.get("error");}
        catch(RuntimeException e){r.lastError=r.lastAction+": "+e.getMessage();}
    }
    static void advance(){ticks++;}
    private AgentBehaviors(){}
}
