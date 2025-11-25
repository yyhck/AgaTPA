package com.aga.tpa.commands;

import com.aga.tpa.AgaTPA;
import com.aga.tpa.managers.TpaManager;
import com.aga.tpa.utils.ChatUtils;
import com.aga.tpa.utils.Permissions;
import org.bukkit.Bukkit;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

public class TpaCommand implements CommandExecutor {

    private final AgaTPA plugin;
    private final TpaManager manager;

    public TpaCommand(AgaTPA plugin) {
        this.plugin = plugin;
        this.manager = plugin.getManager();
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!(sender instanceof Player)) {
            return ChatUtils.sendMessage(sender, "player-only");
        }

        Player player = (Player) sender;

        if (!player.hasPermission(Permissions.TPA_SEND)) {
            return ChatUtils.sendMessage(player, "no-permission");
        }

        if (args.length == 0) {
            return ChatUtils.sendMessage(player, "usage-tpa");
        }

        Player target = Bukkit.getPlayer(args[0]);
        if (target == null || !target.isOnline()) {
            return ChatUtils.sendMessage(player, "player-not-found");
        }

        if (player.equals(target)) {
            return ChatUtils.sendMessage(player, "self-tpa");
        }

        if (manager.isToggled(target.getUniqueId())) {
            return ChatUtils.sendMessage(player, "target-toggle-off");
        }

        if (!player.hasPermission(Permissions.BYPASS_COOLDOWN) && manager.isOnCooldown(player.getUniqueId())) {
            String message = plugin.getConfig().getString("messages.cooldown-wait").replace("%time%", String.valueOf(manager.getCooldownSeconds(player.getUniqueId())));
            player.sendMessage(ChatUtils.format(player, message));
            return true;
        }

        if (manager.hasPendingRequest(player.getUniqueId())) {
            return ChatUtils.sendMessage(player, "already-pending");
        }

        manager.createRequest(player, target);
        return true;
    }
}