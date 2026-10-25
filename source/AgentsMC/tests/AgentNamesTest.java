package dev.agentsmc;

import java.nio.file.Files;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

/** Registration names apply to any agent; the jar bundles no names. */
public final class AgentNamesTest {
    private static final String MINER_ID = "0a1b2c3d4e5f4a6b8c7d0e1f2a3b4c5d";
    private static final String HELPER_ID = "1111aaaa2222411183334444bbbbcccc";
    private static final String BUILDER_ID = "2222bbbb3333422293335555ccccdddd";
    private static final String SCOUT_ID = "3333cccc4444433383336666ddddeeee";

    public static void main(String[] args) throws Exception {
        AgentNames.resetForTest();
        resolveAndFallback();
        liveRenamePlaceholder();
        persistAcrossRestart();
        pollShapes();
        noBundledNames();
        AgentNames.resetForTest();
        System.out.println("PASS: registration names, poll-shape matching, Miner live rename, missing-name warning");
    }

    private static void resolveAndFallback() {
        check(AgentNames.valid("Miner") && AgentNames.valid("Builder Bot"), "valid names");
        check(!AgentNames.valid("") && !AgentNames.valid("???"), "reject junk");
        check("Miner".equals(AgentNames.resolve(MINER_ID, "Miner")), "registration wins for new agent");
        check("Miner".equals(AgentNames.resolve(AgentEvents.hyphenated(MINER_ID), "Miner")), "hyphenated id");
        check("Agent-1111aaaa".equals(AgentNames.resolve(HELPER_ID, null)), "no bundled fallback");
        check("Agent-0a1b2c3d".equals(AgentNames.resolve(MINER_ID, null)), "unknown id is Agent-xxxx");
        check("Agent-0a1b2c3d".equals(AgentNames.placeholder(MINER_ID)), "placeholder form");
        check(AgentNames.isPlaceholder("Agent-0a1b2c3d", MINER_ID), "Agent-xxxx is placeholder");
        check(AgentNames.isPlaceholder(MINER_ID, MINER_ID), "raw id is placeholder");
        check(!AgentNames.isPlaceholder("Miner", MINER_ID), "Miner is not placeholder");
        check("Miner".equals(AgentNames.resolve(HELPER_ID, "Miner")), "supplied registration name wins");
    }

    private static void liveRenamePlaceholder() {
        check(AgentNames.shouldApply("Agent-0a1b2c3d", "Miner", MINER_ID), "rename Miner placeholder live");
        check(AgentNames.shouldApply(MINER_ID, "Miner", MINER_ID), "rename raw-id body");
        check(!AgentNames.shouldApply("Miner", "Miner", MINER_ID), "already named");
        check(!AgentNames.shouldApply("Miner", "Agent-0a1b2c3d", MINER_ID), "do not overwrite real name with placeholder");
        check(AgentNames.shouldApply("Agent-1111aaaa", "Helper", HELPER_ID), "registration renames placeholder");
        AgentNames.remember(MINER_ID, "Miner");
        check("Miner".equals(AgentNames.resolve(MINER_ID, null)), "remembered registration used without a new poll name");
        check(AgentNames.shouldApply("Agent-0a1b2c3d", AgentNames.resolve(MINER_ID, null), MINER_ID),
                "poll apply renames existing Miner placeholder without restart");
    }

    private static void persistAcrossRestart() throws Exception {
        var path = Files.createTempDirectory("agentsmc-names-").resolve("registered-names.json");
        AgentNames.resetForTest();
        AgentNames.attach(path);
        AgentNames.remember(MINER_ID, "Miner");
        AgentNames.resetForTest();
        AgentNames.attach(path);
        check("Miner".equals(AgentNames.persisted(MINER_ID)), "persisted across restart");
        check("Miner".equals(AgentNames.resolve(MINER_ID, null)), "recover uses persisted name, not Agent-xxxx");
        check("Agent-1111aaaa".equals(AgentNames.resolve(HELPER_ID, null)), "unknown stays placeholder");
    }

    /** Live Miner is already linked: hyphenated string in agentIds, not in registrations. */
    private static void pollShapes() {
        String hyphen = AgentEvents.hyphenated(MINER_ID);
        check(AgentNames.sameId(MINER_ID, hyphen) && AgentNames.sameId(hyphen, MINER_ID), "dashless matches hyphenated");
        check(!AgentNames.sameId(MINER_ID, HELPER_ID), "different ids");

        JsonObject stringsOnly = poll("""
                {"registrations":[],"actions":[],"agentIds":["%s"],"viewers":[]}
                """.formatted(hyphen));
        check(AgentNames.namesFromPoll(stringsOnly).isEmpty(), "string agentIds carry no name");
        check(AgentNames.arrayContainsId(stringsOnly.getAsJsonArray("agentIds"), MINER_ID), "string agentIds match dashless");
        check(!AgentNames.arrayContainsId(stringsOnly.getAsJsonArray("registrations"), MINER_ID), "not in registrations");
        String warn = AgentNames.missingNameWarning(MINER_ID, "Agent-0a1b2c3d", stringsOnly);
        check(warn.contains("in agentIds=true") && warn.contains("in registrations=false"), "warn: in agentIds not registrations: " + warn);
        check(warn.contains("extractedName=") && warn.contains("agentIds[0]=string"), "warn includes shape: " + warn);
        check(warn.contains("pollKeys=") && warn.contains("still Agent-0a1b2c3d"), "warn includes keys and current: " + warn);

        JsonObject objectIds = poll("""
                {"registrations":[],"actions":[],"agentIds":[{"id":"%s","name":"Miner"}],"viewers":[]}
                """.formatted(hyphen));
        check("Miner".equals(AgentNames.namesFromPoll(objectIds).get(MINER_ID)), "object agentIds name");
        check(AgentNames.pollShape(objectIds).contains("agentIds[0]=object"), "shape notes object agentIds");

        JsonObject displayName = poll("""
                {"registrations":[{"agentId":"%s","displayName":"Miner"}],"actions":[],"agentIds":["%s"],"viewers":[]}
                """.formatted(MINER_ID, hyphen));
        check("Miner".equals(AgentNames.namesFromPoll(displayName).get(MINER_ID)), "displayName + dashless agentId");
        check("Miner".equals(AgentNames.pollName(displayName.getAsJsonArray("registrations").get(0))), "pollName reads displayName");
        check(AgentNames.sameId(AgentNames.pollId(displayName.getAsJsonArray("registrations").get(0)), hyphen),
                "pollId reads agentId dashless vs hyphenated agentIds");

        JsonObject namesMap = poll("""
                {"registrations":[],"actions":[],"agentIds":["%s"],"viewers":[],"names":{"%s":"Miner"}}
                """.formatted(hyphen, hyphen));
        check("Miner".equals(AgentNames.namesFromPoll(namesMap).get(MINER_ID)), "names map hyphenated key");

        JsonObject snake = poll("""
                {"registrations":[{"agent_id":"%s","agent_name":"Miner"}],"actions":[],"agentIds":[{"uuid":"%s"}],"viewers":[]}
                """.formatted(hyphen, MINER_ID));
        check("Miner".equals(AgentNames.namesFromPoll(snake).get(MINER_ID)), "agent_id/agent_name");
        check(AgentNames.arrayContainsId(snake.getAsJsonArray("agentIds"), MINER_ID), "object uuid agentIds");
    }

    private static void noBundledNames() {
        for (String path : new String[] {"source/AgentsMC/main/src/main/resources/agent-names.yml", "main/src/main/resources/agent-names.yml"})
            check(!java.nio.file.Files.exists(java.nio.file.Path.of(path)), "agent-names.yml must not ship: " + path);
    }

    private static JsonObject poll(String json) {
        return JsonParser.parseString(json).getAsJsonObject();
    }

    private static void check(boolean ok, String message) {
        if (!ok) throw new AssertionError(message);
    }
}
