package dev.kilo.aibots.nav;

import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.util.BlockVector;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.PriorityQueue;
import java.util.Set;

/**
 * A* pathfinder over walkable block columns. The bot then physically walks
 * the path one tick at a time (gravity + jumping), never teleporting.
 */
public final class PathFinder {

    private PathFinder() {
    }

    public static List<BlockVector> find(World world, Location start, Location goal, int maxNodes) {
        int sx = start.getBlockX(), sy = start.getBlockY(), sz = start.getBlockZ();
        int gx = goal.getBlockX(), gy = goal.getBlockY(), gz = goal.getBlockZ();

        PriorityQueue<long[]> open = new PriorityQueue<>(Comparator.comparingDouble(a -> Double.longBitsToDouble(a[0])));
        Map<Long, Long> cameFrom = new HashMap<>();
        Map<Long, Double> gScore = new HashMap<>();
        Set<Long> closed = new HashSet<>();

        long startKey = key(sx, sy, sz);
        gScore.put(startKey, 0.0);
        open.add(new long[]{Double.doubleToLongBits(h(sx, sy, sz, gx, gy, gz)), startKey});

        int expanded = 0;
        while (!open.isEmpty() && expanded++ < maxNodes) {
            long[] cur = open.poll();
            long ck = cur[1];
            if (closed.contains(ck)) continue;
            closed.add(ck);

            int cx = x(ck), cy = y(ck), cz = z(ck);
            if (Math.abs(cx - gx) <= 0 && Math.abs(cz - gz) <= 0 && Math.abs(cy - gy) <= 1) {
                return reconstruct(cameFrom, ck);
            }

            for (int[] n : neighbors(world, cx, cy, cz)) {
                long nk = key(n[0], n[1], n[2]);
                if (closed.contains(nk)) continue;
                double cost = gScore.get(ck) + (n[1] != cy ? 1.4 : 1.0)
                        + ((n[0] != cx && n[2] != cz) ? 0.4 : 0.0);
                Double old = gScore.get(nk);
                if (old == null || cost < old) {
                    gScore.put(nk, cost);
                    cameFrom.put(nk, ck);
                    open.add(new long[]{Double.doubleToLongBits(cost + h(n[0], n[1], n[2], gx, gy, gz)), nk});
                }
            }
        }
        return Collections.emptyList();
    }

    private static List<int[]> neighbors(World w, int x, int y, int z) {
        List<int[]> out = new ArrayList<>();
        for (int dx = -1; dx <= 1; dx++) {
            for (int dz = -1; dz <= 1; dz++) {
                if (dx == 0 && dz == 0) continue;
                for (int dy = 1; dy >= -3; dy--) {
                    int nx = x + dx, ny = y + dy, nz = z + dz;
                    if (!walkable(w, nx, ny, nz)) continue;
                    if (dy <= 0) {
                        // require head clearance at the current level while stepping down
                        if (!passable(w, nx, y, nz) || !passable(w, nx, y + 1, nz)) continue;
                        // no tunneling through the floor edge
                        if (solid(w, nx, y, nz) && dy < 0) continue;
                    } else {
                        if (!passable(w, x, y + 2, z)) continue; // ceiling for the jump
                    }
                    out.add(new int[]{nx, ny, nz});
                    break;
                }
            }
        }
        return out;
    }

    public static boolean walkable(World w, int x, int y, int z) {
        return !solid(w, x, y, z) && !solid(w, x, y + 1, z) && solid(w, x, y - 1, z);
    }

    public static boolean solid(World w, int x, int y, int z) {
        Material t = w.getBlockAt(x, y, z).getType();
        return t.isSolid();
    }

    public static boolean passable(World w, int x, int y, int z) {
        return !w.getBlockAt(x, y, z).getType().isSolid();
    }

    /**
     * Cheap straight-line check before running A*: if the bot can walk a clear
     * line to the goal (flat ground, no walls), skip the search entirely.
     */
    static List<BlockVector> tryDirect(World w, Location start, Location goal) {
        double dx = goal.getX() - start.getX();
        double dz = goal.getZ() - start.getZ();
        double dist = Math.hypot(dx, dz);
        if (dist < 0.5 || Math.abs(goal.getY() - start.getY()) > 1) return null;
        int steps = (int) Math.ceil(dist * 2);
        for (int i = 1; i <= steps; i++) {
            double t = (double) i / steps;
            int x = (int) Math.floor(start.getX() + dx * t);
            int z = (int) Math.floor(start.getZ() + dz * t);
            int y = start.getBlockY();
            if (!walkable(w, x, y, z)) return null;
        }
        return List.of(new BlockVector(goal.getBlockX(), goal.getBlockY(), goal.getBlockZ()));
    }

    private static double h(int x, int y, int z, int gx, int gy, int gz) {        double dx = gx - x, dy = (gy - y) * 1.5, dz = gz - z;
        return Math.sqrt(dx * dx + dy * dy + dz * dz);
    }

    private static List<BlockVector> reconstruct(Map<Long, Long> cameFrom, long end) {
        Deque<BlockVector> path = new ArrayDeque<>();
        Long k = end;
        while (k != null) {
            path.addFirst(new BlockVector(x(k), y(k), z(k)));
            k = cameFrom.get(k);
        }
        return new ArrayList<>(path);
    }

    private static long key(int x, int y, int z) {
        return ((long) (x & 0x3FFFFFF) << 38) | ((long) (z & 0x3FFFFFF) << 12) | (y & 0xFFF);
    }

    private static int x(long key) {
        return (int) (key >> 38);
    }

    private static int z(long key) {
        return (int) (key << 26 >> 38);
    }

    private static int y(long key) {
        return (int) (key << 52 >> 52);
    }
}
