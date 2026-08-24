package dev.kilo.aibots;

import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.Biome;
import org.bukkit.entity.Monster;
import org.bukkit.entity.Player;

import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

/**
 * Cheap surroundings scan so the AI knows what's around its body:
 * terrain mix, trees, water, ores, animals/hostiles, players, time, biome.
 * Runs rarely (once per autonomy decision), so the cost is irrelevant.
 */
final class EnvironmentScanner {

    private EnvironmentScanner() {
    }

    static String describe(Player p) {
        Location loc = p.getLocation();
        World w = loc.getWorld();

        Map<String, Integer> counts = new LinkedHashMap<>();
        String treeDir = null;
        int waterDir = -1;
        int r = 6;

        for (int dx = -r; dx <= r; dx += 2) {
            for (int dz = -r; dz <= r; dz += 2) {
                for (int dy = -2; dy <= 3; dy++) {
                    Material t = w.getBlockAt(loc.getBlockX() + dx, loc.getBlockY() + dy, loc.getBlockZ() + dz).getType();
                    if (t.isAir()) continue;
                    counts.merge(categorize(t), 1, Integer::sum);
                    if (treeDir == null && t.name().endsWith("_LOG")) {
                        treeDir = compass(dx, dz);
                    }
                    if (waterDir < 0 && t == Material.WATER) {
                        waterDir = Math.abs(dx) + Math.abs(dz);
                    }
                }
            }
        }

        StringBuilder sb = new StringBuilder();
        sb.append("- Terrain: ");
        if (counts.isEmpty()) sb.append("open flat area");
        else counts.forEach((k, v) -> sb.append(v).append("x ").append(k).append(", "));
        sb.append('\n');
        if (treeDir != null) sb.append("- Trees spotted to the ").append(treeDir).append(".\n");

        // people
        Player nearest = null;
        double best = Double.MAX_VALUE;
        for (Player o : p.getServer().getOnlinePlayers()) {
            if (o.equals(p) || !o.getWorld().equals(p.getWorld())) continue;
            double d = o.getLocation().distance(loc);
            if (d < best) {
                best = d;
                nearest = o;
            }
        }
        if (nearest != null) {
            sb.append("- Nearest player: ").append(nearest.getName()).append(" (")
                    .append((int) best).append(" blocks away).\n");
        } else {
            sb.append("- No players online.\n");
        }

        int hostiles = 0;
        for (var e : p.getNearbyEntities(12, 8, 12)) {
            if (e instanceof Monster) hostiles++;
        }
        if (hostiles > 0) sb.append("- ").append(hostiles).append(" hostile mobs nearby!\n");

        long t = w.getTime();
        sb.append("- Time: ").append(t < 12300 || t > 23400 ? "day" : "night")
                .append(", biome: ").append(loc.getBlock().getBiome().name().toLowerCase(Locale.ROOT))
                .append(".\n");
        return sb.toString();
    }

    private static String categorize(Material t) {
        String n = t.name();
        if (n.endsWith("_LOG") || t == Material.BAMBOO_BLOCK) return "wood logs";
        if (n.endsWith("_LEAVES")) return "tree leaves";
        if (n.endsWith("_ORE")) return "ore";
        if (n.endsWith("_GRASS") || t == Material.GRASS_BLOCK || t == Material.DIRT || t == Material.PODZOL) return "grass/dirt";
        if (t == Material.STONE || n.endsWith("_DEEPSLATE")) return "stone";
        if (t == Material.WATER) return "water";
        if (t == Material.SAND || t == Material.RED_SAND) return "sand";
        if (t == Material.WHEAT || t == Material.CARROTS || t == Material.POTATOES || t == Material.BEETROOTS) return "crops";
        if (t == Material.CRAFTING_TABLE || t == Material.FURNACE || t == Material.CHEST) return "player-made utility block";
        if (t == Material.TORCH || n.endsWith("_PLANKS")) return "wood products";
        return n.toLowerCase(Locale.ROOT);
    }

    private static String compass(int dx, int dz) {
        if (Math.abs(dx) > Math.abs(dz)) return dx > 0 ? "east" : "west";
        return dz > 0 ? "south" : "north";
    }
}
