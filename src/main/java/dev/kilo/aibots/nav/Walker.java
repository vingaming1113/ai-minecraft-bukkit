package dev.kilo.aibots.nav;

import dev.kilo.aibots.nms.FakePlayer;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.entity.Player;
import org.bukkit.util.BlockVector;
import org.bukkit.util.Vector;

import java.util.List;
import java.util.UUID;
import java.util.function.Consumer;

/**
 * Per-tick movement controller. Steers the fake player's ServerPlayer body along
 * an A* path using real physics: horizontal acceleration, gravity, auto-jump.
 * The bot never teleports while walking.
 */
public final class Walker {

    public static final double WALK_SPEED = 0.21;

    private final FakePlayer body;

    private List<BlockVector> path = List.of();
    private int pathIndex;
    private Location goal;
    private UUID followTarget;
    private int repathTimer;
    private int stuckTicks;
    private Location lastProgress;
    private int repathFails;
    private double vy;

    /** Called when the bot gives up reaching its goal. */
    private Consumer<String> onGiveUp;

    public Walker(FakePlayer body) {
        this.body = body;
    }

    public void setOnGiveUp(Consumer<String> onGiveUp) {
        this.onGiveUp = onGiveUp;
    }

    public void walkTo(Location loc) {
        this.goal = loc.clone();
        this.followTarget = null;
        repath();
    }

    public void follow(Player player) {
        this.followTarget = player.getUniqueId();
        this.goal = player.getLocation();
        repath();
    }

    public void stop() {
        goal = null;
        followTarget = null;
        path = List.of();
        pathIndex = 0;
        stuckTicks = 0;
        repathFails = 0;
    }

    public boolean isBusy() {
        return goal != null;
    }

    public Location goal() {
        return goal;
    }

    private void repath() {
        if (goal == null) return;
        World w = body.bukkit().getWorld();
        Location cur = body.location();
        List<BlockVector> p;
        // fast path: straight walkable line beats running A*
        List<BlockVector> direct = PathFinder.tryDirect(w, cur, goal);
        if (direct != null) {
            p = direct;
        } else {
            p = PathFinder.find(w, cur, goal, 6000);
            if (p.isEmpty()) {
                p = List.of(new BlockVector(goal.getBlockX(), goal.getBlockY(), goal.getBlockZ()));
            }
        }
        path = p;
        pathIndex = 0;
        repathTimer = 40;
        stuckTicks = 0;
    }

    /** Runs every tick from the main thread. */
    public void tick() {
        // idle + grounded = nothing to simulate at all (zero-cost tick)
        if (goal == null && body.onGround() && vy == 0) return;

        Location cur = body.location();
        World w = cur.getWorld();

        // refresh follow targets periodically
        if (followTarget != null && --repathTimer <= 0) {
            Player t = org.bukkit.Bukkit.getPlayer(followTarget);
            if (t == null || !t.isOnline()) {
                stop();
                if (onGiveUp != null) onGiveUp.accept("lost the player I was following");
                return;
            }
            goal = t.getLocation();
            repath();
        } else if (goal != null && --repathTimer <= 0) {
            repath();
        }

        if (goal == null) {
            if (body.onGround()) {
                vy = 0;
            } else {
                vy = (vy - 0.08) * 0.98;
                body.move(0, vy, 0);
            }
            return;
        }

        // advance waypoints
        BlockVector target = currentWaypoint();
        if (target == null) {
            // reached the end of the path - check final arrival
            if (cur.toVector().distance(new Vector(goal.getX(), goal.getY(), goal.getZ())) < 2.0) {
                stop();
                return;
            }
            repath();
            return;
        }

        Vector center = new Vector(target.getX() + 0.5, target.getY(), target.getZ() + 0.5);
        Vector flat = new Vector(center.getX() - cur.getX(), 0, center.getZ() - cur.getZ());
        double dist = flat.length();

        if (dist < 0.45 && Math.abs(center.getY() - cur.getY()) < 1.6) {
            pathIndex++; // consume waypoint
            return;
        }
        if (dist > 0.01) flat.normalize();

        // stuck detection
        if (lastProgress == null) lastProgress = cur.clone();
        if (stuckTicks++ >= 20) {
            if (lastProgress.distance(cur) < 0.3) {
                repathFails++;
                if (repathFails >= 3) {
                    stop();
                    if (onGiveUp != null) onGiveUp.accept("I'm stuck and can't find a way through");
                    return;
                }
                repath();
            }
            stuckTicks = 0;
            lastProgress = cur.clone();
        }

        // physics: gravity
        double dy;
        if (body.onGround()) {
            dy = 0;
            // auto-jump when the next step is blocked by a 1-high obstacle
            int aheadX = (int) Math.floor(cur.getX() + flat.getX() * 1.1);
            int aheadZ = (int) Math.floor(cur.getZ() + flat.getZ() * 1.1);
            int feetY = cur.getBlockY();
            if (PathFinder.solid(w, aheadX, feetY, aheadZ) && !PathFinder.solid(w, aheadX, feetY + 1, aheadZ)
                    && !PathFinder.solid(w, aheadX, feetY + 2, aheadZ)) {
                vy = 0.42;
                dy = vy;
                body.swingHand();
            }
        } else {
            vy = (vy - 0.08) * 0.98;
            dy = vy;
        }

        // face the direction of travel
        float yaw = (float) Math.toDegrees(Math.atan2(-flat.getX(), flat.getZ()));
        body.setYawPitch(yaw, 0);

        double speed = Math.min(WALK_SPEED, Math.max(0.05, dist));
        body.move(flat.getX() * speed, dy, flat.getZ() * speed);
    }

    private BlockVector currentWaypoint() {
        while (pathIndex < path.size()) {
            BlockVector v = path.get(pathIndex);
            Location cur = body.location();
            double dxz = Math.hypot(v.getX() + 0.5 - cur.getX(), v.getZ() + 0.5 - cur.getZ());
            if (dxz > 0.45 || Math.abs(v.getY() - cur.getY()) >= 1.6) {
                return v;
            }
            pathIndex++;
        }
        return null;
    }
}
