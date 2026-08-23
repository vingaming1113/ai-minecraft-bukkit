# AIBots

Physical AI players for Paper **26.2** — like [mindcraft](https://github.com/kolbytn/mindcraft), but as a native Paper plugin. No proxies, no headless clients: each bot is a real fake-player entity (NMS `ServerPlayer`, the same way NPC plugins work) with a genuine player body that walks, jumps, falls and swims using actual physics — never teleports.

## Features

- **Real player body** — spawns an NMS `ServerPlayer` via Mojang-mapped reflection (no ProtocolLib needed). It collides, takes gravity, and is steered per-tick with A* pathfinding + auto-jump so it *walks* everywhere.
- **LLM brain** — works with any OpenAI-compatible API out of the box:
  OpenRouter, OpenAI, Groq, DeepSeek, Mistral, xAI, Together, Ollama, LM Studio, vLLM or a custom base URL.
- **Chats like a player** — hears chat in a radius, replies when mentioned, keeps conversation memory, and bots can talk to *each other* (with chain-limiting to prevent infinite loops).
- **Actions** — the AI controls its own body through a simple action protocol:
  walk (`!goto`), follow players (`!follow`), craft (`!craft`), mine (`!mine`), place blocks (`!place`), give/drop items, show inventory.
- **Per-bot settings** — custom username, persona, gamemode (`survival` = must gather/craft resources; `creative` = free items) and whether it may run server commands (`allow-commands`).
- **Persistence** — bot position, inventory and settings are saved to `bots.yml` and restored on restart.

## Building

Requires JDK 25 (Paper 26.x requirement).

```bash
mvn package
```

GitHub Actions builds every commit automatically (`.github/workflows/build.yml`) and uploads the jar as an artifact.

## Setup

1. Drop `AIBots-1.0.0.jar` into `plugins/`.
2. Edit `plugins/AIBots/config.yml`:

```yaml
ai:
  provider: openrouter          # openrouter|openai|groq|deepseek|mistral|xai|together|ollama|lmstudio|custom
  api-key: "sk-or-v1-..."
  model: "openai/gpt-4o-mini"

bots:
  - name: "Alex"                # username shown in chat & nametag
    persona: "You are Alex, a cheerful builder."
    gamemode: survival          # survival | creative
    allow-commands: false
```

3. Restart. Bots spawn at the world spawn (or their saved position).

## Commands

```
/aibot spawn <name> [survival|creative] [commands:true|false] [persona words...]
/aibot remove <name>
/aibot list
/aibot info <name>
/aibot say <botName> <message>     # DM a bot directly
/aibot stop <name>
/aibot reload
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

## Performance

- Bots are real server-side entities, so they cost almost nothing to simulate.
- **Optional ProtocolLib support**: when ProtocolLib is installed and `performance.hide-from-tab-list: true` (default), bots are removed from every client's tab list with a single `PLAYER_INFO_REMOVE` packet — including players who join later. Without ProtocolLib everything still works, just with vanilla tab-list behavior.
- Idle bots skip movement simulation entirely (zero-cost ticks).
- A cheap line-of-sight check short-circuits A* whenever the goal is reachable in a straight walkable line.
- Chat handling is a no-op while no bots are online.

## Requirements

- Paper (or fork) for Minecraft **26.2**
- Java **25**
- ProtocolLib (optional, for tab-list hiding / packet optimizations)
