package com.mira.loaders.service;

import com.mira.loaders.LoaderRecord;
import com.mira.loaders.MiraLoadersPlugin;
import org.bukkit.Bukkit;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.metadata.FixedMetadataValue;
import org.bukkit.util.Vector;

import java.lang.reflect.Array;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Creates a real server-side player simulation source for each fueled loader.
 *
 * A Bukkit/Paper plugin chunk ticket only prevents unloading. It does not make
 * every vanilla system behave as though a player is present. These anchors go
 * through Minecraft's normal ServerPlayer registration path so random ticks,
 * mob spawning, player-proximity checks, entity activation and simulation
 * distance all see a player at the loader position.
 *
 * NMS access is reflection-only so the release JAR can stay API-compatible
 * across both supported Paper lines.
 */
public final class SimulationAnchorService implements Listener {
    public static final String SYNTHETIC_METADATA = "miraloaders.synthetic";

    private final MiraLoadersPlugin plugin;
    private final Map<UUID, Anchor> anchors = new HashMap<>();
    private final Map<UUID, UUID> syntheticPlayers = new HashMap<>();
    private boolean warnedUnavailable;

    public SimulationAnchorService(MiraLoadersPlugin plugin) {
        this.plugin = plugin;
    }

    public void ensure(LoaderRecord record) {
        Anchor existing = anchors.get(record.id());
        if (existing != null && existing.player().isOnline()) {
            maintain(record, existing);
            return;
        }

        if (existing != null) retire(record.id(), existing);
        try {
            Anchor created = spawn(record);
            anchors.put(record.id(), created);
            syntheticPlayers.put(created.player().getUniqueId(), record.id());
            maintain(record, created);
            plugin.getLogger().info("Activated full simulation anchor " + created.player().getName()
                    + " for loader " + record.id() + " in chunk " + record.chunkX() + "," + record.chunkZ());
        } catch (Throwable ex) {
            if (!warnedUnavailable) {
                warnedUnavailable = true;
                plugin.getLogger().severe("Could not create MiraLoaders full simulation anchor. "
                        + "The chunk will remain loaded but player-dependent mechanics may not run: " + rootMessage(ex));
                ex.printStackTrace();
            }
        }
    }

    public void remove(LoaderRecord record) {
        Anchor anchor = anchors.remove(record.id());
        if (anchor != null) retire(record.id(), anchor);
    }

    public void shutdown() {
        for (Map.Entry<UUID, Anchor> entry : Map.copyOf(anchors).entrySet()) {
            retire(entry.getKey(), entry.getValue());
        }
        anchors.clear();
        syntheticPlayers.clear();
    }

    public boolean isSynthetic(Player player) {
        return player != null && (player.hasMetadata(SYNTHETIC_METADATA)
                || syntheticPlayers.containsKey(player.getUniqueId()));
    }

    private Anchor spawn(LoaderRecord record) throws Exception {
        World world = Bukkit.getWorld(record.worldId());
        if (world == null) throw new IllegalStateException("Loader world is unavailable");

        Object craftServer = Bukkit.getServer();
        Object minecraftServer = craftServer.getClass().getMethod("getServer").invoke(craftServer);
        Object serverLevel = world.getClass().getMethod("getHandle").invoke(world);

        Class<?> gameProfileClass = Class.forName("com.mojang.authlib.GameProfile");
        UUID fakeUuid = UUID.nameUUIDFromBytes(("MiraLoader:" + record.id()).getBytes(StandardCharsets.UTF_8));
        String name = "MiraLdr_" + record.id().toString().replace("-", "").substring(0, 8);
        Object profile = gameProfileClass.getConstructor(UUID.class, String.class).newInstance(fakeUuid, name);

        Class<?> cookieClass = Class.forName("net.minecraft.server.network.CommonListenerCookie");
        Method createInitial = cookieClass.getMethod("createInitial", gameProfileClass, boolean.class);
        Object cookie = createInitial.invoke(null, profile, false);
        Object clientInformation = cookieClass.getMethod("clientInformation").invoke(cookie);

        Class<?> serverPlayerClass = Class.forName("net.minecraft.server.level.ServerPlayer");
        Constructor<?> playerCtor = findConstructor(serverPlayerClass,
                minecraftServer.getClass(), serverLevel.getClass(), gameProfileClass, clientInformation.getClass());
        Object serverPlayer = playerCtor.newInstance(minecraftServer, serverLevel, profile, clientInformation);

        Player bukkitPlayer = (Player) serverPlayerClass.getMethod("getBukkitEntity").invoke(serverPlayer);
        bukkitPlayer.setMetadata(SYNTHETIC_METADATA, new FixedMetadataValue(plugin, record.id().toString()));

        Class<?> packetFlowClass = Class.forName("net.minecraft.network.protocol.PacketFlow");
        @SuppressWarnings({"rawtypes", "unchecked"})
        Object serverbound = Enum.valueOf((Class<? extends Enum>) packetFlowClass.asSubclass(Enum.class), "SERVERBOUND");
        Class<?> connectionClass = Class.forName("net.minecraft.network.Connection");
        Object connection = connectionClass.getConstructor(packetFlowClass).newInstance(serverbound);

        Class<?> channelHandlerClass = Class.forName("io.netty.channel.ChannelHandler");
        Class<?> embeddedChannelClass = Class.forName("io.netty.channel.embedded.EmbeddedChannel");
        Object handlers = Array.newInstance(channelHandlerClass, 1);
        Array.set(handlers, 0, connection);
        Constructor<?> channelCtor = embeddedChannelClass.getConstructor(handlers.getClass());
        Object channel = channelCtor.newInstance(handlers);

        Object playerList = minecraftServer.getClass().getMethod("getPlayerList").invoke(minecraftServer);
        Method placeNewPlayer = findMethod(playerList.getClass(), "placeNewPlayer", connectionClass, serverPlayerClass, cookieClass);
        placeNewPlayer.invoke(playerList, connection, serverPlayer, cookie);

        // The normal login path may relocate a fresh profile to world spawn, so
        // pin it to the loader after registration.
        bukkitPlayer.teleport(anchorLocation(record));
        configurePlayer(bukkitPlayer);
        hideFromRealPlayers(bukkitPlayer);
        disableNetworkTimeouts(serverPlayer);
        drainChannel(channel);

        return new Anchor(serverPlayer, bukkitPlayer, channel, playerList);
    }

    private void maintain(LoaderRecord record, Anchor anchor) {
        Player player = anchor.player();
        if (!player.isOnline()) return;

        Location target = anchorLocation(record);
        Location current = player.getLocation();
        if (current.getWorld() != target.getWorld() || current.distanceSquared(target) > 0.25D) {
            player.teleport(target);
        }

        configurePlayer(player);
        hideFromRealPlayers(player);
        disableNetworkTimeouts(anchor.nmsPlayer());
        drainChannel(anchor.channel());
    }

    private Location anchorLocation(LoaderRecord record) {
        World world = Bukkit.getWorld(record.worldId());
        if (world == null) throw new IllegalStateException("Loader world is unavailable");
        return new Location(world, record.x() + 0.5D, record.y() + 1.05D, record.z() + 0.5D, 0.0F, 0.0F);
    }

    private void configurePlayer(Player player) {
        try { player.setGameMode(GameMode.SURVIVAL); } catch (Throwable ignored) {}
        player.setInvulnerable(true);
        player.setInvisible(true);
        player.setSilent(true);
        player.setCollidable(false);
        player.setGravity(false);
        player.setCanPickupItems(false);
        player.setSleepingIgnored(true);
        player.setFireTicks(0);
        player.setFreezeTicks(0);
        player.setFallDistance(0.0F);
        player.setVelocity(new Vector(0, 0, 0));
        player.setFoodLevel(20);
        player.setSaturation(20.0F);
        if (!player.isDead() && player.getHealth() < player.getMaxHealth()) player.setHealth(player.getMaxHealth());

        // Keep the synthetic player's own radius as tight as Paper permits.
        // The loader itself still represents the containing chunk; neighbouring
        // simulation is only the unavoidable vanilla player simulation fringe.
        try { player.setSimulationDistance(2); } catch (Throwable ignored) {}
        try { player.setViewDistance(2); } catch (Throwable ignored) {}
        try { player.setSendViewDistance(2); } catch (Throwable ignored) {}
    }

    private void hideFromRealPlayers(Player fake) {
        for (Player viewer : Bukkit.getOnlinePlayers()) {
            if (viewer.getUniqueId().equals(fake.getUniqueId()) || isSynthetic(viewer)) continue;
            try { viewer.hidePlayer(plugin, fake); } catch (Throwable ignored) {}
            try { viewer.unlistPlayer(fake); } catch (Throwable ignored) {}
        }
    }

    private void disableNetworkTimeouts(Object serverPlayer) {
        try {
            Field connectionField = findField(serverPlayer.getClass(), "connection");
            Object listener = connectionField.get(serverPlayer);
            if (listener == null) return;
            setField(listener, "keepAlivePending", false);
            setField(listener, "keepAliveTime", System.currentTimeMillis());
            setField(listener, "clientLoadedTimeoutTimer", Integer.MAX_VALUE);
        } catch (Throwable ignored) {
            // A later ensure() will respawn the anchor if Paper disconnects it.
        }
    }

    private void drainChannel(Object channel) {
        if (channel == null) return;
        try {
            Method runPendingTasks = channel.getClass().getMethod("runPendingTasks");
            runPendingTasks.invoke(channel);
        } catch (Throwable ignored) {}
        try {
            Method readOutbound = channel.getClass().getMethod("readOutbound");
            Method release = Class.forName("io.netty.util.ReferenceCountUtil").getMethod("release", Object.class);
            while (true) {
                Object message = readOutbound.invoke(channel);
                if (message == null) break;
                try { release.invoke(null, message); } catch (Throwable ignored) {}
            }
        } catch (Throwable ignored) {}
    }

    private void retire(UUID loaderId, Anchor anchor) {
        syntheticPlayers.remove(anchor.player().getUniqueId());
        try {
            Object nmsPlayer = anchor.nmsPlayer();
            Method remove = findMethodByAssignableParameters(anchor.playerList().getClass(), "remove", nmsPlayer.getClass());
            remove.invoke(anchor.playerList(), nmsPlayer);
        } catch (Throwable ex) {
            try { anchor.player().kickPlayer("Mira loader simulation ended"); } catch (Throwable ignored) {}
        }
        try {
            Method finishAndReleaseAll = anchor.channel().getClass().getMethod("finishAndReleaseAll");
            finishAndReleaseAll.invoke(anchor.channel());
        } catch (Throwable ignored) {}
        plugin.getLogger().info("Stopped full simulation anchor for loader " + loaderId);
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onJoin(PlayerJoinEvent event) {
        Player player = event.getPlayer();
        if (isSynthetic(player)) {
            event.joinMessage(null);
            return;
        }
        for (Anchor anchor : anchors.values()) {
            if (!anchor.player().isOnline()) continue;
            try { player.hidePlayer(plugin, anchor.player()); } catch (Throwable ignored) {}
            try { player.unlistPlayer(anchor.player()); } catch (Throwable ignored) {}
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onQuit(PlayerQuitEvent event) {
        if (isSynthetic(event.getPlayer())) event.quitMessage(null);
    }

    private static Constructor<?> findConstructor(Class<?> type, Class<?>... args) throws NoSuchMethodException {
        for (Constructor<?> ctor : type.getConstructors()) {
            Class<?>[] params = ctor.getParameterTypes();
            if (params.length != args.length) continue;
            boolean match = true;
            for (int i = 0; i < params.length; i++) {
                if (!params[i].isAssignableFrom(args[i])) { match = false; break; }
            }
            if (match) return ctor;
        }
        throw new NoSuchMethodException(type.getName() + " constructor with " + args.length + " parameters");
    }

    private static Method findMethod(Class<?> type, String name, Class<?>... params) throws NoSuchMethodException {
        try { return type.getMethod(name, params); }
        catch (NoSuchMethodException ignored) { return findMethodByAssignableParameters(type, name, params); }
    }

    private static Method findMethodByAssignableParameters(Class<?> type, String name, Class<?>... args) throws NoSuchMethodException {
        for (Method method : type.getMethods()) {
            if (!method.getName().equals(name) || method.getParameterCount() != args.length) continue;
            Class<?>[] params = method.getParameterTypes();
            boolean match = true;
            for (int i = 0; i < params.length; i++) {
                if (!params[i].isAssignableFrom(args[i])) { match = false; break; }
            }
            if (match) return method;
        }
        throw new NoSuchMethodException(type.getName() + "#" + name);
    }

    private static Field findField(Class<?> type, String name) throws NoSuchFieldException {
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
        throw new NoSuchFieldException(name);
    }

    private static void setField(Object target, String name, Object value) {
        try { findField(target.getClass(), name).set(target, value); }
        catch (Throwable ignored) {}
    }

    private static String rootMessage(Throwable throwable) {
        Throwable root = throwable;
        while (root.getCause() != null) root = root.getCause();
        return root.getClass().getSimpleName() + ": " + root.getMessage();
    }

    private record Anchor(Object nmsPlayer, Player player, Object channel, Object playerList) {}
}
