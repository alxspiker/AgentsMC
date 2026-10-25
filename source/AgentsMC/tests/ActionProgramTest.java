package dev.agentsmc;

import com.google.gson.JsonParser;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

public final class ActionProgramTest {
    public static void main(String[] args) {
        var program=JsonParser.parseString("{\"steps\":[{\"action\":\"observe\"},{\"action\":\"observe\"},{\"action\":\"observe\"}]}").getAsJsonObject();
        AtomicInteger calls=new AtomicInteger();
        Map<String,Object> stopped=AgentActionProgram.execute(program,"world",(step,index)->{
            calls.incrementAndGet();return index==1?Map.of("ok",false,"action","observe","error","injected failure"):Map.of("ok",true,"action","observe");
        });
        check(calls.get()==2,"Program did not stop on first failed action");
        check(Boolean.FALSE.equals(stopped.get("ok")),"Failed program claimed success");
        var results=(java.util.List<?>)stopped.get("results");
        check(results.size()==3,"Stopped program must still list every step");
        check(((Map<?,?>)results.get(1)).get("step").equals(2),"Failure does not identify its step number");
        check(String.valueOf(((Map<?,?>)results.get(2)).get("error")).contains("was not attempted"),"Skipped step missing reason: "+results.get(2));
        check(!AgentErrors.blank(stopped.get("error")),"Program top-level error was empty: "+stopped);
        check(Boolean.TRUE.equals(stopped.get("partial"))&&Integer.valueOf(1).equals(stopped.get("succeeded"))
                &&Integer.valueOf(1).equals(stopped.get("failed")),
                "partial program counts: "+stopped);
        check(String.valueOf(stopped.get("error")).toLowerCase().contains("partial"),
                "partial program error: "+stopped.get("error"));

        var continuing=JsonParser.parseString("{\"continueOnError\":true,\"steps\":[{\"action\":\"observe\"},{\"action\":\"observe\"},{\"action\":\"observe\"}]}").getAsJsonObject();
        calls.set(0);
        Map<String,Object> continued=AgentActionProgram.execute(continuing,"world",(step,index)->{
            calls.incrementAndGet();return index==1?Map.of("ok",false,"action","observe","error","injected failure"):Map.of("ok",true,"action","observe");
        });
        check(calls.get()==3,"continueOnError did not attempt remaining steps");
        check(((Map<?,?>)((java.util.List<?>)continued.get("results")).get(2)).get("step").equals(3),"Step numbering is not one-based");

        var malformed=JsonParser.parseString("{\"steps\":[{\"action\":\"observe\"},7]}").getAsJsonObject();
        Map<String,Object> invalid=AgentActionProgram.execute(malformed,"world",(step,index)->Map.of("ok",true));
        Map<?,?> failed=(Map<?,?>)((java.util.List<?>)invalid.get("results")).get(1);
        check(Boolean.FALSE.equals(failed.get("ok"))&&failed.get("step").equals(2),"Malformed step did not return an indexed failure");
        java.util.Iterator<String> states=java.util.List.of("navigating","paused","moving","arrived_near").iterator();
        Map<String,Object> near=AgentActionProgram.awaitNavigation(Map.of("ok",true,"action","goto"),()->Map.of("status",states.next()),()->{throw new AssertionError("stopped");},java.util.concurrent.TimeUnit.SECONDS.toNanos(5),1);
        check(Boolean.TRUE.equals(near.get("ok"))&&near.get("state").equals("arrived_near")&&near.get("status").equals("arrived_near"),"arrived_near did not replace the navigating step status: "+near);
        check(!states.hasNext(),"goto step returned before navigation settled");
        Map<String,Object> lost=AgentActionProgram.awaitNavigation(Map.of("ok",true),()->Map.of("status","failed","reason","blocked"),()->{},java.util.concurrent.TimeUnit.SECONDS.toNanos(5),1);
        check(Boolean.FALSE.equals(lost.get("ok"))&&lost.get("error").equals("blocked"),"Failed navigation reported success");
        Map<String,Object> shore=AgentActionProgram.awaitNavigation(Map.of("ok",true),()->Map.of("status","blocked_shore","reason","Shore blocks the remaining water route"),()->{},java.util.concurrent.TimeUnit.SECONDS.toNanos(5),1);
        check(Boolean.FALSE.equals(shore.get("ok"))&&"blocked_shore".equals(shore.get("status")),"blocked_shore must end the goto step");
        boolean[] stopped2={false};
        Map<String,Object> timeout=AgentActionProgram.awaitNavigation(Map.of("ok",true),()->Map.of("status","moving"),()->stopped2[0]=true,0,1);
        check(Boolean.FALSE.equals(timeout.get("ok"))&&stopped2[0],"Timeout did not stop navigation");
        var mixed=JsonParser.parseString("{\"steps\":[{\"action\":\"goto\",\"x\":1,\"y\":64,\"z\":1},{\"action\":\"place\",\"x\":1,\"y\":64,\"z\":2,\"block\":\"DIRT\"}]}").getAsJsonObject();
        Map<String,Object> placed=AgentActionProgram.execute(mixed,"world",(step,index)->Map.of("ok",true,"action",step.get("action").getAsString()));
        check(Boolean.TRUE.equals(placed.get("ok")),"Program rejected place step with block field: "+placed);
        var waitStep=JsonParser.parseString("{\"steps\":[{\"action\":\"wait\",\"seconds\":1},{\"action\":\"observe\"}]}").getAsJsonObject();
        long started=System.currentTimeMillis();
        Map<String,Object> waited=AgentActionProgram.execute(waitStep,"world",(step,index)->{
            if(step.get("action").getAsString().equals("wait"))return Map.of("ok",true,"action","wait","seconds",1);
            return Map.of("ok",true,"action","observe");
        });
        check(Boolean.TRUE.equals(waited.get("ok"))&&System.currentTimeMillis()-started>=900,"wait step did not pause the program");
        check(AgentActionProgram.waitSeconds(Map.of("seconds",12),null)==12,"waitSeconds from result");
        System.out.println("PASS: programs report one-based failed step, stop or continue by policy, isolate malformed steps, and honor wait");
    }
    private static void check(boolean ok,String message){if(!ok)throw new AssertionError(message);}
}
