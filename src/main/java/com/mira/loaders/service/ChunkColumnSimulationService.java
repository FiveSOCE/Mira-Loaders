package com.mira.loaders.service;

import com.mira.loaders.LoaderRecord;
import com.mira.loaders.MiraLoadersPlugin;
import org.bukkit.Bukkit;
import org.bukkit.Chunk;
import org.bukkit.NamespacedKey;
import org.bukkit.World;
import org.bukkit.entity.Entity;
import org.bukkit.persistence.PersistentDataType;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Keeps fueled loader chunks operating as complete vertical simulation columns
 * without synthetic/fake players.
 */
public final class ChunkColumnSimulationService {
    private final MiraLoadersPlugin plugin;
    private final NamespacedKey forcedByMiraKey;
    private final Map<UUID, LoaderRecord> active = new LinkedHashMap<>();
    private final Set<UUID> warnedLoadLevel = new HashSet<>();
    private final Map<Class<?>, Method> getHandleMethods = new HashMap<>();
    private final LoaderSpawnerDriver spawnerDriver;

    private Field minecraftCurrentTickField;
    private boolean activationReflectionResolved;
    private boolean activationReflectionUnavailable;
    private boolean pauseVoteApplied;

    public ChunkColumnSimulationService(MiraLoadersPlugin plugin) {
        this.plugin = plugin;
        this.forcedByMiraKey = new NamespacedKey(plugin, "forced_by_mira_loader");
        this.spawnerDriver = new LoaderSpawnerDriver(plugin);
    }

    public void ensure(LoaderRecord record) {
        active.put(record.id(), record);
        Chunk chunk = chunk(record);
        if (chunk == null) return;

        ensureForceLoaded(chunk);
        spawnerDriver.prepare(chunk);
        applyPauseVote(false);
    }

    public void remove(LoaderRecord record) {
        active.remove(record.id());
        Chunk chunk = chunk(record);
        if (chunk != null) spawnerDriver.restore(chunk);
        releaseForceLoaded(record);
        warnedLoadLevel.remove(record.id());
        if (active.isEmpty()) applyPauseVote(true);
    }

    public void shutdown() {
        spawnerDriver.restoreAll();
        for (LoaderRecord record : new ArrayList<>(active.values())) releaseForceLoaded(record);
        active.clear();
        warnedLoadLevel.clear();
        applyPauseVote(true);
    }

    /** Called once every server tick. */
    public void tick() {
        if (active.isEmpty()) return;

        long currentTick = currentServerTick();
        for (LoaderRecord record : new ArrayList<>(active.values())) {
            Chunk chunk = chunk(record);
            if (chunk == null) continue;

            ensureForceLoaded(chunk);
            verifyEntityTicking(record, chunk);
            activateChunkEntities(chunk, currentTick);
            spawnerDriver.tick(chunk);
        }
    }

    private Chunk chunk(LoaderRecord record) {
        World world = Bukkit.getWorld(record.worldId());
        if (world == null) return null;
        return world.getChunkAt(record.chunkX(), record.chunkZ());
    }

    private void ensureForceLoaded(Chunk chunk) {
        if (chunk.isForceLoaded()) return;
        chunk.setForceLoaded(true);
        chunk.getPersistentDataContainer().set(forcedByMiraKey, PersistentDataType.BYTE, (byte) 1);
    }

    private void releaseForceLoaded(LoaderRecord record) {
        Chunk chunk = chunk(record);
        if (chunk == null) return;
        Byte ours = chunk.getPersistentDataContainer().get(forcedByMiraKey, PersistentDataType.BYTE);
        if (ours == null || ours != (byte) 1) return;

        chunk.getPersistentDataContainer().remove(forcedByMiraKey);
        if (chunk.isForceLoaded()) chunk.setForceLoaded(false);
    }

    private void verifyEntityTicking(LoaderRecord record, Chunk chunk) {
        if (chunk.getLoadLevel() == Chunk.LoadLevel.ENTITY_TICKING) {
            warnedLoadLevel.remove(record.id());
            return;
        }
        if (warnedLoadLevel.add(record.id())) {
            plugin.getLogger().warning("Loader " + record.id() + " chunk " + record.chunkX() + "," + record.chunkZ()
                    + " is currently " + chunk.getLoadLevel() + " instead of ENTITY_TICKING; MiraLoaders will keep the"
                    + " ticket/force-load applied and continue enforcing column simulation.");
        }
    }

    private void activateChunkEntities(Chunk chunk, long currentTick) {
        if (activationReflectionUnavailable) return;
        try {
            resolveActivationReflection();
            if (activationReflectionUnavailable) return;

            long activeUntil = currentTick + 2L;
            for (Entity entity : chunk.getEntities()) {
                Method getHandle = getHandleMethods.computeIfAbsent(entity.getClass(), type -> {
                    try {
                        Method method = type.getMethod("getHandle");
                        method.setAccessible(true);
                        return method;
                    } catch (ReflectiveOperationException ex) {
                        return null;
                    }
                });
                if (getHandle == null) continue;

                Object nms = getHandle.invoke(entity);
                Field activatedTick = findField(nms.getClass(), "activatedTick");
                if (activatedTick == null) continue;
                if (activatedTick.getType() == long.class) activatedTick.setLong(nms, activeUntil);
                else if (activatedTick.getType() == int.class) activatedTick.setInt(nms, (int) Math.min(Integer.MAX_VALUE, activeUntil));
            }
        } catch (Throwable ex) {
            activationReflectionUnavailable = true;
            plugin.getLogger().warning("Could not force full-column entity activation on this Paper build: " + rootMessage(ex));
        }
    }

    private void resolveActivationReflection() {
        if (activationReflectionResolved || activationReflectionUnavailable) return;
        activationReflectionResolved = true;
        try {
            Class<?> minecraftServer = Class.forName("net.minecraft.server.MinecraftServer");
            minecraftCurrentTickField = findField(minecraftServer, "currentTick");
            if (minecraftCurrentTickField == null) throw new NoSuchFieldException("MinecraftServer.currentTick");
        } catch (Throwable ex) {
            activationReflectionUnavailable = true;
            plugin.getLogger().warning("Paper entity activation hook is unavailable: " + rootMessage(ex));
        }
    }

    private long currentServerTick() {
        resolveActivationReflection();
        if (minecraftCurrentTickField == null) return Bukkit.getCurrentTick();
        try {
            if (minecraftCurrentTickField.getType() == long.class) return minecraftCurrentTickField.getLong(null);
            if (minecraftCurrentTickField.getType() == int.class) return minecraftCurrentTickField.getInt(null);
        } catch (IllegalAccessException ignored) {
        }
        return Bukkit.getCurrentTick();
    }

    private void applyPauseVote(boolean allowPause) {
        if (allowPause && !pauseVoteApplied) return;
        if (!allowPause && pauseVoteApplied) return;
        try {
            Method method = Bukkit.getServer().getClass().getMethod("allowPausing", org.bukkit.plugin.Plugin.class, boolean.class);
            method.invoke(Bukkit.getServer(), plugin, allowPause);
            pauseVoteApplied = !allowPause;
        } catch (Throwable ignored) {
            // Older supported Paper versions may not expose server pausing.
        }
    }

    private static Field findField(Class<?> type, String name) {
        Class<?> cursor = type;
        while (cursor != null) {
            try {
                Field field = cursor.getDeclaredField(name);
                field.setAccessible(true);
                return field;
            } catch (NoSuchFieldException ignored) {
                cursor = cursor.getSuperclass();
            }
        }
        return null;
    }

    private static String rootMessage(Throwable throwable) {
        Throwable root = throwable;
        while (root.getCause() != null) root = root.getCause();
        return root.getClass().getSimpleName() + ": " + root.getMessage();
    }
}
