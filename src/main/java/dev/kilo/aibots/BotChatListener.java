package dev.kilo.aibots;

import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import io.papermc.paper.event.player.AsyncChatEvent;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;

import java.util.List;

/** Feeds chat into the bots. */
public final class BotChatListener implements Listener {

    private final AIBotPlugin plugin;

    public BotChatListener(AIBotPlugin plugin) {
        this.plugin = plugin;
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onChat(AsyncChatEvent event) {
        String plain = PlainTextComponentSerializer.plainText().serialize(event.message());
        if (plain.isBlank()) return;
        // hop to the main thread - bot logic is not thread-safe
        plugin.getServer().getGlobalRegionScheduler().run(plugin, task -> {
            for (Bot bot : List.copyOf(plugin.botManager().all())) {
                bot.hear(event.getPlayer(), event.message());
            }
        });
    }
}
