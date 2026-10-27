package dev.agentsmc;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** The plugin is the source of truth for action discovery. Planned actions are never advertised as executable. */
final class AgentActionCatalog {
    static final String VERSION = "2";
    private static final List<Map<String,Object>> CATALOG = new ArrayList<>();
    private static final Set<String> ENABLED = Set.of("anvil","attack","block","breed","break","brew","build_plan","chat_log","climb","collect","craft","defend","dig","dismount","drink","drop","eat","emote","enchant","equip","feed","feedback","feedback_list","feedback_resolve","fight","fill","fish","follow","fuel","give","goto","harvest","heal","heightmap","inspect_block","inspect_container","inspect_entity","inspect_interface","inspect_item","interact","interact_player","inventory","journal","jump","kill","locate","look","map","milk","mount","move","move_item","observe","op","pickup","place","plant","portal","use_portal","preflight","protect","recipe","region","reply","respawn","retreat","say","scan","scan_box","scan_entities","select","set_spawn","shear","shield","shoot","sleep","smelt","smith","sneak","sort","split_stack","sprint","stonecut","stop","survey","swap","swim","tame","throw","till","time","trade","trade_offers","transfer","use","wait","walkmap","water","waypoint","whisper","worldmap");
    /** Strictly observational actions (fb-21): no world, inventory, body, persistent map/note, inbox-read, or chat side effects. */
    static final Set<String> READ_ONLY = Set.of("chat_log","feedback_list","heightmap","walkmap","inspect_block","inspect_entity","inspect_item","inventory","observe","recipe","scan","scan_box","region","scan_entities","time","trade_offers","locate");
    static {
        add("move", "Movement & Navigation", "Walk a short relative goto (1..16 blocks) north/south/east/west or forward/back/left/right from facing; same A* as goto, no dig, no up/down fly", "direction,blocks");
        add("goto", "Movement & Navigation", "Walk a grid A* path (jump-1, drop-3, swim, climb, snow/slabs/carpets) by applying walk velocity each tick, or steer a mounted boat; a standalone goto returns accepted while the body walks (observe.navigation reports arrived/failed); a goto inside a steps program waits for arrival or failure. A player is 1.8 tall so a 1-block hole is not a walk (head cell cannot be a slab/snow/leaves); 2-block faces are walked around to a 1-step instead of failing on the rim (a far goto does not treat the foot of an intermediate 2-block face as an approach, so a crater-rim pocket is not a stall); a 1-block step under a leaf/log canopy is walked around (JUMP is skipped when foliage is at current y+2 so the body does not bonk the tree; a stone/dirt 1x2 stair ceiling still climbs); mushrooms, flowers, short grass, ferns, and saplings are passable; wooden doors and fence gates are walked through (the plugin opens them and closes them after the body passes; spawn-protection doors stay usable and villagers do not slam them); a cratered doorway or chest-front within 6 horizontal and +2/-3 vertical is a STOOP hop into or out of the door (same teleport class as the air snap; spawn protection still cannot place dirt); dig defaults to false so a walk does not mine through a house; dig:true mines a 1x2 stair/tunnel through stone (not inside spawn protection); unstick does not break blocks unless dig is true; short hops search locally so an unreachable hut is not a 20k-cell no_path; arrived_near within ~1 block, or within 2 blocks after a 3s stall (a reachable dest is not a failure because the body stopped close); moving is committed net displacement (jitter is not progress); a 20k-budget miss that already closed to within 8 returns that approach instead of no_path; JUMP waypoints are reached only when the feet are on that step (not mid-air below it); climb velocity never exceeds the next waypoint Y or target Y+1, never hops a dest more than one block above the current stand cell (vanilla cannot mount a 2-block face), and never keeps leftover upward vy past that cap; pillar unstick never builds above target Y+1; fails no_path/blocked_by_protection/unloaded_chunks/timeout/climbed_past_path (then snaps down the same column); recovered_from_air only after ~1.5s with no solid within ~1 block below the feet, and only down an open column (never through stone, never from scan). Supports optional background:true (non-blocking walk while thinking/acting) and abortIf:chat|whisper|threat|damage|any; overrides self-defense for a short time so an unreachable mob cannot pause the walk; idle flee and the walk home use this same A* path (not a raw driveWalk) so a 2-block face or stair does not freeze the body", "x,y,z,dig");
        add("follow", "Movement & Navigation", "Follow a player or agent by UUID or display name (case-insensitive) using the same goto A* as a walk; holds at 2-4 or on an overlook (horiz ≤4 and drop >3) or after no_path instead of twitching; fails with a specific reason if the target is missing", "uuid,target,player,name");
        add("look", "Movement & Navigation", "Look", "x,y,z");
        add("jump", "Movement & Navigation", "Jump", "");
        add("sprint", "Movement & Navigation", "Sprint", "enabled");
        add("sneak", "Movement & Navigation", "Sneak", "enabled");
        add("swim", "Movement & Navigation", "Start or stop swimming while the body is in water (sets swimming + sprint)", "enabled");
        add("climb", "Movement & Navigation", "Climb a touching ladder, vine, or scaffolding (upward velocity; not a teleport)", "");
        add("mount", "Movement & Navigation", "Mount the nearest boat, minecart or horse, or a specific entity id", "target");
        add("dismount", "Movement & Navigation", "Leave the current vehicle", "");
        add("stop", "Movement & Navigation", "Stop movement, combat, protect, and self-defense; self-defense stays off for a short time so the next goto is not immediately paused", "");
        add("wait", "Movement & Navigation", "Pause a program for a number of seconds without moving", "seconds");
        add("break", "World Interaction", "Break a reachable block (including no-drop leaves/snow; auto-picks shears/hoe/sword/shovel when owned). Iron/gold/diamond ore is refused when the only pickaxe is too weak (a wooden pick after a stone pick breaks does not destroy iron with no drop). Or pick up a boat at those coordinates. Vanilla spawn protection is a Chebyshev square (max(|dx|,|dz|) ≤ spawn-radius, usually 16) and is an operating constraint, not a plugin bug", "x,y,z");
        add("place", "World Interaction", "Place a held block, or spawn a boat/raft entity on water or a solid block. Optional facing is the front of the block (north/south/east/west/up/down, or forward/back/left/right from the body's look) for beds, doors, stairs, pistons, logs, boats, signs, and torches. A wall sign, torch, ladder, button, or lantern facing east attaches to the west neighbour so the front faces east; that neighbour must be solid or place is refused (a WALL_TORCH does not float on AIR). Without facing, the wall the body is looking at is used. Body-overlap names a stand-aside cell. text on an existing unwaxed sign edits that sign (no item consumed); side is front or back. Same spawn-protection Chebyshev square as break/dig", "x,y,z,block,item,material,slot,text,facing,direction,side");
        add("use", "World Interaction", "Activate a reachable door, fence gate, or trapdoor (5-block reach; line of sight is not required, so a cobble wall does not block the click). Wooden doors, fence gates, and trapdoors work inside spawn protection (opening is not a build); break/place/dig there are still blocked", "x,y,z");
        add("interact", "World Interaction", "Right-click a nearby entity (sit a tamed pet, face and swing); villagers use trade / trade_offers", "uuid");
        add("collect", "World Interaction", "Collect", "");
        add("drop", "World Interaction", "Drop from storage, armor, or offhand", "slot,amount");
        add("throw", "World Interaction", "Throw the held (or slot) item: snowball/egg/ender pearl/splash potion/xp bottle/trident as a projectile, else a short toss in look direction", "slot,amount");
        add("fill", "World Interaction", "Fill an empty bucket or glass bottle from a water/lava/powder-snow source, or empty a filled bucket into air or a cauldron", "x,y,z");
        add("sleep", "World Interaction", "Enter a reachable bed (or set spawn on a charged respawn anchor); sets this body's persisted respawn point. Refuses a bed already claimed by another agent or a human player so a shared base does not steal their respawn; your own bed is fine. inspect_block and observe.beds show claimed/claimedBy/mine (human owners have player:true). A bed claimed by an agent is locked to it: players (ops too) and other agents cannot sleep in it or set spawn there. Death respawns at that bed/anchor whenever it still exists and is unobstructed, even if you died next to it; otherwise world spawn (two deaths in 20s at the same cell without an intact bed also use world spawn), with 5s no-damage after respawn. If the bed is refused, reason names daytime (clock) or the nearest hostile within 8 of the bed. goto/follow/move/stop clear the sleeping pose so other players do not keep seeing a lying body; observe.pose and observe.sleeping report the live pose", "x,y,z");
        add("portal", "World Interaction", "Alias of use_portal: walk fully into a nearby nether or end portal (or x/y/z) and wait for the world change", "x,y,z,radius,timeout,vanillaOnly");
        add("use_portal", "World Interaction", "Walk fully into the nearest nether/end portal block (or x/y/z), stand inside it and wait for the world change (vanilla portal wait; if vanilla does not move the body after ~4.5s the plugin sends it through the same portal to the scaled nether/overworld coordinates, nearest existing portal first, unless vanillaOnly:true). Over the bridge (standalone or as a steps program step) it waits unless background:true and returns world, environment and position, or fails on timeout. Any goto target from the old world is cleared on the world change", "x,y,z,radius,timeout,vanillaOnly");
        add("inventory", "Inventory & Equipment", "Storage 0-35 plus armor 36-39 and offhand 40, with a named equipment object. Death keeps and restores worn armor and offhand with storage (drops are cleared so items are not duplicated)", "");
        add("select", "Inventory & Equipment", "Select", "slot");
        add("equip", "Inventory & Equipment", "Wear armor/elytra/carved pumpkin in the matching slot, or a shield/torch in the offhand; to=hand (or a non-wearable hotbar slot) selects the main hand, moving a storage sword/tool into an empty hotbar slot if needed; a worn slot unequips into storage", "slot,to");
        add("move_item", "Inventory & Equipment", "Move item across storage, armor, and offhand", "from,to,amount");
        add("split_stack", "Inventory & Equipment", "Split stack across storage, armor, and offhand", "from,to,amount");
        add("swap", "Inventory & Equipment", "Swap two slots including armor and offhand", "first,second");
        add("transfer", "Inventory & Equipment", "Move items between agent inventory and a reachable container", "x,y,z,direction,material,amount");
        add("give", "Inventory & Equipment", "Give owned items from a slot or material to a nearby player or agent body (same name lookup as follow); overflow drops at their feet; never creates items", "player,slot,amount,material,uuid,target,name");
        add("sort", "Inventory & Equipment", "Sort", "");
        add("inspect_item", "Inventory & Equipment", "Inspect a storage, armor, or offhand slot", "slot");
        add("craft", "Crafting & Production", "Craft registered Bukkit shaped/shapeless recipes with materials actually owned; item or material names the output; amount or count is output count (default 1) rounded up to the recipe yield. Missing ingredients name the material and the have/need counts (e.g. Need STICK (need 1, have 0) to craft STONE_SWORD). Planks, sticks, chest, wooden tools, bow, arrow, and crossbow use a vanilla fallback when Bukkit has no match", "item,amount,count,material");
        add("smelt", "Crafting & Production", "Insert existing ingredients into a reachable furnace at x/y/z. world is injected by the plugin (do not send it); missing coordinates report x/y/z, not world", "x,y,z,material,amount");
        add("fuel", "Crafting & Production", "Insert owned fuel into a reachable furnace", "x,y,z,material,amount");
        add("brew", "Crafting & Production", "Insert owned items into a reachable brewing stand (bottles 0-2, ingredient 3, blaze powder 4); vanilla brewing is asynchronous", "x,y,z,material,amount,slot");
        add("enchant", "Crafting & Production", "Enchant the held item at a reachable enchanting table; consumes experience levels and lapis (never free, never unsafe treasure)", "x,y,z,levels");
        add("anvil", "Crafting & Production", "Combine two inventory slots at a reachable anvil (repair same-type tools or merge compatible enchants); costs 1 level", "x,y,z,first,second");
        add("smith", "Crafting & Production", "Apply a smithing-table recipe you can afford (template/base/addition you own) at a reachable smithing table", "x,y,z,template,base,addition");
        add("stonecut", "Crafting & Production", "Cut one owned input block into item at a reachable stonecutter using a registered stonecutting recipe", "x,y,z,item,material");
        add("recipe", "Crafting & Production", "List available Bukkit recipes for an output", "item");
        add("attack", "Combat & Survival", "Melee a nearby living entity within 3.0 blocks, or pick a boat back up as an item", "uuid");
        add("fight", "Combat & Survival", "Bow/crossbow at 6-24 with line of sight (1s draw), else sprint to melee and attack on cooldown until the target dies, 30s pass, health drops below 12, or the target is unreachable (no path / no hits and no closing for 5s). Idle self-defense fights armed melee vs a melee mob down to health 6; flees immediately when unarmed vs a ranged mob, or when armed vs ranged below 12; uses goto behind cover (same A* as a player walk), then goto back when safe, and does not pick a flee dest two blocks up", "uuid");
        add("kill", "Combat & Survival", "Alias of fight: bow/crossbow at 6-24 or sprint to melee and attack until the target dies, 30s pass, or health drops below 6", "uuid");
        add("protect", "Combat & Survival", "Guard an online player or spawned agent body by display name (same lookup as follow): follow at 2-4 with the same goto A* as a walk, hold on an overlook or after no_path, fight hostiles in radius (abandon past radius+4), retreat until health recovers or the threat is gone, eat with vanilla cooldown; stop ends it. Does not resume after server restart", "player,radius");
        add("shield", "Combat & Survival", "Raise or lower a shield held in the main hand or offhand", "enabled");
        add("shoot", "Combat & Survival", "Fire one full-charge bow or crossbow shot at an entity or coordinates (range 24, gravity/lead aim, vanilla arrow consume unless Infinity)", "target,uuid,x,y,z");
        add("eat", "Combat & Survival", "Eat held or inventory food", "item");
        add("drink", "Combat & Survival", "Drink a potion, milk, or honey bottle from inventory (same consume events as eat)", "item");
        add("heal", "Combat & Survival", "Recover by drinking a healing potion or eating food; never sets health directly", "item");
        add("retreat", "Combat & Survival", "Stop combat and goto away from the nearest hostile (or backward 8 blocks) using the same A* as flee", "");
        add("defend", "Combat & Survival", "If player is set, alias of protect; otherwise raise a held shield", "player,enabled,radius");
        add("respawn", "Combat & Survival", "Send this body to its persisted bed or charged respawn anchor, or to world spawn if none is set. Same teleport class as death respawn (safe stand, 5s no-damage). Stops goto/combat. Does not drop inventory or reset health. sleep sets the bed/anchor used here", "");
        add("set_spawn", "Combat & Survival", "Set this body's persisted respawn on a reachable bed or charged respawn anchor without entering the bed. Refuses a bed already claimed by another agent or a human player (inspect_block / observe.beds show claimedBy, with player:true for humans); your own bed is fine", "x,y,z");
        add("till", "Farming & Animals", "Till", "x,y,z");
        add("plant", "Farming & Animals", "Plant the held seed on farmland (or soul sand for nether wart). Target the farmland cell or the air above it. Optional item/material/slot selects the seed first so a held sword is not planted. Seeds: wheat_seeds, beetroot_seeds, carrot, potato, melon_seeds, pumpkin_seeds, nether_wart", "x,y,z,slot,item,material");
        add("harvest", "Farming & Animals", "Harvest a mature crop and vacuum nearby drops into inventory (same as chop/dig); result lists collected items. On a log, chops the whole tree", "x,y,z");
        add("water", "Farming & Animals", "Apply held bone meal to a crop/plant, or empty a water bucket into a cell or cauldron", "x,y,z");
        add("feed", "Farming & Animals", "Feed", "uuid");
        add("breed", "Farming & Animals", "Breed", "uuid");
        add("tame", "Farming & Animals", "Tame a nearby wolf, cat, parrot, horse, llama, or camel with the food it accepts (consumes one item)", "uuid");
        add("fish", "Farming & Animals", "Cast a held fishing rod, or reel the current hook", "");
        add("shear", "Farming & Animals", "Shear", "uuid");
        add("milk", "Farming & Animals", "Milk a nearby cow, mooshroom, or goat with an empty bucket", "uuid");
        add("say", "Villagers & Multiplayer", "Say publicly, or whisper when player/to/name is set (players and agents, case-insensitive). text is the message; message is an accepted alias. Failures always return a non-empty error on the inline result and in action.failed", "text,message,player");
        add("whisper", "Villagers & Multiplayer", "Whisper to an online player or agent by display name (case-insensitive)", "player,text");
        add("reply", "Villagers & Multiplayer", "Reply", "text");
        add("trade", "Villagers & Multiplayer", "Buy one villager or wandering-trader offer by index (consumes the listed ingredients you own)", "uuid,index,times");
        add("trade_offers", "Villagers & Multiplayer", "List a nearby villager or wandering trader's current offers", "uuid");
        add("interact_player", "Villagers & Multiplayer", "Face a nearby player or agent and wave (swing)", "player,uuid,target,name");
        add("emote", "Villagers & Multiplayer", "Play a short body emote (wave swing, or bow/sneak); name is optional", "name");
        add("command", "Villagers & Multiplayer", "Command", "");
        add("inspect_block", "Observation & Knowledge", "Inspect a nearby block. Signs include lines/text (front) and back when written. Beds and respawn anchors include claimed/claimedBy/mine for agents and human players (player:true) so a shared-base agent can see who already set spawn there", "x,y,z");
        add("inspect_entity", "Observation & Knowledge", "Inspect entity", "uuid");
        add("scan", "Observation & Knowledge", "Scan a sphere around the agent without loading chunks. material may be one name, a comma list or an array; omit it for a summary of block counts in range. recent:true adds blocks placed/broken by players and agents in the last 30 minutes (who, when, block). Every result reports radius, center, bounds (min/max x,y,z), blocksChecked, total, returned, truncated, edgeDirections and a hint such as \"move toward east or raise radius/limit\" when results are cut off or reach the scan edge", "material,radius,limit,recent");
        add("scan_entities", "Observation & Knowledge", "Find nearby entities of a type without loading chunks", "type,radius");
        add("scan_box", "Observation & Knowledge", "Bounded region scan (minX..maxX, minY..maxY, minZ..maxZ, volume cap 4096). Returns per-layer material grids (rows +z, cols +x; . air, ? unloaded, NAME solid) plus a solids list and coverage/complete/unloaded so out-of-range or unloaded cells are never mistaken for AIR. Never loads chunks. Optional fromX/fromY/fromZ + toX/toY/toZ runs the same goto A* and reports reachable plus the first blocking cell", "minX,minY,minZ,maxX,maxY,maxZ,fromX,fromY,fromZ,toX,toY,toZ");
        add("region", "Observation & Knowledge", "Alias of scan_box: bounded min/max region scan with coverage flags and optional A* connectivity", "minX,minY,minZ,maxX,maxY,maxZ,fromX,fromY,fromZ,toX,toY,toZ");
        add("build_plan", "Observation & Knowledge", "MVP preflight/verify for multi-block builds (stairs, hallways, wall lights). mode=preflight (default) inspects each planned cell and its six neighbours, names the attachment face, expected placed block/facing/half, bodyOverlap, a stand-aside cell in 5-block reach, and arrival exact|near|out (arrived_near is not exact). mode=verify re-reads the world and flags missing or wrong-facing stairs. plan is a semicolon/newline list (max 64): 'oak_stairs -16 81 -67 facing=east half=bottom'. mirror=x|z with originX/originZ remaps positions; facing is kept unless flipFacing=true. Does not dig, place, or auto-goto (those are deferred). Optional from/to runs A* connectivity", "mode,plan,x,y,z,block,item,material,facing,direction,half,mirror,originX,originZ,flipFacing,fromX,fromY,fromZ,toX,toY,toZ");
        add("preflight", "Observation & Knowledge", "Alias of build_plan", "mode,plan,x,y,z,block,item,material,facing,direction,half,mirror,originX,originZ,flipFacing,fromX,fromY,fromZ,toX,toY,toZ");
        add("heightmap", "Observation & Knowledge", "Compact top-down surface map: rows are +z, cols are +x, agent marked *. heights are relative to agent Y; surface letters are grass/dirt stone sand water lava leaves/tree snow other. Never loads chunks. originX/originZ/yBase map cells back to world coords", "radius,x,z");
        add("walkmap", "Observation & Knowledge", "Compact top-down walkability map using the same stand/step/drop/hazard rules as goto. Mushrooms, flowers, short grass, ferns, and saplings are passable (not floors). Flood starts at the nearest standable feet Y in ymin..ymax (not a head-height air cell). Marks reachable flood-fill, standable, blocked, drop/cliff, liquid, hazard; dig:true adds reachable-with-digging. Never loads chunks. Default radius 12, Y is agent ±4", "radius,ymin,ymax,dig");
        add("survey", "Observation & Knowledge", "Flood-fill the connected walkable space with the same stand/step/drop rules as goto (dig false, 1x2 body). Returns environment (enclosed_room/passage/cave/outdoors/unknown), floorY/ceilingY, wall materials, reachable doors/openings with destination (connected_room/passage/outdoors/unknown), and workstations in the same room. room.headroom is a per-column clearance grid (min/max/mode, ceilings, floors) plus warnings for uneven floors/ceilings and cells that are short of the rest of the room so an unfinished excavation is visible. Includes time (worldTime, time24, time12, timeOfDay, ticksUntilSunrise/Sunset) from the world clock, not sky light, so an indoor room still knows night. Beds in the room report claimed/claimedBy/mine for agents and human players (player:true). Signs in the room report lines/text. Stops at wooden doors and 1-wide openings so a hillside room is not the whole outdoors. Never loads chunks; unloaded or out-of-radius neighbors are unknown, not blocked. Default radius 8, max 12, cell budget 256. Each survey merges into this body's worldmap", "radius");
        add("inspect_container", "Observation & Knowledge", "Inspect a reachable container; touching (Chebyshev 1) does not need line of sight; otherwise the error names the blocking block and x,y,z. Opening a chest is not a build; spawn protection still blocks break/place/dig", "x,y,z");
        add("inspect_interface", "Observation & Knowledge", "Inspect supported open or nearby interfaces", "");
        add("time", "Observation & Knowledge", "Read the Minecraft world clock without sky visibility: worldTime (0..23999), time24 (HH:MM, hour=(worldTime/1000+6)%24), time12, timeOfDay (dawn/day/dusk/night), dayNumber, daylightCycle (false means frozen), sleepable, ticksUntilSunrise (tick 0 / 6:00 AM), ticksUntilSunset (tick 12000 / 6:00 PM; 0 after sunset so a 6:55 PM read is not ~19 min), afterSunset, and ticksUntilNextSunset after dusk. Same object is included on observe and survey so agents can plan sleep and travel indoors without converting ticks themselves", "");
        add("chat_log", "Observation & Knowledge", "Page the current-run server chat log (public feed, or this body's private whispers). after fetches newer; before pages older. Mismatched serverSessionId returns reset:true. truncated means retained history is incomplete", "after,before,limit,scope,serverSessionId");
        add("locate", "Observation & Knowledge", "Locate", "name");
        add("waypoint", "Observation & Knowledge", "Create or read a body-owned named waypoint", "name,x,y,z");
        add("worldmap", "Observation & Knowledge", "Persistent room-and-passage graph built from survey. mode=status (default) returns rooms, connections, and the room containing the body; mode=clear wipes it. from/to (room id, here, outdoors, or object type such as chest) asks for a route across known rooms. Never loads chunks", "mode,from,to");
        add("map", "Observation & Knowledge", "Background routine and real-time micro-autonomy controller (mode=status|set|clear; behavior=auto|night_home|passive|none; steps=JSON array up to 24)", "mode,steps,behavior");
        add("journal", "Observation & Knowledge", "Write or read body-owned notes", "text");
        add("feedback", "Feedback", "File a bug, request, or idea for the AgentsMC maintainer (agents should call this when something is wrong or missing). Persists to plugins/AgentsMC/feedback.json", "text,category,context");
        add("feedback_list", "Feedback", "Maintainer read of persisted feedback. Filter with status=open|done|all and since=epoch milliseconds", "status,since");
        add("feedback_resolve", "Feedback", "Maintainer mark of a feedback entry as done. id from feedback_list; optional note. status is an accepted alias from older sessions and is ignored (resolve always marks done)", "id,note,status");
        add("op", "Admin", "Operator command for console-opped agents only (/agentsmc op <agent>). command is one of give, tp, heal, feed, gamemode, time, weather, difficulty, setspawn, clearinv, kill, summon, killmobs, fill, op, deop, ban, pardon, kick, restart; args is a space-separated string, e.g. {action:op,command:give,args:\"Steve diamond 3\"}. No stop and no raw console. Non-opped agents get ok:false", "command,args");
        add("observe", "Legacy aliases", "observe includes equipment (helmet/chestplate/leggings/boots/offhand/mainHand), time (worldTime, time24, time12, timeOfDay, daylightCycle, sleepable, ticksUntilSunrise/Sunset), pose/sleeping (STANDING vs leftover bed pose), and beds (claimed beds/anchors within 16: claimedBy/mine, including human players with player:true) so a shared base does not steal respawns", "radius,includeBlocks");
        add("block", "Legacy aliases", "block", "x,y,z");
        add("pickup", "Legacy aliases", "pickup", "");
        add("dig", "Legacy aliases", "Alias of break: mine a reachable block even when it would drop nothing. Failures always return a non-empty named error (missing/wrong-type field, reach, spawn protection). Same Chebyshev spawn-protection square as break", "x,y,z");
    }
    private static void add(String name, String category, String description, String fields) {
        Map<String,Object> entry = new LinkedHashMap<>();
        entry.put("action",name); entry.put("category",category); entry.put("description",description);
        entry.put("fields", fields.isEmpty() ? List.of() : java.util.Arrays.stream(fields.split(",")).map(field -> field + " [" + contract(name,field) + "]").toList());
        entry.put("available", ENABLED.contains(name));
        entry.put("readOnly", READ_ONLY.contains(name));
        CATALOG.add(entry);
    }
    private static String contract(String action,String field) {
        if(field.equals("minX")||field.equals("maxX")||field.equals("minY")||field.equals("maxY")||field.equals("minZ")||field.equals("maxZ"))
            return "integer block coordinate, same world; box volume (dx+1)*(dy+1)*(dz+1) at most 4096";
        if(field.equals("fromX")||field.equals("fromY")||field.equals("fromZ")||field.equals("toX")||field.equals("toY")||field.equals("toZ"))
            return "optional path endpoint; when all six from/to are set, A* reports reachable and the first blocking cell";
        if(field.equals("x")||field.equals("y")||field.equals("z")) {
            if(action.equals("heightmap"))return "optional integer center; default agent feet";
            if(action.equals("portal")||action.equals("use_portal"))return "optional portal block; default nearest nether/end portal within radius";
            return action.equals("goto")||action.equals("look")?"number, finite, same world, within world bounds":action.equals("shoot")?"optional integer aim coordinate, same world, within 24 blocks":"integer block coordinate, same world, reachable within 5 blocks where applicable";
        }
        if(field.equals("ymin")||field.equals("ymax"))return "optional integer feet Y; default agent Y ±4, span at most 16";
        if((action.equals("portal")||action.equals("use_portal"))&&field.equals("radius"))return "optional integer 1..16, default 8";
        if((action.equals("portal")||action.equals("use_portal"))&&field.equals("timeout"))return "optional integer seconds 5..60, default 20";
        if((action.equals("portal")||action.equals("use_portal"))&&field.equals("vanillaOnly"))return "optional boolean, default false; true disables the plugin fallback teleport";
        if(field.equals("radius"))return action.equals("protect")||action.equals("heightmap")?"optional integer 1..32, default 16":action.equals("walkmap")?"optional integer 1..24, default 12":action.equals("survey")?"optional integer 1..12, default 8":action.equals("scan")?"optional integer 1..32, default 16":action.equals("scan_entities")?"integer 1..32":action.equals("observe")?"integer 1..8":"integer 1..4";
        if(field.equals("slot")||field.equals("from")||field.equals("to")||field.equals("first")||field.equals("second")) {
            if(action.equals("worldmap")&&(field.equals("from")||field.equals("to")))return "optional room id, here, outdoors, or object type (chest, door)";
            if(action.equals("place")||action.equals("plant"))return "optional integer 0..8";
            if(action.equals("select"))return "integer 0..8";
            if(action.equals("brew")&&field.equals("slot"))return "optional brewing slot 0..4 (bottles 0-2, ingredient 3, blaze powder 4); default 3";
            if((action.equals("give")||action.equals("throw"))&&field.equals("slot"))return "optional inventory slot 0..40; default held";
            if(action.equals("equip")&&field.equals("to"))return "optional helmet/chest/legs/feet/offhand/hand or 36..40";
            if(action.equals("equip"))return "integer 0..40 (0-8 hotbar, 9-35 storage, 36 boots, 37 leggings, 38 chest, 39 helmet, 40 offhand)";
            return "integer 0..40 (0-35 storage, 36-39 armor, 40 offhand)";
        }
        if(field.equals("amount"))return action.equals("transfer")?"integer 1..2304":action.equals("craft")?"integer 1..64, output count, rounded up to recipe yield":action.equals("give")||action.equals("throw")?"optional integer 1..64, default 1":"integer 1..64";
        if(field.equals("count"))return action.equals("craft")?"optional alias of amount (output count 1..64)":"scalar; validated by the action-specific contract";
        if(field.equals("recent"))return "optional boolean; include blocks placed/broken nearby in the last 30 minutes (alone, without material, returns only those)";
        if(field.equals("enabled"))return "boolean";
        if(field.equals("dig"))return "optional boolean, default false";
        if(field.equals("includeBlocks"))return "optional boolean";
        if(field.equals("uuid"))return action.equals("shoot")||action.equals("follow")||action.equals("give")||action.equals("interact_player")?"optional UUID string for a nearby entity":"UUID string for a nearby entity";
        if(field.equals("target"))return action.equals("mount")?"optional: nearest, boat, minecart, horse, or entity UUID; default nearest":action.equals("shoot")?"optional entity UUID (alias of uuid)":action.equals("follow")||action.equals("give")||action.equals("interact_player")?"optional player or agent display name or UUID":"scalar; validated by the action-specific contract";
        if(field.equals("type"))return "Minecraft EntityType name, case-insensitive";
        if(field.equals("text")) {
            if(action.equals("place"))return "optional string, up to 4 lines separated by \\n; on an existing unwaxed sign this edits it";
            if(action.equals("reply"))return "optional string, 1..256 characters";
            if(action.equals("feedback"))return "string, 1..2000 characters; the report body";
            return "string, 1..256 characters";
        }
        if(field.equals("side"))return action.equals("place")?"optional front or back; default front when editing a sign":"scalar; validated by the action-specific contract";
        if(field.equals("category"))return "optional bug, request, or idea";
        if(field.equals("context"))return "optional string, 1..2000 characters";
        if(field.equals("status"))return action.equals("feedback_resolve")?"optional done marker (older-session alias); resolve always marks done":"optional open, done, or all; default open";
        if(field.equals("since"))return "optional epoch milliseconds; only entries at or after this time";
        if(field.equals("after"))return "optional exclusive sequence; messages newer than this";
        if(field.equals("before"))return "optional exclusive sequence; older page ending before this";
        if(field.equals("limit"))return action.equals("scan")?"optional integer 1..100 matches returned, default 20":action.equals("chat_log")?"optional integer 1..100, default 50":"scalar; validated by the action-specific contract";
        if(field.equals("scope"))return "optional public or private; default public";
        if(field.equals("serverSessionId"))return "optional current-run id; mismatch returns reset:true";
        if(field.equals("id"))return action.equals("feedback_resolve")?"feedback id from feedback_list, e.g. fb-1":"scalar; validated by the action-specific contract";
        if(field.equals("note"))return "optional string, 1..1000 characters";
        if(field.equals("message"))return "string, 1..256 characters";
        if(field.equals("material")||field.equals("item")||field.equals("block")) {
            if(action.equals("eat")||action.equals("drink")||action.equals("heal")||action.equals("give")||action.equals("plant")||action.equals("build_plan")||action.equals("preflight"))return "optional Minecraft material name or namespaced key";
            if(action.equals("scan"))return "optional block name, comma list or array of up to 16; omit for a block-count summary";
            if(action.equals("stonecut")&&field.equals("material"))return "optional input block; default any owned matching recipe";
            return "Minecraft material name or namespaced key";
        }
        if(field.equals("facing"))return "optional north, south, east, west, up, down, or forward/back/left/right from the body's look";
        if(field.equals("direction"))return action.equals("place")?"optional alias of facing":action.equals("move")?"north, south, east, west, forward, back, left, or right":"deposit or withdraw";
        if(field.equals("blocks"))return "integer 1..16";
        if(field.equals("plan"))return "optional semicolon/newline list of 'material x y z [facing=..] [half=..]', max 64 cells";
        if(field.equals("half"))return "optional bottom or top for stairs/slabs";
        if(field.equals("mirror"))return "optional x or z; remaps plan positions across originX/originZ and keeps facing unless flipFacing";
        if(field.equals("originX")||field.equals("originZ"))return "optional integer mirror origin; default 0";
        if(field.equals("flipFacing"))return "optional boolean, default false; true also mirrors east/west or north/south facing";
        if(field.equals("index"))return "integer 0..15, offer index from trade_offers";
        if(field.equals("times"))return "optional integer 1..16, default 1";
        if(field.equals("levels"))return "optional integer 1..30 experience levels to spend, default 1";
        if(field.equals("template")||field.equals("base")||field.equals("addition"))return "optional Minecraft material you own for that smithing slot";
        if(field.equals("player"))return action.equals("protect")?"online player or agent display name, case-insensitive":action.equals("defend")?"optional player or agent to guard; if omitted, raise a held shield":action.equals("say")||action.equals("whisper")||action.equals("follow")||action.equals("give")||action.equals("interact_player")?"optional online player or agent display name, case-insensitive":"scalar; validated by the action-specific contract";
        if(field.equals("name"))return action.equals("locate")?"optional string, 1..32 characters":action.equals("follow")||action.equals("give")||action.equals("interact_player")?"optional player or agent display name, case-insensitive":action.equals("emote")?"optional wave, bow, or sneak":"string, 1..32 characters";
        if(field.equals("mode"))return action.equals("worldmap")?"optional status or clear; default status":action.equals("build_plan")||action.equals("preflight")?"optional preflight, verify, or stand; default preflight":"status, set, or clear";
        if(field.equals("steps"))return "JSON array string, max 24";
        if(field.equals("behavior"))return "night_home or none";
        if(field.equals("seconds"))return "integer 1..30";
        return "scalar; validated by the action-specific contract";
    }
    static boolean readOnly(String name) { return READ_ONLY.contains(name); }
    static boolean executable(String name) { return ENABLED.contains(name); }
    static boolean advertised(String name) { return ENABLED.contains(name); }
    static java.util.List<String> advertisedNames() {
        java.util.List<String> names = new ArrayList<>();
        for (Map<String,Object> entry : CATALOG)
            if (Boolean.TRUE.equals(entry.get("available"))) names.add((String) entry.get("action"));
        return names;
    }
    static Map<String,Object> snapshot() {
        return Map.of("version",VERSION,"actions",CATALOG);
    }
    private AgentActionCatalog() {}
}
