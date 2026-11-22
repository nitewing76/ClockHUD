package com.sgd.clockhud;

import net.kyori.adventure.text.Component;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.jetbrains.annotations.NotNull;

import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.stream.Stream;

public final class ClockHudCommand implements CommandExecutor, TabCompleter {

    private final ClockHUDPlugin plugin;

    public ClockHudCommand(ClockHUDPlugin plugin) {
        this.plugin = plugin;
    }

    @Override
    public boolean onCommand(@NotNull CommandSender sender, @NotNull Command command,
                             @NotNull String label, @NotNull String @NotNull [] args) {
        if (!sender.hasPermission("clockhud.admin")) {
            sendMessage(sender, "messages.no-permission", "&cYou do not have permission to use this command.");
            return true;
        }

        if (args.length == 0) {
            sendMessage(sender, "messages.usage", "&eUsage: /clockhud <toggle|reload>");
            return true;
        }

        String sub = args[0].toLowerCase(Locale.ROOT);
        switch (sub) {
            case "toggle" -> plugin.togglePluginState();
            case "reload" -> {
                plugin.reloadPlugin();
                sendMessage(sender, "messages.reloaded", "&aClockHUD configuration reloaded.");
            }
            default -> sendMessage(sender, "messages.usage", "&eUsage: /clockhud <toggle|reload>");
        }
        return true;
    }

    @Override
    public @NotNull List<String> onTabComplete(@NotNull CommandSender sender, @NotNull Command command,
                                                 @NotNull String alias, @NotNull String @NotNull [] args) {
        if (!sender.hasPermission("clockhud.admin")) {
            return Collections.emptyList();
        }
        if (args.length == 1) {
            String partial = args[0].toLowerCase(Locale.ROOT);
            return Stream.of("toggle", "reload")
                    .filter(option -> option.startsWith(partial))
                    .toList();
        }
        return Collections.emptyList();
    }

    private void sendMessage(CommandSender sender, String key, String fallback) {
        String prefix = plugin.getConfig().getString("messages.prefix", "&8[&6ClockHUD&8] ");
        String body = plugin.getConfig().getString(key, fallback);
        Component full = ClockHUDPlugin.AMPERSAND.deserialize(prefix + body);
        sender.sendMessage(full);
    }
}
