package com.mira.loaders.gui;

import com.mira.loaders.LoaderRecord;
import com.mira.loaders.MiraLoadersPlugin;
import com.mira.loaders.service.LoaderService;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;

import java.util.*;

public final class LoaderGui implements Listener {
    private static final int TIMER_SLOT = 11;
    private static final int DEPOSIT_SLOT = 13;
    private static final int REMOVE_SLOT = 15;

    private final MiraLoadersPlugin plugin;
    private final LoaderService service;
    private final Map<UUID, UUID> open = new HashMap<>();

    public LoaderGui(MiraLoadersPlugin plugin, LoaderService service) {
        this.plugin = plugin;
        this.service = service;
    }

    public void open(Player player, LoaderRecord record) {
        LoaderHolder holder = new LoaderHolder(record.id());
        Inventory inventory = Bukkit.createInventory(holder, 27, plugin.color("&8Mira Chunk Loader"));
        holder.inventory = inventory;
        render(inventory, record, player);
        open.put(player.getUniqueId(), record.id());
        player.openInventory(inventory);
    }

    public void refreshOpenGuis() {
        for (Map.Entry<UUID, UUID> entry : new HashMap<>(open).entrySet()) {
            Player player = Bukkit.getPlayer(entry.getKey());
            if (player == null || !(player.getOpenInventory().getTopInventory().getHolder() instanceof LoaderHolder holder)) {
                open.remove(entry.getKey());
                continue;
            }
            LoaderRecord record = service.records().stream().filter(r -> r.id().equals(holder.loaderId)).findFirst().orElse(null);
            if (record == null) {
                player.closeInventory();
                open.remove(entry.getKey());
                continue;
            }
            render(player.getOpenInventory().getTopInventory(), record, player);
        }
    }

    @EventHandler
    public void onClick(InventoryClickEvent event) {
        if (!(event.getInventory().getHolder() instanceof LoaderHolder holder)) return;
        event.setCancelled(true);
        if (!(event.getWhoClicked() instanceof Player player)) return;
        if (event.getRawSlot() < 0 || event.getRawSlot() >= event.getInventory().getSize()) return;

        LoaderRecord record = service.records().stream().filter(r -> r.id().equals(holder.loaderId)).findFirst().orElse(null);
        if (record == null) {
            player.closeInventory();
            return;
        }

        if (event.getRawSlot() == DEPOSIT_SLOT) {
            service.depositHour(player, record);
            LoaderRecord updated = service.records().stream().filter(r -> r.id().equals(holder.loaderId)).findFirst().orElse(record);
            render(event.getInventory(), updated, player);
            return;
        }

        if (event.getRawSlot() == REMOVE_SLOT) {
            if (!service.canRemove(player, record)) {
                plugin.send(player, "&cOnly the loader owner or an administrator can remove it.");
                return;
            }

            var world = Bukkit.getWorld(record.worldId());
            if (world == null) {
                plugin.send(player, "&cThe loader world is unavailable.");
                return;
            }

            var block = world.getBlockAt(record.x(), record.y(), record.z());
            if (block.getType() == Material.BEACON) block.setType(Material.AIR, false);
            ItemStack loader = service.remove(player, record);
            HashMap<Integer, ItemStack> overflow = player.getInventory().addItem(loader);
            overflow.values().forEach(item -> player.getWorld().dropItemNaturally(player.getLocation(), item));
            player.closeInventory();
            plugin.send(player, "&aChunk loader removed and returned to your inventory.");
        }
    }

    private void render(Inventory inventory, LoaderRecord record, Player player) {
        inventory.clear();
        long remaining = service.remainingMillis(record);
        boolean active = remaining > 0L;

        inventory.setItem(TIMER_SLOT, item(active ? Material.CLOCK : Material.RECOVERY_COMPASS,
                active ? "&a&lLoader Active" : "&c&lLoader Inactive",
                List.of(
                        "&7Time remaining: &f" + service.formatRemaining(record),
                        "&7Chunk: &f" + record.chunkX() + ", " + record.chunkZ(),
                        active ? "&aThis chunk is being kept loaded." : "&cDeposit fuel to activate this loader."
                )));

        inventory.setItem(DEPOSIT_SLOT, item(Material.EMERALD,
                "&a&lDeposit $" + service.formatMoney(plugin.hourlyCost()),
                List.of(
                        "&7Adds &e1 hour &7of loader time.",
                        "&7Maximum stored time: &e" + plugin.maximumHours() + " hours",
                        "",
                        "&eClick to deposit"
                )));

        boolean canRemove = service.canRemove(player, record);
        inventory.setItem(REMOVE_SLOT, item(Material.BARRIER,
                "&c&lRemove Loader",
                List.of(
                        canRemove ? "&7Returns the loader to your inventory." : "&cOnly the owner or an admin can remove this.",
                        "",
                        canRemove ? "&eClick to remove" : "&8Removal unavailable"
                )));
    }

    private ItemStack item(Material material, String name, List<String> lore) {
        ItemStack item = new ItemStack(material);
        ItemMeta meta = item.getItemMeta();
        meta.setDisplayName(plugin.color(name));
        meta.setLore(lore.stream().map(plugin::color).toList());
        item.setItemMeta(meta);
        return item;
    }

    private static final class LoaderHolder implements InventoryHolder {
        private final UUID loaderId;
        private Inventory inventory;

        private LoaderHolder(UUID loaderId) { this.loaderId = loaderId; }

        @Override
        public Inventory getInventory() { return inventory; }
    }
}
