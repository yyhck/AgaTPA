package com.aga.tpa;

import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.plugin.java.JavaPlugin;
import com.aga.tpa.managers.TpaManager;
import com.aga.tpa.commands.TpaCommandExecutor;

public class AgaTPA extends JavaPlugin {

    private static AgaTPA instance;
    private TpaManager tpaManager;

    @Override
    public void onEnable() {
        instance = this;
        saveDefaultConfig();

        this.tpaManager = new TpaManager(this);

        getCommand("tpa").setExecutor(new TpaCommandExecutor(this));
        getCommand("tpaccept").setExecutor(new TpaCommandExecutor(this));
        getCommand("tpadeny").setExecutor(new TpaCommandExecutor(this));
        getCommand("tpacancel").setExecutor(new TpaCommandExecutor(this));
        getCommand("tpatoggle").setExecutor(new TpaCommandExecutor(this));
        getCommand("agatpa").setExecutor(new TpaCommandExecutor(this));

        sendConsoleLogo();
    }

    @Override
    public void onDisable() {
        if (tpaManager != null) {
            tpaManager.shutdown();
        }
    }

    public static AgaTPA getInstance() { return instance; }
    public TpaManager getManager() { return tpaManager; }

    private void sendConsoleLogo() {
        String border = "&a&l========================================";
        String title  = "&7      AgaTPA &8(Spigot) &a&lATIVADO";
        String author = "&7      Autor: &f" + (getDescription().getAuthors().isEmpty() ? "yyHcK" : getDescription().getAuthors().get(0));
        String ver    = "&7      Versao: &f" + getDescription().getVersion();

        Bukkit.getConsoleSender().sendMessage(color(border));
        Bukkit.getConsoleSender().sendMessage(color(title));
        Bukkit.getConsoleSender().sendMessage(color(author));
        Bukkit.getConsoleSender().sendMessage(color(ver));
        Bukkit.getConsoleSender().sendMessage(color(border));
    }

    private String color(String s) {
        return ChatColor.translateAlternateColorCodes('&', s);
    }
}