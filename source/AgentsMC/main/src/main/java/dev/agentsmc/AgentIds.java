package dev.agentsmc;

/** Agent IDs in responses are the dashless form used by the bridge (UUID minus hyphens). */
final class AgentIds {
    static String publicId(Object id) {
        if (id == null) return null;
        String s = AgentErrors.text(id);
        return s.isBlank() || "null".equals(s) ? null : s.replace("-", "");
    }

    static boolean same(String a, String b) {
        String left = publicId(a), right = publicId(b);
        return left != null && left.equalsIgnoreCase(right);
    }

    private AgentIds() {}
}
