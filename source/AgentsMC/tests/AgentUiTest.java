package dev.agentsmc;

import java.util.List;
import java.util.Map;

import com.google.gson.JsonObject;

/** Offline MCP App panel template, size, debounce, and catalog contract. */
public final class AgentUiTest {
    public static void main(String[] args) {
        AgentUi.resetForTest();
        String html = AgentUi.html();
        check(html.length() > 1000, "bundled html");
        check(AgentUi.looksSafe(html), "complete MCP App html");
        check(!html.contains("Bearer ") && !html.contains("server-token") && !html.contains("accessToken"),
                "no credentials in html");
        check(html.contains("agentsmc_panel") && html.contains("agentsmc_execute"), "host tool names");
        check(html.toLowerCase().contains("<!doctype html"), "doctype");

        Map<String, Object> data = AgentUi.panelData("Builder Bot", "abc", "0.6.31", true,
                18, 16, "world", "NORMAL", 23.69, 78, 7.53, "navigating", "", "goto",
                List.of(Map.of("material", "DIRT", "amount", 32)),
                List.of(Map.of("type", "goto.arrived", "summary", "arrived")),
                List.of(Map.of("name", "Player1", "d", 4.2, "x", 21.0, "y", 78.0, "z", 9.0)));
        check("1 stack, 32 items".equals(data.get("inventoryLabel")), "inventory label: " + data.get("inventoryLabel"));
        JsonObject body = AgentUi.requestOf(data);
        check(AgentUi.withinLimit(body), "publish body " + AgentUi.utf8Bytes(body) + " > " + AgentUi.MAX_BYTES);
        check(body.getAsJsonObject("template").get("title").getAsString().equals(AgentUi.TITLE), "stable title");
        check(body.getAsJsonObject("template").get("html").getAsString().equals(html), "constant html");
        check(body.getAsJsonObject("template").getAsJsonObject("csp").getAsJsonArray("connectDomains").size() == 0,
                "empty csp");

        String fp1 = AgentUi.fingerprint(data);
        Map<String, Object> again = new java.util.LinkedHashMap<>(data);
        again.put("updatedAt", 99);
        check(fp1.equals(AgentUi.fingerprint(again)), "fingerprint ignores updatedAt");
        again.put("health", 10);
        check(!fp1.equals(AgentUi.fingerprint(again)), "fingerprint sees health");

        check(AgentUi.due("a1", fp1, 1000), "first publish");
        AgentUi.markPublished("a1", fp1, 1000);
        check(!AgentUi.due("a1", fp1, 2000), "same data is not republished");
        String fp2 = AgentUi.fingerprint(again);
        check(!AgentUi.due("a1", fp2, 1000 + AgentUi.DEBOUNCE_MS - 1), "debounce holds");
        check(AgentUi.due("a1", fp2, 1000 + AgentUi.DEBOUNCE_MS), "debounce elapsed");

        check(AgentActionCatalog.advertised("feedback"), "feedback still advertised");
        String schema = AgentUi.schema().toString();
        check(schema.contains("\"health\"") && schema.contains("\"food\""), "schema");
        PluginChangelog.resetForTest();
        PluginChangelog.Notes notes = PluginChangelog.notes("0.6.31");
        check(notes != null && notes.actionsAdded.contains("feedback"), "changelog 0.6.31: " + notes);
        PluginChangelog.Notes tunnel = PluginChangelog.notes("0.6.32");
        check(tunnel != null && tunnel.actionsChanged.contains("goto"), "changelog 0.6.32: " + tunnel);
        PluginChangelog.Notes chat = PluginChangelog.notes("0.6.33");
        check(chat != null && chat.actionsAdded.contains("chat_log"), "changelog 0.6.33: " + chat);
        PluginChangelog.Notes maps = PluginChangelog.notes("0.6.34");
        check(maps != null && maps.actionsAdded.contains("heightmap") && maps.actionsAdded.contains("walkmap"),
                "changelog 0.6.34: " + maps);
        PluginChangelog.Notes sky = PluginChangelog.notes("0.6.35");
        check(sky != null && sky.actionsChanged.contains("goto")
                && sky.changes.stream().anyMatch(s -> s.contains("climbed_past_path") || s.contains("recovered_from_air"))
                && sky.changes.stream().anyMatch(s -> s.toLowerCase().contains("capture bounds") || s.contains("Invalid capture bounds")),
                "changelog 0.6.35: " + sky);
        PluginChangelog.Notes stall = PluginChangelog.notes("0.6.36");
        check(stall != null && stall.actionsChanged.contains("goto")
                && stall.actionsChanged.contains("heightmap") && stall.actionsChanged.contains("walkmap")
                && stall.changes.stream().anyMatch(s -> s.toLowerCase().contains("stall") || s.contains("blacklist"))
                && stall.changes.stream().anyMatch(s -> s.contains("heightmap") || s.contains("documented")),
                "changelog 0.6.36: " + stall);
        PluginChangelog.Notes stair = PluginChangelog.notes("0.6.37");
        check(stair != null && stair.actionsChanged.contains("goto")
                && stair.changes.stream().anyMatch(s -> s.toLowerCase().contains("1x2") || s.toLowerCase().contains("stair"))
                && stair.changes.stream().anyMatch(s -> s.contains("arrived_near") || s.contains("block below"))
                && stair.changes.stream().anyMatch(s -> s.toLowerCase().contains("fall")),
                "changelog 0.6.37: " + stair);
        PluginChangelog.Notes air = PluginChangelog.notes("0.6.38");
        check(air != null && air.actionsChanged.contains("goto")
                && air.changes.stream().anyMatch(s -> s.contains("recovered_from_air") || s.contains("open air column") || s.contains("open air"))
                && air.changes.stream().anyMatch(s -> s.toLowerCase().contains("combat") || s.toLowerCase().contains("knockback")),
                "changelog 0.6.38: " + air);
        PluginChangelog.Notes hill = PluginChangelog.notes("0.6.39");
        check(hill != null && hill.actionsChanged.contains("goto")
                && hill.changes.stream().anyMatch(s -> s.contains("1 block") || s.contains("~1") || s.toLowerCase().contains("feet"))
                && hill.changes.stream().anyMatch(s -> s.toLowerCase().contains("scan") || s.toLowerCase().contains("watchdog")),
                "changelog 0.6.39: " + hill);
        PluginChangelog.Notes wear = PluginChangelog.notes("0.6.40");
        check(wear != null && wear.actionsChanged.contains("equip") && wear.actionsChanged.contains("walkmap")
                && wear.actionsChanged.contains("say")
                && wear.changes.stream().anyMatch(s -> s.toLowerCase().contains("armor") || s.toLowerCase().contains("offhand"))
                && wear.changes.stream().anyMatch(s -> s.toLowerCase().contains("walkmap") || s.contains("all-#"))
                && wear.changes.stream().anyMatch(s -> s.toLowerCase().contains("say") || s.toLowerCase().contains("error")),
                "changelog 0.6.40: " + wear);
        PluginChangelog.Notes rt = PluginChangelog.notes("0.6.41");
        check(rt != null && rt.actionsChanged.contains("goto") && rt.actionsChanged.contains("map")
                && rt.changes.stream().anyMatch(s -> s.contains("250ms") || s.toLowerCase().contains("fast-lane"))
                && rt.changes.stream().anyMatch(s -> s.toLowerCase().contains("concurrent") || s.toLowerCase().contains("background"))
                && rt.changes.stream().anyMatch(s -> s.toLowerCase().contains("autonomy") || s.toLowerCase().contains("buoyancy")),
                "changelog 0.6.41: " + rt);
        PluginChangelog.Notes trap = PluginChangelog.notes("0.6.42");
        check(trap != null && trap.actionsChanged.contains("break") && trap.actionsChanged.contains("goto")
                && trap.actionsChanged.contains("stop")
                && trap.changes.stream().anyMatch(s -> s.toLowerCase().contains("self-defense") || s.toLowerCase().contains("unreachable"))
                && trap.changes.stream().anyMatch(s -> s.toLowerCase().contains("leaves") || s.toLowerCase().contains("no-drop") || s.toLowerCase().contains("drop")),
                "changelog 0.6.42: " + trap);
        PluginChangelog.Notes crater = PluginChangelog.notes("0.6.43");
        check(crater != null && crater.actionsChanged.contains("observe")
                && crater.changes.stream().anyMatch(s -> s.toLowerCase().contains("16") || s.toLowerCase().contains("vertical") || s.toLowerCase().contains("crater")),
                "changelog 0.6.43: " + crater);
        PluginChangelog.Notes ledge = PluginChangelog.notes("0.6.44");
        check(ledge != null && ledge.actionsChanged.contains("goto")
                && ledge.changes.stream().anyMatch(s -> s.toLowerCase().contains("downward") || s.toLowerCase().contains("ledge") || s.toLowerCase().contains("descent")),
                "changelog 0.6.44: " + ledge);
        PluginChangelog.Notes nav = PluginChangelog.notes("0.6.45");
        check(nav != null && nav.actionsChanged.contains("goto") && nav.actionsChanged.contains("dig")
                && nav.actionsChanged.contains("sleep")
                && nav.changes.stream().anyMatch(s -> s.toLowerCase().contains("moving") || s.toLowerCase().contains("net"))
                && nav.changes.stream().anyMatch(s -> s.toLowerCase().contains("climbed") || s.toLowerCase().contains("pillar") || s.toLowerCase().contains("balloon"))
                && nav.changes.stream().anyMatch(s -> s.toLowerCase().contains("error") || s.toLowerCase().contains("field"))
                && nav.changes.stream().anyMatch(s -> s.toLowerCase().contains("bed") || s.toLowerCase().contains("world spawn") || s.toLowerCase().contains("death")),
                "changelog 0.6.45: " + nav);
        PluginChangelog.Notes plants = PluginChangelog.notes("0.6.46");
        check(plants != null && plants.actionsChanged.contains("goto") && plants.actionsChanged.contains("craft")
                && plants.actionsChanged.contains("smelt") && plants.actionsChanged.contains("inventory")
                && plants.changes.stream().anyMatch(s -> s.toLowerCase().contains("mushroom") || s.toLowerCase().contains("plant"))
                && plants.changes.stream().anyMatch(s -> s.toLowerCase().contains("smelt") || s.toLowerCase().contains("world"))
                && plants.changes.stream().anyMatch(s -> s.toLowerCase().contains("armor") || s.toLowerCase().contains("offhand"))
                && plants.changes.stream().anyMatch(s -> s.toLowerCase().contains("ranged") || s.toLowerCase().contains("cover") || s.toLowerCase().contains("unarmed")),
                "changelog 0.6.46: " + plants);
        PluginChangelog.Notes jump = PluginChangelog.notes("0.6.47");
        check(jump != null && jump.actionsChanged.contains("goto")
                && jump.changes.stream().anyMatch(s -> s.toLowerCase().contains("2-block") || s.toLowerCase().contains("two-block") || s.toLowerCase().contains("jump")),
                "changelog 0.6.47: " + jump);
        PluginChangelog.Notes around = PluginChangelog.notes("0.6.48");
        check(around != null && around.actionsChanged.contains("goto") && around.actionsChanged.contains("fight")
                && around.changes.stream().anyMatch(s -> s.toLowerCase().contains("around") || s.toLowerCase().contains("rim"))
                && around.changes.stream().anyMatch(s -> s.toLowerCase().contains("1.8") || s.toLowerCase().contains("1-block") || s.toLowerCase().contains("head"))
                && around.changes.stream().anyMatch(s -> s.toLowerCase().contains("flee") || s.toLowerCase().contains("fb-20")),
                "changelog 0.6.48: " + around);
        PluginChangelog.Notes corner = PluginChangelog.notes("0.6.49");
        check(corner != null && corner.actionsChanged.contains("goto")
                && corner.changes.stream().anyMatch(s -> s.toLowerCase().contains("auto-jump") || s.toLowerCase().contains("horizontal") || s.toLowerCase().contains("corner"))
                && corner.changes.stream().anyMatch(s -> s.toLowerCase().contains("cardinal") || s.toLowerCase().contains("diagonal")),
                "changelog 0.6.49: " + corner);
        PluginChangelog.Notes protect = PluginChangelog.notes("0.6.50");
        check(protect != null && protect.actionsChanged.contains("protect") && protect.actionsChanged.contains("goto")
                && protect.actionsChanged.contains("craft") && protect.actionsChanged.contains("equip")
                && protect.changes.stream().anyMatch(s -> s.toLowerCase().contains("fb-21") || s.toLowerCase().contains("helper"))
                && protect.changes.stream().anyMatch(s -> s.toLowerCase().contains("stall") || s.toLowerCase().contains("2 block"))
                && protect.changes.stream().anyMatch(s -> s.toLowerCase().contains("ingredient") || s.toLowerCase().contains("hand")),
                "changelog 0.6.50: " + protect);
        PluginChangelog.Notes resolve = PluginChangelog.notes("0.6.51");
        check(resolve != null && resolve.actionsChanged.contains("feedback_resolve")
                && resolve.changes.stream().anyMatch(s -> s.toLowerCase().contains("fb-22") || s.toLowerCase().contains("status")),
                "changelog 0.6.51: " + resolve);
        PluginChangelog.Notes doors = PluginChangelog.notes("0.6.52");
        check(doors != null && doors.actionsChanged.contains("goto")
                && doors.changes.stream().anyMatch(s -> s.toLowerCase().contains("fb-23") || s.toLowerCase().contains("door")),
                "changelog 0.6.52: " + doors);
        PluginChangelog.Notes stoop = PluginChangelog.notes("0.6.53");
        check(stoop != null && stoop.actionsChanged.contains("goto") && stoop.actionsChanged.contains("craft")
                && stoop.changes.stream().anyMatch(s -> s.toLowerCase().contains("fb-24") || s.toLowerCase().contains("stoop"))
                && stoop.changes.stream().anyMatch(s -> s.toLowerCase().contains("fb-25") || s.toLowerCase().contains("wooden")),
                "changelog 0.6.53: " + stoop);
        PluginChangelog.Notes ore = PluginChangelog.notes("0.6.54");
        check(ore != null && ore.actionsChanged.contains("break") && ore.actionsChanged.contains("goto")
                && ore.changes.stream().anyMatch(s -> s.toLowerCase().contains("fb-28") || s.toLowerCase().contains("iron"))
                && ore.changes.stream().anyMatch(s -> s.toLowerCase().contains("fb-27") || s.toLowerCase().contains("exit"))
                && ore.changes.stream().anyMatch(s -> s.toLowerCase().contains("fb-26") || s.toLowerCase().contains("chest")),
                "changelog 0.6.54: " + ore);
        PluginChangelog.Notes rim = PluginChangelog.notes("0.6.55");
        check(rim != null && rim.actionsChanged.contains("goto")
                && rim.changes.stream().anyMatch(s -> s.toLowerCase().contains("rim") || s.toLowerCase().contains("pocket") || s.toLowerCase().contains("2-block"))
                && rim.changes.stream().anyMatch(s -> s.toLowerCase().contains("walk") || s.toLowerCase().contains("1-step")),
                "changelog 0.6.55: " + rim);
        PluginChangelog.Notes restore = PluginChangelog.notes("0.6.56");
        check(restore != null
                && restore.changes.stream().anyMatch(s -> s.toLowerCase().contains("bridge") || s.toLowerCase().contains("token"))
                && restore.changes.stream().anyMatch(s -> s.toLowerCase().contains("spawn")),
                "changelog 0.6.56: " + restore);
        PluginChangelog.Notes canopy = PluginChangelog.notes("0.6.57");
        check(canopy != null && canopy.actionsChanged.contains("goto")
                && canopy.changes.stream().anyMatch(s -> s.toLowerCase().contains("jump") || s.toLowerCase().contains("canopy") || s.toLowerCase().contains("leaves"))
                && canopy.changes.stream().anyMatch(s -> s.toLowerCase().contains("1x2") || s.toLowerCase().contains("stair")),
                "changelog 0.6.57: " + canopy);
        PluginChangelog.Notes noDig = PluginChangelog.notes("0.6.58");
        check(noDig != null && noDig.actionsChanged.contains("goto")
                && noDig.changes.stream().anyMatch(s -> s.toLowerCase().contains("dig") || s.toLowerCase().contains("mine"))
                && noDig.changes.stream().anyMatch(s -> s.toLowerCase().contains("default") || s.toLowerCase().contains("house") || s.toLowerCase().contains("false")),
                "changelog 0.6.58: " + noDig);
        PluginChangelog.Notes home = PluginChangelog.notes("0.6.59");
        check(home != null && home.actionsAdded.contains("respawn")
                && home.changes.stream().anyMatch(s -> s.toLowerCase().contains("respawn") && (s.toLowerCase().contains("bed") || s.toLowerCase().contains("spawn")))
                && home.changes.stream().anyMatch(s -> s.toLowerCase().contains("dig") || s.toLowerCase().contains("0.6.58")),
                "changelog 0.6.59: " + home);
        PluginChangelog.Notes fleeGoto = PluginChangelog.notes("0.6.60");
        check(fleeGoto != null && fleeGoto.actionsChanged.contains("goto") && fleeGoto.actionsChanged.contains("fight")
                && fleeGoto.changes.stream().anyMatch(s -> s.toLowerCase().contains("flee") && s.toLowerCase().contains("goto"))
                && fleeGoto.changes.stream().anyMatch(s -> s.toLowerCase().contains("return") || s.toLowerCase().contains("home") || s.toLowerCase().contains("stand")),
                "changelog 0.6.60: " + fleeGoto);
        PluginChangelog.Notes fast = PluginChangelog.notes("0.6.61");
        check(fast != null
                && fast.changes.stream().anyMatch(s -> s.toLowerCase().contains("100") || s.toLowerCase().contains("poll"))
                && fast.changes.stream().anyMatch(s -> s.toLowerCase().contains("heartbeat") || s.toLowerCase().contains("telemetry") || s.toLowerCase().contains("offline")),
                "changelog 0.6.61: " + fast);
        PluginChangelog.Notes followHold = PluginChangelog.notes("0.6.62");
        check(followHold != null && followHold.actionsChanged.contains("follow") && followHold.actionsChanged.contains("protect")
                && followHold.changes.stream().anyMatch(s -> s.toLowerCase().contains("goto") && (s.toLowerCase().contains("overlook") || s.toLowerCase().contains("no_path"))),
                "changelog 0.6.62: " + followHold);
        PluginChangelog.Notes finish = PluginChangelog.notes("0.6.63");
        check(finish != null && finish.actionsAdded.contains("give") && finish.actionsAdded.contains("trade")
                && finish.changes.stream().anyMatch(s -> s.toLowerCase().contains("give") && s.toLowerCase().contains("command")),
                "changelog 0.6.63: " + finish);
        PluginChangelog.Notes rooms = PluginChangelog.notes("0.6.64");
        check(rooms != null && rooms.actionsAdded.contains("survey") && rooms.actionsAdded.contains("worldmap")
                && rooms.changes.stream().anyMatch(s -> s.toLowerCase().contains("survey") && s.toLowerCase().contains("room")),
                "changelog 0.6.64: " + rooms);
        PluginChangelog.Notes headroom = PluginChangelog.notes("0.6.65");
        check(headroom != null && headroom.actionsChanged.contains("survey")
                && headroom.changes.stream().anyMatch(s -> s.toLowerCase().contains("headroom") || s.toLowerCase().contains("ceiling")),
                "changelog 0.6.65: " + headroom);
        PluginChangelog.Notes clock = PluginChangelog.notes("0.6.66");
        check(clock != null && clock.actionsAdded.contains("time")
                && clock.actionsChanged.contains("observe") && clock.actionsChanged.contains("survey")
                && clock.changes.stream().anyMatch(s -> s.toLowerCase().contains("time") && s.toLowerCase().contains("dawn")),
                "changelog 0.6.66: " + clock);
        PluginChangelog.Notes readable = PluginChangelog.notes("0.6.67");
        check(readable != null && readable.actionsChanged.contains("time")
                && readable.actionsChanged.contains("observe") && readable.actionsChanged.contains("survey")
                && readable.changes.stream().anyMatch(s -> s.toLowerCase().contains("time24") || s.toLowerCase().contains("1:35")),
                "changelog 0.6.67: " + readable);
        PluginChangelog.Notes claims = PluginChangelog.notes("0.6.68");
        check(claims != null && claims.actionsChanged.contains("sleep")
                && claims.actionsChanged.contains("inspect_block")
                && claims.actionsChanged.contains("place")
                && claims.changes.stream().anyMatch(s -> s.toLowerCase().contains("claimed"))
                && claims.changes.stream().anyMatch(s -> s.toLowerCase().contains("facing")),
                "changelog 0.6.68: " + claims);
        PluginChangelog.Notes signs = PluginChangelog.notes("0.6.69");
        check(signs != null && signs.actionsChanged.contains("inspect_block")
                && signs.actionsChanged.contains("plant")
                && signs.changes.stream().anyMatch(s -> s.toLowerCase().contains("sign"))
                && signs.changes.stream().anyMatch(s -> s.toLowerCase().contains("farmland") || s.toLowerCase().contains("plant")),
                "changelog 0.6.69: " + signs);
        PluginChangelog.Notes wallSign = PluginChangelog.notes("0.6.70");
        check(wallSign != null && wallSign.actionsChanged.contains("place")
                && wallSign.changes.stream().anyMatch(s -> s.toLowerCase().contains("wall") && s.toLowerCase().contains("sign")),
                "changelog 0.6.70: " + wallSign);
        PluginChangelog.Notes editSign = PluginChangelog.notes("0.6.71");
        check(editSign != null && editSign.actionsChanged.contains("place")
                && editSign.changes.stream().anyMatch(s -> s.toLowerCase().contains("edit") && s.toLowerCase().contains("sign")),
                "changelog 0.6.71: " + editSign);
        PluginChangelog.Notes playerBeds = PluginChangelog.notes("0.6.72");
        check(playerBeds != null && playerBeds.actionsChanged.contains("inspect_block")
                && playerBeds.actionsChanged.contains("observe")
                && playerBeds.changes.stream().anyMatch(s -> s.toLowerCase().contains("human") || s.toLowerCase().contains("player")),
                "changelog 0.6.72: " + playerBeds);
        PluginChangelog.Notes closeDoors = PluginChangelog.notes("0.6.73");
        check(closeDoors != null && closeDoors.actionsChanged.contains("goto")
                && closeDoors.changes.stream().anyMatch(s -> s.toLowerCase().contains("close") && s.toLowerCase().contains("door")),
                "changelog 0.6.73: " + closeDoors);
        PluginChangelog.Notes feedback = PluginChangelog.notes("0.6.74");
        check(feedback != null && feedback.actionsChanged.contains("harvest")
                && feedback.actionsChanged.contains("craft")
                && feedback.actionsChanged.contains("time")
                && feedback.changes.stream().anyMatch(s -> s.toLowerCase().contains("harvest") || s.toLowerCase().contains("wheat") || s.toLowerCase().contains("vacuum")),
                "changelog 0.6.74: " + feedback);
        PluginChangelog.Notes farmlandScan = PluginChangelog.notes("0.6.75");
        check(farmlandScan != null && farmlandScan.actionsChanged.contains("goto")
                && farmlandScan.actionsAdded.contains("scan_box")
                && farmlandScan.actionsAdded.contains("region")
                && farmlandScan.changes.stream().anyMatch(s -> s.toLowerCase().contains("farmland") || s.toLowerCase().contains("soul sand"))
                && farmlandScan.changes.stream().anyMatch(s -> s.toLowerCase().contains("scan_box") || s.toLowerCase().contains("4096")),
                "changelog 0.6.75: " + farmlandScan);
        PluginChangelog.Notes build = PluginChangelog.notes("0.6.76");
        check(build != null && build.actionsChanged.contains("place")
                && build.actionsChanged.contains("sleep")
                && build.actionsChanged.contains("observe")
                && build.actionsAdded.contains("build_plan")
                && build.changes.stream().anyMatch(s -> s.toLowerCase().contains("torch") || s.toLowerCase().contains("attach"))
                && build.changes.stream().anyMatch(s -> s.toLowerCase().contains("pose") || s.toLowerCase().contains("sleep")),
                "changelog 0.6.76: " + build);
        PluginChangelog.Notes fb = PluginChangelog.notes("0.6.77");
        check(fb != null && fb.actionsChanged.contains("survey")
                && fb.changes.stream().anyMatch(s -> s.contains("readOnly"))
                && fb.changes.stream().anyMatch(s -> s.toLowerCase().contains("eat")),
                "changelog 0.6.77: " + fb);
        PluginChangelog.Notes bedFix = PluginChangelog.notes("0.6.78");
        check(bedFix != null && bedFix.actionsChanged.contains("sleep")
                && bedFix.changes.stream().anyMatch(s -> s.contains("HEAD") && s.contains("FACING")),
                "changelog 0.6.78: " + bedFix);
        PluginChangelog.Notes wake = PluginChangelog.notes("0.6.80");
        check(wake != null && wake.actionsChanged.contains("inspect_container")
                && wake.changes.stream().anyMatch(s -> s.contains("dawn")),
                "changelog 0.6.80: " + wake);
        PluginChangelog.Notes pin = PluginChangelog.notes("0.7.1");
        check(pin != null && pin.changes.stream().anyMatch(s -> s.contains("PIN")), "changelog 0.7.1: " + pin);
        PluginChangelog.Notes ops = PluginChangelog.notes("0.7.2");
        check(ops != null && ops.actionsAdded.contains("op") && ops.changes.stream().anyMatch(s -> s.contains("404")), "changelog 0.7.2: " + ops);
        PluginChangelog.Notes pub = PluginChangelog.notes("0.7.0");
        check(pub != null && pub.changes.stream().anyMatch(s -> s.contains("/agentsmc link")),
                "changelog 0.7.0: " + pub);

        System.out.println("PASS: panel html bundled, under 512KiB, debounce, no credentials, 0.7.3");
    }

    static void check(boolean ok, String message) {
        if (!ok) throw new AssertionError(message);
    }
}
