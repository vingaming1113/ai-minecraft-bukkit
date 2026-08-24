# AIBots

Physical AI players for Paper **26.2** — like [mindcraft](https://github.com/kolbytn/mindcraft), but as a native Paper plugin. No proxies, no headless clients: each bot is a real fake-player entity (NMS `ServerPlayer`, the same way NPC plugins work) with a genuine player body that walks, jumps, falls and swims using actual physics — never teleports.

## Features

- **Real player body** — spawns an NMS `ServerPlayer` via Mojang-mapped reflection (no ProtocolLib needed). It collides, takes gravity, and is steered per-tick with A* pathfinding + auto-jump so it *walks* everywhere.
- **LLM brain** — works with any OpenAI-compatible API out of the box:
  OpenRouter, OpenAI, Groq, DeepSeek, Mistral, xAI, Together, Ollama, LM Studio, vLLM or a custom base URL.
- **Chats like a player** — hears chat in a radius, replies when mentioned, keeps conversation memory, and bots can talk to *each other* (with chain-limiting to prevent infinite loops).
- **Actions** — the AI controls its own body through a simple action protocol:
  walk (`!goto`), follow players (`!follow`), break any block it can see (`!break oak_log 4` — how it gathers wood and mines), craft (`!craft`), build shelters (`!build`), place blocks (`!place`), give/drop items, show inventory.
- **Autonomy** — idle bots periodically scan their surroundings (terrain, trees, water, ores, mobs, nearby players) and decide what to do like a real player: gather wood, craft tools, build, explore — or ask another player in chat if they can come join them (that's how they seek civilization).
- **Per-bot settings** — custom username, persona, gamemode (`survival` = must gather/craft resources; `creative` = free items) and whether it may run server commands (`allow-commands`).
- **Persistence** — bot position, inventory and settings are saved to `bots.yml` and restored on restart.

## Building

Requires JDK 25 (Paper 26.x requirement).

```bash
mvn package
```

GitHub Actions builds every commit automatically (`.github/workflows/build.yml`) and uploads the jar as an artifact.

## Setup

1. Drop `AIBots.jar` and ProtocolLib into `plugins/`.
2. **`plugins/AIBots/bots.yml`** is the single file that defines your bots (it is created with an example on first start):

```yaml
bots:
  - name: "Alex"                # username shown in chat & tab
    persona: "You are Alex, a cheerful builder."
    gamemode: survival          # survival | creative
    allow-commands: false       # when true the AI may also !tp and run server commands
    skin: "Notch"               # premium Minecraft username for the bot's skin (optional)
    model: ""                   # optional per-bot model override (empty = global)
```

3. Edit `plugins/AIBots/config.yml` for the AI provider:

```yaml
ai:
  provider: openrouter          # openrouter|openai|groq|deepseek|mistral|xai|together|ollama|lmstudio|custom
  api-key: "sk-or-v1-..."
  model: "openai/gpt-4o-mini"   # global model; per-bot overrides live in bots.yml
  reasoning-effort: medium      # off | low | medium | high
```

4. Restart. Bots defined in `bots.yml` spawn at their saved position (or world spawn). Everything the runtime changes (position, inventory, new bots from `/aibot spawn`) is saved back into the **same file** — there is no second bot config.

## Commands

```
/aibot spawn <name> [skin:<playerName>] [survival|creative] [commands:true|false] [persona words...]
/aibot skin <botName> <playerName|base64Texture>
/aibot remove <name>
/aibot list
/aibot info <name>
/aibot say <botName> <message>     # DM a bot directly
/aibot stop <name>
/aibot reload                      # FULL reload: discards all bots, re-reads config.yml AND bots.yml
```

Permission: `aibots.admin` (default op).

## How the AI acts

The system prompt gives the model its body state (position, health, inventory, nearby bots) and the action list above. Any plain text line it writes is spoken in chat; lines starting with `!` execute silently. Example reply:

```
Let me go check that out!
!goto 120 64 -35
```

## Notes

- Bots appear in the tab list like real players (they *are* real server-side player entities).
- Like all fake-player implementations on Paper, chunks only stay loaded while real players are nearby.
- If a future Paper build changes internal mappings, the plugin logs a clear error instead of crashing.

## Like a real player

- **Visible & real** — bots are genuine player entities: they render with skins, show in the tab list (required for rendering), appear in the locator bar, and are selectable by vanilla selectors/commands (`@p`, `/tp`, ...).
- **Custom skins** — set `skin: <premium username>` per bot in config, use `skin:<username>` on spawn, or change live with `/aibot skin <bot> <username>`. Raw base64 texture values also work. Skins resolve from Mojang's session servers (async, cached).
- **Polish** — idle bots turn their heads toward the nearest player; walking bots swing their arms.

## Performance

- Bots are real server-side entities, so they cost almost nothing to simulate.
- **ProtocolLib is a required dependency.** On enable, bots are removed from every client's tab list with a single `PLAYER_INFO_REMOVE` packet — including players who join later (`performance.hide-from-tab-list`, default `true`).
- Idle bots skip movement simulation entirely (zero-cost ticks).
- A cheap line-of-sight check short-circuits A* whenever the goal is reachable in a straight walkable line.
- Chat handling is a no-op while no bots are online.

## Requirements

- Paper (or fork) for Minecraft **26.2**
- Java **25**
- **ProtocolLib** (required — the plugin disables itself without it)
