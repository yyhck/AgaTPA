package com.aga.tpa.commands;

import com.aga.tpa.AgaTPA;
import com.aga.tpa.managers.TpaManager;
import me.clip.placeholderapi.PlaceholderAPI;
import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

public class TpaCommandExecutor implements CommandExecutor {

    private final AgaTPA plugin;

    public TpaCommandExecutor(AgaTPA plugin) {
        this.plugin = plugin;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command cmd, String label, String[] args) {
        if (cmd.getName().equalsIgnoreCase("agatpa")) {
            if (!sender.hasPermission("aga.tpa.admin")) return msg(sender, "no-permission");

            if (args.length == 0 || args[0].equalsIgnoreCase("help")) {
                sendHelp(sender);
                return true;
            }

            if (args[0].equalsIgnoreCase("reload")) {
                plugin.reloadConfig();
                return msg(sender, "reload-success");
            }

            sendHelp(sender);
            return true;
        }

        if (!(sender instanceof Player)) {
            sender.sendMessage(ChatColor.RED + "Apenas jogadores.");
            return true;
        }

        Player p = (Player) sender;
        TpaManager manager = plugin.getManager();
        String command = cmd.getName().toLowerCase();

        switch (command) {
            case "tpa":
                if (!p.hasPermission("aga.tpa.use")) return msg(p, "no-permission");
                if (args.length == 0) return msg(p, "usage-tpa");

                Player target = Bukkit.getPlayer(args[0]);
                if (target == null || !target.isOnline()) return msg(p, "player-not-found");
                if (target.equals(p)) return msg(p, "self-tpa");

                if (plugin.getConfig().getStringList("restrictions.blocked-worlds").contains(p.getWorld().getName())) {
                    return msg(p, "world-blocked");
                }

                if (manager.isToggled(target.getUniqueId())) {
                    p.sendMessage(format(p, plugin.getConfig().getString("messages.target-toggle-off")));
                    return true;
                }

                if (manager.hasPendingRequest(p.getUniqueId())) return msg(p, "already-pending");

                if (!p.hasPermission("aga.tpa.bypass.cooldown") && manager.isOnCooldown(p.getUniqueId())) {
                    String cooldownMsg = plugin.getConfig().getString("messages.cooldown-wait")
                            .replace("%time%", String.valueOf(manager.getCooldownSeconds(p.getUniqueId())));
                    p.sendMessage(format(p, cooldownMsg));
                    return true;
                }

                manager.createRequest(p, target);
                return true;

            case "tpaccept":
                if (!p.hasPermission("aga.tpa.accept")) return msg(p, "no-permission");
                manager.acceptRequest(p);
                return true;

            case "tpadeny":
                if (!p.hasPermission("aga.tpa.deny")) return msg(p, "no-permission");
                manager.denyRequest(p);
                return true;

            case "tpacancel":
                if (!p.hasPermission("aga.tpa.cancel")) return msg(p, "no-permission");
                manager.cancelRequest(p);
                return true;

            case "tpatoggle":
                if (!p.hasPermission("aga.tpa.toggle")) return msg(p, "no-permission");
                manager.toggleTpa(p);
                return true;
        }
        return true;
    }

    private void sendHelp(CommandSender s) {
        if (plugin.getConfig().isList("messages.help-message")) {
            for (String line : plugin.getConfig().getStringList("messages.help-message")) {
                String processed = line.replace("%version%", plugin.getDescription().getVersion());
                if (s instanceof Player && Bukkit.getPluginManager().isPluginEnabled("PlaceholderAPI")) {
                    processed = PlaceholderAPI.setPlaceholders((Player) s, processed);
                }
                s.sendMessage(color(processed));
            }
        } else {
            s.sendMessage(color("&cErro ao carregar menu de ajuda da config."));
        }
    }

    private boolean msg(CommandSender s, String key) {
        String msg = plugin.getConfig().getString("messages." + key);
        if (msg != null) {
            if (s instanceof Player) {
                s.sendMessage(format((Player) s, msg));
            } else {
                s.sendMessage(color(msg));
            }
        } else {
            s.sendMessage(ChatColor.RED + "Mensagem faltando na config: " + key);
        }
        return true;
    }

    private String format(Player p, String s) {
        if (s == null) return "";
        String prefix = plugin.getConfig().getString("messages.prefix", "");
        s = s.replace("%prefix%", prefix);

        if (p != null && Bukkit.getPluginManager().isPluginEnabled("PlaceholderAPI")) {
            s = PlaceholderAPI.setPlaceholders(p, s);
        }
        return ChatColor.translateAlternateColorCodes('&', s);
    }

    private String color(String s) {
        if (s == null) return "";
        return ChatColor.translateAlternateColorCodes('&', s);
    }
}