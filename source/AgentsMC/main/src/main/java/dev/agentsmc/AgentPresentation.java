package dev.agentsmc;

import java.util.*;
import org.bukkit.Location;
import org.bukkit.attribute.Attribute;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import com.google.gson.JsonObject;
import net.citizensnpcs.api.npc.NPC;

/** Read-only presentation, derived automatically from confirmed actions and real player state. */
final class AgentPresentation {
    private static final Map<UUID, State> states = new HashMap<>();
    private static final Set<String> INVENTORY_ACTIONS = Set.of("inventory","inspect_item","equip","select","select_slot","move_item","swap","swap_items","sort","sort_inventory","drop","pickup","collect","inspect_container","transfer","transfer_item","deposit","withdraw","inspect_interface");
    private static class State {
        long watchedUntil;
        final Deque<Map<String,Object>> motion = new ArrayDeque<>();
        final List<Map<String,Object>> panels = new ArrayList<>();
        final Deque<Map<String,Object>> chat = new ArrayDeque<>();
        long swingAt;
    }
    private static State state(Player p) { return states.computeIfAbsent(p.getUniqueId(), key -> new State()); }
    static Map<String,Object> point(Location l) { return Map.of("x",l.getX(),"y",l.getY(),"z",l.getZ(),"yaw",(double)l.getYaw(),"pitch",(double)l.getPitch()); }
    static Object item(ItemStack item) {
        if(item==null || item.getType().isAir())return null;
        return Map.of("type",item.getType().getKey().toString(),"amount",item.getAmount(),"glint",!item.getEnchantments().isEmpty());
    }
    static List<Object> slots(Player p) {
        List<Object> slots=new ArrayList<>();for(ItemStack item:p.getInventory().getStorageContents())slots.add(item(item));return slots;
    }
    static void watch(Player p) { state(p).watchedUntil=System.currentTimeMillis()+15000; }
    static void sample(Player p) {
        State s=state(p);long now=System.currentTimeMillis();if(s.watchedUntil<now){s.motion.clear();return;}
        Map<String,Object> sample=new LinkedHashMap<>();sample.put("at",now);sample.put("camera",point(p.getEyeLocation()));sample.put("body",point(p.getLocation()));
        sample.put("sneaking",p.isSneaking());sample.put("swimming",p.isSwimming());sample.put("onGround",p.isOnGround());
        sample.put("velocity",Map.of("x",p.getVelocity().getX(),"y",p.getVelocity().getY(),"z",p.getVelocity().getZ()));
        s.motion.addLast(sample);while(s.motion.size()>30)s.motion.removeFirst();
    }
    static List<Map<String,Object>> motion(Player p) { watch(p);sample(p);return List.copyOf(state(p).motion); }
    static void chat(Player p,String sender,String message) {
        State s=state(p);long now=System.currentTimeMillis();s.chat.addLast(Map.of("id",UUID.randomUUID().toString(),"at",now,"expiresAt",now+30000,"sender",sender,"text",message));while(s.chat.size()>8)s.chat.removeFirst();
    }
    static void action(Player p,String action,JsonObject input,Map<String,Object> result) {
        State s=state(p);long now=System.currentTimeMillis();
        if(Set.of("dig","break","place","attack","fight","kill","shoot","harvest","till","plant").contains(action)){s.swingAt=now;p.swingMainHand();}
        if(INVENTORY_ACTIONS.contains(action)){
            List<Object> worn=new ArrayList<>();for(ItemStack armor:p.getInventory().getArmorContents())worn.add(item(armor));worn.add(item(p.getInventory().getItemInOffHand()));
            panel(p,"Inventory",List.of(Map.of("kind","text","text","Armor · offhand"),Map.of("kind","slots","columns",5,"items",worn),Map.of("kind","text","text","Storage · hotbar first"),Map.of("kind","slots","columns",9,"items",slots(p))));
        }
        if(action.equals("craft")) {
            List<Object> widgets=new ArrayList<>();
            widgets.add(Map.of("kind","text","text","Crafted "+result.get("amount")+" × "+input.get("item").getAsString()));
            Object recipe=result.get("viewerRecipe");if(recipe!=null){widgets.add(Map.of("kind","text","text","Ingredients consumed"));widgets.add(recipe);}
            widgets.add(Map.of("kind","text","text","Inventory"));widgets.add(Map.of("kind","slots","columns",9,"items",slots(p)));
            panel(p,"Crafting",widgets);
        }
    }
    private static void panel(Player p,String title,List<?> widgets){long now=System.currentTimeMillis();State s=state(p);s.panels.removeIf(v->v.get("anchor").equals("center"));s.panels.add(Map.of("id",UUID.randomUUID().toString(),"createdAt",now,"expiresAt",now+5000,"anchor","center","title",title,"widgets",widgets));}
    static Map<String,Object> ui(Player p) {
        State s=state(p);long now=System.currentTimeMillis();s.panels.removeIf(v->((Number)v.get("expiresAt")).longValue()<=now);s.chat.removeIf(v->((Number)v.get("expiresAt")).longValue()<=now);
        double maximum=Objects.requireNonNull(p.getAttribute(Attribute.MAX_HEALTH)).getValue();
        double armor=Objects.requireNonNull(p.getAttribute(Attribute.ARMOR)).getValue();
        List<Object> widgets=new ArrayList<>();
        widgets.add(Map.of("kind","bar","label","Health","style","hearts","value",p.getHealth(),"max",maximum));
        widgets.add(Map.of("kind","bar","label","Food","style","food","value",p.getFoodLevel(),"max",20));
        widgets.add(Map.of("kind","bar","label","Armor","style","armor","value",armor,"max",20));
        widgets.add(Map.of("kind","bar","label","XP · level "+p.getLevel(),"style","xp","value",p.getExp(),"max",1));
        widgets.add(Map.of("kind","slots","columns",9,"selected",p.getInventory().getHeldItemSlot(),"items",slots(p).subList(0,9)));
        List<Map<String,Object>> panels=new ArrayList<>();panels.add(Map.of("id","hud","createdAt",0,"expiresAt",0,"anchor","bottom","title","","widgets",widgets));panels.addAll(s.panels);
        return Map.of("panels",panels,"chat",List.copyOf(s.chat),"swingAt",s.swingAt);
    }
    @SuppressWarnings("unchecked")
    static Map<String,Object> recipeWidget(List<Object> consumed){
        Map<String,Map<String,Object>> prototypes=new LinkedHashMap<>();Map<String,Integer> totals=new LinkedHashMap<>();
        for(Object value:consumed){Map<String,Object> item=(Map<String,Object>)value;String key=item.get("type")+":"+item.get("glint");prototypes.put(key,item);totals.merge(key,((Number)item.get("amount")).intValue(),Integer::sum);}
        List<Object> slots=new ArrayList<>();for(var entry:totals.entrySet()){int count=entry.getValue();while(count>0){Map<String,Object> stack=new LinkedHashMap<>(prototypes.get(entry.getKey()));stack.put("amount",Math.min(127,count));slots.add(stack);count-=Math.min(127,count);}}
        return Map.of("kind","slots","columns",3,"items",slots);
    }
    static void remove(UUID id){states.remove(id);}
    private AgentPresentation(){}
}
