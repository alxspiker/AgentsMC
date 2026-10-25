package dev.agentsmc;

/** Base64 wrapper for Paper's serialized item arrays plus a stack-size counter (testable without a server). */
final class AgentInventoryCodec {
    static String encode(byte[] data) { return java.util.Base64.getEncoder().encodeToString(data); }
    static byte[] decode(Object stored) { return java.util.Base64.getDecoder().decode(AgentErrors.text(stored)); }
    static int count(org.bukkit.inventory.ItemStack[] items) { int n = 0; for (var s : items) if (s != null && !s.getType().isAir()) n += s.getAmount(); return n; }
    private AgentInventoryCodec() {}
}
