package dev.kilo.aibots;

import dev.kilo.aibots.skin.SkinResolver;
import net.kyori.adventure.key.Key;
import org.bukkit.Bukkit;
import org.bukkit.Color;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.Consumer;

/** Owns all bots: lifecycle, persistence, tick driver. */
public final class BotManager {

    private final AIBotPlugin plugin;
    private final Map<String, Bot> bots = new LinkedHashMap<>();
    private int tickTask = -1;
    private int lookTimer;

    public BotManager(AIBotPlugin plugin) {
        this.plugin = plugin;
    }

    public Collection<Bot> all() {
        return bots.values();
    }

    public Bot botByName(String name) {
        return bots.get(name.toLowerCase(Locale.ROOT));
    }

    public String botNamesExcluding(String exclude) {
        List<String> names = new ArrayList<>();
        for (Bot b : bots.values()) {
            if (!b.name().equals(exclude)) names.add(b.name());
        }
        return names.isEmpty() ? "(none)" : String.join(", ", names);
    }

    public void resetChains() {
        bots.values().forEach(Bot::resetChain);
    }

    /**
     * Minecraft usernames are hard-capped at 16 chars [A-Za-z0-9_]. An oversized
     * bot name makes every player_info_update packet fail to encode, which KICKS
     * every real player online. Validate at every entry point - no exceptions.
     */
    public static String sanitizeBotName(String raw) {
        if (raw == null) return null;
        String cleaned = raw.replaceAll("[^A-Za-z0-9_]", "");
        if (cleaned.length() > 16) cleaned = cleaned.substring(0, 16);
        return cleaned.isEmpty() ? null : cleaned;
    }

    /** Resolves the skin off the main thread, then spawns on the main thread. */
    public void resolveAndSpawn(String name, Location loc, Bot.Settings settings,
                                String skinInput, Consumer<Bot> done) {
        Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
            String[] tex = SkinResolver.resolve(skinInput);
            Bukkit.getScheduler().runTask(plugin, () -> done.accept(spawn(name, loc, settings, tex)));
        });
    }

    public Bot spawn(String name, Location loc, Bot.Settings settings) {
        return spawn(name, loc, settings, null);
    }

    public Bot spawn(String name, Location loc, Bot.Settings settings, String[] skinTextures) {
        if (botByName(name) != null) return null;
        // never spawn inside terrain or in the sky: snap to the highest safe block
        Location safe = loc.clone();
        World w = loc.getWorld();
        int top = w.getHighestBlockYAt(safe.getBlockX(), safe.getBlockZ());
        if (top > w.getMinHeight()) {
            safe.setY(top + 1.0);
            safe.setX(Math.floor(safe.getX()) + 0.5);
            safe.setZ(Math.floor(safe.getZ()) + 0.5);
        }
        Bot bot = new Bot(plugin, name, safe, settings, skinTextures);

        Player body = bot.body().bukkit();
        body.customName(net.kyori.adventure.text.Component.text(name));
        body.setCustomNameVisible(true);

        // show up on the locator bar like a real player
        try {
            body.setWaypointColor(Color.AQUA);
            body.setWaypointStyle(Key.key("minecraft", "default"));
        } catch (Throwable ignored) {
        }

        bots.put(name.toLowerCase(Locale.ROOT), bot);

        // some server builds ignore pre-add positioning for players - enforce it
        Location actual = bot.body().location();
        if (!actual.getWorld().equals(safe.getWorld())
                || Math.abs(actual.getX() - safe.getX()) > 2 || Math.abs(actual.getZ() - safe.getZ()) > 2
                || actual.getY() < w.getMinHeight() + 1) {
            bot.teleport(safe);
        }

        // vanilla never announced the bot (we bypass placeNewPlayer) - do it ourselves:
        // ADD_PLAYER info packet with skin to every viewer + entity render refresh
        plugin.packets().announceBot(bot.body().bukkit());
        return bot;
    }

    public boolean remove(String name) {
        Bot bot = bots.remove(name.toLowerCase(Locale.ROOT));
        if (bot == null) return false;
        // an explicitly removed bot must not be resurrected from the file snapshot
        String k = name.toLowerCase(Locale.ROOT);
        preservedDefs.removeIf(def -> k.equals(keyOf(def)));
        bot.discard();
        // clean the ghost tab entry left behind by a despawned fake player
        plugin.packets().hideFromTabList(bot.body().bukkit().getUniqueId());
        return true;
    }

    /** Rebuilds a dead bot as a fresh body at world spawn (same identity/settings). */
    public void recreate(Bot old) {
        String name = old.name();
        Bot.Settings settings = old.settings();
        String skin = old.skinInput();
        Location spawnLoc = Bukkit.getWorlds().get(0).getSpawnLocation();
        remove(name);
        resolveAndSpawn(name, spawnLoc, settings, skin, fresh -> {
            if (fresh != null) fresh.speak("I'm back!");
            else plugin.getLogger().warning("[" + name + "] respawn failed - spawn manually with /aibot spawn");
        });
    }

    public void removeAll() {
        for (Bot b : List.copyOf(bots.values())) {
            remove(b.name());
        }
    }

    /** Starts the per-tick movement driver - this is what makes bodies walk. */
    public void startTicker() {
        stopTicker();
        long[] autosaveCounter = {0};
        tickTask = Bukkit.getScheduler().runTaskTimer(plugin, () -> {
            // periodic autosave so positions survive crashes, not just clean stops
            if (++autosaveCounter[0] >= 6000) { // ~5 minutes
                autosaveCounter[0] = 0;
                save();
            }
            for (Bot b : bots.values()) {
                try {
                    Player body = b.body().bukkit();
                    // void watchdog: falling out of the world kills you
                    if (!body.isDead() && body.getLocation().getY() < body.getWorld().getMinHeight() - 16) {
                        body.damage(1000.0);
                        continue;
                    }
                    if (body.isDead()) {
                        // safety net: no corpse may lie around forever - rebuild the
                        // bot ~4s after death even if the death event was missed
                        if (b.corpseTicks() > 80) {
                            plugin.getLogger().info("[" + b.name() + "] force-respawning lingering corpse");
                            recreate(b);
                        }
                        continue;
                    }
                    b.walker().tick();
                    b.tryAutonomy(System.currentTimeMillis());

                    // polish: idle bots glance at the nearest player, like real ones do
                    if (++lookTimer % 10 == 0 && !b.isBusy()) {
                        Player near = nearestPlayer(body.getLocation(), 12.0);
                        if (near != null && near != body) b.lookAt(near);
                    }
                } catch (Throwable t) {
                    plugin.getLogger().warning("[" + b.name() + "] movement error: " + t);
                    b.walker().stop();
                }
            }
        }, 1L, 1L).getTaskId();
    }

    public void stopTicker() {
        if (tickTask != -1) {
            Bukkit.getScheduler().cancelTask(tickTask);
            tickTask = -1;
        }
    }

    // ---------- persistence ----------

    private File botsFile() {
        return new File(plugin.getDataFolder(), "bots.yml");
    }

    /**
     * Definitions read from bots.yml, kept so save() can never lose a bot that is
     * only temporarily absent from runtime (death->respawn gap, failed spawn,
     * crash during skin resolution). Purged only by an explicit /aibot remove.
     */
    private final List<Map<String, Object>> preservedDefs = new ArrayList<>();

    private static String keyOf(Map<String, Object> def) {
        Object n = def.get("name");
        return n == null ? null : sanitizeBotName(String.valueOf(n)).toLowerCase(Locale.ROOT);
    }

    /**
     * bots.yml is the single source of truth: it ships as a default resource,
     * is edited by admins, and runtime state (position/inventory) is saved back
     * into the exact same format. Saving is LOSS-PROOF: definitions from the
     * file always survive unless explicitly removed with /aibot remove.
     */
    public void save() {
        List<Map<String, Object>> out = new ArrayList<>();
        java.util.Set<String> written = new java.util.HashSet<>();

        for (Bot b : bots.values()) {
            Map<String, Object> e = new LinkedHashMap<>();
            Location loc = b.body().location();
            e.put("name", b.name());
            e.put("world", loc.getWorld().getName());
            e.put("x", loc.getX());
            e.put("y", loc.getY());
            e.put("z", loc.getZ());
            e.put("yaw", (double) loc.getYaw());
            e.put("pitch", (double) loc.getPitch());
            e.put("persona", b.settings().persona());
            e.put("gamemode", b.settings().gamemode().name());
            e.put("allowCommands", b.settings().allowCommands());
            if (b.skinInput() != null) e.put("skin", b.skinInput());
            if (b.settings().model() != null && !b.settings().model().isBlank()) {
                e.put("model", b.settings().model());
            }
            List<String> inv = new ArrayList<>();
            b.inventoryMap().forEach((m, n) -> inv.add(m.name() + ":" + n));
            if (!inv.isEmpty()) e.put("inventory", inv);
            out.add(e);
            String k = b.name().toLowerCase(Locale.ROOT);
            written.add(k);
        }

        // never drop a defined bot just because it isn't alive right now
        for (Map<String, Object> preserved : preservedDefs) {
            String k = keyOf(preserved);
            if (k != null && written.add(k)) {
                out.add(preserved);
            }
        }

        YamlConfiguration yml = new YamlConfiguration();
        yml.set("bots", out.isEmpty() ? null : out);
        try {
            plugin.getDataFolder().mkdirs();
            yml.save(botsFile());
        } catch (IOException e) {
            plugin.getLogger().warning("Could not save bots.yml: " + e.getMessage());
        }
    }

    /** Spawns every bot defined in bots.yml at its saved position (or world spawn). */
    public void loadAll() {
        List<BotDef> defs = new ArrayList<>();
        preservedDefs.clear();

        if (!botsFile().exists()) {
            plugin.getLogger().warning("No bots.yml found - no bots to spawn. Create one or use /aibot spawn.");
            return;
        }
        YamlConfiguration yml = YamlConfiguration.loadConfiguration(botsFile());
        for (Map<?, ?> raw : yml.getMapList("bots")) {
            Map<String, Object> s = castMap(raw);
            String entryName = sanitizeBotName(String.valueOf(s.getOrDefault("name", "")));
            if (entryName == null) {
                plugin.getLogger().warning("bots.yml entry has an invalid name - skipped");
                continue;
            }
            String rawName = String.valueOf(s.getOrDefault("name", ""));
            if (!entryName.equals(rawName)) {
                plugin.getLogger().warning("Bot name '" + rawName + "' is invalid (max 16 chars, "
                        + "[A-Za-z0-9_] only) - using '" + entryName + "'");
            }
            World world = Bukkit.getWorld(String.valueOf(s.getOrDefault("world", Bukkit.getWorlds().get(0).getName())));
            if (world == null) world = Bukkit.getWorlds().get(0);
            Location loc;
            if (s.containsKey("x")) {
                loc = new Location(world,
                        asDouble(s.get("x")), asDouble(s.get("y")), asDouble(s.get("z")),
                        (float) asDouble(s.getOrDefault("yaw", 0.0)), (float) asDouble(s.getOrDefault("pitch", 0.0)));
            } else {
                loc = world.getSpawnLocation(); // brand-new bot from a hand-written entry
            }
            GameMode gm = parseGamemode(String.valueOf(s.getOrDefault("gamemode", "survival")));
            Bot.Settings st = new Bot.Settings(
                    String.valueOf(s.getOrDefault("persona", plugin.defaultPersona())),
                    gm,
                    Boolean.parseBoolean(String.valueOf(s.getOrDefault("allowCommands", "false"))),
                    s.get("model") != null && !String.valueOf(s.get("model")).isBlank()
                            ? String.valueOf(s.get("model")) : null);
            Map<Material, Integer> inv = new LinkedHashMap<>();
            if (s.get("inventory") instanceof List<?> list) {
                for (Object o : list) {
                    String[] split = String.valueOf(o).split(":");
                    Material m = Material.matchMaterial(split[0]);
                    if (m != null && split.length > 1) {
                        try {
                            inv.put(m, Integer.parseInt(split[1]));
                        } catch (NumberFormatException ignored) {
                        }
                    }
                }
            }
            String skin = s.get("skin") != null && !String.valueOf(s.get("skin")).isBlank()
                    ? String.valueOf(s.get("skin")) : null;
            defs.add(new BotDef(entryName, loc, st, inv, skin));
            // snapshot exactly what the file says, so saves can never lose it
            Map<String, Object> copy = new LinkedHashMap<>(castMap(raw));
            copy.put("name", entryName);
            preservedDefs.add(copy);
        }

        // resolve skins off-thread, then spawn everything on the main thread
        Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
            List<PendingSpawn> pending = new ArrayList<>();
            for (BotDef def : defs) {
                pending.add(new PendingSpawn(def, SkinResolver.resolve(def.skinInput())));
            }
            Bukkit.getScheduler().runTask(plugin, () -> {
                for (PendingSpawn p : pending) {
                    Bot bot = spawn(p.def().name(), p.def().loc(), p.def().settings(), p.tex());
                    if (bot != null) {
                        bot.setSkinInput(p.def().skinInput());
                        p.def().inventory().forEach((m, n) -> bot.giveItem(new org.bukkit.inventory.ItemStack(m, n)));
                    } else {
                        plugin.getLogger().warning("[AIBots] Could not spawn '" + p.def().name()
                                + "' - definition kept in bots.yml and will retry on next restart/reload.");
                    }
                }
            });
        });
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> castMap(Object o) {
        Map<String, Object> out = new LinkedHashMap<>();
        if (o instanceof Map<?, ?> m) {
            m.forEach((k, v) -> out.put(String.valueOf(k), v));
        }
        return out;
    }

    private static double asDouble(Object o) {
        try {
            return Double.parseDouble(String.valueOf(o));
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    private record BotDef(String name, Location loc, Bot.Settings settings, Map<Material, Integer> inventory,
                          String skinInput) {
    }

    private record PendingSpawn(BotDef def, String[] tex) {
    }

    private GameMode parseGamemode(String name) {
        try {
            return GameMode.valueOf((name == null ? "SURVIVAL" : name).toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            return GameMode.SURVIVAL;
        }
    }

    public Player nearestPlayer(Location loc, double maxRange) {
        Player best = null;
        double bestDist = maxRange;
        for (Player p : Bukkit.getOnlinePlayers()) {
            if (!p.getWorld().equals(loc.getWorld())) continue;
            double d = p.getLocation().distance(loc);
            if (d < bestDist) {
                bestDist = d;
                best = p;
            }
        }
        return best;
    }
}
