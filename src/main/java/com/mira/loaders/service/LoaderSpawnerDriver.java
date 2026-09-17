package com.mira.loaders.service;

import com.mira.loaders.MiraLoadersPlugin;
import org.bukkit.Bukkit;
import org.bukkit.Chunk;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.NamespacedKey;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.BlockState;
import org.bukkit.block.CreatureSpawner;
import org.bukkit.entity.Entity;
import org.bukkit.entity.EntityType;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.event.entity.CreatureSpawnEvent;
import org.bukkit.persistence.PersistentDataType;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.UUID;

/**
 * Supplies off-player CreatureSpawner ticking for fueled Mira loader chunks.
 *
 * The driver never disables or rewrites a healthy vanilla spawner while a real
 * player already satisfies its activation rule. It only supplements the native
 * spawner when no qualifying player is present. This keeps an uncharged loader
 * completely transparent and avoids double-spawning when players are nearby.
 */
public final class LoaderSpawnerDriver {
    public static final String SOURCE_MARKER = "loader_spawner_spawn";
    public static final String SOURCE_WORLD = "loader_spawner_world";
    public static final String SOURCE_X = "loader_spawner_x";
    public static final String SOURCE_Y = "loader_spawner_y";
    public static final String SOURCE_Z = "loader_spawner_z";

    private static final int VANILLA_DEFAULT_MAX_NEARBY = 6;
    private static final int VANILLA_DEFAULT_REQUIRED_RANGE = 16;
    private static final NamespacedKey MIRA_MANAGED_SPAWNER = NamespacedKey.fromString("miraspawners:managed_spawner");

    private final MiraLoadersPlugin plugin;
    private final Random random = new Random();
    private final Map<SpawnerKey, SpawnerState> states = new HashMap<>();

    private final NamespacedKey markerKey;
    private final NamespacedKey worldKey;
    private final NamespacedKey xKey;
    private final NamespacedKey yKey;
    private final NamespacedKey zKey;

    public LoaderSpawnerDriver(MiraLoadersPlugin plugin) {
        this.plugin = plugin;
        this.markerKey = new NamespacedKey(plugin, SOURCE_MARKER);
        this.worldKey = new NamespacedKey(plugin, SOURCE_WORLD);
        this.xKey = new NamespacedKey(plugin, SOURCE_X);
        this.yKey = new NamespacedKey(plugin, SOURCE_Y);
        this.zKey = new NamespacedKey(plugin, SOURCE_Z);
    }

    /** Repairs block-state damage left by the v0.2.1/v0.2.2 suppression approach. */
    public void prepare(Chunk chunk) {
        repairLegacySuppression(chunk);
    }

    public void tick(Chunk chunk) {
        repairLegacySuppression(chunk);
        Set<SpawnerKey> seen = new HashSet<>();

        for (BlockState blockState : chunk.getTileEntities(false)) {
            if (!(blockState instanceof CreatureSpawner spawner)) continue;

            SpawnerKey key = SpawnerKey.of(spawner.getBlock());
            seen.add(key);

            if (vanillaHasQualifyingPlayer(spawner)) {
                int nativeDelay = spawner.getDelay();
                states.put(key, new SpawnerState(nativeDelay >= 0 ? nativeDelay : randomDelay(spawner)));
                continue;
            }

            states.computeIfAbsent(key, ignored -> {
                int initialDelay = spawner.getDelay();
                if (initialDelay < 0) initialDelay = randomDelay(spawner);
                return new SpawnerState(initialDelay);
            });
            tickSpawner(spawner, key);
        }

        states.keySet().removeIf(key -> key.worldId().equals(chunk.getWorld().getUID())
                && key.chunkX() == chunk.getX() && key.chunkZ() == chunk.getZ() && !seen.contains(key));
    }

    /**
     * Deactivation no longer needs to restore runtime settings because the
     * current driver never suppresses healthy native settings. We still run the
     * one-time legacy repair for chunks touched by older builds.
     */
    public void restore(Chunk chunk) {
        repairLegacySuppression(chunk);
        states.keySet().removeIf(key -> key.worldId().equals(chunk.getWorld().getUID())
                && key.chunkX() == chunk.getX() && key.chunkZ() == chunk.getZ());
    }

    public void restoreAll() {
        states.clear();
    }

    private void tickSpawner(CreatureSpawner spawner, SpawnerKey key) {
        SpawnerState state = states.get(key);
        if (state == null) return;

        if (state.delay() > 0) {
            states.put(key, state.withDelay(state.delay() - 1));
            return;
        }

        EntityType type = spawner.getSpawnedType();
        Class<? extends Entity> rawClass = type == null ? null : type.getEntityClass();
        if (type == null || rawClass == null || !LivingEntity.class.isAssignableFrom(rawClass)) {
            states.put(key, state.withDelay(randomDelay(spawner)));
            return;
        }

        int allowedNearby = Math.max(1, spawner.getMaxNearbyEntities());
        if (nearbySameType(spawner, type) >= allowedNearby) {
            states.put(key, state.withDelay(randomDelay(spawner)));
            return;
        }

        int attempts = Math.max(1, spawner.getSpawnCount());
        int spawned = 0;
        for (int i = 0; i < attempts; i++) {
            Location location = findSpawnLocation(spawner);
            if (location == null) continue;
            if (spawn(spawner, type, rawClass, location)) spawned++;
        }

        states.put(key, state.withDelay(spawned > 0 ? randomDelay(spawner) : 20));
    }

    @SuppressWarnings("unchecked")
    private boolean spawn(CreatureSpawner spawner, EntityType type, Class<? extends Entity> rawClass, Location location) {
        try {
            Class<? extends LivingEntity> entityClass = (Class<? extends LivingEntity>) rawClass;
            LivingEntity entity = location.getWorld().spawn(
                    location,
                    entityClass,
                    CreatureSpawnEvent.SpawnReason.SPAWNER,
                    true,
                    living -> {
                        living.getPersistentDataContainer().set(markerKey, PersistentDataType.BYTE, (byte) 1);
                        living.getPersistentDataContainer().set(worldKey, PersistentDataType.STRING, spawner.getWorld().getUID().toString());
                        living.getPersistentDataContainer().set(xKey, PersistentDataType.INTEGER, spawner.getX());
                        living.getPersistentDataContainer().set(yKey, PersistentDataType.INTEGER, spawner.getY());
                        living.getPersistentDataContainer().set(zKey, PersistentDataType.INTEGER, spawner.getZ());
                    }
            );
            return entity != null && entity.isValid();
        } catch (RuntimeException ex) {
            plugin.getLogger().warning("Loader-driven " + type + " spawner spawn failed at "
                    + spawner.getX() + "," + spawner.getY() + "," + spawner.getZ() + ": " + ex.getMessage());
            return false;
        }
    }

    /**
     * Chooses a whole-block air column and places the mob in its center. This
     * prevents entities (especially zombies) from clipping the neighbouring
     * spawner blocks in checkerboard/one-block-gap farm layouts.
     */
    private Location findSpawnLocation(CreatureSpawner spawner) {
        World world = spawner.getWorld();
        int range = Math.max(1, spawner.getSpawnRange());

        Location selected = null;
        int valid = 0;
        for (int dy = -1; dy <= 1; dy++) {
            int y = spawner.getY() + dy;
            if (y < world.getMinHeight() || y + 1 >= world.getMaxHeight()) continue;

            for (int dx = -range; dx <= range; dx++) {
                for (int dz = -range; dz <= range; dz++) {
                    int x = spawner.getX() + dx;
                    int z = spawner.getZ() + dz;
                    Block feet = world.getBlockAt(x, y, z);
                    Block head = world.getBlockAt(x, y + 1, z);
                    if (!feet.getType().isAir() || !head.getType().isAir()) continue;

                    valid++;
                    if (random.nextInt(valid) == 0) {
                        selected = new Location(world, x + 0.5D, y, z + 0.5D);
                    }
                }
            }
        }
        return selected;
    }

    private boolean vanillaHasQualifyingPlayer(CreatureSpawner spawner) {
        int requiredRange = spawner.getRequiredPlayerRange();
        if (requiredRange <= 0) {
            return Bukkit.getOnlinePlayers().stream()
                    .anyMatch(player -> player.isOnline() && !player.isDead() && player.getGameMode() != GameMode.SPECTATOR);
        }

        double maxDistanceSquared = (double) requiredRange * requiredRange;
        Location center = spawner.getLocation().add(0.5D, 0.5D, 0.5D);
        for (Player player : spawner.getWorld().getPlayers()) {
            if (!player.isOnline() || player.isDead() || player.getGameMode() == GameMode.SPECTATOR) continue;
            if (player.getLocation().distanceSquared(center) <= maxDistanceSquared) return true;
        }
        return false;
    }

    private int nearbySameType(CreatureSpawner spawner, EntityType type) {
        int range = Math.max(1, spawner.getSpawnRange());
        Location center = spawner.getLocation().add(0.5D, 0.5D, 0.5D);
        return center.getWorld().getNearbyEntities(center, range, range, range,
                entity -> entity instanceof LivingEntity && entity.getType() == type).size();
    }

    private int randomDelay(CreatureSpawner spawner) {
        int min = Math.max(1, spawner.getMinSpawnDelay());
        int max = Math.max(min, spawner.getMaxSpawnDelay());
        return min == max ? min : min + random.nextInt(max - min + 1);
    }

    private void repairLegacySuppression(Chunk chunk) {
        for (BlockState blockState : chunk.getTileEntities(false)) {
            if (!(blockState instanceof CreatureSpawner spawner)) continue;
            boolean changed = false;

            // v0.2.2 wrote zero here to suppress vanilla ticking. If a restart
            // happened before restoration, zero became permanent and the spawner
            // stopped working even after the loader ran out of fuel.
            if (spawner.getMaxNearbyEntities() <= 0) {
                spawner.setMaxNearbyEntities(VANILLA_DEFAULT_MAX_NEARBY);
                changed = true;
            }

            // v0.2.1 could also leave physical/non-Mira spawners with a zero
            // required range. Mira-managed spawners intentionally use zero, so
            // only repair ordinary physical spawners here.
            if (spawner.getRequiredPlayerRange() <= 0 && !isMiraManagedSpawner(spawner)) {
                spawner.setRequiredPlayerRange(VANILLA_DEFAULT_REQUIRED_RANGE);
                changed = true;
            }

            if (changed) spawner.update(true, false);
        }
    }

    private boolean isMiraManagedSpawner(CreatureSpawner spawner) {
        if (MIRA_MANAGED_SPAWNER == null) return false;
        Byte managed = spawner.getPersistentDataContainer().get(MIRA_MANAGED_SPAWNER, PersistentDataType.BYTE);
        return managed != null && managed == (byte) 1;
    }

    private record SpawnerState(int delay) {
        SpawnerState withDelay(int value) { return new SpawnerState(value); }
    }

    private record SpawnerKey(UUID worldId, int x, int y, int z) {
        static SpawnerKey of(Block block) {
            return new SpawnerKey(block.getWorld().getUID(), block.getX(), block.getY(), block.getZ());
        }
        int chunkX() { return x >> 4; }
        int chunkZ() { return z >> 4; }
    }
}
