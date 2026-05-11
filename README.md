# Set Anger

A server-side NeoForge mod for Minecraft `1.21.1` that adds command support for forcing mobs to target selected living entities.

The main command is:

```mcfunction
/setanger <subjects> <targets>
```

The shorthand alias is:

```mcfunction
/sa <subjects> <targets>
```

Examples:

```mcfunction
/setanger @e[type=zombie,limit=1,sort=nearest] @s
/sa @e[type=chicken,distance=..16] @p
/setanger @e[hostile=true,distance=..32] @a[limit=1,sort=nearest]
```

`subjects` must select mobs. `targets` must select living entities such as players, mobs, or animals. Both arguments can select more than one entity. When multiple targets are selected, each subject uses the nearest selected living target in the same dimension.

To clear a forced target:

```mcfunction
/setanger clear <subjects>
```

Example:

```mcfunction
/setanger clear @e[type=zombie,distance=..32]
```

To reload the Set Anger server config without restarting:

```mcfunction
/setanger reloadconfig
/sa reloadconfig
```

The reload command reports the exact TOML file it loaded. Set Anger only uses the server's `config/set_anger.toml`; world `serverconfig` copies are ignored.

## What It Does

- Calls the mob targeting API directly, similar in spirit to setting `AngryAt`, but works beyond vanilla neutral mobs.
- Keeps the forced target active each server tick while the target is alive and in the same dimension.
- Adds a simple melee attack goal to passive pathfinding mobs that do not already have one, so mobs such as chickens can move toward and attack their forced target.
- Adds a default `generic.attack_damage` value of `1.0` to mob types where NeoForge exposes the attribute builder, and the generic attack goal also has a guarded 1 damage fallback for passive mobs that still lack the attribute.
- Uses vanilla persistent entity data to remember the forced target UUID across saves while both entities still exist.
- Uses server-side checks and command errors instead of crashing when a selector picks an invalid subject or target.

## Mob Disposition Selector Options

This mod adds:

```mcfunction
@e[hostile=true]
@e[hostile=false]
@e[neutral=true]
@e[neutral=false]
@e[passive=true]
@e[passive=false]
@e[angryatplayer=true]
@e[angryatplayer=false]
```

These options classify mobs by their normal behavior, not by their current anger state.

- `hostile=true` matches mobs that normally target players, such as zombies and skeletons.
- `neutral=true` matches mobs that are normally neutral but can become angry when provoked, such as wolves, bees, endermen, and zombified piglins.
- `passive=true` matches mobs that are normally non-hostile and do not retaliate, such as pigs, cows, and chickens.

This means a wolf remains `neutral=true` whether or not it is currently angry. An angered wolf does not become `hostile=true` just because it currently has a target.

`angryatplayer=true` is different: it is a live-state filter. It matches mobs whose current target is an alive, non-creative, non-spectator player. This includes mobs targeting players because of vanilla AI, this mod's `/setanger`, or another mod's AI changes.

The options can be combined with normal selector filters:

```mcfunction
@e[hostile=true,type=!minecraft:creeper,distance=..24]
@e[neutral=true,distance=..16]
@e[passive=true,type=minecraft:chicken]
@e[angryatplayer=true,distance=..32]
```

Classification order is:

1. Server config `hostile`
2. Server config `neutral`
3. Server config `passive`
4. Entity types in `#set_anger:hostile`
5. Entity types in `#set_anger:neutral`
6. Entity types in `#set_anger:passive`
7. Entities implementing Minecraft's `NeutralMob` interface
8. Other mobs whose entity type category is `monster`
9. Other mobs as passive

## Entity Category Config

Set Anger creates a server config:

```text
config/set_anger.toml
```

This is in the normal NeoForge config folder for the running server. The config lets server owners classify entities without making a datapack. If the file is missing or has not loaded yet, Set Anger uses safe defaults and recreates the file from the config spec. If an older `config/set_anger-server.toml` exists and `config/set_anger.toml` does not, Set Anger copies the old file once during startup or reload.

Example:

```toml
[relations]
updateAttackerOnBlockedDamage = true
enableExecuteOnVictim = true

[entity_categories]
hostile = ["minecraft:zombie", "examplehostiles:*"]
neutral = ["minecraft:wolf", "retaliationmod:*"]
passive = ["exampleanimals:gentle_cow"]
```

Entries can be:

- Exact entity ids: `minecraft:zombie`
- Mod id wildcards: `examplemod:*`

Config entries override datapack tags and built-in heuristics. If the same entity matches more than one config list, `hostile` wins, then `neutral`, then `passive`.

The relation options are:

- `updateAttackerOnBlockedDamage`: when true, blocked shield damage updates the defender's recent attacker so vanilla `execute on attacker` can work from advancement reward functions even if the damage was fully blocked.
- `enableExecuteOnVictim`: when true, Set Anger's `execute on victim` relation resolves to the source entity's recent victim. When false, the command relation remains in the command tree but resolves to no entity, so the setting can be changed with `/setanger reloadconfig`.

The tags are empty by default and are intended for datapacks and modpacks. They make modded entity support explicit without hard-coding another mod's classes. For example, a datapack can classify a modded mob as neutral:

```json
{
  "replace": false,
  "values": [
    "examplemod:retaliating_deer"
  ]
}
```

Put that file at:

```text
data/set_anger/tags/entity_type/neutral.json
```

For mods that change AI at runtime, such as Enhanced AI's animal retaliation features, these selectors do not inspect or modify another mod's goals. They only classify the entity type for command selection. If a modpack wants animals changed by another mod to count as neutral or hostile for commands, add those entity types to the appropriate `set_anger` tag.

For runtime AI behavior, use `angryatplayer=true`. That selector looks at the mob's current target and therefore can match mobs made aggressive by vanilla, Set Anger, or other AI mods.

## Multiplayer Safety

Set Anger is server-authoritative.

- Commands and selectors run on the server command parser.
- Forced target UUIDs are stored per entity, not globally per player.
- Each mob independently chooses the nearest selected target in its own dimension.
- Cross-dimension targeting is rejected.
- `angryatplayer=true` reads the mob's current target and does not mutate AI state.
- Client-only classes are not used by command or targeting logic.

The mod does keep a static selector registration flag, but that only prevents duplicate selector registration during mod loading. Runtime anger state is stored on the relevant mob entity.

Note: this is parsed on the server. A client without the mod may not autocomplete or locally understand the custom selector option, but the server command parser accepts it.

## Advancement Reward Helpers

Minecraft `1.21.1` already includes:

```mcfunction
execute on attacker run ...
```

For advancement reward functions where the command source is the hurt player, such as `minecraft:entity_hurt_player`, that can reference the entity that attacked the player. Set Anger refreshes this relation when shield damage is blocked as well, unless `updateAttackerOnBlockedDamage` is disabled.

This mod also adds, unless `enableExecuteOnVictim` is disabled:

```mcfunction
execute on victim run ...
```

`victim` resolves to the current command source's most recent hurt or killed living target. This is useful in reward functions for triggers such as `minecraft:player_hurt_entity` and `minecraft:player_killed_entity`.

Examples:

```mcfunction
execute on attacker run damage @s 2 player_skills:bonus_damage
execute on victim run effect give @s minecraft:glowing 3 0 true
```

These relations depend on Minecraft's recent combat tracking. If there is no recent attacker or victim, the `execute on ...` branch simply has no entity to run as.

## Server-Side Use

Set Anger is intended to run on the server. The mod metadata uses `IGNORE_SERVER_VERSION` so vanilla clients do not need the jar for normal multiplayer use.

## License

This project is licensed under the `MIT` License.
