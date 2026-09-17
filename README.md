# MiraLoaders

Paid physical full-column chunk loaders for the Mira Paper server suite.

MiraLoaders provides an admin-issued Beacon item that, once placed and fueled, keeps its **entire containing chunk column** active from the world's minimum build height to maximum build height. It does this without synthetic or fake players.

## Current Release

**v0.2.2** — compatible with Paper/Minecraft **1.21.11 through 26.2**.

## Download

**[Download MiraLoaders v0.2.2](https://github.com/FiveSOCE/Mira-Loaders/releases/download/v0.2.2/MiraLoaders-0.2.2.jar)**

## Requirements

- Paper 1.21.11 through 26.2
- Java 21 runtime for the production JAR
- Vault
- a Vault-compatible economy provider
- MiraSpawners **v0.1.14+ recommended for managed/stacked Mira spawners**

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

While fueled, MiraLoaders owns the chunk directly:

1. a Paper plugin chunk ticket keeps the chunk resident
2. MiraLoaders force-loads the same chunk while active
3. the loader verifies Paper's chunk load level and keeps enforcing simulation
4. entities physically inside the chunk are kept activated so player-distance entity activation cannot switch them off vertically
5. physical Creature Spawners are driven by MiraLoaders' own off-player spawn clock while the loader is fueled
6. original spawner settings and force-load state are restored when the loader shuts down or is removed

This is **chunk-column based**, not distance-from-beacon based.

A loader at bedrock and a loader at build height affect the same vertical column: the entire containing chunk from minimum world height to maximum world height.

### Spawners with no player nearby

Paper documents `requiredPlayerRange <= 0` as always active only while players are online. That is not sufficient for MiraLoaders.

v0.2.2 therefore does not depend on vanilla player activation for fueled loader spawners. While a loader is active:

- MiraLoaders temporarily suppresses the vanilla spawn attempt for each physical Creature Spawner in the chunk
- MiraLoaders maintains that spawner's own delay, spawn count, spawn range and maximum-nearby limit
- when the timer expires, it performs a real `SpawnReason.SPAWNER` spawn attempt
- the source spawner coordinates are attached to the spawned entity before the spawn event fires
- MiraSpawners v0.1.14+ consumes that source marker and applies the normal Mira stack multiplier, mob policy and managed-spawner safety rules

This works even when **no real player is within range or no real players are online**.

Both normal physical spawners and Mira-managed/stacked Creature Spawners are supported.

### No fake players

MiraLoaders does not create, register, hide or simulate a Minecraft player.

There is no fake account, fake connection, player Y-position or player-shaped vertical activation radius.

### Natural mob spawning

Vanilla natural mob spawning is explicitly calculated around real players and mob caps. MiraLoaders does not create fake players and therefore does not fabricate a replacement natural-spawn player population.

Physical/managed spawners, entity farms, redstone farms, crop farms, block entities and loaded entities are handled by the chunk-column system above.

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

Removal restores loader-owned spawner settings, releases the force-load state and plugin ticket, removes the Beacon and persisted record, and returns a physical Mira Chunk Loader item.

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

## Configuration

```yaml
fuel:
  cost-per-hour: 150000.0
  maximum-hours: 12

loader:
  one-per-chunk: true
```

Item name/lore and the Mira chat prefix are also configurable.
