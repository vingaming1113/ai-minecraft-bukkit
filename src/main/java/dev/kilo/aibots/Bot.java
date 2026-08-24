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

    public record Settings(String persona, GameMode gamemode, boolean allowCommands, String model) {

        public Settings(String persona, GameMode gamemode, boolean allowCommands) {
            this(persona, gamemode, allowCommands, null);
        }
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
    private volatile boolean thinking;
    private long nextAutonomyAt;

    Bot(AIBotPlugin plugin, String name, Location spawn, Settings settings) {
        this(plugin, name, spawn, settings, null);
    }

    Bot(AIBotPlugin plugin, String name, Location spawn, Settings settings, String[] skinTextures) {
        this.plugin = plugin;
        this.name = name;
        this.settings = settings;
        this.body = FakePlayer.create(spawn, name, skinTextures);
        if (body == null) throw new IllegalStateException("Fake player body could not be created");
        this.walker = new Walker(body);
        walker.setOnGiveUp(msg -> speak("* " + msg));
    }

    // ---------- skin ----------

    private String skinInput;

    public String skinInput() {
        return skinInput;
    }

    public void setSkinInput(String skinInput) {
        this.skinInput = skinInput;
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

    /** Called for every chat line the bot can hear - Minecraft chat is global. */
    void hear(Player speaker, Component message) {
        String senderName = speaker.getName();
        String text = PlainTextComponentSerializer.plainText().serialize(message);
        if (text.isBlank()) return;
        if (text.length() > 240) text = text.substring(0, 240);

        boolean addressedByMe = isAddressed(text);
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
        Bukkit.getScheduler().runTaskLater(plugin, () -> think(senderIsBot, null), delay / 50L);
    }

    /** Direct prompt via /aibot say - always answered. */
    public void hearDirect(String text) {
        remember("user", text);
        plugin.botManager().resetChains();
        long delay = ThreadLocalRandom.current().nextLong(plugin.replyDelayMinMs(), plugin.replyDelayMaxMs());
        Bukkit.getScheduler().runTaskLater(plugin, this::thinkNow, delay / 50L);
    }

    private void thinkNow() {
        think(false, null);
    }

    private boolean isAddressed(String message) {
        // whole-word match anywhere in the sentence, so "@Alex", "alex," and
        // "ask alex or steve" all work - every mentioned bot may answer at once
        String me = java.util.regex.Pattern.quote(name.toLowerCase(Locale.ROOT));
        return java.util.regex.Pattern.compile("(^|\\W)" + me + "\\b")
                .matcher(message.toLowerCase(Locale.ROOT)).find();
    }

    private synchronized void remember(String role, String content) {
        memory.addLast(new String[]{role, content});
        while (memory.size() > 12) memory.removeFirst();
    }

    /**
     * Asks the LLM for a response and executes its actions/speech on the main thread.
     * ephemeralUserLine (if any) is shown to the model for THIS reply only and not
     * stored in memory - used for autonomy scans so they don't pollute history.
     */
    void think(boolean fromBot, String ephemeralUserLine) {
        long now = System.currentTimeMillis();
        if (thinking || now - lastReplyAt < 1500) return; // debounce
        lastReplyAt = now;
        thinking = true;
        if (fromBot) botChainDepth++;

        List<LLMService.Message> messages = new ArrayList<>();
        messages.add(new LLMService.Message("system", buildSystemPrompt()));
        messages.addAll(currentMemory());
        String closing = ephemeralUserLine != null
                ? ephemeralUserLine + "\nReply now: chat lines and/or '!' action lines."
                : "You are " + name + ". Reply now: chat lines and/or '!' action lines.";
        messages.add(new LLMService.Message("user", closing));

        plugin.llm().chat(messages, settings.model()).whenComplete((reply, err) ->
                Bukkit.getScheduler().runTask(plugin, () -> {
                    thinking = false;
                    if (err != null) {
                        plugin.getLogger().warning("[" + name + "] AI error: " + err.getMessage());
                        speak("(my thoughts got cut off - AI error)");
                        return;
                    }
                    handleReply(reply == null ? "" : reply);
                }));
    }

    /** Stores the bot's own reply as an assistant turn so history reads correctly. */
    private void rememberOwnReply(String reply) {
        String clean = reply.replace("(my thoughts got cut off - AI error)", "").trim();
        if (clean.isBlank()) return;
        if (clean.length() > 280) clean = clean.substring(0, 280);
        remember("assistant", clean);
    }

    /**
     * Autonomy: when idle, periodically scans the surroundings and lets the AI
     * decide what to do - gather wood, build, explore, chat with someone, or ask
     * a player if it can come join them (civilization).
     */
    void tryAutonomy(long now) {
        long interval = plugin.autonomyIntervalMs();
        if (interval <= 0 || walker.isBusy() || thinking) return;
        if (now < nextAutonomyAt) return;
        nextAutonomyAt = now + interval + ThreadLocalRandom.current().nextLong(interval / 2);

        String env = EnvironmentScanner.describe(body.bukkit());
        StringBuilder prompt = new StringBuilder("[system] You have free time right now. Your surroundings:\n")
                .append(env)
                .append("\nDecide what to do next, like a real player would: gather resources (!break), craft (!craft), build (!build), or explore (!goto random coords). ")
                .append("You may also just start a chat with someone by mentioning their name. ");
        boolean hasOthers = plugin.botManager().botNamesExcluding(name) != "(none)";
        if (hasOthers) prompt.append("Another bot is online - you can talk to them too. ");
        prompt.append("If you feel far from civilization, ASK a player in chat if you can come to them - if they say yes, !goto them.");
        think(false, prompt.toString());
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
                .append(Math.round(body.bukkit().getHealth())).append("/20 (you slowly regenerate over time, and respawn at spawn if you die).\n");
        sb.append("Other bots online: ").append(plugin.botManager().botNamesExcluding(name))
                .append(". You may talk to them by mentioning their name.\n");
        sb.append("Inventory: ").append(inventorySummary()).append('\n');
        sb.append("""
                You answer with short chat lines and/or actions. Action lines start with '!' and are executed silently (players do not see them):
                !goto <x> <z> | <x> <y> <z> | <player>   walk somewhere with your own legs
                !follow <player>                          follow someone around
                !stop                                     stop walking/following
                !break <block> [count]                    find and break up to count blocks nearby (e.g. oak_log, stone, iron_ore) and pocket the drops - this is how you gather wood and mine
                !break                                    break the block you are looking at
                !craft <item> [count]                     craft items from what you carry
                !place [item]                             place a block you carry
                !build                                    build a tiny shelter out of blocks you carry
                !give <player> <item> [count]
                !drop <item>
                !inventory                                list your inventory in chat
                """);
        if (settings.allowCommands()) {
            sb.append("!command <command>                       run a server command (you are allowed)\n");
            sb.append("!tp <x> <y> <z> | <player>                teleport (you are allowed to use commands)\n");
        }
        sb.append("""
                Rules:
                - Every plain text line you write is spoken aloud in chat.
                - You live your own life like a real player: when idle, gather wood with !break, craft planks/tools, build, or explore.
                - To seek civilization, ask another player in chat if you can come to them; if they agree, walk (!goto) to them.
                - Use actions instead of claiming you did something; be honest about what you carry.
                - Keep each spoken line under ~15 words, casual gamer tone.
                - Older messages are just context: never repeat things you already said, and only respond to the NEWEST message.
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
        if (!speech.isEmpty()) {
            speak(speech.toString());
            rememberOwnReply(speech.toString());
        }
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

    public void teleport(Location loc) {
        body.bukkit().teleport(loc);
        walker.stop();
    }

    /** Turns the bot's head toward an entity, like players naturally do. */
    public void lookAt(Player target) {
        Location eye = body.bukkit().getEyeLocation();
        org.bukkit.util.Vector to = target.getEyeLocation().toVector().subtract(eye.toVector());
        double dist = Math.hypot(to.getX(), to.getZ());
        if (dist < 0.01) return;
        float yaw = (float) Math.toDegrees(Math.atan2(-to.getX(), to.getZ()));
        float pitch = (float) -Math.toDegrees(Math.atan2(to.getY(), dist));
        body.setYawPitch(yaw, pitch);
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

    /** Drops everything the bot carries at the given location (death). */
    public void dropInventoryAt(Location loc) {
        for (var e : List.copyOf(inventory.entrySet())) {
            int left = e.getValue();
            while (left > 0) {
                int stack = Math.min(left, e.getKey().getMaxStackSize());
                loc.getWorld().dropItemNaturally(loc, new ItemStack(e.getKey(), stack));
                left -= stack;
            }
        }
        inventory.clear();
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
