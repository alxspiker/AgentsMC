package dev.agentsmc;

import java.nio.file.Files;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

public final class BridgeTest {
    public static void main(String[] args) throws Exception {
        String a = "00000000-0000-4000-8000-000000000001";
        String b = "00000000-0000-4000-8000-000000000002";
        var path = Files.createTempDirectory("agentsmc-receipts-").resolve("state.json");
        BridgeStore first = new BridgeStore(path);
        String credentialA = first.register(a), credentialB = first.register(b);
        if (credentialA.equals(credentialB)) throw new AssertionError("Shared body credentials");
        BridgeStore restarted = new BridgeStore(path);
        if (!restarted.credential(a).equals(credentialA) || !restarted.credential(b).equals(credentialB))
            throw new AssertionError("Lost persistent body bindings");
        JsonObject payload = JsonParser.parseString("{\"message\":\"hello\"}").getAsJsonObject();
        JsonObject translated = BridgeProtocol.action("say", payload, "world");
        if (!translated.get("text").getAsString().equals("hello") || translated.has("agent"))
            throw new AssertionError("Incorrect credential-bound action");
        for (String selector : new String[] {"agent", "agentId", "bodyUuid", "world", "accessToken", "action"}) {
            JsonObject hostile = payload.deepCopy(); hostile.addProperty(selector, b);
            try { BridgeProtocol.action("say", hostile, "world"); throw new AssertionError("Accepted selector " + selector); }
            catch (IllegalArgumentException expected) { }
        }
        try { BridgeProtocol.action("create", new JsonObject(), "world"); throw new AssertionError("Remote provisioning exposed"); }
        catch (IllegalArgumentException expected) { }
        restarted.begin("action-1", a + " say hello");
        BridgeStore afterCrash = new BridgeStore(path);
        if (afterCrash.previous("action-1", a + " say hello").get("ok").getAsBoolean())
            throw new AssertionError("Interrupted action reported success");
        if (!"server_restart".equals(afterCrash.previous("action-1", a + " say hello").get("error").getAsString()))
            throw new AssertionError("Interrupted action must report server_restart");
        JsonObject completed = new JsonObject(); completed.addProperty("ok", true);
        afterCrash.complete("action-1", completed);
        if (!new BridgeStore(path).previous("action-1", a + " say hello").equals(completed))
            throw new AssertionError("Completed result not durable");
        try { afterCrash.previous("action-1", b + " say hello"); throw new AssertionError("Reused action ID switched bodies"); }
        catch (IllegalArgumentException expected) { }
        if (!BridgeProtocol.removedTarget(404, "/api/server/agent", "{\"error\":\"Agent not found.\"}"))
            throw new AssertionError("Deleted registration must reconcile instead of stopping the bridge");
        if (!BridgeProtocol.removedTarget(404, "/api/server/actions/receipt", "{\"error\":\"Queued action not found.\"}"))
            throw new AssertionError("Cancelled action must not replay");
        if (BridgeProtocol.removedTarget(401, "/api/server/agent", "{\"error\":\"Agent not found.\"}")
                || BridgeProtocol.removedTarget(404, "/api/server/agent", "{\"error\":\"Route not found\"}"))
            throw new AssertionError("Unexpected HTTP failures must remain visible");
        try { BridgeProtocol.action("remove", new JsonObject(), "world"); throw new AssertionError("Agent removal exposed remotely"); }
        catch (IllegalArgumentException expected) { }
        String token = "A".repeat(64);
        AgentBridge.Settings off = AgentBridge.resolveSettings(false, "", "", "world", 0.5, 64, 0.5, 1, null, null, null);
        if (off.enabled) throw new AssertionError("empty yaml stays disabled");
        AgentBridge.Settings env = AgentBridge.resolveSettings(false, "", "", "world", 0.5, 64, 0.5, 1, null, null, token);
        if (!env.enabled || !token.equals(env.token) || !AgentBridge.DEFAULT_URL.equals(env.url))
            throw new AssertionError("env token must enable and fill the default url: enabled=" + env.enabled);
        AgentBridge.Settings stayOff = AgentBridge.resolveSettings(false, AgentBridge.DEFAULT_URL, token, "world", 0.5, 64, 0.5, 1, null, null, null);
        if (stayOff.enabled) throw new AssertionError("yaml token with enabled:false stays off without env");
        if (!AgentBridge.tokenOk(token) || AgentBridge.tokenOk("short") || AgentBridge.tokenOk("") || AgentBridge.tokenOk("has space in it 12345678"))
            throw new AssertionError("token format");
        if (!AgentBridge.tokenOk("amcs_" + "b".repeat(40))) throw new AssertionError("prefixed token format");
        String masked = AgentBridge.maskToken("amcs_" + "b".repeat(40));
        if (masked.contains("bbbb") || !masked.startsWith("amcs_")) throw new AssertionError("mask: " + masked);
        if (AgentBridge.maskToken(token).contains("AAAA")) throw new AssertionError("legacy mask");
        if (!AgentBridge.unauthorized(401, "{\"error\":\"token_revoked\"}")) throw new AssertionError("401 is unauthorized");
        AgentsMC.ClaimResult claim = AgentsMC.parseClaim(200, "{\"serverId\":\"srv-1\",\"serverToken\":\"amcs_" + "c".repeat(40) + "\"}");
        if (!"srv-1".equals(claim.serverId) || !claim.token.startsWith("amcs_")) throw new AssertionError("claim parse");
        try { AgentsMC.parseClaim(404, "{\"error\":\"Link code expired or already used\"}"); throw new AssertionError("bad code must fail"); }
        catch (IllegalStateException expected) { if (!expected.getMessage().contains("expired")) throw new AssertionError(expected.getMessage()); }
        try { AgentsMC.parseClaim(200, "{}"); throw new AssertionError("missing token must fail"); }
        catch (IllegalStateException expected) { }
        System.out.println("PASS: persistent isolated credentials, selector rejection, interrupted-action safety, durable result replay, env token enables after reset, link claim parse, token masking");
    }
}
