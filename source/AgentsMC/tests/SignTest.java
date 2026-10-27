package dev.agentsmc;

import java.util.List;
import java.util.Map;

/** Offline sign text view used by inspect_block / survey. */
public final class SignTest {
    public static void main(String[] args) {
        check(AgentSigns.isSign("OAK_SIGN") && AgentSigns.isSign("oak_wall_sign")
                && AgentSigns.isSign("minecraft:birch_hanging_sign"), "sign materials");
        check(!AgentSigns.isSign("OAK_PLANKS") && !AgentSigns.isSign("red_bed"), "not a sign");

        Map<String, Object> frontOnly = AgentSigns.view(List.of("Home", "keep out"), List.of("", "", "", ""), false);
        check("Home\nkeep out".equals(frontOnly.get("text")), "join drops trailing blanks: " + frontOnly.get("text"));
        @SuppressWarnings("unchecked")
        List<String> lines = (List<String>) frontOnly.get("lines");
        check(lines.size() == 4 && "Home".equals(lines.get(0)) && "keep out".equals(lines.get(1))
                && lines.get(2).isEmpty() && lines.get(3).isEmpty(), "four front lines: " + lines);
        check(!frontOnly.containsKey("back"), "blank back omitted");
        check(Boolean.FALSE.equals(frontOnly.get("waxed")), "unwaxed");

        Map<String, Object> both = AgentSigns.view(List.of("front"), List.of("rear"), true);
        check("front".equals(both.get("text")), "front text");
        @SuppressWarnings("unchecked")
        Map<String, Object> back = (Map<String, Object>) both.get("back");
        check(back != null && "rear".equals(back.get("text")), "back text: " + back);
        check(Boolean.TRUE.equals(both.get("waxed")), "waxed");

        check("".equals(AgentSigns.join(List.of())), "empty join");
        check("a\n\nb".equals(AgentSigns.join(List.of("a", "", "b"))), "middle blank kept");

        check("sign".equals(AgentSurvey.objectType("oak_sign"))
                && "sign".equals(AgentSurvey.objectType("spruce_wall_sign")), "survey type is sign");

        check("front".equals(AgentSigns.sideKey(null)) && "front".equals(AgentSigns.sideKey("FRONT"))
                && "back".equals(AgentSigns.sideKey("rear")), "side aliases");
        try { AgentSigns.sideKey("left"); throw new AssertionError("left side accepted"); }
        catch (IllegalArgumentException expected) { check(expected.getMessage().toLowerCase().contains("front"), "bad side: " + expected.getMessage()); }
        try { AgentSigns.requireEditable(true); throw new AssertionError("waxed editable"); }
        catch (IllegalArgumentException expected) { check(expected.getMessage().toLowerCase().contains("waxed"), "waxed error: " + expected.getMessage()); }
        AgentSigns.requireEditable(false);

        String version = AgentVersion.fromPluginYml();
        check("0.7.5".equals(version), "plugin.yml version: " + version);
        System.out.println("PASS: sign text view, front/back, edit side, waxed refuse, 0.7.4");
    }

    private static void check(boolean ok, String message) {
        if (!ok) throw new AssertionError(message);
    }
}
