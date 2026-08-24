package dev.kilo.aibots.packets;

import com.comphenix.protocol.PacketType;
import com.comphenix.protocol.ProtocolLibrary;
import com.comphenix.protocol.ProtocolManager;
import com.comphenix.protocol.events.PacketContainer;
import com.comphenix.protocol.wrappers.EnumWrappers;
import com.comphenix.protocol.wrappers.PlayerInfoData;
import com.comphenix.protocol.wrappers.WrappedGameProfile;
import com.destroystokyo.paper.profile.ProfileProperty;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.function.Supplier;

/**
 * ProtocolLib integration.
 * <p>
 * Bots bypass PlayerList.placeNewPlayer, so vanilla never announces them to
 * clients. We do it ourselves:
 * <ul>
 *   <li>ADD_PLAYER info packets (with skin) to every viewer when a bot spawns,
 *       and to every player that joins later - without this entry modern clients
 *       refuse to render the player entity (invisible) and hide it from tab.</li>
 *   <li>a forced hide/show refresh so clients (re)spawn the entity.</li>
 *   <li>PLAYER_INFO_REMOVE only on despawn, cleaning ghost tab entries.</li>
 * </ul>
 */
public final class PacketManager implements Listener {

    private static boolean loggedPacketError;

    private final org.bukkit.plugin.java.JavaPlugin plugin;
    private final Supplier<List<Player>> botBodies;
    private final ProtocolManager protocol;

    private PacketManager(org.bukkit.plugin.java.JavaPlugin plugin, ProtocolManager protocol,
                          Supplier<List<Player>> botBodies) {
        this.plugin = plugin;
        this.protocol = protocol;
        this.botBodies = botBodies;
    }

    /** Returns null if ProtocolLib is missing or incompatible, which disables the plugin. */
    public static PacketManager create(org.bukkit.plugin.java.JavaPlugin plugin,
                                       Supplier<List<Player>> botBodies) {
        if (Bukkit.getPluginManager().getPlugin("ProtocolLib") == null) {
            Bukkit.getLogger().severe("[AIBots] ProtocolLib is required but not installed! Download it from:");
            Bukkit.getLogger().severe("[AIBots] https://modrinth.com/plugin/protocollib");
            return null;
        }
        try {
            PacketManager pm = new PacketManager(plugin, ProtocolLibrary.getProtocolManager(), botBodies);
            Bukkit.getPluginManager().registerEvents(pm, plugin);
            String version = Bukkit.getPluginManager().getPlugin("ProtocolLib").getDescription().getVersion();
            Bukkit.getLogger().info("[AIBots] Hooked into ProtocolLib " + version);
            return pm;
        } catch (Throwable t) {
            Bukkit.getLogger().severe("[AIBots] ProtocolLib is installed but incompatible with this server "
                    + "build (" + t.getMessage() + "). Update ProtocolLib.");
            return null;
        }
    }

    /** Announces a freshly spawned bot: ADD_PLAYER to everyone + entity render refresh. */
    public void announceBot(Player body) {
        PlayerProfileSnapshot snap = PlayerProfileSnapshot.of(body);
        for (Player p : Bukkit.getOnlinePlayers()) {
            if (p.equals(body)) continue;
            sendTabEntry(p, snap);
            forceRender(p, body);
        }
    }

    /** Late joiners need the entries of every bot that already exists. */
    @EventHandler(priority = EventPriority.MONITOR)
    public void onJoin(PlayerJoinEvent event) {
        Player joining = event.getPlayer();
        // next tick - the client must finish its own login first
        Bukkit.getScheduler().runTask(plugin, () -> {
            for (Player body : botBodies.get()) {
                sendTabEntry(joining, PlayerProfileSnapshot.of(body));
                forceRender(joining, body);
            }
        });
    }

    /** Removes a despawned bot's ghost entry from everyone's tab list. */
    public void hideFromTabList(UUID uuid) {
        try {
            PacketContainer packet = protocol.createPacket(PacketType.Play.Server.PLAYER_INFO_REMOVE);
            packet.getUUIDLists().write(0, List.of(uuid));
            protocol.broadcastServerPacket(packet);
        } catch (Throwable ignored) {
        }
    }

    private void sendTabEntry(Player viewer, PlayerProfileSnapshot profile) {
        try {
            PacketContainer packet = protocol.createPacket(PacketType.Play.Server.PLAYER_INFO);
            java.util.EnumSet<EnumWrappers.PlayerInfoAction> actions =
                    java.util.EnumSet.of(EnumWrappers.PlayerInfoAction.ADD_PLAYER);
            packet.getPlayerInfoActions().write(0, actions);

            WrappedGameProfile wgp = new WrappedGameProfile(profile.uuid(), profile.name());
            applyTextures(wgp, profile);

            List<PlayerInfoData> dataAsList = new ArrayList<>();
            dataAsList.add(new PlayerInfoData(wgp, 30,
                    EnumWrappers.NativeGameMode.SURVIVAL, null));
            packet.getPlayerInfoDataLists().write(1, dataAsList);
            protocol.sendServerPacket(viewer, packet);
        } catch (Throwable t) {
            if (!loggedPacketError) {
                loggedPacketError = true;
                Bukkit.getLogger().warning("[AIBots] Could not build PLAYER_INFO_UPDATE packet: " + t);
            }
        }
    }

    private void applyTextures(WrappedGameProfile wgp, PlayerProfileSnapshot profile) throws Exception {
        if (!profile.hasTextures()) return;
        Object handle = wgp.getHandle();
        Object props = handle.getClass().getMethod("getProperties").invoke(handle);
        Class<?> propertyClass = Class.forName("com.mojang.authlib.Property");
        Object prop = profile.signature() != null
                ? propertyClass.getConstructor(String.class, String.class, String.class)
                        .newInstance("textures", profile.value(), profile.signature())
                : propertyClass.getConstructor(String.class, String.class)
                        .newInstance("textures", profile.value());
        props.getClass().getMethod("put", Object.class, Object.class).invoke(props, "textures", prop);
    }

    private void forceRender(Player viewer, Player body) {
        try {
            viewer.hideEntity(plugin, body);
            viewer.showEntity(plugin, body);
        } catch (Throwable ignored) {
        }
    }

    /** Plain snapshot of a Paper player profile (uuid/name/skin), safe to pass around. */
    public record PlayerProfileSnapshot(UUID uuid, String name, String value, String signature) {
        static PlayerProfileSnapshot of(Player player) {
            try {
                var pf = player.getPlayerProfile(); // Paper profile with real properties
                String value = null;
                String signature = null;
                for (ProfileProperty pp : pf.getProperties()) {
                    if ("textures".equals(pp.getName())) {
                        value = pp.getValue();
                        signature = pp.getSignature();
                        break;
                    }
                }
                return new PlayerProfileSnapshot(pf.getId(), pf.getName(), value, signature);
            } catch (Throwable t) {
                return new PlayerProfileSnapshot(player.getUniqueId(), player.getName(), null, null);
            }
        }

        boolean hasTextures() {
            return value != null;
        }
    }
}
