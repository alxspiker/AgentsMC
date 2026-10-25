package dev.agentsmc;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.block.Block;
import org.bukkit.block.Container;
import org.bukkit.block.Furnace;
import org.bukkit.block.data.Openable;
import org.bukkit.entity.Entity;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.entity.Item;
import org.bukkit.event.block.Action;
import org.bukkit.block.BlockFace;
import org.bukkit.event.Event;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerDropItemEvent;
import org.bukkit.event.player.PlayerItemConsumeEvent;
import org.bukkit.event.entity.FoodLevelChangeEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.block.BlockState;
import org.bukkit.inventory.EquipmentSlot;
import io.papermc.paper.datacomponent.DataComponentTypes;
import io.papermc.paper.datacomponent.item.FoodProperties;
import io.papermc.paper.datacomponent.item.Consumable;
import io.papermc.paper.datacomponent.item.consumable.ConsumeEffect;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.Recipe;
import org.bukkit.inventory.RecipeChoice;
import org.bukkit.inventory.ShapedRecipe;
import org.bukkit.inventory.ShapelessRecipe;
import org.bukkit.util.Vector;
import com.google.gson.JsonObject;
import net.citizensnpcs.api.npc.NPC;

/** Additional server-thread actions. Fail closed on unsupported interactions. */
final class AgentSurvivalActions {
    static void check(boolean ok, String message) {
        if (!ok) throw new IllegalArgumentException(AgentTargets.requireError(message));
    }
    static Map<String,Object> out(Object... values) {
        Map<String,Object> map = new LinkedHashMap<>();
        for (int i=0;i<values.length;i+=2) map.put((String)values[i], values[i+1]);
        return map;
    }
    static String str(JsonObject o,String key) {
        check(o.has(key) && o.get(key).isJsonPrimitive() && o.getAsJsonPrimitive(key).isString(),"Expected string: "+key);
        String s=o.get(key).getAsString(); check(!s.isBlank(),"Empty string: "+key); return s;
    }
    static int num(JsonObject o,String key,int low,int high) {
        check(o.has(key) && o.get(key).isJsonPrimitive() && o.getAsJsonPrimitive(key).isNumber(),"Expected number: "+key);
        double n=o.get(key).getAsDouble();
        check(Double.isFinite(n) && n==Math.rint(n) && n>=low && n<=high,"Invalid integer: "+key);
        return (int)n;
    }
    static void fields(JsonObject o,String... allowed) {
        fields(o, List.of(), allowed);
    }
    /** Package-visible so tests can run documented heightmap/walkmap examples through the same allowlist. */
    static void fields(JsonObject o, List<String> optional, String... required) {
        List<String> valid = Arrays.asList(required);
        String action = o.has("action") && o.get("action").isJsonPrimitive() ? o.get("action").getAsString() : "action";
        for (String k:o.keySet()) {
            if (k.equals("world")) continue; // injected when x/y/z are present; clients cannot send it
            check(valid.contains(k) || optional.contains(k),"Unknown action field '"+k+"' for action "+action);
        }
        for (String k:required) {
            if (k.equals("world") || k.equals("agent")) continue; // injected; clients cannot send them
            check(o.has(k) && !o.get(k).isJsonNull(),"Missing action field '"+k+"' for action "+action);
        }
    }
    static Material material(String s) {
        String key;
        try { key = AgentPlacement.blockKey(s); }
        catch (IllegalArgumentException ignored) { key = s; }
        Material m=Material.matchMaterial(key);
        if (m == null) m = Material.matchMaterial(s);
        check(m!=null && m.isItem(),"Unknown item: "+s+" (accepted namespaced keys like minecraft:torch or Bukkit names like TORCH)");
        return m;
    }
    static Block target(Player p, JsonObject o, boolean reach) {
        String world = str(o,"world");
        check(p.getWorld().getName().equals(world),"Target must be in agent's current world");
        int x=num(o,"x",-30000000,30000000), y=num(o,"y",p.getWorld().getMinHeight(),p.getWorld().getMaxHeight()-1),
                z=num(o,"z",-30000000,30000000);
        check(p.getWorld().isChunkLoaded(x>>4,z>>4),"Target chunk is not loaded");
        Block b=p.getWorld().getBlockAt(x,y,z);
        check(p.getWorld().getWorldBorder().isInside(b.getLocation()),"Target outside world border");
        if (reach) {
            check(AgentReach.inReach(p.getEyeLocation(),b),"Block beyond 5-block reach ("+
                    Math.round(AgentReach.nearest(p.getEyeLocation(),b)*10)/10.0+" blocks from eyes; max 5.0)");
            if(!AgentReach.touching(p.getLocation(),b)){
                Block obstruction=AgentBehaviors.firstObstruction(p,b);
                if(obstruction!=null && !obstruction.equals(b))
                    throw new IllegalArgumentException(AgentBehaviors.obstructedMessage(obstruction));
                if(obstruction!=null)
                    throw new IllegalArgumentException("Target is outside five-block reach ("+
                            Math.round(AgentReach.nearest(p.getEyeLocation(),b)*10)/10.0+" blocks from eyes; max 5.0)");
            }
        }
        return b;
    }
    static Entity entity(Player p, JsonObject o, double max) {
        String raw;
        try { raw=str(o,"uuid"); }
        catch (IllegalArgumentException e) { throw new IllegalArgumentException(AgentTargets.requireError(e.getMessage())); }
        UUID id=AgentTargets.parseUuid(raw);
        Entity e=Bukkit.getEntity(id);
        check(e!=null && !e.isDead() && e!=p && e.getWorld().equals(p.getWorld()),"Entity unavailable");
        check(e.getLocation().distanceSquared(p.getLocation())<=max*max,"Entity out of range");
        return e;
    }

    /** Follow: UUID or case-insensitive player/agent display name. Never an empty error. */
    static Entity followTarget(Player p, JsonObject o, double max) {
        String sel=AgentTargets.selector(o);
        check(!sel.isBlank(), AgentTargets.followNeed());
        Entity e;
        if(AgentTargets.looksLikeUuid(sel)){
            UUID id=AgentTargets.parseUuid(sel);
            e=Bukkit.getEntity(id);
            check(e!=null, "No entity with UUID "+id);
        }else{
            e=AgentTargets.findNamed(p,sel);
            check(e!=null, AgentTargets.missing(sel));
        }
        check(e!=p, "Cannot follow self");
        check(e.isValid() && !e.isDead(), "Follow target is gone");
        check(e.getWorld()!=null && e.getWorld().equals(p.getWorld()), "Follow target is in another world");
        double dist=e.getLocation().distance(p.getLocation());
        check(dist<=max, "Follow target is out of range ("+Math.round(dist*10)/10.0+" blocks; max "+(int)max+")");
        return e;
    }
    static Object item(ItemStack stack) {
        if (stack==null || stack.getType().isAir()) return null;
        return out("material",stack.getType().name(),"amount",stack.getAmount(),
                "maxStack",stack.getMaxStackSize(),"enchantments",stack.getEnchantments().toString(),
                "hasMetadata",stack.hasItemMeta());
    }
    static ItemStack slot(Player p,int n) {
        check(AgentEquipment.inRange(n), AgentEquipment.rangeError());
        return p.getInventory().getItem(n);
    }
    static void put(Player p,int n,ItemStack stack) {
        p.getInventory().setItem(n,stack);
    }
    private static int space(Inventory inv,ItemStack desired) {
        int room=0;
        for (ItemStack stack:inv.getStorageContents()) {
            if (stack==null || stack.getType().isAir()) room+=desired.getMaxStackSize();
            else if (stack.isSimilar(desired)) room+=Math.max(0,stack.getMaxStackSize()-stack.getAmount());
        }
        return room;
    }
    static boolean permitted(Player p,Block b,Action action) {
        return AgentProtection.interact(p,b,action);
    }
    /** Vanilla bow / arrow / crossbow when Bukkit has no matching shaped recipe. Needs a table. */
    static Map<String,Object> craftKnown(Player p, Material output, int requested, boolean table) {
        AgentRanged.RecipeSpec spec=AgentRanged.craftSpec(output.name());
        if(spec==null) return null;
        if(!table) {
            boolean have=false;
            for(ItemStack stack:p.getInventory().getStorageContents())
                if(stack!=null && stack.getType()==Material.CRAFTING_TABLE){have=true;break;}
            throw new IllegalArgumentException(tableNeeded(output.name()+" ("+output.name()+")",false,have));
        }
        int crafts=AgentReach.craftsFor(requested,spec.yield);
        if(crafts<1) return null;
        ItemStack[] working=p.getInventory().getStorageContents().clone();
        List<Object> viewerIngredients=new ArrayList<>();
        for(int i=0;i<working.length;i++) if(working[i]!=null) working[i]=working[i].clone();
        for(int round=0;round<crafts;round++) {
            for(String in:spec.inputs) {
                boolean found=false;
                for(int n=0;n<working.length;n++) {
                    ItemStack stack=working[n];
                    if(stack!=null && stack.getAmount()>0 && stack.getType().name().equalsIgnoreCase(in)) {
                        ItemStack consumed=stack.clone();consumed.setAmount(1);viewerIngredients.add(AgentPresentation.item(consumed));
                        stack.setAmount(stack.getAmount()-1);
                        if(stack.getAmount()==0) working[n]=null;
                        found=true;break;
                    }
                }
                if(!found) throw new IllegalArgumentException(AgentCraft.missingIngredient(output.name(),in,ownedNamed(p,in)));
            }
        }
        ItemStack product=new ItemStack(output,spec.yield);
        int produced=crafts*spec.yield;
        ItemStack remaining=product.clone();remaining.setAmount(produced);
        for(ItemStack stack:working) if(stack!=null && stack.isSimilar(remaining)) {
            int add=Math.min(remaining.getAmount(),stack.getMaxStackSize()-stack.getAmount());
            if(add>0){stack.setAmount(stack.getAmount()+add);remaining.setAmount(remaining.getAmount()-add);}
        }
        for(int i=0;i<working.length && remaining.getAmount()>0;i++) if(working[i]==null) {
            int add=Math.min(remaining.getAmount(),remaining.getMaxStackSize());
            working[i]=remaining.clone();working[i].setAmount(add);
            remaining.setAmount(remaining.getAmount()-add);
        }
        if(remaining.getAmount()>0) return null;
        p.getInventory().setStorageContents(working);
        return out("viewerRecipe",AgentPresentation.recipeWidget(viewerIngredients),"crafted",item(product),"amount",produced,"usedCraftingTable",true,
                "requested",requested,"yield",spec.yield,"crafts",crafts,
                "note","Uses vanilla "+spec.output+" recipe (yield "+spec.yield+")");
    }
    /** Planks / sticks / chest when Bukkit's recipe iterator has no match. */
    static Map<String,Object> craftVanilla(Player p, Material output, int requested, boolean table) {
        AgentCraft.Spec spec=AgentCraft.vanilla(output.name());
        if(spec==null) return null;
        if(spec.table && !table) {
            boolean have=false;
            for(ItemStack stack:p.getInventory().getStorageContents())
                if(stack!=null && stack.getType()==Material.CRAFTING_TABLE){have=true;break;}
            throw new IllegalArgumentException(tableNeeded(output.name(),false,have));
        }
        Map<String,Integer> counts=new LinkedHashMap<>();
        for(ItemStack stack:p.getInventory().getStorageContents())
            if(stack!=null && stack.getAmount()>0)
                counts.merge(stack.getType().name(),stack.getAmount(),Integer::sum);
        int affordable=AgentCraft.maxCrafts(counts,spec);
        int produced=AgentCraft.produced(requested,spec.yield,affordable);
        if(produced<1) throw new IllegalArgumentException(AgentCraft.shortage(output.name(),spec.inputs,counts));
        int crafts=produced/spec.yield;
        ItemStack[] working=p.getInventory().getStorageContents().clone();
        List<Object> viewerIngredients=new ArrayList<>();
        for(int i=0;i<working.length;i++) if(working[i]!=null) working[i]=working[i].clone();
        for(int round=0;round<crafts;round++) {
            for(String in:spec.inputs) {
                boolean found=false;
                for(int n=0;n<working.length;n++) {
                    ItemStack stack=working[n];
                    if(stack!=null && stack.getAmount()>0 && AgentCraft.matchesInput(stack.getType().name(),in)) {
                        ItemStack consumed=stack.clone();consumed.setAmount(1);viewerIngredients.add(AgentPresentation.item(consumed));
                        stack.setAmount(stack.getAmount()-1);
                        if(stack.getAmount()==0) working[n]=null;
                        found=true;break;
                    }
                }
                if(!found) throw new IllegalArgumentException(AgentCraft.missingIngredient(output.name(),in,ownedNamed(p,in)));
            }
        }
        ItemStack product=new ItemStack(output,spec.yield);
        ItemStack remaining=product.clone();remaining.setAmount(produced);
        for(ItemStack stack:working) if(stack!=null && stack.isSimilar(remaining)) {
            int add=Math.min(remaining.getAmount(),stack.getMaxStackSize()-stack.getAmount());
            if(add>0){stack.setAmount(stack.getAmount()+add);remaining.setAmount(remaining.getAmount()-add);}
        }
        for(int i=0;i<working.length && remaining.getAmount()>0;i++) if(working[i]==null) {
            int add=Math.min(remaining.getAmount(),remaining.getMaxStackSize());
            working[i]=remaining.clone();working[i].setAmount(add);
            remaining.setAmount(remaining.getAmount()-add);
        }
        if(remaining.getAmount()>0) return null;
        p.getInventory().setStorageContents(working);
        return out("viewerRecipe",AgentPresentation.recipeWidget(viewerIngredients),"crafted",item(product),"amount",produced,"usedCraftingTable",spec.table,
                "requested",requested,"yield",spec.yield,"crafts",crafts,
                "note","Uses vanilla "+spec.output+" recipe (yield "+spec.yield+")");
    }
    static String tableNeeded(String recipe, boolean tableNearby, boolean tableInInventory) {
        StringBuilder s=new StringBuilder("Recipe ").append(recipe).append(" needs a crafting table");
        if(!tableNearby)s.append("; none within 3 blocks");
        if(tableInInventory)s.append("; you have a CRAFTING_TABLE in inventory to place");
        else s.append("; no CRAFTING_TABLE in inventory");
        return s.toString();
    }
    private static String keys(NPC npc,String key) { return "agentsmc-"+key; }

    static Map<String,Object> execute(AgentsMC plugin,NPC npc,Player p,JsonObject o) {
        String action=str(o,"action");
        switch (action) {
            case "inspect_block": {
                fields(o,"action","agent","world","x","y","z");
                Block b=target(p,o,false);
                check(b.getLocation().distanceSquared(p.getLocation())<=32*32,"Block beyond observation range");
                Map<String,Object> inspected=out("material",b.getType().name(),"data",b.getBlockData().getAsString(),"light",b.getLightLevel());
                AgentRespawn.attach(inspected,b,npc);
                AgentSigns.attach(inspected,b);
                return inspected;
            }
            case "inspect_entity": {
                fields(o,"action","agent","uuid");
                Entity e=entity(p,o,32);
                Map<String,Object> r=out("uuid",e.getUniqueId().toString(),"type",e.getType().name(),
                        "name",e.getName(),"x",e.getX(),"y",e.getY(),"z",e.getZ());
                if (e instanceof LivingEntity living) {
                    r.put("health",living.getHealth()); r.put("maxHealth",living.getMaxHealth());
                }
                return r;
            }
            case "scan": {
                fields(o, List.of("material", "block", "item"), "action", "agent", "radius");
                check(o.has("material")||o.has("block")||o.has("item"),"scan requires material");
                String scanName=o.has("material")?str(o,"material"):o.has("block")?str(o,"block"):str(o,"item");
                Material wanted=Material.matchMaterial(AgentPlacement.blockKey(scanName));
                check(wanted!=null && wanted.isBlock(),"Unknown block material");
                int radius=num(o,"radius",1,32); List<Map<String,Object>> found=new ArrayList<>();
                Location at=p.getLocation();int total=0;
                for(int x=-radius;x<=radius;x++) for(int z=-radius;z<=radius;z++) {
                    int bx=at.getBlockX()+x,bz=at.getBlockZ()+z;
                    if (!p.getWorld().isChunkLoaded(bx>>4,bz>>4)) continue;
                    for(int y=-radius;y<=radius;y++) {
                        int by=at.getBlockY()+y;
                        if(by<p.getWorld().getMinHeight() || by>=p.getWorld().getMaxHeight()) continue;
                        int dy=by-at.getBlockY();
                        if(!AgentReach.scanInRadius(x,dy,z,radius))continue;
                        if(p.getWorld().getBlockAt(bx,by,bz).getType()==wanted) {
                            total++;found.add(out("x",bx,"y",by,"z",bz,"d",Math.round(Math.sqrt((double)x*x+(double)dy*dy+(double)z*z)*10)/10.0));
                        }
                    }
                }
                found.sort(java.util.Comparator.comparingDouble(m->(Double)m.get("d")));
                return out("matches",new ArrayList<>(found.subList(0,Math.min(20,found.size()))),"total",total,"truncated",total>20);
            }
            case "scan_entities": {
                fields(o,"action","agent","type","radius");
                org.bukkit.entity.EntityType wanted;
                try { wanted=org.bukkit.entity.EntityType.valueOf(str(o,"type").toUpperCase(java.util.Locale.ROOT)); }
                catch(IllegalArgumentException unknown){throw new IllegalArgumentException("Unknown entity type: "+str(o,"type"));}
                int radius=num(o,"radius",1,32);
                List<Entity> all=new ArrayList<>();for(Entity entity:p.getNearbyEntities(radius,radius,radius))if(entity.getType()==wanted&&entity.getLocation().distance(p.getLocation())<=radius+1e-9)all.add(entity);
                all.sort(java.util.Comparator.comparingDouble(e->e.getLocation().distanceSquared(p.getLocation())));
                List<Object> found=new ArrayList<>();
                for(Entity entity:all.subList(0,Math.min(20,all.size())))found.add(out("uuid",entity.getUniqueId().toString(),"type",entity.getType().name(),"name",entity.getName(),"x",Math.round(entity.getX()*10)/10.0,"y",Math.round(entity.getY()*10)/10.0,"z",Math.round(entity.getZ()*10)/10.0,"d",Math.round(entity.getLocation().distance(p.getLocation())*10)/10.0));
                return out("matches",found,"total",all.size(),"truncated",all.size()>20);
            }
            case "inspect_item": {
                fields(o,"action","agent","slot");
                int n=num(o,"slot",0,AgentEquipment.LAST);
                Map<String,Object> inspected=out("slot",n,"item",item(slot(p,n)));
                if(AgentEquipment.worn(n)) inspected.put("name",AgentEquipment.name(n));
                return inspected;
            }
            case "select": {
                fields(o,"action","agent","slot");
                int n=num(o,"slot",0,8); p.getInventory().setHeldItemSlot(n);
                return out("slot",n,"hand",item(p.getInventory().getItemInMainHand()));
            }
            case "move_item": case "split_stack": {
                fields(o,"action","agent","from","to","amount");
                int from=num(o,"from",0,AgentEquipment.LAST),to=num(o,"to",0,AgentEquipment.LAST),amount=num(o,"amount",1,64);
                check(from!=to,"Cannot transfer to same slot");
                ItemStack source=slot(p,from),destination=slot(p,to);
                check(source!=null && !source.getType().isAir() && amount<=source.getAmount(),"Not enough items in source");
                check(destination==null || destination.getType().isAir() || destination.isSimilar(source),"Target holds a different item");
                check(destination==null || destination.getType().isAir() || destination.getAmount()+amount<=destination.getMaxStackSize(),"Target stack full");
                check(destination!=null && !destination.getType().isAir() || amount<=source.getMaxStackSize(),"Amount exceeds stack limit");
                ItemStack change=source.clone();change.setAmount(amount);
                if(destination!=null && !destination.getType().isAir()) {
                    change=destination.clone();change.setAmount(destination.getAmount()+amount);
                }
                put(p,to,change);
                ItemStack remain=source.clone();remain.setAmount(source.getAmount()-amount);
                put(p,from,remain.getAmount()>0?remain:null);
                return out("moved",amount,"from",from,"to",to);
            }
            case "swap": {
                fields(o,"action","agent","first","second");
                int a=num(o,"first",0,AgentEquipment.LAST),b=num(o,"second",0,AgentEquipment.LAST);check(a!=b,"Slots must differ");
                ItemStack left=slot(p,a),right=slot(p,b);
                put(p,a,right);put(p,b,left);return out("first",a,"second",b);
            }
            case "sort": {
                fields(o,"action","agent");
                ItemStack[] contents=p.getInventory().getStorageContents();
                List<ItemStack> kept=new ArrayList<>();
                for(ItemStack stack:contents) if(stack!=null && !stack.getType().isAir()) kept.add(stack.clone());
                kept.sort((a,b)->a.getType().name().compareTo(b.getType().name()));
                ItemStack[] result=new ItemStack[contents.length];
                for(int i=0;i<kept.size();i++) result[i]=kept.get(i);
                p.getInventory().setStorageContents(result);
                return out("stacks",kept.size());
            }
            case "drop": {
                fields(o,"action","agent","slot","amount");
                int n=num(o,"slot",0,AgentEquipment.LAST),amount=num(o,"amount",1,64);
                ItemStack src=slot(p,n);check(src!=null && !src.getType().isAir() && src.getAmount()>=amount,"Not enough items");
                ItemStack drop=src.clone();drop.setAmount(amount);
                Item spawned=p.getWorld().dropItem(p.getEyeLocation(),drop);
                spawned.setPickupDelay(30);
                PlayerDropItemEvent event=new PlayerDropItemEvent(p,spawned);
                Bukkit.getPluginManager().callEvent(event);
                if(event.isCancelled()) {spawned.remove();throw new IllegalArgumentException("Drop was cancelled");}
                ItemStack remain=src.clone();remain.setAmount(src.getAmount()-amount);
                put(p,n,remain.getAmount()>0?remain:null);
                return out("dropped",item(drop));
            }
            case "inspect_container": {
                fields(o,"action","agent","world","x","y","z");
                Block b=target(p,o,true);
                check(permitted(p,b,Action.RIGHT_CLICK_BLOCK),"Container use rejected");
                check(b.getState() instanceof Container,"Target is not a container");
                List<Object> stacks=new ArrayList<>();
                Inventory inv=((Container)b.getState()).getInventory();
                for(int i=0;i<inv.getSize();i++) { ItemStack s=inv.getItem(i);if(s!=null && !s.getType().isAir()) stacks.add(out("slot",i,"item",item(s))); }
                Map<String,Object> seen=out("size",inv.getSize(),"items",stacks);
                if(b.getState() instanceof Furnace) seen.put("furnace",AgentFurnaceLog.status(b));
                return seen;
            }
            case "transfer": {
                fields(o,"action","agent","world","x","y","z","direction","material","amount");
                Block b=target(p,o,true);
                check(b.getState() instanceof Container,"Target is not a container");
                check(permitted(p,b,Action.RIGHT_CLICK_BLOCK),"Container use rejected by another plugin");
                Inventory container=((Container)b.getState()).getInventory();
                String direction=str(o,"direction");
                check(direction.equals("deposit") || direction.equals("withdraw"),"direction must be deposit or withdraw");
                Material m=material(str(o,"material"));int amount=num(o,"amount",1,2304);
                Inventory source=direction.equals("deposit")?p.getInventory():container;
                Inventory destination=direction.equals("deposit")?container:p.getInventory();
                int owned=0;ItemStack prototype=null;
                for(ItemStack s:source.getStorageContents()) if(s!=null && s.getType()==m) {owned+=s.getAmount();if(prototype==null)prototype=s.clone();}
                check(owned>=amount,"Not enough source items");check(prototype!=null,"Item not present");
                // No partial transfers and no merging dissimilar metadata.
                int matching=0;
                for(ItemStack s:source.getStorageContents()) if(s!=null && s.isSimilar(prototype)) matching+=s.getAmount();
                check(matching>=amount,"Matching item stacks insufficient; use separate transfers for differing metadata");
                check(space(destination,prototype)>=amount,"Destination has insufficient inventory space");
                int remaining=amount;
                for(int i=0;i<source.getStorageContents().length && remaining>0;i++) {
                    ItemStack s=source.getItem(i);
                    if(s==null || !s.isSimilar(prototype)) continue;
                    int taken=Math.min(s.getAmount(),remaining);
                    ItemStack moving=s.clone();moving.setAmount(taken);
                    Map<Integer,ItemStack> overflow=destination.addItem(moving);
                    check(overflow.isEmpty(),"Unexpected destination capacity change");
                    int left=s.getAmount()-taken;
                    if(left==0) source.setItem(i,null);
                    else {ItemStack updated=s.clone();updated.setAmount(left);source.setItem(i,updated);}
                    remaining-=taken;
                }
                if(b.getState() instanceof Furnace) AgentFurnaceLog.record(b.getLocation(),p.getName(),"transfer_"+direction,new ItemStack(m,amount));
                return out("direction",direction,"material",m.name(),"amount",amount);
            }
            case "craft": {
                fields(o, List.of("item", "material", "amount", "count"), "action", "agent");
                Material outputMaterial=material(AgentCraft.outputField(o));
                int requested=AgentCraft.amount(o);
                boolean table=false;
                Location at=p.getLocation();
                for(int dx=-3;dx<=3 && !table;dx++)for(int dy=-2;dy<=2 && !table;dy++)for(int dz=-3;dz<=3 && !table;dz++) {
                    int x=at.getBlockX()+dx,z=at.getBlockZ()+dz,y=at.getBlockY()+dy;
                    if(y<p.getWorld().getMinHeight() || y>=p.getWorld().getMaxHeight() || !p.getWorld().isChunkLoaded(x>>4,z>>4))continue;
                    if(p.getWorld().getBlockAt(x,y,z).getType()==Material.CRAFTING_TABLE)table=true;
                }
                String problem="No valid recipe and ingredients available";
                java.util.Iterator<Recipe> recipes=Bukkit.recipeIterator();
                while(recipes.hasNext()) {
                    Recipe recipe=recipes.next();
                    if(!(recipe instanceof ShapedRecipe) && !(recipe instanceof ShapelessRecipe))continue;
                    ItemStack product=recipe.getResult();
                    if(product.getType()!=outputMaterial || product.getAmount()<=0)continue;
                    int crafts=AgentReach.craftsFor(requested,product.getAmount());
                    if(crafts<1)continue;
                    List<RecipeChoice> choices=new ArrayList<>();
                    boolean requiresTable=false;
                    if(recipe instanceof ShapedRecipe shaped) {
                        String[] shape=shaped.getShape();
                        int width=0;
                        for(String row:shape)width=Math.max(width,row.length());
                        requiresTable=shape.length>2 || width>2;
                        Map<Character,RecipeChoice> map=shaped.getChoiceMap();
                        for(String row:shape)for(char c:row.toCharArray())if(c!=' ' && map.get(c)!=null)choices.add(map.get(c));
                    } else {
                        choices.addAll(((ShapelessRecipe)recipe).getChoiceList());
                        requiresTable=choices.size()>4;
                    }
                    if(requiresTable && !table){
                        boolean have=false;
                        for(ItemStack stack:p.getInventory().getStorageContents())
                            if(stack!=null && stack.getType()==Material.CRAFTING_TABLE){have=true;break;}
                        String recipeName=outputMaterial.name();
                        try{if(recipe instanceof org.bukkit.Keyed keyed)recipeName=keyed.getKey().getKey()+" ("+outputMaterial.name()+")";}catch(RuntimeException ignored){}
                        problem=tableNeeded(recipeName,false,have);continue;
                    }
                    if(choices.isEmpty())continue;
                    ItemStack[] working=p.getInventory().getStorageContents().clone();
                    List<Object> viewerIngredients=new ArrayList<>();
                    for(int i=0;i<working.length;i++)if(working[i]!=null)working[i]=working[i].clone();
                    boolean possible=true;
                    for(int round=0;round<crafts && possible;round++) {
                        for(RecipeChoice choice:choices) {
                            boolean found=false;
                            for(int n=0;n<working.length;n++) {
                                ItemStack stack=working[n];
                                if(stack!=null && stack.getAmount()>0 && choice.test(stack)) {
                                    // A returned container or persistent tool needs specialized recipe logic;
                                    // fail rather than silently destroy it or accidentally duplicate it.
                                    Material remainder=stack.getType().getCraftingRemainingItem();
                                    if(remainder!=null && remainder!=Material.AIR) continue;
                                    ItemStack consumed=stack.clone();consumed.setAmount(1);viewerIngredients.add(AgentPresentation.item(consumed));
                                    stack.setAmount(stack.getAmount()-1);
                                    if(stack.getAmount()==0)working[n]=null;
                                    found=true;break;
                                }
                            }
                            if(!found){
                                possible=false;
                                problem=AgentCraft.missingIngredient(outputMaterial.name(),describeChoice(choice),ownedForChoice(p,choice));
                                break;
                            }
                        }
                    }
                    if(!possible)continue;
                    int produced=crafts*product.getAmount();
                    ItemStack remaining=product.clone();remaining.setAmount(produced);
                    for(ItemStack stack:working)if(stack!=null && stack.isSimilar(remaining)) {
                        int add=Math.min(remaining.getAmount(),stack.getMaxStackSize()-stack.getAmount());
                        if(add>0){stack.setAmount(stack.getAmount()+add);remaining.setAmount(remaining.getAmount()-add);}
                    }
                    for(int i=0;i<working.length && remaining.getAmount()>0;i++)if(working[i]==null) {
                        int add=Math.min(remaining.getAmount(),remaining.getMaxStackSize());
                        working[i]=remaining.clone();working[i].setAmount(add);
                        remaining.setAmount(remaining.getAmount()-add);
                    }
                    if(remaining.getAmount()>0){problem="Inventory has no space for crafted output";continue;}
                    // Atomic inventory update: no ingredients are consumed if crafting fails.
                    p.getInventory().setStorageContents(working);
                    return out("viewerRecipe",AgentPresentation.recipeWidget(viewerIngredients),"crafted",item(product),"amount",produced,"usedCraftingTable",requiresTable,
                            "requested",requested,"yield",product.getAmount(),"crafts",crafts,
                            "note","Uses registered recipe inputs; crafting events are not simulated");
                }
                Map<String,Object> known=craftKnown(p,outputMaterial,requested,table);
                if(known!=null) return known;
                Map<String,Object> vanilla=craftVanilla(p,outputMaterial,requested,table);
                if(vanilla!=null) return vanilla;
                throw new IllegalArgumentException(problem);
            }
            case "recipe": {
                fields(o,"action","agent","item");
                Material m=material(str(o,"item"));
                List<Object> found=new ArrayList<>();
                for(Recipe recipe:Bukkit.getRecipesFor(new ItemStack(m))) {
                    Map<String,Object> entry=out("result",item(recipe.getResult()),"type",recipe.getClass().getSimpleName());
                    if(recipe instanceof ShapedRecipe shaped) entry.put("pattern",Arrays.asList(shaped.getShape()));
                    if(recipe instanceof ShapelessRecipe shapeless) entry.put("inputs",shapeless.getChoiceList().size());
                    found.add(entry);
                    if(found.size()>=32) break;
                }
                if(found.isEmpty()) {
                    AgentRanged.RecipeSpec spec=AgentRanged.craftSpec(m.name());
                    if(spec!=null) found.add(out("result",item(new ItemStack(m,spec.yield)),"type","VanillaFallback","inputs",spec.inputs,"yield",spec.yield));
                    AgentCraft.Spec known=AgentCraft.vanilla(m.name());
                    if(spec==null && known!=null) found.add(out("result",item(new ItemStack(m,known.yield)),"type","VanillaFallback","inputs",known.inputs,"yield",known.yield));
                }
                return out("item",m.name(),"recipes",found);
            }
            case "smelt": case "fuel": {
                fields(o,"action","agent","world","x","y","z","material","amount");
                Block b=target(p,o,true);
                check(b.getState() instanceof Furnace,"Target must be a furnace, smoker, or blast furnace");
                check(permitted(p,b,Action.RIGHT_CLICK_BLOCK),"Furnace use rejected");
                Material m=material(str(o,"material"));int amount=num(o,"amount",1,64);
                Inventory furnace=((Furnace)b.getState()).getInventory();
                int n=action.equals("fuel")?1:0;
                ItemStack existing=furnace.getItem(n);
                ItemStack desired=new ItemStack(m,amount);
                check(existing==null || existing.getType().isAir() || existing.isSimilar(desired),"Furnace slot occupied");
                check(existing==null || existing.getType().isAir() ? amount<=desired.getMaxStackSize() : existing.getAmount()+amount<=existing.getMaxStackSize(),"Furnace stack full");
                int owned=0;for(ItemStack stack:p.getInventory().getStorageContents())if(stack!=null && stack.isSimilar(desired)) owned+=stack.getAmount();
                check(owned>=amount,"Not enough owned ingredients or fuel");
                int remaining=amount;
                for(int i=0;i<36 && remaining>0;i++) {
                    ItemStack s=p.getInventory().getItem(i);if(s==null || !s.isSimilar(desired))continue;
                    int take=Math.min(remaining,s.getAmount());int count=s.getAmount()-take;
                    if(count==0)p.getInventory().setItem(i,null);else {ItemStack next=s.clone();next.setAmount(count);p.getInventory().setItem(i,next);}
                    remaining-=take;
                }
                if(existing==null || existing.getType().isAir())furnace.setItem(n,desired);
                else {ItemStack stack=existing.clone();stack.setAmount(existing.getAmount()+amount);furnace.setItem(n,stack);}
                AgentFurnaceLog.record(b.getLocation(),p.getName(),action,new ItemStack(m,amount));
                return out("loaded",m.name(),"amount",amount,"slot",n,"note","Vanilla furnace processing is asynchronous; the result collects in slot 2 until withdrawn (transfer direction=withdraw). Anyone can take it; inspect_container furnace.history shows who did");
            }
            case "wait": {
                fields(o,"action","agent","seconds");
                int seconds=num(o,"seconds",1,30);
                return out("seconds",seconds,"state","waited");
            }
            case "follow": {
                fields(o, List.of("uuid","target","player","to","name"), "action","agent");
                Entity e=followTarget(p,o,96);
                AgentBehaviors.cancel(npc);
                AgentProtect.stop(npc);
                AgentSelfDefense.release(npc);
                return AgentCombat.follow(npc,p,e);
            }
            case "fight": case "kill": {
                fields(o,"action","agent","uuid");Entity e=entity(p,o,96);
                check(e instanceof LivingEntity,"Target is not a living entity");
                AgentBehaviors.cancel(npc);
                AgentProtect.stop(npc);
                AgentSelfDefense.release(npc);
                return AgentCombat.fight(npc,p,(LivingEntity)e);
            }
            case "shoot": {
                fields(o, List.of("uuid","target","x","y","z","world"), "action","agent");
                Location dest;
                Vector vel=new Vector();
                String label;
                Entity aimed=null;
                if(o.has("uuid")||o.has("target")) {
                    String raw=o.has("uuid")?str(o,"uuid"):str(o,"target");
                    UUID id;
                    try { id=UUID.fromString(raw); }
                    catch(IllegalArgumentException e) { throw new IllegalArgumentException("Invalid entity UUID"); }
                    Entity e=Bukkit.getEntity(id);
                    check(e!=null && !e.isDead() && e!=p && e.getWorld().equals(p.getWorld()),"Entity unavailable");
                    dest=e instanceof LivingEntity living?AgentRanged.aimPoint(living):e.getLocation();
                    vel=AgentRanged.velocityOf(e);
                    label=e.getUniqueId().toString();
                    aimed=e;
                } else {
                    check(o.has("x")&&o.has("y")&&o.has("z"),"shoot requires target entity or x,y,z");
                    if(o.has("world")) check(p.getWorld().getName().equals(str(o,"world")),"Target must be in agent's current world");
                    int x=num(o,"x",-30000000,30000000), y=num(o,"y",p.getWorld().getMinHeight(),p.getWorld().getMaxHeight()-1),
                            z=num(o,"z",-30000000,30000000);
                    dest=new Location(p.getWorld(),x+0.5,y+0.5,z+0.5);
                    label=x+","+y+","+z;
                }
                AgentRanged.Shot shot=AgentRanged.fire(p,dest,vel);
                check(shot.ok, shot.error.isEmpty()?"Could not shoot":shot.error);
                AgentRanged.noteShot(npc.getUniqueId(),shot,label);
                AgentHunger.exhaust(p, AgentHunger.ATTACK);
                if(aimed!=null) AgentFocus.note(npc,"shoot",aimed);
                else AgentFocus.note(npc,"shoot",dest);
                return out("shot",true,"weapon",shot.weapon,"ammo",shot.ammo,"consumed",shot.consumed,
                        "speed",shot.speed,"target",label);
            }
            case "protect": {
                fields(o,List.of("radius"),"action","agent","player");
                String name=str(o,"player");
                int radius=o.has("radius")?num(o,"radius",1,32):AgentProtect.DEFAULT_RADIUS;
                Player ward;
                try { ward=AgentProtect.findWard(p,name); }
                catch (IllegalArgumentException e) { throw e; }
                check(AgentProtect.livingWard(ward),AgentProtect.offlineMessage(name));
                check(!ward.equals(p),"Cannot protect self");
                AgentBehaviors.cancel(npc);
                AgentSelfDefense.release(npc);
                return AgentProtect.start(npc,p,ward,radius);
            }
            case "jump": {
                fields(o,"action","agent");
                check(p.isOnGround(),"Agent must be on ground");
                Location before=p.getLocation().clone();
                double beforeY=p.getVelocity().getY();
                Vector velocity=p.getVelocity(); velocity.setY(0.42);p.setVelocity(velocity);
                double afterY=p.getVelocity().getY();
                check(afterY>beforeY+0.05 && afterY>=0.3,"Jump did not change vertical velocity (was "+Math.round(beforeY*100)/100.0+", now "+Math.round(afterY*100)/100.0+")");
                return out("jumped",true,"vy",afterY,"fromY",before.getY());
            }
            case "sprint": case "sneak": {
                fields(o,"action","agent","enabled");
                check(o.get("enabled").isJsonPrimitive() && o.getAsJsonPrimitive("enabled").isBoolean(),"enabled must be boolean");
                boolean value=o.get("enabled").getAsBoolean();
                if(action.equals("sprint"))p.setSprinting(value);else p.setSneaking(value);
                return out("enabled",value);
            }
            case "mount": {
                fields(o, List.of("target","uuid"), "action","agent");
                String target=o.has("target")?str(o,"target"):o.has("uuid")?str(o,"uuid"):"nearest";
                Entity e=AgentBoats.resolveMount(p,target,AgentBoats.RANGE);
                check(e!=null,"No nearby boat, minecart, or horse to mount");
                if(p.isInsideVehicle())check(p.leaveVehicle(),"Cannot leave current vehicle");
                check(e.addPassenger(p),"Entity refused passenger");
                return out("mounted",e.getUniqueId().toString(),"type",e.getType().name(),"kind",AgentBoats.kind(e.getType().name()));
            }
            case "dismount": {
                fields(o,"action","agent");
                check(p.isInsideVehicle(),"Agent is not mounted");
                Entity vehicle=p.getVehicle();
                check(p.leaveVehicle(),"Cannot leave vehicle");
                if(vehicle!=null)AgentBoats.halt(vehicle);
                return out("dismounted",true);
            }
            case "attack": {
                fields(o,"action","agent","uuid");Entity e=entity(p,o,16);
                if(AgentBoats.isBoatEntity(e))return AgentBoats.pickup(p,npc,e);
                check(e instanceof LivingEntity,"Target is not a living entity");
                double reach=AgentReach.nearest(p.getEyeLocation(),e);
                check(AgentReach.inAttackReach(p,e),"Entity out of range ("+Math.round(reach*10)/10.0+" blocks to bounding box; melee reach "+AgentReach.ATTACK+")");
                AgentFocus.note(npc, "attack", e);
                p.attack(e);AgentHunger.exhaust(p, AgentHunger.ATTACK);return out("attempted",true,"target",e.getUniqueId().toString(),"reach",Math.round(reach*10)/10.0);
            }
            case "use": {
                fields(o,"action","agent","world","x","y","z");
                Block b=target(p,o,false);
                check(b.getBlockData() instanceof Openable,"Only doors, fence gates, and trapdoors are supported by this action");
                check(AgentReach.inReach(p.getEyeLocation(),b),"Block beyond 5-block reach ("+
                        Math.round(AgentReach.nearest(p.getEyeLocation(),b)*10)/10.0+" blocks from eyes; max 5.0)");
                check(permitted(p,b,Action.RIGHT_CLICK_BLOCK),"Block use rejected");
                AgentFocus.note(npc, "use", b);
                Openable data=(Openable)b.getBlockData();data.setOpen(!data.isOpen());b.setBlockData(data,true);
                return out("open",data.isOpen(),"material",b.getType().name());
            }
            case "respawn": {
                fields(o,"action","agent");
                return plugin.respawnAtHome(npc,p);
            }
            case "sleep": {
                fields(o,"action","agent","world","x","y","z");Block b=target(p,o,true);
                check(AgentRespawn.isBed(b.getType().name()) || AgentRespawn.isAnchor(b.getType().name()),
                        "Target is not a bed or respawn anchor");
                AgentRespawn.requireUnclaimedOrMine(npc,b);
                boolean set=AgentRespawn.claim(npc,p,b);
                check(set,"Cannot set respawn at this bed or anchor");
                boolean slept=false;
                if(AgentRespawn.isBed(b.getType().name())){
                    try{slept=p.sleep(AgentSleep.headOf(b.getLocation()),false);}
                    catch(RuntimeException e){/* spawn is set; daytime or monsters only block sleeping */}
                    if(slept) AgentSleep.markAsleep(npc, b.getLocation());
                }
                Map<String,Object> r=out("sleeping",slept,"respawnSet",true,"respawnPoint",AgentRespawn.view(npc,p));
                if(!slept){
                    String why=sleepBlockReason(p,b);
                    r.put("reason",why);
                    r.put("note","Respawn point set; could not enter the bed ("+why+")");
                }
                return r;
            }
            case "eat": {
                fields(o, List.of("item"), "action", "agent");
                Material wanted=null;
                if(o.has("item")) wanted=material(str(o,"item"));
                if(wanted!=null) {
                    ItemStack probe=new ItemStack(wanted);
                    check(probe.hasData(DataComponentTypes.FOOD),"Not a food item: "+wanted.name());
                }
                int slot=foodSlot(p,wanted);
                if(slot<0) throw new IllegalArgumentException(wanted==null?"No food in inventory":"No "+wanted.name()+" in inventory");
                if(slot<=8) p.getInventory().setHeldItemSlot(slot);
                ItemStack hand=p.getInventory().getItem(slot);
                check(hand!=null && !hand.getType().isAir() && hand.hasData(DataComponentTypes.FOOD),"No food in inventory");
                FoodProperties food=hand.getData(DataComponentTypes.FOOD);
                check(food!=null,"Food data is missing");
                check(AgentHunger.mayEat(p.getFoodLevel(), food.canAlwaysEat()),"Food level is full");
                AgentAutonomy.clearStaleItemUse(p);
                PlayerItemConsumeEvent event=new PlayerItemConsumeEvent(p,hand.clone(),EquipmentSlot.HAND);
                Bukkit.getPluginManager().callEvent(event);
                check(!event.isCancelled(),"Food consumption rejected");
                int targetFood=Math.min(20,p.getFoodLevel()+food.nutrition());
                FoodLevelChangeEvent foodChange=new FoodLevelChangeEvent(p,targetFood,hand.clone());
                Bukkit.getPluginManager().callEvent(foodChange);
                check(!foodChange.isCancelled(),"Hunger change rejected");
                p.setFoodLevel(Math.min(20,Math.max(0,foodChange.getFoodLevel())));
                p.setSaturation(Math.min(p.getFoodLevel(),p.getSaturation()+food.saturation()));
                applyConsumeEffects(p,hand);
                ItemStack next=hand.clone();next.setAmount(hand.getAmount()-1);
                p.getInventory().setItem(slot, next.getAmount()>0?next:null);
                ItemStack replacement=event.getReplacement();
                if(replacement==null) {
                    Material empty=hand.getType().getCraftingRemainingItem();
                    if(empty!=null && empty!=Material.AIR)replacement=new ItemStack(empty);
                }
                if(replacement!=null && !replacement.getType().isAir()) {
                    Map<Integer,ItemStack> excess=p.getInventory().addItem(replacement.clone());
                    for(ItemStack lost:excess.values())p.getWorld().dropItem(p.getLocation(),lost);
                }
                return out("food",p.getFoodLevel(),"saturation",p.getSaturation(),"eaten",hand.getType().name(),"slot",slot);
            }
            case "till": {
                fields(o,"action","agent","world","x","y","z");
                Block b=target(p,o,true);
                check(b.getType()==Material.DIRT || b.getType()==Material.GRASS_BLOCK || b.getType()==Material.DIRT_PATH || b.getType()==Material.COARSE_DIRT,
                        "Block is not tillable soil");
                ItemStack hoe=p.getInventory().getItemInMainHand();
                check(hoe!=null && hoe.getType().name().endsWith("_HOE"),"Hold a hoe");
                check(b.getRelative(0,1,0).isEmpty(),"Farmland space obstructed");
                check(permitted(p,b,Action.RIGHT_CLICK_BLOCK),"Soil interaction rejected");
                b.setType(b.getType()==Material.COARSE_DIRT?Material.DIRT:Material.FARMLAND,true);
                p.getInventory().setItemInMainHand(hoe.damage(1,p));
                return out("soil",b.getType().name());
            }
            case "plant": {
                fields(o, List.of("slot", "item", "material"), "action", "agent", "world", "x", "y", "z");
                Block clicked=target(p,o,false);
                check(AgentReach.inReach(p.getEyeLocation(),clicked),"Plant position out of reach ("+
                        Math.round(AgentReach.nearest(p.getEyeLocation(),clicked)*10)/10.0+" blocks from eyes; max 5.0)");
                if(o.has("slot")) {
                    int slot=num(o,"slot",0,8);
                    p.getInventory().setHeldItemSlot(slot);
                }
                if(o.has("item")||o.has("material")) {
                    String raw=o.has("item")?str(o,"item"):str(o,"material");
                    Material wanted=material(raw);
                    check(AgentPlant.plantable(wanted.name()), AgentPlant.unsupportedHeld(wanted.name()));
                    selectOwned(p, wanted);
                }
                boolean soilClick=AgentPlant.isSoil(clicked.getType().name());
                Block cropCell=soilClick?clicked.getRelative(0,1,0):clicked;
                boolean cropEmpty=cropCell.isEmpty()||AgentPlacement.replaceable(cropCell.getType().name(),cropCell.isReplaceable());
                check(cropEmpty, AgentPlant.occupiedError(cropCell.getType().name(),
                        cropCell.getX(),cropCell.getY(),cropCell.getZ(),soilClick));
                ItemStack hand=p.getInventory().getItemInMainHand();
                check(hand!=null && hand.getAmount()>0,"Hold seeds or planting material (select the seed slot first)");
                String cropName=AgentPlant.cropOf(hand.getType().name());
                check(cropName!=null, AgentPlant.unsupportedHeld(hand.getType().name()));
                Material crop=Material.matchMaterial(cropName);
                check(crop!=null && crop.isBlock(), AgentPlant.unsupportedHeld(hand.getType().name()));
                Material soil=cropCell.getRelative(0,-1,0).getType();
                check(AgentPlant.soilOk(cropName, soil.name()), AgentPlant.soilError(cropName));
                check(permitted(p,cropCell.getRelative(0,-1,0),Action.RIGHT_CLICK_BLOCK),"Planting was rejected");
                BlockState old=cropCell.getState();cropCell.setType(crop,false);
                try{AgentProtection.requirePlace(p,cropCell,AgentProtection.placeEvent(cropCell,old,cropCell.getRelative(0,-1,0),hand,p));}
                catch(IllegalArgumentException rejected){old.update(true,false);throw rejected;}
                ItemStack left=hand.clone();left.setAmount(hand.getAmount()-1);
                p.getInventory().setItemInMainHand(left.getAmount()>0?left:null);
                return out("planted",crop.name(),"x",cropCell.getX(),"y",cropCell.getY(),"z",cropCell.getZ(),
                        "soil",soil.name());
            }
            case "heightmap": {
                fields(o, List.of("radius", "x", "z"), "action", "agent");
                return AgentPerception.heightmapLive(p, o);
            }
            case "walkmap": {
                fields(o, List.of("radius", "ymin", "ymax", "dig"), "action", "agent");
                return AgentPerception.walkmapLive(p, o);
            }
            case "scan_box": case "region": {
                fields(o, List.of("fromX", "fromY", "fromZ", "toX", "toY", "toZ"),
                        "action", "agent", "minX", "minY", "minZ", "maxX", "maxY", "maxZ");
                return AgentPerception.scanBoxLive(p, o);
            }
            case "build_plan": case "preflight": {
                fields(o, List.of("mode", "plan", "block", "item", "material", "facing", "direction", "half",
                                "mirror", "originX", "originZ", "flipFacing",
                                "fromX", "fromY", "fromZ", "toX", "toY", "toZ", "x", "y", "z"),
                        "action", "agent");
                return AgentBuildPlan.live(p, npc, o);
            }
            case "time": {
                fields(o, "action", "agent");
                return AgentWorldTime.view(p.getWorld());
            }
            case "survey": {
                fields(o, List.of("radius"), "action", "agent");
                Map<String,Object> surveyed = AgentSurvey.surveyLive(p, o, npc);
                Object stored = npc.data().get(keys(npc, "worldmap"), "");
                String prev = stored == null ? "" : String.valueOf(stored);
                if ("null".equals(prev)) prev = "";
                String next = AgentWorldMap.remember(prev, p.getWorld().getName(), surveyed);
                npc.data().setPersistent(keys(npc, "worldmap"), next);
                surveyed.put("mappedRooms", AgentWorldMap.roomCount(next));
                return surveyed;
            }
            case "worldmap": {
                fields(o, List.of("mode", "from", "to"), "action", "agent");
                String mode = o.has("mode") ? str(o, "mode") : "status";
                check(mode.equals("status") || mode.equals("clear"), "worldmap mode must be status or clear");
                if (mode.equals("clear")) {
                    npc.data().setPersistent(keys(npc, "worldmap"), "");
                    return out("cleared", true, "rooms", 0);
                }
                Object stored = npc.data().get(keys(npc, "worldmap"), "");
                String json = stored == null || "null".equals(String.valueOf(stored)) ? "" : String.valueOf(stored);
                String from = o.has("from") ? str(o, "from") : null;
                String to = o.has("to") ? str(o, "to") : null;
                Location at = p.getLocation();
                return AgentWorldMap.status(json, at.getWorld().getName(), at.getBlockX(), at.getBlockY(), at.getBlockZ(), from, to);
            }
            case "map": return AgentBehaviors.mapAction(npc,p,o);
            case "harvest": {
                fields(o,"action","agent","world","x","y","z");
                Block log=target(p,o,false);
                if(org.bukkit.Tag.LOGS.isTagged(log.getType())){
                    check(log.getLocation().add(.5,.5,.5).distanceSquared(p.getLocation())<=32*32,"Tree beyond 32 blocks");
                    AgentFocus.note(npc, "harvest", log);
                    AgentBehaviors.cancel(npc);
                    AgentSelfDefense.release(npc);
                    Map<String,Object> r=AgentBehaviors.startChop(npc,p,log);r.put("note","Chopping connected logs in background; progress via map {mode:status}");return r;
                }
                Block b=target(p,o,true);
                check(b.getBlockData() instanceof org.bukkit.block.data.Ageable,"Target is not an ageable crop");
                org.bukkit.block.data.Ageable age=(org.bukkit.block.data.Ageable)b.getBlockData();
                check(age.getAge()==age.getMaximumAge(),"Crop is not mature");
                AgentFocus.note(npc, "harvest", b);
                Material before=b.getType();
                Map<Material,Integer> have=counts(p);
                check(AgentProtection.breakBlock(p,b),"Crop harvest rejected");
                AgentBehaviors.noteMined(p,before);
                AgentHunger.exhaust(p, AgentHunger.BREAK);
                int picked=AgentsMC.collectNearby(p,8);
                picked+=AgentsMC.collectAround(p,b.getLocation().add(.5,.5,.5),2.5);
                Map<Material,Integer> after=counts(p);
                Map<String,Object> gained=new LinkedHashMap<>();
                for(Map.Entry<Material,Integer> e:after.entrySet()){
                    int extra=e.getValue()-have.getOrDefault(e.getKey(),0);
                    if(extra>0)gained.put(e.getKey().name(),extra);
                }
                return out("harvested",before.name(),"collected",gained,"pickedFromDrops",picked);
            }
            case "whisper": {
                fields(o, List.of("player", "to", "name", "text", "message"), "action", "agent");
                String message=o.has("text")?str(o,"text"):o.has("message")?str(o,"message"):null;
                check(message!=null,"whisper requires text or message");
                check(message.length()<=256 && message.chars().noneMatch(Character::isISOControl),"Invalid message");
                String who=o.has("player")?str(o,"player"):o.has("to")?str(o,"to"):o.has("name")?str(o,"name"):null;
                check(who!=null,"whisper requires player, to, or name");
                throw new IllegalArgumentException("whisper must be handled by the agent body lookup; no recipient named '"+who+"' in the survival-action path");
            }
            case "journal": {
                fields(o,"action","agent","text");String note=str(o,"text");
                check(note.length()<=1024 && note.chars().noneMatch(Character::isISOControl),"Journal note must be printable and <=1024 characters");
                npc.data().setPersistent(keys(npc,"journal"),note);
                return out("saved",true,"characters",note.length());
            }
            case "waypoint": {
                fields(o,"action","agent","name","x","y","z","world");
                String name=str(o,"name");check(name.matches("[A-Za-z0-9_-]{1,24}"),"Invalid waypoint name");
                Block b=target(p,o,false);
                npc.data().setPersistent(keys(npc,"waypoint-"+name),
                        b.getWorld().getName()+":"+b.getX()+","+b.getY()+","+b.getZ());
                return out("name",name,"x",b.getX(),"y",b.getY(),"z",b.getZ());
            }
            default: return AgentFinishActions.execute(plugin, npc, p, o);
        }
    }

    static String daytimeSleepNote(long time) {
        return "daytime (" + AgentWorldTime.time12(time) + "; beds from tick 12542 / ~7:32 PM)";
    }

    static boolean hostileSleep(LivingEntity e) {
        return e instanceof org.bukkit.entity.Monster || e instanceof org.bukkit.entity.Slime
                || e instanceof org.bukkit.entity.Phantom || e instanceof org.bukkit.entity.Ghast
                || e instanceof org.bukkit.entity.Shulker;
    }

    static String sleepBlockReason(Player p, Block bed) {
        if (p == null || p.getWorld() == null) return "bed refused";
        long time = p.getWorld().getTime();
        if (!AgentWorldTime.sleepable(time, p.getWorld().isThundering())) return daytimeSleepNote(time);
        if (bed == null || bed.getWorld() == null) return "vanilla refused the bed (obstruction or occupied)";
        Location at = bed.getLocation().add(0.5, 0.5, 0.5);
        LivingEntity nearest = null;
        double best = 8.01;
        try {
            for (Entity e : bed.getWorld().getNearbyEntities(at, 8, 8, 8)) {
                if (!(e instanceof LivingEntity le) || e instanceof Player) continue;
                if (!hostileSleep(le)) continue;
                double d = le.getLocation().distance(at);
                if (d < best) { best = d; nearest = le; }
            }
        } catch (RuntimeException ignored) {}
        if (nearest != null)
            return nearest.getType().name() + " at " + nearest.getLocation().getBlockX() + ","
                    + nearest.getLocation().getBlockY() + "," + nearest.getLocation().getBlockZ()
                    + " (" + (Math.round(best * 10) / 10.0) + " blocks from the bed)";
        return "vanilla refused the bed (no monster within 8; obstruction or occupied)";
    }

    /** shear / feed / breed a nearby animal by uuid. */
    static Map<String,Object> animal(NPC npc, String action, Player p, JsonObject o) {
        Entity e=entity(p,o,4.5);
        org.bukkit.event.player.PlayerInteractEntityEvent interact=new org.bukkit.event.player.PlayerInteractEntityEvent(p,e,EquipmentSlot.HAND);
        Bukkit.getPluginManager().callEvent(interact);
        check(!interact.isCancelled(),action+" on "+e.getType().name()+" was rejected by server protection");
        if(action.equals("shear")){
            AgentFocus.note(npc, "shear", e);
            check(e instanceof io.papermc.paper.entity.Shearable,e.getType().name()+" cannot be sheared");
            io.papermc.paper.entity.Shearable sh=(io.papermc.paper.entity.Shearable)e;
            check(sh.readyToBeSheared(),e.getType().name()+" is not ready to be sheared (already sheared or a baby)");
            int slot=p.getInventory().first(Material.SHEARS);check(slot>=0,"Shears are required in inventory");
            Map<Material,Integer> before=counts(p);
            sh.shear(net.kyori.adventure.sound.Sound.Source.PLAYER);
            ItemStack shears=p.getInventory().getItem(slot);
            if(shears!=null&&shears.getType()==Material.SHEARS)p.getInventory().setItem(slot,shears.damage(1,p));
            int picked=AgentsMC.collectNearby(p,5.5);
            Map<Material,Integer> after=counts(p);Map<String,Object> gained=new LinkedHashMap<>();
            for(var en:after.entrySet()){int d=en.getValue()-before.getOrDefault(en.getKey(),0);if(d>0&&en.getKey()!=Material.SHEARS)gained.put(en.getKey().name(),d);}
            return out("sheared",e.getUniqueId().toString(),"type",e.getType().name(),"collected",gained,"pickedFromDrops",picked);
        }
        check(e instanceof org.bukkit.entity.Animals,e.getType().name()+" is not a breedable animal");
        org.bukkit.entity.Animals a=(org.bukkit.entity.Animals)e;
        int slot=-1;for(int i=0;i<36;i++){ItemStack s=p.getInventory().getItem(i);if(s!=null&&!s.getType().isAir()&&a.isBreedItem(s)){slot=i;break;}}
        check(slot>=0,"No food this "+e.getType().name()+" accepts in inventory");
        if(action.equals("breed")){check(a.isAdult(),"Animal is a baby and cannot breed yet");}
        if(a.isAdult()){check(a.canBreed(),"Animal cannot breed right now (cooldown)");check(!a.isLoveMode(),"Animal is already in love mode");}
        ItemStack food=p.getInventory().getItem(slot);String used=food.getType().name();
        food.setAmount(food.getAmount()-1);p.getInventory().setItem(slot,food.getAmount()>0?food:null);
        if(a.isAdult()){a.setLoveModeTicks(600);a.setBreedCause(p.getUniqueId());return out("fed",e.getUniqueId().toString(),"food",used,"loveMode",true,"note","Feed a second adult of the same type nearby to breed");}
        int age=a.getAge();a.setAge(Math.min(0,age+(int)Math.ceil(-age/10.0)));
        return out("fed",e.getUniqueId().toString(),"food",used,"baby",true,"age",a.getAge());
    }
    private static Map<Material,Integer> counts(Player p){Map<Material,Integer> m=new java.util.EnumMap<>(Material.class);for(ItemStack s:p.getInventory().getStorageContents())if(s!=null&&!s.getType().isAir())m.merge(s.getType(),s.getAmount(),Integer::sum);return m;}
    static String describeChoice(RecipeChoice choice) {
        if (choice instanceof RecipeChoice.MaterialChoice mats && !mats.getChoices().isEmpty()) {
            StringBuilder s = new StringBuilder();
            int n = 0;
            for (Material m : mats.getChoices()) {
                if (m == null) continue;
                if (n > 0) s.append('|');
                s.append(m.name());
                if (++n >= 3) break;
            }
            if (n > 0) return s.toString();
        }
        if (choice instanceof RecipeChoice.ExactChoice exact && !exact.getChoices().isEmpty()) {
            ItemStack first = exact.getChoices().get(0);
            if (first != null && !first.getType().isAir()) return first.getType().name();
        }
        return "ingredient";
    }

    static int ownedForChoice(Player p, RecipeChoice choice) {
        if (p == null || choice == null) return 0;
        int n = 0;
        try {
            for (ItemStack stack : p.getInventory().getStorageContents())
                if (stack != null && stack.getAmount() > 0 && choice.test(stack)) n += stack.getAmount();
        } catch (RuntimeException ignored) {}
        return n;
    }

    static int ownedNamed(Player p, String need) {
        if (p == null || need == null) return 0;
        int n = 0;
        try {
            for (ItemStack stack : p.getInventory().getStorageContents())
                if (stack != null && stack.getAmount() > 0 && AgentCraft.matchesInput(stack.getType().name(), need))
                    n += stack.getAmount();
        } catch (RuntimeException ignored) {}
        return n;
    }

    private static boolean isFood(ItemStack s){return s!=null && !s.getType().isAir() && s.hasData(DataComponentTypes.FOOD);}
    static final int EAT_TICKS = 32;
    static final long EAT_MS = EAT_TICKS * 50L;
    private static final Map<UUID, Long> lastEatAt = new HashMap<>();

    static boolean readyToEat(long lastAt, long now) {
        return lastAt <= 0 || now - lastAt >= EAT_MS;
    }

    /** Regular food only when hungry; always-edible (gapple) may eat at food 20. */
    static boolean shouldEat(int food, boolean canAlwaysEat) {
        return AgentHunger.mayEat(food, canAlwaysEat);
    }

    static boolean hasEdible(Player p) {
        try {
            int slot = foodSlot(p, null);
            if (slot < 0) return false;
            ItemStack hand = p.getInventory().getItem(slot);
            if (hand == null || hand.getType().isAir() || !hand.hasData(DataComponentTypes.FOOD)) return false;
            FoodProperties food = hand.getData(DataComponentTypes.FOOD);
            return food != null && shouldEat(p.getFoodLevel(), food.canAlwaysEat());
        } catch (RuntimeException e) {
            return false;
        }
    }

    /** Eat one owned food with vanilla-length cooldown; used by protect retreat. */
    static boolean tryEat(Player p) {
        try {
            if (p == null) return false;
            long now = System.currentTimeMillis();
            if (!readyToEat(lastEatAt.getOrDefault(p.getUniqueId(), 0L), now)) return false;
            int slot = foodSlot(p, null);
            if (slot < 0) return false;
            ItemStack hand = p.getInventory().getItem(slot);
            if (hand == null || hand.getType().isAir() || !hand.hasData(DataComponentTypes.FOOD)) return false;
            FoodProperties food = hand.getData(DataComponentTypes.FOOD);
            if (food == null || !shouldEat(p.getFoodLevel(), food.canAlwaysEat())) return false;
            int targetFood = Math.min(20, p.getFoodLevel() + food.nutrition());
            p.setFoodLevel(targetFood);
            p.setSaturation(Math.min(p.getFoodLevel(), p.getSaturation() + food.saturation()));
            applyConsumeEffects(p, hand);
            ItemStack next = hand.clone();
            next.setAmount(hand.getAmount() - 1);
            p.getInventory().setItem(slot, next.getAmount() > 0 ? next : null);
            lastEatAt.put(p.getUniqueId(), now);
            AgentAutonomy.clearStaleItemUse(p);
            return true;
        } catch (RuntimeException ignored) {
            return false;
        }
    }
    private static int foodSlot(Player p, Material wanted) {
        int held=p.getInventory().getHeldItemSlot();
        ItemStack hand=p.getInventory().getItemInMainHand();
        if(isFood(hand) && (wanted==null || hand.getType()==wanted)) return held;
        for(int i=0;i<36;i++){ItemStack s=p.getInventory().getItem(i);if(isFood(s)&&(wanted==null||s.getType()==wanted))return i;}
        return -1;
    }
    private static void applyConsumeEffects(Player p, ItemStack eaten) {
        Consumable consume=eaten.getData(DataComponentTypes.CONSUMABLE);
        if(consume==null) return;
        for(ConsumeEffect effect:consume.consumeEffects()){
            if(effect instanceof ConsumeEffect.ApplyStatusEffects apply){
                if(Math.random()<=apply.probability())
                    for(org.bukkit.potion.PotionEffect pot:apply.effects()) p.addPotionEffect(pot);
            }
        }
    }
    /** Move an owned stack into the held hotbar slot so plant consumes the requested seed. */
    private static void selectOwned(Player p, Material wanted) {
        org.bukkit.inventory.PlayerInventory inv = p.getInventory();
        int held = inv.getHeldItemSlot();
        ItemStack hand = inv.getItem(held);
        if (hand != null && hand.getType() == wanted && hand.getAmount() > 0) return;
        for (int i = 0; i < 9; i++) {
            ItemStack s = inv.getItem(i);
            if (s != null && s.getType() == wanted && s.getAmount() > 0) {
                inv.setHeldItemSlot(i);
                return;
            }
        }
        for (int i = 9; i < 36; i++) {
            ItemStack s = inv.getItem(i);
            if (s != null && s.getType() == wanted && s.getAmount() > 0) {
                ItemStack previous = inv.getItem(held);
                inv.setItem(held, s);
                inv.setItem(i, previous);
                return;
            }
        }
        throw new IllegalArgumentException("No " + wanted.name() + " in inventory");
    }
    private AgentSurvivalActions() {}
}
