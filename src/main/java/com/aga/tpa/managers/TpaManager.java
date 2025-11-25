package com.aga.tpa.managers;

import com.aga.tpa.AgaTPA;
import me.clip.placeholderapi.PlaceholderAPI;
import net.md_5.bungee.api.chat.*;
import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.Location;
import org.bukkit.Sound;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.scheduler.BukkitRunnable;

import java.io.File;
import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.sql.*;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

public class TpaManager implements Listener {

    private final AgaTPA plugin;
    private Connection connection;

    private final Map<UUID, UUID> pendingRequests = new ConcurrentHashMap<>();
    private final Map<UUID, Long> cooldowns = new ConcurrentHashMap<>();
    private final Set<UUID> toggles = Collections.synchronizedSet(new HashSet<>());

    public TpaManager(AgaTPA plugin) {
        this.plugin = plugin;
        Bukkit.getPluginManager().registerEvents(this, plugin);
        setupDatabase();
        startCleanerTask();
    }

    private void setupDatabase() {
        try {
            Class.forName("org.sqlite.JDBC");
            File dbFile = new File(plugin.getDataFolder(), "agatpa.db");
            connection = DriverManager.getConnection("jdbc:sqlite:" + dbFile.getAbsolutePath());
            try (Statement st = connection.createStatement()) {
                st.execute("CREATE TABLE IF NOT EXISTS toggles (uuid VARCHAR(36) PRIMARY KEY);");
            }
            loadToggles();
        } catch (Exception e) {
            plugin.getLogger().severe("Erro ao conectar no SQLite: " + e.getMessage());
        }
    }

    private void loadToggles() {
        Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
            try (Statement st = connection.createStatement(); ResultSet rs = st.executeQuery("SELECT uuid FROM toggles")) {
                while (rs.next()) {
                    toggles.add(UUID.fromString(rs.getString("uuid")));
                }
            } catch (SQLException e) {
                e.printStackTrace();
            }
        });
    }

    public void shutdown() {
        try { if (connection != null && !connection.isClosed()) connection.close(); } catch (SQLException e) { e.printStackTrace(); }
    }

    public void createRequest(Player sender, Player target) {
        pendingRequests.put(sender.getUniqueId(), target.getUniqueId());
        cooldowns.put(sender.getUniqueId(), System.currentTimeMillis() + (plugin.getConfig().getInt("settings.send-cooldown") * 1000L));

        sendJsonRequest(target, sender);

        String baseMsg = plugin.getConfig().getString("messages.request-sent").replace("%target%", target.getName()) + " ";
        TextComponent msg = new TextComponent(format(sender, baseMsg));

        TextComponent cancelBtn = new TextComponent(format(sender, plugin.getConfig().getString("messages.request-sent-cancel")));
        cancelBtn.setClickEvent(new ClickEvent(ClickEvent.Action.RUN_COMMAND, "/tpacancel"));
        cancelBtn.setHoverEvent(new HoverEvent(HoverEvent.Action.SHOW_TEXT, new ComponentBuilder(format(sender, plugin.getConfig().getString("messages.buttons.cancel-hover"))).create()));

        msg.addExtra(cancelBtn);
        sender.spigot().sendMessage(msg);

        playSound(sender);
        playSound(target);
    }

    public void acceptRequest(Player target) {
        UUID senderUUID = getSenderByTarget(target.getUniqueId());
        if (senderUUID == null) {
            target.sendMessage(format(target, plugin.getConfig().getString("messages.no-pending-request")));
            return;
        }

        Player sender = Bukkit.getPlayer(senderUUID);
        pendingRequests.remove(senderUUID);

        if (sender == null || !sender.isOnline()) {
            target.sendMessage(format(target, plugin.getConfig().getString("messages.player-not-found")));
            return;
        }

        target.sendMessage(format(target, plugin.getConfig().getString("messages.request-accepted").replace("%player%", sender.getName())));
        sender.sendMessage(format(sender, plugin.getConfig().getString("messages.request-accepted-target").replace("%target%", target.getName())));

        startTeleportProcess(sender, target);
    }

    public void denyRequest(Player target) {
        UUID senderUUID = getSenderByTarget(target.getUniqueId());
        if (senderUUID == null) {
            target.sendMessage(format(target, plugin.getConfig().getString("messages.no-pending-request")));
            return;
        }

        Player sender = Bukkit.getPlayer(senderUUID);
        pendingRequests.remove(senderUUID);

        target.sendMessage(format(target, plugin.getConfig().getString("messages.request-denied").replace("%player%", sender != null ? sender.getName() : "Desconhecido")));
        if (sender != null && sender.isOnline()) {
            sender.sendMessage(format(sender, plugin.getConfig().getString("messages.request-denied-target").replace("%target%", target.getName())));
        }
    }

    public void cancelRequest(Player sender) {
        if (!pendingRequests.containsKey(sender.getUniqueId())) {
            sender.sendMessage(format(sender, plugin.getConfig().getString("messages.no-pending-request")));
            return;
        }

        UUID targetUUID = pendingRequests.remove(sender.getUniqueId());
        sender.sendMessage(format(sender, plugin.getConfig().getString("messages.request-canceled")));

        Player target = Bukkit.getPlayer(targetUUID);
        if (target != null && target.isOnline()) {
            target.sendMessage(format(target, plugin.getConfig().getString("messages.request-canceled-target").replace("%player%", sender.getName())));
        }
    }

    public void toggleTpa(Player player) {
        boolean isNowDisabled = !toggles.contains(player.getUniqueId());

        if (isNowDisabled) {
            toggles.add(player.getUniqueId());
            player.sendMessage(format(player, plugin.getConfig().getString("messages.toggle-off")));
            saveToggleAsync(player.getUniqueId(), true);
        } else {
            toggles.remove(player.getUniqueId());
            player.sendMessage(format(player, plugin.getConfig().getString("messages.toggle-on")));
            saveToggleAsync(player.getUniqueId(), false);
        }
    }

    private void startTeleportProcess(Player player, Player dest) {
        if (player.hasPermission("aga.tpa.bypass.delay")) {
            player.teleport(dest);
            sendActionBar(player, format(player, plugin.getConfig().getString("messages.actionbar-success")));
            playSound(player);
            return;
        }

        int delay = plugin.getConfig().getInt("settings.teleport-delay");
        Location startLoc = player.getLocation();
        player.sendMessage(format(player, plugin.getConfig().getString("messages.teleporting").replace("%time%", String.valueOf(delay))));

        new BukkitRunnable() {
            @Override
            public void run() {
                if (!player.isOnline()) return;

                if (player.getLocation().distance(startLoc) > 0.5) {
                    player.sendMessage(format(player, plugin.getConfig().getString("messages.teleport-canceled-move")));
                    sendActionBar(player, format(player, plugin.getConfig().getString("messages.actionbar-move")));
                    return;
                }

                player.teleport(dest);
                sendActionBar(player, format(player, plugin.getConfig().getString("messages.actionbar-success")));
                playSound(player);
            }
        }.runTaskLater(plugin, delay * 20L);
    }

    private void sendJsonRequest(Player target, Player sender) {
        FileConfiguration c = plugin.getConfig();

        TextComponent acceptBtn = new TextComponent(format(target, c.getString("messages.buttons.accept-text")));
        acceptBtn.setClickEvent(new ClickEvent(ClickEvent.Action.RUN_COMMAND, "/tpaccept"));
        acceptBtn.setHoverEvent(new HoverEvent(HoverEvent.Action.SHOW_TEXT, new ComponentBuilder(format(target, c.getString("messages.buttons.accept-hover"))).create()));

        TextComponent denyBtn = new TextComponent(format(target, c.getString("messages.buttons.deny-text")));
        denyBtn.setClickEvent(new ClickEvent(ClickEvent.Action.RUN_COMMAND, "/tpadeny"));
        denyBtn.setHoverEvent(new HoverEvent(HoverEvent.Action.SHOW_TEXT, new ComponentBuilder(format(target, c.getString("messages.buttons.deny-hover"))).create()));

        for (String line : c.getStringList("messages.request-received")) {
            if (line.contains("{accept}") || line.contains("{deny}")) {
                TextComponent comp = new TextComponent("");

                if (line.contains("{accept}")) {
                    String[] parts = line.split("\\{accept\\}");
                    if(parts.length > 0) comp.addExtra(new TextComponent(format(sender, parts[0])));
                    comp.addExtra(acceptBtn);
                }

                if (line.contains("{deny}")) {
                    String between = "  &8ou  ";
                    if(line.contains("{accept}") && line.contains("{deny}")) {
                        int start = line.indexOf("}") + 1;
                        int end = line.lastIndexOf("{");
                        if(end > start) between = line.substring(start, end);
                    }
                    comp.addExtra(new TextComponent(format(target, between)));
                    comp.addExtra(denyBtn);
                }

                target.spigot().sendMessage(comp);
            } else {
                String processed = line.replace("%player%", sender.getName());
                target.sendMessage(format(sender, processed));
            }
        }
    }

    private void saveToggleAsync(UUID uuid, boolean add) {
        Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
            try (PreparedStatement ps = connection.prepareStatement(add ? "INSERT OR IGNORE INTO toggles (uuid) VALUES (?)" : "DELETE FROM toggles WHERE uuid = ?")) {
                ps.setString(1, uuid.toString());
                ps.executeUpdate();
            } catch (SQLException e) { e.printStackTrace(); }
        });
    }

    private void startCleanerTask() {
        new BukkitRunnable() {
            @Override
            public void run() {
                long now = System.currentTimeMillis();
                cooldowns.entrySet().removeIf(entry -> entry.getValue() < now);
            }
        }.runTaskTimerAsynchronously(plugin, 600L, 600L);
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent e) {
        UUID u = e.getPlayer().getUniqueId();
        pendingRequests.remove(u);
        pendingRequests.values().removeIf(target -> target.equals(u));
        cooldowns.remove(u);
    }

    public boolean hasPendingRequest(UUID sender) { return pendingRequests.containsKey(sender); }
    public boolean isToggled(UUID target) { return toggles.contains(target); }
    public boolean isOnCooldown(UUID sender) { return cooldowns.getOrDefault(sender, 0L) > System.currentTimeMillis(); }
    public long getCooldownSeconds(UUID sender) { return (cooldowns.get(sender) - System.currentTimeMillis()) / 1000; }

    private UUID getSenderByTarget(UUID target) {
        for (Map.Entry<UUID, UUID> entry : pendingRequests.entrySet()) {
            if (entry.getValue().equals(target)) return entry.getKey();
        }
        return null;
    }

    private void playSound(Player p) {
        if (plugin.getConfig().getBoolean("sounds.enabled")) {
            try {
                p.playSound(p.getLocation(), Sound.valueOf(plugin.getConfig().getString("sounds.sound-name")), 1f, 1f);
            } catch (Exception ignored) {}
        }
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

    private void sendActionBar(Player player, String message) {
        try {
            Class<?> chatPacketClass = Class.forName("net.minecraft.server.v1_8_R3.PacketPlayOutChat");
            Class<?> chatComponentClass = Class.forName("net.minecraft.server.v1_8_R3.IChatBaseComponent");
            Class<?> chatSerializerClass = Class.forName("net.minecraft.server.v1_8_R3.IChatBaseComponent$ChatSerializer");

            Method a = chatSerializerClass.getMethod("a", String.class);
            Object component = a.invoke(null, "{\"text\": \"" + message + "\"}");

            Constructor<?> packetConstructor = chatPacketClass.getConstructor(chatComponentClass, byte.class);
            Object packet = packetConstructor.newInstance(component, (byte) 2);

            Object entityPlayer = player.getClass().getMethod("getHandle").invoke(player);
            Object playerConnection = entityPlayer.getClass().getField("playerConnection").get(entityPlayer);
            Method sendPacket = playerConnection.getClass().getMethod("sendPacket", Class.forName("net.minecraft.server.v1_8_R3.Packet"));

            sendPacket.invoke(playerConnection, packet);
        } catch (Exception ignored) { }
    }
}