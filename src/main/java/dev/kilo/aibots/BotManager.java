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

        // vanilla never announced the bot (we bypass placeNewPlayer) - do it ourselves:
        // ADD_PLAYER info packet with skin to every viewer + entity render refresh
        plugin.packets().announceBot(bot.body().bukkit());
        return bot;
    }

    public boolean remove(String name) {
        Bot bot = bots.remove(name.toLowerCase(Locale.ROOT));
        if (bot == null) return false;
        bot.discard();
        // clean the ghost tab entry left behind by a despawned fake player
        plugin.packets().hideFromTabList(bot.body().bukkit().getUniqueId());
        return true;
    }

    public void removeAll() {
        for (Bot b : List.copyOf(bots.values())) {
            remove(b.name());
        }
    }

    /** Starts the per-tick movement driver - this is what makes bodies walk. */
    public void startTicker() {
        stopTicker();
        tickTask = Bukkit.getScheduler().runTaskTimer(plugin, () -> {
            for (Bot b : bots.values()) {
                try {
                    Player body = b.body().bukkit();
                    // void watchdog: falling out of the world kills you
                    if (!body.isDead() && body.getLocation().getY() < body.getWorld().getMinHeight() - 16) {
                        body.damage(1000.0);
                        continue;
                    }
                    if (body.isDead()) continue;
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

    public void save() {
        File file = new File(plugin.getDataFolder(), "bots.yml");
        YamlConfiguration yml = new YamlConfiguration();
        int i = 0;
        for (Bot b : bots.values()) {
            String base = "bots." + i++ + ".";
            Location loc = b.body().location();
            yml.set(base + "name", b.name());
            yml.set(base + "world", loc.getWorld().getName());
            yml.set(base + "x", loc.getX());
            yml.set(base + "y", loc.getY());
            yml.set(base + "z", loc.getZ());
            yml.set(base + "yaw", loc.getYaw());
            yml.set(base + "pitch", loc.getPitch());
            yml.set(base + "persona", b.settings().persona());
            yml.set(base + "gamemode", b.settings().gamemode().name());
            yml.set(base + "allowCommands", b.settings().allowCommands());
            yml.set(base + "skin", b.skinInput());
            List<String> inv = new ArrayList<>();
            b.inventoryMap().forEach((m, n) -> inv.add(m.name() + ":" + n));
            yml.set(base + "inventory", inv);
        }
        try {
            plugin.getDataFolder().mkdirs();
            yml.save(file);
        } catch (IOException e) {
            plugin.getLogger().warning("Could not save bots.yml: " + e.getMessage());
        }
    }

    private record BotDef(String name, Location loc, Bot.Settings settings, Map<Material, Integer> inventory,
                          String skinInput) {
    }

    /** Respawns persisted bots, falling back to config-defined ones. Skins resolve async. */
    public void loadAll() {
        List<BotDef> defs = new ArrayList<>();

        File file = new File(plugin.getDataFolder(), "bots.yml");
        boolean restored = false;
        if (file.exists()) {
            YamlConfiguration yml = YamlConfiguration.loadConfiguration(file);
            ConfigurationSection sec = yml.getConfigurationSection("bots");
            if (sec != null) {
                for (String key : sec.getKeys(false)) {
                    ConfigurationSection s = sec.getConfigurationSection(key);
                    if (s == null) continue;
                    World world = Bukkit.getWorld(s.getString("world", "world"));
                    if (world == null) continue;
                    Location loc = new Location(world,
                            s.getDouble("x"), s.getDouble("y"), s.getDouble("z"),
                            (float) s.getDouble("yaw"), (float) s.getDouble("pitch"));
                    GameMode gm = parseGamemode(s.getString("gamemode"));
                    Bot.Settings st = new Bot.Settings(
                            s.getString("persona", plugin.defaultPersona()), gm, s.getBoolean("allowCommands"));
                    Map<Material, Integer> inv = new LinkedHashMap<>();
                    for (String entry : s.getStringList("inventory")) {
                        String[] split = entry.split(":");
                        Material m = Material.matchMaterial(split[0]);
                        if (m != null && split.length > 1) {
                            try {
                                inv.put(m, Integer.parseInt(split[1]));
                            } catch (NumberFormatException ignored) {
                            }
                        }
                    }
                    defs.add(new BotDef(s.getString("name", "Bot" + key), loc, st, inv, s.getString("skin")));
                    restored = true;
                }
            }
        }
        if (!restored) {
            World world = Bukkit.getWorlds().get(0);
            for (Map<?, ?> raw : plugin.getConfig().getMapList("bots")) {
                Map<String, Object> def = castMap(raw);
                GameMode gm = Boolean.parseBoolean(String.valueOf(def.getOrDefault("creative", def.getOrDefault("gamemode", "survival"))))
                        ? GameMode.CREATIVE : parseGamemode(String.valueOf(def.getOrDefault("gamemode", "survival")).toUpperCase(Locale.ROOT));
                defs.add(new BotDef(
                        String.valueOf(def.getOrDefault("name", "Alex")),
                        world.getSpawnLocation(),
                        new Bot.Settings(
                                String.valueOf(def.getOrDefault("persona", plugin.defaultPersona())),
                                parseGamemode(String.valueOf(def.getOrDefault("gamemode", "survival")).toUpperCase(Locale.ROOT)),
                                Boolean.parseBoolean(String.valueOf(def.getOrDefault("allowCommands", "false")))),
                        Map.of(),
                        def.get("skin") != null ? String.valueOf(def.get("skin")) : null));
            }
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
                    }
                }
            });
        });
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

    @SuppressWarnings("unchecked")
    private Map<String, Object> castMap(Object o) {
        Map<String, Object> out = new LinkedHashMap<>();
        if (o instanceof Map<?, ?> m) {
            m.forEach((k, v) -> out.put(String.valueOf(k), v));
        }
        return out;
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
