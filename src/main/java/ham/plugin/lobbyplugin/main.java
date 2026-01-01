package ham.plugin.lobbyplugin;

import java.io.File;
import java.io.IOException;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Level;

import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.Particle;
import org.bukkit.Sound;
import org.bukkit.command.PluginCommand;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerDropItemEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerMoveEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.BookMeta;
import org.bukkit.plugin.java.JavaPlugin;

import net.md_5.bungee.api.ChatColor;
import net.md_5.bungee.api.chat.BaseComponent;
import net.md_5.bungee.api.chat.ClickEvent;
import net.md_5.bungee.api.chat.TextComponent;

public class main extends JavaPlugin implements Listener {

    private final Set<UUID> knownPlayers = new HashSet<>();
    private File playerDataFile;
    private final Set<UUID> airbornePlayers = new HashSet<>();
    private final Set<UUID> frozenPlayers = new HashSet<>();
    private final Map<UUID, Long> lastMoveWarn = new ConcurrentHashMap<>();
    private final Map<UUID, org.bukkit.scheduler.BukkitTask> autoCloseTasks = new ConcurrentHashMap<>();

    @Override
    public void onEnable() {
        saveDefaultConfig();
        loadKnownPlayers();
        getServer().getPluginManager().registerEvents(this, this);
        getServer().getPluginManager().registerEvents(new Listener() {
            @EventHandler
            public void onPlayerJoin(PlayerJoinEvent event) {
                Player player = event.getPlayer();
                if (!knownPlayers.contains(player.getUniqueId())) {
                    freezePlayer(player);
                }
            }
        }, this);
        PluginCommand lobbyCloseCommand = getCommand("lobbyclose");
        if (lobbyCloseCommand != null) {
            lobbyCloseCommand.setExecutor((sender, command, label, args) -> {
                if (sender instanceof Player player) {
                    unfreezePlayer(player); 
                    player.closeInventory();
                    FileConfiguration config = getConfig();
                    String title = org.bukkit.ChatColor.translateAlternateColorCodes('&',
                            config.getString("rulebook.welcome-title", "&aようこそ！"));
                    String sub = org.bukkit.ChatColor.translateAlternateColorCodes('&',
                            config.getString("rulebook.welcome-subtitle", "&f楽しい時間を過ごそう！"));
                    player.sendTitle(title, sub, 10, 40, 10);
                }
                return true;
            });
        }
        PluginCommand lobbyLoginCommand = getCommand("lobbylogin");
        if (lobbyLoginCommand != null) {
            lobbyLoginCommand.setExecutor((sender, command, label, args) -> {
                Player target = null;
                if (args.length >= 1) target = Bukkit.getPlayerExact(args[0]);
                if (target == null && sender instanceof Player p) target = p;
                if (target == null) {
                    if (sender != null) {
                        sender.sendMessage(org.bukkit.ChatColor.RED + "プレイヤーが見つかりません。");
                    }
                    return true;
                }
                playLoginSound(target);
                sender.sendMessage(org.bukkit.ChatColor.GREEN + "ログインサウンドを再生しました: " + target.getName());
                return true;
            });
        }
        PluginCommand lobbyFirstJoinCommand = getCommand("lobbyfirstjoin");
        if (lobbyFirstJoinCommand != null) {
            lobbyFirstJoinCommand.setExecutor((sender, command, label, args) -> {
                Player target = null;
                if (args.length >= 1) target = Bukkit.getPlayerExact(args[0]);
                if (target == null && sender instanceof Player p) target = p;
                if (target == null) {
                    if (sender != null) {
                        sender.sendMessage(org.bukkit.ChatColor.RED + "プレイヤーが見つかりません。");
                    }
                    return true;
                }
                handleFirstLogin(target, true);
                playLoginSound(target);
                sender.sendMessage(org.bukkit.ChatColor.GREEN + "初参加処理を実行しました: " + target.getName());
                return true;
            });
        }
        PluginCommand lobbyReloadCommand = getCommand("lobbyreload");
        if (lobbyReloadCommand != null) {
            lobbyReloadCommand.setExecutor((sender, command, label, args) -> {
                if (sender.hasPermission("lobbyplugin.reload")) {
                    reloadConfig();
                    sender.sendMessage(org.bukkit.ChatColor.GREEN + "Configuration reloaded successfully.");
                } else {
                    sender.sendMessage(org.bukkit.ChatColor.RED + "You do not have permission to execute this command.");
                }
                return true;
            });
        }
        getLogger().info("LobbyPlugin enabled!");
    }

    @Override
    public void onDisable() {
        saveKnownPlayers();
    }

    private void loadKnownPlayers() {
        playerDataFile = new File(getDataFolder(), "players.txt");
        if (playerDataFile.exists()) {
            try {
                List<String> lines = java.nio.file.Files.readAllLines(playerDataFile.toPath());
                for (String line : lines) {
                    if (line != null && !line.isEmpty()) {
                        try {
                            knownPlayers.add(UUID.fromString(line));
                        } catch (IllegalArgumentException ex) {
                            getLogger().log(Level.WARNING, "Skipping invalid UUID in players file: {0}", line);
                        }
                    }
                }
            } catch (java.io.IOException e) {
                getLogger().log(Level.WARNING, "Failed to load known players", e);
            }
        }
    }

    private void saveKnownPlayers() {
        Bukkit.getScheduler().runTaskAsynchronously(this, () -> {
            try {
                java.nio.file.Files.write(playerDataFile.toPath(),
                    knownPlayers.stream().map(UUID::toString).toList());
            } catch (IOException e) {
                getLogger().log(Level.SEVERE, "Failed to write player data file", e);
            }
        });
    }

    @EventHandler
    public void onPlayerJoin(PlayerJoinEvent event) {
        Player player = event.getPlayer();
        playLoginSound(player);
        handleFirstLogin(player, false);
    }

    private void giveAndOpenRuleBook(Player player) {
        FileConfiguration config = getConfig();
        ItemStack book = new ItemStack(Material.WRITTEN_BOOK);
        BookMeta meta = (BookMeta) book.getItemMeta();

        meta.setTitle(config.getString("rulebook.title", "Server Rules"));
        meta.setAuthor(config.getString("rulebook.author", "Server"));

        List<String> pages = config.getStringList("rulebook.pages");
        if (pages.isEmpty()) {
            pages = List.of("Welcome to the server!\n\nPlease follow the rules.");
        }
        for (String page : pages) {
            meta.addPage(ChatColor.translateAlternateColorCodes('&', page));
        }

        if (config.getBoolean("rulebook.close-button.enabled", true)) {
            String buttonTextRaw = config.getString("rulebook.close-button.text", "&a[閉じる]");
            if (buttonTextRaw == null) buttonTextRaw = "&a[閉じる]";
            String headerRaw = config.getString("rulebook.close-button.header", "&6=== 終了 ===\n\n");
            if (headerRaw == null) headerRaw = "&6=== 終了 ===\n\n";
            BaseComponent[] header = TextComponent.fromLegacyText(org.bukkit.ChatColor.translateAlternateColorCodes('&', headerRaw));
            TextComponent button = new TextComponent(org.bukkit.ChatColor.stripColor(org.bukkit.ChatColor.translateAlternateColorCodes('&', buttonTextRaw)));
            button.setColor(ChatColor.GREEN);
            button.setBold(true);
            button.setClickEvent(new ClickEvent(ClickEvent.Action.RUN_COMMAND, "/lobbyclose"));

            TextComponent newline = new TextComponent("\n\n");
            TextComponent tip = new TextComponent("クリックすると本を閉じ、続行します。");
            tip.setColor(ChatColor.GRAY);

            BaseComponent[] page = concatComponents(header, new BaseComponent[]{button, newline, tip});
            meta.spigot().addPage(page);
        }

        book.setItemMeta(meta);
        player.getInventory().addItem(book);
        if (player != null) {
            player.openBook(book);
            FileConfiguration cfg = getConfig();
            boolean autoClose = cfg.getBoolean("rulebook.auto-close-enabled", false);
            long autoCloseTicks = cfg.getLong("rulebook.auto-close-ticks", 1200L);
            if (autoClose) {
                UUID uuid = player.getUniqueId();
                org.bukkit.scheduler.BukkitTask prev = autoCloseTasks.remove(uuid);
                if (prev != null) prev.cancel();
                org.bukkit.scheduler.BukkitTask task = Bukkit.getScheduler().runTaskLater(this, () -> {
                    if (frozenPlayers.contains(uuid)) {
                        Player p = Bukkit.getPlayer(uuid);
                        if (p != null) {
                            p.closeInventory();
                            unfreezePlayer(p);
                        }
                    }
                    autoCloseTasks.remove(uuid);
                }, autoCloseTicks);
                autoCloseTasks.put(uuid, task);
            }
        }
    }

    private void playLoginSound(Player player) {
        FileConfiguration config = getConfig();
        if (!config.getBoolean("login-sound.enabled", true)) return;

        String soundName = config.getString("login-sound.sound", "minecraft:ui.toast.challenge_complete");
        float volume = (float) config.getDouble("login-sound.volume", 1.0);
        float pitch = (float) config.getDouble("login-sound.pitch", 1.0);

        if (soundName == null || soundName.isEmpty()) {
            getLogger().warning("Sound name is null or empty in the configuration.");
            return;
        }

        Sound sound = resolveSound(soundName);
        if (sound == null) {
            getLogger().log(Level.WARNING, "Invalid sound: {0}", soundName);
            return;
        }
        player.playSound(player.getLocation(), sound, volume, pitch);
    }

    private Sound resolveSound(String name) {
        try {
            return Sound.valueOf(name);
        } catch (IllegalArgumentException ignored) {}

        if (name.contains(":")) {
            String key = name.substring(name.indexOf(':') + 1);
            key = key.replace('.', '_').replace('-', '_').toUpperCase();
            try {
                return Sound.valueOf(key);
            } catch (IllegalArgumentException ignored2) {}
        }
        return null;
    }

    private void handleFirstLogin(Player player, boolean force) {
        FileConfiguration config = getConfig();
        boolean isKnown = knownPlayers.contains(player.getUniqueId());
        if (force || !isKnown) {
            knownPlayers.add(player.getUniqueId());
            saveKnownPlayers();
            if (config.getBoolean("rulebook.enabled", true)) {
                Bukkit.getScheduler().runTaskLater(this, () -> giveAndOpenRuleBook(player),
                        config.getLong("rulebook.open-delay-ticks", 20L));
            }
        }
    }

    private BaseComponent[] concatComponents(BaseComponent[] a, BaseComponent[] b) {
        BaseComponent[] out = new BaseComponent[a.length + b.length];
        System.arraycopy(a, 0, out, 0, a.length);
        System.arraycopy(b, 0, out, a.length, b.length);
        return out;
    }

    @EventHandler
    public void onPlayerMove(PlayerMoveEvent event) {
        Player player = event.getPlayer();
        FileConfiguration config = getConfig();
        if (!config.getBoolean("move-particles.enabled", true)) return;

        Location from = event.getFrom();
        Location to = event.getTo();
        if (to == null) return;

        if (from.getBlockX() == to.getBlockX() && 
            from.getBlockY() == to.getBlockY() && 
            from.getBlockZ() == to.getBlockZ()) {
            return;
        }

        boolean isOnGround = player.isOnGround();
        if (!isOnGround) {
            airbornePlayers.add(player.getUniqueId());
            Bukkit.getScheduler().runTaskLater(this, () -> airbornePlayers.remove(player.getUniqueId()), 10L); // 0.5 sec
        }

        if (isOnGround || airbornePlayers.contains(player.getUniqueId())) {
            String particleName = config.getString("move-particles.particle", "CLOUD");
            int count = config.getInt("move-particles.count", 3);
            double offsetY = config.getDouble("move-particles.offset-y", 0.1);

            Particle particle;
            try {
                particle = Particle.valueOf(particleName);
            } catch (IllegalArgumentException e) {
                return;
            }

            Location loc = player.getLocation().add(0, offsetY, 0);

            if (player.isSprinting()) {
                int sprintCount = config.getInt("move-particles.sprint-count", 8);
                player.getWorld().spawnParticle(particle, loc, sprintCount, 0.2, 0.05, 0.2, 0.01);
            } else {
                player.getWorld().spawnParticle(particle, loc, count, 0.1, 0.05, 0.1, 0.01);
            }
        }

        if (frozenPlayers.contains(player.getUniqueId())) {
            Location fromLocation = event.getFrom();
            Location toLocation = event.getTo();
            if (fromLocation.getX() != toLocation.getX() || fromLocation.getY() != toLocation.getY() || fromLocation.getZ() != toLocation.getZ()) {
                player.teleport(fromLocation);
                UUID uuid = player.getUniqueId();
                long now = System.currentTimeMillis();
                long last = lastMoveWarn.getOrDefault(uuid, 0L);
                if (now - last > 1500L) {
                    lastMoveWarn.put(uuid, now);
                    String warn = org.bukkit.ChatColor.translateAlternateColorCodes('&', config.getString("rulebook.move-warning", "&cルールを最後までご確認ください"));
                    player.sendMessage(warn);
                }
            }
        }
    }

    @EventHandler
    public void onPlayerDropItem(PlayerDropItemEvent event) {
        ItemStack item = event.getItemDrop().getItemStack();
        if (item != null && item.getType() == Material.WRITTEN_BOOK && item.getItemMeta() instanceof BookMeta meta) {
            String title = meta.getTitle();
            String configTitle = getConfig().getString("rulebook.title", "Server Rules");
            if (title != null && configTitle != null && title.equals(configTitle)) {
                event.setCancelled(true);
            }
        }
    }

    private void freezePlayer(Player player) {
        if (player == null) return;
        UUID uuid = player.getUniqueId();
        frozenPlayers.add(uuid);
    }

    private void unfreezePlayer(Player player) {
        if (player == null) return;
        UUID uuid = player.getUniqueId();
        frozenPlayers.remove(uuid);
        lastMoveWarn.remove(uuid);
        org.bukkit.scheduler.BukkitTask task = autoCloseTasks.remove(uuid);
        if (task != null) task.cancel();
    }
}
