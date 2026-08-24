package dev.kilo.aibots;

import org.bukkit.Bukkit;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.player.PlayerJoinEvent;

import java.util.List;
import java.util.concurrent.ThreadLocalRandom;

/**
 * Makes bots die and respawn like real players: on death they drop what they
 * carry (survival), stop walking, and come back ~3 seconds later at spawn.
 * <p>
 * We deliberately do NOT use spigot().respawn(): vanilla respawn creates a brand
 * new ServerPlayer instance, which would leave the plugin holding a dead body
 * whose health reads 0 forever. Recreating the bot keeps everything consistent.
 */
public final class BotLifeListener implements Listener {

    private final AIBotPlugin plugin;

    public BotLifeListener(AIBotPlugin plugin) {
        this.plugin = plugin;
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onDeath(PlayerDeathEvent event) {
        Bot bot = plugin.botManager().botByName(event.getEntity().getName());
        if (bot == null) return;
        final Bot b = bot;

        b.walker().stop();
        Location deathSpot = event.getEntity().getLocation();
        boolean survival = b.settings().gamemode() != GameMode.CREATIVE;
        if (survival) {
            b.dropInventoryAt(deathSpot);
            event.getDrops().clear(); // we already dropped everything ourselves
        }

        // give the death message a moment, then rebuild a fresh body at spawn
        Bukkit.getScheduler().runTaskLater(plugin,
                () -> plugin.botManager().recreate(b),
                50L + ThreadLocalRandom.current().nextLong(20));
    }

    /** Bots greet players who join, like real ones do. */
    @EventHandler(priority = EventPriority.MONITOR)
    public void onJoin(PlayerJoinEvent event) {
        if (!plugin.greetJoins()) return;
        List<Bot> all = List.copyOf(plugin.botManager().all());
        if (all.isEmpty()) return;
        Bot greeter = all.get(ThreadLocalRandom.current().nextInt(all.size()));
        String joiner = event.getPlayer().getName();
        Bukkit.getScheduler().runTaskLater(plugin, () -> {
            if (!greeter.body().bukkit().isDead()) {
                greeter.hearDirect("[server] " + joiner + " just joined the game. Say hi!");
            }
        }, 60L + ThreadLocalRandom.current().nextLong(80));
    }
}
