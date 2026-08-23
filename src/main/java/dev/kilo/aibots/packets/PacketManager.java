package dev.kilo.aibots.packets;

import com.comphenix.protocol.PacketType;
import com.comphenix.protocol.ProtocolLibrary;
import com.comphenix.protocol.ProtocolManager;
import com.comphenix.protocol.events.PacketContainer;
import org.bukkit.Bukkit;

import java.util.List;
import java.util.function.Supplier;

/**
 * ProtocolLib integration. IMPORTANT: the bot's tab-list entry must STAY while
 * the bot is alive - modern clients do not render a player entity whose info
 * entry was removed (the bot becomes invisible). We therefore only send
 * PLAYER_INFO_REMOVE when a bot despawns, cleaning up its ghost tab entry.
 */
public final class PacketManager {

    private final Supplier<List<java.util.UUID>> botUuids;
    private final ProtocolManager protocol;

    private PacketManager(ProtocolManager protocol, Supplier<List<java.util.UUID>> botUuids) {
        this.protocol = protocol;
        this.botUuids = botUuids;
    }

    /** Returns null if ProtocolLib is missing or incompatible, which disables the plugin. */
    public static PacketManager create(org.bukkit.plugin.java.JavaPlugin plugin,
                                       Supplier<List<java.util.UUID>> botUuids) {
        if (Bukkit.getPluginManager().getPlugin("ProtocolLib") == null) {
            Bukkit.getLogger().severe("[AIBots] ProtocolLib is required but not installed! Download it from:");
            Bukkit.getLogger().severe("[AIBots] https://modrinth.com/plugin/protocollib");
            return null;
        }
        try {
            PacketManager pm = new PacketManager(ProtocolLibrary.getProtocolManager(), botUuids);
            String version = Bukkit.getPluginManager().getPlugin("ProtocolLib").getDescription().getVersion();
            Bukkit.getLogger().info("[AIBots] Hooked into ProtocolLib " + version);
            return pm;
        } catch (Throwable t) {
            Bukkit.getLogger().severe("[AIBots] ProtocolLib is installed but incompatible with this server "
                    + "build (" + t.getMessage() + "). Update ProtocolLib.");
            return null;
        }
    }

    /** Removes a despawned bot's ghost entry from everyone's tab list. */
    public void hideFromTabList(java.util.UUID uuid) {
        try {
            PacketContainer packet = protocol.createPacket(PacketType.Play.Server.PLAYER_INFO_REMOVE);
            packet.getUUIDLists().write(0, List.of(uuid));
            protocol.broadcastServerPacket(packet);
        } catch (Throwable ignored) {
        }
    }
}
