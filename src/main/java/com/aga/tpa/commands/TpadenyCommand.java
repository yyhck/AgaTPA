package com.aga.tpa.commands;

import com.aga.tpa.AgaTPA;
import com.aga.tpa.utils.ChatUtils;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

public class TpadenyCommand implements CommandExecutor {

    private final AgaTPA plugin;

    public TpadenyCommand(AgaTPA plugin) {
        this.plugin = plugin;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!(sender instanceof Player)) {
            return ChatUtils.sendMessage(sender, "player-only");
        }
        Player player = (Player) sender;
        if (!player.hasPermission("aga.tpa.deny")) {
            return ChatUtils.sendMessage(player, "no-permission");
        }

        plugin.getManager().denyRequest(player);
        return true;
    }
}