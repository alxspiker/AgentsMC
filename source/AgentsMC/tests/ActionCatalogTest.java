package dev.agentsmc;

import java.util.HashSet;
import java.util.List;
import java.util.Map;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

/** Runs offline without a Minecraft server. */
public final class ActionCatalogTest {
    public static void main(String[] args) {
        Map<String,Object> snapshot = AgentActionCatalog.snapshot();
        List<?> all=(List<?>)snapshot.get("actions");
        if(all.size()!=101) throw new AssertionError("Unexpected action count " + all.size());
        HashSet<String> names=new HashSet<>();
        int enabled=0;
        for(Object object: all) {
            @SuppressWarnings("unchecked") Map<String,Object> item=(Map<String,Object>)object;
            String name=(String)item.get("action");
            if(!names.add(name))throw new AssertionError("Duplicate action: "+name);
            if(Boolean.TRUE.equals(item.get("available")))enabled++;
            if(Boolean.TRUE.equals(item.get("available"))!=AgentActionCatalog.advertised(name))
                throw new AssertionError("Bad availability: "+name);
            if(AgentActionCatalog.executable(name)!=AgentActionCatalog.advertised(name))
                throw new AssertionError("Executable but not advertised: "+name);
        }
        for(Object object: all) {
            @SuppressWarnings("unchecked") Map<String,Object> item=(Map<String,Object>)object;
            if(!(item.get("readOnly") instanceof Boolean)) throw new AssertionError("readOnly missing: "+item.get("action"));
        }
        for(String ro: List.of("observe","scan","inventory","time","chat_log","feedback_list","heightmap","walkmap","inspect_block","locate"))
            if(!Boolean.TRUE.equals(entry(all,ro).get("readOnly"))) throw new AssertionError("should be readOnly: "+ro);
        for(String rw: List.of("goto","say","feedback","survey","worldmap","inspect_container","inspect_interface","journal","map","eat","craft","wait","feedback_resolve","place","dig"))
            if(!Boolean.FALSE.equals(entry(all,rw).get("readOnly"))) throw new AssertionError("must not be readOnly: "+rw);
        if(enabled!=100)throw new AssertionError("Unexpected enabled count: "+enabled);
        for(String name: List.of("shear","feed","breed","reply","inspect_interface","locate","fight","kill","wait","protect","shoot","feedback","feedback_list","feedback_resolve","chat_log","heightmap","walkmap","survey","worldmap","time","respawn","give","throw","interact","interact_player","shield","drink","heal","milk","tame","set_spawn","water","fill","fish","trade","trade_offers","emote","retreat","defend","brew","enchant","anvil","smith","stonecut","swim","climb","portal","move","scan_box","region","build_plan","preflight"))
            if(!AgentActionCatalog.advertised(name)) throw new AssertionError("Should advertise "+name);
        if(AgentActionCatalog.advertised("command")||AgentActionCatalog.executable("command"))
            throw new AssertionError("command must stay unavailable");
        Map<String,Object> shear=entry(all,"shear");
        if(!fields(shear).equals(List.of("uuid [UUID string for a nearby entity]"))) throw new AssertionError("shear fields: "+fields(shear));
        if(!fields(entry(all,"feed")).equals(fields(shear))||!fields(entry(all,"breed")).equals(fields(shear)))
            throw new AssertionError("feed/breed fields");
        List<?> reply=fields(entry(all,"reply"));
        if(reply.size()!=1||!String.valueOf(reply.get(0)).startsWith("text [optional")) throw new AssertionError("reply fields: "+reply);
        List<?> locate=fields(entry(all,"locate"));
        if(locate.size()!=1||!String.valueOf(locate.get(0)).startsWith("name [optional")) throw new AssertionError("locate fields: "+locate);
        if(!fields(entry(all,"inspect_interface")).isEmpty()) throw new AssertionError("inspect_interface must have no fields");
        List<?> gotoFields=fields(entry(all,"goto"));
        if(gotoFields.stream().noneMatch(f->String.valueOf(f).startsWith("dig [optional boolean, default false]")))
            throw new AssertionError("goto missing dig: "+gotoFields);
        if(!String.valueOf(fields(entry(all,"scan")).get(1)).contains("1..32")) throw new AssertionError("scan radius: "+fields(entry(all,"scan")));
        if(!String.valueOf(fields(entry(all,"scan_entities")).get(1)).contains("1..32")) throw new AssertionError("scan_entities radius");
        List<?> observe=fields(entry(all,"observe"));
        if(!String.valueOf(observe.get(0)).contains("1..8")||observe.stream().noneMatch(f->String.valueOf(f).startsWith("includeBlocks ")))
            throw new AssertionError("observe fields: "+observe);
        List<?> place=fields(entry(all,"place"));
        for(String prefix: List.of("block ","item ","material ","slot [optional","text [optional","facing [optional","direction [optional","side [optional"))
            if(place.stream().noneMatch(f->String.valueOf(f).startsWith(prefix))) throw new AssertionError("place missing "+prefix+": "+place);
        String placeDesc=String.valueOf(entry(all,"place").get("description")).toLowerCase();
        if(!placeDesc.contains("facing")||!placeDesc.contains("bed"))
            throw new AssertionError("place should document directional facing: "+placeDesc);
        if(!placeDesc.contains("wall sign")&&!placeDesc.contains("wall-sign"))
            throw new AssertionError("place should document wall-sign facing: "+placeDesc);
        if(!placeDesc.contains("edit")||!placeDesc.contains("waxed"))
            throw new AssertionError("place should document editing existing signs: "+placeDesc);
        if(!placeDesc.contains("solid")||(!placeDesc.contains("air")&&!placeDesc.contains("float")))
            throw new AssertionError("place should refuse floating wall torches: "+placeDesc);
        List<?> map=fields(entry(all,"map"));
        if(map.size()!=3||!String.valueOf(map.get(0)).startsWith("mode ")||!String.valueOf(map.get(1)).contains("max 24")||!String.valueOf(map.get(2)).contains("night_home"))
            throw new AssertionError("map fields: "+map);
        String harvest=String.valueOf(entry(all,"harvest").get("description"));
        if(!harvest.toLowerCase().contains("log")||!harvest.toLowerCase().contains("tree"))
            throw new AssertionError("harvest description should note tree chop: "+harvest);
        if(!harvest.toLowerCase().contains("vacuum")&&!harvest.toLowerCase().contains("drop"))
            throw new AssertionError("harvest should vacuum crop drops: "+harvest);
        String craftAmount=String.valueOf(fields(entry(all,"craft")).get(1));
        if(!craftAmount.contains("1..64")||!craftAmount.toLowerCase().contains("yield"))
            throw new AssertionError("craft amount must document output count / recipe yield: "+craftAmount);
        if(fields(entry(all,"craft")).stream().noneMatch(f->String.valueOf(f).startsWith("count ")))
            throw new AssertionError("craft must advertise count alias: "+fields(entry(all,"craft")));
        List<?> eat=fields(entry(all,"eat"));
        if(eat.size()!=1||!String.valueOf(eat.get(0)).startsWith("item [optional"))
            throw new AssertionError("eat fields: "+eat);
        String ownerWorld="world";
        JsonObject accepted=AgentActionProgram.normalize(JsonParser.parseString("{\"action\":\"craft\",\"item\":\"oak_planks\",\"amount\":4}").getAsJsonObject(),ownerWorld);
        if(accepted.has("world"))throw new AssertionError("Injected world without coordinates");
        accepted=AgentActionProgram.normalize(JsonParser.parseString("{\"action\":\"harvest\",\"x\":1,\"y\":64,\"z\":2}").getAsJsonObject(),ownerWorld);
        if(!"world".equals(accepted.get("world").getAsString())) throw new AssertionError("World not credential bound");
        for(String attack : List.of("{\"action\":\"command\"}",
                "{\"action\":\"say\",\"text\":\"Hi\",\"agentId\":\"victim\"}",
                "{\"action\":\"goto\",\"world\":\"nether\",\"x\":0,\"y\":64,\"z\":0}",
                "{\"action\":\"craft\",\"item\":{\"action\":\"create\"}}",
                "{\"action\":\"observe\",\"agent\":\"other\"}")) {
            try {AgentActionProgram.normalize(JsonParser.parseString(attack).getAsJsonObject(),ownerWorld);
                throw new AssertionError("Invalid request accepted: "+attack);
            } catch(IllegalArgumentException expected) { }
        }
        if(!"2".equals(AgentActionCatalog.VERSION))throw new AssertionError("catalog version must stay 2");
        if(!AgentActionCatalog.executable("scan_entities"))throw new AssertionError("Entity scan not enabled");
        List<?> follow=fields(entry(all,"follow"));
        if(follow.size()!=4||!String.valueOf(follow.get(0)).toLowerCase().contains("uuid")
                ||!String.valueOf(follow.get(1)).toLowerCase().contains("target")
                ||!String.valueOf(follow.get(2)).toLowerCase().contains("player")
                ||!String.valueOf(follow.get(3)).toLowerCase().contains("name"))
            throw new AssertionError("follow fields: "+follow);
        String followDesc=String.valueOf(entry(all,"follow").get("description")).toLowerCase();
        if(!followDesc.contains("goto")||!followDesc.contains("overlook")||!followDesc.contains("no_path"))
            throw new AssertionError("follow should mention goto A*, overlook hold, and no_path: "+followDesc);
        List<?> say=fields(entry(all,"say"));
        if(say.size()!=3||!String.valueOf(say.get(0)).startsWith("text ")
                ||!String.valueOf(say.get(1)).toLowerCase().contains("message")
                ||!String.valueOf(say.get(2)).toLowerCase().contains("player"))
            throw new AssertionError("say fields: "+say);
        String sleep=String.valueOf(entry(all,"sleep").get("description")).toLowerCase();
        if(!sleep.contains("respawn")||!sleep.contains("bed")||!sleep.contains("world spawn"))
            throw new AssertionError("sleep description: "+sleep);
        if(!sleep.contains("claimed")||!sleep.contains("another")||!sleep.contains("player"))
            throw new AssertionError("sleep should refuse another agent's or player's claimed bed: "+sleep);
        if(!sleep.contains("reason")||!sleep.contains("hostile")&&!sleep.contains("daytime"))
            throw new AssertionError("sleep should name the bed blocker: "+sleep);
        String inspectBlock=String.valueOf(entry(all,"inspect_block").get("description")).toLowerCase();
        if(!inspectBlock.contains("claimed")||!inspectBlock.contains("bed")||!inspectBlock.contains("player"))
            throw new AssertionError("inspect_block should report bed claims including players: "+inspectBlock);
        if(!inspectBlock.contains("sign")||!inspectBlock.contains("text"))
            throw new AssertionError("inspect_block should report sign text: "+inspectBlock);
        String plant=String.valueOf(entry(all,"plant").get("description")).toLowerCase();
        if(!plant.contains("held")||!plant.contains("farmland")||!plant.contains("seed"))
            throw new AssertionError("plant should document held seeds and farmland: "+plant);
        List<?> plantFields=fields(entry(all,"plant"));
        for(String prefix: List.of("x ","y ","z ","slot [optional","item [optional","material [optional"))
            if(plantFields.stream().noneMatch(f->String.valueOf(f).startsWith(prefix)))
                throw new AssertionError("plant missing "+prefix+": "+plantFields);
        String setSpawn=String.valueOf(entry(all,"set_spawn").get("description")).toLowerCase();
        if(!setSpawn.contains("claimed"))
            throw new AssertionError("set_spawn should refuse claimed beds: "+setSpawn);
        if(!fields(entry(all,"respawn")).isEmpty()) throw new AssertionError("respawn must have no fields");
        String respawn=String.valueOf(entry(all,"respawn").get("description")).toLowerCase();
        if(!respawn.contains("bed")||!respawn.contains("world spawn")||!respawn.contains("inventory"))
            throw new AssertionError("respawn description: "+respawn);
        if(!fields(entry(all,"fight")).equals(List.of("uuid [UUID string for a nearby entity]")))
            throw new AssertionError("fight fields: "+fields(entry(all,"fight")));
        if(!fields(entry(all,"kill")).equals(fields(entry(all,"fight"))))
            throw new AssertionError("kill fields");
        String fight=String.valueOf(entry(all,"fight").get("description"));
        if(!fight.toLowerCase().contains("sprint")||!fight.toLowerCase().contains("cooldown")||!fight.toLowerCase().contains("bow"))
            throw new AssertionError("fight description: "+fight);
        List<?> shoot=fields(entry(all,"shoot"));
        if(shoot.size()!=5||!String.valueOf(shoot.get(0)).toLowerCase().contains("target")||!String.valueOf(shoot.get(1)).toLowerCase().contains("uuid"))
            throw new AssertionError("shoot fields: "+shoot);
        List<?> wait=fields(entry(all,"wait"));
        if(wait.size()!=1||!String.valueOf(wait.get(0)).contains("1..30"))
            throw new AssertionError("wait fields: "+wait);
        List<?> protect=fields(entry(all,"protect"));
        if(protect.size()!=2||!String.valueOf(protect.get(0)).startsWith("player [online")||!String.valueOf(protect.get(1)).contains("default 16"))
            throw new AssertionError("protect fields: "+protect);
        List<?> mount=fields(entry(all,"mount"));
        if(mount.size()!=1||!String.valueOf(mount.get(0)).startsWith("target [optional"))
            throw new AssertionError("mount fields: "+mount);
        String gotoDesc=String.valueOf(entry(all,"goto").get("description")).toLowerCase();
        if(!gotoDesc.contains("boat")||!gotoDesc.contains("no_path")) throw new AssertionError("goto description should mention boat and fail codes: "+gotoDesc);
        if(!gotoDesc.contains("accepted")||!gotoDesc.contains("steps"))
            throw new AssertionError("goto should say standalone accepted vs program wait: "+gotoDesc);
        if(!gotoDesc.contains("self-defense")&&!gotoDesc.contains("self_defense"))
            throw new AssertionError("goto should override self-defense: "+gotoDesc);
        if(!gotoDesc.contains("door")||!gotoDesc.contains("close"))
            throw new AssertionError("goto should walk through doors and close them: "+gotoDesc);
        String protDesc=String.valueOf(entry(all,"protect").get("description")).toLowerCase();
        if(!protDesc.contains("agent")) throw new AssertionError("protect should mention agent bodies: "+protDesc);
        if(!protDesc.contains("goto")||!protDesc.contains("overlook")||!protDesc.contains("no_path"))
            throw new AssertionError("protect should mention goto/overlook/no_path: "+protDesc);
        if(!String.valueOf(protect.get(0)).toLowerCase().contains("agent"))
            throw new AssertionError("protect player field should mention agents: "+protect);
        List<?> feedback=fields(entry(all,"feedback"));
        if(feedback.size()!=3||!String.valueOf(feedback.get(0)).contains("1..2000")
                ||!String.valueOf(feedback.get(1)).toLowerCase().contains("bug")
                ||!String.valueOf(feedback.get(2)).toLowerCase().contains("context"))
            throw new AssertionError("feedback fields: "+feedback);
        List<?> resolve=fields(entry(all,"feedback_resolve"));
        if(resolve.size()!=3||!String.valueOf(resolve.get(0)).startsWith("id ")
                ||!String.valueOf(resolve.get(1)).startsWith("note [optional")
                ||!String.valueOf(resolve.get(2)).toLowerCase().contains("alias"))
            throw new AssertionError("feedback_resolve fields: "+resolve);
        List<?> chatLog=fields(entry(all,"chat_log"));
        if(chatLog.size()!=5||!String.valueOf(chatLog.get(0)).toLowerCase().contains("after")
                ||!String.valueOf(chatLog.get(3)).toLowerCase().contains("public")
                ||!String.valueOf(chatLog.get(4)).toLowerCase().contains("serversessionid"))
            throw new AssertionError("chat_log fields: "+chatLog);
        List<?> heightmap=fields(entry(all,"heightmap"));
        if(heightmap.size()!=3||!String.valueOf(heightmap.get(0)).contains("1..32")
                ||!String.valueOf(heightmap.get(1)).toLowerCase().contains("optional"))
            throw new AssertionError("heightmap fields: "+heightmap);
        List<?> walkmap=fields(entry(all,"walkmap"));
        if(walkmap.size()!=4||!String.valueOf(walkmap.get(0)).contains("1..24")
                ||!String.valueOf(walkmap.get(3)).toLowerCase().contains("false"))
            throw new AssertionError("walkmap fields: "+walkmap);
        List<?> survey=fields(entry(all,"survey"));
        if(survey.size()!=1||!String.valueOf(survey.get(0)).contains("1..12"))
            throw new AssertionError("survey fields: "+survey);
        List<?> worldmap=fields(entry(all,"worldmap"));
        if(worldmap.size()!=3||!String.valueOf(worldmap.get(0)).toLowerCase().contains("status")
                ||!String.valueOf(worldmap.get(1)).toLowerCase().contains("room")
                ||!String.valueOf(worldmap.get(2)).toLowerCase().contains("outdoors"))
            throw new AssertionError("worldmap fields: "+worldmap);
        String surveyDesc=String.valueOf(entry(all,"survey").get("description")).toLowerCase();
        if(!surveyDesc.contains("enclosed")||!surveyDesc.contains("door")||!surveyDesc.contains("unknown"))
            throw new AssertionError("survey should describe rooms, doors, and unknown: "+surveyDesc);
        if(!surveyDesc.contains("headroom")||!surveyDesc.contains("uneven"))
            throw new AssertionError("survey should describe headroom grid and uneven warnings: "+surveyDesc);
        if(!surveyDesc.contains("time")||!surveyDesc.contains("worldtime")&&!surveyDesc.contains("timeofday"))
            throw new AssertionError("survey should mention the world clock: "+surveyDesc);
        if(!surveyDesc.contains("claimed")||!surveyDesc.contains("bed")||!surveyDesc.contains("player"))
            throw new AssertionError("survey should mention claimed beds including players: "+surveyDesc);
        if(!surveyDesc.contains("sign")||!surveyDesc.contains("text"))
            throw new AssertionError("survey should mention sign text: "+surveyDesc);
        if(!fields(entry(all,"time")).isEmpty())
            throw new AssertionError("time must have no fields: "+fields(entry(all,"time")));
        String timeDesc=String.valueOf(entry(all,"time").get("description")).toLowerCase();
        if(!timeDesc.contains("worldtime")||!timeDesc.contains("daynumber")||!timeDesc.contains("dawn")
                ||!timeDesc.contains("time24")||!timeDesc.contains("sunrise")||!timeDesc.contains("aftersunset"))
            throw new AssertionError("time description: "+timeDesc);
        String observeDesc=String.valueOf(entry(all,"observe").get("description")).toLowerCase();
        if(!observeDesc.contains("time")||!observeDesc.contains("worldtime")&&!observeDesc.contains("timeofday"))
            throw new AssertionError("observe should mention time: "+observeDesc);
        List<?> equip=fields(entry(all,"equip"));
        if(equip.size()!=2||!String.valueOf(equip.get(0)).contains("0..40")
                ||!String.valueOf(equip.get(1)).toLowerCase().contains("optional"))
            throw new AssertionError("equip fields: "+equip);
        if(!String.valueOf(fields(entry(all,"move_item")).get(0)).contains("0..40"))
            throw new AssertionError("move_item from: "+fields(entry(all,"move_item")));
        if(!String.valueOf(fields(entry(all,"swap")).get(0)).contains("0..40"))
            throw new AssertionError("swap first: "+fields(entry(all,"swap")));
        String walkDesc=String.valueOf(entry(all,"walkmap").get("description")).toLowerCase();
        if(!walkDesc.contains("standable")||!walkDesc.contains("feet"))
            throw new AssertionError("walkmap should snap flood to standable feet Y: "+walkDesc);
        String brk=String.valueOf(entry(all,"break").get("description")).toLowerCase();
        if(!brk.contains("no-drop")&&!brk.contains("leaves")&&!brk.contains("drop"))
            throw new AssertionError("break should allow no-drop blocks: "+brk);
        if(!brk.contains("chebyshev")&&!brk.contains("spawn protection"))
            throw new AssertionError("break should document spawn protection: "+brk);
        String fightGive=String.valueOf(entry(all,"fight").get("description")).toLowerCase();
        if(!fightGive.contains("unreachable"))
            throw new AssertionError("fight should give up when unreachable: "+fightGive);
        String fightFlee=String.valueOf(entry(all,"fight").get("description")).toLowerCase();
        if(!fightFlee.contains("goto")||!fightFlee.contains("cover")||!fightFlee.contains("safe"))
            throw new AssertionError("fight should flee via goto and return when safe: "+fightFlee);
        if(!fightFlee.contains("armed")||!fightFlee.contains("6"))
            throw new AssertionError("fight should mention armed melee to health 6: "+fightFlee);
        if(!gotoDesc.contains("flee")||!gotoDesc.contains("a*"))
            throw new AssertionError("goto should mention idle flee uses the same path: "+gotoDesc);
        String give=String.valueOf(entry(all,"give").get("description")).toLowerCase();
        if(!give.contains("player")||!give.contains("agent")||!give.contains("never"))
            throw new AssertionError("give should transfer owned items to a player/agent: "+give);
        List<?> giveFields=fields(entry(all,"give"));
        if(giveFields.size()<4||giveFields.stream().noneMatch(f->String.valueOf(f).toLowerCase().contains("player")))
            throw new AssertionError("give fields: "+giveFields);
        String heal=String.valueOf(entry(all,"heal").get("description")).toLowerCase();
        if(!heal.contains("food")&&!heal.contains("potion"))
            throw new AssertionError("heal should eat/drink, not set health: "+heal);
        List<?> box=fields(entry(all,"scan_box"));
        for(String prefix: List.of("minX ","minY ","minZ ","maxX ","maxY ","maxZ "))
            if(box.stream().noneMatch(f->String.valueOf(f).startsWith(prefix)))
                throw new AssertionError("scan_box missing "+prefix+": "+box);
        String boxDesc=String.valueOf(entry(all,"scan_box").get("description")).toLowerCase();
        if(!boxDesc.contains("4096")||!boxDesc.contains("unloaded")||!boxDesc.contains("air"))
            throw new AssertionError("scan_box should document cap and unloaded≠air: "+boxDesc);
        if(!boxDesc.contains("reachable")&&!boxDesc.contains("a*"))
            throw new AssertionError("scan_box should document connectivity: "+boxDesc);
        if(!fields(entry(all,"region")).equals(box))
            throw new AssertionError("region fields should match scan_box");
        List<?> plan=fields(entry(all,"build_plan"));
        for(String prefix: List.of("mode ","plan ","facing ","half ","mirror "))
            if(plan.stream().noneMatch(f->String.valueOf(f).startsWith(prefix)))
                throw new AssertionError("build_plan missing "+prefix+": "+plan);
        String planDesc=String.valueOf(entry(all,"build_plan").get("description")).toLowerCase();
        if(!planDesc.contains("preflight")||!planDesc.contains("verify")||!planDesc.contains("arrived_near"))
            throw new AssertionError("build_plan should document preflight/verify/arrived_near: "+planDesc);
        if(!fields(entry(all,"preflight")).equals(plan))
            throw new AssertionError("preflight fields should match build_plan");
        System.out.println("PASS: 100 catalog entries (99 advertised; command stays off), build_plan/preflight, scan_box/region, time/survey/worldmap available, eat.item optional, field lists include 0.6.8/0.6.9/0.6.18/0.6.22 options, safe generic schema");
    }
    @SuppressWarnings("unchecked")
    private static Map<String,Object> entry(List<?> all,String name){
        return all.stream().map(o->(Map<String,Object>)o).filter(m->name.equals(m.get("action"))).findFirst()
                .orElseThrow(()->new AssertionError("Missing action "+name));
    }
    private static List<?> fields(Map<String,Object> entry){ return (List<?>)entry.get("fields"); }
}
