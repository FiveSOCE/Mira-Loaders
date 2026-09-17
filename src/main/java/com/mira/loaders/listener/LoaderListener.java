package com.mira.loaders.listener;

import com.mira.loaders.LoaderRecord;
import com.mira.loaders.MiraLoadersPlugin;
import com.mira.loaders.gui.LoaderGui;
import com.mira.loaders.service.LoaderService;
import org.bukkit.Material;
import org.bukkit.block.Block;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockExplodeEvent;
import org.bukkit.event.block.BlockPistonExtendEvent;
import org.bukkit.event.block.BlockPistonRetractEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.entity.EntityExplodeEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.inventory.EquipmentSlot;

public final class LoaderListener implements Listener {
    private final MiraLoadersPlugin plugin;
    private final LoaderService service;
    private final LoaderGui gui;

    public LoaderListener(MiraLoadersPlugin plugin, LoaderService service, LoaderGui gui) {
        this.plugin = plugin;
        this.service = service;
        this.gui = gui;
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onPlace(BlockPlaceEvent event) {
        if (!service.isLoaderItem(event.getItemInHand())) return;

        Block block = event.getBlockPlaced();
        if (block.getType() != Material.BEACON) return;

        if (plugin.onePerChunk() && service.chunkAlreadyHasLoader(block.getWorld(), block.getChunk().getX(), block.getChunk().getZ())) {
            event.setCancelled(true);
            plugin.send(event.getPlayer(), "&cThere is already a Mira Chunk Loader in this chunk.");
            return;
        }

        service.register(block, event.getPlayer());
        plugin.send(event.getPlayer(), "&aChunk loader placed. &7Right-click it to add fuel.");
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = false)
    public void onInteract(PlayerInteractEvent event) {
        if (event.getHand() != EquipmentSlot.HAND) return;
        if (event.getAction() != Action.RIGHT_CLICK_BLOCK) return;
        Block block = event.getClickedBlock();
        if (block == null || block.getType() != Material.BEACON) return;

        LoaderRecord record = service.at(block).orElse(null);
        if (record == null) return;

        event.setCancelled(true);
        Player player = event.getPlayer();
        gui.open(player, record);
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = false)
    public void onBreak(BlockBreakEvent event) {
        LoaderRecord record = service.at(event.getBlock()).orElse(null);
        if (record == null) return;

        event.setCancelled(true);
        plugin.send(event.getPlayer(), "&cMira Chunk Loaders must be removed through their right-click menu.");
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onEntityExplode(EntityExplodeEvent event) {
        event.blockList().removeIf(block -> service.at(block).isPresent());
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onBlockExplode(BlockExplodeEvent event) {
        event.blockList().removeIf(block -> service.at(block).isPresent());
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onPistonExtend(BlockPistonExtendEvent event) {
        if (event.getBlocks().stream().anyMatch(block -> service.at(block).isPresent())) event.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onPistonRetract(BlockPistonRetractEvent event) {
        if (event.getBlocks().stream().anyMatch(block -> service.at(block).isPresent())) event.setCancelled(true);
    }
}
