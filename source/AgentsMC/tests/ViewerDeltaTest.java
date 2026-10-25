package dev.agentsmc;
import com.google.gson.*;
public final class ViewerDeltaTest {
 public static void main(String[] args){
  AgentViewerDelta delta=new AgentViewerDelta();JsonObject frame=JsonParser.parseString("{\"world\":\"world\",\"mode\":\"first\",\"capturedAt\":1,\"agent\":{\"x\":1},\"blocks\":[{\"x\":0,\"y\":64,\"z\":0,\"type\":\"stone\"},{\"x\":1,\"y\":64,\"z\":0,\"type\":\"dirt\"}]}").getAsJsonObject();
  JsonObject full=delta.packet(frame);check(full.get("kind").getAsString().equals("full"),"Session must start full");
  check(delta.packet(frame).equals(full),"An unacknowledged transmission cannot advance the base");delta.acknowledge(frame);
  JsonObject next=frame.deepCopy();next.addProperty("capturedAt",2);next.getAsJsonObject("agent").addProperty("x",2);next.getAsJsonArray("blocks").remove(0);
  JsonObject packet=delta.packet(next);check(packet.get("kind").getAsString().equals("delta"),"Expected delta");check(packet.getAsJsonArray("blocks").isEmpty(),"Unchanged blocks resent");check(packet.getAsJsonArray("removed").size()==1,"Block removal lost");check(packet.get("baseSequence").getAsLong()==1,"Incorrect base");check(packet.getAsJsonObject("patch").getAsJsonObject("agent").get("x").getAsInt()==2,"Movement lost");
  delta.acknowledge(next);check(delta.packet(next).getAsJsonObject("patch").isEmpty(),"Unchanged fields resent");delta.reset();check(delta.packet(next).get("kind").getAsString().equals("full"),"Resync did not send full state");

  int[] sky=AgentViewerSnapshot.captureBounds(20,316,-20,-64,320);
  check(sky[2]<=sky[3]&&sky[2]>=AgentViewerSnapshot.SCHEMA_MIN_Y&&sky[3]<=AgentViewerSnapshot.SCHEMA_MAX_Y,
          "vanilla ceiling window in schema: "+java.util.Arrays.toString(sky));
  check(sky[3]<=319,"vanilla exclusive maxHeight 320 last block is 319, got "+sky[3]);
  check(sky[3]<=316+AgentViewerSnapshot.ABOVE,"must not extend above body+ABOVE");
  int[] tall=AgentViewerSnapshot.captureBounds(20,316,-20,-2032,2032);
  check(tall[2]>=AgentViewerSnapshot.SCHEMA_MIN_Y&&tall[3]<=AgentViewerSnapshot.SCHEMA_MAX_Y&&tall[2]<=tall[3],
          "tall datapack world must still fit Site Zod: "+java.util.Arrays.toString(tall));
  check(tall[3]<=320,"323 (316+7) is Invalid capture bounds on Site max 320");
  int[] over=AgentViewerSnapshot.captureBounds(0,400,0,-64,320);
  check(over[2]<=over[3]&&over[3]<=319,"body above world pins a legal window: "+java.util.Arrays.toString(over));
  int[] floor=AgentViewerSnapshot.captureBounds(0,-70,0,-64,320);
  check(floor[2]>=-64&&floor[2]<=floor[3],"body below world pins a legal window: "+java.util.Arrays.toString(floor));
  int[] mid=AgentViewerSnapshot.captureBounds(0,64,0,-64,320);
  check(mid[2]==61&&mid[3]==71,"normal window is cy-3..cy+7");

  check(AgentOutboundBackoff.delayMs(0)==0,"no delay before a failure");
  check(AgentOutboundBackoff.delayMs(1)==1_000,"first retry 1s");
  check(AgentOutboundBackoff.delayMs(2)==2_000,"second retry 2s");
  check(AgentOutboundBackoff.delayMs(3)==4_000,"third retry 4s");
  check(AgentOutboundBackoff.delayMs(6)==30_000,"cap 30s");
  AgentOutboundBackoff wait=new AgentOutboundBackoff();
  check(wait.due(0),"fresh backoff is due");
  long delay=wait.fail(10_000);
  check(delay==1_000&&!wait.due(10_500)&&wait.due(11_000),"backoff skips until nextAt");
  wait.ok();
  check(wait.due(11_000)&&wait.failures==0,"success clears backoff");

  System.out.println("PASS: acknowledged viewer sequences, omitted unchanged fields, block removals, explicit full resync, capture bounds clamp, viewer backoff");
 }
 private static void check(boolean value,String message){if(!value)throw new AssertionError(message);}
}
