package dev.agentsmc;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Random;

import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.block.BrewingStand;
import org.bukkit.block.data.Levelled;
import org.bukkit.entity.AbstractVillager;
import org.bukkit.entity.Egg;
import org.bukkit.entity.EnderPearl;
import org.bukkit.entity.Entity;
import org.bukkit.entity.FishHook;
import org.bukkit.entity.Item;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.entity.Snowball;
import org.bukkit.entity.Tameable;
import org.bukkit.entity.ThrownExpBottle;
import org.bukkit.entity.ThrownPotion;
import org.bukkit.entity.Trident;
import org.bukkit.event.block.Action;
import org.bukkit.event.player.PlayerInteractEntityEvent;
import org.bukkit.event.player.PlayerItemConsumeEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.MerchantRecipe;
import org.bukkit.inventory.Recipe;
import org.bukkit.inventory.SmithingRecipe;
import org.bukkit.inventory.StonecuttingRecipe;
import org.bukkit.inventory.meta.Damageable;
import org.bukkit.inventory.meta.PotionMeta;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;
import org.bukkit.util.Vector;

import com.google.gson.JsonObject;

import net.citizensnpcs.api.npc.NPC;

/**
 * Remaining catalog verbs that were planned stubs: give, throw, trade, stations,
 * animals extras, movement extras. Never implements {@code command}.
 */
final class AgentFinishActions {
    static final double GIVE_RANGE = 5;
    static final int MOVE_MAX = 16;

    static Map<String, Object> execute(AgentsMC plugin, NPC npc, Player p, JsonObject o) {
        String action = AgentSurvivalActions.str(o, "action");
        return switch (action) {
            case "give" -> give(npc, p, o);
            case "throw" -> throwHeld(npc, p, o);
            case "interact" -> interact(npc, p, o);
            case "interact_player" -> interactPlayer(npc, p, o);
            case "shield" -> shield(p, o);
            case "drink" -> drink(p, o);
            case "heal" -> heal(p, o);
            case "milk" -> milk(npc, p, o);
            case "tame" -> tame(npc, p, o);
            case "set_spawn" -> setSpawn(plugin, npc, p, o);
            case "water" -> water(npc, p, o);
            case "fill" -> fill(npc, p, o);
            case "fish" -> fish(npc, p, o);
            case "trade_offers" -> tradeOffers(p, o);
            case "trade" -> trade(npc, p, o);
            case "emote" -> emote(npc, p, o);
            case "retreat" -> retreat(npc, p, o);
            case "defend" -> defend(plugin, npc, p, o);
            case "brew" -> brew(p, o);
            case "enchant" -> enchant(p, o);
            case "anvil" -> anvil(p, o);
            case "smith" -> smith(p, o);
            case "stonecut" -> stonecut(p, o);
            case "swim" -> swim(p, o);
            case "climb" -> climb(p);
            case "portal", "use_portal" -> AgentPortal.start(npc, p, o);
            case "move" -> move(npc, p, o);
            default -> null;
        };
    }

    static String giveNeed() {
        return "give requires player, target, name, or uuid (a nearby player or agent) plus slot or material";
    }

    static boolean drinkable(String material) {
        if (material == null || material.isBlank()) return false;
        String m = material.toUpperCase(Locale.ROOT);
        return m.contains("POTION") || m.equals("MILK_BUCKET") || m.equals("HONEY_BOTTLE")
                || m.equals("OMINOUS_BOTTLE") || m.equals("SUSPICIOUS_STEW");
    }

    static String throwKind(String material) {
        if (material == null) return "toss";
        return switch (material.toUpperCase(Locale.ROOT)) {
            case "SNOWBALL" -> "snowball";
            case "EGG" -> "egg";
            case "ENDER_PEARL" -> "ender_pearl";
            case "SPLASH_POTION", "LINGERING_POTION" -> "potion";
            case "EXPERIENCE_BOTTLE" -> "xp";
            case "TRIDENT" -> "trident";
            case "WIND_CHARGE" -> "wind_charge";
            default -> "toss";
        };
    }

    static boolean milkable(String type) {
        if (type == null) return false;
        String t = type.toUpperCase(Locale.ROOT);
        return t.equals("COW") || t.equals("MOOSHROOM") || t.equals("MUSHROOM_COW") || t.equals("GOAT");
    }

    static boolean tameFood(String animal, String item) {
        if (animal == null || item == null) return false;
        String a = animal.toUpperCase(Locale.ROOT), i = item.toUpperCase(Locale.ROOT);
        if (a.contains("WOLF")) return i.equals("BONE");
        if (a.contains("CAT") || a.contains("OCELOT"))
            return i.contains("COD") || i.contains("SALMON") || i.equals("TROPICAL_FISH");
        if (a.contains("PARROT")) return i.endsWith("_SEEDS");
        if (a.contains("HORSE") || a.contains("DONKEY") || a.contains("MULE") || a.contains("ZOMBIE_HORSE"))
            return i.equals("GOLDEN_APPLE") || i.equals("ENCHANTED_GOLDEN_APPLE") || i.equals("GOLDEN_CARROT");
        if (a.contains("LLAMA") || a.contains("TRADER_LLAMA")) return i.equals("HAY_BLOCK");
        if (a.contains("CAMEL")) return i.equals("CACTUS");
        return false;
    }

    static boolean climbable(String block) {
        if (block == null) return false;
        String b = block.toUpperCase(Locale.ROOT);
        return b.contains("LADDER") || b.contains("VINE") || b.contains("SCAFFOLDING")
                || b.contains("WEEPING") || b.contains("TWISTING") || b.contains("CAVE_VINES");
    }

    static boolean portalBlock(String material) {
        if (material == null) return false;
        String m = material.toUpperCase(Locale.ROOT);
        return (m.contains("PORTAL") && !m.contains("FRAME") && !m.contains("ANCHOR"));
    }

    static String fillMode(String hand, String block) {
        if (hand == null) hand = "";
        if (block == null) block = "";
        String h = hand.toUpperCase(Locale.ROOT), b = block.toUpperCase(Locale.ROOT);
        if (h.equals("BUCKET") && (b.equals("WATER") || b.equals("LAVA") || b.equals("POWDER_SNOW")))
            return "fill_" + b.toLowerCase(Locale.ROOT);
        if (h.equals("GLASS_BOTTLE") && b.equals("WATER")) return "bottle_water";
        if ((h.equals("WATER_BUCKET") || h.equals("LAVA_BUCKET") || h.equals("POWDER_SNOW_BUCKET"))
                && (b.isBlank() || b.equals("AIR") || b.equals("CAVE_AIR") || b.equals("VOID_AIR") || b.equals("CAULDRON")))
            return "empty_" + h.replace("_BUCKET", "").toLowerCase(Locale.ROOT);
        return "";
    }

    static boolean moveDirection(String direction) {
        if (direction == null) return false;
        return switch (direction.toLowerCase(Locale.ROOT)) {
            case "north", "south", "east", "west", "forward", "back", "left", "right" -> true;
            default -> false;
        };
    }

    /** Block delta for a grounded walk. Never up/down. */
    static int[] moveDelta(String direction, int blocks, float yaw) {
        if (!moveDirection(direction) || blocks < 1 || blocks > MOVE_MAX)
            throw new IllegalArgumentException("move requires direction north/south/east/west/forward/back/left/right and blocks 1.." + MOVE_MAX);
        String d = direction.toLowerCase(Locale.ROOT);
        int fx = (int) Math.round(-Math.sin(Math.toRadians(yaw)));
        int fz = (int) Math.round(Math.cos(Math.toRadians(yaw)));
        if (fx == 0 && fz == 0) fz = 1;
        return switch (d) {
            case "north" -> new int[]{0, 0, -blocks};
            case "south" -> new int[]{0, 0, blocks};
            case "east" -> new int[]{blocks, 0, 0};
            case "west" -> new int[]{-blocks, 0, 0};
            case "forward" -> new int[]{fx * blocks, 0, fz * blocks};
            case "back" -> new int[]{-fx * blocks, 0, -fz * blocks};
            case "left" -> new int[]{fz * blocks, 0, -fx * blocks};
            case "right" -> new int[]{-fz * blocks, 0, fx * blocks};
            default -> throw new IllegalArgumentException("Unknown move direction: " + direction);
        };
    }

    /** Step away from a threat on the XZ plane. */
    static int[] retreatDelta(int hx, int hz, int tx, int tz, int span) {
        int dx = hx - tx, dz = hz - tz;
        if (dx == 0 && dz == 0) dx = 1;
        double len = Math.hypot(dx, dz);
        return new int[]{hx + (int) Math.round(dx / len * span), hz + (int) Math.round(dz / len * span)};
    }

    static boolean healSetsHealth() {
        return false;
    }

    private static Map<String, Object> give(NPC npc, Player p, JsonObject o) {
        AgentSurvivalActions.fields(o, List.of("uuid", "target", "player", "to", "name", "slot", "amount", "material", "item"),
                "action", "agent");
        Entity raw = namedOrUuid(p, o, GIVE_RANGE, "give");
        AgentSurvivalActions.check(raw instanceof Player dest && dest != p, "give target must be a nearby player or agent body");
        Player dest = (Player) raw;
        int amount = o.has("amount") ? AgentSurvivalActions.num(o, "amount", 1, 64) : 1;
        int slot;
        ItemStack src;
        if (o.has("slot")) {
            slot = AgentSurvivalActions.num(o, "slot", 0, AgentEquipment.LAST);
            src = AgentSurvivalActions.slot(p, slot);
        } else {
            Material wanted = null;
            if (o.has("material") || o.has("item"))
                wanted = AgentSurvivalActions.material(AgentSurvivalActions.str(o, o.has("material") ? "material" : "item"));
            slot = findOwned(p, wanted);
            AgentSurvivalActions.check(slot >= 0, wanted == null ? "Nothing in hand to give" : "No " + wanted.name() + " in inventory");
            src = AgentSurvivalActions.slot(p, slot);
        }
        AgentSurvivalActions.check(src != null && !src.getType().isAir() && src.getAmount() >= amount, "Not enough items to give");
        ItemStack gift = src.clone();
        gift.setAmount(amount);
        Map<Integer, ItemStack> overflow = dest.getInventory().addItem(gift);
        int given = amount;
        if (!overflow.isEmpty()) {
            int left = 0;
            for (ItemStack extra : overflow.values()) {
                left += extra.getAmount();
                Item drop = dest.getWorld().dropItem(dest.getLocation().add(0, 0.2, 0), extra);
                drop.setPickupDelay(10);
            }
            given = amount - left;
            AgentSurvivalActions.check(given > 0, "Recipient inventory is full");
        }
        ItemStack remain = src.clone();
        remain.setAmount(src.getAmount() - amount);
        AgentSurvivalActions.put(p, slot, remain.getAmount() > 0 ? remain : null);
        AgentFocus.note(npc, "give", dest);
        return AgentSurvivalActions.out("given", AgentSurvivalActions.item(gift), "amount", given, "to", dest.getName(),
                "kind", AgentTargets.kindOf(dest), "droppedOverflow", given < amount);
    }

    private static Map<String, Object> throwHeld(NPC npc, Player p, JsonObject o) {
        AgentSurvivalActions.fields(o, List.of("slot", "amount"), "action", "agent");
        int slot = o.has("slot") ? AgentSurvivalActions.num(o, "slot", 0, AgentEquipment.LAST) : p.getInventory().getHeldItemSlot();
        int amount = o.has("amount") ? AgentSurvivalActions.num(o, "amount", 1, 64) : 1;
        ItemStack src = AgentSurvivalActions.slot(p, slot);
        AgentSurvivalActions.check(src != null && !src.getType().isAir() && src.getAmount() >= amount, "Nothing to throw");
        if (slot <= 8) p.getInventory().setHeldItemSlot(slot);
        String kind = throwKind(src.getType().name());
        ItemStack one = src.clone();
        one.setAmount(1);
        boolean launched = false;
        try {
            launched = switch (kind) {
                case "snowball" -> {
                    p.launchProjectile(Snowball.class);
                    yield true;
                }
                case "egg" -> {
                    p.launchProjectile(Egg.class);
                    yield true;
                }
                case "ender_pearl" -> {
                    p.launchProjectile(EnderPearl.class);
                    yield true;
                }
                case "potion" -> {
                    ThrownPotion pot = p.launchProjectile(ThrownPotion.class);
                    pot.setItem(one);
                    yield true;
                }
                case "xp" -> {
                    p.launchProjectile(ThrownExpBottle.class);
                    yield true;
                }
                case "trident" -> {
                    p.launchProjectile(Trident.class);
                    yield true;
                }
                default -> false;
            };
        } catch (RuntimeException ignored) {
            launched = false;
        }
        if (!launched) {
            Item tossed = p.getWorld().dropItem(p.getEyeLocation(), one);
            Vector look = p.getLocation().getDirection();
            if (look.lengthSquared() < 1e-4) look = new Vector(0, 0, 1);
            tossed.setVelocity(look.normalize().multiply(0.6).add(new Vector(0, 0.15, 0)));
            tossed.setPickupDelay(20);
            kind = "toss";
        }
        ItemStack remain = src.clone();
        remain.setAmount(src.getAmount() - 1);
        AgentSurvivalActions.put(p, slot, remain.getAmount() > 0 ? remain : null);
        AgentHunger.exhaust(p, AgentHunger.ATTACK);
        AgentFocus.note(npc, "throw", p.getEyeLocation());
        return AgentSurvivalActions.out("thrown", src.getType().name(), "kind", kind, "amount", 1);
    }

    private static Map<String, Object> interact(NPC npc, Player p, JsonObject o) {
        AgentSurvivalActions.fields(o, "action", "agent", "uuid");
        Entity e = AgentSurvivalActions.entity(p, o, 4.5);
        PlayerInteractEntityEvent ev = new PlayerInteractEntityEvent(p, e, EquipmentSlot.HAND);
        Bukkit.getPluginManager().callEvent(ev);
        AgentSurvivalActions.check(!ev.isCancelled(), "Interact was rejected");
        try { p.swingMainHand(); } catch (RuntimeException ignored) {}
        npc.faceLocation(e.getLocation());
        if (e instanceof Tameable tame && tame.isTamed() && e instanceof org.bukkit.entity.Sittable sit) {
            try {
                var owner = tame.getOwner();
                if (owner != null && p.getUniqueId().equals(owner.getUniqueId()))
                    sit.setSitting(!sit.isSitting());
            } catch (RuntimeException ignored) {}
        }
        AgentFocus.note(npc, "interact", e);
        return AgentSurvivalActions.out("interacted", e.getUniqueId().toString(), "type", e.getType().name(),
                "kind", AgentTargets.kindOf(e));
    }

    private static Map<String, Object> interactPlayer(NPC npc, Player p, JsonObject o) {
        AgentSurvivalActions.fields(o, List.of("uuid", "target", "player", "to", "name"), "action", "agent");
        Entity e = namedOrUuid(p, o, 8, "interact_player");
        AgentSurvivalActions.check(e instanceof Player && e != p, "interact_player needs a nearby player or agent");
        npc.faceLocation(e.getLocation());
        try { p.swingMainHand(); } catch (RuntimeException ignored) {}
        AgentFocus.note(npc, "interact_player", e);
        return AgentSurvivalActions.out("waved", e.getName(), "kind", AgentTargets.kindOf(e));
    }

    private static Map<String, Object> shield(Player p, JsonObject o) {
        AgentSurvivalActions.fields(o, "action", "agent", "enabled");
        AgentSurvivalActions.check(o.get("enabled").isJsonPrimitive() && o.getAsJsonPrimitive("enabled").isBoolean(),
                "enabled must be boolean");
        boolean on = o.get("enabled").getAsBoolean();
        EquipmentSlot hand = shieldHand(p);
        AgentSurvivalActions.check(hand != null, "Hold a shield in the main hand or offhand");
        if (on) {
            try { p.startUsingItem(hand); } catch (RuntimeException e) { p.setSneaking(true); }
        } else {
            try { p.clearActiveItem(); } catch (RuntimeException ignored) {}
            p.setSneaking(false);
        }
        return AgentSurvivalActions.out("enabled", on, "hand", hand.name());
    }

    private static Map<String, Object> drink(Player p, JsonObject o) {
        AgentSurvivalActions.fields(o, List.of("item"), "action", "agent");
        Material wanted = o.has("item") ? AgentSurvivalActions.material(AgentSurvivalActions.str(o, "item")) : null;
        if (wanted != null)
            AgentSurvivalActions.check(drinkable(wanted.name()), "Not a drink: " + wanted.name());
        int slot = drinkSlot(p, wanted);
        AgentSurvivalActions.check(slot >= 0, wanted == null ? "No drink in inventory" : "No " + wanted.name() + " in inventory");
        return consume(p, slot, "drunk");
    }

    private static Map<String, Object> heal(Player p, JsonObject o) {
        AgentSurvivalActions.fields(o, List.of("item"), "action", "agent");
        AgentSurvivalActions.check(!healSetsHealth(), "heal does not set health");
        if (p.getHealth() >= 19.5 && p.getFoodLevel() >= 20)
            throw new IllegalArgumentException("Already at full health and food");
        if (o.has("item")) {
            Material wanted = AgentSurvivalActions.material(AgentSurvivalActions.str(o, "item"));
            if (drinkable(wanted.name())) return consume(p, drinkSlot(p, wanted), "drunk");
        }
        int potion = drinkSlot(p, null);
        if (potion >= 0 && isHealingDrink(AgentSurvivalActions.slot(p, potion)))
            return consume(p, potion, "drunk");
        AgentSurvivalActions.check(AgentSurvivalActions.tryEat(p), "No food or healing drink in inventory");
        return AgentSurvivalActions.out("healed", false, "ate", true, "food", p.getFoodLevel(),
                "health", p.getHealth(), "note", "heal eats or drinks; it does not set health");
    }

    private static Map<String, Object> milk(NPC npc, Player p, JsonObject o) {
        AgentSurvivalActions.fields(o, "action", "agent", "uuid");
        Entity e = AgentSurvivalActions.entity(p, o, 4.5);
        AgentSurvivalActions.check(milkable(e.getType().name()), e.getType().name() + " cannot be milked");
        int slot = findOwned(p, Material.BUCKET);
        AgentSurvivalActions.check(slot >= 0, "Empty bucket required");
        PlayerInteractEntityEvent ev = new PlayerInteractEntityEvent(p, e, EquipmentSlot.HAND);
        Bukkit.getPluginManager().callEvent(ev);
        AgentSurvivalActions.check(!ev.isCancelled(), "Milking was rejected");
        ItemStack bucket = AgentSurvivalActions.slot(p, slot);
        ItemStack next = bucket.clone();
        next.setAmount(bucket.getAmount() - 1);
        AgentSurvivalActions.put(p, slot, next.getAmount() > 0 ? next : null);
        Map<Integer, ItemStack> extra = p.getInventory().addItem(new ItemStack(Material.MILK_BUCKET));
        for (ItemStack lost : extra.values()) p.getWorld().dropItem(p.getLocation(), lost);
        AgentFocus.note(npc, "milk", e);
        return AgentSurvivalActions.out("milked", e.getUniqueId().toString(), "type", e.getType().name());
    }

    private static Map<String, Object> tame(NPC npc, Player p, JsonObject o) {
        AgentSurvivalActions.fields(o, "action", "agent", "uuid");
        Entity e = AgentSurvivalActions.entity(p, o, 4.5);
        AgentSurvivalActions.check(e instanceof Tameable tame, e.getType().name() + " cannot be tamed");
        Tameable tame = (Tameable) e;
        AgentSurvivalActions.check(!tame.isTamed(), e.getType().name() + " is already tamed");
        int slot = -1;
        String used = "";
        for (int i = 0; i < 36; i++) {
            ItemStack s = p.getInventory().getItem(i);
            if (s == null || s.getType().isAir()) continue;
            if (tameFood(e.getType().name(), s.getType().name())) {
                slot = i;
                used = s.getType().name();
                break;
            }
        }
        AgentSurvivalActions.check(slot >= 0, "No food this " + e.getType().name() + " accepts for taming");
        PlayerInteractEntityEvent ev = new PlayerInteractEntityEvent(p, e, EquipmentSlot.HAND);
        Bukkit.getPluginManager().callEvent(ev);
        AgentSurvivalActions.check(!ev.isCancelled(), "Tame was rejected");
        ItemStack food = p.getInventory().getItem(slot);
        food.setAmount(food.getAmount() - 1);
        p.getInventory().setItem(slot, food.getAmount() > 0 ? food : null);
        tame.setTamed(true);
        try { tame.setOwner(p); } catch (RuntimeException ignored) {}
        AgentFocus.note(npc, "tame", e);
        return AgentSurvivalActions.out("tamed", e.getUniqueId().toString(), "type", e.getType().name(), "food", used);
    }

    private static Map<String, Object> setSpawn(AgentsMC plugin, NPC npc, Player p, JsonObject o) {
        AgentSurvivalActions.fields(o, "action", "agent", "world", "x", "y", "z");
        Block b = AgentSurvivalActions.target(p, o, true);
        AgentSurvivalActions.check(AgentRespawn.isBed(b.getType().name()) || AgentRespawn.isAnchor(b.getType().name()),
                "Target is not a bed or respawn anchor");
        AgentRespawn.requireUnclaimedOrMine(npc, b);
        boolean set = AgentRespawn.claim(npc, p, b);
        AgentSurvivalActions.check(set, "Cannot set respawn at this bed or anchor");
        return AgentSurvivalActions.out("respawnSet", true, "sleeping", false, "respawnPoint", AgentRespawn.view(npc, p));
    }

    private static Map<String, Object> water(NPC npc, Player p, JsonObject o) {
        AgentSurvivalActions.fields(o, "action", "agent", "world", "x", "y", "z");
        Block b = AgentSurvivalActions.target(p, o, true);
        ItemStack hand = p.getInventory().getItemInMainHand();
        AgentSurvivalActions.check(hand != null && !hand.getType().isAir(), "Hold bone meal or a water bucket");
        String name = hand.getType().name();
        if (name.equals("BONE_MEAL")) {
            AgentSurvivalActions.check(AgentSurvivalActions.permitted(p, b, Action.RIGHT_CLICK_BLOCK), "Bone meal rejected");
            boolean grew;
            try { grew = b.applyBoneMeal(p.getFacing()); }
            catch (RuntimeException e) { grew = false; }
            AgentSurvivalActions.check(grew, "Bone meal did nothing on " + b.getType().name());
            consumeOneHand(p);
            AgentFocus.note(npc, "water", b);
            return AgentSurvivalActions.out("bonemeal", true, "block", b.getType().name());
        }
        if (name.equals("WATER_BUCKET")) {
            if (b.getType() == Material.CAULDRON) {
                b.setType(Material.WATER_CAULDRON, true);
                if (b.getBlockData() instanceof Levelled lvl) {
                    lvl.setLevel(lvl.getMaximumLevel());
                    b.setBlockData(lvl, true);
                }
            } else {
                AgentSurvivalActions.check(b.isEmpty() || b.isLiquid(), "Water needs an empty cell or cauldron");
                AgentProtection.requireEdit(p, b);
                b.setType(Material.WATER, true);
            }
            p.getInventory().setItemInMainHand(new ItemStack(Material.BUCKET));
            AgentFocus.note(npc, "water", b);
            return AgentSurvivalActions.out("watered", true, "block", b.getType().name());
        }
        throw new IllegalArgumentException("Hold bone meal or a water bucket");
    }

    private static Map<String, Object> fill(NPC npc, Player p, JsonObject o) {
        AgentSurvivalActions.fields(o, "action", "agent", "world", "x", "y", "z");
        Block b = AgentSurvivalActions.target(p, o, true);
        ItemStack hand = p.getInventory().getItemInMainHand();
        AgentSurvivalActions.check(hand != null && !hand.getType().isAir(), "Hold a bucket or glass bottle");
        String mode = fillMode(hand.getType().name(), b.getType().name());
        AgentSurvivalActions.check(!mode.isEmpty(), "Cannot fill or empty " + hand.getType().name() + " on " + b.getType().name());
        AgentSurvivalActions.check(AgentSurvivalActions.permitted(p, b, Action.RIGHT_CLICK_BLOCK), "Fill rejected");
        if (mode.startsWith("fill_")) {
            Material filled = switch (mode) {
                case "fill_lava" -> Material.LAVA_BUCKET;
                case "fill_powder_snow" -> Material.POWDER_SNOW_BUCKET;
                default -> Material.WATER_BUCKET;
            };
            if (b.getBlockData() instanceof org.bukkit.block.data.Levelled src && src.getLevel() != 0)
                throw new IllegalArgumentException("Not a source block");
            AgentProtection.requireEdit(p, b);
            b.setType(Material.AIR, true);
            p.getInventory().setItemInMainHand(new ItemStack(filled));
        } else if (mode.equals("bottle_water")) {
            consumeOneHand(p);
            Map<Integer, ItemStack> extra = p.getInventory().addItem(new ItemStack(Material.POTION));
            for (ItemStack lost : extra.values()) p.getWorld().dropItem(p.getLocation(), lost);
        } else {
            Material fluid = switch (hand.getType()) {
                case LAVA_BUCKET -> Material.LAVA;
                case POWDER_SNOW_BUCKET -> Material.POWDER_SNOW;
                default -> Material.WATER;
            };
            if (b.getType() == Material.CAULDRON && fluid == Material.WATER) {
                b.setType(Material.WATER_CAULDRON, true);
            } else {
                AgentSurvivalActions.check(b.isEmpty() || b.getType().isAir(), "Empty the bucket into air or a cauldron");
                AgentProtection.requireEdit(p, b);
                b.setType(fluid, true);
            }
            p.getInventory().setItemInMainHand(new ItemStack(Material.BUCKET));
        }
        AgentFocus.note(npc, "fill", b);
        return AgentSurvivalActions.out("mode", mode, "block", b.getType().name(), "hand", p.getInventory().getItemInMainHand().getType().name());
    }

    private static Map<String, Object> fish(NPC npc, Player p, JsonObject o) {
        AgentSurvivalActions.fields(o, "action", "agent");
        FishHook hook = null;
        try { hook = p.getFishHook(); } catch (RuntimeException ignored) {}
        if (hook != null && hook.isValid()) {
            try { hook.remove(); } catch (RuntimeException ignored) {}
            AgentFocus.note(npc, "fish", p.getLocation());
            return AgentSurvivalActions.out("reeled", true);
        }
        ItemStack hand = p.getInventory().getItemInMainHand();
        AgentSurvivalActions.check(hand != null && hand.getType() == Material.FISHING_ROD, "Hold a fishing rod");
        try { p.launchProjectile(FishHook.class); }
        catch (RuntimeException e) { throw new IllegalArgumentException("Could not cast the fishing rod"); }
        AgentHunger.exhaust(p, AgentHunger.ATTACK);
        return AgentSurvivalActions.out("cast", true);
    }

    private static Map<String, Object> tradeOffers(Player p, JsonObject o) {
        AgentSurvivalActions.fields(o, "action", "agent", "uuid");
        Entity e = AgentSurvivalActions.entity(p, o, 6);
        AgentSurvivalActions.check(e instanceof AbstractVillager, "Target is not a villager or wandering trader");
        AbstractVillager villager = (AbstractVillager) e;
        List<Object> offers = new ArrayList<>();
        int i = 0;
        for (MerchantRecipe recipe : villager.getRecipes()) {
            List<Object> ins = new ArrayList<>();
            for (ItemStack in : recipe.getIngredients())
                if (in != null && !in.getType().isAir()) ins.add(AgentSurvivalActions.item(in));
            offers.add(AgentSurvivalActions.out("index", i, "uses", recipe.getUses(), "maxUses", recipe.getMaxUses(),
                    "ingredients", ins, "result", AgentSurvivalActions.item(recipe.getResult())));
            i++;
            if (i >= 16) break;
        }
        return AgentSurvivalActions.out("merchant", e.getUniqueId().toString(), "type", e.getType().name(), "offers", offers);
    }

    private static Map<String, Object> trade(NPC npc, Player p, JsonObject o) {
        AgentSurvivalActions.fields(o, List.of("times"), "action", "agent", "uuid", "index");
        Entity e = AgentSurvivalActions.entity(p, o, 4.5);
        AgentSurvivalActions.check(e instanceof AbstractVillager, "Target is not a villager or wandering trader");
        AbstractVillager villager = (AbstractVillager) e;
        int index = AgentSurvivalActions.num(o, "index", 0, 15);
        int times = o.has("times") ? AgentSurvivalActions.num(o, "times", 1, 16) : 1;
        List<MerchantRecipe> recipes = new ArrayList<>(villager.getRecipes());
        AgentSurvivalActions.check(index < recipes.size(), "No trade at index " + index);
        MerchantRecipe recipe = recipes.get(index);
        AgentSurvivalActions.check(recipe.getUses() + times <= recipe.getMaxUses(), "Trade is used up");
        for (int n = 0; n < times; n++) {
            for (ItemStack need : recipe.getIngredients()) {
                if (need == null || need.getType().isAir()) continue;
                AgentSurvivalActions.check(ownedSimilar(p, need) >= need.getAmount(),
                        "Need " + need.getType().name() + " x" + need.getAmount());
            }
            for (ItemStack need : recipe.getIngredients()) {
                if (need == null || need.getType().isAir()) continue;
                takeSimilar(p, need, need.getAmount());
            }
            Map<Integer, ItemStack> extra = p.getInventory().addItem(recipe.getResult().clone());
            for (ItemStack lost : extra.values()) p.getWorld().dropItem(p.getLocation(), lost);
            recipe.setUses(recipe.getUses() + 1);
        }
        villager.setRecipe(index, recipe);
        AgentFocus.note(npc, "trade", e);
        return AgentSurvivalActions.out("traded", index, "times", times, "result", AgentSurvivalActions.item(recipe.getResult()));
    }

    private static Map<String, Object> emote(NPC npc, Player p, JsonObject o) {
        AgentSurvivalActions.fields(o, List.of("name", "text"), "action", "agent");
        String name = o.has("name") ? AgentSurvivalActions.str(o, "name") : o.has("text") ? AgentSurvivalActions.str(o, "text") : "wave";
        try { p.swingMainHand(); } catch (RuntimeException ignored) {}
        if (name.equalsIgnoreCase("bow") || name.equalsIgnoreCase("sneak")) p.setSneaking(true);
        AgentFocus.note(npc, "emote", p.getLocation());
        return AgentSurvivalActions.out("emote", name.toLowerCase(Locale.ROOT));
    }

    private static Map<String, Object> retreat(NPC npc, Player p, JsonObject o) {
        AgentSurvivalActions.fields(o, "action", "agent");
        LivingEntity threat = nearestHostile(p, 16);
        Location here = p.getLocation();
        int[] dest;
        if (threat != null) {
            Location t = threat.getLocation();
            dest = AgentSelfDefense.coverOrAway(here.getBlockX(), here.getBlockY(), here.getBlockZ(),
                    t.getBlockX(), t.getBlockY(), t.getBlockZ(), 8, 12, null);
        } else {
            int[] d = retreatDelta(here.getBlockX(), here.getBlockZ(),
                    here.getBlockX() - (int) Math.round(-Math.sin(Math.toRadians(here.getYaw()))),
                    here.getBlockZ() - (int) Math.round(Math.cos(Math.toRadians(here.getYaw()))), 8);
            dest = new int[]{d[0], here.getBlockY(), d[1]};
        }
        Location to = new Location(here.getWorld(), dest[0] + 0.5, dest[1], dest[2] + 0.5);
        AgentBehaviors.cancel(npc);
        AgentCombat.stop(npc);
        AgentProtect.stop(npc);
        AgentSelfDefense.release(npc);
        p.setSprinting(true);
        Map<String, Object> r;
        try { r = AgentNavigation.start(npc, to, false); }
        catch (RuntimeException e) { throw new IllegalArgumentException(e.getMessage() == null ? "no_path" : e.getMessage()); }
        r.put("retreating", true);
        r.put("threat", threat == null ? "" : threat.getType().name());
        return r;
    }

    private static Map<String, Object> defend(AgentsMC plugin, NPC npc, Player p, JsonObject o) {
        AgentSurvivalActions.fields(o, List.of("player", "radius", "enabled"), "action", "agent");
        if (o.has("player")) {
            JsonObject protect = aliasProtect(o);
            protect.remove("enabled");
            return AgentSurvivalActions.execute(plugin, npc, p, protect);
        }
        JsonObject shield = o.deepCopy();
        shield.addProperty("action", "shield");
        if (!shield.has("enabled")) shield.addProperty("enabled", true);
        return shield(p, shield);
    }

    private static Map<String, Object> brew(Player p, JsonObject o) {
        AgentSurvivalActions.fields(o, List.of("slot"), "action", "agent", "world", "x", "y", "z", "material", "amount");
        Block b = AgentSurvivalActions.target(p, o, true);
        AgentSurvivalActions.check(b.getState() instanceof BrewingStand, "Target must be a brewing stand");
        BrewingStand stand = (BrewingStand) b.getState();
        AgentSurvivalActions.check(AgentSurvivalActions.permitted(p, b, Action.RIGHT_CLICK_BLOCK), "Brewing stand use rejected");
        int slot = o.has("slot") ? AgentSurvivalActions.num(o, "slot", 0, 4) : 3;
        Material m = AgentSurvivalActions.material(AgentSurvivalActions.str(o, "material"));
        int amount = AgentSurvivalActions.num(o, "amount", 1, 64);
        Inventory inv = stand.getInventory();
        ItemStack existing = inv.getItem(slot);
        ItemStack desired = new ItemStack(m, amount);
        AgentSurvivalActions.check(existing == null || existing.getType().isAir() || existing.isSimilar(desired), "Brewing slot occupied");
        int have = ownedSimilar(p, desired);
        AgentSurvivalActions.check(have >= amount, "Not enough " + m.name());
        takeSimilar(p, desired, amount);
        if (existing == null || existing.getType().isAir()) inv.setItem(slot, desired);
        else {
            ItemStack stack = existing.clone();
            stack.setAmount(existing.getAmount() + amount);
            inv.setItem(slot, stack);
        }
        return AgentSurvivalActions.out("loaded", m.name(), "amount", amount, "slot", slot,
                "note", "Vanilla brewing is asynchronous; bottles in 0-2, ingredient 3, blaze powder 4");
    }

    private static Map<String, Object> enchant(Player p, JsonObject o) {
        AgentSurvivalActions.fields(o, List.of("levels"), "action", "agent", "world", "x", "y", "z");
        Block b = AgentSurvivalActions.target(p, o, true);
        AgentSurvivalActions.check(b.getType() == Material.ENCHANTING_TABLE, "Target must be an enchanting table");
        ItemStack hand = p.getInventory().getItemInMainHand();
        AgentSurvivalActions.check(hand != null && !hand.getType().isAir(), "Hold the item to enchant");
        int levels = o.has("levels") ? AgentSurvivalActions.num(o, "levels", 1, 30) : 1;
        int lapis = Math.min(3, Math.max(1, (levels + 9) / 10));
        AgentSurvivalActions.check(p.getLevel() >= levels, "Need " + levels + " experience levels");
        AgentSurvivalActions.check(ownedNamed(p, Material.LAPIS_LAZULI) >= lapis, "Need " + lapis + " LAPIS_LAZULI");
        ItemStack enchanted = enchantHeld(hand, levels);
        AgentSurvivalActions.check(enchanted != null && !enchanted.getEnchantments().isEmpty(),
                "Could not enchant " + hand.getType().name());
        takeNamed(p, Material.LAPIS_LAZULI, lapis);
        p.setLevel(p.getLevel() - levels);
        p.getInventory().setItemInMainHand(enchanted);
        return AgentSurvivalActions.out("enchanted", AgentSurvivalActions.item(enchanted), "levels", levels, "lapis", lapis);
    }

    private static Map<String, Object> anvil(Player p, JsonObject o) {
        AgentSurvivalActions.fields(o, "action", "agent", "world", "x", "y", "z", "first", "second");
        Block b = AgentSurvivalActions.target(p, o, true);
        AgentSurvivalActions.check(b.getType().name().contains("ANVIL"), "Target must be an anvil");
        int first = AgentSurvivalActions.num(o, "first", 0, AgentEquipment.LAST);
        int second = AgentSurvivalActions.num(o, "second", 0, AgentEquipment.LAST);
        AgentSurvivalActions.check(first != second, "Anvil slots must differ");
        ItemStack a = AgentSurvivalActions.slot(p, first), extra = AgentSurvivalActions.slot(p, second);
        AgentSurvivalActions.check(a != null && !a.getType().isAir() && extra != null && !extra.getType().isAir(),
                "Both anvil slots need items");
        AgentSurvivalActions.check(p.getLevel() >= 1, "Anvil needs 1 experience level");
        ItemStack out = anvilMerge(a, extra);
        AgentSurvivalActions.check(out != null, "Those items cannot be combined");
        AgentSurvivalActions.put(p, first, out);
        AgentSurvivalActions.put(p, second, null);
        p.setLevel(p.getLevel() - 1);
        return AgentSurvivalActions.out("repaired", AgentSurvivalActions.item(out), "cost", 1);
    }

    private static Map<String, Object> smith(Player p, JsonObject o) {
        AgentSurvivalActions.fields(o, List.of("template", "base", "addition", "first", "second", "slot"),
                "action", "agent", "world", "x", "y", "z");
        Block b = AgentSurvivalActions.target(p, o, true);
        AgentSurvivalActions.check(b.getType() == Material.SMITHING_TABLE, "Target must be a smithing table");
        ItemStack template = takeNamedStack(p, o, "template");
        ItemStack base = takeNamedStack(p, o, "base");
        ItemStack addition = takeNamedStack(p, o, "addition");
        AgentSurvivalActions.check(base != null && addition != null, "smith needs base and addition materials you own");
        ItemStack result = null;
        for (Recipe recipe : iterableRecipes()) {
            if (!(recipe instanceof SmithingRecipe smithing)) continue;
            try {
                if (template != null && !choiceMatches(smithing, "template", template)) continue;
                if (!choiceMatches(smithing, "base", base)) continue;
                if (!choiceMatches(smithing, "addition", addition)) continue;
                result = smithing.getResult().clone();
                break;
            } catch (RuntimeException ignored) {}
        }
        AgentSurvivalActions.check(result != null, "No smithing recipe for those items");
        if (template != null) takeSimilar(p, template, 1);
        takeSimilar(p, base, 1);
        takeSimilar(p, addition, 1);
        Map<Integer, ItemStack> extra = p.getInventory().addItem(result);
        for (ItemStack lost : extra.values()) p.getWorld().dropItem(p.getLocation(), lost);
        return AgentSurvivalActions.out("smithed", AgentSurvivalActions.item(result));
    }

    private static Map<String, Object> stonecut(Player p, JsonObject o) {
        AgentSurvivalActions.fields(o, List.of("material"), "action", "agent", "world", "x", "y", "z", "item");
        Block b = AgentSurvivalActions.target(p, o, true);
        AgentSurvivalActions.check(b.getType() == Material.STONECUTTER, "Target must be a stonecutter");
        Material output = AgentSurvivalActions.material(AgentSurvivalActions.str(o, "item"));
        Material input = o.has("material") ? AgentSurvivalActions.material(AgentSurvivalActions.str(o, "material")) : null;
        StonecuttingRecipe match = null;
        ItemStack takeFrom = null;
        for (Recipe recipe : iterableRecipes()) {
            if (!(recipe instanceof StonecuttingRecipe cut)) continue;
            if (cut.getResult().getType() != output) continue;
            ItemStack in;
            try { in = cut.getInput(); }
            catch (RuntimeException e) { in = input == null ? new ItemStack(output) : new ItemStack(input); }
            if (input != null) {
                boolean ok = false;
                try { ok = cut.getInputChoice().test(new ItemStack(input)); } catch (RuntimeException ignored) {}
                if (!ok && in.getType() != input) continue;
            }
            if (ownedSimilar(p, in) < 1) continue;
            match = cut;
            takeFrom = in;
            break;
        }
        AgentSurvivalActions.check(match != null && takeFrom != null, "No stonecutter recipe for " + output.name() + " from owned blocks");
        takeSimilar(p, takeFrom, 1);
        Map<Integer, ItemStack> extra = p.getInventory().addItem(match.getResult().clone());
        for (ItemStack lost : extra.values()) p.getWorld().dropItem(p.getLocation(), lost);
        return AgentSurvivalActions.out("cut", AgentSurvivalActions.item(match.getResult()), "from", takeFrom.getType().name());
    }

    private static Map<String, Object> swim(Player p, JsonObject o) {
        AgentSurvivalActions.fields(o, List.of("enabled"), "action", "agent");
        boolean on = !o.has("enabled") || (o.get("enabled").isJsonPrimitive() && o.getAsJsonPrimitive("enabled").isBoolean()
                && o.get("enabled").getAsBoolean());
        if (o.has("enabled"))
            AgentSurvivalActions.check(o.get("enabled").isJsonPrimitive() && o.getAsJsonPrimitive("enabled").isBoolean(),
                    "enabled must be boolean");
        AgentSurvivalActions.check(p.isInWater() || p.isSwimming(), "Agent is not in water");
        try { p.setSwimming(on); } catch (RuntimeException ignored) {}
        p.setSprinting(on);
        return AgentSurvivalActions.out("enabled", on, "inWater", p.isInWater());
    }

    private static Map<String, Object> climb(Player p) {
        boolean on = false;
        Location here = p.getLocation();
        for (BlockFace face : new BlockFace[]{BlockFace.NORTH, BlockFace.SOUTH, BlockFace.EAST, BlockFace.WEST, BlockFace.SELF}) {
            if (climbable(here.getBlock().getRelative(face).getType().name())) { on = true; break; }
        }
        AgentSurvivalActions.check(on, "No ladder, vine, or scaffolding against the body");
        Vector v = p.getVelocity();
        v.setY(Math.max(0.2, v.getY()));
        p.setVelocity(v);
        return AgentSurvivalActions.out("climbed", true, "vy", v.getY());
    }

    private static Map<String, Object> move(NPC npc, Player p, JsonObject o) {
        AgentSurvivalActions.fields(o, "action", "agent", "direction", "blocks");
        String direction = AgentSurvivalActions.str(o, "direction");
        int blocks = AgentSurvivalActions.num(o, "blocks", 1, MOVE_MAX);
        int[] d = moveDelta(direction, blocks, p.getLocation().getYaw());
        Location dest = p.getLocation().clone().add(d[0], d[1], d[2]);
        AgentBehaviors.cancel(npc);
        AgentSelfDefense.release(npc);
        Map<String, Object> r = AgentNavigation.start(npc, dest, false);
        r.put("direction", direction.toLowerCase(Locale.ROOT));
        r.put("blocks", blocks);
        return r;
    }

    private static Entity namedOrUuid(Player p, JsonObject o, double max, String verb) {
        String sel = AgentTargets.selector(o);
        AgentSurvivalActions.check(!sel.isBlank(), verb + " requires uuid, target, player, or name");
        Entity e;
        if (AgentTargets.looksLikeUuid(sel)) {
            e = Bukkit.getEntity(AgentTargets.parseUuid(sel));
            AgentSurvivalActions.check(e != null, "No entity with that UUID");
        } else {
            e = AgentTargets.findNamed(p, sel);
            AgentSurvivalActions.check(e != null, AgentTargets.missing(sel));
        }
        AgentSurvivalActions.check(e != p, "Cannot " + verb + " self");
        AgentSurvivalActions.check(e.isValid() && !e.isDead(), "Target is gone");
        AgentSurvivalActions.check(e.getWorld() != null && e.getWorld().equals(p.getWorld()), "Target is in another world");
        double dist = e.getLocation().distance(p.getLocation());
        AgentSurvivalActions.check(dist <= max, "Target is out of range (" + Math.round(dist * 10) / 10.0 + " blocks; max " + (int) max + ")");
        return e;
    }

    private static int findOwned(Player p, Material wanted) {
        if (wanted == null) {
            ItemStack hand = p.getInventory().getItemInMainHand();
            if (hand != null && !hand.getType().isAir()) return p.getInventory().getHeldItemSlot();
            return -1;
        }
        int held = p.getInventory().getHeldItemSlot();
        ItemStack hand = p.getInventory().getItemInMainHand();
        if (hand != null && hand.getType() == wanted) return held;
        for (int i = 0; i < 36; i++) {
            ItemStack s = p.getInventory().getItem(i);
            if (s != null && s.getType() == wanted) return i;
        }
        return -1;
    }

    private static int drinkSlot(Player p, Material wanted) {
        int held = p.getInventory().getHeldItemSlot();
        ItemStack hand = p.getInventory().getItemInMainHand();
        if (isDrink(hand) && (wanted == null || hand.getType() == wanted)) return held;
        for (int i = 0; i < 36; i++) {
            ItemStack s = p.getInventory().getItem(i);
            if (isDrink(s) && (wanted == null || s.getType() == wanted)) return i;
        }
        return -1;
    }

    private static boolean isDrink(ItemStack s) {
        return s != null && !s.getType().isAir() && drinkable(s.getType().name());
    }

    private static boolean isHealingDrink(ItemStack s) {
        if (!isDrink(s)) return false;
        if (s.getType() == Material.MILK_BUCKET) return true;
        try {
            if (s.getItemMeta() instanceof PotionMeta meta) {
                for (PotionEffect e : meta.getCustomEffects())
                    if (e.getType().equals(PotionEffectType.INSTANT_HEALTH) || e.getType().equals(PotionEffectType.REGENERATION))
                        return true;
            }
        } catch (RuntimeException ignored) {}
        String n = s.getType().name();
        return n.contains("POTION");
    }

    private static Map<String, Object> consume(Player p, int slot, String key) {
        AgentAutonomy.clearStaleItemUse(p);
        AgentSurvivalActions.check(slot >= 0, "No drink in inventory");
        if (slot <= 8) p.getInventory().setHeldItemSlot(slot);
        ItemStack hand = p.getInventory().getItem(slot);
        AgentSurvivalActions.check(isDrink(hand), "Not a drink");
        PlayerItemConsumeEvent event = new PlayerItemConsumeEvent(p, hand.clone(), EquipmentSlot.HAND);
        Bukkit.getPluginManager().callEvent(event);
        AgentSurvivalActions.check(!event.isCancelled(), "Drink was rejected");
        try {
            if (hand.getItemMeta() instanceof PotionMeta meta) {
                for (PotionEffect e : meta.getCustomEffects()) p.addPotionEffect(e);
            }
        } catch (RuntimeException ignored) {}
        if (hand.getType() == Material.MILK_BUCKET)
            try { p.clearActivePotionEffects(); } catch (RuntimeException ignored) {}
        ItemStack next = hand.clone();
        next.setAmount(hand.getAmount() - 1);
        p.getInventory().setItem(slot, next.getAmount() > 0 ? next : null);
        ItemStack replacement = event.getReplacement();
        if (replacement == null) {
            Material empty = hand.getType().getCraftingRemainingItem();
            if (empty != null && empty != Material.AIR) replacement = new ItemStack(empty);
            if (hand.getType() == Material.MILK_BUCKET || hand.getType() == Material.HONEY_BOTTLE
                    || hand.getType().name().contains("POTION"))
                replacement = new ItemStack(hand.getType() == Material.HONEY_BOTTLE ? Material.GLASS_BOTTLE
                        : hand.getType() == Material.MILK_BUCKET ? Material.BUCKET : Material.GLASS_BOTTLE);
        }
        if (replacement != null && !replacement.getType().isAir()) {
            Map<Integer, ItemStack> extra = p.getInventory().addItem(replacement.clone());
            for (ItemStack lost : extra.values()) p.getWorld().dropItem(p.getLocation(), lost);
        }
        return AgentSurvivalActions.out(key, hand.getType().name(), "slot", slot, "food", p.getFoodLevel(), "health", p.getHealth());
    }

    private static EquipmentSlot shieldHand(Player p) {
        if (p.getInventory().getItemInMainHand() != null && p.getInventory().getItemInMainHand().getType() == Material.SHIELD)
            return EquipmentSlot.HAND;
        if (p.getInventory().getItemInOffHand() != null && p.getInventory().getItemInOffHand().getType() == Material.SHIELD)
            return EquipmentSlot.OFF_HAND;
        return null;
    }

    private static void consumeOneHand(Player p) {
        ItemStack hand = p.getInventory().getItemInMainHand();
        ItemStack next = hand.clone();
        next.setAmount(hand.getAmount() - 1);
        p.getInventory().setItemInMainHand(next.getAmount() > 0 ? next : null);
    }

    private static int ownedSimilar(Player p, ItemStack need) {
        int n = 0;
        for (ItemStack s : p.getInventory().getStorageContents())
            if (s != null && s.isSimilar(need)) n += s.getAmount();
        return n;
    }

    private static int ownedNamed(Player p, Material m) {
        int n = 0;
        for (ItemStack s : p.getInventory().getStorageContents())
            if (s != null && s.getType() == m) n += s.getAmount();
        return n;
    }

    private static void takeSimilar(Player p, ItemStack need, int amount) {
        int left = amount;
        for (int i = 0; i < 36 && left > 0; i++) {
            ItemStack s = p.getInventory().getItem(i);
            if (s == null || !s.isSimilar(need)) continue;
            int take = Math.min(left, s.getAmount());
            if (s.getAmount() == take) p.getInventory().setItem(i, null);
            else {
                ItemStack next = s.clone();
                next.setAmount(s.getAmount() - take);
                p.getInventory().setItem(i, next);
            }
            left -= take;
        }
        AgentSurvivalActions.check(left == 0, "Not enough " + need.getType().name());
    }

    private static void takeNamed(Player p, Material m, int amount) {
        takeSimilar(p, new ItemStack(m), amount);
    }

    private static ItemStack takeNamedStack(Player p, JsonObject o, String key) {
        if (!o.has(key)) return null;
        Material m = AgentSurvivalActions.material(AgentSurvivalActions.str(o, key));
        if (ownedNamed(p, m) < 1) return null;
        return new ItemStack(m);
    }

    private static ItemStack enchantHeld(ItemStack hand, int levels) {
        try {
            ItemStack next = hand.clone();
            next = next.enchantWithLevels(Math.max(1, Math.min(30, levels)), false, new Random());
            if (next != null && !next.getEnchantments().isEmpty()) return next;
        } catch (RuntimeException ignored) {}
        ItemStack next = hand.clone();
        try {
            for (org.bukkit.enchantments.Enchantment enc : org.bukkit.enchantments.Enchantment.values()) {
                if (enc == null || !enc.canEnchantItem(next)) continue;
                next.addEnchantment(enc, 1);
                break;
            }
        } catch (RuntimeException ignored) {}
        return next.getEnchantments().isEmpty() ? null : next;
    }

    static ItemStack anvilMerge(ItemStack base, ItemStack extra) {
        if (base == null || extra == null) return null;
        ItemStack out = base.clone();
        boolean changed = false;
        if (base.getType() == extra.getType() && base.getItemMeta() instanceof Damageable d1
                && extra.getItemMeta() instanceof Damageable d2) {
            int max = base.getType().getMaxDurability();
            if (max > 0) {
                int remain = (max - d1.getDamage()) + (max - d2.getDamage()) + max / 20;
                int damage = Math.max(0, max - remain);
                Damageable meta = (Damageable) out.getItemMeta();
                meta.setDamage(damage);
                out.setItemMeta(meta);
                changed = true;
            }
        }
        try {
            for (var e : extra.getEnchantments().entrySet()) {
                int have = out.getEnchantmentLevel(e.getKey());
                int want = Math.max(have, e.getValue());
                if (want > have && e.getKey().canEnchantItem(out)) {
                    out.addUnsafeEnchantment(e.getKey(), want);
                    changed = true;
                }
            }
        } catch (RuntimeException ignored) {}
        return changed ? out : null;
    }

    private static boolean choiceMatches(SmithingRecipe recipe, String part, ItemStack stack) {
        try {
            var method = recipe.getClass().getMethod("get" + Character.toUpperCase(part.charAt(0)) + part.substring(1));
            Object choice = method.invoke(recipe);
            if (choice instanceof org.bukkit.inventory.RecipeChoice rc) return rc.test(stack);
            if (choice instanceof ItemStack item) return item.getType() == stack.getType();
        } catch (ReflectiveOperationException ignored) {}
        return true;
    }

    private static List<Recipe> iterableRecipes() {
        List<Recipe> all = new ArrayList<>();
        try {
            var it = Bukkit.recipeIterator();
            while (it.hasNext()) all.add(it.next());
        } catch (RuntimeException ignored) {}
        return all;
    }

    private static LivingEntity nearestHostile(Player p, int radius) {
        LivingEntity best = null;
        double score = radius * radius;
        try {
            for (Entity e : p.getNearbyEntities(radius, radius, radius)) {
                if (!(e instanceof LivingEntity living) || living.isDead() || living.equals(p)) continue;
                if (!AgentProtect.hostile(living)) continue;
                double d = living.getLocation().distanceSquared(p.getLocation());
                if (d < score) { score = d; best = living; }
            }
        } catch (RuntimeException ignored) {}
        return best;
    }

    private static Block nearest(Player p, int radius, java.util.function.Predicate<String> want) {
        Location at = p.getLocation();
        Block best = null;
        double score = radius * radius + 1;
        for (int dx = -radius; dx <= radius; dx++)
            for (int dz = -radius; dz <= radius; dz++)
                for (int dy = -2; dy <= 4; dy++) {
                    int x = at.getBlockX() + dx, y = at.getBlockY() + dy, z = at.getBlockZ() + dz;
                    if (y < p.getWorld().getMinHeight() || y >= p.getWorld().getMaxHeight()) continue;
                    if (!p.getWorld().isChunkLoaded(x >> 4, z >> 4)) continue;
                    Block b = p.getWorld().getBlockAt(x, y, z);
                    if (!want.test(b.getType().name())) continue;
                    double d = b.getLocation().add(0.5, 0.5, 0.5).distanceSquared(at);
                    if (d < score) { score = d; best = b; }
                }
        return best;
    }

    private static JsonObject aliasProtect(JsonObject o) {
        JsonObject copy = o.deepCopy();
        copy.addProperty("action", "protect");
        return copy;
    }

    private AgentFinishActions() {}
}
