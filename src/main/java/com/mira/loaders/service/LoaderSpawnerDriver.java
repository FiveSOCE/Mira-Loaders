package com.mira.loaders.service;

import com.mira.loaders.MiraLoadersPlugin;
import org.bukkit.Chunk;
import org.bukkit.Location;
import org.bukkit.NamespacedKey;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.BlockState;
import org.bukkit.block.CreatureSpawner;
import org.bukkit.entity.Entity;
import org.bukkit.entity.EntityType;
import org.bukkit.entity.LivingEntity;
import org.bukkit.event.entity.CreatureSpawnEvent;
import org.bukkit.persistence.PersistentDataType;

import java.util.HashMap;
import java.util.Map;
import java.util.Random;
import java.util.UUID;

/**
 * Runs physical CreatureSpawner clocks while a Mira loader owns the chunk.
 *
 * Vanilla's requiredPlayerRange <= 0 shortcut still requires at least one real
 * player to exist. Loader chunks must continue spawning with zero players, so
 * this service owns the spawn clock while the loader is fueled and temporarily
 * suppresses the vanilla spawn attempt by setting maxNearbyEntities to zero.
 * Original settings are restored when the loader deactivates.
 */
public final class LoaderSpawnerDriver {
    public static final String SOURCE_MARKER = "loader_spawner_spawn";
    public static final String SOURCE_WORLD = "loader_spawner_world";
    public static final String SOURCE_X = "loader_spawner_x";
    public static final String SOURCE_Y = "loader_spawner_y";
    public static final String SOURCE_Z = "loader_spawner_z";

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

    public void prepare(Chunk chunk) {
        for (BlockState state : chunk.getTileEntities(false)) {
            if (state instanceof CreatureSpawner spawner) prepareSpawner(spawner);
        }
    }

    public void tick(Chunk chunk) {
        for (BlockState state : chunk.getTileEntities(false)) {
            if (!(state instanceof CreatureSpawner spawner)) continue;
            prepareSpawner(spawner);
            tickSpawner(spawner);
        }
    }

    public void restore(Chunk chunk) {
        for (Map.Entry<SpawnerKey, SpawnerState> entry : Map.copyOf(states).entrySet()) {
            SpawnerKey key = entry.getKey();
            if (!key.worldId().equals(chunk.getWorld().getUID()) || key.chunkX() != chunk.getX() || key.chunkZ() != chunk.getZ()) continue;
            restoreSpawner(key, entry.getValue());
            states.remove(key);
        }
    }

    public void restoreAll() {
        for (Map.Entry<SpawnerKey, SpawnerState> entry : Map.copyOf(states).entrySet()) {
            restoreSpawner(entry.getKey(), entry.getValue());
        }
        states.clear();
    }

    private void prepareSpawner(CreatureSpawner spawner) {
        SpawnerKey key = SpawnerKey.of(spawner.getBlock());
        if (states.containsKey(key)) return;

        int originalMaxNearby = spawner.getMaxNearbyEntities();
        int initialDelay = spawner.getDelay();
        if (initialDelay < 0) initialDelay = randomDelay(spawner);
        states.put(key, new SpawnerState(Math.max(0, originalMaxNearby), initialDelay));

        // Prevent the vanilla player-gated tick path from producing duplicates.
        // Our own clock below becomes authoritative only for the fueled loader.
        spawner.setMaxNearbyEntities(0);
        spawner.update(true, false);
    }

    private void tickSpawner(CreatureSpawner spawner) {
        SpawnerKey key = SpawnerKey.of(spawner.getBlock());
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

        int allowedNearby = state.originalMaxNearby();
        if (allowedNearby > 0 && nearbySameType(spawner, type) >= allowedNearby) {
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

        // Native spawners reset after a successful batch. If all attempts fail,
        // retry shortly rather than busy-looping every tick forever.
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

    private Location findSpawnLocation(CreatureSpawner spawner) {
        World world = spawner.getWorld();
        int range = Math.max(1, spawner.getSpawnRange());
        double x = spawner.getX() + 0.5D + ((random.nextDouble() - random.nextDouble()) * range);
        double y = spawner.getY() + random.nextInt(3) - 1;
        double z = spawner.getZ() + 0.5D + ((random.nextDouble() - random.nextDouble()) * range);
        Location candidate = new Location(world, x, y, z);

        Block feet = candidate.getBlock();
        Block head = world.getBlockAt(feet.getX(), feet.getY() + 1, feet.getZ());
        if (!feet.isPassable() || !head.isPassable()) return null;
        return candidate;
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

    private void restoreSpawner(SpawnerKey key, SpawnerState saved) {
        World world = plugin.getServer().getWorld(key.worldId());
        if (world == null) return;
        BlockState state = world.getBlockAt(key.x(), key.y(), key.z()).getState();
        if (!(state instanceof CreatureSpawner spawner)) return;
        spawner.setMaxNearbyEntities(saved.originalMaxNearby());
        spawner.setDelay(Math.max(0, saved.delay()));
        spawner.update(true, false);
    }

    private record SpawnerState(int originalMaxNearby, int delay) {
        SpawnerState withDelay(int value) { return new SpawnerState(originalMaxNearby, value); }
    }

    private record SpawnerKey(UUID worldId, int x, int y, int z) {
        static SpawnerKey of(Block block) {
            return new SpawnerKey(block.getWorld().getUID(), block.getX(), block.getY(), block.getZ());
        }
        int chunkX() { return x >> 4; }
        int chunkZ() { return z >> 4; }
    }
}
