package eu.nurkert.neverUp2Late.gui;

import eu.nurkert.neverUp2Late.Permissions;
import eu.nurkert.neverUp2Late.command.QuickInstallCoordinator;
import eu.nurkert.neverUp2Late.core.PluginContext;
import eu.nurkert.neverUp2Late.gui.anvil.AnvilTextPrompt;
import eu.nurkert.neverUp2Late.persistence.PluginUpdateSettingsRepository;
import eu.nurkert.neverUp2Late.persistence.PluginUpdateSettingsRepository.PluginUpdateSettings;
import eu.nurkert.neverUp2Late.persistence.PluginUpdateSettingsRepository.UpdateBehaviour;
import eu.nurkert.neverUp2Late.plugin.ManagedPlugin;
import eu.nurkert.neverUp2Late.plugin.PluginLifecycleException;
import eu.nurkert.neverUp2Late.plugin.PluginLifecycleManager;
import eu.nurkert.neverUp2Late.update.UpdateSourceRegistry.TargetDirectory;
import eu.nurkert.neverUp2Late.update.UpdateSourceRegistry.UpdateSource;
import eu.nurkert.neverUp2Late.update.suggestion.PluginLinkSuggestion;
import eu.nurkert.neverUp2Late.update.suggestion.PluginLinkSuggester;
import eu.nurkert.neverUp2Late.util.FileNameSanitizer;
import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryCloseEvent;
import org.bukkit.event.player.AsyncPlayerChatEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.plugin.Plugin;

import java.io.File;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Level;
import java.util.Locale;

/**
 * Creates a simple chest based GUI that lists all plugins currently managed by NU2L.
 */
public class PluginOverviewGui implements Listener {

    private static final int MAX_SIZE = 54;
    private static final int DETAIL_INVENTORY_SIZE = 27;
    private static final int DETAIL_STATUS_SLOT = 10;
    private static final int DETAIL_ENABLE_SLOT = 12;
    private static final int DETAIL_LOAD_SLOT = 14;
    private static final int DETAIL_LINK_SLOT = 16;
    private static final int DETAIL_DISABLE_SLOT = 18;
    private static final int DETAIL_UPDATE_SLOT = 20;
    private static final int DETAIL_RENAME_SLOT = 21;
    private static final int DETAIL_REMOVE_SLOT = 22;
    private static final int DETAIL_QUICK_RENAME_SLOT = 23;
    private static final int DETAIL_BACK_SLOT = 26;

    private final PluginContext context;
    private final QuickInstallCoordinator coordinator;
    private final Map<UUID, InventorySession> openInventories = new ConcurrentHashMap<>();
    private final Map<UUID, LinkRequest> pendingLinkRequests = new ConcurrentHashMap<>();
    private final Map<UUID, ManagedPlugin> pendingRemovalRequests = new ConcurrentHashMap<>();
    private final Map<UUID, ManagedPlugin> pendingSuggestionRequests = new ConcurrentHashMap<>();
    private final PluginUpdateSettingsRepository updateSettingsRepository;
    private final PluginLinkSuggester linkSuggester;
    private final AnvilTextPrompt anvilTextPrompt;

    public PluginOverviewGui(PluginContext context, QuickInstallCoordinator coordinator, AnvilTextPrompt anvilTextPrompt) {
        this.context = Objects.requireNonNull(context, "context");
        this.coordinator = Objects.requireNonNull(coordinator, "coordinator");
        this.anvilTextPrompt = Objects.requireNonNull(anvilTextPrompt, "anvilTextPrompt");
        this.updateSettingsRepository = context.getPluginUpdateSettingsRepository();
        this.linkSuggester = new PluginLinkSuggester(context.getPlugin().getLogger());
    }

    public void open(Player player) {
        if (!player.hasPermission(Permissions.GUI_OPEN)) {
            player.sendMessage(ChatColor.RED + "Dir fehlt die Berechtigung, die NU2L-GUI zu öffnen.");
            return;
        }
        if (context.getPluginLifecycleManager() == null) {
            player.sendMessage(ChatColor.RED + "Die Plugin-Verwaltung ist deaktiviert.");
            return;
        }
        openOverview(player);
    }

    private void openOverview(Player player) {
        List<ManagedPlugin> plugins = context.getPluginLifecycleManager().getManagedPlugins()
                .stream()
                .sorted(Comparator.comparing(ManagedPlugin::getName, String.CASE_INSENSITIVE_ORDER))
                .toList();

        int requiredSlots = plugins.size() + 1;
        int size = Math.max(9, ((requiredSlots + 8) / 9) * 9);
        size = Math.min(size, MAX_SIZE);

        Inventory inventory = Bukkit.createInventory(null, size, ChatColor.DARK_PURPLE + "NU2L Plugins");

        ItemStack filler = createFiller();
        for (int i = 0; i < size; i++) {
            inventory.setItem(i, filler.clone());
        }

        Map<Integer, ManagedPlugin> slotMapping = new HashMap<>();
        int availableSlots = Math.max(0, size - 1);
        boolean truncated = plugins.size() > availableSlots;
        for (int slot = 0; slot < availableSlots && slot < plugins.size(); slot++) {
            ManagedPlugin plugin = plugins.get(slot);
            inventory.setItem(slot, createPluginItem(plugin));
            slotMapping.put(slot, plugin);
        }

        inventory.setItem(size - 1, createInstallButton());

        openInventories.put(player.getUniqueId(), InventorySession.overview(inventory, slotMapping));
        player.openInventory(inventory);

        if (truncated) {
            player.sendMessage(ChatColor.YELLOW + "Es werden nur die ersten " + availableSlots
                    + " Plugins angezeigt (" + plugins.size() + " insgesamt).");
        }
    }

    private void openPluginDetails(Player player, ManagedPlugin plugin) {
        Inventory inventory = Bukkit.createInventory(null, DETAIL_INVENTORY_SIZE,
                ChatColor.DARK_PURPLE + Objects.requireNonNullElse(plugin.getName(), "Plugin"));

        ItemStack filler = createFiller();
        for (int i = 0; i < DETAIL_INVENTORY_SIZE; i++) {
            inventory.setItem(i, filler.clone());
        }

        inventory.setItem(DETAIL_STATUS_SLOT, createPluginItem(plugin));
        inventory.setItem(DETAIL_ENABLE_SLOT, createEnableItem(plugin));
        inventory.setItem(DETAIL_LOAD_SLOT, createLoadItem(plugin));
        inventory.setItem(DETAIL_LINK_SLOT, createLinkItem(plugin));
        inventory.setItem(DETAIL_DISABLE_SLOT, createDisableUpdatesItem(plugin));
        inventory.setItem(DETAIL_UPDATE_SLOT, createUpdateSettingsItem(plugin));
        inventory.setItem(DETAIL_RENAME_SLOT, createRenameItem(plugin));
        createQuickRenameItem(plugin).ifPresent(item ->
                inventory.setItem(DETAIL_QUICK_RENAME_SLOT, item));
        inventory.setItem(DETAIL_REMOVE_SLOT, createRemoveItem(plugin));
        inventory.setItem(DETAIL_BACK_SLOT, createBackItem());

        openInventories.put(player.getUniqueId(), InventorySession.detail(inventory, plugin));
        player.openInventory(inventory);
    }

    private ItemStack createPluginItem(ManagedPlugin plugin) {
        ItemStack item = new ItemStack(Material.ENCHANTED_BOOK);
        ItemMeta meta = item.getItemMeta();
        if (meta != null) {
            meta.setDisplayName(ChatColor.GOLD + plugin.getName());

            List<String> lore = new ArrayList<>();
            lore.add(ChatColor.GRAY + "Status: " + statusLabel(plugin));
            plugin.getPlugin().ifPresentOrElse(loaded -> {
                String version = loaded.getDescription().getVersion();
                if (version != null && !version.isBlank()) {
                    lore.add(ChatColor.GRAY + "Version: " + ChatColor.AQUA + version);
                }
                lore.add(ChatColor.GRAY + "Aktiviert: "
                        + (loaded.isEnabled() ? ChatColor.GREEN + "Ja" : ChatColor.RED + "Nein"));
            }, () -> lore.add(ChatColor.RED + "Nicht geladen"));

            Path path = plugin.getPath();
            if (path != null) {
                lore.add(ChatColor.DARK_GRAY + "Datei: " + ChatColor.WHITE + path.getFileName());
                findMatchingSource(plugin).ifPresentOrElse(source -> {
                    lore.add(ChatColor.GRAY + "Update-Quelle: " + ChatColor.AQUA + source.getName());
                    lore.add(ChatColor.YELLOW + "Klicke, um den Link zu aktualisieren.");
            }, () -> lore.add(ChatColor.RED + "Keine Update-Quelle verknüpft – klicken, um Link zu setzen."));
        } else {
            lore.add(ChatColor.RED + "Kein JAR-Pfad gefunden – Link nicht möglich.");
        }

        PluginUpdateSettings settings = readSettings(plugin);
        if (!settings.autoUpdateEnabled()) {
            lore.add(ChatColor.GRAY + "Updates: " + ChatColor.RED + "Automatisch deaktiviert");
        } else if (settings.behaviour() == UpdateBehaviour.AUTO_RELOAD) {
            lore.add(ChatColor.GRAY + "Updates: " + ChatColor.GREEN + "Automatisch neu laden");
        } else {
            lore.add(ChatColor.GRAY + "Updates: " + ChatColor.GOLD + "Serverneustart erforderlich");
        }

        meta.setLore(lore);
        item.setItemMeta(meta);
    }
    return item;
}

    private String statusLabel(ManagedPlugin plugin) {
        if (plugin.isEnabled()) {
            return ChatColor.GREEN + "Aktiv";
        }
        if (plugin.isLoaded()) {
            return ChatColor.YELLOW + "Geladen";
        }
        return ChatColor.RED + "Nicht geladen";
    }

    private ItemStack createFiller() {
        ItemStack item = new ItemStack(Material.GRAY_STAINED_GLASS_PANE);
        ItemMeta meta = item.getItemMeta();
        if (meta != null) {
            meta.setDisplayName(" ");
            item.setItemMeta(meta);
        }
        return item;
    }

    private PluginUpdateSettings readSettings(ManagedPlugin plugin) {
        if (updateSettingsRepository == null) {
            return PluginUpdateSettings.defaultSettings();
        }
        String name = plugin.getName();
        if (name == null || name.isBlank()) {
            return PluginUpdateSettings.defaultSettings();
        }
        return updateSettingsRepository.getSettings(name);
    }

    private ItemStack createEnableItem(ManagedPlugin plugin) {
        ItemStack item = new ItemStack(plugin.isEnabled() ? Material.RED_DYE : Material.LIME_DYE);
        ItemMeta meta = item.getItemMeta();
        if (meta != null) {
            if (!plugin.isLoaded()) {
                meta.setDisplayName(ChatColor.DARK_GRAY + "Aktivieren (nicht möglich)");
                meta.setLore(List.of(
                        ChatColor.GRAY + "Das Plugin ist derzeit nicht geladen.",
                        ChatColor.GRAY + "Lade es, um es zu aktivieren."
                ));
            } else if (plugin.isEnabled()) {
                meta.setDisplayName(ChatColor.RED + "Plugin deaktivieren");
                meta.setLore(List.of(
                        ChatColor.GRAY + "Stoppt das Plugin, ohne es zu entladen."
                ));
            } else {
                meta.setDisplayName(ChatColor.GREEN + "Plugin aktivieren");
                meta.setLore(List.of(
                        ChatColor.GRAY + "Startet das Plugin nach dem Laden."
                ));
            }
            item.setItemMeta(meta);
        }
        return item;
    }

    private ItemStack createLoadItem(ManagedPlugin plugin) {
        boolean loaded = plugin.isLoaded();
        ItemStack item = new ItemStack(loaded ? Material.BARRIER : Material.EMERALD_BLOCK);
        ItemMeta meta = item.getItemMeta();
        if (meta != null) {
            if (loaded) {
                meta.setDisplayName(ChatColor.GOLD + "Plugin entladen");
                meta.setLore(List.of(
                        ChatColor.GRAY + "Entlädt das Plugin vollständig.",
                        ChatColor.GRAY + "Befreit Ressourcen und entfernt Listener."
                ));
            } else {
                meta.setDisplayName(ChatColor.AQUA + "Plugin laden");
                meta.setLore(List.of(
                        ChatColor.GRAY + "Lädt das Plugin aus der JAR-Datei und aktiviert es."
                ));
            }
            item.setItemMeta(meta);
        }
        return item;
    }

    private ItemStack createLinkItem(ManagedPlugin plugin) {
        ItemStack item = new ItemStack(Material.PAPER);
        ItemMeta meta = item.getItemMeta();
        if (meta != null) {
            meta.setDisplayName(ChatColor.AQUA + "Update-Link festlegen");
            meta.setLore(List.of(
                    ChatColor.GRAY + "Klicke, um eine neue Download-URL einzugeben.",
                    ChatColor.GRAY + "Eigene Links ermöglichen Installation und Updates."
            ));
            item.setItemMeta(meta);
        }
        return item;
    }

    private ItemStack createDisableUpdatesItem(ManagedPlugin plugin) {
        Optional<UpdateSource> source = findMatchingSource(plugin);
        PluginUpdateSettings settings = readSettings(plugin);

        Material material = source.isPresent() ? Material.BARRIER : Material.LIME_DYE;
        ItemStack item = new ItemStack(material);
        ItemMeta meta = item.getItemMeta();
        if (meta != null) {
            if (source.isPresent()) {
                meta.setDisplayName(ChatColor.RED + "Automatische Updates deaktivieren");
                List<String> lore = new ArrayList<>();
                lore.add(ChatColor.GRAY + "Entfernt die Update-Quelle aus NU2L.");
                lore.add(ChatColor.GRAY + "Das Plugin bleibt installiert.");
                String sourceName = source.get().getName();
                if (sourceName != null && !sourceName.isBlank()) {
                    lore.add(ChatColor.DARK_GRAY + "Quelle: " + ChatColor.AQUA + sourceName);
                }
                lore.add(ChatColor.YELLOW + "Klicken, um Updates zu deaktivieren.");
                meta.setLore(lore);
            } else {
                meta.setDisplayName(ChatColor.GREEN + "Keine Update-Verknüpfung aktiv");
                List<String> lore = new ArrayList<>();
                if (!settings.autoUpdateEnabled()) {
                    lore.add(ChatColor.GRAY + "Automatische Updates sind deaktiviert.");
                } else {
                    lore.add(ChatColor.GRAY + "Dieses Plugin wird aktuell nicht automatisch aktualisiert.");
                }
                lore.add(ChatColor.GRAY + "Nutze \"Update-Link festlegen\", um Updates zu aktivieren.");
                meta.setLore(lore);
            }
            item.setItemMeta(meta);
        }
        return item;
    }

    private ItemStack createUpdateSettingsItem(ManagedPlugin plugin) {
        PluginUpdateSettings settings = readSettings(plugin);
        ItemStack item = new ItemStack(Material.COMPARATOR);
        ItemMeta meta = item.getItemMeta();
        if (meta != null) {
            meta.setDisplayName(ChatColor.LIGHT_PURPLE + "Update-Verhalten anpassen");
            String current;
            if (!settings.autoUpdateEnabled()) {
                current = ChatColor.RED + "Automatische Updates deaktiviert";
            } else if (settings.behaviour() == UpdateBehaviour.AUTO_RELOAD) {
                current = ChatColor.GREEN + "Automatisch neu laden";
            } else {
                current = ChatColor.GOLD + "Serverneustart nach Updates";
            }
            meta.setLore(List.of(
                    ChatColor.GRAY + "Aktueller Modus:",
                    ChatColor.GRAY + " → " + current,
                    ChatColor.GRAY + "Dateiname: "
                            + (settings.retainUpstreamFilename()
                            ? ChatColor.GREEN + "Upstream"
                            : ChatColor.YELLOW + "Konfiguriert"),
                    ChatColor.YELLOW + "Links/Rechts Klick für Optionen"
            ));
            item.setItemMeta(meta);
        }
        return item;
    }

    private ItemStack createRemoveItem(ManagedPlugin plugin) {
        ItemStack item = new ItemStack(Material.TNT);
        ItemMeta meta = item.getItemMeta();
        if (meta != null) {
            meta.setDisplayName(ChatColor.RED + "Plugin entfernen");
            meta.setLore(List.of(
                    ChatColor.GRAY + "Entlädt das Plugin, löscht die Datei",
                    ChatColor.GRAY + "und entfernt die Update-Quelle."
            ));
            item.setItemMeta(meta);
        }
        return item;
    }

    private ItemStack createRenameItem(ManagedPlugin plugin) {
        ItemStack item = new ItemStack(Material.NAME_TAG);
        ItemMeta meta = item.getItemMeta();
        if (meta != null) {
            meta.setDisplayName(ChatColor.AQUA + "Datei umbenennen");
            meta.setLore(List.of(
                    ChatColor.GRAY + "Ändert den Namen der JAR-Datei",
                    ChatColor.GRAY + "und aktualisiert die Update-Quelle."
            ));
            item.setItemMeta(meta);
        }
        return item;
    }

    private Optional<ItemStack> createQuickRenameItem(ManagedPlugin plugin) {
        Path path = plugin.getPath();
        if (path == null) {
            return Optional.empty();
        }

        Optional<Plugin> loadedPlugin = plugin.getPlugin();
        if (loadedPlugin.isEmpty()) {
            return Optional.empty();
        }

        File dataFolder = loadedPlugin.get().getDataFolder();
        if (dataFolder == null) {
            return Optional.empty();
        }

        String sanitized = FileNameSanitizer.sanitizeJarFilename(dataFolder.getName());
        if (sanitized == null) {
            return Optional.empty();
        }

        String current = path.getFileName().toString();
        if (sanitized.equals(current)) {
            return Optional.empty();
        }

        ItemStack item = new ItemStack(Material.CHEST);
        ItemMeta meta = item.getItemMeta();
        if (meta != null) {
            meta.setDisplayName(ChatColor.GREEN + "Mit Datenordner angleichen");
            meta.setLore(List.of(
                    ChatColor.GRAY + "Benennt die Datei automatisch um,",
                    ChatColor.GRAY + "damit sie wie der Datenordner heißt.",
                    " ",
                    ChatColor.DARK_GRAY + "Ziel: " + ChatColor.AQUA + sanitized
            ));
            item.setItemMeta(meta);
        }
        return Optional.of(item);
    }

    private ItemStack createBackItem() {
        ItemStack item = new ItemStack(Material.ARROW);
        ItemMeta meta = item.getItemMeta();
        if (meta != null) {
            meta.setDisplayName(ChatColor.YELLOW + "Zurück zur Übersicht");
            meta.setLore(List.of(ChatColor.GRAY + "Schließt diese Ansicht und zeigt alle Plugins."));
            item.setItemMeta(meta);
        }
        return item;
    }

    private ItemStack createInstallButton() {
        ItemStack item = new ItemStack(Material.NETHER_STAR);
        ItemMeta meta = item.getItemMeta();
        if (meta != null) {
            meta.setDisplayName(ChatColor.AQUA + "Neues Plugin installieren …");
            meta.setLore(List.of(
                    ChatColor.GRAY + "Klicke, um eine Download-URL einzugeben.",
                    ChatColor.GRAY + "Die Installation läuft direkt über NU2L."
            ));
            item.setItemMeta(meta);
        }
        return item;
    }

    private void toggleEnable(Player player, ManagedPlugin plugin) {
        if (!checkPermission(player, Permissions.GUI_MANAGE_LIFECYCLE)) {
            return;
        }
        PluginLifecycleManager manager = context.getPluginLifecycleManager();
        if (manager == null) {
            player.sendMessage(ChatColor.RED + "Die Plugin-Verwaltung ist deaktiviert.");
            return;
        }

        String pluginName = plugin.getName();
        if (pluginName == null) {
            player.sendMessage(ChatColor.RED + "Pluginname konnte nicht ermittelt werden.");
            return;
        }

        try {
            if (!plugin.isLoaded()) {
                player.sendMessage(ChatColor.RED + "Bitte lade das Plugin, bevor du es aktivierst.");
            } else if (plugin.isEnabled()) {
                if (manager.disablePlugin(pluginName)) {
                    player.sendMessage(ChatColor.YELLOW + "Plugin " + ChatColor.AQUA + pluginName
                            + ChatColor.YELLOW + " wurde deaktiviert.");
                } else {
                    player.sendMessage(ChatColor.RED + "Plugin konnte nicht deaktiviert werden.");
                }
            } else {
                if (manager.enablePlugin(pluginName)) {
                    player.sendMessage(ChatColor.GREEN + "Plugin " + ChatColor.AQUA + pluginName
                            + ChatColor.GREEN + " wurde aktiviert.");
                } else {
                    player.sendMessage(ChatColor.RED + "Plugin konnte nicht aktiviert werden.");
                }
            }
        } catch (PluginLifecycleException ex) {
            context.getPlugin().getLogger().log(Level.WARNING,
                    "Failed to toggle plugin " + pluginName, ex);
            player.sendMessage(ChatColor.RED + "Aktion fehlgeschlagen: " + ex.getMessage());
        }

        openPluginDetails(player, plugin);
    }

    private void toggleLoad(Player player, ManagedPlugin plugin) {
        if (!checkPermission(player, Permissions.GUI_MANAGE_LIFECYCLE)) {
            return;
        }
        PluginLifecycleManager manager = context.getPluginLifecycleManager();
        if (manager == null) {
            player.sendMessage(ChatColor.RED + "Die Plugin-Verwaltung ist deaktiviert.");
            return;
        }

        String pluginName = plugin.getName();
        if (pluginName == null) {
            player.sendMessage(ChatColor.RED + "Pluginname konnte nicht ermittelt werden.");
            return;
        }

        try {
            if (plugin.isLoaded()) {
                if (manager.unloadPlugin(pluginName)) {
                    player.sendMessage(ChatColor.YELLOW + "Plugin " + ChatColor.AQUA + pluginName
                            + ChatColor.YELLOW + " wurde entladen.");
                } else {
                    player.sendMessage(ChatColor.RED + "Plugin konnte nicht entladen werden.");
                }
            } else {
                Path path = plugin.getPath();
                if (path == null) {
                    player.sendMessage(ChatColor.RED + "Es ist kein JAR-Pfad für dieses Plugin bekannt.");
                    return;
                }
                if (manager.loadPlugin(path)) {
                    player.sendMessage(ChatColor.GREEN + "Plugin " + ChatColor.AQUA + pluginName
                            + ChatColor.GREEN + " wurde geladen und aktiviert.");
                } else {
                    player.sendMessage(ChatColor.RED + "Plugin konnte nicht geladen werden.");
                }
            }
        } catch (PluginLifecycleException ex) {
            context.getPlugin().getLogger().log(Level.WARNING,
                    "Failed to toggle plugin load state for " + pluginName, ex);
            player.sendMessage(ChatColor.RED + "Aktion fehlgeschlagen: " + ex.getMessage());
        }

        openPluginDetails(player, plugin);
    }

    private void cycleUpdateSettings(Player player, ManagedPlugin plugin) {
        cycleUpdateBehaviour(player, plugin);
    }

    private void cycleUpdateBehaviour(Player player, ManagedPlugin plugin) {
        if (!checkPermission(player, Permissions.GUI_MANAGE_SETTINGS)) {
            return;
        }
        if (updateSettingsRepository == null) {
            player.sendMessage(ChatColor.RED + "Update-Einstellungen können derzeit nicht gespeichert werden.");
            return;
        }

        String pluginName = plugin.getName();
        if (pluginName == null || pluginName.isBlank()) {
            player.sendMessage(ChatColor.RED + "Pluginname konnte nicht ermittelt werden.");
            return;
        }

        PluginUpdateSettings current = readSettings(plugin);
        PluginUpdateSettings next;
        ChatColor color;
        String message;

        if (!current.autoUpdateEnabled()) {
            next = new PluginUpdateSettings(true, UpdateBehaviour.AUTO_RELOAD, current.retainUpstreamFilename());
            color = ChatColor.GREEN;
            message = "Automatische Updates aktiviert. Das Plugin wird nach Updates neu geladen.";
        } else if (current.behaviour() == UpdateBehaviour.AUTO_RELOAD) {
            next = new PluginUpdateSettings(true, UpdateBehaviour.REQUIRE_RESTART, current.retainUpstreamFilename());
            color = ChatColor.GOLD;
            message = "Automatische Updates erfordern nun einen Serverneustart.";
        } else {
            next = new PluginUpdateSettings(false, UpdateBehaviour.REQUIRE_RESTART, current.retainUpstreamFilename());
            color = ChatColor.RED;
            message = "Automatische Updates für dieses Plugin wurden deaktiviert.";
        }

        updateSettingsRepository.saveSettings(pluginName, next);
        player.sendMessage(color + message);
        openPluginDetails(player, plugin);
    }

    private void toggleFilenameRetention(Player player, ManagedPlugin plugin) {
        if (!checkPermission(player, Permissions.GUI_MANAGE_SETTINGS)) {
            return;
        }

        if (updateSettingsRepository == null) {
            player.sendMessage(ChatColor.RED + "Update-Einstellungen können derzeit nicht gespeichert werden.");
            return;
        }

        String pluginName = plugin.getName();
        if (pluginName == null || pluginName.isBlank()) {
            player.sendMessage(ChatColor.RED + "Pluginname konnte nicht ermittelt werden.");
            return;
        }

        PluginUpdateSettings current = readSettings(plugin);
        boolean retain = !current.retainUpstreamFilename();
        updateSettingsRepository.saveSettings(pluginName,
                new PluginUpdateSettings(current.autoUpdateEnabled(), current.behaviour(), retain));

        if (retain) {
            player.sendMessage(ChatColor.GREEN + "Upstream-Dateinamen werden künftig beibehalten (alte Dateien werden gelöscht).");
        } else {
            player.sendMessage(ChatColor.YELLOW + "NU2L verwendet wieder feste Dateinamen aus der Konfiguration.");
        }

        openPluginDetails(player, plugin);
    }

    private void beginStandaloneInstall(Player player) {
        if (!checkPermission(player, Permissions.INSTALL)) {
            return;
        }
        player.closeInventory();
        openInventories.remove(player.getUniqueId());
        pendingLinkRequests.put(player.getUniqueId(), new LinkRequest(null, true));
        player.sendMessage(ChatColor.AQUA + "Bitte gib die Download-URL des neuen Plugins im Chat ein oder tippe 'abbrechen'.");
    }

    @EventHandler
    public void onInventoryClick(InventoryClickEvent event) {
        if (!(event.getWhoClicked() instanceof Player player)) {
            return;
        }

        InventorySession session = openInventories.get(player.getUniqueId());
        if (session == null) {
            return;
        }

        if (!event.getView().getTopInventory().equals(session.inventory())) {
            return;
        }

        if (event.getRawSlot() >= session.inventory().getSize()) {
            event.setCancelled(true);
            return;
        }

        event.setCancelled(true);

        if (session.view() == View.OVERVIEW) {
            int installSlot = session.inventory().getSize() - 1;
            if (event.getRawSlot() == installSlot) {
                beginStandaloneInstall(player);
                return;
            }

            ManagedPlugin plugin = session.plugins().get(event.getRawSlot());
            if (plugin == null) {
                return;
            }

            openPluginDetails(player, plugin);
            return;
        }

        ManagedPlugin plugin = session.plugin();
        if (plugin == null) {
            return;
        }

        if (session.view() == View.LINK_SUGGESTIONS) {
            int manualSlot = session.inventory().getSize() - 2;
            int backSlot = session.inventory().getSize() - 1;
            int clicked = event.getRawSlot();
            if (clicked == backSlot) {
                openPluginDetails(player, plugin);
                return;
            }
            if (clicked == manualSlot) {
                player.closeInventory();
                promptManualLinkInput(player, plugin, Optional.empty());
                return;
            }
            PluginLinkSuggestion suggestion = session.suggestions().get(clicked);
            if (suggestion != null) {
                applySuggestion(player, plugin, suggestion);
            }
            return;
        }

        int slot = event.getRawSlot();
        if (slot == DETAIL_BACK_SLOT) {
            openOverview(player);
            return;
        }
        if (slot == DETAIL_ENABLE_SLOT) {
            toggleEnable(player, plugin);
            return;
        }
        if (slot == DETAIL_LOAD_SLOT) {
            toggleLoad(player, plugin);
            return;
        }
        if (slot == DETAIL_LINK_SLOT) {
            beginLinking(player, plugin);
            return;
        }
        if (slot == DETAIL_DISABLE_SLOT) {
            disableAutoUpdates(player, plugin);
            return;
        }
        if (slot == DETAIL_UPDATE_SLOT) {
            if (event.isRightClick()) {
                toggleFilenameRetention(player, plugin);
            } else {
                cycleUpdateBehaviour(player, plugin);
            }
            return;
        }
        if (slot == DETAIL_RENAME_SLOT) {
            beginRename(player, plugin);
            return;
        }
        if (slot == DETAIL_QUICK_RENAME_SLOT) {
            ItemStack clickedItem = event.getCurrentItem();
            if (clickedItem != null && clickedItem.getType() == Material.CHEST) {
                quickRenameToDataDirectory(player, plugin);
            }
            return;
        }
        if (slot == DETAIL_REMOVE_SLOT) {
            confirmRemoval(player, plugin);
        }
    }

    @EventHandler
    public void onInventoryClose(InventoryCloseEvent event) {
        UUID playerId = event.getPlayer().getUniqueId();
        InventorySession session = openInventories.get(playerId);
        if (session != null && event.getInventory().equals(session.inventory())) {
            openInventories.remove(playerId);
        }
    }

    @EventHandler
    public void onPlayerQuit(PlayerQuitEvent event) {
        UUID playerId = event.getPlayer().getUniqueId();
        openInventories.remove(playerId);
        pendingLinkRequests.remove(playerId);
        pendingRemovalRequests.remove(playerId);
        pendingSuggestionRequests.remove(playerId);
    }

    @EventHandler
    public void onPlayerChat(AsyncPlayerChatEvent event) {
        Player player = event.getPlayer();
        UUID playerId = player.getUniqueId();
        ManagedPlugin pendingRemoval = pendingRemovalRequests.get(playerId);
        if (pendingRemoval != null) {
            event.setCancelled(true);
            context.getScheduler().runTask(context.getPlugin(),
                    () -> handleRemovalInput(player, pendingRemoval, event.getMessage()));
            return;
        }
        LinkRequest request = pendingLinkRequests.get(playerId);
        if (request == null) {
            return;
        }

        event.setCancelled(true);
        String message = event.getMessage() != null ? event.getMessage().trim() : "";
        context.getScheduler().runTask(context.getPlugin(),
                () -> handleChatInput(player, message, request));
    }

    private void handleChatInput(Player player, String message, LinkRequest request) {
        UUID playerId = player.getUniqueId();
        if (pendingLinkRequests.get(playerId) != request) {
            return;
        }

        if (message.isBlank()) {
            player.sendMessage(ChatColor.RED + "Bitte gib einen gültigen Link ein oder tippe 'abbrechen'.");
            return;
        }

        if (message.equalsIgnoreCase("abbrechen") || message.equalsIgnoreCase("cancel")) {
            pendingLinkRequests.remove(playerId);
            player.sendMessage(ChatColor.YELLOW + "Verknüpfung abgebrochen.");
            return;
        }

        pendingLinkRequests.remove(playerId);
        if (request.standalone()) {
            player.sendMessage(ChatColor.GREEN + "Starte Installation eines neuen Plugins …");
            coordinator.install(player, message);
            return;
        }

        String pluginName = request.pluginName();
        player.sendMessage(ChatColor.GREEN + "Verarbeite Link für " + ChatColor.AQUA + pluginName + ChatColor.GREEN + " …");
        coordinator.installForPlugin(player, pluginName, message);
    }

    private void beginLinking(Player player, ManagedPlugin plugin) {
        if (!checkPermission(player, Permissions.GUI_MANAGE_LINK)) {
            return;
        }
        UUID playerId = player.getUniqueId();
        player.closeInventory();
        openInventories.remove(playerId);
        pendingSuggestionRequests.remove(playerId);

        Path path = plugin.getPath();
        if (path == null) {
            player.sendMessage(ChatColor.RED + "Für dieses Plugin konnte kein JAR gefunden werden.");
            return;
        }

        Optional<UpdateSource> existingSource = findMatchingSource(plugin);
        if (existingSource.isPresent()) {
            promptManualLinkInput(player, plugin, existingSource);
            return;
        }

        List<String> searchTerms = buildSearchTerms(plugin);
        if (searchTerms.isEmpty()) {
            promptManualLinkInput(player, plugin, Optional.empty());
            return;
        }

        pendingSuggestionRequests.put(playerId, plugin);
        player.sendMessage(ChatColor.GRAY + "Suche nach passenden Quellen …");

        context.getScheduler().runTaskAsynchronously(context.getPlugin(), () -> {
            List<PluginLinkSuggestion> suggestions;
            try {
                suggestions = linkSuggester.suggest(searchTerms);
            } catch (Exception e) {
                context.getPlugin().getLogger().log(Level.FINE,
                        "Failed to resolve link suggestions for " + plugin.getName(), e);
                suggestions = List.of();
            }
            List<PluginLinkSuggestion> finalSuggestions = suggestions;
            context.getScheduler().runTask(context.getPlugin(), () ->
                    handleSuggestionResults(player, plugin, finalSuggestions));
        });
    }

    private void handleSuggestionResults(Player player,
                                         ManagedPlugin plugin,
                                         List<PluginLinkSuggestion> suggestions) {
        UUID playerId = player.getUniqueId();
        ManagedPlugin expected = pendingSuggestionRequests.get(playerId);
        if (expected == null || expected != plugin) {
            return;
        }
        pendingSuggestionRequests.remove(playerId);

        if (!player.isOnline()) {
            return;
        }

        if (suggestions == null || suggestions.isEmpty()) {
            player.sendMessage(ChatColor.YELLOW + "Keine passenden Quellen gefunden. Bitte gib den Link manuell ein.");
            promptManualLinkInput(player, plugin, Optional.empty());
            return;
        }

        player.sendMessage(ChatColor.GREEN + "Es wurden " + suggestions.size()
                + " mögliche Update-Quellen gefunden.");
        player.sendMessage(ChatColor.GRAY + "Wähle einen Vorschlag aus oder trage einen Link manuell ein.");
        openLinkSuggestions(player, plugin, suggestions);
    }

    private void openLinkSuggestions(Player player,
                                      ManagedPlugin plugin,
                                      List<PluginLinkSuggestion> suggestions) {
        String pluginName = Objects.requireNonNullElse(plugin.getName(), "Plugin");
        List<PluginLinkSuggestion> entries = suggestions == null ? List.of() : suggestions;
        int baseCount = Math.max(0, entries.size()) + 2;
        int size = Math.max(9, ((baseCount + 8) / 9) * 9);
        size = Math.min(size, MAX_SIZE);

        Inventory inventory = Bukkit.createInventory(null, size,
                ChatColor.DARK_PURPLE + pluginName + " – Update-Quellen");

        ItemStack filler = createFiller();
        for (int i = 0; i < size; i++) {
            inventory.setItem(i, filler.clone());
        }

        Map<Integer, PluginLinkSuggestion> mapping = new HashMap<>();
        int maxSuggestions = Math.max(0, size - 2);
        for (int i = 0; i < maxSuggestions && i < entries.size(); i++) {
            PluginLinkSuggestion suggestion = entries.get(i);
            inventory.setItem(i, createSuggestionItem(suggestion));
            mapping.put(i, suggestion);
        }

        int manualSlot = size - 2;
        inventory.setItem(manualSlot, createManualLinkOptionItem());
        inventory.setItem(size - 1, createSuggestionBackItem());

        openInventories.put(player.getUniqueId(), InventorySession.suggestions(inventory, plugin, mapping));
        player.openInventory(inventory);
    }

    private void applySuggestion(Player player, ManagedPlugin plugin, PluginLinkSuggestion suggestion) {
        String pluginName = Objects.requireNonNullElse(plugin.getName(),
                plugin.getPath() != null ? plugin.getPath().getFileName().toString() : "Plugin");
        player.closeInventory();
        openInventories.remove(player.getUniqueId());
        pendingLinkRequests.remove(player.getUniqueId());
        player.sendMessage(ChatColor.GREEN + "Übernehme Vorschlag " + ChatColor.AQUA
                + suggestion.provider() + ChatColor.GREEN + " für " + ChatColor.AQUA + pluginName + ChatColor.GREEN + " …");
        coordinator.installForPlugin(player, pluginName, suggestion.url());
    }

    private void promptManualLinkInput(Player player,
                                       ManagedPlugin plugin,
                                       Optional<UpdateSource> existingSource) {
        UUID playerId = player.getUniqueId();
        pendingSuggestionRequests.remove(playerId);
        String pluginName = Objects.requireNonNullElse(plugin.getName(),
                plugin.getPath() != null ? plugin.getPath().getFileName().toString() : "Plugin");
        pendingLinkRequests.put(playerId, new LinkRequest(pluginName, false));

        player.sendMessage(ChatColor.AQUA + "Update-Link für " + pluginName + " festlegen.");
        existingSource.ifPresentOrElse(source ->
                        player.sendMessage(ChatColor.GRAY + "Aktuelle Quelle: " + ChatColor.AQUA + source.getName()),
                () -> player.sendMessage(ChatColor.GRAY + "Aktuell ist keine Quelle verknüpft."));
        player.sendMessage(ChatColor.YELLOW + "Bitte gib die Download-URL im Chat ein oder tippe 'abbrechen'.");
    }

    private List<String> buildSearchTerms(ManagedPlugin plugin) {
        Set<String> terms = new LinkedHashSet<>();

        String pluginName = plugin.getName();
        if (pluginName != null) {
            terms.add(pluginName);
        }

        plugin.getPlugin().ifPresent(loaded -> {
            String descriptionName = loaded.getDescription().getName();
            if (descriptionName != null) {
                terms.add(descriptionName);
            }
            String fullName = loaded.getDescription().getFullName();
            if (fullName != null) {
                terms.add(fullName);
            }
        });

        Path path = plugin.getPath();
        if (path != null) {
            String baseName = stripJarExtension(path.getFileName().toString());
            if (baseName != null && !baseName.isBlank()) {
                terms.add(baseName);
                terms.add(baseName.replace('_', ' '));
                int dash = baseName.indexOf('-');
                if (dash > 0) {
                    terms.add(baseName.substring(0, dash));
                }
                int underscore = baseName.indexOf('_');
                if (underscore > 0) {
                    terms.add(baseName.substring(0, underscore));
                }
            }
        }

        return terms.stream()
                .map(String::trim)
                .filter(term -> !term.isEmpty())
                .limit(6)
                .toList();
    }

    private ItemStack createSuggestionItem(PluginLinkSuggestion suggestion) {
        ItemStack item = new ItemStack(Material.WRITABLE_BOOK);
        ItemMeta meta = item.getItemMeta();
        if (meta != null) {
            String title = Objects.requireNonNullElse(suggestion.title(), suggestion.provider());
            meta.setDisplayName(ChatColor.AQUA + title + ChatColor.DARK_GRAY + " (" + suggestion.provider() + ")");
            List<String> lore = new ArrayList<>();
            lore.add(ChatColor.GRAY + suggestion.provider() + " Vorschlag");
            for (String line : wrapText(suggestion.description(), 40)) {
                lore.add(ChatColor.DARK_GRAY + line);
            }
            if (!suggestion.highlights().isEmpty()) {
                lore.add(" ");
                for (String highlight : suggestion.highlights()) {
                    lore.add(ChatColor.GRAY + "• " + ChatColor.YELLOW + highlight);
                }
            }
            lore.add(" ");
            lore.add(ChatColor.DARK_GRAY + suggestion.url());
            lore.add(ChatColor.GREEN + "Klicken, um diesen Link zu übernehmen.");
            meta.setLore(lore);
            item.setItemMeta(meta);
        }
        return item;
    }

    private ItemStack createManualLinkOptionItem() {
        ItemStack item = new ItemStack(Material.OAK_SIGN);
        ItemMeta meta = item.getItemMeta();
        if (meta != null) {
            meta.setDisplayName(ChatColor.AQUA + "Link manuell eintragen");
            meta.setLore(List.of(
                    ChatColor.GRAY + "Öffnet die Chat-Eingabe,",
                    ChatColor.GRAY + "um eine URL selbst einzutragen."
            ));
            item.setItemMeta(meta);
        }
        return item;
    }

    private ItemStack createSuggestionBackItem() {
        ItemStack item = new ItemStack(Material.ARROW);
        ItemMeta meta = item.getItemMeta();
        if (meta != null) {
            meta.setDisplayName(ChatColor.YELLOW + "Zurück zu den Plugin-Details");
            meta.setLore(List.of(ChatColor.GRAY + "Kehrt zu den Details des Plugins zurück."));
            item.setItemMeta(meta);
        }
        return item;
    }

    private List<String> wrapText(String text, int maxLength) {
        if (text == null) {
            return List.of();
        }
        String trimmed = text.trim();
        if (trimmed.isEmpty() || maxLength <= 0) {
            return List.of();
        }
        List<String> lines = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        for (String word : trimmed.split("\\s+")) {
            if (current.length() > 0 && current.length() + word.length() + 1 > maxLength) {
                lines.add(current.toString());
                current.setLength(0);
            }
            if (current.length() > 0) {
                current.append(' ');
            }
            current.append(word);
        }
        if (current.length() > 0) {
            lines.add(current.toString());
        }
        return lines;
    }

    private void confirmRemoval(Player player, ManagedPlugin plugin) {
        if (!checkPermission(player, Permissions.GUI_MANAGE_REMOVE)) {
            return;
        }
        pendingRemovalRequests.put(player.getUniqueId(), plugin);
        player.closeInventory();
        player.sendMessage(ChatColor.RED + "Bist du sicher, dass du "
                + ChatColor.AQUA + plugin.getName() + ChatColor.RED + " entfernen möchtest?");
        player.sendMessage(ChatColor.YELLOW + "Tippe \"ja\" zum Bestätigen oder \"abbrechen\" zum Abbrechen.");
    }

    private void handleRemovalInput(Player player, ManagedPlugin plugin, String message) {
        UUID playerId = player.getUniqueId();
        pendingRemovalRequests.remove(playerId);
        String trimmed = message != null ? message.trim() : "";
        if (trimmed.equalsIgnoreCase("ja") || trimmed.equalsIgnoreCase("yes")) {
            coordinator.removeManagedPlugin(player, plugin);
        } else {
            player.sendMessage(ChatColor.YELLOW + "Entfernen abgebrochen.");
        }
    }

    private void disableAutoUpdates(Player player, ManagedPlugin plugin) {
        if (!checkPermission(player, Permissions.GUI_MANAGE_LINK)) {
            return;
        }
        coordinator.disableAutomaticUpdates(player, plugin);
        openPluginDetails(player, plugin);
    }

    private void beginRename(Player player, ManagedPlugin plugin) {
        if (!checkPermission(player, Permissions.GUI_MANAGE_RENAME)) {
            return;
        }
        Path path = plugin.getPath();
        if (path == null) {
            player.sendMessage(ChatColor.RED + "Für dieses Plugin konnte kein Dateipfad ermittelt werden.");
            return;
        }

        String currentName = stripJarExtension(path.getFileName().toString());
        ItemStack paper = new ItemStack(Material.PAPER);
        ItemMeta meta = paper.getItemMeta();
        if (meta != null) {
            meta.setDisplayName(currentName);
            paper.setItemMeta(meta);
        }

        anvilTextPrompt.open(player, AnvilTextPrompt.Prompt.builder()
                .title(ChatColor.DARK_PURPLE + "NU2L Umbenennen")
                .initialText(currentName)
                .inputItem(paper)
                .previewFactory(this::createRenamePreview)
                .validation(value -> {
                    if (value == null || value.trim().isEmpty()) {
                        return Optional.of(ChatColor.RED + "Bitte gib einen gültigen Namen ein.");
                    }
                    return Optional.empty();
                })
                .onConfirm((p, value) -> {
                    requestRename(p, plugin, value);
                })
                .onCancel(p -> p.sendMessage(ChatColor.YELLOW + "Umbenennen abgebrochen."))
                .build());

        player.sendMessage(ChatColor.GRAY + "Gib einen neuen Dateinamen ein (\".jar\" wird automatisch ergänzt).");
    }

    private void requestRename(Player player, ManagedPlugin plugin, String requestedName) {
        String pluginName = plugin.getName();
        coordinator.renameManagedPlugin(player, plugin, requestedName);
        PluginLifecycleManager lifecycleManager = context.getPluginLifecycleManager();
        if (lifecycleManager != null && pluginName != null) {
            context.getScheduler().runTaskLater(context.getPlugin(), () ->
                    lifecycleManager.findByName(pluginName)
                            .ifPresent(updated -> openPluginDetails(player, updated)), 2L);
        }
    }

    private void quickRenameToDataDirectory(Player player, ManagedPlugin plugin) {
        if (!checkPermission(player, Permissions.GUI_MANAGE_RENAME)) {
            return;
        }

        Path path = plugin.getPath();
        if (path == null) {
            player.sendMessage(ChatColor.RED + "Für dieses Plugin konnte kein Dateipfad ermittelt werden.");
            return;
        }

        Optional<Plugin> loadedPlugin = plugin.getPlugin();
        if (loadedPlugin.isEmpty()) {
            player.sendMessage(ChatColor.RED + "Das Plugin muss geladen sein, um den Datenordnernamen zu ermitteln.");
            return;
        }

        File dataFolder = loadedPlugin.get().getDataFolder();
        if (dataFolder == null) {
            player.sendMessage(ChatColor.RED + "Es konnte kein Datenordner für dieses Plugin gefunden werden.");
            return;
        }

        String sanitized = FileNameSanitizer.sanitizeJarFilename(dataFolder.getName());
        if (sanitized == null) {
            player.sendMessage(ChatColor.RED + "Der Datenordnername ist ungültig.");
            return;
        }

        String current = path.getFileName().toString();
        if (sanitized.equals(current)) {
            player.sendMessage(ChatColor.GRAY + "Die Datei heißt bereits wie der Datenordner.");
            return;
        }

        requestRename(player, plugin, sanitized);
    }

    private String stripJarExtension(String value) {
        if (value == null) {
            return "";
        }
        if (value.toLowerCase(Locale.ROOT).endsWith(".jar")) {
            return value.substring(0, value.length() - 4);
        }
        return value;
    }

    private Optional<UpdateSource> findMatchingSource(ManagedPlugin plugin) {
        Path path = plugin.getPath();
        if (path == null) {
            return Optional.empty();
        }

        String fileName = path.getFileName().toString();
        return context.getUpdateSourceRegistry().getSources().stream()
                .filter(source -> source.getTargetDirectory() == TargetDirectory.PLUGINS)
                .filter(source -> fileName.equalsIgnoreCase(source.getFilename()))
                .findFirst();
    }

    private enum View {
        OVERVIEW,
        DETAIL,
        LINK_SUGGESTIONS
    }

    private record InventorySession(Inventory inventory,
                                    View view,
                                    Map<Integer, ManagedPlugin> plugins,
                                    ManagedPlugin plugin,
                                    Map<Integer, PluginLinkSuggestion> suggestions) {
        static InventorySession overview(Inventory inventory, Map<Integer, ManagedPlugin> plugins) {
            return new InventorySession(inventory, View.OVERVIEW, Map.copyOf(plugins), null, Map.of());
        }

        static InventorySession detail(Inventory inventory, ManagedPlugin plugin) {
            return new InventorySession(inventory, View.DETAIL, Map.of(), plugin, Map.of());
        }

        static InventorySession suggestions(Inventory inventory,
                                            ManagedPlugin plugin,
                                            Map<Integer, PluginLinkSuggestion> suggestions) {
            return new InventorySession(inventory, View.LINK_SUGGESTIONS, Map.of(), plugin, Map.copyOf(suggestions));
        }
    }

    private record LinkRequest(String pluginName, boolean standalone) {
    }

    private boolean checkPermission(Player player, String permission) {
        if (permission == null || permission.isBlank()) {
            return true;
        }
        if (player.hasPermission(permission) || player.hasPermission(Permissions.GUI_MANAGE)) {
            return true;
        }
        player.sendMessage(ChatColor.RED + "Dir fehlt die Berechtigung (" + permission + ").");
        return false;
    }

    private ItemStack createRenamePreview(String value) {
        String sanitized = FileNameSanitizer.sanitizeJarFilename(value);
        if (sanitized == null) {
            return null;
        }
        ItemStack result = new ItemStack(Material.PAPER);
        ItemMeta meta = result.getItemMeta();
        if (meta != null) {
            meta.setDisplayName(ChatColor.GREEN + sanitized);
            result.setItemMeta(meta);
        }
        return result;
    }
}
