package xyz.acrylicstyle.tos;

import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryCloseEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.event.player.AsyncPlayerChatEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerMoveEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitRunnable;
import org.jetbrains.annotations.NotNull;
import util.Collection;
import util.CollectionList;
import util.ICollectionList;
import xyz.acrylicstyle.tomeito_api.TomeitoAPI;
import xyz.acrylicstyle.tomeito_api.gui.ClickableItem;
import xyz.acrylicstyle.tomeito_api.providers.ConfigProvider;
import xyz.acrylicstyle.tomeito_api.sounds.Sound;
import xyz.acrylicstyle.tomeito_api.utils.Log;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.NoSuchElementException;
import java.util.Objects;
import java.util.UUID;

public class ConsentChecker extends JavaPlugin implements Listener {
    public static CollectionList<UUID> agreedPlayers = new CollectionList<>();
    public static String agreeMessage = "";
    public static String disagreeMessage = "";
    public static CollectionList<CollectionList<String>> rules = new CollectionList<>();
    public static ConsentChecker instance = null;

    @Override
    public void onLoad() {
        instance = this;
    }

    @Override
    public void onEnable() {
        ConfigProvider config = ConfigProvider.getConfig("./plugins/ConsentChecker/messages.yml");
        loadMessages(config);
        this.getConfig().options().copyDefaults(false);
        Bukkit.getPluginManager().registerEvents(this, this);
        agreedPlayers.addAll(ICollectionList.asList(this.getConfig().getStringList("agreedPlayers")).map(UUID::fromString));
        TomeitoAPI.registerCommand("consentreload", (sender, command, label, args) -> {
            config.reload();
            loadMessages(config);
            sender.sendMessage(ChatColor.GREEN + "Config reloaded.");
            return true;
        });
    }

    @SuppressWarnings("unchecked")
    private void loadMessages(ConfigProvider config) {
        agreeMessage = ChatColor.translateAlternateColorCodes('&', Objects.requireNonNull(config.getString("messages.agree", "&a[同意する]")));
        disagreeMessage = ChatColor.translateAlternateColorCodes('&', Objects.requireNonNull(config.getString("messages.disagree", "&c[同意しない]")));
        rules = ICollectionList.asList(Objects.requireNonNull(config.getList("rules", new ArrayList<>()))).map(o -> {
            if (o instanceof List) {
                List<String> list = (List<String>) o;
                return ICollectionList.asList(list).map(s -> ChatColor.translateAlternateColorCodes('&', s));
            }
            return CollectionList.of(ChatColor.translateAlternateColorCodes('&', (String) o));
        });
    }

    @Override
    public void onDisable() {
        this.getConfig().set("agreedPlayers", agreedPlayers.map(UUID::toString).toList());
        try {
            this.getConfig().save("./plugins/ConsentChecker/config.yml");
        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    @EventHandler
    public void onBlockBreak(BlockBreakEvent e) {
        if (agreedPlayers.contains(e.getPlayer().getUniqueId())) return;
        e.setCancelled(true);
    }

    @EventHandler
    public void onBlockPlace(BlockPlaceEvent e) {
        if (agreedPlayers.contains(e.getPlayer().getUniqueId())) return;
        e.setCancelled(true);
    }

    @EventHandler
    public void onPlayerJoin(PlayerJoinEvent e) {
        if (agreedPlayers.contains(e.getPlayer().getUniqueId())) return;
        e.getPlayer().playSound(e.getPlayer().getLocation(), Sound.BLOCK_NOTE_PLING, 100, 1);
        showGui(e.getPlayer());
    }

    @EventHandler
    public void onInventoryClose(InventoryCloseEvent e) {
        if (e.getInventory().getHolder() instanceof ConsentGui) {
            if (agreedPlayers.contains(e.getPlayer().getUniqueId())) return;
            new BukkitRunnable() {
                @Override
                public void run() {
                    showGui((Player) e.getPlayer());
                }
            }.runTask(this);
        }
    }

    @EventHandler
    public void onPlayerMove(PlayerMoveEvent e) {
        if (agreedPlayers.contains(e.getPlayer().getUniqueId())) return;
        e.setCancelled(true);
    }

    @EventHandler
    public void onAsyncPlayerChat(AsyncPlayerChatEvent e) {
        if (agreedPlayers.contains(e.getPlayer().getUniqueId())) return;
        e.setCancelled(true);
    }

    private static final Collection<UUID, ConsentGui> cachedGui = new Collection<>();

    public void showGui(@NotNull Player player) {
        player.openInventory(getGuiFor(player.getUniqueId()).getInventory());
    }

    public ConsentGui getGuiFor(UUID uuid) {
        if (!cachedGui.containsKey(uuid)) cachedGui.add(uuid, new ConsentGui().register(uuid));
        return cachedGui.get(uuid);
    }

    public static class ConsentGui implements InventoryHolder, Listener {
        public static final ItemStack black = new ItemStack(Material.BLACK_STAINED_GLASS_PANE);

        static {
            ItemMeta meta = black.getItemMeta();
            meta.setDisplayName(" ");
            black.setItemMeta(meta);
        }

        private final CollectionList<Integer> agreedRules = new CollectionList<>();
        private final Collection<Integer, Integer> slotMap = new Collection<>();
        private UUID uuid = null;

        public ConsentGui register(UUID uuid) {
            this.uuid = uuid;
            Bukkit.getPluginManager().registerEvents(this, instance);
            return this;
        }

        public Inventory updateInventory(Inventory inventory) {
            inventory.clear();
            rules.foreach((_rule, index) -> {
                Material material;
                if (agreedRules.contains(index)) {
                    material = Material.LIME_WOOL; // agreed state
                } else {
                    material = Material.WHITE_WOOL; // default state (not set yet)
                }
                CollectionList<String> rule = _rule.clone();
                String s = rule.clone().get(0);
                rule.shift();
                int slot = 10 + index;
                if (slot >= 17) slot += 2;
                slotMap.add(slot, index);
                inventory.setItem(slot, ClickableItem.of(material, 1, s, rule, e -> {}).getItemStack());
            });
            if (agreedRules.size() >= rules.size()) inventory.setItem(38, ClickableItem.of(Material.LIME_WOOL, 1, ChatColor.GREEN + "同意する", new ArrayList<>(), e -> {}).getItemStack());
            inventory.setItem(42, ClickableItem.of(Material.RED_WOOL, 1, ChatColor.RED + "同意しない", new ArrayList<>(), e -> {}).getItemStack());
            inventory.setItem(53, ClickableItem.of(Material.BARRIER, 1, ChatColor.RED + "切断する", new ArrayList<>(), e -> {}).getItemStack());
            ItemStack[] contents = inventory.getContents();
            for (int i = 0; i < contents.length; i++)
                if (contents[i] == null || contents[i].getType() == Material.AIR)
                    contents[i] = black;
            inventory.setContents(contents);
            return inventory;
        }

        @Override
        @NotNull
        public Inventory getInventory() {
            return updateInventory(Bukkit.createInventory(this, 54, "利用規約 (すべての羊毛をクリックして同意)"));
        }

        @EventHandler
        public void onInventoryDrag(InventoryDragEvent e) {
            if (e.getInventory().getHolder() != this) return;
            e.setCancelled(true);
        }

        @EventHandler
        public void onInventoryClick(InventoryClickEvent e) {
            if (e.getInventory().getHolder() != this) return;
            Player player = (Player) e.getWhoClicked();
            if (!player.getUniqueId().equals(uuid)) return;
            e.setCancelled(true);
            if (e.getSlot() == 38) { // agree
                if (agreedRules.size() < rules.size()) return;
                player.playSound(player.getLocation(), Sound.BLOCK_NOTE_PLING, 1, 2);
                if (agreedRules.size() >= rules.size()) {
                    new BukkitRunnable() {
                        @Override
                        public void run() {
                            player.closeInventory();
                        }
                    }.runTask(ConsentChecker.instance);
                    player.sendMessage(ChatColor.GREEN + "ルールに同意したため、サーバーで遊べるようになりました！");
                    agreedPlayers.add(player.getUniqueId());
                    Bukkit.getOnlinePlayers().parallelStream().filter(Player::isOp).forEach(p -> {
                        p.playSound(p.getLocation(), Sound.BLOCK_NOTE_PLING, 10, 1);
                        p.sendMessage(ChatColor.YELLOW + player.getName() + ChatColor.GOLD + "がルールに同意しました。");
                    });
                } else {
                    updateInventory(e.getInventory());
                    player.updateInventory();
                }
            } else if (e.getSlot() == 42) { // disagree
                player.kickPlayer(ChatColor.RED + "同意規約に同意しなかったため、キックされました。");
            } else if (e.getSlot() == 53) { // disconnect
                player.kickPlayer("");
            } else {
                boolean isWool = e.getCurrentItem() != null && e.getCurrentItem().getType().name().endsWith("WOOL");
                if (isWool) {
                    if (!slotMap.containsKey(e.getSlot())) {
                        Log.error("Slot map does not contains " + e.getSlot() + "!");
                        Log.error("Slot map: " + slotMap.keysList().map(i -> Integer.toString(i)).join(", "));
                        throw new NoSuchElementException();
                    }
                    if (!agreedRules.contains(slotMap.get(e.getSlot()))) agreedRules.add(slotMap.get(e.getSlot()));
                    updateInventory(e.getInventory());
                    player.updateInventory();
                    player.playSound(player.getLocation(), org.bukkit.Sound.BLOCK_NOTE_BLOCK_BASS, 1, 2);
                }
            }
        }
    }
}
