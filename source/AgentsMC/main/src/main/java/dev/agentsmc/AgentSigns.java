package dev.agentsmc;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import org.bukkit.block.Block;
import org.bukkit.block.Sign;
import org.bukkit.block.sign.Side;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.TextComponent;

/** Sign text for inspect_block / survey. Agents can already write via place.text. */
final class AgentSigns {
    static boolean isSign(String material) {
        if (material == null || material.isBlank()) return false;
        String k = material.trim().toLowerCase(Locale.ROOT);
        if (k.startsWith("minecraft:")) k = k.substring("minecraft:".length());
        return k.equals("sign") || k.endsWith("_sign");
    }

    static List<String> pad4(List<String> lines) {
        List<String> out = new ArrayList<>(4);
        for (int i = 0; i < 4; i++) {
            String line = lines != null && i < lines.size() && lines.get(i) != null ? lines.get(i) : "";
            out.add(line);
        }
        return out;
    }

    static String join(List<String> lines) {
        List<String> padded = pad4(lines);
        int last = padded.size();
        while (last > 0 && padded.get(last - 1).isEmpty()) last--;
        if (last == 0) return "";
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < last; i++) {
            if (i > 0) sb.append('\n');
            sb.append(padded.get(i));
        }
        return sb.toString();
    }

    static boolean anyText(List<String> lines) {
        if (lines == null) return false;
        for (String line : lines) if (line != null && !line.isEmpty()) return true;
        return false;
    }

    static Map<String, Object> sideView(List<String> lines) {
        List<String> padded = pad4(lines);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("lines", padded);
        out.put("text", join(padded));
        return out;
    }

    static Map<String, Object> view(List<String> front, List<String> back, boolean waxed) {
        Map<String, Object> out = new LinkedHashMap<>();
        Map<String, Object> frontView = sideView(front);
        out.put("lines", frontView.get("lines"));
        out.put("text", frontView.get("text"));
        if (anyText(back)) out.put("back", sideView(back));
        out.put("waxed", waxed);
        return out;
    }

    static String plain(Component component) {
        if (component == null) return "";
        StringBuilder sb = new StringBuilder();
        appendPlain(component, sb);
        return sb.toString();
    }

    private static void appendPlain(Component component, StringBuilder sb) {
        if (component instanceof TextComponent text) sb.append(text.content());
        for (Component child : component.children()) appendPlain(child, sb);
    }

    static List<String> sideLines(Sign sign, Side side) {
        List<String> lines = new ArrayList<>(4);
        var face = sign.getSide(side);
        for (int i = 0; i < 4; i++) lines.add(plain(face.line(i)));
        return lines;
    }

    static void attach(Map<String, Object> out, Block block) {
        if (out == null || block == null || !isSign(block.getType().name())) return;
        if (!(block.getState() instanceof Sign sign)) return;
        out.putAll(view(sideLines(sign, Side.FRONT), sideLines(sign, Side.BACK), sign.isWaxed()));
    }

    static String sideKey(String raw) {
        if (raw == null || raw.isBlank()) return "front";
        return switch (raw.trim().toLowerCase(Locale.ROOT)) {
            case "front", "f" -> "front";
            case "back", "rear", "b" -> "back";
            default -> throw new IllegalArgumentException("side must be front or back");
        };
    }

    static void requireEditable(boolean waxed) {
        if (waxed) throw new IllegalArgumentException("Sign is waxed; it cannot be edited");
    }

    /** Rewrite one side of an existing sign. Waxed signs are refused. */
    static Map<String, Object> write(Block block, String text, String sideRaw) {
        if (block == null || !(block.getState() instanceof Sign sign))
            throw new IllegalArgumentException("Target is not a sign");
        requireEditable(sign.isWaxed());
        String side = sideKey(sideRaw);
        String[] lines = AgentPlacement.signLines(text);
        var face = sign.getSide("back".equals(side) ? Side.BACK : Side.FRONT);
        for (int i = 0; i < 4; i++) face.line(i, Component.text(i < lines.length ? lines[i] : ""));
        sign.update(true, false);
        Sign fresh = block.getState() instanceof Sign next ? next : sign;
        Map<String, Object> out = view(sideLines(fresh, Side.FRONT), sideLines(fresh, Side.BACK), fresh.isWaxed());
        out.put("edited", true);
        out.put("side", side);
        return out;
    }

    static void annotateObjects(List<Map<String, Object>> objects, org.bukkit.World world) {
        if (objects == null || world == null) return;
        for (Map<String, Object> obj : objects) {
            if (obj == null) continue;
            String type = String.valueOf(obj.get("type"));
            if (!isSign(type) && !"sign".equals(type)) continue;
            Object pos = obj.get("position");
            if (!(pos instanceof List<?> xyz) || xyz.size() < 3) continue;
            int x = ((Number) xyz.get(0)).intValue();
            int y = ((Number) xyz.get(1)).intValue();
            int z = ((Number) xyz.get(2)).intValue();
            attach(obj, world.getBlockAt(x, y, z));
        }
    }

    private AgentSigns() {}
}
