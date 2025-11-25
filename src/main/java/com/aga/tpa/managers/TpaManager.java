package com.aga.tpa.managers;

import com.aga.tpa.AgaTPA;
import com.aga.tpa.utils.ChatUtils;
import net.md_5.bungee.api.ChatMessageType;
import net.md_5.bungee.api.chat.*;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Sound;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerMoveEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.scheduler.BukkitRunnable;

import java.lang.reflect.Constructor;
import java.io.File;
import java.sql.*;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

public class TpaManager implements Listener {

    private final AgaTPA plugin;
    private Connection connection;

    private final Map<UUID, UUID> pendingRequests = new ConcurrentHashMap<>();
    private final Map<UUID, UUID> reverseRequests = new ConcurrentHashMap<>();
    private final Map<UUID, Long> cooldowns = new ConcurrentHashMap<>();
    private final Map<UUID, Long> requestTimestamps = new ConcurrentHashMap<>();
    private final Set<UUID> toggles = Collections.synchronizedSet(new HashSet<>());
    private final Map<UUID, BukkitRunnable> teleportTasks = new ConcurrentHashMap<>();

    // --- Cache para Reflexão (Otimização para 1.8.8) ---
    private static Constructor<?> packetPlayOutChatConstructor;
    private static java.lang.reflect.Method getHandleMethod;
    private static java.lang.reflect.Field playerConnectionField;
    private static java.lang.reflect.Method sendPacketMethod;
    private static java.lang.reflect.Method chatSerializerAMethod;
    private static boolean nmsSetupFailed = false;
    // ----------------------------------------------------

    public TpaManager(AgaTPA plugin) {
        this.plugin = plugin;
        // O registro de eventos agora é feito na classe principal
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
                plugin.getLogger().warning("Falha ao carregar os toggles do banco de dados: " + e.getMessage());
            }
        });
    }

    public void shutdown() {
        try { if (connection != null && !connection.isClosed()) connection.close(); } catch (SQLException e) { plugin.getLogger().severe("Falha ao fechar a conexão com o banco de dados: " + e.getMessage()); }
    }

    public void reload() {
        // Limpa caches que dependem da config, se necessário.
        // Neste caso, não há caches complexos para limpar,
        // pois os valores são lidos da config em tempo real.
        // Apenas garante que o plugin.reloadConfig() já foi chamado.
    }

    public void createRequest(Player sender, Player target) {
        pendingRequests.put(sender.getUniqueId(), target.getUniqueId());
        reverseRequests.put(target.getUniqueId(), sender.getUniqueId());
        cooldowns.put(sender.getUniqueId(), System.currentTimeMillis() + (plugin.getConfig().getInt("settings.send-cooldown") * 1000L));
        requestTimestamps.put(sender.getUniqueId(), System.currentTimeMillis());

        sendJsonRequest(target, sender);

        String baseMsg = plugin.getConfig().getString("messages.request-sent").replace("%target%", target.getName());
        TextComponent msg = new TextComponent(ChatUtils.format(sender, baseMsg + " "));

        TextComponent cancelBtn = new TextComponent(ChatUtils.format(sender, plugin.getConfig().getString("messages.request-sent-cancel")));
        cancelBtn.setClickEvent(new ClickEvent(ClickEvent.Action.RUN_COMMAND, "/tpacancel"));
        cancelBtn.setHoverEvent(new HoverEvent(HoverEvent.Action.SHOW_TEXT, new ComponentBuilder(ChatUtils.format(sender, plugin.getConfig().getString("messages.buttons.cancel-hover"))).create()));

        msg.addExtra(cancelBtn);
        sender.spigot().sendMessage(msg);

        playSound(sender);
        playSound(target);
    }

    public void acceptRequest(Player target) {
        UUID senderUUID = reverseRequests.get(target.getUniqueId());
        if (senderUUID == null) {
            ChatUtils.sendMessage(target, "no-pending-request");
            return;
        }

        Player sender = Bukkit.getPlayer(senderUUID);
        removeRequest(senderUUID, target.getUniqueId());
        requestTimestamps.remove(senderUUID);

        if (sender == null || !sender.isOnline()) {
            ChatUtils.sendMessage(target, "player-not-found");
            return;
        }

        target.sendMessage(ChatUtils.format(target, plugin.getConfig().getString("messages.request-accepted").replace("%player%", sender.getName())));
        sender.sendMessage(ChatUtils.format(sender, plugin.getConfig().getString("messages.request-accepted-target").replace("%target%", target.getName())));

        startTeleportProcess(sender, target);
    }

    public void denyRequest(Player target) {
        UUID senderUUID = reverseRequests.get(target.getUniqueId());
        if (senderUUID == null) {
            ChatUtils.sendMessage(target, "no-pending-request");
            return;
        }

        Player sender = Bukkit.getPlayer(senderUUID);
        removeRequest(senderUUID, target.getUniqueId());
        requestTimestamps.remove(senderUUID);

        target.sendMessage(ChatUtils.format(target, plugin.getConfig().getString("messages.request-denied").replace("%player%", sender != null ? sender.getName() : "Desconhecido")));
        if (sender != null && sender.isOnline()) {
            sender.sendMessage(ChatUtils.format(sender, plugin.getConfig().getString("messages.request-denied-target").replace("%target%", target.getName())));
        }
    }

    public void cancelRequest(Player sender) {
        if (!pendingRequests.containsKey(sender.getUniqueId())) {
            ChatUtils.sendMessage(sender, "no-pending-request");
            return;
        }

        UUID targetUUID = pendingRequests.get(sender.getUniqueId());
        removeRequest(sender.getUniqueId(), targetUUID);
        requestTimestamps.remove(sender.getUniqueId());
        sender.sendMessage(ChatUtils.format(sender, plugin.getConfig().getString("messages.request-canceled")));

        Player target = Bukkit.getPlayer(targetUUID);
        if (target != null && target.isOnline()) {
            target.sendMessage(ChatUtils.format(target, plugin.getConfig().getString("messages.request-canceled-target").replace("%player%", sender.getName())));
        }
    }

    public void toggleTpa(Player player) {
        if (toggles.contains(player.getUniqueId())) {
            toggles.remove(player.getUniqueId());
            ChatUtils.sendMessage(player, "toggle-on");
            saveToggleAsync(player.getUniqueId(), false);
        } else {
            toggles.add(player.getUniqueId());
            ChatUtils.sendMessage(player, "toggle-off");
            saveToggleAsync(player.getUniqueId(), true);
        }
    }

    private void startTeleportProcess(Player player, Player dest) {
        if (player.hasPermission("aga.tpa.bypass.delay")) {
            player.teleport(dest);
            sendActionBar(player, ChatUtils.format(player, plugin.getConfig().getString("messages.actionbar-success")));
            playSound(player);
            return;
        }

        int delay = plugin.getConfig().getInt("settings.teleport-delay");
        Location startLoc = player.getLocation();
        player.sendMessage(ChatUtils.format(player, plugin.getConfig().getString("messages.teleporting").replace("%time%", String.valueOf(delay))));

        BukkitRunnable task = new BukkitRunnable() {
            @Override
            public void run() {
                if (!player.isOnline()) {
                    teleportTasks.remove(player.getUniqueId());
                    return;
                }
                // A verificação de movimento foi movida para o evento PlayerMoveEvent
                teleportTasks.remove(player.getUniqueId());
                if (player.teleport(dest)) {
                    sendActionBar(player, ChatUtils.format(player, plugin.getConfig().getString("messages.actionbar-success")));
                    playSound(player);
                }
            }
        };

        teleportTasks.put(player.getUniqueId(), task);
        task.runTaskLater(plugin, delay * 20L);
    }

    private void sendJsonRequest(Player target, Player sender) {
        FileConfiguration c = plugin.getConfig();

        // Usar ChatUtils.format para consistência
        TextComponent acceptBtn = new TextComponent(ChatUtils.format(target, c.getString("messages.buttons.accept-text")));
        acceptBtn.setClickEvent(new ClickEvent(ClickEvent.Action.RUN_COMMAND, "/tpaccept"));
        acceptBtn.setHoverEvent(new HoverEvent(HoverEvent.Action.SHOW_TEXT, new ComponentBuilder(ChatUtils.format(target, c.getString("messages.buttons.accept-hover"))).create()));

        TextComponent denyBtn = new TextComponent(ChatUtils.format(target, c.getString("messages.buttons.deny-text")));
        denyBtn.setClickEvent(new ClickEvent(ClickEvent.Action.RUN_COMMAND, "/tpadeny"));
        denyBtn.setHoverEvent(new HoverEvent(HoverEvent.Action.SHOW_TEXT, new ComponentBuilder(ChatUtils.format(target, c.getString("messages.buttons.deny-hover"))).create()));

        for (String line : c.getStringList("messages.request-received")) {
            if (line.contains("{accept}") || line.contains("{deny}")) {
                TextComponent comp = new TextComponent("");

                if (line.contains("{accept}")) {
                    String[] parts = line.split("\\{accept\\}");
                    if(parts.length > 0) comp.addExtra(new TextComponent(ChatUtils.format(sender, parts[0])));
                    comp.addExtra(acceptBtn);
                }

                if (line.contains("{deny}")) {
                    String between = "  &8ou  ";
                    if(line.contains("{accept}") && line.contains("{deny}")) {
                        int start = line.indexOf("}") + 1;
                        int end = line.lastIndexOf("{");
                        if(end > start) between = line.substring(start, end);
                    }
                    comp.addExtra(new TextComponent(ChatUtils.format(target, between)));
                    comp.addExtra(denyBtn);
                }

                target.spigot().sendMessage(comp);
            } else {
                String processed = line.replace("%player%", sender.getName());
                target.sendMessage(ChatUtils.format(sender, processed));
            }
        }
    }

    private void saveToggleAsync(UUID uuid, boolean add) {
        Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
            try (PreparedStatement ps = connection.prepareStatement(add ? "INSERT OR IGNORE INTO toggles (uuid) VALUES (?)" : "DELETE FROM toggles WHERE uuid = ?")) {
                ps.setString(1, uuid.toString());
                ps.executeUpdate();
            } catch (SQLException e) {
                plugin.getLogger().warning("Falha ao salvar o estado do toggle para o UUID " + uuid + ": " + e.getMessage());
            }
        });
    }

    private void startCleanerTask() {
        new BukkitRunnable() {
            @Override
            public void run() {
                long expirationTime = plugin.getConfig().getInt("settings.request-expiration", 60) * 1000L;
                if (expirationTime <= 0) return; // Tarefa desativada se o tempo for 0 ou menor

                long now = System.currentTimeMillis();

                // Limpa cooldowns expirados
                cooldowns.entrySet().removeIf(entry -> entry.getValue() < now);

                // Limpa pedidos de TPA expirados
                pendingRequests.entrySet().removeIf(entry -> {
                    long requestTime = requestTimestamps.getOrDefault(entry.getKey(), 0L);
                    boolean expired = (now - requestTime) > expirationTime && requestTime != 0L;
                    if (expired) {
                        reverseRequests.remove(entry.getValue());
                        requestTimestamps.remove(entry.getKey());
                        Player sender = Bukkit.getPlayer(entry.getKey());
                        if (sender != null && sender.isOnline()) {
                            ChatUtils.sendMessage(sender, "request-expired");
                        }
                    }
                    return expired;
                });
            }
        }.runTaskTimerAsynchronously(plugin, 600L, 600L);
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent e) {
        UUID disconnectedPlayerUUID = e.getPlayer().getUniqueId();

        // Se o jogador que saiu tinha um pedido ENVIADO
        if (pendingRequests.containsKey(disconnectedPlayerUUID)) {
            UUID targetUUID = pendingRequests.get(disconnectedPlayerUUID);
            removeRequest(disconnectedPlayerUUID, targetUUID);
            requestTimestamps.remove(disconnectedPlayerUUID);
        }

        // Se o jogador que saiu tinha um pedido RECEBIDO
        if (reverseRequests.containsKey(disconnectedPlayerUUID)) {
            UUID senderUUID = reverseRequests.get(disconnectedPlayerUUID);
            removeRequest(senderUUID, disconnectedPlayerUUID);
            Player sender = Bukkit.getPlayer(senderUUID);
            requestTimestamps.remove(senderUUID);
            if (sender != null && sender.isOnline()) {
                sender.sendMessage(ChatUtils.format(sender, plugin.getConfig().getString("messages.request-canceled-target").replace("%player%", e.getPlayer().getName())));
            }
        }

        cooldowns.remove(disconnectedPlayerUUID);
        cancelTeleport(e.getPlayer());
    }

    @EventHandler
    public void onPlayerMove(PlayerMoveEvent event) {
        if (teleportTasks.containsKey(event.getPlayer().getUniqueId())) {
            Location from = event.getFrom();
            Location to = event.getTo();
            if (to == null || from.getX() != to.getX() || from.getY() != to.getY() || from.getZ() != to.getZ()) {
                cancelTeleport(event.getPlayer());
                event.getPlayer().sendMessage(ChatUtils.format(event.getPlayer(), plugin.getConfig().getString("messages.teleport-canceled-move")));
                sendActionBar(event.getPlayer(), ChatUtils.format(event.getPlayer(), plugin.getConfig().getString("messages.actionbar-move")));
            }
        }
    }

    public boolean hasPendingRequest(UUID sender) { return pendingRequests.containsKey(sender); }
    public boolean isToggled(UUID target) { return toggles.contains(target); }
    public boolean isOnCooldown(UUID sender) { return cooldowns.getOrDefault(sender, 0L) > System.currentTimeMillis(); }
    public long getCooldownSeconds(UUID sender) { return (cooldowns.get(sender) - System.currentTimeMillis()) / 1000; }
    
    private void sendActionBar(Player player, String message) { // Implementação específica para 1.8.8
        if (message == null || message.isEmpty()) return;
        setupNMS(); // Garante que a reflexão foi inicializada
        if (nmsSetupFailed) return;

        try {
            Object chatComponent = chatSerializerAMethod.invoke(null, "{\"text\": \"" + message + "\"}");
            Object packet = packetPlayOutChatConstructor.newInstance(chatComponent, (byte) 2);

            Object handle = getHandleMethod.invoke(player);
            Object playerConnection = playerConnectionField.get(handle);
            sendPacketMethod.invoke(playerConnection, packet);
        } catch (Exception e) {
            plugin.getLogger().severe("Falha crítica ao enviar ActionBar via NMS: " + e.getMessage());
            nmsSetupFailed = true; // Desativa para evitar spam de erros
        }
    }

    private void setupNMS() {
        if (packetPlayOutChatConstructor != null || nmsSetupFailed) return; // Já configurado ou falhou

        try {
            String version = Bukkit.getServer().getClass().getPackage().getName().split("\\.")[3];
            Class<?> craftPlayerClass = Class.forName("org.bukkit.craftbukkit." + version + ".entity.CraftPlayer");
            Class<?> packetPlayOutChatClass = Class.forName("net.minecraft.server." + version + ".PacketPlayOutChat");
            Class<?> packetClass = Class.forName("net.minecraft.server." + version + ".Packet");
            Class<?> iChatBaseComponentClass = Class.forName("net.minecraft.server." + version + ".IChatBaseComponent");
            Class<?> chatSerializerClass = iChatBaseComponentClass.getClasses()[0]; // IChatBaseComponent$ChatSerializer
            Class<?> playerConnectionClass = Class.forName("net.minecraft.server." + version + ".PlayerConnection");

            getHandleMethod = craftPlayerClass.getMethod("getHandle");
            playerConnectionField = getHandleMethod.getReturnType().getField("playerConnection");
            sendPacketMethod = playerConnectionClass.getMethod("sendPacket", packetClass);
            chatSerializerAMethod = chatSerializerClass.getMethod("a", String.class);
            packetPlayOutChatConstructor = packetPlayOutChatClass.getConstructor(iChatBaseComponentClass, byte.class);
        } catch (Exception e) {
            plugin.getLogger().severe("Erro ao inicializar NMS para ActionBars. Esta funcionalidade será desativada.");
            e.printStackTrace();
            nmsSetupFailed = true;
        }
    }

    private void removeRequest(UUID sender, UUID target) {
        if (sender != null) pendingRequests.remove(sender);
        if (target != null) reverseRequests.remove(target);
    }

    private void cancelTeleport(Player player) {
        BukkitRunnable task = teleportTasks.remove(player.getUniqueId());
        if (task != null) {
            try {
                task.cancel();
            } catch (IllegalStateException ignored) {}
        }
    }

    private void playSound(Player p) {
        if (plugin.getConfig().getBoolean("sounds.enabled")) {
            String soundName = plugin.getConfig().getString("sounds.sound-name", "ENTITY_PLAYER_LEVELUP");
            try {
                p.playSound(p.getLocation(), Sound.valueOf(soundName.toUpperCase()), 1f, 1f);
            } catch (IllegalArgumentException e) {
                plugin.getLogger().warning("O som '" + soundName + "' especificado na config.yml é inválido.");
            }
        }
    }
}