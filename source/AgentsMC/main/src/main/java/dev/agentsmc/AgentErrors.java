package dev.agentsmc;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Every failure reaching an agent carries a non-empty, human-readable error. */
final class AgentErrors {
    private AgentErrors() {}
    /** Message, else simple class name; prefixed with context (e.g. action name) when given. Never empty. */
    static String message(Throwable failure, String context) {
        Throwable t = failure;
        // Unwrap generic wrappers whose own message is empty.
        while (t != null && blank(t.getMessage()) && t.getCause() != null && t.getCause() != t) t = t.getCause();
        String base;
        if (t == null) base = "Unknown failure";
        else if (blank(t.getMessage())) base = t.getClass().getSimpleName();
        else base = t.getMessage();
        if (!(t instanceof IllegalArgumentException) && !(t instanceof IllegalStateException) && t != null && !blank(t.getMessage()))
            base = t.getClass().getSimpleName() + ": " + base;
        if (blank(base)) base = "Unknown failure";
        return blank(context) ? base : context + ": " + base;
    }
    /**
     * Safe stringify for generic-returning getters (e.g. Citizens {@code MetadataStore#get}).
     * {@code String.valueOf(T)} binds to {@code valueOf(char[])} and inserts {@code checkcast [C},
     * which throws {@code ClassCastException} when T is a String. Always go through Object.
     */
    static String text(Object value) {
        if (value == null) return "null";
        if (value instanceof String s) return s;
        return value.toString();
    }
    static boolean blank(Object s) {
        if (s == null) return true;
        String t = s instanceof String str ? str : text(s);
        return t.isBlank();
    }
    /** Returns a failure map with ok=false and a guaranteed non-empty error. */
    static Map<String,Object> failure(String action, Throwable t) {
        Map<String,Object> r = new LinkedHashMap<>();
        r.put("ok", false); if (action != null) r.put("action", action);
        r.put("error", message(t, action));
        return r;
    }
    /** Ensures a result that is not ok has a non-empty top-level error (programs summarize the first failed step). */
    @SuppressWarnings("unchecked")
    static Map<String,Object> ensureError(Map<String,Object> result) {
        if (result == null) { Map<String,Object> r = new LinkedHashMap<>(); r.put("ok", false); r.put("error", "Action produced no result"); return r; }
        if (Boolean.TRUE.equals(result.get("ok")) || !blank(result.get("error"))) return result;
        Map<String,Object> r = new LinkedHashMap<>(result);
        String error = null;
        if (r.get("results") instanceof List<?> steps) for (Object s : steps)
            if (s instanceof Map<?,?> m && !Boolean.TRUE.equals(m.get("ok"))) {
                Object e = m.get("error");
                error = "Step " + m.get("step") + " (" + m.get("action") + ") failed: " + (blank(e) ? "no detail reported" : e);
                break;
            }
        if (error == null) error = "Action failed" + (r.get("action") == null ? "" : ": " + r.get("action")) + " (no detail reported)";
        if (!r.containsKey("ok")) r.put("ok", false);
        r.put("error", error);
        return r;
    }
    /** Never-blank string for Site/MCP inline results and action.failed events. */
    static String display(Object error) {
        if (!blank(error)) return text(error).trim();
        return "Action failed (no detail reported)";
    }
}
