package com.mira.loaders.command;

import com.mira.loaders.MiraLoadersPlugin;
import com.mira.loaders.service.LoaderService;
import org.bukkit.Bukkit;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;

public final class LoaderCommand implements CommandExecutor, TabCompleter {
    private final MiraLoadersPlugin plugin;
    private final LoaderService service;

    public LoaderCommand(MiraLoadersPlugin plugin, LoaderService service) {
        this.plugin = plugin;
        this.service = service;
    }

    @Override
    public boolean onCommand(@NotNull CommandSender sender, @NotNull Command command, @NotNull String label, @NotNull String[] args) {
        if (!sender.hasPermission("miraloaders.admin")) {
            plugin.send(sender, "&cYou do not have permission to use this command.");
            return true;
        }

        if (args.length == 0 || args[0].equalsIgnoreCase("help")) {
            plugin.send(sender, "&f/mloader give <player> [amount] &7- Give physical chunk loader(s)");
            plugin.send(sender, "&f/mloader reload &7- Reload MiraLoaders configuration");
            return true;
        }

        if (args[0].equalsIgnoreCase("reload")) {
            plugin.reloadConfig();
            plugin.send(sender, "&aMiraLoaders configuration reloaded.");
            return true;
        }

        if (args[0].equalsIgnoreCase("give")) {
            if (args.length < 2) {
                plugin.send(sender, "&cUsage: /mloader give <player> [amount]");
                return true;
            }

            Player target = Bukkit.getPlayerExact(args[1]);
            if (target == null) {
                plugin.send(sender, "&cThat player is not online.");
                return true;
            }

            int amount = 1;
            if (args.length >= 3) {
                try {
                    amount = Integer.parseInt(args[2]);
                } catch (NumberFormatException ex) {
                    plugin.send(sender, "&cAmount must be a whole number.");
                    return true;
                }
            }
            amount = Math.max(1, Math.min(64, amount));

            int remaining = amount;
            while (remaining > 0) {
                int stackSize = Math.min(64, remaining);
                ItemStack item = service.createLoaderItem(stackSize);
                HashMap<Integer, ItemStack> overflow = target.getInventory().addItem(item);
                overflow.values().forEach(extra -> target.getWorld().dropItemNaturally(target.getLocation(), extra));
                remaining -= stackSize;
            }

            plugin.send(sender, "&aGave &f" + amount + " &aMira Chunk Loader(s) to &f" + target.getName() + "&a.");
            if (!sender.equals(target)) plugin.send(target, "&aYou received &f" + amount + " &aMira Chunk Loader(s).");
            return true;
        }

        plugin.send(sender, "&cUnknown subcommand. Use /mloader help.");
        return true;
    }

    @Override
    public @Nullable List<String> onTabComplete(@NotNull CommandSender sender, @NotNull Command command, @NotNull String alias, @NotNull String[] args) {
        if (!sender.hasPermission("miraloaders.admin")) return List.of();
        if (args.length == 1) return filter(List.of("give", "reload", "help"), args[0]);
        if (args.length == 2 && args[0].equalsIgnoreCase("give")) {
            return filter(Bukkit.getOnlinePlayers().stream().map(Player::getName).toList(), args[1]);
        }
        if (args.length == 3 && args[0].equalsIgnoreCase("give")) return filter(List.of("1", "2", "4", "8", "16"), args[2]);
        return List.of();
    }

    private List<String> filter(List<String> values, String prefix) {
        String lower = prefix.toLowerCase();
        List<String> out = new ArrayList<>();
        for (String value : values) if (value.toLowerCase().startsWith(lower)) out.add(value);
        return out;
    }
}
