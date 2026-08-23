package dev.kilo.aibots;

import org.bukkit.Bukkit;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.PlayerDeathEvent;

import java.util.concurrent.ThreadLocalRandom;

/**
 * Makes bots die and respawn like real players: on death they drop what they
 * carry (survival), stop walking, and respawn ~3 seconds later at spawn.
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

        // drop the virtual inventory like a real survival death
        if (b.settings().gamemode() != GameMode.CREATIVE) {
            b.dropInventoryAt(deathSpot);
            event.getDrops().clear(); // we already dropped everything ourselves
        }

        // respawn after a short delay, like a player clicking "Respawn"
        Bukkit.getScheduler().runTaskLater(plugin, () -> {
            try {
                event.getEntity().spigot().respawn();
                b.speak("(ouch... respawning)");
            } catch (Throwable t) {
                plugin.getLogger().warning("[" + b.name() + "] respawn failed: " + t);
            }
        }, 50L + ThreadLocalRandom.current().nextLong(20));
    }
}
