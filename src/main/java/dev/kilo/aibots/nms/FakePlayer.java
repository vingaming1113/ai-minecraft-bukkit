package dev.kilo.aibots.nms;

import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.entity.Player;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.UUID;

/**
 * Spawns a genuine net.minecraft.server.level.ServerPlayer (a fake player,
 * exactly how NPC plugins like Citizens/FakePlayer do it) using reflection
 * against Paper's Mojang-mapped runtime. No packets library required.
 * <p>
 * The returned body has real player physics: it collides, falls, swims and can be
 * pushed. It is moved every tick with Entity#move(MoverType.SELF, ...) so it WALKS
 * like a real player instead of teleporting.
 */
public final class FakePlayer {

    private static boolean failed;

    private final Object serverPlayer;
    private final Method moveMethod;
    private final Object moverSelf;
    private final Constructor<?> vec3Ctor;
    private volatile Player bukkitPlayer;
    private Object playerList; // NMS PlayerList the bot was registered in

    private FakePlayer(Object serverPlayer, Method moveMethod, Object moverSelf,
                       Constructor<?> vec3Ctor, Player bukkitPlayer) {
        this.serverPlayer = serverPlayer;
        this.moveMethod = moveMethod;
        this.moverSelf = moverSelf;
        this.vec3Ctor = vec3Ctor;
        this.bukkitPlayer = bukkitPlayer;
    }

    /** Returns null (and logs once) if this server build doesn't support fake players. */
    public static synchronized FakePlayer create(Location loc, String name) {
        return create(loc, name, null);
    }

    /**
     * @param skinTextures optional {value, signature} pair injected into the GameProfile,
     *                     making the bot wear that skin. Null signature = unsigned (works for NPCs).
     */
    public static synchronized FakePlayer create(Location loc, String name, String[] skinTextures) {
        if (failed) return null;
        try {
            // Bukkit.getServer() IS a CraftServer at runtime; get its NMS MinecraftServer
            Object craftServer = Bukkit.getServer();
            Class<?> craftServerClass = craftServer.getClass();
            Object mcServer = craftServerClass.getMethod("getServer").invoke(craftServer);

            World bukkitWorld = loc.getWorld();
            Object serverLevel = bukkitWorld.getClass().getMethod("getHandle").invoke(bukkitWorld);

            Class<?> serverPlayerClass = Class.forName("net.minecraft.server.level.ServerPlayer");
            Class<?> clientInfoClass = Class.forName("net.minecraft.server.level.ClientInformation");
            Object clientInfo = clientInfoClass.getMethod("createDefault").invoke(null);

            Class<?> gameProfileClass = Class.forName("com.mojang.authlib.GameProfile");
            // MC usernames are hard-capped at 16 chars by the protocol - an oversized
            // name crashes EVERY client that receives this bot's info packet
            String safeName = name.length() > 16 ? name.substring(0, 16) : name;
            Object profile = gameProfileClass.getConstructor(UUID.class, String.class)
                    .newInstance(UUID.randomUUID(), safeName);

            if (skinTextures != null && skinTextures.length >= 2 && skinTextures[0] != null) {
                injectSkin(gameProfileClass, profile, skinTextures);
            }

            Constructor<?> ctor = null;
            for (Constructor<?> c : serverPlayerClass.getDeclaredConstructors()) {
                Class<?>[] p = c.getParameterTypes();
                if (p.length == 4 && p[0].isInstance(mcServer) && p[1].isInstance(serverLevel)
                        && p[2] == gameProfileClass) {
                    ctor = c;
                    break;
                }
            }
            if (ctor == null) throw new IllegalStateException("ServerPlayer ctor not found");
            ctor.setAccessible(true);
            Object sp = ctor.newInstance(mcServer, serverLevel, profile, clientInfo);

            attachBlackHoleConnection(sp, serverPlayerClass, mcServer, mcServerClass(serverPlayerClass), gameProfileClass, profile);

            // position before adding to world
            Method setPos = findMethod(serverPlayerClass, "setPos", double.class, double.class, double.class);
            setPos.invoke(sp, loc.getX(), loc.getY(), loc.getZ());
            serverPlayerClass.getMethod("setYRot", float.class).invoke(sp, loc.getYaw());
            serverPlayerClass.getMethod("setXRot", float.class).invoke(sp, loc.getPitch());

            // add to world: prefer addNewPlayer, fallback addPlayer, fallback any (ServerPlayer)->void/boolean
            Method add = findMethod(serverLevel.getClass(), "addNewPlayer", serverPlayerClass);
            if (add == null) add = findMethod(serverLevel.getClass(), "addPlayer", serverPlayerClass);
            if (add != null) {
                add.invoke(serverLevel, sp);
            } else {
                Method found = null;
                for (Method m : serverLevel.getClass().getMethods()) {
                    Class<?>[] p = m.getParameterTypes();
                    if (p.length == 1 && p[0] == serverPlayerClass
                            && (m.getReturnType() == void.class || m.getReturnType() == boolean.class)) {
                        found = m;
                        break;
                    }
                }
                if (found == null) throw new IllegalStateException("no ServerLevel#add*Player found");
                found.invoke(serverLevel, sp);
            }

            // movement plumbing
            Class<?> moverTypeClass = Class.forName("net.minecraft.world.entity.MoverType");
            Object moverSelf = null;
            for (Field f : moverTypeClass.getFields()) {
                if (f.getName().equals("SELF")) {
                    moverSelf = f.get(null);
                    break;
                }
            }
            if (moverSelf == null) throw new IllegalStateException("MoverType.SELF not found");

            Method move = findMethod(Class.forName("net.minecraft.world.entity.Entity"), "move",
                    moverTypeClass, Class.forName("net.minecraft.world.phys.Vec3"));
            if (move == null) throw new IllegalStateException("Entity#move not found");
            Constructor<?> vec3 = Class.forName("net.minecraft.world.phys.Vec3")
                    .getConstructor(double.class, double.class, double.class);

            Player bukkit = (Player) serverPlayerClass.getMethod("getBukkitEntity").invoke(sp);

            // never let the fake player burn/despawn weirdly
            bukkit.setRemoveWhenFarAway(false);
            bukkit.setPersistent(false);

            FakePlayer fp = new FakePlayer(sp, move, moverSelf, vec3, bukkit);
            // register in the server's PlayerList so the bot counts as an online
            // player everywhere: selectors (@p), command tab-completion, /list
            fp.registerInPlayerList(mcServer, bukkit);
            return fp;
        } catch (ReflectiveOperationException | RuntimeException e) {
            failed = true;
            getLoggerStatic(e);
            return null;
        }
    }

    /**
     * Adds the bot to PlayerList#players / playersByName / playersByUUID.
     * Without this the bot is invisible to Bukkit.getOnlinePlayers(), so vanilla
     * selectors (@p), command tab-completion and /list never see it.
     */
    private void registerInPlayerList(Object mcServer, Player bukkit) {
        try {
            Object list = mcServer.getClass().getMethod("getPlayerList").invoke(mcServer);
            this.playerList = list;
            Field players = findField(list.getClass(), java.util.List.class, "players");
            if (players != null) {
                @SuppressWarnings("unchecked")
                java.util.Collection<Object> c = (java.util.Collection<Object>) players.get(list);
                c.add(this.serverPlayer);
            }
            String name = bukkit.getName();
            java.util.UUID id = bukkit.getUniqueId();
            Field byName = findField(list.getClass(), java.util.Map.class, "playersByName");
            if (byName != null) {
                @SuppressWarnings("unchecked")
                java.util.Map<Object, Object> m = (java.util.Map<Object, Object>) byName.get(list);
                m.put(name, this.serverPlayer);
            }
            Field byUuid = findField(list.getClass(), java.util.Map.class, "playersByUUID");
            if (byUuid != null) {
                @SuppressWarnings("unchecked")
                java.util.Map<Object, Object> m = (java.util.Map<Object, Object>) byUuid.get(list);
                m.put(id, this.serverPlayer);
            }
        } catch (ReflectiveOperationException | RuntimeException e) {
            org.bukkit.Bukkit.getLogger().warning("[AIBots] Could not register bot in player list: " + e);
        }
    }

    private static Field findField(Class<?> clazz, Class<?> type, String preferredName) {
        for (Class<?> k = clazz; k != null; k = k.getSuperclass()) {
            for (Field f : k.getDeclaredFields()) {
                if (f.getType() == type && preferredName.equals(f.getName())) {
                    f.setAccessible(true);
                    return f;
                }
            }
        }
        for (Class<?> k = clazz; k != null; k = k.getSuperclass()) {
            for (Field f : k.getDeclaredFields()) {
                if (f.getType() == type) {
                    f.setAccessible(true);
                    return f;
                }
            }
        }
        return null;
    }

    private static void getLoggerStatic(Exception e) {
        org.bukkit.Bukkit.getLogger().severe("[AIBots] Fake players unsupported on this server build: " + e);
    }

    /**
     * Vanilla systems (clock sync, PlayerList broadcasts, ...) send packets to every
     * ServerPlayer via player.connection. A fake player has no network connection,
     * which crashes the server tick with an NPE. We attach a "black hole" connection:
     * a bare Connection (no channel) wrapped in a real ServerGamePacketListenerImpl.
     * Packets sent into it are queued and never flushed - completely harmless.
     */
    private static void attachBlackHoleConnection(Object sp, Class<?> serverPlayerClass, Object mcServer,
                                                  Class<?> mcServerClass, Class<?> gameProfileClass, Object profile) {
        try {
            Class<?> flowClass = Class.forName("net.minecraft.network.protocol.PacketFlow");
            Object serverbound = Enum.valueOf((Class<? extends Enum>) flowClass.asSubclass(Enum.class), "SERVERBOUND");

            Class<?> connectionClass = Class.forName("net.minecraft.network.Connection");
            Constructor<?> connectionCtor = null;
            for (Constructor<?> c : connectionClass.getDeclaredConstructors()) {
                Class<?>[] p = c.getParameterTypes();
                if (p.length == 1 && p[0] == flowClass) {
                    connectionCtor = c;
                    break;
                }
            }
            if (connectionCtor == null) throw new IllegalStateException("Connection ctor not found");
            connectionCtor.setAccessible(true);
            Object connection = connectionCtor.newInstance(serverbound);

            Class<?> cookieClass = Class.forName("net.minecraft.server.network.CommonListenerCookie");
            Object cookie = cookieClass.getMethod("createInitial", gameProfileClass, boolean.class)
                    .invoke(null, profile, false);

            Class<?> listenerClass = Class.forName("net.minecraft.server.network.ServerGamePacketListenerImpl");
            Constructor<?> listenerCtor = null;
            boolean takesPlayer = false;
            for (Constructor<?> c : listenerClass.getDeclaredConstructors()) {
                Class<?>[] p = c.getParameterTypes();
                // shape A: (MinecraftServer, Connection, ServerPlayer, CommonListenerCookie)
                if (p.length == 4 && p[0] == mcServerClass && p[1] == connectionClass
                        && p[2] == serverPlayerClass && p[3] == cookieClass) {
                    listenerCtor = c;
                    takesPlayer = true;
                    break;
                }
                // older shape: (MinecraftServer, Connection, CommonListenerCookie)
                if (p.length == 3 && p[0] == mcServerClass && p[1] == connectionClass && p[2] == cookieClass) {
                    listenerCtor = c;
                    break;
                }
            }
            if (listenerCtor == null) throw new IllegalStateException("ServerGamePacketListenerImpl ctor not found");
            listenerCtor.setAccessible(true);
            Object listener = takesPlayer
                    ? listenerCtor.newInstance(mcServer, connection, sp, cookie)
                    : listenerCtor.newInstance(mcServer, connection, cookie);

            Field connectionField = null;
            for (Class<?> k = serverPlayerClass; k != null && connectionField == null; k = k.getSuperclass()) {
                for (Field f : k.getDeclaredFields()) {
                    if (f.getName().equals("connection") && f.getType() == listenerClass) {
                        connectionField = f;
                        break;
                    }
                }
            }
            if (connectionField == null) throw new IllegalStateException("'connection' field not found");
            connectionField.setAccessible(true);
            connectionField.set(sp, listener);

            org.bukkit.Bukkit.getLogger().info("[AIBots] Attached black-hole connection to bot body.");
        } catch (ReflectiveOperationException | RuntimeException e) {
            org.bukkit.Bukkit.getLogger().warning("[AIBots] Could not attach dummy connection - "
                    + "vanilla broadcasts may NPE (" + e + ")");
        }
    }

    private static Class<?> mcServerClass(Class<?> serverPlayerClass) {
        // resolve net.minecraft.server.MinecraftServer from the first constructor param
        for (Constructor<?> c : serverPlayerClass.getDeclaredConstructors()) {
            Class<?>[] p = c.getParameterTypes();
            if (p.length >= 1 && p[0].getName().equals("net.minecraft.server.MinecraftServer")) {
                return p[0];
            }
        }
        throw new IllegalStateException("MinecraftServer class not found");
    }

    public Player bukkit() {
        // vanilla respawn can swap the underlying ServerPlayer - always prefer the
        // live instance the server knows by UUID, so health/position never go stale
        Player live = org.bukkit.Bukkit.getPlayer(bukkitPlayer.getUniqueId());
        if (live != null && live != bukkitPlayer) {
            bukkitPlayer = live;
        }
        return bukkitPlayer;
    }

    public Location location() {
        return bukkitPlayer.getLocation();
    }

    public boolean onGround() {
        try {
            return (boolean) serverPlayer.getClass().getMethod("onGround").invoke(serverPlayer);
        } catch (ReflectiveOperationException e) {
            return true;
        }
    }

    public void setYawPitch(float yaw, float pitch) {
        try {
            serverPlayer.getClass().getMethod("setYRot", float.class).invoke(serverPlayer, yaw);
            serverPlayer.getClass().getMethod("setXRot", float.class).invoke(serverPlayer, pitch);
        } catch (ReflectiveOperationException ignored) {
        }
    }

    /** Physics move - this is what makes the bot actually walk. */
    public void move(double dx, double dy, double dz) {
        try {
            Object vec = vec3Ctor.newInstance(dx, dy, dz);
            moveMethod.invoke(serverPlayer, moverSelf, vec);
        } catch (ReflectiveOperationException ignored) {
        }
    }

    public void swingHand() {
        try {
            Class<?> hand = Class.forName("net.minecraft.world.InteractionHand");
            Object main = hand.getField("MAIN_HAND").get(null);
            Method swing = findMethod(serverPlayer.getClass(), "swing", hand);
            if (swing != null) swing.invoke(serverPlayer, main);
        } catch (ReflectiveOperationException ignored) {
        }
    }

    public void discard() {
        // 1. scrub our PlayerList registration (players / playersByName / playersByUUID)
        if (playerList != null) {
            try {
                Field players = findField(playerList.getClass(), java.util.List.class, "players");
                if (players != null) {
                    @SuppressWarnings("unchecked")
                    java.util.Collection<Object> c = (java.util.Collection<Object>) players.get(playerList);
                    c.remove(serverPlayer);
                }
                String name = bukkitPlayer.getName();
                java.util.UUID id = bukkitPlayer.getUniqueId();
                Field byName = findField(playerList.getClass(), java.util.Map.class, "playersByName");
                if (byName != null) {
                    @SuppressWarnings("unchecked")
                    java.util.Map<Object, Object> m = (java.util.Map<Object, Object>) byName.get(playerList);
                    m.values().removeIf(v -> v == serverPlayer);
                    m.remove(name);
                }
                Field byUuid = findField(playerList.getClass(), java.util.Map.class, "playersByUUID");
                if (byUuid != null) {
                    @SuppressWarnings("unchecked")
                    java.util.Map<Object, Object> m = (java.util.Map<Object, Object>) byUuid.get(playerList);
                    m.values().removeIf(v -> v == serverPlayer);
                    m.remove(id);
                }
            } catch (ReflectiveOperationException | RuntimeException e) {
                org.bukkit.Bukkit.getLogger().warning("[AIBots] Could not scrub player list entry: " + e);
            }
        }

        // 2. remove the entity from the world with a proper removal reason
        try {
            Class<?> reasonClass = Class.forName("net.minecraft.world.entity.Entity$RemovalReason");
            Object discarded = Enum.valueOf((Class<? extends Enum>) reasonClass.asSubclass(Enum.class), "DISCARDED");
            Method remove = null;
            for (Method m : serverPlayer.getClass().getMethods()) {
                Class<?>[] p = m.getParameterTypes();
                if (p.length == 1 && p[0] == reasonClass) {
                    remove = m;
                    break;
                }
            }
            if (remove != null) {
                remove.invoke(serverPlayer, discarded);
                return; // entity destroyed - clients get the despawn packet
            }
        } catch (ReflectiveOperationException e) {
            org.bukkit.Bukkit.getLogger().warning("[AIBots] NMS discard failed: " + e);
        }

        // 3. last resort
        try {
            bukkit().remove();
        } catch (Throwable ignored) {
        }
    }

    private static void injectSkin(Class<?> gameProfileClass, Object profile, String[] tex)
            throws ReflectiveOperationException {
        Class<?> propertyClass = Class.forName("com.mojang.authlib.Property");
        Object property = tex[1] != null
                ? propertyClass.getConstructor(String.class, String.class, String.class)
                        .newInstance("textures", tex[0], tex[1])
                : propertyClass.getConstructor(String.class, String.class)
                        .newInstance("textures", tex[0]);
        Object properties = gameProfileClass.getMethod("getProperties").invoke(profile);
        properties.getClass().getMethod("put", Object.class, Object.class).invoke(properties, "textures", property);
    }

    private static Method findMethod(Class<?> clazz, String name, Class<?>... params) {
        try {
            Method m = clazz.getMethod(name, params);
            m.setAccessible(true);
            return m;
        } catch (NoSuchMethodException e) {
            return null;
        }
    }
}
