package dev.agentsmc;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import com.google.gson.JsonObject;

/**
 * Craft field aliases and vanilla fallbacks for common recipes the Bukkit
 * iterator sometimes misses (planks, sticks, chest, wooden tools). Amount is output count.
 */
final class AgentCraft {
    static final class Spec {
        final String output;
        final int yield;
        final List<String> inputs;
        final boolean table;
        Spec(String output, int yield, List<String> inputs, boolean table) {
            this.output = output;
            this.yield = yield;
            this.inputs = List.copyOf(inputs);
            this.table = table;
        }
    }

    static String outputField(JsonObject o) {
        if (o == null) throw new IllegalArgumentException("craft requires item or material");
        if (hasString(o, "item")) return o.get("item").getAsString();
        if (hasString(o, "material")) return o.get("material").getAsString();
        throw new IllegalArgumentException("craft requires item or material");
    }

    static boolean hasString(JsonObject o, String key) {
        return o != null && o.has(key) && o.get(key).isJsonPrimitive() && o.get(key).getAsJsonPrimitive().isString()
                && !o.get(key).getAsString().isBlank();
    }

    static int amount(JsonObject o) {
        com.google.gson.JsonElement raw = null;
        String name = "amount";
        if (o != null && o.has("amount") && !o.get("amount").isJsonNull()) raw = o.get("amount");
        else if (o != null && o.has("count") && !o.get("count").isJsonNull()) { raw = o.get("count"); name = "count"; }
        if (raw == null) return 1;
        if (!raw.isJsonPrimitive() || !raw.getAsJsonPrimitive().isNumber())
            throw new IllegalArgumentException("Expected number: " + name);
        double n = raw.getAsDouble();
        if (!Double.isFinite(n) || n != Math.rint(n) || n < 1 || n > 64)
            throw new IllegalArgumentException("Invalid integer: " + name);
        return (int) n;
    }

    static String bare(String raw) {
        if (raw == null) return "";
        String n = raw.trim().toLowerCase(Locale.ROOT);
        return n.startsWith("minecraft:") ? n.substring("minecraft:".length()) : n;
    }

    /** Matching-wood planks, sticks, chest, or wooden tools. Null when the output is not a vanilla fallback. */
    static Spec vanilla(String output) {
        String n = bare(output);
        if (n.endsWith("_planks")) {
            String wood = n.substring(0, n.length() - "_planks".length());
            return new Spec(n, 4, List.of(wood + "_log"), false);
        }
        if (n.equals("stick") || n.equals("sticks"))
            return new Spec("stick", 4, List.of("any_planks", "any_planks"), false);
        if (n.equals("chest"))
            return new Spec("chest", 1, List.of(
                    "any_planks", "any_planks", "any_planks", "any_planks",
                    "any_planks", "any_planks", "any_planks", "any_planks"), true);
        if (n.equals("wooden_sword"))
            return new Spec("wooden_sword", 1, List.of("any_planks", "any_planks", "stick"), false);
        if (n.equals("wooden_axe"))
            return new Spec("wooden_axe", 1, List.of("any_planks", "any_planks", "any_planks", "stick", "stick"), false);
        if (n.equals("wooden_pickaxe"))
            return new Spec("wooden_pickaxe", 1, List.of("any_planks", "any_planks", "any_planks", "stick", "stick"), false);
        if (n.equals("wooden_shovel"))
            return new Spec("wooden_shovel", 1, List.of("any_planks", "stick", "stick"), false);
        if (n.equals("wooden_hoe"))
            return new Spec("wooden_hoe", 1, List.of("any_planks", "any_planks", "stick", "stick"), false);
        return null;
    }

    static boolean matchesInput(String have, String need) {
        String h = bare(have), n = bare(need);
        if (n.equals("any_planks")) return h.endsWith("_planks") || h.equals("planks");
        if (n.endsWith("_log")) {
            String wood = n.substring(0, n.length() - 4);
            return h.equals(n) || h.equals(wood + "_wood") || h.equals("stripped_" + wood + "_log")
                    || h.equals("stripped_" + wood + "_wood") || h.equals(wood + "_stem")
                    || h.equals("stripped_" + wood + "_stem");
        }
        return h.equals(n);
    }

    static Map<String, Integer> leftover(Map<String, Integer> inv, List<String> inputs) {
        if (inv == null) return null;
        Map<String, Integer> have = new LinkedHashMap<>();
        for (Map.Entry<String, Integer> e : inv.entrySet())
            have.put(bare(e.getKey()), e.getValue() == null ? 0 : e.getValue());
        for (String need : inputs) {
            boolean found = false;
            for (Map.Entry<String, Integer> e : have.entrySet()) {
                if (e.getValue() > 0 && matchesInput(e.getKey(), need)) {
                    e.setValue(e.getValue() - 1);
                    found = true;
                    break;
                }
            }
            if (!found) return null;
        }
        return have;
    }

    static int maxCrafts(Map<String, Integer> inv, Spec spec) {
        if (spec == null || spec.yield < 1) return 0;
        int n = 0;
        Map<String, Integer> have = leftover(inv, List.of());
        if (have == null) have = new LinkedHashMap<>();
        else have = new LinkedHashMap<>(have);
        for (Map.Entry<String, Integer> e : inv.entrySet())
            have.put(bare(e.getKey()), e.getValue() == null ? 0 : e.getValue());
        while (true) {
            Map<String, Integer> next = leftover(have, spec.inputs);
            if (next == null) break;
            have = next;
            n++;
            if (n > 64) break;
        }
        return n;
    }

    static int produced(int requested, int yield, int affordable) {
        int crafts = AgentReach.craftsFor(requested, yield);
        if (crafts < 1) return 0;
        crafts = Math.min(crafts, Math.max(0, affordable));
        return crafts * yield;
    }

    static List<String> inputNames(Spec spec) {
        return spec == null ? List.of() : new ArrayList<>(spec.inputs);
    }

    static String label(String raw) {
        String n = bare(raw);
        if (n.equals("any_planks") || n.equals("planks")) return "PLANKS";
        return n.isEmpty() ? "ingredient" : n.toUpperCase(Locale.ROOT);
    }

    static int owned(Map<String, Integer> have, String need) {
        if (have == null) return 0;
        int n = 0;
        for (Map.Entry<String, Integer> e : have.entrySet())
            if (e.getValue() != null && e.getValue() > 0 && matchesInput(e.getKey(), need)) n += e.getValue();
        return n;
    }

    /** Human-readable missing list, e.g. Need STICK (need 1, have 0) to craft STONE_SWORD. */
    static String shortage(String output, List<String> inputs, Map<String, Integer> have) {
        Map<String, int[]> want = new LinkedHashMap<>();
        if (inputs != null) for (String in : inputs) {
            String key = label(in);
            int[] row = want.computeIfAbsent(key, k -> new int[]{0, owned(have, in)});
            row[0]++;
        }
        List<String> missing = new ArrayList<>();
        for (Map.Entry<String, int[]> e : want.entrySet())
            if (e.getValue()[1] < e.getValue()[0])
                missing.add(e.getKey() + " (need " + e.getValue()[0] + ", have " + e.getValue()[1] + ")");
        String item = output == null || output.isBlank() ? "item" : label(output);
        if (missing.isEmpty()) return "Need ingredients to craft " + item;
        return "Need " + String.join("; ", missing) + " to craft " + item;
    }

    static String missingIngredient(String output, String need, int have) {
        return "Need " + label(need) + " (need 1, have " + Math.max(0, have) + ") to craft " + label(output);
    }

    private AgentCraft() {}
}
