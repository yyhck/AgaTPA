package com.aga.tpa;

import org.bukkit.Bukkit;
import org.bukkit.plugin.java.JavaPlugin;

import com.aga.tpa.commands.*;
import com.aga.tpa.managers.TpaManager;
import com.aga.tpa.utils.ChatUtils;

public class AgaTPA extends JavaPlugin {

    private static AgaTPA instance;
    private TpaManager tpaManager;

    @Override
    public void onEnable() {
        instance = this;
        saveDefaultConfig();

        tpaManager = new TpaManager(this);
        getServer().getPluginManager().registerEvents(tpaManager, this);

        registerCommands();

        sendConsoleLogo();
    }

    @Override
    public void onDisable() {
        if (tpaManager != null) {
            tpaManager.shutdown();
        }
        instance = null;
    }

    public static AgaTPA getInstance() { return instance; }
    public TpaManager getManager() { return tpaManager; }

    private void sendConsoleLogo() {
        String border = "&a&l========================================";
        String title  = "&7      AgaTPA &8(Spigot) &a&lATIVADO";
        String author = "&7      Autor: &f" + (getDescription().getAuthors().isEmpty() ? "yyHcK" : getDescription().getAuthors().get(0));
        String ver    = "&7      Versao: &f" + getDescription().getVersion();

        Bukkit.getConsoleSender().sendMessage(ChatUtils.color(border));
        Bukkit.getConsoleSender().sendMessage(ChatUtils.color(title));
        Bukkit.getConsoleSender().sendMessage(ChatUtils.color(author));
        Bukkit.getConsoleSender().sendMessage(ChatUtils.color(ver));
        Bukkit.getConsoleSender().sendMessage(ChatUtils.color(border));
    }

    private void registerCommands() {
        getCommand("tpa").setExecutor(new TpaCommand(this));
        getCommand("tpaccept").setExecutor(new TpacceptCommand(this));
        getCommand("tpadeny").setExecutor(new TpadenyCommand(this));
        getCommand("tpacancel").setExecutor(new TpacancelCommand(this));
        getCommand("tpatoggle").setExecutor(new TpatoggleCommand(this));
        getCommand("agatpa").setExecutor(new AgaTpaAdminCommand(this));
    }
}