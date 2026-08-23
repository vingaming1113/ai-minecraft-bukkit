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
    private final Player bukkitPlayer;

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
            Object profile = gameProfileClass.getConstructor(UUID.class, String.class)
                    .newInstance(UUID.randomUUID(), name);

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

            return new FakePlayer(sp, move, moverSelf, vec3, bukkit);
        } catch (ReflectiveOperationException | RuntimeException e) {
            failed = true;
            getLoggerStatic(e);
            return null;
        }
    }

    private static void getLoggerStatic(Exception e) {
        org.bukkit.Bukkit.getLogger().severe("[AIBots] Fake players unsupported on this server build: " + e);
    }

    public Player bukkit() {
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
            if (remove != null) remove.invoke(serverPlayer, discarded);
            else bukkitPlayer.remove();
        } catch (ReflectiveOperationException e) {
            bukkitPlayer.remove();
        }
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
