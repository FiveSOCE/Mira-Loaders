package com.mira.loaders;

import com.mira.loaders.command.LoaderCommand;
import com.mira.loaders.gui.LoaderGui;
import com.mira.loaders.listener.LoaderListener;
import com.mira.loaders.service.LoaderService;
import net.milkbowl.vault.economy.Economy;
import org.bukkit.ChatColor;
import org.bukkit.command.PluginCommand;
import org.bukkit.plugin.RegisteredServiceProvider;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.List;

public final class MiraLoadersPlugin extends JavaPlugin {
    private Economy economy;
    private LoaderService loaderService;
    private LoaderGui loaderGui;

    @Override
    public void onEnable() {
        saveDefaultConfig();

        RegisteredServiceProvider<Economy> registration = getServer().getServicesManager().getRegistration(Economy.class);
        if (registration == null || registration.getProvider() == null) {
            getLogger().severe("No Vault economy provider is available. Disabling MiraLoaders.");
            getServer().getPluginManager().disablePlugin(this);
            return;
        }

        economy = registration.getProvider();
        loaderService = new LoaderService(this, economy);
        loaderService.load();
        loaderGui = new LoaderGui(this, loaderService);

        getServer().getPluginManager().registerEvents(loaderGui, this);
        getServer().getPluginManager().registerEvents(new LoaderListener(this, loaderService, loaderGui), this);

        LoaderCommand command = new LoaderCommand(this, loaderService);
        PluginCommand pluginCommand = getCommand("miraloader");
        if (pluginCommand == null) {
            throw new IllegalStateException("miraloader command missing from plugin.yml");
        }
        pluginCommand.setExecutor(command);
        pluginCommand.setTabCompleter(command);

        getServer().getScheduler().runTaskTimer(this, () -> {
            loaderService.tick();
            loaderGui.refreshOpenGuis();
        }, 20L, 20L);

        getLogger().info("MiraLoaders v" + getPluginMeta().getVersion() + " enabled with "
                + loaderService.loaderCount() + " tracked loader(s).");
    }

    @Override
    public void onDisable() {
        if (loaderService != null) loaderService.shutdown();
    }

    public double hourlyCost() {
        return Math.max(0.0D, getConfig().getDouble("fuel.cost-per-hour", 150000.0D));
    }

    public int maximumHours() {
        return Math.max(1, getConfig().getInt("fuel.maximum-hours", 12));
    }

    public boolean onePerChunk() {
        return getConfig().getBoolean("loader.one-per-chunk", true);
    }

    public String loaderItemName() {
        return color(getConfig().getString("loader.item-name", "&b&lMira Chunk Loader"));
    }

    public List<String> loaderItemLore() {
        return getConfig().getStringList("loader.item-lore").stream().map(this::color).toList();
    }

    public String prefix() {
        return color(getConfig().getString("messages.prefix", "&5&lMira &8>> &r"));
    }

    public void send(org.bukkit.command.CommandSender sender, String message) {
        sender.sendMessage(prefix() + color(message));
    }

    public String color(String text) {
        return ChatColor.translateAlternateColorCodes('&', text == null ? "" : text);
    }
}
