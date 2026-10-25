package dev.agentsmc;
import com.google.gson.*;
import java.util.*;
/** Sequenced, acknowledged terrain/field deltas. A new session always starts with a full frame. */
final class AgentViewerDelta {
    private JsonObject acknowledged;private long sequence;
    JsonObject packet(JsonObject frame){
        JsonObject packet=new JsonObject();packet.addProperty("transportVersion",1);packet.addProperty("sequence",sequence+1);
        if(acknowledged==null||!frame.get("world").equals(acknowledged.get("world"))||!frame.get("mode").equals(acknowledged.get("mode"))){packet.addProperty("kind","full");packet.add("frame",frame);return packet;}
        packet.addProperty("kind","delta");packet.addProperty("baseSequence",sequence);JsonObject patch=new JsonObject();
        for(String key:frame.keySet())if(!key.equals("blocks")&&!frame.get(key).equals(acknowledged.get(key)))patch.add(key,frame.get(key));
        Map<String,JsonElement> old=blocks(acknowledged.getAsJsonArray("blocks")),current=blocks(frame.getAsJsonArray("blocks"));JsonArray changed=new JsonArray(),removed=new JsonArray();
        for(var entry:current.entrySet())if(!entry.getValue().equals(old.get(entry.getKey())))changed.add(entry.getValue());
        for(var entry:old.entrySet())if(!current.containsKey(entry.getKey())){JsonObject b=entry.getValue().getAsJsonObject();JsonObject xyz=new JsonObject();for(String k:List.of("x","y","z"))xyz.add(k,b.get(k));removed.add(xyz);}
        packet.add("patch",patch);packet.add("blocks",changed);packet.add("removed",removed);return packet;
    }
    void acknowledge(JsonObject frame){acknowledged=frame.deepCopy();sequence++;}
    void reset(){acknowledged=null;sequence=0;}
    private static Map<String,JsonElement> blocks(JsonArray blocks){Map<String,JsonElement> result=new LinkedHashMap<>();for(JsonElement value:blocks){JsonObject b=value.getAsJsonObject();result.put(b.get("x")+","+b.get("y")+","+b.get("z"),value);}return result;}
}
