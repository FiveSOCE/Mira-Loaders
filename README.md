# MiraLoaders

Paid physical chunk loaders for the Mira Paper server suite.

MiraLoaders provides an admin-issued Beacon item that, once placed and fueled, keeps its entire chunk loaded using Paper plugin chunk tickets. Fuel is purchased through Vault economy and persists as an absolute expiry timestamp across restarts.

## Current Release

**v0.1.1** — compatible with Paper/Minecraft **1.21.11 through 26.2**.

## Requirements

- Paper 1.21.11 through 26.2
- Java 21 runtime for the production JAR
- Vault
- a Vault-compatible economy provider
- MiraSpawners optional/recommended

## Loader Item

The physical loader is a modified `BEACON` carrying MiraLoaders persistent item identity.

Only administrators can create loaders through commands. Once issued, any player may place one.

Default item presentation:

- **Mira Chunk Loader**
- keeps the entire containing chunk loaded while fueled
- fuel cost: **$150,000 per hour**
- maximum stored fuel: **12 hours**

Normal Beacons are not treated as loaders.

## Fuel & Chunk Loading

Right-clicking a placed Mira Chunk Loader suppresses the normal Beacon interface and opens the MiraLoaders GUI.

The GUI shows:

- current active/inactive state
- exact remaining time
- chunk coordinates
- **Deposit $150,000** button, adding exactly one hour
- **Remove Loader** button

Fuel behavior:

- one deposit = one hour
- default cost = `$150,000`
- default maximum stored time = `12 hours`
- expiry is stored as an absolute timestamp
- restarting the server does not pause or reset fuel
- while fueled, the containing chunk receives a Paper plugin chunk ticket
- when fuel expires, the plugin ticket is removed and the Beacon remains placed/inactive

By default only one Mira loader may exist in a chunk.

## Removal & Protection

Tracked loaders cannot be broken normally and are excluded from explosion destruction. Piston movement involving a tracked loader is also cancelled so the persisted location cannot become desynchronised.

The player who placed the loader can remove it through the GUI. Administrators with `miraloaders.admin` can also remove it.

Removal:

1. removes the Paper chunk ticket
2. removes the placed Beacon
3. deletes the persisted loader record
4. returns a physical Mira Chunk Loader item to the removing player

If the inventory is full, the returned loader is dropped safely at the player's location.

## Commands

| Command | Permission | Purpose |
| --- | --- | --- |
| `/mloader give <player> [amount]` | `miraloaders.admin` | Gives physical Mira Chunk Loader items. |
| `/mloader reload` | `miraloaders.admin` | Reloads MiraLoaders configuration. |
| `/mloader help` | `miraloaders.admin` | Shows command help. |

Aliases: `/miraloader`, `/loaders`.

## Permissions

| Permission | Default | Purpose |
| --- | --- | --- |
| `miraloaders.admin` | OP | Gives loaders and bypasses loader ownership for removal. |

## MiraSpawners Integration

MiraSpawners already treats managed spawner activation as chunk-based rather than 3D player-distance-based by setting managed spawners to a zero required-player range while their chunk is ticking.

This means:

- if a player keeps a chunk ticking, managed spawners anywhere in that chunk can operate regardless of Y level
- another player's loaded/ticking chunk provides the same behavior
- an active Mira Chunk Loader keeps its chunk ticketed, so managed MiraSpawners in that chunk continue operating without requiring a nearby player

MiraLoaders does not fake players or modify spawner blocks directly.

## Configuration

```yaml
fuel:
  cost-per-hour: 150000.0
  maximum-hours: 12

loader:
  one-per-chunk: true
```

Item name/lore and the Mira chat prefix are also configurable.
