# MiraLoaders

Paid physical full-column chunk loaders for the Mira Paper server suite.

MiraLoaders provides an admin-issued Beacon item that, once placed and fueled, keeps its **entire containing chunk column** active from the world's minimum build height to maximum build height. v0.2.1 does this without synthetic or fake players.

## Current Release

**v0.2.1** — compatible with Paper/Minecraft **1.21.11 through 26.2**.

## Download

**[Download MiraLoaders v0.2.1](https://github.com/FiveSOCE/Mira-Loaders/releases/download/v0.2.1/MiraLoaders-0.2.1.jar)**

## Requirements

- Paper 1.21.11 through 26.2
- Java 21 runtime for the production JAR
- Vault
- a Vault-compatible economy provider
- MiraSpawners optional/recommended

## Loader Item

The physical loader is a modified `BEACON` carrying MiraLoaders persistent item identity.

Only administrators can create loaders through commands. Once issued, any player may place one.

Default behavior:

- **Mira Chunk Loader**
- entire `16 x world-height x 16` chunk column remains active while fueled
- loader Y coordinate does not change the simulated vertical area
- fuel cost: **$150,000 per hour**
- maximum stored fuel: **12 hours**

Normal Beacons are not treated as loaders.

## Full-Column Simulation

v0.2.1 removes the v0.2.0 synthetic `ServerPlayer` implementation completely.

While fueled, MiraLoaders now owns the chunk directly:

1. a Paper plugin chunk ticket keeps the chunk resident
2. MiraLoaders additionally force-loads the same chunk while active
3. the loader verifies the chunk reaches Paper's `ENTITY_TICKING` load level
4. every entity physically inside that chunk is kept activated so Paper's player-distance entity activation optimisation cannot switch off AI/ticking vertically
5. every Creature Spawner and Trial Spawner in the chunk has its player activation range temporarily removed while the loader is active
6. original spawner ranges and force-load state are restored when the loader shuts down or is removed

This is chunk-column based, not distance-from-beacon based.

A loader at bedrock and a loader at build height affect the same vertical column: the entire containing chunk from minimum world height to maximum world height.

Paper defines `ENTITY_TICKING` as the load level where all normal chunk game logic is processed. This covers the systems that belong to ticking chunks, including scheduled block updates, redstone, block entities, furnaces, hoppers and random block ticks used by crops/plants.

MiraLoaders additionally removes the player-distance gates that normally affect spawners and Paper entity activation inside the loaded chunk.

### No fake players

MiraLoaders does not create, register, hide or simulate a Minecraft player in v0.2.1.

There is no fake account in the player list, no fake connection, no player Y-position and no player-shaped vertical activation radius.

### Natural mob spawning

Vanilla natural mob spawning is explicitly calculated around real players and mob caps. MiraLoaders does not create fake players and therefore does not fabricate a replacement natural-spawn player population. Physical/managed spawners, entity farms, redstone farms, crop farms, block entities and loaded entities are handled by the chunk-column system above.

## Fuel & GUI

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
- active persisted loaders re-establish their chunk simulation after restart

By default only one Mira loader may exist in a chunk.

## Removal & Protection

Tracked loaders cannot be broken normally and are excluded from explosion destruction. Piston movement involving a tracked loader is cancelled so the persisted location cannot become desynchronised.

The player who placed the loader can remove it through the GUI. Administrators with `miraloaders.admin` can also remove it.

Removal:

1. restores any spawner activation ranges changed by MiraLoaders
2. releases MiraLoaders' force-load marker
3. removes the Paper plugin chunk ticket
4. removes the placed Beacon
5. deletes the persisted loader record
6. returns a physical Mira Chunk Loader item to the removing player

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

MiraSpawners already normalizes managed spawners to remove their normal 3D required-player range. MiraLoaders v0.2.1 applies the same full-column activation principle to spawner block states in an active loader chunk, so vertical distance from the Beacon is irrelevant.

## Configuration

```yaml
fuel:
  cost-per-hour: 150000.0
  maximum-hours: 12

loader:
  one-per-chunk: true
```

Item name/lore and the Mira chat prefix are also configurable.
