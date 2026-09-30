# HumanBot 2.2 — a Baritone-style bot that plays like a person

For **Minecraft Java 26.3** · Fabric Loader ≥ 0.19.5 · Fabric API · Java 25

HumanBot gives the game to a bot: tell it to go somewhere, mine diamonds, chop trees,
farm, follow a friend or explore, and it does it. It's modelled on Baritone (movement
types, goals, one process at a time, segment planning) but everything it does goes
through the normal player controls with a humanizer on top, so it moves like a person:

- **Aim** accelerates and decelerates like a mouse flick, sometimes overshoots and
  corrects, and has a small hand tremor.
- **Reaction time** before reacting to anything new (skewed like real reaction times:
  mostly ~220 ms, sometimes slower, never instant).
- **Clicks** wait for the attack cooldown and only fire when the crosshair is really on
  the target; a little sloppy on purpose.
- **Habits**: short thinking pauses between tasks, glancing sideways while walking,
  looking down while eating or towering, sneaking to the edge to bridge.

## Install

1. Install Fabric Loader for 26.3 (fabricmc.net/use).
2. Put **`humanbot-2.2.0.jar`** and **Fabric API** into `.minecraft/mods`.
3. Start the game. Press **Right Shift** in a world.

## Controls

| Key | Action |
| --- | --- |
| **Right Shift** | Open the menu |
| **J** | Pause / resume — instantly gives you back control |

Both can be rebound in *Options → Controls → HumanBot*.

## The menu (Right Shift)

A tab column on the left, the live status of the bot in the header, and **STOP** /
**Pause** always at the bottom. Hover any button or box for a hint.

- **Tasks** — *Go to* (type x / y / z or hit *Here*), *Get to* a crafting table / chest /
  furnace, *Mine* with one-click ore presets (Diamond, Iron, Coal...) and an optional
  count, *Follow* a player, and one-click **Chop trees / Farm / Fight mobs / Explore**.
- **Automation** — switch on AutoEat, **MobFighter** (defends you), AntiAFK, AutoSprint,
  AutoTool, AutoRespawn; set fight range and the hunger level it eats at.
- **Humanizer** — turn speed, reaction time, aim shake, overshoot, click sloppiness,
  thinking pauses.
- **Pathing** — dig, place blocks (bridge/tower), parkour, max fall, background mode, HUD,
  search/farm radius, and which blocks it may build with.
- **Waypoints** — save where you are, then *Go* or *Delete* from the list.

## Fighting

- **Fight mobs** (menu button or `/hb fight`) is a job: it hunts the nearest hostile mob
  in range, walks up to it (with the pathfinder when there are walls, water or ledges in
  the way), hits it with the best sword/axe in the hotbar, and moves on to the next.
- **MobFighter** (Automation tab) does the same but only for mobs that come close, and
  then hands control back to whatever job was running.
- It hits when the crosshair is really on the mob and the attack cooldown is charged,
  strafes a little, and backs off from creepers after a hit. Endermen, piglins, ghasts,
  guardians, wardens and bosses are left alone.

## Commands (`/hb` or `/humanbot`)

```
/hb                         open the menu
/hb help                    list commands
/hb goto <x> <z>            walk there (any height)
/hb goto <x> <y> <z>        walk to an exact block
/hb goto y <level>          go up/down to a height (e.g. y -58 for diamonds)
/hb mine [blocks...] [n]    mine ores, or e.g. /hb mine diamond_ore deepslate_diamond_ore 10
                            (by default only ores it could actually see: not buried in stone)
/hb chop [n]                chop trees
/hb farm [radius]           harvest grown crops and replant them
/hb follow [player]         follow a player (nearest if no name)
/hb getto <block>           go to the nearest block and open it (crafting_table, chest...)
/hb explore                 wander outward in a spiral of chunks
/hb fight                   hunt and kill hostile mobs nearby
/hb wp save|goto|del <name> waypoints   ·   /hb wp list
/hb stop                    stop the current job
/hb pause                   same as J
/hb toggle <module>         e.g. /hb toggle MobFighter
/hb modules                 what's on and off
```

When you die, a `death` waypoint is saved automatically: `/hb wp goto death`.

## How it moves (Baritone-style)

The pathfinder is A* over blocks with costs in ticks. Moves:

| Move | What the bot does |
| --- | --- |
| Traverse / diagonal | Walks, sprints on straight stretches |
| Ascend | Jumps up one block |
| Descend / fall | Steps or drops down (up to *Max fall*, default 3) |
| Dig | Mines blocks in the way with the best tool (staircases only, never straight down) |
| **Bridge** | Sneaks to the edge, aims at the side of the block underfoot, places a block |
| **Pillar** | Looks down, jumps, places a block under itself (towers up) |
| **Parkour** | Sprint-jumps 1–2 block gaps (2-block gaps need a run-up) |

How it follows a path (the same ideas Baritone uses):

- **The legs don't wait for the camera.** Each tick it picks the W/A/S/D combination
  that goes the right way from where the camera is actually pointing, so it never
  drifts into a wall while it's still turning or glancing around.
- **Steps flow into each other.** A step counts as done the moment the feet enter the
  next block, and on straight stretches it steers several blocks ahead, so walking is
  smooth instead of stop-start.
- **Digging is a last resort.** Breaking blocks is costed high, so it walks around
  walls and only digs when going round is much longer (or there's no other way).
- **It notices when things go wrong.** Pressing into something: it hops. Still stuck,
  or pushed off the path: it re-plans from where it is.
- Searches are limited to a few milliseconds per tick, so thinking never stutters the
  game, and after a couple of seconds it walks the best partial path it has and keeps
  planning the next stretch while moving (like Baritone's segments).
- **If it's already further along the path than the plan thinks** (cut a corner, fell
  early) it carries on from there instead of walking back.
- Standing on farmland, dirt paths, slabs or carpets works: the feet position uses
  Baritone's +0.1251 offset and bottom slabs count as walkable floor.

It plans in segments: on long trips it works out the next stretch while still walking.
Bridging and towering use the blocks listed under *Pathing → Blocks to build with*
(cobblestone, dirt, netherrack, stone variants... by default) from your **hotbar**. With
none, it finds a route without placing.

Safety: never walks into lava, fire, cactus, berry bushes or powder snow; never breaks a
block next to lava or under water/lava; won't break bedrock, chests, spawners or
portals; never places against chests, doors, levers and other usable blocks; won't
parkour over lava.

## Working in the background

With *Pathing → Work in background* on (default), the bot keeps going when you alt-tab:

- While a job is running, the game's "pause when unfocused" setting is switched off,
  and switched back when the bot goes idle.
- Mining and placing talk to the game's interaction manager directly (like Baritone),
  instead of relying on the mouse being captured.

## Files

- Settings and waypoints: `.minecraft/config/humanbot.json`

## Building from source

Needs JDK 25:

```
./gradlew build        # Windows: gradlew.bat build
```

Output: `build/libs/humanbot-2.2.0.jar`. `./gradlew runClient` starts a dev client.

## Code map

```
src/main/java/dev/humanbot/
  HumanBot.java            entrypoint: keys, tick loop, HUD, /hb commands
  BotConfig.java           settings + waypoints (JSON)
  Waypoints.java           per-dimension waypoints
  StatusHud.java           top-left status line
  rotation/HumanRotator    the human-like aim model
  path/PathFinder, Move    A* with Baritone-style moves and goals
  path/PathExecutor        performs each move with keys, mining and placing
  module/                  Module, Process (one-at-a-time jobs), ModuleManager
  modules/                 Goto, Mine, Chop, Farm, Follow, GetTo, Explore, Fight,
                           AutoEat, MobFighter (both use CombatAI), AntiAFK,
                           AutoSprint, AutoTool, AutoRespawn
  util/                    InputUtil (all game input), Placer, WorldUtil, Rand
  gui/BotScreen            the tabbed menu
```

New job = a class extending `Process` (override `tickActive`), registered in
`ModuleManager`.

## Notes

- Most multiplayer servers forbid bots in their rules. Use it in singleplayer, on your
  own server, or where it's allowed.
- Only the hotbar is used for food, tools and building blocks.
- No elytra flying or schematic building (Baritone has those; this doesn't yet).
