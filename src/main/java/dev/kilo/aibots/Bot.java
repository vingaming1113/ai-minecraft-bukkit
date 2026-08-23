package dev.kilo.aibots;

import dev.kilo.aibots.llm.LLMService;
import dev.kilo.aibots.nav.Walker;
import dev.kilo.aibots.nms.FakePlayer;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.bukkit.Bukkit;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.EnumMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ThreadLocalRandom;

/** One AI bot: a fake-player body + LLM brain + virtual inventory. */
public final class Bot {

    public record Settings(String persona, GameMode gamemode, boolean allowCommands) {
    }

    private final String name;
    private final FakePlayer body;
    private final Walker walker;
    private final Actions actions = new Actions(this);
    private final AIBotPlugin plugin;

    private Settings settings;
    private final Map<Material, Integer> inventory = new EnumMap<>(Material.class);
    private final Deque<String[]> memory = new ArrayDeque<>(); // {role, content}
    private int botChainDepth;
    private long lastReplyAt;

    Bot(AIBotPlugin plugin, String name, Location spawn, Settings settings) {
        this.plugin = plugin;
        this.name = name;
        this.settings = settings;
        this.body = FakePlayer.create(spawn, name);
        if (body == null) throw new IllegalStateException("Fake player body could not be created");
        this.walker = new Walker(body);
        walker.setOnGiveUp(msg -> speak("* " + msg));
    }

    // ---------- accessors ----------

    public String name() {
        return name;
    }

    public FakePlayer body() {
        return body;
    }

    public Walker walker() {
        return walker;
    }

    public Settings settings() {
        return settings;
    }

    public void updateSettings(Settings s) {
        this.settings = s;
    }

    public boolean isBusy() {
        return walker.isBusy();
    }

    // ---------- chat ----------

    /** Called for every chat line the bot can hear. */
    void hear(Player speaker, Component message) {
        int range = plugin.hearingRange();
        if (range > 0 && !speaker.getWorld().equals(body.bukkit().getWorld())) return;
        if (range > 0 && speaker.getLocation().distance(body.location()) > range) return;

        String senderName = speaker.getName();
        String text = PlainTextComponentSerializer.plainText().serialize(message);
        if (text.isBlank()) return;

        boolean addressedByMe = isAddressed(message);
        boolean senderIsBot = plugin.botManager().botByName(senderName) != null;

        if (!addressedByMe && plugin.mentionOnly()) return;
        if (!addressedByMe && senderIsBot) return; // bots don't jump into conversations uninvited
        if (senderIsBot) {
            if (botChainDepth >= plugin.maxBotChain()) return;
        } else {
            plugin.botManager().resetChains(); // a human spoke - reset loop protection
        }

        remember("user", senderName + " says: " + text);

        // natural-feeling delay
        long delay = ThreadLocalRandom.current().nextLong(plugin.replyDelayMinMs(), plugin.replyDelayMaxMs());
        Bukkit.getScheduler().runTaskLater(plugin, () -> think(senderIsBot), delay / 50L);
    }

    private boolean isAddressed(String message) {
        String lower = message.toLowerCase(Locale.ROOT);
        String me = name.toLowerCase(Locale.ROOT);
        return lower.contains("@" + me) || lower.startsWith(me) || lower.contains(me + ",")
                || lower.contains(me + ":") || lower.equals(me);
    }

    private synchronized void remember(String role, String content) {
        memory.addLast(new String[]{role, content});
        while (memory.size() > 16) memory.removeFirst();
    }

    /** Asks the LLM for a response and executes its actions/speech on the main thread. */
    void think(boolean fromBot) {
        long now = System.currentTimeMillis();
        if (now - lastReplyAt < 1500) return; // debounce
        lastReplyAt = now;
        if (fromBot) botChainDepth++;

        List<LLMService.Message> messages = new ArrayList<>();
        messages.add(new LLMService.Message("system", buildSystemPrompt()));
        messages.addAll(currentMemory());
        messages.add(new LLMService.Message("user", "You are " + name
                + ". Reply now: chat lines and/or '!' action lines."));

        plugin.llm().chat(messages).whenComplete((reply, err) ->
                Bukkit.getScheduler().runTask(plugin, () -> {
                    if (err != null) {
                        plugin.getLogger().warning("[" + name + "] AI error: " + err.getMessage());
                        speak("(my thoughts got cut off - AI error)");
                        return;
                    }
                    handleReply(reply == null ? "" : reply);
                }));
    }

    private synchronized List<LLMService.Message> currentMemory() {
        List<LLMService.Message> out = new ArrayList<>();
        for (String[] m : memory) out.add(new LLMService.Message(m[0], m[1]));
        return out;
    }

    private String buildSystemPrompt() {
        Location loc = body.location();
        StringBuilder sb = new StringBuilder();
        sb.append(settings.persona()).append('\n');
        sb.append("You are ").append(name)
                .append(", a real physical player in Minecraft (gamemode ")
                .append(settings.gamemode().name().toLowerCase(Locale.ROOT)).append(").\n");
        sb.append("You have a real body with legs - you WALK everywhere yourself and never teleport.\n");
        sb.append("Your position: x=").append(loc.getBlockX())
                .append(" y=").append(loc.getBlockY())
                .append(" z=").append(loc.getBlockZ()).append(". Health: ")
                .append(Math.round(body.bukkit().getHealth())).append("/20.\n");
        sb.append("Other bots online: ").append(plugin.botManager().botNamesExcluding(name))
                .append(". You may talk to them by mentioning their name.\n");
        sb.append("Inventory: ").append(inventorySummary()).append('\n');
        sb.append("""
                You answer with short chat lines and/or actions. Action lines start with '!' and are executed silently (players do not see them):
                !goto <x> <z> | <x> <y> <z> | <player>   walk somewhere with your own legs
                !follow <player>                          follow someone around
                !stop                                     stop walking/following
                !craft <item> [count]                     craft items from what you carry
                !mine                                     mine the block you look at
                !place [item]                             place a block you carry
                !give <player> <item> [count]
                !drop <item>
                !inventory                                list your inventory in chat
                """);
        if (settings.allowCommands()) {
            sb.append("!command <command>                       run a server command (you are allowed)\n");
        }
        sb.append("""
                Rules:
                - Every plain text line you write is spoken aloud in chat.
                - Use actions instead of claiming you did something; be honest about what you carry.
                - Keep each spoken line under ~15 words, casual gamer tone.
                """);
        return sb.toString();
    }

    private void handleReply(String reply) {
        StringBuilder speech = new StringBuilder();
        for (String rawLine : reply.split("\n")) {
            String line = rawLine.trim();
            if (line.isEmpty()) continue;
            if (line.startsWith("!")) {
                if (!actions.execute(line)) {
                    speak("I don't know how to do that yet.");
                }
            } else {
                if (speech.length() > 0) speech.append(' ');
                speech.append(line.replaceFirst("^" + java.util.regex.Pattern.quote(name + ":"), "").trim());
            }
        }
        if (!speech.isEmpty()) speak(speech.toString());
    }

    /** Broadcasts a chat message that looks like a normal player speaking. */
    public void speak(String text) {
        Component msg = Component.text("<" + name + "> ", NamedTextColor.WHITE)
                .append(Component.text(text, NamedTextColor.WHITE));
        Bukkit.getServer().sendMessage(msg);
    }

    public void swingBodyHand() {
        body.swingHand();
    }

    // ---------- inventory ----------

    public void giveItem(ItemStack stack) {
        if (stack.getType().isAir()) return;
        inventory.merge(stack.getType(), stack.getAmount(), Integer::sum);
    }

    public int count(Material m) {
        return inventory.getOrDefault(m, 0);
    }

    public boolean hasItem(Material m, int amount) {
        return count(m) >= amount;
    }

    public boolean hasItems(Map<Material, Integer> amounts) {
        for (var e : amounts.entrySet()) {
            if (count(e.getKey()) < e.getValue()) return false;
        }
        return true;
    }

    public void takeItems(Map<Material, Integer> amounts, int batches) {
        for (var e : amounts.entrySet()) {
            removeItem(e.getKey(), e.getValue() * batches);
        }
    }

    public void removeItem(Material m, int amount) {
        int have = count(m);
        if (have <= amount) inventory.remove(m);
        else inventory.put(m, have - amount);
    }

    public Material firstPlaceable() {
        for (Material m : inventory.keySet()) {
            if (m.isBlock() && !m.hasGravity()) return m;
        }
        return null;
    }

    public String inventorySummary() {
        if (inventory.isEmpty()) return "(empty)";
        List<String> parts = new ArrayList<>();
        for (var e : inventory.entrySet()) {
            parts.add(e.getValue() + "x " + e.getKey().name().toLowerCase(Locale.ROOT));
        }
        return String.join(", ", parts);
    }

    Map<Material, Integer> inventoryMap() {
        return inventory;
    }

    void resetChain() {
        botChainDepth = 0;
    }

    public void discard() {
        walker.stop();
        body.discard();
    }
}
