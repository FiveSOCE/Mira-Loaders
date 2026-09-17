# MiraLoaders

Paid physical full-simulation chunk loaders for the Mira Paper server suite.

MiraLoaders provides an admin-issued Beacon item that, once placed and fueled, keeps the loader area running as though a real player were standing at the beacon. A normal Paper plugin chunk ticket is retained for residency, while v0.2.0 adds a hidden server-side player simulation anchor so vanilla player-dependent mechanics continue without a real player online nearby.

## Current Release

**v0.2.0** — compatible with Paper/Minecraft **1.21.11 through 26.2**.

## Download

**[Download MiraLoaders v0.2.0](https://github.com/FiveSOCE/Mira-Loaders/releases/download/v0.2.0/MiraLoaders-0.2.0.jar)**

## Requirements

- Paper 1.21.11 through 26.2
- Java 21 runtime for the production JAR
- Vault
- a Vault-compatible economy provider
- MiraSpawners optional/recommended
- MiraCore optional/recommended for synthetic-player join-message suppression

## Loader Item

The physical loader is a modified `BEACON` carrying MiraLoaders persistent item identity.

Only administrators can create loaders through commands. Once issued, any player may place one.

Default item presentation:

- **Mira Chunk Loader**
- keeps the containing chunk resident and maintains vanilla-style player simulation while fueled
- fuel cost: **$150,000 per hour**
- maximum stored fuel: **12 hours**

Normal Beacons are not treated as loaders.

## Full Simulation

A simple chunk ticket only prevents unloading. That is not enough for mechanics that explicitly require a player or player simulation source.

While a Mira Chunk Loader has fuel, MiraLoaders now maintains both:

1. a Paper plugin chunk ticket for the loader chunk
2. a hidden server-side `ServerPlayer` simulation anchor pinned to the beacon position

The synthetic player uses Minecraft's normal server player path with an in-memory connection. It is invisible, invulnerable, non-collidable, gravity-free, excluded from normal MiraCore join presentation, and hidden/unlisted from real players.

This allows vanilla itself to continue mechanics that normally rely on a nearby player, including:

- redstone and scheduled block updates
- hoppers and other block entities
- furnaces and processing blocks
- entity ticking and AI activation
- random block ticks such as crop and plant growth
- vanilla mob spawner player checks
- natural spawning/player-proximity logic
- MiraSpawners in the loaded simulation area

The loader's Y coordinate does not define a vertical activation band. Minecraft chunks are full world-height columns, so the containing chunk remains available from the world's minimum build height to maximum build height.

The hidden simulation player is kept at the physical beacon position and uses the minimum simulation/view distance Paper permits in order to keep the surrounding simulation fringe as small as possible while still using real vanilla player mechanics.

When fuel expires, the synthetic player is retired and the plugin chunk ticket is removed immediately. The Beacon remains physically placed and can be refueled later.

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
- active persisted loaders recreate their simulation anchors after restart

By default only one Mira loader may exist in a chunk.

## Removal & Protection

Tracked loaders cannot be broken normally and are excluded from explosion destruction. Piston movement involving a tracked loader is also cancelled so the persisted location cannot become desynchronised.

The player who placed the loader can remove it through the GUI. Administrators with `miraloaders.admin` can also remove it.

Removal:

1. retires the synthetic simulation player
2. removes the Paper chunk ticket
3. removes the placed Beacon
4. deletes the persisted loader record
5. returns a physical Mira Chunk Loader item to the removing player

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

MiraSpawners already normalizes managed spawners to remove their normal 3D required-player range. With MiraLoaders v0.2.0, an active loader additionally supplies a real server-side player simulation source in the area, so both managed and vanilla player-dependent behavior can continue when no real player is present.

## Configuration

```yaml
fuel:
  cost-per-hour: 150000.0
  maximum-hours: 12

loader:
  one-per-chunk: true
```

Item name/lore and the Mira chat prefix are also configurable.
