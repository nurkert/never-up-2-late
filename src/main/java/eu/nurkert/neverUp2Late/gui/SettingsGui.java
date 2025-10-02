package eu.nurkert.neverUp2Late.gui;

import eu.nurkert.neverUp2Late.core.ConfigurationHelper;
import eu.nurkert.neverUp2Late.core.PluginContext;
import eu.nurkert.neverUp2Late.update.UpdateSourceRegistry;
import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.Material;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryCloseEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * GUI that exposes global configuration options of the plugin for quick adjustments in-game.
 */
public class SettingsGui implements Listener {

    private static final int MAIN_SIZE = 27;
    private static final int SOURCES_MAX_SIZE = 54;
    private static final int MAIN_LIFECYCLE_SLOT = 10;
    private static final int MAIN_AUTOLOAD_SLOT = 12;
    private static final int MAIN_IGNORE_UNSTABLE_SLOT = 14;
    private static final int MAIN_SOURCES_SLOT = 16;
    private static final int MAIN_BACK_SLOT = 26;

    private final PluginContext context;
    private final Map<UUID, Session> sessions = new ConcurrentHashMap<>();
    private PluginOverviewGui overviewGui;

    public SettingsGui(PluginContext context) {
        this.context = Objects.requireNonNull(context, "context");
    }

    public void setOverviewGui(PluginOverviewGui overviewGui) {
        this.overviewGui = overviewGui;
    }

    public void open(Player player) {
        openMain(player);
    }

    private void openMain(Player player) {
        Inventory inventory = Bukkit.createInventory(null, MAIN_SIZE, ChatColor.DARK_PURPLE + "NU2L Einstellungen");
        fill(inventory);

        inventory.setItem(MAIN_LIFECYCLE_SLOT, createLifecycleItem());
        inventory.setItem(MAIN_AUTOLOAD_SLOT, createAutoLoadItem());
        inventory.setItem(MAIN_IGNORE_UNSTABLE_SLOT, createIgnoreUnstableItem());
        inventory.setItem(MAIN_SOURCES_SLOT, createSourcesItem());
        inventory.setItem(MAIN_BACK_SLOT, createBackItem(ChatColor.YELLOW + "Zurück zur Übersicht"));

        sessions.put(player.getUniqueId(), Session.main(inventory));
        player.openInventory(inventory);
    }

    private void openSources(Player player) {
        FileConfiguration configuration = context.getConfiguration();
        ConfigurationSection sourcesSection = ConfigurationHelper.ensureSourcesSection(configuration);

        List<String> keys = new ArrayList<>(sourcesSection.getKeys(false));
        keys.sort(Comparator.naturalOrder());

        int reservedSlots = 1;
        int requiredSlots = keys.size() + reservedSlots;
        int size = Math.max(9, ((requiredSlots + 8) / 9) * 9);
        size = Math.min(size, SOURCES_MAX_SIZE);

        Inventory inventory = Bukkit.createInventory(null, size, ChatColor.DARK_PURPLE + "Update-Quellen");
        fill(inventory);

        Map<Integer, String> slotMapping = new HashMap<>();
        int availableSlots = size - reservedSlots;
        for (int i = 0; i < availableSlots && i < keys.size(); i++) {
            String key = keys.get(i);
            ConfigurationSection entry = sourcesSection.getConfigurationSection(key);
            if (entry == null) {
                continue;
            }
            inventory.setItem(i, createSourceItem(entry));
            slotMapping.put(i, key);
        }

        inventory.setItem(size - 1, createBackItem(ChatColor.YELLOW + "Zurück zu den Einstellungen"));

        sessions.put(player.getUniqueId(), Session.sources(inventory, slotMapping));
        player.openInventory(inventory);
    }

    private ItemStack createLifecycleItem() {
        boolean enabled = context.isPluginLifecycleEnabled();
        ItemStack item = new ItemStack(enabled ? Material.LIME_DYE : Material.RED_DYE);
        ItemMeta meta = item.getItemMeta();
        if (meta != null) {
            meta.setDisplayName(ChatColor.AQUA + "Plugin-Verwaltung");
            List<String> lore = new ArrayList<>();
            lore.add(ChatColor.GRAY + "Status: " + (enabled ? ChatColor.GREEN + "Aktiv" : ChatColor.RED + "Deaktiviert"));
            lore.add(ChatColor.YELLOW + "Klicke, um " + (enabled ? "zu deaktivieren." : "zu aktivieren."));
            meta.setLore(lore);
            item.setItemMeta(meta);
        }
        return item;
    }

    private ItemStack createAutoLoadItem() {
        boolean enabled = context.isAutoLoadOnInstallEnabled();
        ItemStack item = new ItemStack(enabled ? Material.ENDER_EYE : Material.ENDER_PEARL);
        ItemMeta meta = item.getItemMeta();
        if (meta != null) {
            meta.setDisplayName(ChatColor.AQUA + "Plugins nach Installation laden");
            List<String> lore = new ArrayList<>();
            lore.add(ChatColor.GRAY + "Status: " + (enabled ? ChatColor.GREEN + "Aktiv" : ChatColor.RED + "Deaktiviert"));
            lore.add(ChatColor.GRAY + "Gilt, wenn die Plugin-Verwaltung aktiv ist.");
            lore.add(ChatColor.YELLOW + "Klicke, um umzuschalten.");
            meta.setLore(lore);
            item.setItemMeta(meta);
        }
        return item;
    }

    private ItemStack createIgnoreUnstableItem() {
        boolean ignoreUnstable = context.getConfiguration().getBoolean("updates.ignoreUnstable",
                context.getConfiguration().getBoolean("ignoreUnstable", true));
        ItemStack item = new ItemStack(ignoreUnstable ? Material.SLIME_BALL : Material.MAGMA_CREAM);
        ItemMeta meta = item.getItemMeta();
        if (meta != null) {
            meta.setDisplayName(ChatColor.AQUA + "Instabile Builds ignorieren");
            List<String> lore = new ArrayList<>();
            lore.add(ChatColor.GRAY + "Status: " + (ignoreUnstable ? ChatColor.GREEN + "Ja" : ChatColor.RED + "Nein"));
            lore.add(ChatColor.GRAY + "Gilt für Fetcher, die Filter unterstützen.");
            lore.add(ChatColor.YELLOW + "Klicke, um umzuschalten.");
            meta.setLore(lore);
            item.setItemMeta(meta);
        }
        return item;
    }

    private ItemStack createSourcesItem() {
        ItemStack item = new ItemStack(Material.BOOK);
        ItemMeta meta = item.getItemMeta();
        if (meta != null) {
            meta.setDisplayName(ChatColor.AQUA + "Update-Quellen verwalten");
            meta.setLore(List.of(
                    ChatColor.GRAY + "Aktiviere oder deaktiviere Paper, Geyser & Co.",
                    ChatColor.YELLOW + "Klicke, um die Liste zu öffnen."
            ));
            item.setItemMeta(meta);
        }
        return item;
    }

    private ItemStack createSourceItem(ConfigurationSection section) {
        String name = section.getString("name", section.getName());
        boolean enabled = section.getBoolean("enabled", true);
        Material material = enabled ? Material.LIME_DYE : Material.GRAY_DYE;
        ItemStack item = new ItemStack(material);
        ItemMeta meta = item.getItemMeta();
        if (meta != null) {
            meta.setDisplayName(ChatColor.AQUA + name);
            List<String> lore = new ArrayList<>();
            lore.add(ChatColor.GRAY + "Status: " + (enabled ? ChatColor.GREEN + "Aktiv" : ChatColor.RED + "Deaktiviert"));
            String type = section.getString("type", "unbekannt");
            lore.add(ChatColor.GRAY + "Fetcher: " + ChatColor.AQUA + type);
            String target = section.getString("target", "PLUGINS");
            lore.add(ChatColor.GRAY + "Ziel: " + ChatColor.AQUA + target);
            lore.add(ChatColor.YELLOW + "Klicke, um umzuschalten.");
            meta.setLore(lore);
            item.setItemMeta(meta);
        }
        return item;
    }

    private ItemStack createBackItem(String title) {
        ItemStack item = new ItemStack(Material.ARROW);
        ItemMeta meta = item.getItemMeta();
        if (meta != null) {
            meta.setDisplayName(title);
            meta.setLore(List.of(ChatColor.GRAY + "Klicke, um zurückzugehen."));
            item.setItemMeta(meta);
        }
        return item;
    }

    private void fill(Inventory inventory) {
        ItemStack filler = new ItemStack(Material.GRAY_STAINED_GLASS_PANE);
        ItemMeta meta = filler.getItemMeta();
        if (meta != null) {
            meta.setDisplayName(" ");
            filler.setItemMeta(meta);
        }
        for (int i = 0; i < inventory.getSize(); i++) {
            inventory.setItem(i, filler.clone());
        }
    }

    @EventHandler
    public void onInventoryClick(InventoryClickEvent event) {
        if (!(event.getWhoClicked() instanceof Player player)) {
            return;
        }

        Session session = sessions.get(player.getUniqueId());
        if (session == null || !session.inventory().equals(event.getView().getTopInventory())) {
            return;
        }

        if (event.getRawSlot() >= session.inventory().getSize()) {
            event.setCancelled(true);
            return;
        }

        event.setCancelled(true);

        if (session.view() == View.MAIN) {
            handleMainClick(player, event.getRawSlot());
        } else if (session.view() == View.SOURCES) {
            handleSourceClick(player, event.getRawSlot(), session.sources());
        }
    }

    private void handleMainClick(Player player, int slot) {
        if (slot == MAIN_LIFECYCLE_SLOT) {
            toggleLifecycle(player);
            openMain(player);
            return;
        }
        if (slot == MAIN_AUTOLOAD_SLOT) {
            toggleAutoLoad(player);
            openMain(player);
            return;
        }
        if (slot == MAIN_IGNORE_UNSTABLE_SLOT) {
            toggleIgnoreUnstable(player);
            openMain(player);
            return;
        }
        if (slot == MAIN_SOURCES_SLOT) {
            openSources(player);
            return;
        }
        if (slot == MAIN_BACK_SLOT) {
            player.closeInventory();
            sessions.remove(player.getUniqueId());
            if (overviewGui != null) {
                overviewGui.reopenOverview(player);
            }
        }
    }

    private void handleSourceClick(Player player, int slot, Map<Integer, String> sources) {
        if (slot == player.getOpenInventory().getTopInventory().getSize() - 1) {
            openMain(player);
            return;
        }

        String key = sources.get(slot);
        if (key == null) {
            return;
        }

        FileConfiguration configuration = context.getConfiguration();
        ConfigurationSection sourcesSection = ConfigurationHelper.ensureSourcesSection(configuration);
        ConfigurationSection entry = sourcesSection.getConfigurationSection(key);
        if (entry == null) {
            player.sendMessage(ChatColor.RED + "Konfigurationseintrag nicht gefunden.");
            openSources(player);
            return;
        }

        boolean enabled = entry.getBoolean("enabled", true);
        entry.set("enabled", !enabled);
        context.getPlugin().saveConfig();
        UpdateSourceRegistry registry = context.getUpdateSourceRegistry();
        registry.reloadFromConfiguration();

        player.sendMessage((!enabled ? ChatColor.GREEN : ChatColor.YELLOW)
                + "Quelle " + entry.getString("name", key) + " ist nun "
                + (!enabled ? "aktiv" : "deaktiviert") + ".");
        openSources(player);
    }

    private void toggleLifecycle(Player player) {
        FileConfiguration configuration = context.getConfiguration();
        boolean enabled = context.isPluginLifecycleEnabled();
        if (enabled) {
            context.disablePluginLifecycle();
            configuration.set("pluginLifecycle.autoManage", false);
            context.getPlugin().saveConfig();
            player.sendMessage(ChatColor.YELLOW + "Plugin-Verwaltung deaktiviert. Aktionen an Plugins sind vorübergehend nicht verfügbar.");
        } else {
            context.enablePluginLifecycle();
            configuration.set("pluginLifecycle.autoManage", true);
            context.getPlugin().saveConfig();
            player.sendMessage(ChatColor.GREEN + "Plugin-Verwaltung aktiviert. Plugins können nun über die GUI verwaltet werden.");
        }
    }

    private void toggleAutoLoad(Player player) {
        boolean enabled = context.isAutoLoadOnInstallEnabled();
        boolean next = !enabled;
        context.setAutoLoadOnInstall(next);
        context.getConfiguration().set("pluginLifecycle.autoLoadOnInstall", next);
        context.getPlugin().saveConfig();
        player.sendMessage((next ? ChatColor.GREEN : ChatColor.YELLOW)
                + "Automatisches Laden nach der Installation ist nun "
                + (next ? "aktiv." : "deaktiviert."));
    }

    private void toggleIgnoreUnstable(Player player) {
        FileConfiguration configuration = context.getConfiguration();
        boolean current = configuration.getBoolean("updates.ignoreUnstable",
                configuration.getBoolean("ignoreUnstable", true));
        boolean next = !current;
        configuration.set("updates.ignoreUnstable", next);
        context.getPlugin().saveConfig();
        context.getUpdateSourceRegistry().reloadFromConfiguration();
        player.sendMessage((next ? ChatColor.GREEN : ChatColor.YELLOW)
                + "Instabile Builds werden " + (next ? "ignoriert." : "nun berücksichtigt."));
    }

    @EventHandler
    public void onInventoryClose(InventoryCloseEvent event) {
        sessions.remove(event.getPlayer().getUniqueId());
    }

    @EventHandler
    public void onPlayerQuit(PlayerQuitEvent event) {
        sessions.remove(event.getPlayer().getUniqueId());
    }

    private enum View {
        MAIN,
        SOURCES
    }

    private record Session(Inventory inventory, View view, Map<Integer, String> sources) {
        static Session main(Inventory inventory) {
            return new Session(inventory, View.MAIN, Map.of());
        }

        static Session sources(Inventory inventory, Map<Integer, String> sources) {
            return new Session(inventory, View.SOURCES, Map.copyOf(sources));
        }
    }
}
