package dev.agentsmc;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Enumeration;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.sun.net.httpserver.HttpServer;

/** 0.7.6 chat-mention + feedback webhook: matching, gating, rate limits, payload, async errors. */
public final class InternWebhookTest {
    record Call(URI url, String bearer, String json) {}

    public static void main(String[] args) throws Exception {
        // whole-word matching
        for (String t : new String[]{"intern", "hey Intern!", "INTERN, help", "the intern's bed", "ask intern-steve", "(intern)"})
            check("intern".equals(AgentInternWebhook.chatReason(t)), "intern match: " + t);
        for (String t : new String[]{"admin", "Admin?", "hey ADMIN pls", "admin's house"})
            check("admin".equals(AgentInternWebhook.chatReason(t)), "admin match: " + t);
        for (String t : new String[]{"internal", "interns", "international", "winterns", "intern_x", "administrator", "admins", "badmin", "hello", "", null})
            check(AgentInternWebhook.chatReason(t) == null, "no match: " + t);
        check("intern".equals(AgentInternWebhook.chatReason("admin and intern")), "intern wins when both");

        // off unless both url and key
        List<Call> calls = Collections.synchronizedList(new ArrayList<>());
        AgentInternWebhook.Transport rec = (u, b, j) -> { calls.add(new Call(u, b, j)); return CompletableFuture.completedFuture(200); };
        check(!new AgentInternWebhook(null, "k", "s", rec, null).enabled(), "no url -> off");
        check(!new AgentInternWebhook("https://x.test/h", "", "s", rec, null).enabled(), "blank key -> off");
        check(!new AgentInternWebhook("not a url", "k", "s", rec, null).enabled(), "bad url -> off");
        AgentInternWebhook off = new AgentInternWebhook("", null, "s", rec, null);
        check(!off.onPublicChat("Bob", "intern", 0) && !off.onFeedback("fb-1", "A", "x", 0) && calls.isEmpty(), "disabled never posts");

        // classpath resource gate
        check(!AgentInternWebhook.fromClasspath(loader(null), "s", null).enabled(), "missing resource -> off");
        check(!AgentInternWebhook.fromClasspath(loader("url=https://x.test/h\n"), "s", null).enabled(), "url only -> off");
        check(AgentInternWebhook.fromClasspath(loader("url=https://x.test/h\nkey=abc\n"), "s", null).enabled(), "url+key -> on");
        check(InternWebhookTest.class.getClassLoader().getResource(AgentInternWebhook.RESOURCE) == null, "public build has no resource");

        AgentInternWebhook hook = new AgentInternWebhook("https://x.test/h", "sekret", "srv", rec, null);
        check(!hook.onPublicChat("Intern Steve", "I am the intern", 1_000), "Intern Steve ignored");
        check(!hook.onPublicChat("intern steve", "intern", 1_000), "Intern Steve ignored any case");
        check(!hook.onPublicChat("Bob", "internal stuff", 1_000), "non-word ignored");
        check(calls.isEmpty(), "nothing sent yet");
        check(hook.onPublicChat("Bob", "hey Intern", 1_000), "first mention sent");
        Call c = calls.get(0);
        check(c.url().toString().equals("https://x.test/h") && "sekret".equals(c.bearer()), "url + bearer");
        JsonObject o = JsonParser.parseString(c.json()).getAsJsonObject();
        check("intern".equals(o.get("reason").getAsString()) && "Bob".equals(o.get("from").getAsString()) && "hey Intern".equals(o.get("text").getAsString())
                && "1970-01-01T00:00:01Z".equals(o.get("time").getAsString()) && "srv".equals(o.get("server").getAsString()), "chat payload: " + c.json());
        check(!hook.onPublicChat("Bob", "admin", 30_000), "chat debounced inside 60s");
        check(!hook.onPublicChat("Bob", "intern", 60_999), "still debounced at 59.999s");
        check(hook.onPublicChat("Ann", "ADMIN help", 61_000), "sent after 60s");
        check("admin".equals(JsonParser.parseString(calls.get(1).json()).getAsJsonObject().get("reason").getAsString()), "admin reason");

        // feedback: own 10s cap, not affected by chat debounce
        check(hook.onFeedback("fb-7", "Builder Bot", "stuck on stairs", 62_000), "feedback sent right after chat");
        JsonObject f = JsonParser.parseString(calls.get(2).json()).getAsJsonObject();
        check("feedback".equals(f.get("reason").getAsString()) && "fb-7".equals(f.get("id").getAsString()) && "Builder Bot".equals(f.get("from").getAsString())
                && "stuck on stairs".equals(f.get("text").getAsString()) && f.has("time") && "srv".equals(f.get("server").getAsString()), "feedback payload: " + calls.get(2).json());
        check(!hook.onFeedback("fb-8", "Builder Bot", "again", 71_999), "feedback capped inside 10s");
        check(hook.onFeedback("fb-9", "Builder Bot", "later", 72_000), "feedback after 10s");
        check(!hook.onPublicChat("Bob", "intern", 80_000), "feedback does not reset chat window either way");
        check(calls.size() == 4, "total posts: " + calls.size());

        // AgentFeedback.submit fires the listener with the new entry
        AgentFeedback.resetForTest();
        AtomicReference<AgentFeedback.Entry> seen = new AtomicReference<>();
        AgentFeedback.onSubmit = seen::set;
        JsonObject in = new JsonObject(); in.addProperty("action", "feedback"); in.addProperty("agent", "x"); in.addProperty("text", "please add boats");
        AgentFeedback.submit(null, in);
        check(seen.get() != null && "fb-1".equals(seen.get().id) && "please add boats".equals(seen.get().text), "feedback listener fired");
        AgentFeedback.onSubmit = e -> { throw new RuntimeException("boom"); };
        AgentFeedback.submit(null, in);
        check(AgentFeedback.all().size() == 2, "listener failure does not break submit");
        AgentFeedback.onSubmit = null;
        AgentFeedback.resetForTest();

        // network errors: logged once, never thrown, never block
        List<String> logs = Collections.synchronizedList(new ArrayList<>());
        AgentInternWebhook dead = new AgentInternWebhook("http://127.0.0.1:1/x", "k", "s", null, logs::add);
        long t0 = System.nanoTime();
        check(dead.onPublicChat("Bob", "intern", 0) && dead.onFeedback("fb-1", "A", "x", 0) && dead.onPublicChat("Bob", "intern", 120_000), "posts started");
        check(System.nanoTime() - t0 < 2_000_000_000L, "non-blocking");
        long until = System.currentTimeMillis() + 10_000;
        while (logs.isEmpty() && System.currentTimeMillis() < until) Thread.sleep(20);
        Thread.sleep(300);
        check(logs.size() == 1 && logs.get(0).contains("failed"), "error logged once: " + logs);
        AgentInternWebhook throwing = new AgentInternWebhook("https://x.test/h", "k", "s", (u, b, j) -> { throw new IllegalStateException("x"); }, logs::add);
        check(!throwing.onPublicChat("Bob", "intern", 0), "transport throw swallowed");

        // real HTTP round trip: header + JSON body
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        AtomicReference<String> auth = new AtomicReference<>(), body = new AtomicReference<>(), type = new AtomicReference<>();
        CountDownLatch got = new CountDownLatch(1);
        server.createContext("/hook", ex -> {
            auth.set(ex.getRequestHeaders().getFirst("Authorization"));
            type.set(ex.getRequestHeaders().getFirst("Content-Type"));
            body.set(new String(ex.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            ex.sendResponseHeaders(204, -1); ex.close(); got.countDown();
        });
        server.start();
        try {
            AgentInternWebhook live = new AgentInternWebhook("http://127.0.0.1:" + server.getAddress().getPort() + "/hook", "tkn", "srv", null, logs::add);
            check(live.onPublicChat("Bob", "where is the intern?", 5_000), "live post started");
            check(got.await(10, TimeUnit.SECONDS), "server received post");
            check("Bearer tkn".equals(auth.get()) && type.get().startsWith("application/json"), "headers");
            JsonObject b = JsonParser.parseString(body.get()).getAsJsonObject();
            check("Bob".equals(b.get("from").getAsString()) && "where is the intern?".equals(b.get("text").getAsString()) && "intern".equals(b.get("reason").getAsString()), "live body");
        } finally { server.stop(0); }
        System.out.println("PASS: intern/admin whole-word match, Intern Steve excluded, off without url+key, 60s chat debounce, feedback webhook with 10s cap, async error logged once, bearer POST");
    }

    private static ClassLoader loader(String props) {
        return new ClassLoader(null) {
            @Override public InputStream getResourceAsStream(String name) {
                return props != null && AgentInternWebhook.RESOURCE.equals(name) ? new ByteArrayInputStream(props.getBytes(StandardCharsets.ISO_8859_1)) : null;
            }
            @Override public URL getResource(String name) { return null; }
            @Override public Enumeration<URL> getResources(String name) { return Collections.emptyEnumeration(); }
        };
    }
    private static void check(boolean ok, String what) { if (!ok) throw new AssertionError(what); }
}
