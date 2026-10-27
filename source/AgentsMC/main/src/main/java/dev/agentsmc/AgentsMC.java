package dev.agentsmc;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.logging.Level;

import org.bukkit.Axis;
import org.bukkit.Bukkit;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.BlockState;
import org.bukkit.block.data.Bisected;
import org.bukkit.block.data.BlockData;
import org.bukkit.block.data.Directional;
import org.bukkit.block.data.Orientable;
import org.bukkit.block.data.Rotatable;
import org.bukkit.block.data.type.Bed;
import org.bukkit.block.data.type.Door;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Entity;
import org.bukkit.entity.EntityType;
import org.bukkit.entity.Item;
import org.bukkit.entity.Player;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.entity.EntityTargetEvent;
import net.citizensnpcs.api.ai.event.CancelReason;
import net.citizensnpcs.api.ai.event.NavigationCancelEvent;
import net.citizensnpcs.api.ai.event.NavigationCompleteEvent;
import net.citizensnpcs.trait.TargetableTrait;
import net.citizensnpcs.util.NMS;
import org.bukkit.block.BlockFace;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.util.Vector;
import net.kyori.adventure.text.Component;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParseException;
import com.google.gson.JsonParser;

import net.citizensnpcs.Citizens;
import net.citizensnpcs.api.CitizensAPI;
import net.citizensnpcs.api.npc.NPC;
import net.citizensnpcs.api.trait.trait.Inventory;
import net.citizensnpcs.api.trait.trait.Spawned;

/** AgentsMC embeds the Citizens NPC engine; each stable agent ID owns one NPC. */
public final class AgentsMC extends Citizens {
    private static final Gson JSON = new Gson();
    private static final String AGENT_ID = "agentsmc-id";
    private static final String AGENT_CREDENTIAL = "agentsmc-credential-sha256";
    private static final String AGENT_SPAWNED = "agentsmc-spawned";
    private static final String AGENT_LAST = "agentsmc-last-location";
    private static final String AGENT_INVENTORY = "agentsmc-inventory";
    private static final String AGENT_HELD = "agentsmc-held-slot";
    private final java.util.Set<java.util.UUID> awaitingRestore=java.util.concurrent.ConcurrentHashMap.newKeySet();
    private static List<String> statusActions() {
        java.util.LinkedHashSet<String> names = new java.util.LinkedHashSet<>();
        names.add("status"); names.add("list"); names.add("create");
        names.add("spawn"); names.add("despawn"); names.add("remove");
        names.addAll(AgentActionCatalog.advertisedNames());
        return List.copyOf(names);
    }
    private boolean ready;
    private AgentBridge bridge;

    static AgentsMC INSTANCE;
    private final Map<java.util.UUID,Long> pendingDeathRespawn=new java.util.concurrent.ConcurrentHashMap<>();
    private final Map<java.util.UUID,long[]> chunkTickets=new java.util.HashMap<>();
    private final Map<java.util.UUID,String> ticketWorld=new java.util.HashMap<>();
    private AgentInternWebhook internWebhook;
    @Override
    public void onEnable() {
        INSTANCE=this;
        AgentChatLog.reset();
        internWebhook=AgentInternWebhook.fromClasspath(getClass().getClassLoader(), Bukkit.getServer().getName()+" "+(Bukkit.getIp().isBlank()?"":Bukkit.getIp()+":")+Bukkit.getPort(), m->getLogger().warning(m));
        if(internWebhook.enabled())getLogger().info("Intern chat-mention webhook enabled");
        AgentInternWebhook hookRef=internWebhook;
        AgentFeedback.onSubmit=hookRef.enabled()?e->hookRef.onFeedback(e.id,e.agentName,e.text,e.timestamp):null;
        if (Bukkit.getPluginManager().getPlugin("Citizens") != null) {
            getLogger().severe("AgentsMC includes Citizens. Remove the separate Citizens plugin before starting AgentsMC.");
            Bukkit.getPluginManager().disablePlugin(this);
            return;
        }
        super.onEnable();
        if (!isEnabled())
            return;
        Objects.requireNonNull(getCommand("agentsmc"), "plugin.yml must register agentsmc").setExecutor(this);
        Bukkit.getScheduler().runTask(this, () -> {
            Objects.requireNonNull(CitizensAPI.getNPCRegistry(), "Citizens registry did not initialize");
            ready = true;
            AgentFeedback.attach(new java.io.File(getDataFolder(), "feedback.json").toPath());
            AgentOp.attach(new java.io.File(getDataFolder(), "ops.yml"));
            AgentRespawn.loadHumans();
            restoreSpawnedAgents();
            for(NPC body:CitizensAPI.getNPCRegistry())if(body.data().has(AGENT_ID)){configureBody(body);AgentRestart.idle(body);}
            Bukkit.getScheduler().runTaskTimer(this,()->{for(NPC body:CitizensAPI.getNPCRegistry())if(body.data().has(AGENT_ID)){reconcile(body);keepChunk(body);if(bodyUp(body)&&body.getEntity() instanceof Player sampled){try{AgentNavigation.swimTick(body,sampled);}catch(RuntimeException ignored){}try{AgentNavigation.recoverFromAir(body,sampled);}catch(RuntimeException ignored){}AgentEvents.sample(body,sampled);}}},10,10);
            Bukkit.getScheduler().runTaskTimer(this,()->{boolean changed=false;for(NPC body:CitizensAPI.getNPCRegistry())if(body.data().has(AGENT_ID)&&!awaitingRestore.contains(body.getUniqueId())){changed|=saveInventory(body);changed|=AgentHunger.save(body);}if(changed)storeNPCsNow();},10,10);
            getLogger().info("AgentsMC body watchdog active (logger check)");
            Bukkit.getPluginManager().registerEvents(new AgentFurnaceLog(), this);
            Bukkit.getPluginManager().registerEvents(new AgentRespawn.Lock(), this);
            Bukkit.getPluginManager().registerEvents(new org.bukkit.event.Listener() {
                @org.bukkit.event.EventHandler(priority=org.bukkit.event.EventPriority.MONITOR)
                public void despawn(net.citizensnpcs.api.event.NPCDespawnEvent ev){NPC n=ev.getNPC();if(!n.data().has(AGENT_ID))return;
                    if(!ev.isCancelled()&&ev.getReason()!=net.citizensnpcs.api.event.DespawnReason.DEATH&&!awaitingRestore.contains(n.getUniqueId())&&(saveInventory(n)|AgentHunger.save(n)))storeNPCsNow();
                    getLogger().info("Despawn agent "+n.data().get(AGENT_ID)+" npc="+n.getId()+" reason="+ev.getReason()+(ev.isCancelled()?" (cancelled)":""));}
                @org.bukkit.event.EventHandler(priority=org.bukkit.event.EventPriority.MONITOR)
                public void death(net.citizensnpcs.api.event.NPCDeathEvent ev){NPC n=ev.getNPC();if(!n.data().has(AGENT_ID))return;
                    // Keep the last persisted 41-slot inventory (armor/offhand included). Clear vanilla
                    // drops so restore does not duplicate items that were already saved.
                    awaitingRestore.remove(n.getUniqueId());
                    if(ev.getEvent().getEntity() instanceof Player dead){
                        persistInventory(n,dead,true);
                        try{ev.getEvent().getDrops().clear();}catch(RuntimeException ignored){}
                    }
                    org.bukkit.event.entity.EntityDamageEvent last=ev.getEvent().getEntity().getLastDamageCause();
                    AgentHunger.onDeath(n, last);
                    Location home=null;Location vanilla=null;
                    if(ev.getEvent().getEntity() instanceof Player p){try{vanilla=p.getRespawnLocation();}catch(Throwable ignored){}}
                    Location at=ev.getEvent().getEntity().getLocation();
                    Location worldSpawn=at.getWorld()==null?null:at.getWorld().getSpawnLocation();
                    boolean camp=AgentRespawn.repeatCamp(n.getUniqueId(),at,System.currentTimeMillis());
                    Location claimed=AgentRespawn.location(n);boolean intact=AgentRespawn.intact(claimed);
                    home=intact?claimed:camp?worldSpawn:AgentRespawn.choose(claimed,false,vanilla,worldSpawn,at);
                    if(home==null||home.getWorld()==null)home=worldSpawn!=null?worldSpawn:at;
                    n.data().setPersistent(AGENT_LAST,locString(home));n.data().setPersistent(AGENT_SPAWNED,true);
                    pendingDeathRespawn.put(n.getUniqueId(),System.currentTimeMillis()+3000);
                    getLogger().info("Agent "+n.data().get(AGENT_ID)+" died at "+locString(at)+" cause="+(last==null?"unknown":last.getCause()+" dmg="+last.getFinalDamage())+"; respawning in 3s at "+locString(home));
                    AgentEvents.emit(AgentEvents.agentId(n), "agent.died", AgentEvents.data("cause", last==null?"unknown":last.getCause().name()));
                    Bukkit.getScheduler().runTaskLater(AgentsMC.this,()->respawnAfterDeath(n),60);}
                @org.bukkit.event.EventHandler(priority=org.bukkit.event.EventPriority.MONITOR)
                public void spawn(net.citizensnpcs.api.event.NPCSpawnEvent ev){NPC n=ev.getNPC();if(!n.data().has(AGENT_ID)||ev.isCancelled()||!n.data().has(AGENT_INVENTORY))return;
                    awaitingRestore.add(n.getUniqueId());restoreInventory(n);AgentHunger.restore(n);
                    Bukkit.getScheduler().runTaskLater(AgentsMC.this,()->{if(n.data().has(AGENT_INVENTORY)){awaitingRestore.add(n.getUniqueId());restoreInventory(n);AgentHunger.restore(n);}},2);}
                @org.bukkit.event.EventHandler(priority=org.bukkit.event.EventPriority.MONITOR)
                public void remove(net.citizensnpcs.api.event.NPCRemoveEvent ev){NPC n=ev.getNPC();if(n.data().has(AGENT_ID)){getLogger().info("Removed agent NPC "+n.data().get(AGENT_ID)+" npc="+n.getId());releaseChunk(n);AgentHunger.forget(n.getUniqueId());AgentFocus.forget(n.getUniqueId());AgentDoors.forget(n.getUniqueId());AgentSelfDefense.forget(n.getUniqueId());AgentAutonomy.forget(n.getUniqueId());AgentRespawn.forget(n.getUniqueId());}}
            },this);
            Bukkit.getPluginManager().registerEvents(new org.bukkit.event.Listener() {
                @org.bukkit.event.EventHandler(priority=org.bukkit.event.EventPriority.MONITOR)
                public void welcome(org.bukkit.event.player.PlayerJoinEvent event) {
                    Player joined=event.getPlayer();if(joined.hasMetadata("NPC"))return;
                    AgentRespawn.refreshHuman(joined);
                    emitAllAgents("player.joined", AgentEvents.data("name", joined.getName()));
                    String welcomeText=joinMessage();if(welcomeText!=null&&!welcomeText.isBlank())Bukkit.getScheduler().runTaskLater(AgentsMC.this,()->{if(joined.isOnline())joined.sendMessage(org.bukkit.ChatColor.AQUA+welcomeText);},40);
                }
                @org.bukkit.event.EventHandler(priority=org.bukkit.event.EventPriority.MONITOR)
                public void goodbye(org.bukkit.event.player.PlayerQuitEvent event) {
                    Player left=event.getPlayer();if(left.hasMetadata("NPC"))return;
                    emitAllAgents("player.left", AgentEvents.data("name", left.getName()));
                }
                @org.bukkit.event.EventHandler(priority=org.bukkit.event.EventPriority.MONITOR,ignoreCancelled=true)
                public void chat(org.bukkit.event.player.AsyncPlayerChatEvent event) {
                    String name=event.getPlayer().getName(),message=event.getMessage();java.util.UUID sender=event.getPlayer().getUniqueId();
                    Bukkit.getScheduler().runTask(AgentsMC.this,()->{capturePlayerChat(name,message,Bukkit.getPlayer(sender)==null?null:Bukkit.getPlayer(sender).getLocation());Player source=Bukkit.getPlayer(sender);if(source==null)return;
                        for(NPC body:CitizensAPI.getNPCRegistry())if(body.data().has(AGENT_ID)&&bodyUp(body)&&body.getEntity() instanceof Player p)AgentPresentation.chat(p,name,message);
                    });
                }
                @org.bukkit.event.EventHandler(priority=org.bukkit.event.EventPriority.LOWEST,ignoreCancelled=true)
                public void whisper(org.bukkit.event.player.PlayerCommandPreprocessEvent event){
                    if(capturePlayerWhisper(event.getPlayer(),event.getMessage()))event.setCancelled(true);
                }
                @org.bukkit.event.EventHandler(priority=org.bukkit.event.EventPriority.MONITOR,ignoreCancelled=true)
                public void bedOrAnchor(org.bukkit.event.player.PlayerInteractEvent event){
                    if(event.getAction()!=org.bukkit.event.block.Action.RIGHT_CLICK_BLOCK||event.getClickedBlock()==null)return;
                    NPC n=CitizensAPI.getNPCRegistry().getNPC(event.getPlayer());
                    if(n==null||!n.data().has(AGENT_ID))return;
                    try { AgentRespawn.requireUnclaimedOrMine(n,event.getClickedBlock()); }
                    catch (IllegalArgumentException stolen) { return; }
                    if(AgentRespawn.claim(n,event.getPlayer(),event.getClickedBlock()))
                        getLogger().info("Agent "+n.data().get(AGENT_ID)+" set respawn at "+locString(event.getClickedBlock().getLocation()));
                }
                @org.bukkit.event.EventHandler(priority=org.bukkit.event.EventPriority.MONITOR,ignoreCancelled=true)
                public void humanSpawn(com.destroystokyo.paper.event.player.PlayerSetSpawnEvent event){
                    Player who=event.getPlayer();
                    if(!AgentRespawn.isHuman(who))return;
                    if(event.getLocation()==null){
                        AgentRespawn.forgetHuman(who.getUniqueId());
                        return;
                    }
                    String kind=event.getCause()==com.destroystokyo.paper.event.player.PlayerSetSpawnEvent.Cause.RESPAWN_ANCHOR?"anchor":"bed";
                    AgentRespawn.rememberHuman(who.getName(), who.getUniqueId(), event.getLocation(), kind);
                }
                @org.bukkit.event.EventHandler(priority=org.bukkit.event.EventPriority.HIGHEST,ignoreCancelled=false)
                public void spawnDoor(org.bukkit.event.player.PlayerInteractEvent event){
                    if(!AgentDoors.playerUse(event))return;
                    org.bukkit.block.Block door=event.getClickedBlock();
                    Bukkit.getScheduler().runTask(AgentsMC.this,()->AgentDoors.toggle(door));
                }
                @org.bukkit.event.EventHandler(priority=org.bukkit.event.EventPriority.HIGH,ignoreCancelled=true)
                public void villagerDoor(org.bukkit.event.entity.EntityChangeBlockEvent event){
                    AgentDoors.villagerClose(event);
                }
            },this);
            Bukkit.getPluginManager().registerEvents(new org.bukkit.event.Listener() {
                @org.bukkit.event.EventHandler(priority=org.bukkit.event.EventPriority.HIGH,ignoreCancelled=true)
                public void phantomFall(org.bukkit.event.entity.EntityDamageEvent ev) {
                    if(ev.getCause()!=org.bukkit.event.entity.EntityDamageEvent.DamageCause.FALL)return;
                    NPC n=CitizensAPI.getNPCRegistry().getNPC(ev.getEntity());
                    if(n==null||!n.data().has(AGENT_ID))return;
                    if(!AgentNavigation.suppressFall(n))return;
                    ev.setCancelled(true);
                    if(ev.getEntity() instanceof Player p)AgentNavigation.clearFall(p);
                }
                @org.bukkit.event.EventHandler(priority=org.bukkit.event.EventPriority.MONITOR,ignoreCancelled=true)
                public void damaged(org.bukkit.event.entity.EntityDamageEvent ev) {
                    NPC n=CitizensAPI.getNPCRegistry().getNPC(ev.getEntity());
                    if(n==null||!n.data().has(AGENT_ID))return;
                    AgentHunger.recordDamage(n, ev.getCause().name(), ev.getFinalDamage());
                    if(ev.getEntity() instanceof Player pl && AgentHunger.armorExhaustion(ev.getCause()))
                        AgentHunger.exhaust(pl, AgentHunger.DAMAGE);
                    String source=ev.getCause().name();
                    if(ev instanceof org.bukkit.event.entity.EntityDamageByEntityEvent by){
                        AgentSelfDefense.onDamaged(n, by.getDamager());
                        source=by.getDamager() instanceof Player named?named.getName():by.getDamager().getType().name();
                    }
                    double health=ev.getEntity() instanceof Player hurt?hurt.getHealth():0;
                    AgentEvents.emitGated(AgentEvents.agentId(n), "agent.damaged", "damage", AgentEvents.DAMAGE_MS,
                            AgentEvents.data("source", source, "health", health, "amount", ev.getFinalDamage()));
                }
                @org.bukkit.event.EventHandler(priority=org.bukkit.event.EventPriority.HIGH)
                public void targeted(EntityTargetEvent ev) {
                    if (ev.getTarget() == null) return;
                    NPC n = CitizensAPI.getNPCRegistry().getNPC(ev.getTarget());
                    if (n == null || !n.data().has(AGENT_ID)) return;
                    ev.setCancelled(false);
                }
                @org.bukkit.event.EventHandler
                public void navComplete(NavigationCompleteEvent ev) {
                    NPC n = ev.getNPC();
                    if (n == null || !n.data().has(AGENT_ID)) return;
                    CancelReason reason = ev instanceof NavigationCancelEvent cancel ? cancel.getCancelReason() : null;
                    AgentNavigation.onNavigatorDone(n, reason);
                }
            },this);
            Bukkit.getScheduler().runTaskTimer(this,()->{for(NPC body:CitizensAPI.getNPCRegistry())if(body.data().has(AGENT_ID)){
                AgentNavigation.tick(body);AgentBehaviors.tick(this,body);
                if(bodyUp(body)&&body.getEntity() instanceof Player p){AgentNavigation.noteWorld(body,p.getWorld());rememberLocation(body);keepChunk(body);AgentDoors.tick(body,p);AgentPresentation.sample(p);}
            }AgentBehaviors.advance();},2,2);
            Bukkit.getScheduler().runTaskTimer(this,()->{for(NPC body:CitizensAPI.getNPCRegistry())if(body.data().has(AGENT_ID)&&bodyUp(body)&&body.getEntity() instanceof Player p){
                body.setProtected(false);AgentHunger.tick(body,p);try{AgentPortal.tick(body,p);}catch(RuntimeException ignored){}AgentAutonomy.tick(body,p);AgentFocus.tick(body,p);AgentSelfDefense.tick(body);AgentCombat.tick(body);AgentProtect.tick(body);
            }},1,1);
            bridge = AgentBridge.start(this);
            getLogger().info("AgentsMC ready; /agentsmc run <JSON object or array>; one Citizens body per agent ID");
        });
    }

    @Override
    public void onDisable() {
        AgentFeedback.onSubmit=null;
        if (bridge != null) bridge.close();
        if (ready) {
            for (NPC npc : CitizensAPI.getNPCRegistry()) if (npc.data().has(AGENT_ID)) {
                AgentRestart.idle(npc);
                if(!awaitingRestore.contains(npc.getUniqueId())){saveInventory(npc);AgentHunger.save(npc);}
                // Preserve requested lifecycle state. Missing during shutdown is not an explicit despawn.
            }
            storeNPCsNow();
        }
        ready = false;
        super.onDisable();
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!command.getName().equalsIgnoreCase("agentsmc"))
            return super.onCommand(sender, command, label, args);
        if (args.length >= 3 && args[0].equals("self")) {
            try {
                JsonObject input = JsonParser.parseString(String.join(" ", Arrays.copyOfRange(args, 2, args.length))).getAsJsonObject();
                sender.sendMessage("[AgentsMC] " + JSON.toJson(executeAuthenticated(args[1], input)));
            } catch (IllegalArgumentException | IllegalStateException | JsonParseException e) {
                sender.sendMessage("[AgentsMC] " + JSON.toJson(result("ok", false, "error", AgentErrors.message(e, null))));
            }
            return true;
        }
        if (args.length >= 1 && args[0].equals("feedback")) {
            if (sender instanceof Player && !sender.hasPermission("agentsmc.admin")) {
                sender.sendMessage("[AgentsMC] ERROR: agentsmc.admin permission required");
                return true;
            }
            try {
                require(ready, "AgentsMC has not finished loading");
                if (args.length == 1 || args[1].equals("list")) {
                    String status = args.length >= 3 ? args[2] : "open";
                    List<String> lines = AgentFeedback.consoleList(status);
                    if (lines.isEmpty()) sender.sendMessage("[AgentsMC] no feedback");
                    else for (String line : lines) sender.sendMessage("[AgentsMC] " + line);
                } else if (args[1].equals("resolve")) {
                    require(args.length >= 3, "/agentsmc feedback resolve <id> [note]");
                    String note = args.length >= 4 ? String.join(" ", Arrays.copyOfRange(args, 3, args.length)) : "";
                    sender.sendMessage("[AgentsMC] " + JSON.toJson(AgentFeedback.consoleResolve(args[2], note)));
                } else {
                    sender.sendMessage("[AgentsMC] /agentsmc feedback list [open|done|all]");
                    sender.sendMessage("[AgentsMC] /agentsmc feedback resolve <id> [note]");
                }
            } catch (IllegalArgumentException | IllegalStateException e) {
                sender.sendMessage("[AgentsMC] " + JSON.toJson(result("ok", false, "error", AgentErrors.message(e, null))));
            }
            return true;
        }
        if (args.length >= 1 && (args[0].equals("op") || args[0].equals("deop") || args[0].equals("ops"))) {
            if (sender instanceof Player && !sender.isOp()) {
                sender.sendMessage("[AgentsMC] /agentsmc op, deop and ops are console-only (or server operators)");
                return true;
            }
            try {
                require(ready, "AgentsMC has not finished loading");
                if (args[0].equals("ops")) {
                    List<String> ops = AgentOp.ops();
                    sender.sendMessage("[AgentsMC] op agents (" + ops.size() + "): " + (ops.isEmpty() ? "none" : String.join(", ", ops)));
                } else {
                    String who = AgentOp.joinName(args);
                    require(!who.isEmpty(), "/agentsmc " + args[0] + " <agent name or id>");
                    NPC target = AgentOp.agentByName(who);
                    String agentId = target != null ? AgentErrors.text(target.data().get(AGENT_ID)) : who.toLowerCase(java.util.Locale.ROOT);
                    if (args[0].equals("op")) {
                        require(target != null, "Unknown agent: " + who);
                        boolean changed = AgentOp.grant(agentId);
                        sender.sendMessage("[AgentsMC] " + (changed ? "opped agent " : "agent already opped: ") + agentId);
                    } else {
                        boolean changed = AgentOp.revoke(agentId);
                        sender.sendMessage("[AgentsMC] " + (changed ? "deopped agent " : "agent was not opped: ") + agentId);
                    }
                    getLogger().info("[op] " + sender.getName() + " ran /agentsmc " + args[0] + " " + agentId + "; op agents=" + AgentOp.ops());
                }
            } catch (IllegalArgumentException | IllegalStateException e) {
                sender.sendMessage("[AgentsMC] " + JSON.toJson(result("ok", false, "error", AgentErrors.message(e, null))));
            }
            return true;
        }
        if (args.length >= 1 && (args[0].equals("hurt") || args[0].equals("hunger"))) {
            if (sender instanceof Player) {
                sender.sendMessage("[AgentsMC] /agentsmc hurt and hunger are console-only");
                return true;
            }
            try {
                require(ready, "AgentsMC has not finished loading");
                require(args.length >= 3, "/agentsmc " + args[0] + " <agentId> <" + (args[0].equals("hurt") ? "amount" : "food") + ">");
                NPC target = find(args[1]);
                require(target != null, "Unknown agent: " + args[1]);
                require(bodyUp(target) && target.getEntity() instanceof Player, "Agent is not spawned");
                Player body = (Player) target.getEntity();
                if (args[0].equals("hurt")) {
                    double amount = Double.parseDouble(args[2]);
                    require(Double.isFinite(amount) && amount > 0 && amount <= 1000, "amount must be a positive number");
                    body.damage(amount);
                    sender.sendMessage("[AgentsMC] hurt " + args[1] + " " + amount + " health=" + body.getHealth());
                } else {
                    int food = Integer.parseInt(args[2]);
                    require(food >= 0 && food <= 20, "food must be 0 to 20");
                    AgentHunger.applyFood(body, food);
                    AgentHunger.save(target);
                    sender.sendMessage("[AgentsMC] hunger " + args[1] + " food=" + body.getFoodLevel() + " saturation=" + body.getSaturation());
                }
            } catch (NumberFormatException e) {
                sender.sendMessage("[AgentsMC] " + JSON.toJson(result("ok", false, "error", "Expected a number")));
            } catch (IllegalArgumentException | IllegalStateException e) {
                sender.sendMessage("[AgentsMC] " + JSON.toJson(result("ok", false, "error", AgentErrors.message(e, null))));
            }
            return true;
        }
        if (!sender.hasPermission("agentsmc.admin")) {
            sender.sendMessage("[AgentsMC] ERROR: agentsmc.admin permission required");
            return true;
        }
        try {
            require(ready, "AgentsMC has not finished loading");
            if (args.length == 1 && (args[0].equals("status") || args[0].equals("list"))) {
                JsonObject input = new JsonObject();
                input.addProperty("action", args[0]);
                sender.sendMessage("[AgentsMC] " + JSON.toJson(execute(input)));
            } else if (args.length >= 1 && args[0].equals("link")) {
                requireAdmin(sender);
                require(args.length == 2, "/agentsmc link <code>  (get a code from the Servers dashboard at " + bridgeUrl() + ")");
                linkWithCode(sender, args[1]);
            } else if (args.length >= 1 && args[0].equals("unlink")) {
                requireAdmin(sender);
                unlinkBridge();
                sender.sendMessage("[AgentsMC] unlinked. The server token was removed from bridge.yml; agents stop syncing. Revoke it in the Servers dashboard if it may have leaked.");
            } else if (args.length >= 1 && args[0].equals("bridge")) {
                requireAdmin(sender);
                if (args.length == 1 || args[1].equals("status")) {
                    sendBridgeStatus(sender);
                    sender.sendMessage("[AgentsMC] /agentsmc link <code> | unlink | bridge enable <server-token>");
                } else if (args[1].equals("enable")) {
                    require(args.length >= 3, "/agentsmc bridge enable <server-token>");
                    enableBridgeFromConsole(args[2]);
                    sender.sendMessage("[AgentsMC] bridge enabled. Site agents respawn at world spawn on the next poll.");
                    sendBridgeStatus(sender);
                } else {
                    sender.sendMessage("[AgentsMC] /agentsmc bridge status | bridge enable <server-token>");
                }
            } else if (args.length >= 2 && args[0].equals("run")) {
                JsonElement input = JsonParser.parseString(String.join(" ", Arrays.copyOfRange(args, 1, args.length)));
                require(input.isJsonObject() || input.isJsonArray(), "Expected a JSON object or array");
                JsonArray batch = input.isJsonArray() ? input.getAsJsonArray() : new JsonArray();
                if (input.isJsonObject()) batch.add(input);
                require(batch.size() >= 1 && batch.size() <= 16, "Batch must contain 1 to 16 actions");
                for (int i = 0; i < batch.size(); i++) {
                    require(batch.get(i).isJsonObject(), "Action " + i + " must be an object");
                    // Each action reports its own outcome; an error stops the remaining batch.
                    sender.sendMessage("[AgentsMC] " + JSON.toJson(execute(batch.get(i).getAsJsonObject())));
                }
            } else {
                sender.sendMessage("[AgentsMC] /agentsmc status | list | run <JSON object or array>");
                sender.sendMessage("[AgentsMC] /agentsmc link <code> | unlink | bridge status | bridge enable <server-token>");
                sender.sendMessage("[AgentsMC] /agentsmc feedback list [open|done|all] | feedback resolve <id> [note]");
                sender.sendMessage("[AgentsMC] console: /agentsmc hurt <agentId> <amount> | hunger <agentId> <food>");
                sender.sendMessage("[AgentsMC] console: /agentsmc op <agent> | deop <agent> | ops");
                sender.sendMessage("[AgentsMC] actions=" + String.join(",", statusActions()));
            }
        } catch (IllegalArgumentException | IllegalStateException | JsonParseException e) {
            sender.sendMessage("[AgentsMC] " + JSON.toJson(result("ok", false, "error", AgentErrors.message(e, null))));
        } catch (RuntimeException e) {
            getLogger().log(Level.SEVERE, "AgentsMC action failed", e);
            sender.sendMessage("[AgentsMC] " + JSON.toJson(result("ok", false, "error", "Internal action failure; see server log")));
        }
        return true;
    }

    private static void requireAdmin(CommandSender sender) {
        if (sender instanceof Player && !sender.hasPermission("agentsmc.admin"))
            throw new IllegalStateException("agentsmc.admin permission required");
    }

    private java.io.File bridgeFile() { return new java.io.File(getDataFolder(), "bridge.yml"); }

    private org.bukkit.configuration.file.YamlConfiguration loadBridgeConfig() {
        org.bukkit.configuration.file.YamlConfiguration config = new org.bukkit.configuration.file.YamlConfiguration();
        java.io.File file = bridgeFile();
        if (file.exists()) {
            try { config.load(file); }
            catch (Exception e) { getLogger().warning("Could not read existing bridge.yml; rewriting it"); }
        }
        return config;
    }

    private String bridgeUrl() {
        String url = loadBridgeConfig().getString("url", "");
        String env = System.getenv("AGENTSMC_BRIDGE_URL");
        if (env != null && !env.isBlank()) url = env.trim();
        return url == null || url.isBlank() ? AgentBridge.DEFAULT_URL : url.trim();
    }

    /** Join message from bridge.yml {@code join-message}; empty string disables it. Never contains secrets. */
    String joinMessage() {
        org.bukkit.configuration.file.YamlConfiguration config = loadBridgeConfig();
        String fallback = "This server uses AgentsMC. Connect your AI agent at " + bridgeUrl().replaceFirst("^https://", "");
        if (!config.contains("join-message")) return fallback;
        String text = config.getString("join-message", "");
        return text == null ? "" : text.replace("{url}", bridgeUrl());
    }

    private void sendBridgeStatus(CommandSender sender) {
        org.bukkit.configuration.file.YamlConfiguration config = loadBridgeConfig();
        String id = config.getString("server-id", "");
        boolean hasToken = !config.getString("server-token", "").isBlank() || (System.getenv("AGENTSMC_SERVER_TOKEN") != null && !System.getenv("AGENTSMC_SERVER_TOKEN").isBlank());
        sender.sendMessage("[AgentsMC] linked=" + (config.getBoolean("enabled") && hasToken) + " serverId=" + (id == null || id.isBlank() ? "(none)" : id)
                + " url=" + bridgeUrl() + " token=" + (hasToken ? "set (hidden)" : "none"));
        sender.sendMessage("[AgentsMC] bridge=" + (bridge == null ? "not initialized" : bridge.status()));
        if (!hasToken) sender.sendMessage("[AgentsMC] Not linked: get a code from the Servers dashboard at " + bridgeUrl() + " and run /agentsmc link <code>");
        else {
            String pin = bridge != null && !bridge.serverPin.isBlank() ? bridge.serverPin : config.getString("server-pin", "");
            String cu = bridge != null && !bridge.connectUrl.isBlank() ? bridge.connectUrl : config.getString("connect-url", "");
            String line = connectLine(bridgeUrl(), pin, cu);
            sender.sendMessage("[AgentsMC] " + (line.isEmpty()
                    ? "Server PIN: not reported by the bridge; find it in the Servers dashboard at " + bridgeUrl() + " (players connect with " + bridgeUrl() + "/api/mcp/<PIN>)"
                    : "Server PIN: " + (pin == null || pin.isBlank() ? "(see URL)" : pin) + ". Players connect their AI with: " + line));
        }
    }

    /** Server PIN from a bridge response: pin, serverPin, or server.pin. Digits only (spaces/dashes stripped). */
    static String pinOf(JsonObject o) {
        if (o == null) return "";
        String v = str(o, "pin", str(o, "serverPin", ""));
        if (v.isEmpty() && o.has("server") && o.get("server").isJsonObject()) v = str(o.getAsJsonObject("server"), "pin", "");
        v = v.replaceAll("[\\s-]", "");
        return v.matches("[0-9]{4,16}") ? v : "";
    }

    /** MCP connect URL from a bridge response: connectUrl, mcpUrl, or server.connectUrl. HTTPS only. */
    static String connectUrlOf(JsonObject o) {
        if (o == null) return "";
        String v = str(o, "connectUrl", str(o, "mcpUrl", ""));
        if (v.isEmpty() && o.has("server") && o.get("server").isJsonObject()) v = str(o.getAsJsonObject("server"), "connectUrl", "");
        return v.startsWith("https://") && !v.contains(" ") ? v : "";
    }

    /** Players' MCP connector URL: explicit connectUrl, else base + /api/mcp/ + pin, else "". */
    static String connectLine(String base, String pin, String connectUrl) {
        if (connectUrl != null && !connectUrl.isBlank()) return connectUrl.trim();
        if (pin == null || pin.isBlank()) return "";
        String b = base == null ? AgentBridge.DEFAULT_URL : base.trim();
        while (b.endsWith("/")) b = b.substring(0, b.length() - 1);
        return b + "/api/mcp/" + pin.trim();
    }

    /** POST /api/server/claim off the main thread, then store serverId + token and start the poller. */
    void linkWithCode(CommandSender sender, String rawCode) {
        require(ready, "AgentsMC has not finished loading");
        String code = rawCode == null ? "" : rawCode.trim();
        require(code.matches("[A-Za-z0-9_-]{4,64}"), "Link code looks invalid; copy it from the Servers dashboard");
        String url = bridgeUrl();
        JsonObject body = new JsonObject();
        body.addProperty("code", code);
        String name = getServer().getName();
        try { String n = getServer().getMotd(); if (n != null && !n.isBlank()) body.addProperty("motd", org.bukkit.ChatColor.stripColor(n)); } catch (RuntimeException ignored) {}
        body.addProperty("serverName", name == null || name.isBlank() ? "Minecraft server" : name);
        body.addProperty("pluginVersion", getDescription().getVersion());
        body.addProperty("mcVersion", Bukkit.getMinecraftVersion());
        body.addProperty("version", Bukkit.getVersion());
        body.addProperty("onlineMode", getServer().getOnlineMode());
        sender.sendMessage("[AgentsMC] linking with " + url + " ...");
        Bukkit.getScheduler().runTaskAsynchronously(this, () -> {
            String message;
            try {
                java.net.http.HttpClient client = java.net.http.HttpClient.newBuilder().connectTimeout(java.time.Duration.ofSeconds(5))
                        .followRedirects(java.net.http.HttpClient.Redirect.NEVER).build();
                java.net.http.HttpRequest request = java.net.http.HttpRequest.newBuilder(java.net.URI.create(url).resolve("/api/server/claim"))
                        .timeout(java.time.Duration.ofSeconds(15)).header("Accept", "application/json").header("Content-Type", "application/json")
                        .header("User-Agent", "AgentsMC/" + getDescription().getVersion())
                        .POST(java.net.http.HttpRequest.BodyPublishers.ofString(body.toString())).build();
                java.net.http.HttpResponse<String> response = client.send(request, java.net.http.HttpResponse.BodyHandlers.ofString());
                ClaimResult claim = parseClaim(response.statusCode(), response.body());
                Bukkit.getScheduler().runTask(this, () -> {
                    try {
                        enableBridge(claim.token, claim.serverId);
                        saveConnectInfo(claim.pin, claim.connectUrl);
                        sender.sendMessage("[AgentsMC] linked! serverId=" + claim.serverId + ". Agents added to this server in the dashboard will spawn at world spawn.");
                        String line = connectLine(url, claim.pin, claim.connectUrl);
                        sender.sendMessage("[AgentsMC] " + (line.isEmpty()
                                ? "Find your server PIN in the Servers dashboard at " + url + ". Players connect their AI with: " + url + "/api/mcp/<PIN>"
                                : "Players connect their AI with: " + line));
                    } catch (RuntimeException e) { sender.sendMessage("[AgentsMC] link failed: " + e.getMessage()); }
                });
                return;
            } catch (IllegalStateException e) { message = e.getMessage(); }
            catch (Exception e) { message = "could not reach " + url + ": " + e.getClass().getSimpleName(); }
            String text = "[AgentsMC] link failed: " + message;
            Bukkit.getScheduler().runTask(this, () -> sender.sendMessage(text));
        });
    }

    static final class ClaimResult { String serverId = ""; String token = ""; String pin = ""; String connectUrl = ""; }

    /** Parses the claim response. Accepts {serverId, serverToken} (also token / server.id variants). */
    static ClaimResult parseClaim(int status, String body) {
        JsonObject json;
        try { json = JsonParser.parseString(body == null ? "" : body).getAsJsonObject(); }
        catch (RuntimeException e) { json = new JsonObject(); }
        if (status < 200 || status >= 300) {
            String error = json.has("error") && json.get("error").isJsonPrimitive() ? json.get("error").getAsString() : "HTTP " + status;
            if (status == 404 || status == 410) throw new IllegalStateException(error + " - create a new code in the Servers dashboard");
            if (status == 429) throw new IllegalStateException("too many attempts, wait a minute and try again");
            throw new IllegalStateException(error);
        }
        ClaimResult r = new ClaimResult();
        r.token = str(json, "serverToken", str(json, "token", ""));
        r.serverId = str(json, "serverId", "");
        if (r.serverId.isEmpty() && json.has("server") && json.get("server").isJsonObject())
            r.serverId = str(json.getAsJsonObject("server"), "id", "");
        if (!AgentBridge.tokenOk(r.token)) throw new IllegalStateException("bridge returned no usable server token");
        r.pin = pinOf(json);
        r.connectUrl = connectUrlOf(json);
        return r;
    }

    private static String str(JsonObject o, String key, String fallback) {
        return o.has(key) && o.get(key).isJsonPrimitive() ? o.get(key).getAsString().trim() : fallback;
    }

    /** Stores the shareable PIN / connect URL (not secrets) in bridge.yml for /agentsmc bridge status. */
    void saveConnectInfo(String pin, String connectUrl) {
        org.bukkit.configuration.file.YamlConfiguration config = loadBridgeConfig();
        config.set("server-pin", pin == null || pin.isBlank() ? null : pin);
        config.set("connect-url", connectUrl == null || connectUrl.isBlank() ? null : connectUrl);
        try { config.save(bridgeFile()); } catch (java.io.IOException e) { getLogger().warning("Could not save server PIN to bridge.yml: " + e.getMessage()); }
    }

    void unlinkBridge() {
        org.bukkit.configuration.file.YamlConfiguration config = loadBridgeConfig();
        config.set("enabled", false);
        config.set("server-token", "");
        config.set("server-id", null);
        config.set("server-pin", null);
        config.set("connect-url", null);
        try { config.save(bridgeFile()); }
        catch (java.io.IOException e) { throw new IllegalStateException("Could not write bridge.yml: " + e.getMessage()); }
        if (bridge != null) { try { bridge.close(); } catch (Exception ignored) {} bridge = null; }
        getLogger().info("AgentsMC unlinked from the bridge (token removed from bridge.yml)");
    }

    /** Writes bridge.yml and starts the poller. Site agentIds are recreated at world spawn. */
    void enableBridgeFromConsole(String rawToken) { enableBridge(rawToken, null); }

    void enableBridge(String rawToken, String serverId) {
        require(ready, "AgentsMC has not finished loading");
        String token = rawToken == null ? "" : rawToken.trim();
        require(AgentBridge.tokenOk(token), "server-token must be 20-256 letters, digits, '_', '-', '.' or '~'");
        java.io.File file = new java.io.File(getDataFolder(), "bridge.yml");
        org.bukkit.configuration.file.YamlConfiguration config = new org.bukkit.configuration.file.YamlConfiguration();
        if (file.exists()) {
            try { config.load(file); }
            catch (Exception e) { getLogger().warning("Could not read existing bridge.yml; rewriting it"); }
        }
        World world = Bukkit.getWorld("world");
        if (world == null && !Bukkit.getWorlds().isEmpty()) world = Bukkit.getWorlds().get(0);
        require(world != null, "No loaded world to bind the bridge spawn");
        String url = config.getString("url", "");
        if (url == null || url.isBlank()) url = AgentBridge.DEFAULT_URL;
        config.set("enabled", true);
        config.set("url", url);
        config.set("server-token", token);
        if (serverId != null) config.set("server-id", serverId.isBlank() ? null : serverId);
        if (!config.contains("world")) config.set("world", world.getName());
        config.set("poll-seconds", 1);
        try { config.save(file); }
        catch (java.io.IOException e) { throw new IllegalStateException("Could not write bridge.yml: " + e.getMessage()); }
        if (bridge != null) {
            try { bridge.close(); } catch (Exception ignored) {}
        }
        bridge = AgentBridge.start(this);
        getLogger().info("Bridge enabled from console; Site agents will be recreated at world spawn if the registry is empty");
    }

    /** Runs one stored routine step for this body (server thread). Catalog + selector checks identical to remote steps. */
    Map<String, Object> routineStep(NPC npc, JsonObject step) {
        require(Bukkit.isPrimaryThread() && ready, "Routine step outside server thread");
        JsonObject bound = AgentActionProgram.normalize(step, npc.getEntity().getWorld().getName());
        require(!"map".equals(text(bound, "action")), "Routine steps cannot nest map");
        bound.addProperty("agent", (String) npc.data().get(AGENT_ID));
        return execute(bound);
    }

    /** Agent-facing entry point. Identity comes solely from the credential, never from action fields. */
    public Map<String, Object> executeAuthenticated(String credential, JsonObject input) {
        require(Bukkit.isPrimaryThread(), "World actions must run on the server thread");
        require(ready, "AgentsMC has not finished loading");
        require(!input.has("agent"), "Agent requests cannot select a body");
        require(AgentActionCatalog.executable(text(input, "action")), "Action is not available to agents");
        NPC owner = authenticatedOwner(credential);
        JsonObject bound = input.deepCopy();
        bound.addProperty("agent", (String) owner.data().get(AGENT_ID));
        // fb-37: the injected world was captured when the program started. After a
        // portal/tp step later steps must target the body's live world.
        if (bound.has("world") && bodyUp(owner) && owner.getEntity() != null)
            bound.addProperty("world", owner.getEntity().getWorld().getName());
        return AgentErrors.ensureError(execute(bound));
    }

    private NPC authenticatedOwner(String credential) {
        String digest = AgentCredential.digest(credential);
        NPC owner = null;
        for (NPC npc : CitizensAPI.getNPCRegistry()) {
            if (!npc.data().has(AGENT_ID) || !npc.data().has(AGENT_CREDENTIAL)) continue;
            if (AgentCredential.matches(digest, npc.data().get(AGENT_CREDENTIAL))) {
                require(owner == null, "Duplicate stored credential binding; agent control is disabled");
                owner = npc;
            }
        }
        require(owner != null, "Invalid agent credential");
        return owner;
    }

    /** Trusted registration path; remote action payloads never reach provisioning. */
    Map<String, Object> provisionBridgeAgent(String siteId, String name, String credential, Location target) {
        require(Bukkit.isPrimaryThread() && ready, "AgentsMC is not ready on the server thread");
        String id = BridgeProtocol.localId(siteId);
        if (name != null && !name.isBlank() && !AgentNames.valid(name))
            throw new IllegalArgumentException("Invalid registered display name");
        if (AgentNames.valid(name)) AgentNames.remember(id, name);
        String display = AgentNames.resolve(id, name);
        NPC npc = find(id);
        if (npc == null) npc = createAgent(id, display, target, credential);
        else {
            applyDisplayName(npc, display);
            if (bodyUp(npc) && npc.getEntity() instanceof Player player) {
                Location here = player.getLocation();
                Block feet = here.getWorld().getBlockAt(AgentSafeSpawn.blockCoord(here.getX()), AgentSafeSpawn.blockCoord(here.getY()), AgentSafeSpawn.blockCoord(here.getZ()));
                if (!open(feet) || !open(feet.getRelative(BlockFace.UP)) || open(feet.getRelative(BlockFace.DOWN)))
                    require(player.teleport(safeStandingPosition(player.getLocation())), "Could not move existing body to a safe standing position");
            }
        }
        require(npc.data().has(AGENT_CREDENTIAL) && AgentCredential.matches(AgentCredential.digest(credential),
                npc.data().get(AGENT_CREDENTIAL)), "Registered body credential does not match; refusing to rebind it");
        return bridgeSnapshot(credential);
    }

    /** Rebinds a bridge-registered agent whose local credential store was lost. The bridge is authoritative for identity. */
    Map<String, Object> recoverBridgeAgent(String siteId, String credential, Location target) {
        require(Bukkit.isPrimaryThread() && ready, "AgentsMC is not ready on the server thread");
        String id = BridgeProtocol.localId(siteId);
        String display = AgentNames.resolve(id, null);
        NPC npc = find(id);
        if (npc == null) npc = createAgent(id, display, target, credential);
        else {
            npc.data().setPersistent(AGENT_CREDENTIAL, AgentCredential.digest(credential));
            applyDisplayName(npc, display);
        }
        return bridgeSnapshot(credential);
    }

    /** Applies a poll/registration name, then a persisted copy, then Agent-xxxxxxxx. */
    void applyBundledName(String siteId) {
        applyPollName(siteId, null, null);
    }

    /** @return a warning when the body is still a placeholder and the poll supplied no name. */
    String applyPollName(String siteId, String pollName, JsonObject poll) {
        String local = AgentNames.dashless(siteId);
        if (local.length() == 32) try { BridgeProtocol.localId(AgentEvents.hyphenated(local)); } catch (RuntimeException ignored) {}
        if (AgentNames.valid(pollName)) AgentNames.remember(local, pollName);
        NPC npc = find(local);
        if (npc == null) return null;
        applyDisplayName(npc, AgentNames.resolve(local, pollName));
        if (AgentNames.isPlaceholder(npc.getName(), local)
                && !AgentNames.valid(pollName)
                && !AgentNames.valid(AgentNames.persisted(local)))
            return AgentNames.missingNameWarning(local, npc.getName(), poll);
        return null;
    }

    boolean applyDisplayName(NPC npc, String wanted) {
        if (npc == null || !AgentNames.shouldApply(npc.getName(), wanted, AgentErrors.text(npc.data().get(AGENT_ID))))
            return false;
        getLogger().info("Renaming agent " + npc.getName() + " -> " + wanted);
        npc.setName(wanted);
        storeNPCsNow();
        return true;
    }

    Map<String, Object> bridgeSnapshot(String credential) {
        require(Bukkit.isPrimaryThread() && ready, "AgentsMC is not ready on the server thread");
        NPC npc = authenticatedOwner(credential);
        if(!bodyUp(npc))reconcile(npc);
        Map<String, Object> state;
        if (bodyUp(npc) && npc.getEntity() instanceof Player) {
            JsonObject input = new JsonObject();
            input.addProperty("action", "observe");
            input.addProperty("radius", 2);
            input.addProperty("includeBlocks", true);
            state = executeAuthenticated(credential, input);
            state.put("inventory", inventorySnapshot(npc, (Player) npc.getEntity()));
        } else state = result("agent", summary(npc), "error", "Body is not spawned" +
                (bodyRecoveryErrors.containsKey(npc.getUniqueId()) ? ": " + bodyRecoveryErrors.get(npc.getUniqueId()) : ""));
        state.put("bodyLifecycle", result("desiredSpawned", Boolean.TRUE.equals(npc.data().get(AGENT_SPAWNED, false)),
                "status", bodyStatus(npc).name(), "lastRecoveryError", bodyRecoveryErrors.get(npc.getUniqueId())));
        return result("bodyUuid", npc.getUniqueId().toString(), "online", bodyUp(npc), "state", state,
                "focus", bodyUp(npc) ? AgentFocus.view(npc) : null,
                "capabilities", AgentActionCatalog.snapshot(),
                "chatLog", AgentChatLog.heartbeat(AgentIds.publicId(npc.data().get(AGENT_ID))));
    }

    Map<String, Object> bridgeViewerSnapshot(String credential) {
        require(Bukkit.isPrimaryThread() && ready, "AgentsMC is not ready on the server thread");
        NPC npc = authenticatedOwner(credential);
        if (!bodyUp(npc)) return null;
        require(npc.getEntity() instanceof Player, "Spawned agent body is not a player entity");
        Player player=(Player)npc.getEntity();Map<String,Object> frame=AgentViewerSnapshot.capture(player);
        frame.put("viewerVersion",3);frame.put("ui",AgentPresentation.ui(player));frame.put("motion",AgentPresentation.motion(player));frame.put("entities",viewerEntities(player));
        frame.put("focus", AgentFocus.view(npc));return frame;
    }

    boolean bodyOnline(String credential) {
        require(Bukkit.isPrimaryThread() && ready, "AgentsMC is not ready on the server thread");
        return bodyUp(authenticatedOwner(credential));
    }

    /** Reconcile a stale body once, then report whether it can run a queued action. */
    boolean prepareBody(String credential) {
        require(Bukkit.isPrimaryThread() && ready, "AgentsMC is not ready on the server thread");
        NPC npc = authenticatedOwner(credential);
        if (bodyUp(npc)) return true;
        reconcile(npc);
        return bodyUp(npc);
    }

    Map<String, Object> executeBridgeAction(String credential, String action, JsonObject payload) {
        require(Bukkit.isPrimaryThread() && ready, "AgentsMC is not ready on the server thread");
        NPC npc = authenticatedOwner(credential);
        require(bodyUp(npc) && npc.getEntity() instanceof Player, "Body is not spawned");
        Player player = (Player) npc.getEntity();
        if (action.equals("execute")) {
            return AgentActionProgram.execute(this, credential, payload, player.getWorld().getName());
        }
        JsonObject input = BridgeProtocol.action(action, payload, player.getWorld().getName());
        return AgentErrors.ensureError(executeAuthenticated(credential, input));
    }

        Map<String,Object> executeBridgeProgramStep(String credential, JsonObject input) {
        require(Bukkit.isPrimaryThread() && ready, "AgentsMC is not ready on the server thread");
        return AgentErrors.ensureError(executeAuthenticated(credential,input));
    }

    String bridgeWorld(String credential) {
        require(Bukkit.isPrimaryThread() && ready, "AgentsMC is not ready on the server thread");
        NPC npc=authenticatedOwner(credential);require(bodyUp(npc)&&npc.getEntity() instanceof Player,"Body is not spawned");
        return ((Player)npc.getEntity()).getWorld().getName();
    }

    JsonObject uiPublish(String siteId) {
        require(Bukkit.isPrimaryThread() && ready, "AgentsMC is not ready on the server thread");
        NPC npc = find(BridgeProtocol.localId(siteId));
        if (npc == null) return null;
        return AgentUi.takePublish(AgentEvents.hyphenated(siteId), npc);
    }

    Map<String,Object> bridgeNavigationSnapshot(String credential) {
        require(Bukkit.isPrimaryThread() && ready, "AgentsMC is not ready on the server thread");
        NPC npc=authenticatedOwner(credential);require(bodyUp(npc),"Body is not spawned");
        return AgentNavigation.snapshot(npc);
    }

    void stopBridgeNavigation(String credential) {
        require(Bukkit.isPrimaryThread() && ready, "AgentsMC is not ready on the server thread");
        AgentNavigation.stop(authenticatedOwner(credential));
    }

    String bridgeInterruptReason(String credential, String abortRule) {
        require(Bukkit.isPrimaryThread() && ready, "AgentsMC is not ready on the server thread");
        NPC npc = authenticatedOwner(credential);
        if (!bodyUp(npc)) return null;
        boolean threat = AgentSelfDefense.active(npc) || AgentCombat.busy(npc);
        List<Map<String, Object>> unread = AgentSocial.peekUnread(npc.getUniqueId());
        boolean unreadWhisper = unread.stream().anyMatch(m -> "whisper".equals(m.get("kind")));
        boolean unreadChat = !unread.isEmpty();
        Map<String, Object> dmg = AgentHunger.lastDamageView(npc);
        boolean recentDamage = false;
        if (dmg != null && dmg.get("time") instanceof Number t) {
            recentDamage = System.currentTimeMillis() - t.longValue() <= 1_500;
        }
        return AgentActionProgram.checkInterrupt(abortRule, threat, unreadWhisper, unreadChat, recentDamage);
    }

    private NPC createAgent(String id, String name, Location target, String credential) {
        target = safeStandingPosition(target);
        NPC created = CitizensAPI.getNPCRegistry().createNPC(EntityType.PLAYER, name);
        created.data().setPersistent(AGENT_ID, id);
        created.data().setPersistent(AGENT_CREDENTIAL, AgentCredential.digest(credential));
        created.setProtected(false);
        created.data().setPersistent(AgentHunger.KEY, AgentHunger.encodeReset());
        created.getOrAddTrait(Inventory.class);
        if (!created.spawn(target)) {
            created.destroy();
            throw new IllegalStateException("Citizens could not spawn the body at the requested position");
        }
        ((Player) created.getEntity()).setGameMode(GameMode.SURVIVAL);
        configureBody(created);
        markSpawned(created, true);
        storeNPCsNow();
        return created;
    }

    /** Canonical privileged action executor. Never expose this as a remote tool. */
    private Map<String, Object> execute(JsonObject input) {
        require(Bukkit.isPrimaryThread(), "World actions must run on the server thread");
        require(ready, "AgentsMC has not finished loading");
        String action = text(input, "action");
        if (action.equals("status")) {
            keys(input, "action");
            List<Object> worlds = new ArrayList<>();
            for (World world : Bukkit.getWorlds())
                worlds.add(result("name", world.getName(), "spawn", position(world.getSpawnLocation())));
            return result("ok", true, "plugin", "AgentsMC", "version", AgentVersion.current(this),
                    "minecraft", Bukkit.getBukkitVersion(), "actions", statusActions(), "worlds", worlds,
                    "serverSessionId", AgentChatLog.sessionId(),
                    "bridge", bridge == null ? "not initialized" : bridge.status(),
                    "note", "Server-side NPCs; an external connected player is still required to keep this host awake");
        }
        if (action.equals("list")) {
            keys(input, "action");
            List<Object> agents = new ArrayList<>();
            for (NPC npc : CitizensAPI.getNPCRegistry()) {
                if (npc.data().has(AGENT_ID)) { if(!bodyUp(npc))reconcile(npc); agents.add(summary(npc)); }
            }
            return result("ok", true, "agents", agents);
        }
        String id = text(input, "agent");
        require(id.matches("[a-z0-9][a-z0-9_-]{0,31}"), "agent must be 1 to 32 lowercase letters, digits, _ or -");
        NPC npc = find(id);
        if (action.equals("create")) {
            keys(input, "action", "agent", "name", "world", "x", "y", "z");
            require(npc == null, "Agent already exists: " + id);
            String name = text(input, "name");
            require(name.matches("[A-Za-z0-9_]{1,16}"), "name must be 1 to 16 Minecraft username characters");
            for (NPC existing : CitizensAPI.getNPCRegistry())
                require(!existing.getName().equalsIgnoreCase(name), "An NPC with this player name already exists");
            Location target = destination(input);
            String credential = AgentCredential.mint();
            NPC created = createAgent(id, name, target, credential);
            return result("ok", true, "action", action, "agent", summary(created), "accessToken", credential,
                    "note", "Save this credential privately; it controls only this body and is shown only at creation");
        }
        require(npc != null, "Unknown agent: " + id);
        if (action.equals("op")) return AgentOp.execute(this, npc, input);
        if (AgentFeedback.handles(action)) {
            Map<String, Object> filed = AgentFeedback.execute(npc, input);
            filed.put("ok", true);
            filed.put("action", action);
            filed.put("agentId", AgentIds.publicId(id));
            return AgentErrors.ensureError(filed);
        }
        if (action.equals("chat_log")) {
            keys(input, List.of("after", "before", "limit", "scope", "serverSessionId"), "action", "agent");
            Map<String, Object> page = AgentChatLog.execute(input, AgentIds.publicId(id));
            page.put("ok", true);
            page.put("action", action);
            page.put("agentId", AgentIds.publicId(id));
            return AgentErrors.ensureError(page);
        }
        if (action.equals("remove")) {
            keys(input, "action", "agent");
            getLogger().info("Remove requested by action for agent "+id);releaseChunk(npc);
            npc.destroy();
            storeNPCsNow();
            return result("ok", true, "action", action, "agentId", AgentIds.publicId(id));
        }
        if (action.equals("spawn")) {
            keys(input, "action", "agent", "world", "x", "y", "z");
            AgentBodyState.Status st=bodyStatus(npc);
            if(AgentBodyState.present(st))throw new IllegalArgumentException("Agent is already spawned");
            if(st!=AgentBodyState.Status.ABSENT||npc.getEntity()!=null)npc.despawn(net.citizensnpcs.api.event.DespawnReason.PENDING_RESPAWN);
            Location target = safeStandingPosition(destination(input));
            require(npc.spawn(target), "Citizens could not spawn the agent");
            ((Player) npc.getEntity()).setGameMode(GameMode.SURVIVAL);
            configureBody(npc);
            markSpawned(npc, true);
            storeNPCsNow();
            return result("ok", true, "action", action, "agent", summary(npc));
        }
        require(bodyUp(npc) && npc.getEntity() instanceof Player, "Agent is not spawned");
        Player player = (Player) npc.getEntity();
        if (AgentSleep.shouldWakeOnAction(action)) { if (AgentSleep.wakeBesideBed(npc, player) == null) AgentSleep.stand(npc, player); }
        Map<String, Object> output;
        try {
        switch (action) {
            case "observe": {
                keys(input, List.of("radius", "includeBlocks"), "action", "agent");
                int radius = input.has("radius") ? lenientInt(input, "radius") : 2;
                require(radius >= 1 && radius <= 8, "radius must be 1 to 8 (got " + radius + ")");
                boolean includeBlocks = input.has("includeBlocks") && flag(input, "includeBlocks");
                Location p = player.getLocation();
                Map<String, Integer> counts = new LinkedHashMap<>();
                List<Object> blocks = new ArrayList<>();
                for (int x = -radius; x <= radius; x++)
                    for (int y = -radius; y <= radius; y++)
                        for (int z = -radius; z <= radius; z++) {
                            int bx = p.getBlockX() + x, by = p.getBlockY() + y, bz = p.getBlockZ() + z;
                            if (by < p.getWorld().getMinHeight() || by >= p.getWorld().getMaxHeight()) continue;
                            if (!p.getWorld().isChunkLoaded(bx >> 4, bz >> 4)) continue;
                            Material material = p.getWorld().getBlockAt(bx, by, bz).getType();
                            if (!material.isAir()) {
                                counts.merge(material.name(), 1, Integer::sum);
                                if (includeBlocks) {
                                    Map<String, Object> row = result("x", p.getBlockX() + x, "y", by, "z", p.getBlockZ() + z, "material", material.name());
                                    if (p.getWorld().getBlockAt(bx, by, bz).getBlockData() instanceof org.bukkit.block.data.type.Snow snow) row.put("layers", snow.getLayers());
                                    blocks.add(row);
                                }
                            }
                        }
                List<Object> nearby = new ArrayList<>();
                for (Entity entity : player.getNearbyEntities(radius, radius, radius)) {
                    if (entity.getLocation().distance(player.getLocation()) > radius + 1e-9) continue;
                    nearby.add(result("uuid", entity.getUniqueId().toString(), "type", entity.getType().name(),
                            "name", entity.getName(), "agent", isAgentEntity(entity), "position", position(entity.getLocation())));
                }
                output = result("navigation",AgentNavigation.snapshot(npc),"fight",AgentCombat.snapshot(npc),"protect",AgentProtect.snapshot(npc),"selfDefense",AgentSelfDefense.snapshot(npc),"ranged",AgentRanged.snapshot(npc,player),"autonomy",AgentAutonomy.snapshot(npc),"program",AgentBridge.programSnapshot(id),"vehicle",AgentBoats.view(player),"agent", summary(npc), "health", player.getHealth(), "food", player.getFoodLevel(),
                        "saturation", player.getSaturation(), "exhaustion", player.getExhaustion(),
                        "lastDamage", AgentHunger.lastDamageView(npc), "deaths", AgentHunger.deaths(npc),
                        "focus", AgentFocus.view(npc),
                        "respawnPoint", AgentRespawn.view(npc, player),
                        "beds", AgentRespawn.nearby(player, npc, 16),
                        "equipment", equipmentSnapshot(npc, player),
                        "time", AgentWorldTime.view(p.getWorld()),
                        "pose", AgentSleep.view(player, npc).get("pose"),
                        "sleeping", AgentSleep.view(player, npc).get("sleeping"),
                        "blockCounts", counts, "nearby", nearby, "players", who(player, true), "chat", AgentSocial.peekUnread(npc.getUniqueId()));
                if (includeBlocks) output.put("blocks", blocks);
                AgentNavigation.noteWorld(npc, p.getWorld());
                output.put("world", p.getWorld().getName());
                output.put("environment", p.getWorld().getEnvironment().name());
                output.put("position", position(p));
                break;
            }
            case "block": {
                keys(input, "action", "agent", "world", "x", "y", "z");
                for (String key : new String[] {"x", "y", "z"}) integer(input, key);
                Location target = destination(input);
                require(target.getWorld().equals(player.getWorld()) && target.distanceSquared(player.getLocation()) <= 32 * 32,
                        "block target must be within 32 blocks in the same world");
                Block block = target.getBlock();
                output = result("position", position(block.getLocation()), "material", block.getType().name(),
                        "data", block.getBlockData().getAsString());
                AgentRespawn.attach(output, block, npc);
                break;
            }
            case "inventory":
                keys(input, "action", "agent");
                output = result("items", inventorySnapshot(npc, player), "equipment", equipmentSnapshot(npc, player));
                break;
            case "goto": {
                keys(input, List.of("dig", "background", "async", "await", "preempt", "queue", "abortIf"), "action", "agent", "world", "x", "y", "z");
                Location target = destination(input);
                require(target.getWorld().equals(player.getWorld()), "goto cannot cross worlds");
                require(target.distanceSquared(player.getLocation()) <= 128 * 128, "goto target exceeds 128 blocks");
                boolean dig = AgentPerception.optionalFlag(input, "dig", false);
                Map<String,Object> cancelledHarvest = AgentBehaviors.cancel(npc);
                AgentProtect.stop(npc);
                AgentSelfDefense.yieldToPlayer(npc);
                AgentFocus.note(npc, "goto", target);
                output = AgentNavigation.start(npc,target,dig);
                if (cancelledHarvest != null) {
                    output.put("cancelledHarvest", cancelledHarvest);
                    output.put("note", "Cancelled background harvest: " + cancelledHarvest.get("note"));
                }
                break;
            }
            case "stop":
                keys(input, "action", "agent");
                AgentNavigation.stop(npc);AgentBehaviors.cancel(npc);AgentCombat.stop(npc);AgentProtect.stop(npc);AgentSelfDefense.stop(npc);
                output = result("state", "stopped");
                break;
            case "look": {
                keys(input, "action", "agent", "world", "x", "y", "z");
                Location target = destination(input);
                require(target.getWorld().equals(player.getWorld()), "look target must be in the same world");
                npc.faceLocation(target);
                output = result("target", position(target));
                break;
            }
            case "equip": {
                keys(input, List.of("to", "hand"), "action", "agent", "slot");
                int slot = integer(input, "slot");
                require(AgentEquipment.inRange(slot), AgentEquipment.rangeError());
                String dest = AgentTargets.first(input, "to", "hand");
                output = AgentEquipment.equip(player, slot, dest.isBlank() ? null : dest);
                break;
            }
            case "pickup": case "collect": {
                keys(input, "action", "agent");
                List<Item> drops=player.getNearbyEntities(16,8,16).stream().filter(Item.class::isInstance).map(Item.class::cast)
                        .filter(item->item.getWorld().equals(player.getWorld())).sorted(java.util.Comparator.comparingDouble(item->player.getLocation().distanceSquared(item.getLocation()))).toList();
                int picked=collectNearby(player,1.5);
                if(picked>0 || drops.isEmpty()) output=result("picked",picked,"items",inventory(player),"remainingNearby",drops.size());
                else { Item nearest=AgentBehaviors.preferDrop(player,drops);output=AgentNavigation.collect(npc,nearest);output.put("state","collecting");output.put("targetItem",nearest.getItemStack().getType().name());output.put("note","Walking to the nearest preferred drop (just-mined types first)"); }
                break;
            }
            case "dig": case "break": {
                keys(input, "action", "agent", "world", "x", "y", "z");
                for (String key : new String[] {"x", "y", "z"}) integer(input, key);
                Location at = destination(input);
                require(at.getWorld().equals(player.getWorld()), "Target must be in the agent's world");
                Entity boat = AgentBoats.boatNear(player, at, 1.5);
                if (boat != null) {
                    output = AgentBoats.pickup(player, npc, boat);
                    break;
                }
                Block block = at.getBlock();
                require(!block.isEmpty() && !block.isLiquid(), "dig requires a solid target");
                require(block.getType().getHardness() >= 0, "Block is unbreakable");
                output = breakTarget(player, npc, block, action);
                break;
            }
            case "place": {
                keys(input, List.of("block", "item", "material", "slot", "text", "facing", "direction", "side"), "action", "agent", "world", "x", "y", "z");
                Block block = placeTarget(player, input);
                if (input.has("text") && AgentSigns.isSign(block.getType().name())) {
                    AgentProtection.requireEdit(player, block);
                    output = AgentSigns.write(block, text(input, "text"), input.has("side") ? text(input, "side") : null);
                    output.put("after", block.getType().name());
                    AgentFocus.note(npc, "place", block);
                    break;
                }
                String wantedKey = input.has("block") ? "block" : input.has("item") ? "item" : input.has("material") ? "material" : null;
                if (input.has("slot")) { int slot = integer(input, "slot"); require(slot >= 0 && slot <= 8, "slot must be a hotbar slot 0..8"); player.getInventory().setHeldItemSlot(slot); }
                if (wantedKey != null) selectBlock(player, AgentPlacement.blockKey(text(input, wantedKey)));
                ItemStack hand = player.getInventory().getItemInMainHand();
                require(hand != null && hand.getAmount() > 0 && !hand.getType().isAir(), "Nothing held in hand (slot " + player.getInventory().getHeldItemSlot() + "); pass block:\"<material>\" or select a slot holding a block");
                String itemKey = hand.getType().getKey().getKey();
                String lookName = player.getFacing().name();
                if (!AgentPlacement.horizontal(lookName.toLowerCase(Locale.ROOT)))
                    lookName = AgentPlacement.facingFromYaw(player.getLocation().getYaw());
                String facingRaw = input.has("facing") ? text(input, "facing") : null;
                if (input.has("direction")) {
                    String alias = text(input, "direction");
                    if (facingRaw != null && !AgentPlacement.sameFacing(facingRaw, alias, lookName))
                        throw new IllegalArgumentException("facing and direction must match");
                    if (facingRaw == null) facingRaw = alias;
                }
                boolean explicitFacing = facingRaw != null;
                String facingKey = explicitFacing ? AgentPlacement.resolveFacing(facingRaw, lookName) : lookName.toLowerCase(Locale.ROOT);
                BlockFace usedFacing = BlockFace.valueOf(facingKey.toUpperCase(Locale.ROOT));
                if (AgentBoats.isBoatItem(itemKey)) {
                    output = AgentBoats.place(player, npc, block, hand, explicitFacing ? facingKey : null);
                    break;
                }
                require(block.isEmpty() || AgentPlacement.replaceable(block.getType().name(), block.isReplaceable()),
                        "place target " + block.getX() + "," + block.getY() + "," + block.getZ() + " is occupied by " + block.getType().name());
                AgentProtection.requireEdit(player, block);
                require(hand.getType().isBlock() || AgentPlacement.isPlaceableItem(itemKey), "Held item " + hand.getType().name() + " is not a placeable block; pass block:\"<material>\"");
                java.util.function.IntPredicate solid = i -> { int[] o = AgentPlacement.SUPPORT_FACES[i];
                    Block n = block.getRelative(o[0], o[1], o[2]); return !n.isEmpty() && !n.isLiquid() && !AgentPlacement.replaceable(n.getType().name(), n.isReplaceable()); };
                int preferred = AgentPlacement.preferredSupport(itemKey, facingKey, explicitFacing);
                int face = AgentPlacement.attachSupport(itemKey, facingKey, explicitFacing, solid);
                if (face < 0 && explicitFacing && AgentPlacement.exactAttach(itemKey, facingKey)) {
                    int[] miss = AgentPlacement.SUPPORT_FACES[Math.max(0, preferred)];
                    Block support = block.getRelative(miss[0], miss[1], miss[2]);
                    throw new IllegalArgumentException(AgentPlacement.missingSupport(
                            block.getX(), block.getY(), block.getZ(), facingKey, itemKey, preferred,
                            support.getType().name(), AgentPlacement.nearestWall(solid)));
                }
                require(face >= 0, "place at " + block.getX() + "," + block.getY() + "," + block.getZ() + " needs an adjacent non-air block to place against (all six neighbours are air/liquid/replaceable)");
                String placedKey = AgentPlacement.placedBlock(itemKey, face);
                Material material = Material.matchMaterial(placedKey);
                require(material != null && material.isBlock(), "Cannot place " + itemKey + " as " + placedKey);
                AgentPlacement.requireHorizontal(placedKey, facingKey);
                String signText = input.has("text") ? text(input, "text") : null;
                if (signText != null) { require(AgentPlacement.isSignItem(itemKey), "text is only valid when placing a sign"); AgentPlacement.signLines(signText); }
                int[] offset = AgentPlacement.SUPPORT_FACES[face];
                Block against = block.getRelative(offset[0], offset[1], offset[2]);
                npc.faceLocation(against.getLocation().add(0.5, 0.5, 0.5));
                boolean originalSneak = player.isSneaking();
                String stanceError = null;
                boolean placed = false;
                BlockState previous = block.getState();
                BlockState extraPrevious = null;
                for (boolean sneak : new boolean[]{originalSneak, !originalSneak}) {
                    player.setSneaking(sneak);
                    Block extra = AgentPlacement.extraAbove(placedKey) ? block.getRelative(BlockFace.UP)
                            : AgentPlacement.extraAhead(placedKey) ? block.getRelative(usedFacing) : null;
                    if (extra != null && !(extra.isEmpty() || AgentPlacement.replaceable(extra.getType().name(), extra.isReplaceable()))) {
                        stanceError = "extra block at " + extra.getX() + "," + extra.getY() + "," + extra.getZ() + " is occupied by " + extra.getType().name();
                        continue;
                    }
                    if (!AgentPlacement.skipBodyOverlap(placedKey) && org.bukkit.util.BoundingBox.of(block).overlaps(player.getBoundingBox())) {
                        int fx = AgentSafeSpawn.blockCoord(player.getLocation().getX());
                        int fy = AgentSafeSpawn.blockCoord(player.getLocation().getY());
                        int fz = AgentSafeSpawn.blockCoord(player.getLocation().getZ());
                        int[] aside = AgentBuildPlan.standFor(fx, fy, fz, block.getX(), block.getY(), block.getZ(),
                                List.of(), (x, y, z) -> AgentSafeSpawn.standable(x, y, z, AgentNavigation.worldProbe(player.getWorld())));
                        stanceError = "body overlaps the target cell while " + (sneak ? "sneaking" : "standing")
                                + (aside == null ? "" : "; stand at " + aside[0] + "," + aside[1] + "," + aside[2] + " then retry");
                        continue;
                    }
                    extraPrevious = extra == null ? null : extra.getState();
                    try {
                        applyPlaced(block, material, face, usedFacing, false, explicitFacing);
                        if (extra != null) applyPlaced(extra, material, face, usedFacing, true, explicitFacing);
                    } catch (RuntimeException failed) {
                        previous.update(true, false);
                        if (extraPrevious != null) extraPrevious.update(true, false);
                        stanceError = failed.getMessage();
                        continue;
                    }
                    try {
                        AgentProtection.requirePlace(player, block, AgentProtection.placeEvent(block, previous, against, hand, player));
                    } catch (IllegalArgumentException rejected) {
                        previous.update(true, false);
                        if (extraPrevious != null) extraPrevious.update(true, false);
                        stanceError = rejected.getMessage() + " while " + (sneak ? "sneaking" : "standing");
                        continue;
                    }
                    if (signText != null) applySignText(block, signText);
                    placed = true;
                    break;
                }
                player.setSneaking(originalSneak);
                require(placed, "Cannot place " + placedKey + " at " + block.getX() + "," + block.getY() + "," + block.getZ() +
                        " from the current stance" + (stanceError == null ? "" : ": " + stanceError));
                AgentFocus.note(npc, "place", block);
                hand.setAmount(hand.getAmount() - 1);
                player.getInventory().setItemInMainHand(hand);
                output = result("after", block.getType().name(), "replaced", previous.getType().name(),
                        "slot", player.getInventory().getHeldItemSlot(), "remaining", hand.getAmount(),
                        "facing", usedFacing.name(), "sneaking", player.isSneaking());
                if (signText != null) output.put("text", signText);
                break;
            }
            case "say": {
                keys(input, List.of("text", "message", "player", "to", "name"), "action", "agent");
                String message = firstString(input, "text", "message");
                require(message != null, AgentTargets.requireError("say requires text or message"));
                require(message.length() <= 256 && message.chars().noneMatch(Character::isISOControl), "text must be at most 256 printable characters");
                String to = firstString(input, "player", "to", "name");
                if (to != null && !to.isBlank()) {
                    output = whisper(npc, to, message);
                    output.put("private", true);
                    break;
                }
                Bukkit.broadcastMessage("<" + npc.getName() + "> " + message);
                AgentChatLog.appendPublic(npc.getName(), message, System.currentTimeMillis());
                internChat(npc.getName(), message, System.currentTimeMillis());
                for(NPC body:CitizensAPI.getNPCRegistry())if(body.data().has(AGENT_ID)&&bodyUp(body)&&body.getEntity() instanceof Player audience)AgentPresentation.chat(audience,npc.getName(),message);
                for(NPC body:CitizensAPI.getNPCRegistry())if(body.data().has(AGENT_ID)&&body!=npc)AgentSocial.deliver(body.getUniqueId(),npc.getName(),"public",message,System.currentTimeMillis());
                emitRelevantChat(npc.getName(),"public",message,player.getLocation());
                output = result("text", message, "private", false);
                break;
            }
            case "despawn":
                keys(input, "action", "agent");
                getLogger().info("Despawn requested by action for agent "+id);
                require(npc.despawn(), "Citizens rejected despawn");
                npc.getOrAddTrait(Spawned.class).setSpawned(false);
                markSpawned(npc, false);
                output = result("state", "despawned");
                break;
            case "whisper": case "reply": {
                keys(input, List.of("player", "to", "name", "text", "message"), "action", "agent");
                String message = firstString(input, "text", "message");
                if (action.equals("reply") && message == null) { output = inbox(npc); break; }
                require(message != null, "whisper requires text or message");
                require(message.length() <= 256 && message.chars().noneMatch(Character::isISOControl), "text must be at most 256 printable characters");
                String to = firstString(input, "player", "to", "name");
                if (to == null && action.equals("reply")) to = AgentSocial.lastWhisperer(npc.getUniqueId());
                require(to != null, action.equals("reply") ? "Nobody has whispered to this agent yet; pass player:\"<name>\"" : "whisper requires player, to, or name (a player or agent name)");
                output = whisper(npc, to, message);
                break;
            }
            case "inspect_interface": {
                keys(input, "action", "agent");
                output = inbox(npc);
                break;
            }
            case "locate": {
                keys(input, List.of("name", "player"), "action", "agent");
                List<Map<String,Object>> all = who(player, false);
                String name = input.has("name") ? text(input, "name") : input.has("player") ? text(input, "player") : null;
                if (name != null) { String n = name; all = all.stream().filter(m -> n.equalsIgnoreCase(String.valueOf(m.get("name"))) || n.equalsIgnoreCase(String.valueOf(m.get("agentId")))).toList();
                    require(!all.isEmpty(), "No online player or agent named " + name); }
                output = result("online", all, "players", all.stream().filter(m -> "player".equals(m.get("kind"))).count(), "agents", all.stream().filter(m -> "agent".equals(m.get("kind"))).count());
                break;
            }
            case "shear": case "feed": case "breed": {
                keys(input, "action", "agent", "uuid");
                output = AgentSurvivalActions.animal(npc, action, player, input);
                break;
            }
            default:
                output = AgentSurvivalActions.execute(this, npc, player, input);
                if (output == null) throw new IllegalArgumentException("Action is not implemented: " + action);
                break;
        }
        } catch (RuntimeException failure) {
            return AgentErrors.failure(action, failure);
        }
        if (output == null) return AgentErrors.failure(action, new IllegalStateException("Action produced no result"));
        if (Boolean.FALSE.equals(output.get("ok"))) return AgentErrors.ensureError(output);
        if (!List.of("observe", "block", "inventory", "look", "inspect_item", "inspect_block", "inspect_entity",
                "scan", "scan_entities", "scan_box", "region", "build_plan", "preflight", "inspect_container", "inspect_interface", "recipe", "trade_offers",
                "feedback", "feedback_list", "feedback_resolve", "chat_log", "heightmap", "walkmap", "time").contains(action))
            storeNPCsNow();
        AgentPresentation.action(player,action,input,output);
        output.remove("viewerRecipe");
        output.put("ok", true);
        output.put("action", action);
        output.put("agentId", AgentIds.publicId(id));
        AgentNavigation.attachAirRecovery(output, npc.getUniqueId());
        return AgentErrors.ensureError(output);
    }

    private static boolean isAgentEntity(Entity e) {
        if (!CitizensAPI.getNPCRegistry().isNPC(e)) return false;
        NPC n = CitizensAPI.getNPCRegistry().getNPC(e); return n != null && n.data().has(AGENT_ID);
    }
    /** Online human players and all agents (spawned or not), nearest first relative to the asking body. */
    static List<Map<String,Object>> who(Player self, boolean compact) {
        List<Map<String,Object>> out = new ArrayList<>();
        for (Player online : Bukkit.getOnlinePlayers()) {
            if (CitizensAPI.getNPCRegistry().isNPC(online)) continue;
            out.add(whoEntry(self, online.getName(), "player", null, true, online.getLocation(), compact));
        }
        for (NPC n : CitizensAPI.getNPCRegistry()) {
            if (!n.data().has(AGENT_ID)) continue;
            if (self != null && n.getEntity() == self) continue;
            boolean up = bodyUp(n);
            Map<String,Object> e = whoEntry(self, n.getName(), "agent", AgentIds.publicId(n.data().get(AGENT_ID)), up, up ? n.getEntity().getLocation() : n.getStoredLocation(), compact);
            if (!compact) e.put("npcId", n.getId());
            out.add(e);
        }
        out.sort(java.util.Comparator.comparingDouble(m -> m.get("d") instanceof Double d ? d : Double.MAX_VALUE));
        return out;
    }
    private static Map<String,Object> whoEntry(Player self, String name, String kind, String agentId, boolean spawned, Location at, boolean compact) {
        Map<String,Object> m = new LinkedHashMap<>();
        m.put("name", name); m.put("kind", kind); if (agentId != null && !compact) m.put("agentId", agentId); m.put("spawned", spawned);
        if (at != null && at.getWorld() != null) {
            m.put("world", at.getWorld().getName());
            m.put("x", Math.round(at.getX() * 10) / 10.0); m.put("y", Math.round(at.getY() * 10) / 10.0); m.put("z", Math.round(at.getZ() * 10) / 10.0);
            if (self != null && self.getWorld().equals(at.getWorld())) m.put("d", Math.round(self.getLocation().distance(at) * 10) / 10.0);
        }
        return m;
    }
    /** Nearby entities for the viewer frame: players, agents and mobs within 24 blocks. */
    static List<Object> viewerEntities(Player player) {
        List<Object> out = new ArrayList<>();
        for (Entity e : player.getNearbyEntities(24, 16, 24)) {
            if (e instanceof Item) continue;
            boolean agent = isAgentEntity(e), human = e instanceof Player && !CitizensAPI.getNPCRegistry().isNPC(e);
            out.add(result("uuid", e.getUniqueId().toString(), "name", e.getName(), "type", agent ? "AGENT" : human ? "PLAYER" : e.getType().name(),
                    "entityType", e.getType().name(), "agent", agent, "player", human, "x", Math.round(e.getX() * 100) / 100.0, "y", Math.round(e.getY() * 100) / 100.0, "z", Math.round(e.getZ() * 100) / 100.0,
                    "yaw", (double) e.getLocation().getYaw(), "d", Math.round(e.getLocation().distance(player.getLocation()) * 10) / 10.0));
            if (out.size() >= 48) break;
        }
        return out;
    }
    private static Map<String,Object> inbox(NPC npc) {
        List<Map<String,Object>> messages = AgentSocial.all(npc.getUniqueId());
        return result("messages", messages, "count", messages.size(), "unread", messages.stream().filter(m -> Boolean.TRUE.equals(m.get("unread"))).count(),
                "lastWhisperFrom", AgentSocial.lastWhisperer(npc.getUniqueId()), "note", "Last 30 public chat/agent say/whisper messages; reading marks all read");
    }
    /** Whisper to an online human player or to another agent (delivered into its inbox; shown to it if spawned). */
    private Map<String,Object> whisper(NPC from, String to, String message) {
        require(to != null && !to.isBlank(), AgentTargets.requireError("whisper requires a recipient name"));
        String want = AgentTargets.strip(to);
        require(!want.isBlank(), AgentTargets.requireError("whisper requires a recipient name"));
        long now = System.currentTimeMillis();
        NPC agent = AgentTargets.findAgent(from, want);
        if (agent != null) {
            String id = AgentIds.publicId(agent.data().get(AGENT_ID));
            AgentSocial.deliver(agent.getUniqueId(), from.getName(), "whisper", message, now);
            AgentChatLog.appendPrivate(from.getName(), message, agent.getName(), AgentIds.publicId(agent.data().get(AGENT_ID)), now);
            if (bodyUp(agent) && agent.getEntity() instanceof Player p) AgentPresentation.chat(p, from.getName() + " (whisper)", message);
            AgentEvents.emit(AgentEvents.agentId(agent), "chat.received",
                    AgentEvents.data("text", message, "from", from.getName(), "kind", "whisper"));
            return result("recipient", agent.getName(), "recipientKind", "agent", "sent", true, "recipientSpawned", bodyUp(agent), "recipientId", id);
        }
        Player target = AgentTargets.findHuman(want);
        require(target != null, AgentTargets.missing(to) + " (use locate to list who is online)");
        AgentChatLog.appendPrivate(from.getName(), message, target.getName(), null, now);
        target.sendMessage("[" + from.getName() + " -> you] " + message);
        return result("recipient", target.getName(), "recipientKind", "player", "sent", true);
    }
    private void emitAllAgents(String type, Map<String, Object> data) {
        for (NPC body : CitizensAPI.getNPCRegistry()) if (body.data().has(AGENT_ID))
            AgentEvents.emit(AgentEvents.agentId(body), type, data);
    }
    private void emitRelevantChat(String from, String kind, String text, Location origin) {
        for (NPC body : CitizensAPI.getNPCRegistry()) if (body.data().has(AGENT_ID)) {
            double dist = Double.POSITIVE_INFINITY;
            if (origin != null && origin.getWorld() != null && bodyUp(body) && body.getEntity() != null
                    && origin.getWorld().equals(body.getEntity().getWorld()))
                dist = body.getEntity().getLocation().distance(origin);
            if (AgentEvents.chatRelevant(kind, text, body.getName(), dist))
                AgentEvents.emit(AgentEvents.agentId(body), "chat.received",
                        AgentEvents.data("text", text, "from", from, "kind", kind));
        }
    }
    private void internChat(String from, String text, long now) {
        AgentInternWebhook hook = internWebhook;
        if (hook != null) hook.onPublicChat(from, text, now);
    }
    private void capturePlayerChat(String name, String message, Location from) {
        long now = System.currentTimeMillis();
        AgentChatLog.appendPublic(name, message, now);
        internChat(name, message, now);
        for (NPC body : CitizensAPI.getNPCRegistry()) if (body.data().has(AGENT_ID)) AgentSocial.deliver(body.getUniqueId(), name, "public", message, now);
        emitRelevantChat(name, "public", message, from);
    }
    /** /msg, /tell, /w, /whisper addressed to an agent body: deliver into its inbox (vanilla cannot target NPC players). */
    private boolean capturePlayerWhisper(Player sender, String raw) {
        String[] parts = raw.trim().split("\\s+", 3);
        if (parts.length < 3) return false;
        String cmd = parts[0].toLowerCase(java.util.Locale.ROOT); if (cmd.contains(":")) cmd = "/" + cmd.substring(cmd.indexOf(':') + 1);
        if (!List.of("/msg", "/tell", "/w", "/whisper").contains(cmd)) return false;
        for (NPC n : CitizensAPI.getNPCRegistry()) if (n.data().has(AGENT_ID) && AgentTargets.namesEqual(n.getName(), parts[1])) {
            String text = parts[2].length() > 256 ? parts[2].substring(0, 256) : parts[2];
            AgentSocial.deliver(n.getUniqueId(), sender.getName(), "whisper", text, System.currentTimeMillis());
            AgentChatLog.appendPrivate(sender.getName(), text, n.getName(), AgentIds.publicId(n.data().get(AGENT_ID)), System.currentTimeMillis());
            AgentEvents.emit(AgentEvents.agentId(n), "chat.received",
                    AgentEvents.data("text", text, "from", sender.getName(), "kind", "whisper"));
            sender.sendMessage("[you -> " + n.getName() + "] " + text);
            return true;
        }
        return false;
    }

    /** Exact inventory (all 41 slots incl. armour/offhand/held) persisted in NPC data; true when it changed. */
    private boolean saveInventory(NPC npc) {
        if (awaitingRestore.contains(npc.getUniqueId())) return false;
        if (!(npc.getEntity() instanceof Player p) || p.isDead()) return false;
        return persistInventory(npc, p, false);
    }
    /** Persist storage + armor + offhand. {@code evenIfEmpty} is for an explicit keep-on-death save. */
    private boolean persistInventory(NPC npc, Player p, boolean evenIfEmpty) {
        try {
            ItemStack[] contents = p.getInventory().getContents().clone();
            for (int i = 0; i < contents.length; i++) if (contents[i] == null) contents[i] = ItemStack.empty();
            int now = AgentInventoryCodec.count(contents);
            Object previous = npc.data().get(AGENT_INVENTORY, "");
            int prev = 0;
            if (previous instanceof String s && !s.isBlank()) {
                try { prev = AgentInventoryCodec.count(ItemStack.deserializeItemsFromBytes(AgentInventoryCodec.decode(s))); }
                catch (RuntimeException ignored) {}
            }
            if (now == 0 && prev > 0 && !evenIfEmpty) {
                getLogger().warning("Refusing to persist an empty inventory over " + prev + " items for agent " + npc.data().get(AGENT_ID));
                return false;
            }
            if (now == 0 && prev > 0) return false; // death already emptied the body; keep the last good snapshot
            String encoded = AgentInventoryCodec.encode(ItemStack.serializeItemsAsBytes(contents));
            int held = p.getInventory().getHeldItemSlot();
            boolean changed = !encoded.equals(npc.data().get(AGENT_INVENTORY, "")) || !Integer.valueOf(held).equals(npc.data().get(AGENT_HELD, -1));
            if (changed) { npc.data().setPersistent(AGENT_INVENTORY, encoded); npc.data().setPersistent(AGENT_HELD, held); }
            return changed;
        } catch (RuntimeException e) { getLogger().warning("Could not save inventory for agent " + npc.data().get(AGENT_ID) + ": " + AgentErrors.message(e, null)); return false; }
    }
    private void restoreInventory(NPC npc) {
        try {
            if (!(npc.getEntity() instanceof Player p) || !npc.data().has(AGENT_INVENTORY)) return;
            ItemStack[] saved = ItemStack.deserializeItemsFromBytes(AgentInventoryCodec.decode(npc.data().get(AGENT_INVENTORY)));
            ItemStack[] full = AgentEquipment.restoreSlots(saved, p.getInventory().getSize());
            p.getInventory().setContents(full);
            AgentEquipment.applyWorn(p.getInventory(), full);
            Object held = npc.data().get(AGENT_HELD, -1);
            if (held instanceof Number h && h.intValue() >= 0 && h.intValue() <= 8) p.getInventory().setHeldItemSlot(h.intValue());
            getLogger().info("Restored inventory for agent " + npc.data().get(AGENT_ID) + " (" + AgentInventoryCodec.count(full) + " items, worn kept)");
        } catch (RuntimeException e) { getLogger().warning("Could not restore inventory for agent " + npc.data().get(AGENT_ID) + ": " + AgentErrors.message(e, null)); }
        finally { awaitingRestore.remove(npc.getUniqueId()); }
    }

    private NPC find(String id) {
        NPC found = null;
        for (NPC npc : CitizensAPI.getNPCRegistry()) {
            if (npc.data().has(AGENT_ID) && AgentIds.same(id, AgentErrors.text(npc.data().get(AGENT_ID)))) {
                require(found == null, "Duplicate stored agent ID: " + id);
                found = npc;
            }
        }
        return found;
    }

    private static Map<String, Object> summary(NPC npc) {
        return result("id", AgentIds.publicId(npc.data().get(AGENT_ID)), "name", npc.getName(), "npcId", npc.getId(),
                "uuid", npc.getUniqueId().toString(), "spawned", bodyUp(npc),
                "position", position(bodyUp(npc)?npc.getEntity().getLocation():npc.getStoredLocation()), "moving", bodyUp(npc) && npc.getNavigator().isNavigating(), "navigation",AgentNavigation.snapshot(npc),
                "pose", AgentSleep.view(bodyUp(npc) && npc.getEntity() instanceof Player pl ? pl : null, npc).get("pose"),
                "sleeping", AgentSleep.view(bodyUp(npc) && npc.getEntity() instanceof Player pl ? pl : null, npc).get("sleeping"));
    }

    private static List<Object> inventory(Player player) {
        return AgentEquipment.items(player);
    }

    private static Object item(ItemStack stack) {
        return result("material", stack.getType().name(), "amount", stack.getAmount());
    }

    private static Location destination(JsonObject input) {
        World world = Bukkit.getWorld(text(input, "world"));
        require(world != null, "Unknown world");
        double x = number(input, "x"), y = number(input, "y"), z = number(input, "z");
        require(y >= world.getMinHeight() && y < world.getMaxHeight(), "y is outside world height");
        Location location = new Location(world, x, y, z);
        require(world.getWorldBorder().isInside(location), "Position is outside world border");
        return location;
    }

    private static Block reachable(Player player, JsonObject input) {
        for (String key : new String[] {"x", "y", "z"}) integer(input, key);
        Location target = destination(input);
        require(target.getWorld().equals(player.getWorld()), "Target must be in the agent's world");
        Block block = target.getBlock();
        require(AgentReach.inReach(player.getEyeLocation(), block), "Target is outside five-block reach (" +
                Math.round(AgentReach.nearest(player.getEyeLocation(), block) * 10) / 10.0 + " blocks from eyes; max 5.0)");
        requireVisible(player, block);
        return block;
    }

    static int collectNearby(Player player,double radius) {
        return player == null ? 0 : collectAround(player, player.getLocation(), radius);
    }

    /** Vacuum ground items around a point (bypasses vanilla pickup delay). */
    static int collectAround(Player player, Location at, double radius) {
        if (player == null || at == null || at.getWorld() == null || radius <= 0) return 0;
        int picked = 0;
        for (Entity entity : at.getWorld().getNearbyEntities(at, radius, radius, radius)) {
            if (!(entity instanceof Item dropped) || !dropped.isValid()) continue;
            ItemStack stack = dropped.getItemStack();
            Map<Integer, ItemStack> left = player.getInventory().addItem(stack.clone());
            int remaining = left.values().stream().mapToInt(ItemStack::getAmount).sum();
            picked += stack.getAmount() - remaining;
            if (remaining == 0) dropped.remove();
            else { ItemStack rest = stack.clone(); rest.setAmount(remaining); dropped.setItemStack(rest); }
        }
        if (picked > 0) {
            NPC n = CitizensAPI.getNPCRegistry().getNPC(player);
            if (n != null && n.data().has(AGENT_ID))
                AgentEvents.emitGated(AgentEvents.agentId(n), "item.picked_up", "pickup", AgentEvents.PICKUP_MS, AgentEvents.data("amount", picked));
        }
        return picked;
    }

    private static boolean open(Block b){return AgentPathfinder.cellOpen(b.getType().name(),b.isLiquid(),b.isPassable(),b.getType().isCollidable(),AgentPathfinder.thinPassable(b));}
    static Location safeStandingPosition(Location requested) {
        Location found=findSafeStanding(requested,3);
        require(found!=null,"No safe standing position within 3 blocks of requested spawn");
        return found;
    }

    static final int RESPAWN_VERTICAL_RADIUS = 16;
    /** A saved spawn can be above a creeper crater; search the nearby columns vertically as well. */
    static Location safeRespawnPosition(Location requested) {
        Location found=findSafeStanding(requested,RESPAWN_VERTICAL_RADIUS);
        require(found!=null,"No safe standing position within 3 horizontal and "+RESPAWN_VERTICAL_RADIUS+" vertical blocks of "+locString(requested));
        return found;
    }
    /** Watchdog/death: 16-block vertical search, then world spawn if the last cell is mid-air or sealed. */
    static Location safeStandingOrWorldSpawn(Location requested) {
        Location found=findSafeStanding(requested,RESPAWN_VERTICAL_RADIUS);
        if(found!=null)return found;
        if(requested==null||requested.getWorld()==null)return null;
        Location spawn=requested.getWorld().getSpawnLocation();
        if(spawn==null)return null;
        found=findSafeStanding(spawn,RESPAWN_VERTICAL_RADIUS);
        if(found!=null){
            found.setYaw(requested.getYaw());
            found.setPitch(requested.getPitch());
        }
        return found;
    }

    /**
     * Voluntary {@code respawn}: bed/anchor if set, else world spawn. Same
     * teleport class as death (safe stand + no-damage). Alive bodies teleport;
     * a dead body is spawned.
     */
    Map<String,Object> respawnAtHome(NPC npc, Player player) {
        Location persisted = AgentRespawn.location(npc);
        String persistedKind = null;
        if (npc != null && npc.data() != null) {
            AgentRespawn.Point stored = AgentRespawn.parse(AgentErrors.text(npc.data().get(AgentRespawn.KEY, "")));
            if (stored != null) persistedKind = stored.kind;
        }
        Location vanilla = null;
        try { if (player != null) vanilla = player.getRespawnLocation(); } catch (Throwable ignored) {}
        org.bukkit.World world = player != null ? player.getWorld() : (persisted != null ? persisted.getWorld() : null);
        Location worldSpawn = world == null ? null : world.getSpawnLocation();
        Location home = AgentRespawn.chooseHome(persisted, vanilla, worldSpawn);
        require(home != null && home.getWorld() != null, "No bed or world spawn to respawn at");
        Location safe = safeStandingOrWorldSpawn(home);
        require(safe != null, "No standable cell at bed or world spawn");
        String kind = AgentRespawn.kindOf(home, persisted, persistedKind, worldSpawn);
        AgentNavigation.stop(npc);
        AgentBehaviors.cancel(npc);
        AgentCombat.stop(npc);
        AgentProtect.stop(npc);
        AgentSelfDefense.stop(npc);
        AgentFocus.note(npc, "respawn", safe);
        if (bodyUp(npc) && player != null && !player.isDead()) {
            try { player.setVelocity(new org.bukkit.util.Vector(0, 0, 0)); } catch (RuntimeException ignored) {}
            require(player.teleport(safe), "Teleport to " + locString(safe) + " was rejected");
            AgentRespawn.applyNoDamage(player);
            AgentRespawn.apply(player, home);
        } else {
            try {
                if (npc.getEntity() != null) npc.despawn(net.citizensnpcs.api.event.DespawnReason.PENDING_RESPAWN);
                require(npc.spawn(safe), "Citizens rejected respawn at " + locString(safe));
                if (npc.getEntity() instanceof Player pl) {
                    pl.setGameMode(GameMode.SURVIVAL);
                    configureBody(npc);
                    AgentRespawn.applyNoDamage(pl);
                    AgentRespawn.restore(npc, pl);
                    markSpawned(npc, true);
                    player = pl;
                }
            } catch (IllegalArgumentException e) { throw e; }
            catch (RuntimeException ex) { throw new IllegalArgumentException(AgentTargets.requireError(AgentErrors.message(ex, "respawn"))); }
        }
        npc.data().setPersistent(AGENT_LAST, locString(safe));
        AgentEvents.emit(AgentEvents.agentId(npc), "agent.respawned",
                AgentEvents.data("x", safe.getX(), "y", safe.getY(), "z", safe.getZ(), "kind", kind));
        return result("kind", kind, "x", safe.getX(), "y", safe.getY(), "z", safe.getZ(),
                "world", safe.getWorld().getName(), "respawnPoint", AgentRespawn.view(npc, player));
    }

    private static Location findSafeStanding(Location requested,int verticalRadius) {
        if(requested==null||requested.getWorld()==null)return null;
        World world=requested.getWorld();int r=3;
        int bx=AgentSafeSpawn.blockCoord(requested.getX()),bz=AgentSafeSpawn.blockCoord(requested.getZ());
        try{for(int cx=(bx-r)>>4;cx<=(bx+r)>>4;cx++)for(int cz=(bz-r)>>4;cz<=(bz+r)>>4;cz++)if(!world.isChunkLoaded(cx,cz))world.getChunkAt(cx,cz);}
        catch(RuntimeException ignored){}
        int[] c=AgentSafeSpawn.find(requested.getX(),requested.getY(),requested.getZ(),r,verticalRadius,new AgentSafeSpawn.Probe(){
            public boolean open(int x,int y,int z){return AgentsMC.open(world.getBlockAt(x,y,z));}
            public boolean floor(int x,int y,int z){Block b=world.getBlockAt(x,y,z);return AgentPathfinder.cellFloor(b.getType().name(),b.isLiquid(),b.isPassable(),b.getType().isSolid(),AgentPathfinder.thinPassable(b),AgentPathfinder.surfaceOf(b));}
            public boolean usable(int x,int y,int z){return y-1>=world.getMinHeight()&&y+1<world.getMaxHeight()&&world.getWorldBorder().isInside(new Location(world,x+.5,y,z+.5));}
        });
        if(c==null)return null;
        return new Location(world,c[0]+.5,c[1],c[2]+.5,requested.getYaw(),requested.getPitch());
    }

    /** True when a live player entity exists in a loaded world, even if Citizens' handle-validity flag lags (NEEDS_RESYNC). */
    static boolean bodyUp(NPC npc){return npc.getEntity() instanceof Player&&AgentBodyState.present(bodyStatus(npc));}
    private final Map<java.util.UUID,Long> lastReconcile=new java.util.HashMap<>();
    private final Map<java.util.UUID,String> bodyRecoveryErrors=new java.util.HashMap<>();
    private static final Map<java.util.UUID,Long> lastStatusLog=new java.util.concurrent.ConcurrentHashMap<>();
    private static final Map<java.util.UUID,AgentBodyState.Status> lastStatus=new java.util.concurrent.ConcurrentHashMap<>();
    /** Single online check: entity exists and is not dead. Ignores chunk-loaded and Citizens isSpawned for presence. */
    static AgentBodyState.Status bodyStatus(NPC npc){
        org.bukkit.entity.Entity e=npc.getEntity();
        boolean has=e!=null, spawned=npc.isSpawned(), valid=has&&e.isValid(), dead=has&&e.isDead();
        boolean loaded=false;
        try{loaded=has&&e.getWorld()!=null&&e.getWorld().isChunkLoaded(e.getLocation().getBlockX()>>4,e.getLocation().getBlockZ()>>4);}catch(RuntimeException ignored){}
        AgentBodyState.Status st=AgentBodyState.classify(has,spawned,has&&!dead,loaded);
        java.util.UUID id=npc.getUniqueId();long now=System.currentTimeMillis();
        AgentBodyState.Status prev=lastStatus.put(id,st);Long t=lastStatusLog.get(id);
        if(prev!=st||t==null||now-t>=60_000){lastStatusLog.put(id,now);try{
            (INSTANCE!=null?INSTANCE.getLogger():java.util.logging.Logger.getLogger("AgentsMC")).info("bodyStatus "+(npc.data()!=null?npc.data().get(AGENT_ID,"?"):"?")+" npc="+npc.getId()+": entityPresent="+has+" isSpawned="+spawned
                +" isValid="+valid+" isDead="+dead+" chunkLoaded="+loaded+" result="+st+(prev!=null&&prev!=st?" (was "+prev+")":""));}catch(RuntimeException ignored){}}
        return st;
    }
    /** Brings Citizens' view in line with the real entity: re-registers visible-but-invalid bodies, re-spawns stale ones that should be up. */
    private void reconcile(NPC npc){
        AgentBodyState.Status st=bodyStatus(npc);
        if(npc.getEntity()!=null&&!npc.getEntity().isDead()){if(!Boolean.TRUE.equals(npc.data().get(AGENT_SPAWNED,false)))markSpawned(npc,true);return;}
        if(st==AgentBodyState.Status.ABSENT&&!Boolean.TRUE.equals(npc.data().get(AGENT_SPAWNED,false)))return;
        // Heartbeat/list call this every second; retry failed lifecycle recovery at most every 5s per body.
        long now=System.currentTimeMillis();Long prev=lastReconcile.get(npc.getUniqueId());
        if(prev!=null&&now-prev<5_000)return;Long dr=pendingDeathRespawn.get(npc.getUniqueId());if(dr!=null&&now<dr)return;lastReconcile.put(npc.getUniqueId(),now);
        Location live=null;
        try{if(npc.getEntity()!=null&&!npc.getEntity().isDead())live=npc.getEntity().getLocation();}catch(RuntimeException ignored){}
        Location at=watchdogAt(savedLocation(npc),live); // Death records the respawn point; a dead entity is still at the death site.
        if(at==null||at.getWorld()==null)return;
        getLogger().info("Watchdog: agent "+npc.data().get(AGENT_ID)+" body missing ("+st+"); respawning at "+locString(at));
        try{
            if(npc.getEntity()!=null&&!npc.getEntity().isDead())return; // never despawn an existing body
            if(npc.getEntity()!=null)npc.despawn(net.citizensnpcs.api.event.DespawnReason.PENDING_RESPAWN);
            Location safe=safeStandingOrWorldSpawn(at);
            require(safe!=null,"no standable cell or world spawn for "+npc.data().get(AGENT_ID));
            require(npc.spawn(safe), "Citizens rejected watchdog respawn at " + locString(safe));
            if(npc.getEntity() instanceof Player pl){pl.setGameMode(GameMode.SURVIVAL);configureBody(npc);AgentRespawn.applyNoDamage(pl);markSpawned(npc,true);
                getLogger().info("Resynchronized agent body "+npc.data().get(AGENT_ID)+" ("+st+") at "+locString(safe));}
        }catch(RuntimeException ex){bodyRecoveryErrors.put(npc.getUniqueId(),AgentErrors.message(ex,"watchdog respawn"));getLogger().warning("Could not resync agent "+npc.data().get(AGENT_ID)+": "+ex.getMessage());}
    }
    private void respawnAfterDeath(NPC npc){
        pendingDeathRespawn.remove(npc.getUniqueId());
        if(!npc.data().has(AGENT_ID)||bodyUp(npc))return;
        Location at=savedLocation(npc);if(at==null||at.getWorld()==null){getLogger().warning("No respawn point for agent "+npc.data().get(AGENT_ID));return;}
        try{if(npc.getEntity()!=null)npc.despawn(net.citizensnpcs.api.event.DespawnReason.PENDING_RESPAWN);
            Location safe=safeStandingOrWorldSpawn(at);
            require(safe!=null,"No standable cell or world spawn for agent "+npc.data().get(AGENT_ID));
            if(npc.spawn(safe)&&npc.getEntity() instanceof Player pl){pl.setGameMode(GameMode.SURVIVAL);pl.setHealth(pl.getMaxHealth());AgentHunger.applyReset(pl);pl.setFireTicks(0);
                AgentRespawn.applyNoDamage(pl);
                npc.getOrAddTrait(Spawned.class).setSpawned(true);configureBody(npc);AgentRespawn.restore(npc,pl);markSpawned(npc,true);storeNPCsNow();
                AgentEvents.emit(AgentEvents.agentId(npc),"agent.respawned",AgentEvents.data("x",safe.getX(),"y",safe.getY(),"z",safe.getZ()));
                getLogger().info("Respawned agent "+npc.data().get(AGENT_ID)+" after death at "+locString(safe));}
            else throw new IllegalStateException("Citizens rejected death respawn at " + locString(safe));
        }catch(RuntimeException ex){bodyRecoveryErrors.put(npc.getUniqueId(),AgentErrors.message(ex,"death respawn"));getLogger().warning("Death respawn failed for "+npc.data().get(AGENT_ID)+": "+ex.getMessage());}
    }
    /** Holds plugin chunk tickets on the body's chunk and its 3x3 neighbors so walking never unloads the NPC. */
    private void keepChunk(NPC npc){
        rememberLocation(npc);
        Location l=npc.getEntity()!=null?npc.getEntity().getLocation():(Boolean.TRUE.equals(npc.data().get(AGENT_SPAWNED,false))?savedLocation(npc):null);
        if(l==null||l.getWorld()==null){releaseChunk(npc);return;}
        long[] want={l.getBlockX()>>4,l.getBlockZ()>>4};long[] have=chunkTickets.get(npc.getUniqueId());
        if(have!=null&&have[0]==want[0]&&have[1]==want[1]&&l.getWorld().getName().equals(ticketWorld.get(npc.getUniqueId())))return;
        releaseChunk(npc);
        World world=l.getWorld();
        for(int dx=-1;dx<=1;dx++)for(int dz=-1;dz<=1;dz++)world.addPluginChunkTicket((int)want[0]+dx,(int)want[1]+dz,this);
        chunkTickets.put(npc.getUniqueId(),want);ticketWorld.put(npc.getUniqueId(),world.getName());
        getLogger().info("Chunk ticket for agent "+npc.data().get(AGENT_ID)+" at "+world.getName()+" "+want[0]+","+want[1]);
    }
    private void releaseChunk(NPC npc){
        long[] have=chunkTickets.remove(npc.getUniqueId());World w=Bukkit.getWorld(String.valueOf(ticketWorld.remove(npc.getUniqueId())));
        if(have!=null&&w!=null)for(int dx=-1;dx<=1;dx++)for(int dz=-1;dz<=1;dz++)w.removePluginChunkTicket((int)have[0]+dx,(int)have[1]+dz,this);
    }
    /** Prefer a live (non-dead) entity cell over stale AGENT_LAST so a short drop is not a bed relocate. */
    static Location watchdogAt(Location saved, Location live) {
        return live != null && live.getWorld() != null ? live : saved;
    }
    private Location savedLocation(NPC npc){
        String[] p=npc.data().get(AGENT_LAST,"").split(";");
        if(p.length==6&&Bukkit.getWorld(p[0])!=null)return new Location(Bukkit.getWorld(p[0]),Double.parseDouble(p[1]),Double.parseDouble(p[2]),Double.parseDouble(p[3]),Float.parseFloat(p[4]),Float.parseFloat(p[5]));
        return npc.getStoredLocation();
    }

    private void configureBody(NPC npc){
        npc.data().setPersistent(NPC.Metadata.KEEP_CHUNK_LOADED, Boolean.TRUE);
        npc.data().setPersistent(NPC.Metadata.PATHFINDER_OPEN_DOORS, Boolean.TRUE);
        exposeToMobs(npc);
    }
    private void exposeToMobs(NPC npc){
        npc.setProtected(false);
        npc.data().setPersistent(NPC.Metadata.DEFAULT_PROTECTED, Boolean.FALSE);
        npc.data().setPersistent(NPC.Metadata.COLLIDABLE, Boolean.TRUE);
        npc.data().setPersistent(NPC.Metadata.REMOVE_FROM_PLAYERLIST, Boolean.FALSE);
        try { npc.getOrAddTrait(TargetableTrait.class).setTargetable(true); } catch (RuntimeException ignored) {}
        if (npc.getEntity() != null) {
            try { NMS.addOrRemoveFromPlayerList(npc.getEntity(), false); } catch (Throwable ignored) {}
            if (npc.getEntity() instanceof Player pl) AgentRespawn.restore(npc, pl);
        }
    }
    static void rememberLocation(NPC npc){
        if(!bodyUp(npc)||npc.data()==null)return;
        npc.data().setPersistent(AGENT_LAST, locString(npc.getEntity().getLocation()));
    }
    private void markSpawned(NPC npc, boolean spawned) {
        npc.data().setPersistent(AGENT_SPAWNED, spawned);
        if(spawned)bodyRecoveryErrors.remove(npc.getUniqueId());
        if(spawned)rememberLocation(npc);
    }
    private static String locString(Location l){return l.getWorld().getName()+";"+l.getX()+";"+l.getY()+";"+l.getZ()+";"+l.getYaw()+";"+l.getPitch();}
    private void restoreSpawnedAgents() {
        for(NPC npc:CitizensAPI.getNPCRegistry()){
            if(!npc.data().has(AGENT_ID)||AgentBodyState.present(bodyStatus(npc))||!Boolean.TRUE.equals(npc.data().get(AGENT_SPAWNED,false)))continue;
            try{
                Location at=null;String saved=npc.data().get(AGENT_LAST,"");String[] p=saved.split(";");
                if(p.length==6&&Bukkit.getWorld(p[0])!=null)at=new Location(Bukkit.getWorld(p[0]),Double.parseDouble(p[1]),Double.parseDouble(p[2]),Double.parseDouble(p[3]),Float.parseFloat(p[4]),Float.parseFloat(p[5]));
                if(at==null)at=npc.getStoredLocation();
                require(at!=null&&at.getWorld()!=null,"no saved position");
                if(npc.getEntity()!=null)npc.despawn(net.citizensnpcs.api.event.DespawnReason.PENDING_RESPAWN);
                Location safe=safeStandingOrWorldSpawn(at);
                require(safe!=null,"no standable cell or world spawn");
                require(npc.spawn(safe),"Citizens could not spawn");
                if(npc.getEntity() instanceof Player pl)pl.setGameMode(GameMode.SURVIVAL);
                configureBody(npc);markSpawned(npc,true);restoreInventory(npc);AgentHunger.restore(npc);
                getLogger().info("Respawned agent "+npc.data().get(AGENT_ID)+" at saved position");
            }catch(RuntimeException e){bodyRecoveryErrors.put(npc.getUniqueId(),AgentErrors.message(e,"startup respawn"));getLogger().warning("Could not respawn agent "+npc.data().get(AGENT_ID)+": "+e.getMessage());}
        }
        storeNPCsNow();
    }

    private static Object position(Location p) {
        return p == null ? null : result("world", p.getWorld().getName(), "x", p.getX(), "y", p.getY(), "z", p.getZ());
    }

    private static void keys(JsonObject input, List<String> optional, String... allowed) {
        List<String> list = Arrays.asList(allowed);
        String action = input.has("action") && input.get("action").isJsonPrimitive() ? input.get("action").getAsString() : "action";
        for (String key : input.keySet()) require(list.contains(key) || optional.contains(key), "Unknown field '" + key + "' for action " + action);
        for (String key : allowed) require(input.has(key) && !input.get(key).isJsonNull(), "Missing field '" + key + "' for action " + action);
    }

    private static void applyPlaced(Block block, Material material, int supportFace, BlockFace playerFacing, boolean extra, boolean explicit) {
        BlockData data = material.createBlockData();
        BlockFace look = playerFacing;
        if (look == null || look == BlockFace.SELF) look = BlockFace.SOUTH;
        if (data instanceof Door door) {
            door.setHalf(extra ? Bisected.Half.TOP : Bisected.Half.BOTTOM);
            if (look != BlockFace.UP && look != BlockFace.DOWN) door.setFacing(look);
            door.setOpen(false);
            block.setBlockData(door, false);
            return;
        }
        if (data instanceof Bed bed) {
            bed.setPart(extra ? Bed.Part.HEAD : Bed.Part.FOOT);
            if (look != BlockFace.UP && look != BlockFace.DOWN) bed.setFacing(look);
            block.setBlockData(bed, false);
            return;
        }
        String attached = AgentPlacement.attachedFacing(supportFace);
        if (data instanceof Directional dir) {
            String chosen = AgentPlacement.directionalFacing(material.getKey().getKey(), look.name(), supportFace, explicit);
            BlockFace face = chosen == null ? null : BlockFace.valueOf(chosen.toUpperCase(Locale.ROOT));
            if (face != null && dir.getFaces().contains(face)) dir.setFacing(face);
            else if (attached != null) dir.setFacing(BlockFace.valueOf(attached.toUpperCase(Locale.ROOT)));
            else if (dir.getFaces().contains(look)) dir.setFacing(look);
            block.setBlockData(dir, false);
            return;
        }
        if (data instanceof Orientable orient) {
            Axis axis = Axis.valueOf(AgentPlacement.axisOf(look.name()).toUpperCase(Locale.ROOT));
            if (orient.getAxes().contains(axis)) orient.setAxis(axis);
            block.setBlockData(orient, false);
            return;
        }
        if (data instanceof Rotatable rot) {
            if (look != BlockFace.UP && look != BlockFace.DOWN) rot.setRotation(look);
            block.setBlockData(rot, false);
            return;
        }
        block.setType(material, false);
    }

    private static void applySignText(Block block, String text) {
        AgentSigns.write(block, text, "front");
    }

    /** Selects the hotbar slot holding the block; moves it from the main inventory into the held slot if needed. */
    private static void selectBlock(Player player, String key) {
        Material wanted = Material.matchMaterial(key);
        require(wanted != null && (wanted.isBlock() || AgentPlacement.isPlaceableItem(wanted.getKey().getKey())
                || AgentBoats.isBoatItem(wanted.getKey().getKey())), "Unknown block: " + key);
        org.bukkit.inventory.PlayerInventory inventory = player.getInventory();
        String[] hotbar = new String[9];
        for (int i = 0; i < 9; i++) { ItemStack item = inventory.getItem(i); hotbar[i] = item == null ? "" : item.getType().getKey().getKey(); }
        int slot = AgentPlacement.hotbarSlot(hotbar, inventory.getHeldItemSlot(), wanted.getKey().getKey());
        if (slot >= 0) { inventory.setHeldItemSlot(slot); return; }
        for (int i = 9; i < 36; i++) {
            ItemStack item = inventory.getItem(i);
            if (item != null && item.getType() == wanted) {
                int held = inventory.getHeldItemSlot(); ItemStack previous = inventory.getItem(held);
                inventory.setItem(held, item); inventory.setItem(i, previous); return;
            }
        }
        throw new IllegalArgumentException("No " + wanted.name() + " in inventory");
    }

    private static void keys(JsonObject input, String... allowed) {
        keys(input, List.of(), allowed);
    }

    private static String firstString(JsonObject input, String... keys) {
        String value = AgentTargets.first(input, keys);
        return value.isBlank() ? null : value;
    }

    private List<Object> inventorySnapshot(NPC npc, Player player) {
        require(Bukkit.isPrimaryThread(), "Inventory must be read on the server thread");
        if (awaitingRestore.contains(npc.getUniqueId()) && npc.data().has(AGENT_INVENTORY)) {
            try {
                ItemStack[] saved = ItemStack.deserializeItemsFromBytes(AgentInventoryCodec.decode(npc.data().get(AGENT_INVENTORY)));
                return AgentEquipment.items(saved);
            } catch (RuntimeException ignored) {}
        }
        return inventory(player);
    }

    private Map<String, Object> equipmentSnapshot(NPC npc, Player player) {
        if (awaitingRestore.contains(npc.getUniqueId()) && npc.data().has(AGENT_INVENTORY)) {
            try {
                ItemStack[] saved = ItemStack.deserializeItemsFromBytes(AgentInventoryCodec.decode(npc.data().get(AGENT_INVENTORY)));
                int held = 0;
                try { held = player.getInventory().getHeldItemSlot(); } catch (RuntimeException ignored) {}
                return AgentEquipment.view(saved, held);
            } catch (RuntimeException ignored) {}
        }
        return AgentEquipment.view(player);
    }

    private static String text(JsonObject input, String key) {
        require(input.has(key), "Missing field '" + key + "'");
        require(!input.get(key).isJsonNull(), "Field '" + key + "' must not be null");
        require(input.get(key).isJsonPrimitive() && input.getAsJsonPrimitive(key).isString(),
                "Field '" + key + "' must be a string" + kindHint(input.get(key)));
        String value = input.get(key).getAsString();
        require(!value.isEmpty(), "Empty field: " + key);
        return value;
    }

    private static double number(JsonObject input, String key) {
        require(input.has(key), "Missing field '" + key + "'");
        require(!input.get(key).isJsonNull(), "Field '" + key + "' must not be null");
        require(input.get(key).isJsonPrimitive() && input.getAsJsonPrimitive(key).isNumber(),
                "Field '" + key + "' must be a number" + kindHint(input.get(key)));
        double value = input.get(key).getAsDouble();
        require(Double.isFinite(value), "Field '" + key + "' must be a finite number");
        return value;
    }
    static String kindHint(com.google.gson.JsonElement v) {
        if (v == null || v.isJsonNull()) return " (got null)";
        if (v.isJsonObject()) return " (got object)";
        if (v.isJsonArray()) return " (got array)";
        if (v.isJsonPrimitive() && v.getAsJsonPrimitive().isString()) return " (got string)";
        if (v.isJsonPrimitive() && v.getAsJsonPrimitive().isBoolean()) return " (got boolean)";
        return "";
    }

    /** Accepts 3, 3.0 or "3". */
    private static int lenientInt(JsonObject input, String key) {
        if (input.has(key) && input.get(key).isJsonPrimitive() && input.getAsJsonPrimitive(key).isString()) {
            try { return (int) Math.round(Double.parseDouble(input.get(key).getAsString().trim())); }
            catch (NumberFormatException e) { throw new IllegalArgumentException("Expected number: " + key); }
        }
        double v = number(input, key); return (int) Math.round(v);
    }
    private static boolean flag(JsonObject input, String key) {
        var v = input.getAsJsonPrimitive(key);
        if (v.isBoolean()) return v.getAsBoolean();
        if (v.isString()) return Boolean.parseBoolean(v.getAsString().trim());
        if (v.isNumber()) return v.getAsDouble() != 0;
        throw new IllegalArgumentException("Expected boolean: " + key);
    }
    /** Reach for placement: the ray may legitimately hit the neighbour being placed against. */
    private static Block placeTarget(Player player, JsonObject input) {
        for (String key : new String[] {"x", "y", "z"}) integer(input, key);
        Location target = destination(input);
        require(target.getWorld().equals(player.getWorld()), "Target must be in the agent's world");
        Block block = target.getBlock();
        require(AgentReach.inReach(player.getEyeLocation(), block), "Target " + block.getX() + "," + block.getY() + "," + block.getZ() +
                " is out of reach (" + Math.round(AgentReach.nearest(player.getEyeLocation(), block) * 10) / 10.0 + " blocks from eyes; max 5.0)");
        requireVisible(player, block);
        return block;
    }

    private static void requireVisible(Player player, Block block) {
        if (AgentReach.visibleFace(player, block)) return;
        Block obstruction = AgentBehaviors.firstObstruction(player, block);
        if (obstruction == null || obstruction.equals(block)) return;
        throw new IllegalArgumentException("Target is obstructed by " + obstruction.getType().name() + " at " + obstruction.getX() + "," + obstruction.getY() + "," + obstruction.getZ());
    }

    private static Map<String, Object> breakTarget(Player player, NPC npc, Block block, String action) {
        AgentBehaviors.equipTool(player, block);
        ItemStack hand = player.getInventory().getItemInMainHand();
        ItemStack tool = hand == null ? null : hand.clone();
        ItemStack probe = tool == null || tool.getType().isAir() ? new ItemStack(Material.AIR) : tool;
        boolean emptyDrops = false;
        try { emptyDrops = block.getDrops(probe).isEmpty(); } catch (RuntimeException ignored) {}
        Material before = block.getType();
        String toolName = tool == null || tool.getType().isAir() ? "HAND" : tool.getType().name();
        if ((emptyDrops || AgentBehaviors.wouldDestroy(toolName, before.name()))
                && !AgentBehaviors.allowEmptyDrops(before.name()))
            throw new IllegalArgumentException(AgentBehaviors.needToolMessage(before.name(), toolName));
        List<String> cleared = AgentBehaviors.clearPath(player, block);
        AgentFocus.note(npc, action, block);
        require(AgentProtection.breakBlock(player, block), "Block break was rejected");
        AgentBehaviors.noteMined(player, before);
        AgentHunger.exhaust(player, AgentHunger.BREAK);
        ItemStack after = player.getInventory().getItemInMainHand();
        boolean toolBroke = tool != null && AgentBehaviors.isTool(tool)
                && (after == null || after.getType().isAir() || after.getType() != tool.getType());
        int picked = collectNearby(player, 5.5);
        Map<String, Object> output = result("before", before.name(), "after", block.getType().name(), "pickedFromDrops", picked,
                "cleared", cleared, "tool", toolName,
                "toolBroke", toolBroke, "emptyDrops", emptyDrops,
                "note", emptyDrops
                        ? "Broke " + before.name() + " with " + toolName + " even though it dropped nothing"
                        : "Block drops within 5.5 blocks were inserted into inventory; remaining drops stay in the world");
        if (toolBroke) output.put("brokenTool", tool.getType().name());
        return output;
    }

    private static int integer(JsonObject input, String key) {
        double value = number(input, key);
        require(value == Math.rint(value) && value >= Integer.MIN_VALUE && value <= Integer.MAX_VALUE, "Expected integer: " + key);
        return (int) value;
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new IllegalArgumentException(AgentTargets.requireError(message));
    }

    private static Map<String, Object> result(Object... pairs) {
        Map<String, Object> output = new LinkedHashMap<>();
        for (int i = 0; i < pairs.length; i += 2) output.put((String) pairs[i], pairs[i + 1]);
        return output;
    }
}

