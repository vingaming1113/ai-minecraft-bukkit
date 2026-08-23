package dev.kilo.aibots;

import org.bukkit.Bukkit;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;

import java.util.EnumMap;
import java.util.Locale;
import java.util.Map;

/** Executes the "!" actions the LLM emits, against the bot's physical body. */
final class Actions {

    private final Bot bot;

    Actions(Bot bot) {
        this.bot = bot;
    }

    /** @return true if the token was a known action */
    boolean execute(String line) {
        String[] parts = line.trim().split("\\s+");
        if (parts.length == 0 || !parts[0].startsWith("!")) return false;
        String action = parts[0].substring(1).toLowerCase(Locale.ROOT);
        switch (action) {
            case "goto" -> gotoAction(parts);
            case "follow" -> followAction(parts);
            case "stop" -> bot.walker().stop();
            case "craft" -> craft(parts.length > 1 ? parts[1] : "", parts.length > 2 ? num(parts[2]) : 1);
            case "mine", "break" -> mine();
            case "place" -> place(parts.length > 1 ? parts[1] : "");
            case "give" -> give(parts);
            case "drop" -> drop(parts.length > 1 ? parts[1] : "", parts.length > 2 ? num(parts[2]) : 1);
            case "inventory" -> bot.speak("I'm carrying: " + bot.inventorySummary());
            case "tp", "teleport" -> tp(parts);
            case "command" -> command(line.substring(line.indexOf(' ') + 1));
            default -> {
                return false;
            }
        }
        return true;
    }

    private void gotoAction(String[] parts) {
        try {
            Location cur = bot.body().location();
            Location target;
            if (parts.length >= 3) {
                int x = Integer.parseInt(parts[1]);
                int z = Integer.parseInt(parts[parts.length - 1]);
                int y = parts.length >= 4 ? Integer.parseInt(parts[2]) : cur.getBlockY();
                target = new Location(cur.getWorld(), x + 0.5, y, z + 0.5);
            } else if (parts.length == 2) {
                Player p = Bukkit.getPlayerExact(parts[1]);
                if (p == null) {
                    bot.speak("I can't find anyone called " + parts[1] + " to walk to.");
                    return;
                }
                target = p.getLocation();
            } else {
                bot.speak("Usage: !goto <x> <z> or !goto <player>");
                return;
            }
            bot.walker().walkTo(target);
        } catch (NumberFormatException e) {
            bot.speak("Those aren't coordinates I understand.");
        }
    }

    private void followAction(String[] parts) {
        if (parts.length < 2) return;
        Player p = Bukkit.getPlayerExact(parts[1]);
        if (p == null || !p.isOnline()) {
            bot.speak("I can't see " + parts[1] + " right now.");
            return;
        }
        bot.walker().follow(p);
        bot.speak("Following " + p.getName() + ".");
    }

    private void craft(String materialName, int count) {
        Material result = Material.matchMaterial(materialName);
        if (result == null) {
            bot.speak("I don't know how to craft " + materialName + ".");
            return;
        }
        boolean creative = bot.settings().gamemode() == GameMode.CREATIVE;
        for (var recipe : recipesFor(result)) {
            Map<Material, Integer> needed = ingredientsOf(recipe);
            if (needed.isEmpty()) continue;
            if (!creative && !bot.hasItems(needed)) continue;
            int batches = creative ? count : Math.min(count, maxBatches(needed));
            if (batches <= 0) continue;
            if (!creative) bot.takeItems(needed, batches);
            ItemStack out = recipe.getResult().clone();
            out.setAmount(Math.min(out.getAmount() * batches, out.getMaxStackSize() * 4));
            bot.giveItem(out);
            bot.speak("Crafted " + describe(out) + ".");
            return;
        }
        bot.speak(creative
                ? ("Hmm, I don't know a recipe for " + pretty(result) + ".")
                : ("I can't craft " + pretty(result) + " with what I'm carrying: " + bot.inventorySummary()));
    }

    private void mine() {
        Block target = bot.body().bukkit().getTargetBlockExact(5);
        if (target == null || target.getType().isAir()) {
            bot.speak("There's nothing in reach to mine.");
            return;
        }
        bot.swingBodyHand();
        boolean survival = bot.settings().gamemode() == GameMode.SURVIVAL;
        if (survival) {
            for (ItemStack drop : target.getDrops()) {
                bot.giveItem(drop);
            }
        }
        target.setType(Material.AIR);
        bot.speak("Mined the " + pretty(target.getType()) + (survival ? ", pocketed it." : "."));
    }

    private void place(String materialName) {
        BlockFace face = bot.body().bukkit().getTargetBlockFace(5);
        Block against = bot.body().bukkit().getTargetBlockExact(5);
        if (against == null || face == null) {
            bot.speak("Nothing in reach to place against.");
            return;
        }
        Block faceBlock = against.getRelative(face);
        Material mat = Material.matchMaterial(materialName);
        if (mat == null || mat.isAir()) {
            mat = bot.firstPlaceable();
            if (mat == null) {
                bot.speak("I have no blocks to place.");
                return;
            }
        }
        if (!faceBlock.getType().isAir()) {
            bot.speak("No room there.");
            return;
        }
        if (bot.settings().gamemode() != GameMode.CREATIVE && !bot.hasItem(mat, 1)) {
            bot.speak("I don't have any " + pretty(mat) + ".");
            return;
        }
        bot.swingBodyHand();
        faceBlock.setType(mat);
        if (bot.settings().gamemode() != GameMode.CREATIVE) bot.removeItem(mat, 1);
        bot.speak("Placed " + pretty(mat) + ".");
    }

    private void give(String[] parts) {
        if (parts.length < 3) return;
        Player p = Bukkit.getPlayerExact(parts[1]);
        Material m = Material.matchMaterial(parts[2]);
        if (p == null || m == null) {
            bot.speak("Can't find that player or item.");
            return;
        }
        int amount = parts.length > 3 ? num(parts[3]) : 1;
        if (bot.settings().gamemode() != GameMode.CREATIVE && !bot.hasItem(m, amount)) {
            bot.speak("I only wish I had " + amount + "x " + pretty(m) + ". Inventory: " + bot.inventorySummary());
            return;
        }
        if (bot.settings().gamemode() != GameMode.CREATIVE) bot.removeItem(m, amount);
        p.getInventory().addItem(new ItemStack(m, Math.min(amount, m.getMaxStackSize())));
        bot.speak("Gave " + amount + "x " + pretty(m) + " to " + p.getName() + ".");
    }

    private void drop(String materialName, int count) {
        Material m = Material.matchMaterial(materialName);
        if (m == null) return;
        if (bot.settings().gamemode() != GameMode.CREATIVE) {
            if (!bot.hasItem(m, count)) return;
            bot.removeItem(m, count);
        }
        Location loc = bot.body().location().add(0, 1, 0);
        bot.body().bukkit().getWorld().dropItemNaturally(loc, new ItemStack(m, count));
        bot.speak("Dropped " + count + "x " + pretty(m) + ".");
    }

    private void tp(String[] parts) {
        if (!bot.settings().allowCommands()) {
            bot.speak("I'm not allowed to use commands, so no teleporting for me.");
            return;
        }
        if (parts.length < 2) return;
        try {
            Location cur = bot.body().location();
            Location target;
            Player p = Bukkit.getPlayerExact(parts[1]);
            if (p != null && p.isOnline()) {
                target = p.getLocation();
            } else if (parts.length >= 4) {
                target = new Location(cur.getWorld(), Double.parseDouble(parts[1]),
                        Double.parseDouble(parts[2]), Double.parseDouble(parts[3]));
            } else if (parts.length == 3) {
                target = new Location(cur.getWorld(), Double.parseDouble(parts[1]),
                        cur.getBlockY(), Double.parseDouble(parts[2]));
            } else {
                bot.speak("Usage: !tp <x> <y> <z> or !tp <player>");
                return;
            }
            bot.walker().stop();
            bot.teleport(target);
            bot.speak("Teleported to " + target.getBlockX() + " " + target.getBlockY() + " " + target.getBlockZ() + ".");
        } catch (NumberFormatException e) {
            bot.speak("Those aren't coordinates I understand.");
        }
    }

    private void command(String cmd) {
        if (!bot.settings().allowCommands()) {
            bot.speak("I'm not allowed to run commands.");
            return;
        }
        Bukkit.dispatchCommand(Bukkit.getConsoleSender(), cmd);
        bot.speak("Done.");
    }

    // ---------- helpers ----------

    private java.util.List<org.bukkit.inventory.Recipe> recipesFor(Material result) {
        java.util.List<org.bukkit.inventory.Recipe> out = new java.util.ArrayList<>();
        for (var it = Bukkit.recipeIterator(); it.hasNext(); ) {
            var r = it.next();
            if (r.getResult().getType() == result
                    && (r instanceof org.bukkit.inventory.ShapedRecipe || r instanceof org.bukkit.inventory.ShapelessRecipe)) {
                out.add(r);
            }
        }
        return out;
    }

    private Map<Material, Integer> ingredientsOf(org.bukkit.inventory.Recipe recipe) {
        Map<Material, Integer> needed = new EnumMap<>(Material.class);
        if (recipe instanceof org.bukkit.inventory.ShapedRecipe shaped) {
            for (var choice : shaped.getChoiceMap().values()) {
                addChoice(needed, choice);
            }
        } else if (recipe instanceof org.bukkit.inventory.ShapelessRecipe shapeless) {
            for (var choice : shapeless.getChoiceList()) {
                addChoice(needed, choice);
            }
        }
        return needed;
    }

    private void addChoice(Map<Material, Integer> needed, org.bukkit.inventory.RecipeChoice choice) {
        if (choice instanceof org.bukkit.inventory.RecipeChoice.MaterialChoice mc) {
            for (Material m : mc.getChoices()) {
                if (!m.isAir()) {
                    needed.merge(m, 1, Integer::sum);
                    break; // one unit per recipe slot
                }
            }
        }
    }

    private int maxBatches(Map<Material, Integer> needed) {
        int min = Integer.MAX_VALUE;
        for (var e : needed.entrySet()) {
            min = Math.min(min, bot.count(e.getKey()) / e.getValue());
        }
        return min == Integer.MAX_VALUE ? 0 : min;
    }

    private int num(String s) {
        try {
            return Math.max(1, Integer.parseInt(s));
        } catch (NumberFormatException e) {
            return 1;
        }
    }

    private String pretty(Material m) {
        return m.name().toLowerCase(Locale.ROOT).replace('_', ' ');
    }

    private String describe(ItemStack stack) {
        return stack.getAmount() + "x " + pretty(stack.getType());
    }
}
