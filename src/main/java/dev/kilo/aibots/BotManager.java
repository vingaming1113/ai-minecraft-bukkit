package dev.kilo.aibots;

import org.bukkit.Bukkit;
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

/** Owns all bots: lifecycle, persistence, tick driver. */
public final class BotManager {

    private final AIBotPlugin plugin;
    private final Map<String, Bot> bots = new LinkedHashMap<>();
    private int tickTask = -1;

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

    public Bot spawn(String name, Location loc, Bot.Settings settings) {
        if (botByName(name) != null) return null;
        Bot bot = new Bot(plugin, name, loc, settings);
        // visible custom name above the head like a real nametag
        bot.body().bukkit().customName(net.kyori.adventure.text.Component.text(name));
        bot.body().bukkit().setCustomNameVisible(true);
        bots.put(name.toLowerCase(Locale.ROOT), bot);
        return bot;
    }

    public boolean remove(String name) {
        Bot bot = bots.remove(name.toLowerCase(Locale.ROOT));
        if (bot == null) return false;
        bot.discard();
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
                    b.walker().tick();
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

    /** Respawns persisted bots, falling back to config-defined ones. */
    public void loadAll() {
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
                    Bot bot = spawn(s.getString("name", "Bot" + key), loc, st);
                    if (bot != null) {
                        for (String entry : s.getStringList("inventory")) {
                            String[] split = entry.split(":");
                            Material m = Material.matchMaterial(split[0]);
                            if (m != null && split.length > 1) {
                                try {
                                    bot.giveItem(new org.bukkit.inventory.ItemStack(m, Integer.parseInt(split[1])));
                                } catch (NumberFormatException ignored) {
                                }
                            }
                        }
                        restored = true;
                    }
                }
            }
        }
        if (!restored) {
            // spawn bots from config.yml
            for (Map<?, ?> raw : plugin.getConfig().getMapList("bots")) {
                Map<String, Object> def = castMap(raw);
                String name = String.valueOf(def.getOrDefault("name", "Alex"));
                World world = Bukkit.getWorlds().get(0);
                Location loc = world.getSpawnLocation();
                Bot.Settings st = new Bot.Settings(
                        String.valueOf(def.getOrDefault("persona", plugin.defaultPersona())),
                        parseGamemode(String.valueOf(def.getOrDefault("gamemode", "survival")).toUpperCase(Locale.ROOT)),
                        Boolean.parseBoolean(String.valueOf(def.getOrDefault("allowCommands", "false"))));
                Object gmObj = def.get("gamemode");
                if (gmObj instanceof String g && g.equalsIgnoreCase("creative")) {
                    st = new Bot.Settings(st.persona(), GameMode.CREATIVE, st.allowCommands());
                }
                spawn(name, loc, st);
            }
        }
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

    public Player nearestPlayer(Location loc) {
        Player best = null;
        double bestDist = Double.MAX_VALUE;
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
