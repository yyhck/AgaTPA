package com.aga.tpa.utils;

import com.aga.tpa.AgaTPA;
import me.clip.placeholderapi.PlaceholderAPI;
import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

public class ChatUtils {

    private static AgaTPA plugin = AgaTPA.getInstance();

    public static String color(String s) {
        if (s == null) return "";
        return ChatColor.translateAlternateColorCodes('&', s);
    }

    public static String format(Player player, String s) {
        if (s == null) return "";

        String prefix = plugin.getConfig().getString("messages.prefix", "");
        s = s.replace("%prefix%", prefix);

        if (player != null && Bukkit.getPluginManager().isPluginEnabled("PlaceholderAPI")) {
            s = PlaceholderAPI.setPlaceholders(player, s);
        }
        return color(s);
    }

    public static boolean sendMessage(CommandSender sender, String configKey) {
        String message = plugin.getConfig().getString("messages." + configKey);
        if (message == null) {
            sender.sendMessage(ChatColor.RED + "Mensagem faltando na config: " + configKey);
            return true;
        }

        if (sender instanceof Player) {
            sender.sendMessage(format((Player) sender, message));
        } else {
            sender.sendMessage(color(message.replace("%prefix%", plugin.getConfig().getString("messages.prefix", ""))));
        }
        return true;
    }
}