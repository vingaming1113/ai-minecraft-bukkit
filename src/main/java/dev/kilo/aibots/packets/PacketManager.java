package dev.kilo.aibots.packets;

import com.comphenix.protocol.PacketType;
import com.comphenix.protocol.ProtocolLibrary;
import com.comphenix.protocol.ProtocolManager;
import com.comphenix.protocol.events.PacketContainer;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;

import java.util.List;
import java.util.UUID;
import java.util.function.Supplier;

/**
 * ProtocolLib integration. Keeps bots off the tab list with a single
 * PLAYER_INFO_REMOVE packet instead of scoreboard-team hacks, and re-applies it
 * to every player that joins later. All packet work is optional - if ProtocolLib
 * is missing, the server falls back to vanilla behaviour at zero cost.
 */
public final class PacketManager implements Listener {

    private final Supplier<List<UUID>> botUuids;
    private final ProtocolManager protocol;
    private final boolean enabled;

    private PacketManager(ProtocolManager protocol, boolean enabled, Supplier<List<UUID>> botUuids) {
        this.protocol = protocol;
        this.enabled = enabled;
        this.botUuids = botUuids;
    }

    /** Returns null when ProtocolLib is not installed or disabled in config. */
    public static PacketManager create(org.bukkit.plugin.java.JavaPlugin plugin, boolean configEnabled,
                                       Supplier<List<UUID>> botUuids) {
        if (!configEnabled || Bukkit.getPluginManager().getPlugin("ProtocolLib") == null) {
            return null;
        }
        try {
            PacketManager pm = new PacketManager(ProtocolLibrary.getProtocolManager(), true, botUuids);
            Bukkit.getPluginManager().registerEvents(pm, plugin);
            return pm;
        } catch (Throwable t) {
            Bukkit.getLogger().warning("[AIBots] ProtocolLib present but incompatible (" + t.getMessage()
                    + ") - continuing without packet optimizations.");
            return null;
        }
    }

    public boolean isEnabled() {
        return enabled;
    }

    /** Removes the given fake players from everyone's tab list (one packet for all). */
    public void hideFromTabList(List<UUID> uuids) {
        if (!enabled || uuids.isEmpty()) return;
        try {
            PacketContainer packet = protocol.createPacket(PacketType.Play.Server.PLAYER_INFO_REMOVE);
            packet.getUUIDLists().write(0, uuids);
            protocol.broadcastServerPacket(packet);
        } catch (Throwable ignored) {
        }
    }

    public void hideFromTabList(UUID uuid) {
        hideFromTabList(List.of(uuid));
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onJoin(PlayerJoinEvent event) {
        List<UUID> uuids = botUuids.get();
        if (uuids.isEmpty()) return;
        Player joining = event.getPlayer();
        try {
            PacketContainer packet = protocol.createPacket(PacketType.Play.Server.PLAYER_INFO_REMOVE);
            packet.getUUIDLists().write(0, uuids);
            protocol.sendServerPacket(joining, packet);
        } catch (Throwable ignored) {
        }
    }
}
