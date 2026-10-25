package dev.agentsmc;

/** Per-target retry delay for viewer/panel POSTs. Failures never stop heartbeats. */
final class AgentOutboundBackoff {
    static final long MIN_MS = 1_000, MAX_MS = 30_000;
    long nextAt;
    int failures;

    boolean due(long now) { return now >= nextAt; }

    void ok() { failures = 0; nextAt = 0; }

    long fail(long now) {
        failures++;
        long delay = delayMs(failures);
        nextAt = now + delay;
        return delay;
    }

    /** 1s, 2s, 4s, 8s, 16s, then cap 30s. */
    static long delayMs(int failures) {
        if (failures < 1) return 0;
        int exp = Math.min(failures - 1, 5);
        return Math.min(MAX_MS, MIN_MS << exp);
    }
}
