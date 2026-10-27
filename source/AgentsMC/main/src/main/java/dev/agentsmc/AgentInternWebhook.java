package dev.agentsmc;

import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.Instant;
import java.util.Properties;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;
import java.util.regex.Pattern;

import com.google.gson.JsonObject;

/**
 * 0.7.6: optional chat-mention webhook. When a public chat line from anyone other than the
 * Intern Steve agent contains the whole word "intern" or "admin" (any case), POST
 * {reason, from, text, time, server} to a configured URL with {@code Authorization: Bearer <key>}.
 * Off unless the classpath resource {@code intern-webhook.properties} supplies both url and key.
 * Each new agent feedback entry POSTs {reason:"feedback", id, from, text, time, server}.
 * Sends are asynchronous; chat posts at most one per 60 s, feedback posts at most one per 10 s
 * (independent limits). Network errors are logged once.
 */
final class AgentInternWebhook {
    static final String RESOURCE = "intern-webhook.properties";
    static final String INTERN_AGENT = "Intern Steve";
    static final long MIN_INTERVAL_MS = 60_000L;
    static final long FEEDBACK_INTERVAL_MS = 10_000L;
    private static final Pattern INTERN = word("intern"), ADMIN = word("admin");
    private static Pattern word(String w) {
        return Pattern.compile("(?<![\\p{L}\\p{N}_])" + w + "(?![\\p{L}\\p{N}_])", Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE);
    }

    /** Transport seam for tests. Must not block the caller. */
    interface Transport { CompletableFuture<Integer> post(URI url, String bearer, String json); }

    private final URI url;
    private final String key;
    private final String server;
    private final Transport transport;
    private final Consumer<String> log;
    private final AtomicLong lastSent = new AtomicLong(Long.MIN_VALUE);
    private final AtomicLong lastFeedback = new AtomicLong(Long.MIN_VALUE);
    private final AtomicBoolean errorLogged = new AtomicBoolean();

    AgentInternWebhook(String url, String key, String server, Transport transport, Consumer<String> log) {
        URI parsed = null;
        if (url != null && !url.isBlank() && key != null && !key.isBlank()) {
            try {
                URI u = URI.create(url.trim());
                if (("https".equalsIgnoreCase(u.getScheme()) || "http".equalsIgnoreCase(u.getScheme())) && u.getHost() != null) parsed = u;
            } catch (IllegalArgumentException ignored) {}
        }
        this.url = parsed;
        this.key = parsed == null ? null : key.trim();
        this.server = server == null ? "" : server;
        this.transport = transport == null ? httpTransport() : transport;
        this.log = log == null ? s -> {} : log;
    }

    /** Loads the classpath resource; returns a disabled instance when it is missing or incomplete. */
    static AgentInternWebhook fromClasspath(ClassLoader loader, String server, Consumer<String> log) {
        Properties p = new Properties();
        try (InputStream in = loader == null ? null : loader.getResourceAsStream(RESOURCE)) {
            if (in != null) p.load(in);
        } catch (Exception e) {
            if (log != null) log.accept("intern webhook config unreadable; feature off");
        }
        return new AgentInternWebhook(p.getProperty("url"), p.getProperty("key"), server, null, log);
    }

    boolean enabled() { return url != null; }

    static boolean mentionsIntern(String text) { return text != null && INTERN.matcher(text).find(); }

    /** "intern", "admin", or null when the text mentions neither as a whole word ("intern" wins if both). */
    static String chatReason(String text) {
        if (text == null) return null;
        if (INTERN.matcher(text).find()) return "intern";
        if (ADMIN.matcher(text).find()) return "admin";
        return null;
    }

    static boolean isInternAgent(String from) { return from != null && from.trim().equalsIgnoreCase(INTERN_AGENT); }

    static String payload(String from, String text, long timeMs, String server) {
        return payload("intern", null, from, text, timeMs, server);
    }

    static String payload(String reason, String id, String from, String text, long timeMs, String server) {
        JsonObject o = new JsonObject();
        o.addProperty("reason", reason);
        if (id != null) o.addProperty("id", id);
        o.addProperty("from", from);
        o.addProperty("text", text);
        o.addProperty("time", Instant.ofEpochMilli(timeMs).toString());
        o.addProperty("server", server);
        return o.toString();
    }

    /** Called for every public chat line. Never blocks or throws. Returns true when a POST was started. */
    boolean onPublicChat(String from, String text, long nowMs) {
        try {
            if (!enabled() || isInternAgent(from)) return false;
            String reason = chatReason(text);
            if (reason == null || !acquire(lastSent, MIN_INTERVAL_MS, nowMs)) return false;
            return send(payload(reason, null, from, text, nowMs, server));
        } catch (Throwable t) {
            logOnce("intern webhook request failed: " + rootMessage(t));
            return false;
        }
    }

    /** Called for each new feedback entry. Own 10 s limit, independent of the chat limit. Never blocks or throws. */
    boolean onFeedback(String id, String from, String text, long nowMs) {
        try {
            if (!enabled() || !acquire(lastFeedback, FEEDBACK_INTERVAL_MS, nowMs)) return false;
            return send(payload("feedback", id, from, text, nowMs, server));
        } catch (Throwable t) {
            logOnce("intern webhook request failed: " + rootMessage(t));
            return false;
        }
    }

    private static boolean acquire(AtomicLong last, long interval, long nowMs) {
        long prev = last.get();
        if (prev != Long.MIN_VALUE && nowMs - prev < interval) return false;
        return last.compareAndSet(prev, nowMs);
    }

    private boolean send(String json) {
        try {
            CompletableFuture<Integer> f = transport.post(url, key, json);
            if (f != null) f.whenComplete((status, error) -> {
                if (error != null) logOnce("intern webhook request failed: " + rootMessage(error));
                else if (status == null || status < 200 || status >= 300) logOnce("intern webhook returned HTTP " + status);
            });
            return true;
        } catch (Throwable t) {
            logOnce("intern webhook request failed: " + rootMessage(t));
            return false;
        }
    }

    private void logOnce(String message) {
        if (errorLogged.compareAndSet(false, true)) {
            try { log.accept(message + " (further webhook errors are not logged)"); } catch (Throwable ignored) {}
        }
    }

    private static String rootMessage(Throwable t) {
        Throwable c = t;
        while (c.getCause() != null && c.getCause() != c) c = c.getCause();
        return c.getClass().getSimpleName() + (c.getMessage() == null ? "" : ": " + c.getMessage());
    }

    private static Transport httpTransport() {
        HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();
        return (uri, bearer, json) -> {
            HttpRequest request = HttpRequest.newBuilder(uri).timeout(Duration.ofSeconds(15))
                    .header("Content-Type", "application/json")
                    .header("Authorization", "Bearer " + bearer)
                    .POST(HttpRequest.BodyPublishers.ofString(json)).build();
            return client.sendAsync(request, HttpResponse.BodyHandlers.discarding()).thenApply(HttpResponse::statusCode);
        };
    }
}
