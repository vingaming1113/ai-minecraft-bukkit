package dev.kilo.aibots;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabExecutor;
import org.bukkit.entity.Player;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;

public final class AIBotCommand implements TabExecutor {

    private final AIBotPlugin plugin;

    public AIBotCommand(AIBotPlugin plugin) {
        this.plugin = plugin;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command cmd, String label, String[] args) {
        if (args.length == 0) {
            help(sender, label);
            return true;
        }
        switch (args[0].toLowerCase(Locale.ROOT)) {
            case "spawn" -> spawn(sender, args);
            case "remove" -> remove(sender, args);
            case "list" -> list(sender);
            case "say" -> say(sender, args);
            case "stop" -> stop(sender, args);
            case "skin" -> skin(sender, args);
            case "reload" -> reload(sender);
            case "info" -> info(sender, args);
            default -> help(sender, label);
        }
        return true;
    }

    private void spawn(CommandSender sender, String[] args) {
        if (args.length < 2) {
            err(sender, "Usage: /aibot spawn <name> [skin:<player>] [model:<model>] [gamemode:survival|creative] [commands:true|false] [persona...]");
            return;
        }
        String name = BotManager.sanitizeBotName(args[1]);
        if (name == null) {
            err(sender, "Invalid bot name - use 1-16 letters, numbers or underscores.");
            return;
        }
        if (!name.equals(args[1])) {
            ok(sender, "Bot name adjusted to '" + name + "' (max 16 chars, [A-Za-z0-9_] only).");
        }
        GameMode gm = plugin.defaultGamemode();
        boolean allowCmds = plugin.defaultAllowCommands();
        String skinInput = null;
        String model = null;
        StringBuilder persona = new StringBuilder(plugin.defaultPersona());
        for (int i = 2; i < args.length; i++) {
            if (args[i].toLowerCase(Locale.ROOT).startsWith("gamemode:")) {
                gm = parseGamemode(args[i].substring(9));
            } else if (args[i].equalsIgnoreCase("creative")) {
                gm = GameMode.CREATIVE;
            } else if (args[i].equalsIgnoreCase("survival")) {
                gm = GameMode.SURVIVAL;
            } else if (args[i].toLowerCase(Locale.ROOT).startsWith("commands:")) {
                allowCmds = Boolean.parseBoolean(args[i].substring(9));
            } else if (args[i].toLowerCase(Locale.ROOT).startsWith("skin:")) {
                skinInput = args[i].substring(5);
            } else if (args[i].toLowerCase(Locale.ROOT).startsWith("model:")) {
                model = args[i].substring(6);
            } else {
                if (persona.length() > 0) persona.append(' ');
                persona.append(args[i]);
            }
        }
        Location loc = sender instanceof Player p ? p.getLocation() : plugin.getServer().getWorlds().get(0).getSpawnLocation();
        final Bot.Settings settings = new Bot.Settings(persona.toString(), gm, allowCmds, model);
        final String finalSkinInput = skinInput;
        ok(sender, "Resolving skin & spawning " + name + "...");
        plugin.botManager().resolveAndSpawn(name, loc, settings, skinInput, bot -> {
            if (bot == null) err(sender, "A bot with that name already exists.");
            else {
                bot.setSkinInput(finalSkinInput);
                ok(sender, "Spawned bot " + name + " (" + settings.gamemode().name().toLowerCase(Locale.ROOT)
                        + ", commands: " + (settings.allowCommands() ? "on" : "off")
                        + ", skin: " + (finalSkinInput != null ? "custom" : "default") + ").");
            }
        });
    }

    private void skin(CommandSender sender, String[] args) {
        if (args.length < 3) {
            err(sender, "Usage: /aibot skin <botName> <playerName|base64Texture>");
            return;
        }
        Bot old = plugin.botManager().botByName(args[1]);
        if (old == null) {
            err(sender, "No such bot.");
            return;
        }
        Location loc = old.body().location();
        Bot.Settings settings = old.settings();
        Map<org.bukkit.Material, Integer> inv = new java.util.LinkedHashMap<>(old.inventoryMap());
        String skinInput = args[2];
        plugin.botManager().remove(old.name());
        plugin.botManager().resolveAndSpawn(old.name(), loc, settings, skinInput, bot -> {
            if (bot == null) {
                err(sender, "Re-spawn failed - try /aibot spawn.");
                return;
            }
            bot.setSkinInput(skinInput);
            inv.forEach((m, n) -> bot.giveItem(new org.bukkit.inventory.ItemStack(m, n)));
            ok(sender, "Applied new skin to " + old.name() + ".");
        });
    }

    private void remove(CommandSender sender, String[] args) {
        if (args.length < 2) {
            err(sender, "Usage: /aibot remove <name>");
            return;
        }
        ok(sender, plugin.botManager().remove(args[1]) ? "Removed " + args[1] + "." : "No such bot.");
    }

    private void list(CommandSender sender) {
        var all = plugin.botManager().all();
        if (all.isEmpty()) {
            ok(sender, "No bots online. Use /aibot spawn <name>.");
            return;
        }
        for (Bot b : all) {
            Location l = b.body().location();
            sender.sendMessage(Component.text(b.name() + " - " + b.settings().gamemode().name().toLowerCase(Locale.ROOT)
                    + " at " + l.getBlockX() + " " + l.getBlockY() + " " + l.getBlockZ()
                    + (b.isBusy() ? " (walking)" : ""), NamedTextColor.YELLOW));
        }
    }

    private void say(CommandSender sender, String[] args) {
        if (args.length < 3) {
            err(sender, "Usage: /aibot say <botName> <message...>");
            return;
        }
        Bot bot = plugin.botManager().botByName(args[1]);
        if (bot == null) {
            err(sender, "No such bot.");
            return;
        }
        String message = String.join(" ", List.of(args).subList(2, args.length));
        String speaker = sender instanceof Player p ? p.getName() : "Server";
        bot.hearDirect(speaker + " says: " + message);
        ok(sender, "Told " + bot.name() + ".");
    }

    private void stop(CommandSender sender, String[] args) {
        Bot bot = args.length >= 2 ? plugin.botManager().botByName(args[1]) : null;
        if (bot == null) {
            err(sender, "Usage: /aibot stop <name>");
            return;
        }
        bot.walker().stop();
        ok(sender, bot.name() + " stopped.");
    }

    private void info(CommandSender sender, String[] args) {
        Bot bot = args.length >= 2 ? plugin.botManager().botByName(args[1]) : null;
        if (bot == null) {
            err(sender, "Usage: /aibot info <name>");
            return;
        }
        Location l = bot.body().location();
        sender.sendMessage(Component.text(bot.name(), NamedTextColor.GOLD));
        sender.sendMessage(Component.text("Persona: " + bot.settings().persona()));
        sender.sendMessage(Component.text("Gamemode: " + bot.settings().gamemode()
                + " | Commands: " + (bot.settings().allowCommands() ? "allowed" : "blocked")
                + " | Model: " + (bot.settings().model() != null ? bot.settings().model() : "(global)")));
        sender.sendMessage(Component.text("Position: " + l.getBlockX() + " " + l.getBlockY() + " " + l.getBlockZ()));
        sender.sendMessage(Component.text("Inventory: " + bot.inventorySummary()));
    }

    private void reload(CommandSender sender) {
        plugin.reloadSettings();
        ok(sender, "Reloaded config (bots unchanged).");
    }

    private void help(CommandSender sender, String label) {
        sender.sendMessage(Component.text("/" + label + " spawn <name> [skin:<player>] [model:<model>] [survival|creative] [commands:true|false] [persona...]", NamedTextColor.AQUA));
        sender.sendMessage(Component.text("/" + label + " skin <name> <playerName|base64Texture>", NamedTextColor.AQUA));
        sender.sendMessage(Component.text("/" + label + " remove|list|say|stop|info|reload", NamedTextColor.AQUA));
    }

    private GameMode parseGamemode(String s) {
        try {
            return GameMode.valueOf(s.toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            return plugin.defaultGamemode();
        }
    }

    private void ok(CommandSender s, String msg) {
        s.sendMessage(Component.text("[AIBots] " + msg, NamedTextColor.GREEN));
    }

    private void err(CommandSender s, String msg) {
        s.sendMessage(Component.text("[AIBots] " + msg, NamedTextColor.RED));
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command cmd, String label, String[] args) {
        List<String> out = new ArrayList<>();
        if (args.length == 1) {
            out.addAll(List.of("spawn", "remove", "list", "say", "stop", "info", "reload"));
        } else if (args.length >= 2 && !args[0].equals("spawn")) {
            plugin.botManager().all().forEach(b -> out.add(b.name()));
        }
        return out.stream().filter(s -> s.toLowerCase(Locale.ROOT).startsWith(args[args.length - 1].toLowerCase(Locale.ROOT))).toList();
    }
}
