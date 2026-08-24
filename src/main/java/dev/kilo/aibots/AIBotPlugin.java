package dev.kilo.aibots;

import dev.kilo.aibots.llm.LLMService;
import dev.kilo.aibots.packets.PacketManager;
import org.bukkit.GameMode;
import org.bukkit.command.PluginCommand;
import org.bukkit.plugin.java.JavaPlugin;

/** AIBots - physical AI players with LLM brains for Paper. */
public final class AIBotPlugin extends JavaPlugin {

    private BotManager botManager;
    private LLMService llm;
    private PacketManager packetManager;

    private boolean mentionOnly;
    private boolean greetJoins;
    private long replyDelayMinMs;
    private long replyDelayMaxMs;
    private int maxBotChain;
    private long autonomyIntervalMs;
    private GameMode defaultGamemode;
    private boolean defaultAllowCommands;
    private String defaultPersona;

    @Override
    public void onEnable() {
        saveDefaultConfig();
        loadSettings();

        this.llm = new LLMService(getConfig().getConfigurationSection("ai"));
        this.botManager = new BotManager(this);
        this.packetManager = PacketManager.create(this,
                () -> botManager.all().stream().map(b -> b.body().bukkit()).toList());
        if (packetManager == null) {
            getLogger().severe("Disabling AIBots - ProtocolLib is required.");
            getServer().getPluginManager().disablePlugin(this);
            return;
        }

        getServer().getPluginManager().registerEvents(new BotChatListener(this), this);
        getServer().getPluginManager().registerEvents(new BotLifeListener(this), this);

        PluginCommand cmd = getCommand("aibot");
        AIBotCommand executor = new AIBotCommand(this);
        cmd.setExecutor(executor);
        cmd.setTabCompleter(executor);

        botManager.startTicker();
        // spawn persisted/configured bots one tick later so worlds are ready
        getServer().getScheduler().runTask(this, botManager::loadAll);

        getLogger().info("AIBots enabled - provider: " + getConfig().getString("ai.provider")
                + ", model: " + getConfig().getString("ai.model"));
    }

    @Override
    public void onDisable() {
        if (botManager != null) {
            botManager.save();
            botManager.stopTicker();
            botManager.removeAll();
        }
    }

    public void reloadSettings() {
        reloadConfig();
        loadSettings();
    }

    private void loadSettings() {
        mentionOnly = getConfig().getBoolean("behavior.mention-only", false);
        replyDelayMinMs = getConfig().getLong("behavior.reply-delay-min-ms", 800);
        replyDelayMaxMs = getConfig().getLong("behavior.reply-delay-max-ms", 2500);
        maxBotChain = getConfig().getInt("behavior.max-bot-chain", 3);
        autonomyIntervalMs = Math.max(0, getConfig().getInt("behavior.autonomy-interval-seconds", 60)) * 1000L;
        greetJoins = getConfig().getBoolean("behavior.greet-joins", true);
        defaultPersona = getConfig().getString("defaults.persona", "You are a helpful player.");
        defaultAllowCommands = getConfig().getBoolean("defaults.allow-commands", false);
        try {
            defaultGamemode = GameMode.valueOf(getConfig().getString("defaults.gamemode", "SURVIVAL").toUpperCase());
        } catch (IllegalArgumentException e) {
            defaultGamemode = GameMode.SURVIVAL;
        }
    }

    public String aiApiKey() {
        return getConfig().getString("ai.api-key", "");
    }

    public BotManager botManager() {
        return botManager;
    }

    public LLMService llm() {
        return llm;
    }

    public PacketManager packets() {
        return packetManager;
    }

    public boolean mentionOnly() {
        return mentionOnly;
    }


    public long replyDelayMinMs() {
        return replyDelayMinMs;
    }

    public long replyDelayMaxMs() {
        return replyDelayMaxMs;
    }

    public int maxBotChain() {
        return maxBotChain;
    }

    public long autonomyIntervalMs() {
        return autonomyIntervalMs;
    }

    public boolean greetJoins() {
        return greetJoins;
    }

    public GameMode defaultGamemode() {
        return defaultGamemode;
    }

    public boolean defaultAllowCommands() {
        return defaultAllowCommands;
    }

    public String defaultPersona() {
        return defaultPersona;
    }
}
