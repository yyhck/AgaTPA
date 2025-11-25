package com.aga.tpa.commands;

import com.aga.tpa.AgaTPA;
import com.aga.tpa.utils.ChatUtils;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

public class AgaTpaAdminCommand implements CommandExecutor {

    private final AgaTPA plugin;

    public AgaTpaAdminCommand(AgaTPA plugin) {
        this.plugin = plugin;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!sender.hasPermission("aga.tpa.admin")) {
            return ChatUtils.sendMessage(sender, "no-permission");
        }

        if (args.length > 0 && args[0].equalsIgnoreCase("reload")) {
            plugin.reloadConfig();
            plugin.getManager().reload();
            return ChatUtils.sendMessage(sender, "reload-success");
        }

        sendHelp(sender);
        return true;
    }

    private void sendHelp(CommandSender sender) {
        plugin.getConfig().getStringList("messages.help-message").forEach(line -> {
            String processed = line.replace("%version%", plugin.getDescription().getVersion());
            sender.sendMessage(ChatUtils.format(sender instanceof Player ? (Player) sender : null, processed));
        });
    }
}