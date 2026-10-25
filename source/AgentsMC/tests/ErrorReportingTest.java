package dev.agentsmc;

import com.google.gson.JsonParser;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** 0.6.9: every failure carries a non-empty error, inbox, and STUCK escape search. Offline. */
public final class ErrorReportingTest {
    @SuppressWarnings("unchecked") static <T> T genericGet(Object v) { return (T) v; }
    public static void main(String[] args) {
        // 0.6.11 / 0.6.28: String.valueOf(<generic T getter>) compiles to valueOf(char[]) and
        // throws ClassCastException String -> [C. That was say/harvest after emit/pickup.
        try { AgentErrors.text(genericGet("agent-1")); } catch (ClassCastException e) { throw new AssertionError("generic getter stringify CCE", e); }
        check(AgentErrors.text(genericGet("agent-1")).equals("agent-1"), "text()");
        check(AgentErrors.text(null).equals("null"), "text(null)");
        check(AgentErrors.text(genericGet(42)).equals("42"), "text(Integer)");
        check(!AgentErrors.blank(genericGet("agent-1")) && AgentErrors.blank(genericGet("  ")), "blank()");
        check("0a1b2c3d4e5f4a6b8c7d0e1f2a3b4c5d".equals(AgentIds.publicId(genericGet("0a1b2c3d-4e5f-4a6b-8c7d-0e1f2a3b4c5d"))),
                "publicId must stringify a generic metadata get");
        boolean reproduced = false;
        try { String bad = String.valueOf(genericGet("agent-1")); } catch (ClassCastException e) { reproduced = true; }
        check(reproduced, "expected javac to bind String.valueOf(T) to char[] overload");
        // say accepts text, and message as an alias (dedicated tool + execute).
        check("Good morning team!".equals(AgentTargets.first(
                JsonParser.parseString("{\"message\":\"Good morning team!\"}").getAsJsonObject(), "text", "message")),
                "say message alias");
        check("hello".equals(AgentTargets.first(
                JsonParser.parseString("{\"text\":\"hello\",\"message\":\"ignored\"}").getAsJsonObject(), "text", "message")),
                "say text wins over message");
        var viaMessage = BridgeProtocol.action("say", JsonParser.parseString("{\"message\":\"Good morning team!\"}").getAsJsonObject(), "world");
        check("Good morning team!".equals(viaMessage.get("text").getAsString()), "bridge say aliases message");
        var viaText = BridgeProtocol.action("say", JsonParser.parseString("{\"text\":\"hello\"}").getAsJsonObject(), "world");
        check("hello".equals(viaText.get("text").getAsString()), "bridge say accepts text");
        // B2: null / blank exception messages become class name + context.
        check(AgentErrors.message(new NullPointerException(), "place").equals("place: NullPointerException"), AgentErrors.message(new NullPointerException(), "place"));
        check(!AgentErrors.message(new IllegalStateException(""), null).isBlank(), "blank message survived");
        check(AgentErrors.message(new RuntimeException(new IllegalArgumentException("inner")), null).contains("inner"), "cause not unwrapped");
        check(AgentErrors.message(new IllegalArgumentException("Target is out of reach"), "place").equals("place: Target is out of reach"), "context missing");

        // Single action throwing a message-less exception -> non-empty error, no exception escapes.
        var single = JsonParser.parseString("{\"action\":\"place\",\"x\":1,\"y\":64,\"z\":2,\"block\":\"dirt\"}").getAsJsonObject();
        Map<String,Object> r = AgentActionProgram.execute(single, "world", (step, i) -> { throw new NullPointerException(); });
        check(Boolean.FALSE.equals(r.get("ok")) && !String.valueOf(r.get("error")).isBlank(), "single failure error empty: " + r);
        // Executor returning ok:false without error -> filled in.
        r = AgentActionProgram.execute(single, "world", (step, i) -> Map.of("ok", false, "action", "place"));
        check(!AgentErrors.blank(r.get("error")), "missing error not filled: " + r);

        // Programs: failing step error surfaces at top level (Site reads result.error).
        var program = JsonParser.parseString("{\"steps\":[{\"action\":\"select\",\"slot\":0},{\"action\":\"observe\",\"radius\":3}]}").getAsJsonObject();
        r = AgentActionProgram.execute(program, "world", (step, i) -> { if (i == 1) throw new IllegalStateException(); return Map.of("ok", true); });
        check(Boolean.FALSE.equals(r.get("ok")) && String.valueOf(r.get("error")).contains("Step 2 (observe)"), "program top-level error: " + r);
        check(Boolean.TRUE.equals(r.get("partial")) && Integer.valueOf(1).equals(r.get("succeeded")),
                "partial program after earlier success: " + r);
        Map<?,?> step2 = (Map<?,?>) ((List<?>) r.get("results")).get(1);
        check("observe: IllegalStateException".equals(step2.get("error")), "step error: " + step2);

        // Result posted to the bridge always carries the error.
        var posted = AgentBridge.withError(JsonParser.parseString("{\"ok\":false,\"error\":\"\"}").getAsJsonObject());
        check(!posted.get("error").getAsString().isBlank(), "bridge result posted with empty error");
        check(!AgentBridge.actionFailure(new IllegalArgumentException(), "scan_entities").get("error").getAsString().isBlank(), "actionFailure empty");
        check(!AgentBridge.actionFailure(new IllegalArgumentException(), "follow").get("error").getAsString().isBlank(), "follow empty exception");
        check(!AgentBridge.actionFailure(new ClassCastException(), "say").get("error").getAsString().isBlank(), "say CCE");
        check(AgentBridge.actionFailure(new NullPointerException(), "say").get("error").getAsString().contains("say"), "say NPE names action");
        check(AgentBridge.actionFailure(new NullPointerException(), "follow").get("error").getAsString().contains("follow"), "follow NPE names action");
        check(!AgentErrors.ensureError(java.util.Map.of("ok", false, "action", "say")).get("error").toString().isBlank(), "say ok:false filled");
        check(!AgentErrors.ensureError(java.util.Map.of("ok", false, "action", "follow", "error", "  ")).get("error").toString().isBlank(), "follow blank error filled");
        check(!AgentTargets.requireError("").isBlank() && !AgentTargets.missing("Helper").isBlank(), "follow/say miss is specific");
        check(AgentTargets.missing("Helper").contains("Helper"), "missing names the recipient");
        check(AgentTargets.sayNeedRecipient().toLowerCase().contains("name"), "say recipient error");
        var good = JsonParser.parseString("{\"ok\":true}").getAsJsonObject();
        check(AgentBridge.withError(good) == good, "success result altered");
        var fromNull = AgentBridge.jsonResult(null);
        check(fromNull != null && fromNull.isJsonObject() && !fromNull.get("ok").getAsBoolean()
                && !fromNull.get("error").getAsString().isBlank(), "null action result must be an explicit error: " + fromNull);
        check(AgentBridge.jsonResult(java.util.Map.of("ok", true)).get("ok").getAsBoolean(), "map result encoded");
        check(!AgentBridge.withError(null).get("error").getAsString().isBlank(), "withError(null)");
        check(AgentErrors.display("").equals("Action failed (no detail reported)"), "display blank");
        check(AgentErrors.display("  ").equals("Action failed (no detail reported)"), "display whitespace");
        check(AgentErrors.display("reach").equals("reach"), "display keeps text");
        try {
            AgentActionProgram.normalize(JsonParser.parseString("{\"action\":\"dig\",\"x\":1,\"y\":64,\"z\":2,\"target\":{\"x\":1}}").getAsJsonObject(), "world");
            throw new AssertionError("object field was accepted");
        } catch (IllegalArgumentException bad) {
            check(bad.getMessage().contains("target") && bad.getMessage().toLowerCase().contains("object"),
                    "object field must name the key: " + bad.getMessage());
        }
        try {
            AgentActionProgram.normalize(JsonParser.parseString("{\"action\":\"dig\",\"x\":1,\"y\":64,\"z\":2,\"extra\":[1]}").getAsJsonObject(), "world");
            throw new AssertionError("array field was accepted");
        } catch (IllegalArgumentException bad) {
            check(bad.getMessage().contains("extra") && bad.getMessage().toLowerCase().contains("array"),
                    "array field must name the key: " + bad.getMessage());
        }
        check(AgentsMC.kindHint(JsonParser.parseString("{\"a\":1}")).contains("object"), "kindHint object");
        check(AgentsMC.kindHint(JsonParser.parseString("\"hi\"")).contains("string"), "kindHint string");

        // F1: inbox keeps last 30, unread tracking, whisper sender for reply.
        UUID a = UUID.randomUUID();
        for (int i = 0; i < 35; i++) AgentSocial.deliver(a, "Steve", "public", "m" + i, i);
        AgentSocial.deliver(a, "Helper", "whisper", "hi", 99);
        check(AgentSocial.peekUnread(a).size() == 30, "capacity");
        check(AgentSocial.peekUnread(a).size() == 30, "peek must not mark read");
        check("Helper".equals(AgentSocial.lastWhisperer(a)), "whisperer");
        check(AgentSocial.all(a).size() == 30 && AgentSocial.unreadCount(a) == 0, "read marking");
        AgentSocial.deliver(a, "Bob", "public", "new", 100);
        check(AgentSocial.unread(a).size() == 1 && AgentSocial.unreadCount(a) == 0, "new since last read");

        // B4: wedged in a 1-deep dip at -12.2,62,11.3 (floor y=61 in cell, walls y=62 around, ground level y=63 outside).
        AgentSafeSpawn.Probe dip = new AgentSafeSpawn.Probe() {
            boolean solid(int x, int y, int z) { if (y <= 61) return true; if (y == 62) return !(x == -13 && z == 11); return false; }
            public boolean open(int x, int y, int z) { return !solid(x, y, z); }
            public boolean floor(int x, int y, int z) { return solid(x, y, z); }
            public boolean usable(int x, int y, int z) { return true; }
        };
        int[] out = AgentSafeSpawn.escape(-12.2, 62, 11.3, 0, 11, 3, dip);
        check(out != null && out[3] == 0 && out[1] == 63 && out[0] == -12 && out[2] == 11, "dip escape should step up toward target: " + java.util.Arrays.toString(out));
        // Fully enclosed (no headroom to jump): no adjacent step, and teleport is gone.
        AgentSafeSpawn.Probe box = new AgentSafeSpawn.Probe() {
            boolean solid(int x, int y, int z) { if (y <= 61) return true; if (x == -13 && z == 11 && (y == 62 || y == 63)) return false; return y <= 64; }
            public boolean open(int x, int y, int z) { return !solid(x, y, z); }
            public boolean floor(int x, int y, int z) { return solid(x, y, z); }
            public boolean usable(int x, int y, int z) { return true; }
        };
        out = AgentSafeSpawn.escape(-12.2, 62, 11.3, 0, 11, 3, box);
        check(out == null, "enclosed escape must not teleport: " + java.util.Arrays.toString(out));
        System.out.println("PASS: generic-get stringify (no CCE), say text/message alias, named field errors, non-empty errors, chat inbox, STUCK dip (no teleport)");
    }
    private static void check(boolean ok, String message) { if (!ok) throw new AssertionError(message); }
}
