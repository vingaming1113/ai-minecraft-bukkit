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

    /**
     * Creates the packet manager. ProtocolLib is a hard dependency - returns null
     * only if it is missing or incompatible, which disables the plugin.
     */
    public static PacketManager create(org.bukkit.plugin.java.JavaPlugin plugin, boolean configEnabled,
                                       Supplier<List<UUID>> botUuids) {
        if (Bukkit.getPluginManager().getPlugin("ProtocolLib") == null) {
            Bukkit.getLogger().severe("[AIBots] ProtocolLib is required but not installed! Download it from:");
            Bukkit.getLogger().severe("[AIBots] https://modrinth.com/plugin/protocollib");
            return null;
        }
        try {
            PacketManager pm = new PacketManager(ProtocolLibrary.getProtocolManager(), true, botUuids);
            Bukkit.getPluginManager().registerEvents(pm, plugin);
            Bukkit.getLogger().info("[AIBots] Hooked into ProtocolLib " + Bukkit.getPluginManager()
                    .getPlugin("ProtocolLib").getDescription().getVersion());
            return pm;
        } catch (Throwable t) {
            Bukkit.getLogger().severe("[AIBots] ProtocolLib is installed but incompatible with this server "
                    + "build (" + t.getMessage() + "). Update ProtocolLib.");
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
