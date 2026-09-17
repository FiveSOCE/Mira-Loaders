package com.mira.loaders.service;

import com.mira.loaders.LoaderRecord;
import com.mira.loaders.MiraLoadersPlugin;
import net.milkbowl.vault.economy.Economy;
import net.milkbowl.vault.economy.EconomyResponse;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataType;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

public final class LoaderService {
    private static final long HOUR_MILLIS = 3_600_000L;

    private final MiraLoadersPlugin plugin;
    private final Economy economy;
    private final ChunkColumnSimulationService simulation;
    private final NamespacedKey loaderItemKey;
    private final File dataFile;
    private final Map<UUID, LoaderRecord> records = new LinkedHashMap<>();
    private final Set<UUID> ticketed = new HashSet<>();

    public LoaderService(MiraLoadersPlugin plugin, Economy economy, ChunkColumnSimulationService simulation) {
        this.plugin = plugin;
        this.economy = economy;
        this.simulation = simulation;
        this.loaderItemKey = new NamespacedKey(plugin, "chunk_loader_item");
        this.dataFile = new File(plugin.getDataFolder(), "loaders.yml");
    }

    public void load() {
        records.clear();
        ticketed.clear();
        if (!dataFile.exists()) {
            save();
            return;
        }

        YamlConfiguration yaml = YamlConfiguration.loadConfiguration(dataFile);
        ConfigurationSection root = yaml.getConfigurationSection("loaders");
        if (root == null) return;

        for (String key : root.getKeys(false)) {
            try {
                UUID id = UUID.fromString(key);
                ConfigurationSection section = root.getConfigurationSection(key);
                if (section == null) continue;
                LoaderRecord record = new LoaderRecord(
                        id,
                        UUID.fromString(section.getString("owner", "")),
                        UUID.fromString(section.getString("world", "")),
                        section.getInt("x"), section.getInt("y"), section.getInt("z"),
                        section.getLong("expires-at", 0L)
                );
                records.put(id, record);
            } catch (IllegalArgumentException ex) {
                plugin.getLogger().warning("Skipping invalid loader record " + key);
            }
        }
        reconcileAll();
    }

    public void shutdown() {
        simulation.shutdown();
        for (LoaderRecord record : records.values()) removeChunkTicketOnly(record);
        save();
    }

    public int loaderCount() { return records.size(); }

    public Collection<LoaderRecord> records() { return Collections.unmodifiableCollection(records.values()); }

    public ItemStack createLoaderItem(int amount) {
        ItemStack item = new ItemStack(Material.BEACON, Math.max(1, amount));
        ItemMeta meta = item.getItemMeta();
        meta.setDisplayName(plugin.loaderItemName());
        meta.setLore(plugin.loaderItemLore());
        meta.getPersistentDataContainer().set(loaderItemKey, PersistentDataType.BYTE, (byte) 1);
        item.setItemMeta(meta);
        return item;
    }

    public boolean isLoaderItem(ItemStack item) {
        if (item == null || item.getType() != Material.BEACON || !item.hasItemMeta()) return false;
        Byte value = item.getItemMeta().getPersistentDataContainer().get(loaderItemKey, PersistentDataType.BYTE);
        return value != null && value == (byte) 1;
    }

    public Optional<LoaderRecord> at(Block block) {
        if (block == null) return Optional.empty();
        return records.values().stream().filter(record ->
                record.worldId().equals(block.getWorld().getUID()) &&
                record.x() == block.getX() && record.y() == block.getY() && record.z() == block.getZ()
        ).findFirst();
    }

    public boolean chunkAlreadyHasLoader(World world, int chunkX, int chunkZ) {
        return records.values().stream().anyMatch(record -> record.worldId().equals(world.getUID()) && record.chunkX() == chunkX && record.chunkZ() == chunkZ);
    }

    public LoaderRecord register(Block block, Player owner) {
        UUID id = UUID.randomUUID();
        LoaderRecord record = new LoaderRecord(id, owner.getUniqueId(), block.getWorld().getUID(), block.getX(), block.getY(), block.getZ(), 0L);
        records.put(id, record);
        save();
        return record;
    }

    public boolean canRemove(Player player, LoaderRecord record) {
        return record.owner().equals(player.getUniqueId()) || player.hasPermission("miraloaders.admin");
    }

    public boolean depositHour(Player player, LoaderRecord record) {
        long now = System.currentTimeMillis();
        long base = Math.max(now, record.expiresAt());
        long maxExpiry = now + plugin.maximumHours() * HOUR_MILLIS;
        if (base + HOUR_MILLIS > maxExpiry) {
            plugin.send(player, "&cThis loader can store a maximum of &e" + plugin.maximumHours() + " hours&c.");
            return false;
        }

        double cost = plugin.hourlyCost();
        if (!economy.has(player, cost)) {
            plugin.send(player, "&cYou need &a$" + formatMoney(cost) + " &cto add one hour.");
            return false;
        }

        EconomyResponse response = economy.withdrawPlayer(player, cost);
        if (!response.transactionSuccess()) {
            plugin.send(player, "&cPayment failed: &7" + response.errorMessage);
            return false;
        }

        LoaderRecord updated = record.withExpiresAt(base + HOUR_MILLIS);
        records.put(updated.id(), updated);
        ensureActive(updated);
        save();
        plugin.send(player, "&aAdded &e1 hour &ato this loader for &f$" + formatMoney(cost) + "&a.");
        return true;
    }

    public ItemStack remove(Player player, LoaderRecord record) {
        deactivate(record);
        records.remove(record.id());
        save();
        return createLoaderItem(1);
    }

    public long remainingMillis(LoaderRecord record) {
        return Math.max(0L, record.expiresAt() - System.currentTimeMillis());
    }

    public String formatRemaining(LoaderRecord record) {
        long seconds = remainingMillis(record) / 1000L;
        long hours = seconds / 3600L;
        long minutes = (seconds % 3600L) / 60L;
        long secs = seconds % 60L;
        return String.format("%02dh %02dm %02ds", hours, minutes, secs);
    }

    public void tick() {
        long now = System.currentTimeMillis();
        boolean changed = false;
        for (LoaderRecord record : new ArrayList<>(records.values())) {
            World world = Bukkit.getWorld(record.worldId());
            if (world == null) continue;

            Block block = world.getBlockAt(record.x(), record.y(), record.z());
            if (block.getType() != Material.BEACON) {
                deactivate(record);
                records.remove(record.id());
                changed = true;
                continue;
            }

            if (record.active(now)) ensureActive(record);
            else deactivate(record);
        }
        if (changed) save();
    }

    private void reconcileAll() {
        boolean changed = false;
        long now = System.currentTimeMillis();
        for (LoaderRecord record : new ArrayList<>(records.values())) {
            World world = Bukkit.getWorld(record.worldId());
            if (world == null) continue;
            Block block = world.getBlockAt(record.x(), record.y(), record.z());
            if (block.getType() != Material.BEACON) {
                records.remove(record.id());
                changed = true;
                continue;
            }
            if (record.active(now)) ensureActive(record);
            else simulation.remove(record); // cleans any stale force-load marker after an unclean stop
        }
        if (changed) save();
    }

    private void ensureActive(LoaderRecord record) {
        World world = Bukkit.getWorld(record.worldId());
        if (world == null) return;

        if (!ticketed.contains(record.id())) {
            world.getChunkAt(record.chunkX(), record.chunkZ()).addPluginChunkTicket(plugin);
            ticketed.add(record.id());
        }

        // The chunk ticket/force-load supplies real chunk game logic. The column
        // simulator removes player-proximity gates without creating a fake player.
        simulation.ensure(record);
    }

    private void deactivate(LoaderRecord record) {
        simulation.remove(record);
        removeChunkTicketOnly(record);
    }

    private void removeChunkTicketOnly(LoaderRecord record) {
        if (!ticketed.remove(record.id())) return;
        World world = Bukkit.getWorld(record.worldId());
        if (world == null) return;
        world.getChunkAt(record.chunkX(), record.chunkZ()).removePluginChunkTicket(plugin);
    }

    public void save() {
        YamlConfiguration yaml = new YamlConfiguration();
        for (LoaderRecord record : records.values()) {
            String path = "loaders." + record.id();
            yaml.set(path + ".owner", record.owner().toString());
            yaml.set(path + ".world", record.worldId().toString());
            yaml.set(path + ".x", record.x());
            yaml.set(path + ".y", record.y());
            yaml.set(path + ".z", record.z());
            yaml.set(path + ".expires-at", record.expiresAt());
        }
        try {
            plugin.getDataFolder().mkdirs();
            yaml.save(dataFile);
        } catch (IOException ex) {
            plugin.getLogger().severe("Failed to save loaders.yml: " + ex.getMessage());
        }
    }

    public String formatMoney(double value) {
        return String.format("%,.0f", value);
    }
}
