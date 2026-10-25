package dev.agentsmc;

import java.io.File;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.time.Duration;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Future;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.configuration.file.YamlConfiguration;
import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

/** Outbound HTTPS only. Network and disk work stay off the Minecraft server thread. */
final class AgentBridge implements AutoCloseable {
    /** Fast 100ms (10 Hz) action poll. Telemetry/viewer stays on a second worker at 1000ms (1 Hz)
     * so heartbeats cannot delay the next command pickup. */
    static final long FAST_POLL_MS = 100;
    static final long HEARTBEAT_INTERVAL_MS = 1_000;
    /** Offline body: defer the queued action to the next poll instead of sleeping the poller. */
    static final long BODY_WAIT_MS = 10_000;

    private static final Gson JSON = new Gson();
    private final AgentsMC plugin;
    private final ScheduledExecutorService worker = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread t = new Thread(r, "AgentsMC-HTTPS"); t.setDaemon(true); return t;
    });
    private final ScheduledExecutorService telemetry = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread t = new Thread(r, "AgentsMC-Telemetry"); t.setDaemon(true); return t;
    });
    private final java.util.concurrent.ExecutorService programWorker=Executors.newFixedThreadPool(4,r->{Thread t=new Thread(r,"AgentsMC-Program");t.setDaemon(true);return t;});
    private final Map<String,Future<JsonObject>> runningPrograms=new ConcurrentHashMap<>();
    private final Map<String,String> activeProgramByAgent=new ConcurrentHashMap<>();
    private final Map<String,String> stagedProgramByAgent=new ConcurrentHashMap<>();
    private static final Map<String,Map<String,Object>> liveProgramStatus=new ConcurrentHashMap<>();
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5))
            .followRedirects(HttpClient.Redirect.NEVER).build();
    private URI origin;
    private String serverToken;
    private String serverId = "";
    private volatile boolean revokedWarned;
    private Location spawn;
    private BridgeStore store;
    private volatile String status = "starting";
    // Old Sites do not accept additional heartbeat fields; advertise catalog only after negotiation.
    private volatile boolean catalogSupported;
    private volatile boolean chatLogSupported;
    private volatile boolean eventsSupported;
    private AgentEventStore eventStore;
    private volatile boolean failed;
    private volatile int lastHttpStatus;
    private volatile String lastHttpError = "none";
    /** 0.7.2: action ids whose result POST got HTTP 404; never re-posted. Bounded. */
    private final java.util.Set<String> droppedResults = java.util.Collections.newSetFromMap(new java.util.concurrent.ConcurrentHashMap<>());
    private void rememberDropped(String id) { if (droppedResults.size() > 4096) droppedResults.clear(); droppedResults.add(id); }
    private volatile String lastHeartbeatOk = "never";
    /** Server PIN / MCP connect URL, if the bridge reports them in poll responses ({@code pin}, {@code connectUrl}). */
    volatile String serverPin = "";
    volatile String connectUrl = "";
    private long lastTelemetryAt;
    private final Map<String,AgentViewerDelta> viewerDeltas=new java.util.HashMap<>();
    private final Map<String,AgentOutboundBackoff> viewerBackoff=new java.util.HashMap<>();
    private final Map<String,AgentOutboundBackoff> uiBackoff=new java.util.HashMap<>();
    private boolean loggedPollShape;
    private final Map<String, Long> lastNameWarn = new java.util.HashMap<>();
    private final Map<String, Long> offlineSince = new ConcurrentHashMap<>();
    /** Snapshot copied after each action poll so telemetry never shares the poll thread. */
    private volatile TelemetryFrame telemetryFrame;
    /** Action IDs present on the first successful poll after this plugin boot. */
    private Set<String> restartQueue;

    private AgentBridge(AgentsMC plugin) { this.plugin = plugin; }

    static AgentBridge start(AgentsMC plugin) {
        AgentBridge bridge = new AgentBridge(plugin);
        try { bridge.configure(); }
        catch (Exception error) {
            bridge.status = "failed: " + error.getMessage();
            plugin.getLogger().log(java.util.logging.Level.SEVERE, "AgentsMC bridge configuration failed", error);
            bridge.close();
        }
        return bridge;
    }

    /** Live program status for an agent (foreground or background), exposed in observe.program. */
    static Map<String, Object> programSnapshot(String rawAgentId) {
        if (rawAgentId == null || rawAgentId.isBlank()) return Map.of("active", false, "status", "idle");
        String key = AgentNames.dashless(rawAgentId);
        Map<String, Object> cur = liveProgramStatus.get(key);
        if (cur == null) return Map.of("active", false, "status", "idle");
        return new LinkedHashMap<>(cur);
    }

    static void resetProgramSnapshotsForTest() {
        liveProgramStatus.clear();
    }

    static void updateProgramStatus(String rawAgentId, String programId, boolean background, String status, int step, int totalSteps, String currentAction) {
        if (rawAgentId == null || rawAgentId.isBlank()) return;
        String key = AgentNames.dashless(rawAgentId);
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("active", "running".equals(status));
        m.put("id", programId == null ? "" : programId);
        m.put("background", background);
        m.put("status", status == null ? "idle" : status);
        m.put("step", step);
        m.put("totalSteps", totalSteps);
        if (currentAction != null && !currentAction.isBlank()) m.put("currentAction", currentAction);
        m.put("updatedAt", System.currentTimeMillis());
        liveProgramStatus.put(key, m);
    }

    private void configure() throws Exception {
        File file = new File(plugin.getDataFolder(), "bridge.yml");
        if (!file.exists()) {
            Files.createDirectories(file.toPath().getParent());
            plugin.saveResource("bridge.yml", false);
        }
        YamlConfiguration config = new YamlConfiguration();
        config.load(file);
        // The jar never carries credentials: the data-folder bridge.yml is the only source (plus env vars).
        serverId = config.getString("server-id", "");
        if (serverId == null) serverId = "";
        boolean explicitSpawn = config.get("x") instanceof Number && config.get("y") instanceof Number && config.get("z") instanceof Number;
        if (!(config.get("enabled") instanceof Boolean)) throw new IllegalArgumentException("bridge.yml requires boolean enabled");
        Settings settings = resolveSettings(
                config.getBoolean("enabled"),
                config.getString("url", ""),
                config.getString("server-token", ""),
                config.getString("world", "world"),
                config.get("x") instanceof Number ? config.getDouble("x") : 0.5,
                config.get("y") instanceof Number ? config.getDouble("y") : 64,
                config.get("z") instanceof Number ? config.getDouble("z") : 0.5,
                config.get("poll-seconds") instanceof Number ? (int) config.getDouble("poll-seconds") : 1,
                System.getenv("AGENTSMC_BRIDGE_ENABLED"),
                System.getenv("AGENTSMC_BRIDGE_URL"),
                System.getenv("AGENTSMC_SERVER_TOKEN"));
        if (settings.enabled && !config.getBoolean("enabled") && tokenOk(settings.token)) {
            config.set("enabled", true);
            config.set("url", settings.url);
            config.set("server-token", settings.token);
            config.save(file);
            plugin.getLogger().info("bridge.yml enabled from AGENTSMC_SERVER_TOKEN");
        }
        if (!settings.enabled) {
            status = "disabled: not linked. Get a link code from the Servers dashboard at " + settings.url + " and run /agentsmc link <code>";
            worker.shutdown();
            plugin.getLogger().info(status);
            return;
        }
        origin = URI.create(settings.url);
        if (!"https".equals(origin.getScheme()) || origin.getHost() == null || origin.getUserInfo() != null
                || origin.getRawQuery() != null || origin.getRawFragment() != null
                || !(origin.getPath().isEmpty() || origin.getPath().equals("/")))
            throw new IllegalArgumentException("bridge url must be an HTTPS origin without a path, query, or credentials");
        serverToken = settings.token;
        if (!tokenOk(serverToken)) throw new IllegalArgumentException("Invalid server-token format (" + maskToken(serverToken) + "); run /agentsmc link <code>");
        World world = Bukkit.getWorld(settings.world);
        if (world == null && !explicitSpawn && !Bukkit.getWorlds().isEmpty()) world = Bukkit.getWorlds().get(0);
        if (world == null) throw new IllegalArgumentException("Configured spawn world does not exist");
        spawn = explicitSpawn ? new Location(world, settings.x, settings.y, settings.z) : world.getSpawnLocation();
        if (spawn.getY() < world.getMinHeight() || spawn.getY() >= world.getMaxHeight() || !world.getWorldBorder().isInside(spawn))
            throw new IllegalArgumentException("Configured spawn position is outside the world bounds");
        double seconds = settings.pollSeconds;
        if (seconds != Math.rint(seconds) || seconds < 1 || seconds > 60)
            throw new IllegalArgumentException("poll-seconds must be an integer from 1 to 60");
        if(seconds!=1){config.set("poll-seconds",1);config.save(file);plugin.getLogger().info("Viewer update: poll-seconds changed to 1");}
        plugin.getLogger().info("AgentsMC bridge linked: url=" + origin + " serverId=" + (serverId.isBlank() ? "(unknown)" : serverId) + " token=" + maskToken(serverToken));
        eventStore = new AgentEventStore(new File(plugin.getDataFolder(), "agent-events.json").toPath());
        AgentEvents.attach(eventStore);
        AgentNames.attach(new File(plugin.getDataFolder(), "registered-names.json").toPath());
        worker.scheduleWithFixedDelay(this::tick, 0, FAST_POLL_MS, TimeUnit.MILLISECONDS);
        telemetry.scheduleWithFixedDelay(this::telemetryTick, HEARTBEAT_INTERVAL_MS, HEARTBEAT_INTERVAL_MS, TimeUnit.MILLISECONDS);
    }

    /** Offline body: run now, skip this poll (do not sleep), or fail after {@link #BODY_WAIT_MS}. */
    static String bodyReady(boolean online, long waitedMs) {
        if (online) return "run";
        if (waitedMs >= BODY_WAIT_MS) return "fail";
        return "defer";
    }

    private void tick() {
        if (failed) return;
        try {
            if (store == null) {
                try { store = new BridgeStore(new File(plugin.getDataFolder(), "bridge-state.json").toPath()); }
                catch (IOException failure) { throw new IllegalStateException("Cannot read durable bridge state", failure); }
            }
            JsonObject poll = request("GET", "/api/server/poll", null);
            if (!poll.has("registrations") || !poll.get("registrations").isJsonArray()
                    || !poll.has("actions") || !poll.get("actions").isJsonArray()
                    || !poll.has("agentIds") || !poll.get("agentIds").isJsonArray()
                    || !poll.has("viewers") || !poll.get("viewers").isJsonArray())
                throw new IllegalStateException("Server poll requires registrations, actions, agentIds, and viewers arrays");
            String pinNow = AgentsMC.pinOf(poll), connectNow = AgentsMC.connectUrlOf(poll);
            if (!pinNow.isEmpty()) serverPin = pinNow;
            if (!connectNow.isEmpty()) connectUrl = connectNow;
            catalogSupported = false;
            chatLogSupported = false;
            boolean eventsNow = false;
            if (poll.has("features") && poll.get("features").isJsonArray()) {
                JsonArray features = poll.getAsJsonArray("features");
                for (var feature : features)
                    if (feature.isJsonPrimitive() && feature.getAsString().equals("agent-action-catalog-v1"))
                        catalogSupported = true;
                eventsNow = AgentEvents.supports(features);
                chatLogSupported = AgentChatLog.supports(features);
            }
            boolean eventsJustOn = eventsNow && !eventsSupported;
            eventsSupported = eventsNow;
            if (!loggedPollShape) {
                plugin.getLogger().info("Bridge poll shape: " + AgentNames.pollShape(poll));
                loggedPollShape = true;
            }
            Map<String, String> pollNames = AgentNames.namesFromPoll(poll);
            Set<String> currentAgents = new HashSet<>();
            for (var id : poll.getAsJsonArray("agentIds")) {
                String value = AgentEvents.hyphenated(AgentNames.pollId(id));
                if (value.isBlank()) continue;
                BridgeProtocol.localId(value);
                currentAgents.add(value);
            }
            if (eventsNow) {
                if (eventsJustOn) {
                    AgentEvents.onConnected(currentAgents);
                    plugin.getLogger().info("agent-events-v1: posting event catalog for " + currentAgents.size()
                            + " linked agents " + currentAgents);
                }
                int linked = AgentEvents.ensureLinked(currentAgents);
                if (linked > 0) plugin.getLogger().info("agent-events-v1: catalog due for " + linked + " newly linked agents");
                int updated = AgentEvents.announceRunningVersion(currentAgents);
                if (updated > 0) plugin.getLogger().info("plugin.updated " + AgentVersion.current(plugin)
                        + " queued for " + updated + " agents");
            }
            for (String lost : currentAgents) {
                if (storeHas(lost)) continue;
                boolean pending = AgentNames.arrayContainsId(poll.getAsJsonArray("registrations"), lost);
                if (pending) continue;
                String credential = store.register(lost);
                plugin.getLogger().warning("Agent " + lost + " had no local credential binding (plugin data was reset); rebinding it");
                heartbeat(lost, () -> plugin.recoverBridgeAgent(lost, credential, spawn.getWorld().getHighestBlockAt(spawn.getWorld().getSpawnLocation()).getLocation().add(0.5, 1, 0.5)));
            }
            for (var value : poll.getAsJsonArray("registrations")) {
                String id = AgentEvents.hyphenated(AgentNames.pollId(value));
                String name = AgentNames.pollName(value);
                if (id.isBlank()) continue;
                BridgeProtocol.localId(id);
                if (!currentAgents.contains(id)) throw new IllegalStateException("Registration is absent from agentIds");
                if (!AgentNames.valid(name)) name = pollNames.getOrDefault(AgentNames.dashless(id), "");
                String credential = store.register(id);
                String apply = name;
                heartbeat(id, () -> plugin.provisionBridgeAgent(id, apply, credential, spawn.getWorld().getHighestBlockAt(spawn.getWorld().getSpawnLocation()).getLocation().add(0.5, 1, 0.5)));
            }
            JsonArray actions = poll.getAsJsonArray("actions");
            Set<String> queuedIds = new HashSet<>();
            for (var value : actions) {
                String id = BridgeProtocol.string(value.getAsJsonObject(), "id");
                if (!queuedIds.add(id)) throw new IllegalStateException("Duplicate action ID in queue");
            }
            store.prune(queuedIds);
            restartQueue = AgentRestart.captureQueue(restartQueue, queuedIds);
            // A program whose queue entry vanished (cancelled/expired at the Site) must not block later actions forever.
            for (String stale : Set.copyOf(runningPrograms.keySet())) if (!queuedIds.contains(stale)) {
                Future<JsonObject> f = runningPrograms.remove(stale); if (f != null) f.cancel(true);
                activeProgramByAgent.values().removeIf(stale::equals);
                plugin.getLogger().info("Dropped program " + stale + " no longer queued at the Site");
            }
            activeProgramByAgent.values().removeIf(pid -> !queuedIds.contains(pid));
            stagedProgramByAgent.values().removeIf(pid -> !queuedIds.contains(pid));

            // Pass 1: Finalize any completed foreground programs first so pipelined next-programs can start on this exact tick.
            for (var value : actions) {
                JsonObject queued = value.getAsJsonObject();
                String id = BridgeProtocol.string(queued, "id");
                String agentId = BridgeProtocol.string(queued, "agent_id");
                String action = BridgeProtocol.string(queued, "action");
                Future<JsonObject> pendingProgram = runningPrograms.get(id);
                if (pendingProgram != null && pendingProgram.isDone()) {
                    JsonObject result;
                    try { result = pendingProgram.get(); }
                    catch (ExecutionException failure) { result = actionFailure(failure.getCause()); }
                    catch (java.util.concurrent.CancellationException cancelled) { result = actionFailure(cancelled); }
                    catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); throw interrupted; }
                    runningPrograms.remove(id);
                    activeProgramByAgent.remove(agentId, id);
                    store.complete(id, result);
                    updateProgramStatus(agentId, id, false, Boolean.TRUE.equals(result.get("ok") != null && result.get("ok").getAsBoolean()) ? "completed" : "failed", 0, 0, "");
                    postActionResult(agentId, id, action, result);
                }
            }

            // Pass 2: Execute ready actions, concurrent-safe actions, preempting actions, or staged programs.
            for (var value : actions) {
                JsonObject queued = value.getAsJsonObject();
                String id = BridgeProtocol.string(queued, "id");
                BridgeProtocol.localId(id);
                String agentId = BridgeProtocol.string(queued, "agent_id");
                String action = BridgeProtocol.string(queued, "action");
                JsonObject payload = queued.getAsJsonObject("payload");
                if (!currentAgents.contains(agentId)) throw new IllegalStateException("Queued action is absent from agentIds");
                String fingerprint = agentId + "\n" + action + "\n" + payload;

                if (runningPrograms.containsKey(id)) continue;

                JsonObject existingResult = store.previous(id, fingerprint);
                if (existingResult != null && store.hasResult(id)) {
                    postActionResult(agentId, id, action, existingResult);
                    continue;
                }

                if (AgentRestart.stale(restartQueue, id)) {
                    if (!store.hasResult(id)) {
                        store.cancelIncomplete(id, fingerprint, AgentRestart.cancelled(action));
                        plugin.getLogger().info("Cancelled queued action " + id + " (" + action + ") reason=" + AgentRestart.REASON);
                    }
                    postActionResult(agentId, id, action, store.previous(id, fingerprint));
                    continue;
                }

                String activeProgram = activeProgramByAgent.get(agentId);
                if (activeProgram != null && !activeProgram.equals(id)) {
                    if (AgentActionProgram.isPreempt(action, payload)) {
                        Future<JsonObject> prev = runningPrograms.remove(activeProgram);
                        if (prev != null) prev.cancel(true);
                        activeProgramByAgent.remove(agentId, activeProgram);
                        updateProgramStatus(agentId, activeProgram, false, "preempted", 0, 0, action);
                    } else if (!AgentActionProgram.concurrentSafe(action, payload)) {
                        // Stage the next exclusive program so it starts immediately when activeProgram finishes.
                        stagedProgramByAgent.putIfAbsent(agentId, id);
                        continue;
                    }
                }
                stagedProgramByAgent.remove(agentId, id);

                String credential = store.credential(agentId);
                JsonObject result;
                String ready = waitForBody(id, credential);
                if ("defer".equals(ready)) continue;
                if ("fail".equals(ready)) {
                    result = actionFailure(new IllegalStateException("Body is not spawned; queued action timed out after 10s offline"), action);
                    store.begin(id, fingerprint);
                    store.complete(id, result);
                } else {
                    store.begin(id, fingerprint);
                    if (action.equals("execute") && payload.has("steps")) {
                        int stepCount = payload.get("steps").isJsonArray() ? payload.getAsJsonArray("steps").size() : 0;
                        if (AgentActionProgram.isBackground(payload)) {
                            // Non-blocking background program: return immediate receipt so the LLM can keep thinking!
                            updateProgramStatus(agentId, id, true, "running", 1, stepCount, "starting");
                            programWorker.submit(() -> {
                                try {
                                    Map<String, Object> bgOut = executeProgram(agentId, id, true, credential, payload);
                                    JsonObject bgJson = withError(jsonResult(bgOut));
                                    updateProgramStatus(agentId, id, true,
                                            bgJson.has("ok") && bgJson.get("ok").getAsBoolean() ? "completed" : "failed",
                                            stepCount, stepCount, "done");
                                    AgentEvents.emitAction(agentId, id, "execute", bgJson);
                                } catch (Throwable failure) {
                                    JsonObject bgFail = withError(actionFailure(failure, "execute"));
                                    updateProgramStatus(agentId, id, true, "failed", 0, stepCount, "failed");
                                    AgentEvents.emitAction(agentId, id, "execute", bgFail);
                                }
                            });
                            JsonObject bgReceipt = new JsonObject();
                            bgReceipt.addProperty("ok", true);
                            bgReceipt.addProperty("action", "execute");
                            bgReceipt.addProperty("agentId", AgentIds.publicId(agentId));
                            bgReceipt.addProperty("background", true);
                            bgReceipt.addProperty("state", "running");
                            bgReceipt.addProperty("status", "running");
                            bgReceipt.addProperty("steps", stepCount);
                            bgReceipt.addProperty("note", "Program accepted in background; body executes steps in real time while agent thinks. Check observe.program or agent-events-v1.");
                            result = bgReceipt;
                            store.complete(id, result);
                        } else if (AgentActionProgram.concurrentSafe(action, payload)) {
                            // Concurrent-safe multi-step program (e.g. craft + equip + say while walking): run without blocking movement.
                            try { result = jsonResult(executeProgram(agentId, id, false, credential, payload)); }
                            catch (RuntimeException failure) { result = actionFailure(failure, "execute"); }
                            store.complete(id, result);
                        } else {
                            activeProgramByAgent.put(agentId, id);
                            updateProgramStatus(agentId, id, false, "running", 1, stepCount, "starting");
                            runningPrograms.put(id, programWorker.submit(() -> {
                                try { return jsonResult(executeProgram(agentId, id, false, credential, payload)); }
                                catch (Throwable failure) { return actionFailure(failure); }
                            }));
                            continue;
                        }
                    } else {
                        try { result = jsonResult(sync(() -> plugin.executeBridgeAction(credential, action, payload))); }
                        catch (RuntimeException failure) {
                            result = actionFailure(failure, action.equals("execute") && payload.has("action") && payload.get("action").isJsonPrimitive()
                                    ? payload.get("action").getAsString() : action);
                        }
                        store.complete(id, result);
                    }
                }
                postActionResult(agentId, id, action, result);
            }

            if (eventsSupported) for (String id : currentAgents) publishEvents(id);

            Map<String, String> creds = new LinkedHashMap<>();
            for (String id : store.agents().keySet()) {
                try { creds.put(id, store.credential(id)); } catch (RuntimeException ignored) {}
            }
            telemetryFrame = new TelemetryFrame(currentAgents, pollNames, poll.deepCopy(), creds);
            if (!status.equals("connected")) plugin.getLogger().info("AgentsMC HTTPS bridge connected");
            status = "connected";
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
        } catch (IOException network) {
            status = "disconnected: " + network.getClass().getSimpleName();
            plugin.getLogger().warning("AgentsMC HTTPS request failed: " + network.getMessage() + "; next poll will retry");
        } catch (RuntimeException failure) {
            recoverPoll(failure);
        }
    }

    private void postActionResult(String agentId, String id, String action, JsonObject rawResult) throws InterruptedException, IOException {
        JsonObject result = withError(rawResult);
        AgentEvents.emitAction(agentId, id, action, result);
        JsonObject body = new JsonObject();
        body.add("result", result);
        if (droppedResults.contains(id)) return;
        try {
            request("POST", "/api/server/actions/" + id, body);
            lastHttpError = "none";
        }
        catch (RemovedTarget removed) { plugin.getLogger().info("Queued action was cancelled or already acknowledged; it will not be replayed"); }
        catch (RuntimeException post) {
            if (BridgeProtocol.dropActionResult(post.getMessage())) {
                rememberDropped(id);
                plugin.getLogger().warning("Action result post for " + id + " got HTTP 404; the bridge no longer knows this action, not retrying");
            } else plugin.getLogger().warning("Action result post failed for " + id + ": " + post.getMessage() + "; will retry");
        }
    }

    /** Log a poll/heartbeat failure and keep the HTTPS worker running so the next cycle retries. */
    void recoverPoll(Throwable failure) {
        StackTraceElement[] st = failure == null ? new StackTraceElement[0] : failure.getStackTrace();
        String where = st.length > 0 ? " @ " + st[0] : "";
        if (lastHttpStatus == 401) { status = REVOKED_STATUS; return; }
        status = "recovering: " + (failure == null ? "unknown" : failure.getClass().getSimpleName() + ": " + failure.getMessage()) + where;
        plugin.getLogger().log(java.util.logging.Level.WARNING, "AgentsMC poll/heartbeat failed; retrying next cycle. lastHttp=" + lastHttpStatus + " lastError=" + lastHttpError, failure);
    }

    private void heartbeat(String id, Callable<Map<String, Object>> snapshot) throws InterruptedException {
        Map<String, Object> state;
        try { state = sync(snapshot); }
        catch (RuntimeException snap) {
            plugin.getLogger().warning("Heartbeat snapshot failed for " + id + ": " + snap.getMessage());
            return;
        }
        try { sendState(id, state); }
        catch (RemovedTarget removed) { plugin.getLogger().info("Agent registration was removed during a heartbeat; reconciling on the next poll"); }
        catch (IOException network) {
            plugin.getLogger().warning("Heartbeat network failed for " + id + ": " + network.getMessage());
        }
        catch (RuntimeException post) {
            plugin.getLogger().warning("Heartbeat post failed for " + id + ": " + post.getMessage());
        }
    }

    private void sendState(String id, Map<String, Object> state) throws IOException, InterruptedException {
        JsonObject body = JSON.toJsonTree(state).getAsJsonObject();
        if (!catalogSupported) body.remove("capabilities");
        if (!chatLogSupported) body.remove("chatLog");
        body.addProperty("agentId", id);
        try {
            request("POST", "/api/server/agent", body);
            lastHeartbeatOk = java.time.ZonedDateTime.now().withNano(0).toString();
        }
        catch (RemovedTarget removed) { plugin.getLogger().info("Agent registration was removed during a heartbeat; reconciling on the next poll"); }
    }

    private Map<String,Object> executeProgram(String agentId, String programId, boolean background, String credential, JsonObject payload) throws InterruptedException {
        String world=sync(()->plugin.bridgeWorld(credential));
        int totalSteps = payload.has("steps") && payload.get("steps").isJsonArray() ? payload.getAsJsonArray("steps").size() : 1;
        return AgentActionProgram.execute(payload,world,(step,index)->{
            String verb=step.get("action").getAsString();
            updateProgramStatus(agentId, programId, background, "running", index + 1, totalSteps, verb);
            Map<String,Object> outcome=AgentErrors.ensureError(syncUnchecked(()->plugin.executeBridgeProgramStep(credential,step)));
            if(Boolean.TRUE.equals(outcome.get("ok"))&&(verb.equals("use_portal")||verb.equals("portal"))&&outcome.get("portalId")!=null&&!AgentActionProgram.isBackground(step)){
                try{return AgentPortal.await(outcome);}
                catch(InterruptedException interrupted){Thread.currentThread().interrupt();throw new IllegalStateException("use_portal wait was interrupted",interrupted);}
            }
            if(!Boolean.TRUE.equals(outcome.get("ok"))||!verb.equals("goto"))return outcome;
            if(AgentActionProgram.isBackground(step)){
                Map<String,Object> bgStep=new LinkedHashMap<>(outcome);
                bgStep.put("background",true);
                bgStep.put("state","accepted");
                bgStep.put("status","navigating");
                return bgStep;
            }
            String abortRule = AgentActionProgram.abortIfRule(payload, step);
            return AgentActionProgram.awaitNavigation(outcome,
                    ()->syncUnchecked(()->plugin.bridgeNavigationSnapshot(credential)),
                    ()->syncUnchecked(()->{plugin.stopBridgeNavigation(credential);return null;}),
                    abortRule.isEmpty() ? null : ()->syncUnchecked(()->plugin.bridgeInterruptReason(credential, abortRule)),
                    TimeUnit.SECONDS.toNanos(60),
                    AgentActionProgram.NAV_POLL_MS);
        });
    }

    static JsonObject actionFailure(Throwable failure){return actionFailure(failure,null);}
    static JsonObject actionFailure(Throwable failure,String action){JsonObject result=new JsonObject();result.addProperty("ok",false);if(action!=null)result.addProperty("action",action);result.addProperty("error",AgentErrors.message(failure,action));return result;}
    /** Gson {@code toJsonTree(null).getAsJsonObject()} throws; every posted action must be a JSON object. */
    static JsonObject jsonResult(Object raw){
        if(raw==null)return withError(null);
        try{
            JsonElement el=JSON.toJsonTree(raw);
            if(el==null||!el.isJsonObject())return withError(null);
            return withError(el.getAsJsonObject());
        }catch(RuntimeException failure){return actionFailure(failure);}
    }
    /** Every non-ok result posted to the bridge carries a non-empty top-level error. */
    @SuppressWarnings("unchecked")
    static JsonObject withError(JsonObject result){
        if(result==null){JsonObject r=new JsonObject();r.addProperty("ok",false);r.addProperty("error","Action produced no result");return r;}
        boolean ok=result.has("ok")&&result.get("ok").isJsonPrimitive()&&result.get("ok").getAsJsonPrimitive().isBoolean()&&result.get("ok").getAsBoolean();
        boolean hasError=result.has("error")&&result.get("error").isJsonPrimitive()&&!result.get("error").getAsString().isBlank();
        if(ok||hasError)return result;
        Map<String,Object> fixed=AgentErrors.ensureError(JSON.fromJson(result,java.util.LinkedHashMap.class));
        return JSON.toJsonTree(fixed).getAsJsonObject();
    }

    /** {@code run}, {@code defer} (next poll, no sleep), or {@code fail} after {@link #BODY_WAIT_MS}. */
    private String waitForBody(String actionId, String credential) throws InterruptedException {
        boolean online = false;
        try { online = Boolean.TRUE.equals(sync(() -> plugin.prepareBody(credential))); }
        catch (RuntimeException ignored) {}
        if (online) {
            offlineSince.remove(actionId);
            return "run";
        }
        long now = System.currentTimeMillis();
        long started = offlineSince.computeIfAbsent(actionId, id -> now);
        String ready = bodyReady(false, now - started);
        if ("fail".equals(ready)) offlineSince.remove(actionId);
        return ready;
    }

    private void telemetryTick() {
        if (failed) return;
        TelemetryFrame frame = telemetryFrame;
        if (frame == null) return;
        long now = System.currentTimeMillis();
        if (now - lastTelemetryAt < HEARTBEAT_INTERVAL_MS) return;
        lastTelemetryAt = now;
        try {
            for (String id : frame.creds.keySet()) {
                if (!frame.agents.contains(id) && frame.agents.stream().noneMatch(a -> AgentNames.sameId(a, id))) continue;
                String pollName = frame.names.getOrDefault(AgentNames.dashless(id), "");
                String warn;
                try { warn = sync(() -> plugin.applyPollName(id, pollName, frame.poll)); }
                catch (RuntimeException nameFail) {
                    plugin.getLogger().warning("applyPollName failed for " + id + ": " + nameFail.getMessage());
                    warn = null;
                }
                if (warn != null && !warn.isBlank()) {
                    Long prev = lastNameWarn.get(AgentNames.dashless(id));
                    if (prev == null || now - prev >= 60_000) {
                        lastNameWarn.put(AgentNames.dashless(id), now);
                        plugin.getLogger().warning(warn);
                    }
                }
                String credential = frame.creds.get(id);
                if (credential != null) heartbeat(id, () -> plugin.bridgeSnapshot(credential));
            }
            Set<String> activeSessions = new HashSet<>();
            if (frame.poll.has("viewers") && frame.poll.get("viewers").isJsonArray()) {
                for (var value : frame.poll.getAsJsonArray("viewers")) {
                    try { publishViewer(value.getAsJsonObject(), frame.agents, activeSessions); }
                    catch (RuntimeException viewerFail) {
                        plugin.getLogger().warning("Viewer publish skipped: " + viewerFail.getMessage());
                    }
                }
            }
            viewerDeltas.keySet().retainAll(activeSessions);
            viewerBackoff.keySet().retainAll(frame.agents);
            for (String id : frame.agents) publishUi(id);
            uiBackoff.keySet().retainAll(frame.agents);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
        } catch (RuntimeException failure) {
            plugin.getLogger().warning("Telemetry tick failed: " + failure.getMessage());
        }
    }

    private <T>T syncUnchecked(Callable<T> operation){
        try{return sync(operation);}catch(InterruptedException interrupted){Thread.currentThread().interrupt();throw new IllegalStateException("Program execution was interrupted",interrupted);}
    }

    /** One viewer's 400/5xx must not stop heartbeats or other agents' frames. */
    private void publishViewer(JsonObject viewer, Set<String> currentAgents, Set<String> activeSessions) throws InterruptedException {
        String agentId = BridgeProtocol.string(viewer, "agentId");
        String sessionId = BridgeProtocol.string(viewer, "sessionId");
        String mode = BridgeProtocol.string(viewer, "mode");
        activeSessions.add(sessionId);
        BridgeProtocol.localId(agentId);
        BridgeProtocol.localId(sessionId);
        if (!mode.equals("first") && !mode.equals("third")) {
            plugin.getLogger().warning("Viewer mode must be first or third for " + agentId + " session " + sessionId);
            return;
        }
        if (!currentAgents.contains(agentId)) return;
        long now = System.currentTimeMillis();
        AgentOutboundBackoff wait = viewerBackoff.computeIfAbsent(agentId, key -> new AgentOutboundBackoff());
        if (!wait.due(now)) return;
        String credential = store.credential(agentId);
        Map<String, Object> snapshot;
        try { snapshot = sync(() -> plugin.bridgeViewerSnapshot(credential)); }
        catch (RuntimeException snap) {
            long delay = wait.fail(now);
            plugin.getLogger().warning("Viewer snapshot failed for " + agentId + ": " + snap.getMessage()
                    + "; retry in " + delay + "ms");
            return;
        }
        if (snapshot == null) { wait.ok(); return; }
        JsonObject frame = JSON.toJsonTree(snapshot).getAsJsonObject();
        frame.addProperty("sessionId", sessionId);
        frame.addProperty("mode", mode);
        AgentViewerDelta delta = viewerDeltas.computeIfAbsent(sessionId, key -> new AgentViewerDelta());
        JsonObject packet = delta.packet(frame);
        packet.addProperty("sessionId", sessionId);
        packet.addProperty("mode", mode);
        try {
            HttpResponse<String> response = exchange("POST", "/api/server/viewer/" + agentId, packet);
            int status = response.statusCode();
            if (status == 409) {
                delta.reset();
                wait.ok();
                plugin.getLogger().warning("Viewer base sequence expired; next capture will send a full frame for session " + sessionId);
                return;
            }
            if (BridgeProtocol.removedTarget(status, "/api/server/viewer/" + agentId, response.body())) {
                wait.ok();
                plugin.getLogger().info("Viewer session closed during frame capture; it will be removed from the next poll");
                return;
            }
            if (status >= 200 && status < 300) {
                delta.acknowledge(frame);
                wait.ok();
                return;
            }
            long delay = wait.fail(now);
            plugin.getLogger().warning("Viewer POST " + agentId + " HTTP " + status
                    + " backoff " + delay + "ms: "
                    + (response.body() == null ? "" : response.body().substring(0, Math.min(200, response.body().length()))));
        } catch (IOException network) {
            long delay = wait.fail(now);
            plugin.getLogger().warning("Viewer POST failed for " + agentId + ": " + network.getMessage()
                    + "; retry in " + delay + "ms");
        }
    }

    /** Event 4xx must not stop the HTTPS worker; actions still poll after a bad events response. */
    private void publishEvents(String agentId) throws InterruptedException {
        AgentEvents.Job job = AgentEvents.takeJob(agentId);
        if (job == null) return;
        try {
            HttpResponse<String> response = exchange("POST", "/api/server/events/" + agentId, job.body);
            int status = response.statusCode();
            String disposition = AgentEvents.disposition(status);
            if ("ack".equals(disposition))
                plugin.getLogger().info(AgentEvents.publishLog(agentId, job, status, disposition));
            else if (status == 401 || status == 404 || status == 409 || status == 400)
                plugin.getLogger().severe(AgentEvents.statusLog(status, agentId, response.body()));
            else
                plugin.getLogger().warning(AgentEvents.statusLog(status, agentId, response.body()));
            AgentEvents.finish(agentId, job, disposition);
        } catch (IOException network) {
            plugin.getLogger().warning("events POST failed for " + agentId + ": " + network.getMessage() + "; will retry with the same IDs");
        }
    }

    private void publishUi(String agentId) throws InterruptedException {
        long now = System.currentTimeMillis();
        AgentOutboundBackoff wait = uiBackoff.computeIfAbsent(agentId, key -> new AgentOutboundBackoff());
        if (!wait.due(now)) return;
        JsonObject body;
        try { body = sync(() -> plugin.uiPublish(agentId)); }
        catch (RuntimeException failure) {
            long delay = wait.fail(now);
            plugin.getLogger().warning("ui snapshot failed for " + agentId + ": " + failure.getMessage()
                    + "; retry in " + delay + "ms");
            return;
        }
        if (body == null) { wait.ok(); return; }
        String fingerprint = AgentUi.takeFingerprint(body);
        try {
            HttpResponse<String> response = exchange("POST", "/api/server/ui/" + agentId, body);
            int status = response.statusCode();
            if (status >= 200 && status < 300) {
                wait.ok();
                if (AgentUi.markPublished(agentId, fingerprint, System.currentTimeMillis()))
                    plugin.getLogger().info("Published UI panel for " + agentId + " HTTP " + status);
            } else {
                long delay = wait.fail(now);
                plugin.getLogger().warning("ui POST " + agentId + " HTTP " + status + " backoff " + delay + "ms: "
                        + (response.body() == null ? "" : response.body().substring(0, Math.min(200, response.body().length()))));
            }
        } catch (IOException network) {
            long delay = wait.fail(now);
            plugin.getLogger().warning("ui POST failed for " + agentId + ": " + network.getMessage()
                    + "; retry in " + delay + "ms");
        }
    }

    private HttpResponse<String> exchange(String method, String path, JsonObject body) throws IOException, InterruptedException {
        HttpRequest.Builder builder = HttpRequest.newBuilder(origin.resolve(path)).timeout(Duration.ofSeconds(10))
                .header("Authorization", "Bearer " + serverToken).header("Accept", "application/json")
                .header("User-Agent", "AgentsMC/" + plugin.getDescription().getVersion())
                .header("X-AgentsMC-Plugin-Version", plugin.getDescription().getVersion());
        if (serverId != null && !serverId.isBlank()) builder.header("X-AgentsMC-Server-Id", serverId);
        if (method.equals("GET")) builder.GET();
        else builder.header("Content-Type", "application/json").POST(HttpRequest.BodyPublishers.ofString(body.toString()));
        HttpResponse<String> response;
        try { response = http.send(builder.build(), HttpResponse.BodyHandlers.ofString()); }
        catch (IOException network) { lastHttpError = method+" "+path+": "+network; throw network; }
        lastHttpStatus = response.statusCode();
        if (response.statusCode() == 401) {
            status = REVOKED_STATUS;
            if (!revokedWarned) {
                revokedWarned = true;
                plugin.getLogger().severe(revokedMessage(origin == null ? DEFAULT_URL : origin.toString()));
            }
        } else if (response.statusCode() >= 200 && response.statusCode() < 300) revokedWarned = false;
        if (response.statusCode() < 200 || response.statusCode() >= 300) {
            String b = String.valueOf(response.body()); lastHttpError = method+" "+path+" HTTP "+response.statusCode()+": "+(b.length()>200?b.substring(0,200):b);
        }
        return response;
    }

    private JsonObject request(String method, String path, JsonObject body) throws IOException, InterruptedException {
        HttpResponse<String> response = exchange(method, path, body);
        if (BridgeProtocol.removedTarget(response.statusCode(), path, response.body())) throw new RemovedTarget();
        if(response.statusCode()==409&&path.startsWith("/api/server/viewer/"))throw new ViewerResync();
        if (response.statusCode() >= 500) throw new IOException("Bridge "+method+" "+path+" returned HTTP " + response.statusCode()+"\n"+response.body());
        if (response.statusCode() < 200 || response.statusCode() >= 300) {
            String sent = body == null ? "" : describeKeys(body);
            plugin.getLogger().severe("Bridge rejected "+method+" "+path+" HTTP "+response.statusCode()+" sentKeys="+sent+" response="+response.body());
            lastHttpError = method+" "+path+" HTTP "+response.statusCode()+" sentKeys="+sent+": "+response.body();
            throw new IllegalStateException("Bridge " + method + " " + path + " returned HTTP " + response.statusCode()+" sentKeys="+sent+"\n"+response.body());
        }
        if (response.body().length() > 4_000_000) throw new IllegalStateException("Bridge response exceeds 4 MB");
        return JsonParser.parseString(response.body()).getAsJsonObject();
    }

    private static String describeKeys(JsonObject o) {
        StringBuilder b=new StringBuilder("{");
        for (String k : o.keySet()) { b.append(k); var v=o.get(k); if (v!=null&&v.isJsonObject()) b.append(describeKeys(v.getAsJsonObject())); b.append(','); }
        return b.append('}').toString();
    }

    private static final class ViewerResync extends RuntimeException { }
    private static final class RemovedTarget extends RuntimeException { }

    private <T> T sync(Callable<T> operation) throws InterruptedException {
        try { return Bukkit.getScheduler().callSyncMethod(plugin, operation).get(); }
        catch (ExecutionException failure) {
            if (failure.getCause() instanceof RuntimeException runtime) throw runtime;
            throw new IllegalStateException("Minecraft operation failed", failure.getCause());
        }
    }

    static final String DEFAULT_URL = "https://agentsmc-bridge.alxtheprogrammer.chatgpt.site";
    /** Legacy 64-char tokens and newer prefixed tokens (e.g. amcs_...). Never logged in full. */
    static final String TOKEN_PATTERN = "[A-Za-z0-9_.~-]{20,256}";
    static final String REVOKED_STATUS = "unauthorized: server token unknown or revoked - run /agentsmc link <code>";
    static String revokedMessage(String url) {
        return "AgentsMC bridge rejected this server's token (401 token_revoked). Get a new link code from the Servers dashboard at "
                + url + " and run /agentsmc link <code>.";
    }

    static boolean tokenOk(String token) {
        return token != null && token.matches(TOKEN_PATTERN);
    }

    /** Safe form for logs and status: prefix up to 5 chars + length. */
    static String maskToken(String token) {
        if (token == null || token.isEmpty()) return "(none)";
        int us = token.indexOf('_');
        String prefix = us > 0 && us <= 6 ? token.substring(0, us + 1) : "";
        return prefix + "****(" + token.length() + " chars)";
    }

    static boolean unauthorized(int status, String body) {
        return status == 401 || (body != null && body.contains("token_revoked"));
    }

    String serverId() { return serverId == null ? "" : serverId; }

    /**
     * yaml plus AGENTSMC_BRIDGE_ENABLED / AGENTSMC_BRIDGE_URL / AGENTSMC_SERVER_TOKEN.
     * A 64-char env token turns the bridge on after a world reset wiped plugins/AgentsMC.
     */
    static Settings resolveSettings(boolean yamlEnabled, String yamlUrl, String yamlToken,
            String yamlWorld, double yamlX, double yamlY, double yamlZ, int yamlPoll,
            String envEnabled, String envUrl, String envToken) {
        Settings s = new Settings();
        s.enabled = yamlEnabled;
        s.url = yamlUrl == null ? "" : yamlUrl.trim();
        s.token = yamlToken == null ? "" : yamlToken.trim();
        s.world = yamlWorld == null || yamlWorld.isBlank() ? "world" : yamlWorld.trim();
        s.x = yamlX;
        s.y = yamlY;
        s.z = yamlZ;
        s.pollSeconds = yamlPoll < 1 ? 1 : yamlPoll;
        if (envUrl != null && !envUrl.isBlank()) s.url = envUrl.trim();
        if (envToken != null && !envToken.isBlank()) s.token = envToken.trim();
        if ("true".equalsIgnoreCase(envEnabled)) s.enabled = true;
        if (envToken != null && tokenOk(envToken.trim())) s.enabled = true;
        if (s.url.isBlank()) s.url = DEFAULT_URL;
        return s;
    }

    static final class Settings {
        boolean enabled;
        String url = "";
        String token = "";
        String world = "world";
        double x, y, z;
        int pollSeconds = 1;
    }

    private static final class TelemetryFrame {
        final Set<String> agents;
        final Map<String, String> names;
        final JsonObject poll;
        final Map<String, String> creds;
        TelemetryFrame(Set<String> agents, Map<String, String> names, JsonObject poll, Map<String, String> creds) {
            this.agents = Set.copyOf(agents);
            this.names = Map.copyOf(names);
            this.poll = poll;
            this.creds = Map.copyOf(creds);
        }
    }

    private boolean storeHas(String id) {
        if (store.agents().has(id)) return true;
        for (String key : store.agents().keySet()) if (AgentNames.sameId(key, id)) return true;
        return false;
    }

    String status() { return status + " | serverId=" + (serverId == null || serverId.isBlank() ? "(none)" : serverId) + " | lastHttp=" + lastHttpStatus + " | lastError=" + lastHttpError + " | lastHeartbeatOk=" + lastHeartbeatOk; }
    @Override public void close() {
        AgentEvents.persist();
        AgentNames.persist();
        worker.shutdownNow();
        telemetry.shutdownNow();
        programWorker.shutdownNow();
        try { http.getClass().getMethod("shutdownNow").invoke(http); } catch (Throwable ignored) {}
    }
}
