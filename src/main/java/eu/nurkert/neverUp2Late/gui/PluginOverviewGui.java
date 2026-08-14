package eu.nurkert.neverUp2Late.gui;

import eu.nurkert.neverUp2Late.Permissions;
import eu.nurkert.neverUp2Late.command.QuickInstallCoordinator;
import eu.nurkert.neverUp2Late.core.PluginContext;
import eu.nurkert.neverUp2Late.gui.anvil.AnvilTextPrompt;
import eu.nurkert.neverUp2Late.persistence.PluginUpdateSettingsRepository;
import eu.nurkert.neverUp2Late.persistence.UpdateStateRepository;
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
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.event.player.AsyncPlayerChatEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.plugin.Plugin;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
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
import java.security.CodeSource;
import java.util.Locale;

/**
 * Creates a simple chest based GUI that lists all plugins currently managed by NU2L.
 */
public class PluginOverviewGui implements Listener {

    private static final int MAX_SIZE = 54;
    /** Bottom row reserved for controls, so no plugin ever shares a slot with a button. */
    private static final int CONTROL_ROW_SIZE = 9;
    private static final eu.nurkert.neverUp2Late.update.VersionComparator VERSION_COMPARATOR =
            new eu.nurkert.neverUp2Late.update.VersionComparator();
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
    private static final int DETAIL_CHECK_SLOT = 11;
    private static final int DETAIL_ROLLBACK_SLOT = 19;
    private static final int DETAIL_BACK_SLOT = 26;
    private static final int INSTALL_INVENTORY_SIZE = 27;
    private static final int INSTALL_INFO_SLOT = 18;
    private static final int INSTALL_SEARCH_SLOT = 20;
    private static final int INSTALL_MANUAL_SLOT = 22;
    private static final int INSTALL_BACK_SLOT = 26;

    private final PluginContext context;
    private final QuickInstallCoordinator coordinator;
    private final Map<UUID, InventorySession> openInventories = new ConcurrentHashMap<>();
    private final Map<UUID, LinkRequest> pendingLinkRequests = new ConcurrentHashMap<>();
    private final Map<UUID, ManagedPlugin> pendingRemovalRequests = new ConcurrentHashMap<>();
    private final Map<UUID, ManagedPlugin> pendingSuggestionRequests = new ConcurrentHashMap<>();
    private final Map<UUID, String> pendingInstallSearches = new ConcurrentHashMap<>();
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
            player.sendMessage(ChatColor.RED + "You do not have permission to open the NU2L GUI.");
            return;
        }
        if (context.getPluginLifecycleManager() == null) {
            player.sendMessage(ChatColor.RED + "Plugin management is disabled.");
            return;
        }
        openOverview(player);
    }

    public void openStandaloneSearch(Player player, String query) {
        Objects.requireNonNull(player, "player");

        if (!player.hasPermission(Permissions.GUI_OPEN)
                && !player.hasPermission(Permissions.GUI_MANAGE)) {
            player.sendMessage(ChatColor.RED + "You do not have permission to open the NU2L GUI.");
            return;
        }
        if (!checkPermission(player, Permissions.INSTALL)) {
            return;
        }

        String trimmed = query != null ? query.trim() : "";
        if (trimmed.length() < 3) {
            player.sendMessage(ChatColor.RED + "Please enter at least three characters.");
            return;
        }

        UUID playerId = player.getUniqueId();
        pendingInstallSearches.put(playerId, trimmed);
        player.sendMessage(ChatColor.GRAY + "Searching for " + ChatColor.AQUA + trimmed + ChatColor.GRAY + " …");

        context.getScheduler().runTaskAsynchronously(context.getPlugin(), () -> {
            List<PluginLinkSuggestion> suggestions;
            try {
                suggestions = linkSuggester.suggest(List.of(trimmed));
            } catch (Exception e) {
                context.getPlugin().getLogger().log(Level.FINE,
                        "Failed to resolve standalone search for " + trimmed, e);
                suggestions = List.of();
            }
            List<PluginLinkSuggestion> finalSuggestions = suggestions;
            context.getScheduler().runTask(context.getPlugin(),
                    () -> handleStandaloneSearchResult(player, trimmed, finalSuggestions));
        });
    }

    private void openOverview(Player player) {
        List<ManagedPlugin> plugins = context.getPluginLifecycleManager().getManagedPlugins()
                .stream()
                .sorted(Comparator.comparing(ManagedPlugin::getName, String.CASE_INSENSITIVE_ORDER))
                .toList();

        int requiredSlots = plugins.size() + CONTROL_ROW_SIZE;
        int size = Math.max(18, ((requiredSlots + 8) / 9) * 9);
        size = Math.min(size, MAX_SIZE);

        Inventory inventory = Bukkit.createInventory(null, size, ChatColor.DARK_PURPLE + "NU2L Plugins");

        ItemStack filler = createFiller();
        for (int i = 0; i < size; i++) {
            inventory.setItem(i, filler.clone());
        }

        Map<Integer, ManagedPlugin> slotMapping = new HashMap<>();
        // The bottom row belongs to the controls. Reserving only two slots let
        // the cleanup button overwrite the plugin that had just been placed
        // there, so at 8, 17, 26 ... plugins the last one vanished from the
        // screen while its slot still answered clicks - a click meant for a
        // plugin then triggered the bulk rename.
        int availableSlots = Math.max(0, size - CONTROL_ROW_SIZE);
        boolean truncated = plugins.size() > availableSlots;
        for (int slot = 0; slot < availableSlots && slot < plugins.size(); slot++) {
            ManagedPlugin plugin = plugins.get(slot);
            inventory.setItem(slot, createPluginItem(plugin));
            slotMapping.put(slot, plugin);
        }

        inventory.setItem(size - 3, createCheckAllButton());
        inventory.setItem(size - 2, createCleanupButton());
        inventory.setItem(size - 1, createInstallButton());

        openInventories.put(player.getUniqueId(), InventorySession.overview(inventory, slotMapping));
        player.openInventory(inventory);

        if (truncated) {
            player.sendMessage(ChatColor.YELLOW + "Only the first " + availableSlots
                    + " plugins are shown (" + plugins.size() + " total).");
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
        inventory.setItem(DETAIL_CHECK_SLOT, createCheckItem(plugin));
        createRollbackItem(plugin).ifPresent(item -> inventory.setItem(DETAIL_ROLLBACK_SLOT, item));
        inventory.setItem(DETAIL_BACK_SLOT, createBackItem());

        openInventories.put(player.getUniqueId(), InventorySession.detail(inventory, plugin));
        player.openInventory(inventory);
    }

    private ItemStack createPluginItem(ManagedPlugin plugin) {
        Optional<UpdateSource> source = findMatchingSource(plugin);
        boolean updateWaiting = source
                .flatMap(linked -> context.getPersistentPluginHandler().getCheckState(linked.getName()))
                .map(state -> isNewerThanRunning(state, plugin))
                .orElse(false);
        Material material = updateWaiting
                ? Material.WRITABLE_BOOK
                : source.isPresent() ? Material.ENCHANTED_BOOK : Material.BOOK;
        ItemStack item = new ItemStack(material);
        ItemMeta meta = item.getItemMeta();
        if (meta != null) {
            meta.setDisplayName(ChatColor.GOLD + plugin.getName());

            List<String> lore = new ArrayList<>();
            source.flatMap(linked -> context.getPersistentPluginHandler().getCheckState(linked.getName()))
                    .ifPresent(state -> lore.addAll(describeCheckState(state, plugin)));
            lore.add(ChatColor.GRAY + "Status: " + statusLabel(plugin));
            plugin.getPlugin().ifPresentOrElse(loaded -> {
                String version = loaded.getDescription().getVersion();
                if (version != null && !version.isBlank()) {
                    lore.add(ChatColor.GRAY + "Version: " + ChatColor.AQUA + version);
                }
                lore.add(ChatColor.GRAY + "Enabled: "
                        + (loaded.isEnabled() ? ChatColor.GREEN + "Yes" : ChatColor.RED + "No"));
            }, () -> lore.add(ChatColor.RED + "Not loaded"));

            Path path = plugin.getPath();
            if (path != null) {
                lore.add(ChatColor.DARK_GRAY + "File: " + ChatColor.WHITE + path.getFileName());
                source.ifPresentOrElse(linkedSource -> {
                    lore.add(ChatColor.GRAY + "Update source: " + ChatColor.AQUA + linkedSource.getName());
                    lore.add(ChatColor.YELLOW + "Click to change the update source.");
                }, () -> lore.add(ChatColor.RED + "No update source linked - click to set one."));
            } else {
                lore.add(ChatColor.RED + "No jar file found - cannot link.");
            }

            PluginUpdateSettings settings = readSettings(plugin);
            if (!settings.autoUpdateEnabled()) {
                lore.add(ChatColor.GRAY + "Updates: " + ChatColor.RED + "Automatic updates disabled");
            } else if (settings.behaviour() == UpdateBehaviour.AUTO_RELOAD) {
                lore.add(ChatColor.GRAY + "Updates: " + ChatColor.GREEN + "Automatically reload");
            } else {
                lore.add(ChatColor.GRAY + "Updates: " + ChatColor.GOLD + "Server restart required");
            }
            if (isSelfPlugin(plugin)) {
                lore.add(ChatColor.DARK_GRAY + "Managed by NeverUp2Late itself.");
            }

            meta.setLore(lore);
            item.setItemMeta(meta);
        }
        return item;
    }

    private String statusLabel(ManagedPlugin plugin) {
        if (plugin.isEnabled()) {
            return ChatColor.GREEN + "Active";
        }
        if (plugin.isLoaded()) {
            return ChatColor.YELLOW + "Loaded";
        }
        return ChatColor.RED + "Not loaded";
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

    private boolean isSelfPlugin(ManagedPlugin plugin) {
        if (plugin == null) {
            return false;
        }
        Plugin self = context.getPlugin();
        if (self == null) {
            return false;
        }
        if (plugin.getPlugin().map(self::equals).orElse(false)) {
            return true;
        }
        String pluginName = plugin.getName();
        if (pluginName != null && pluginName.equalsIgnoreCase(self.getName())) {
            return true;
        }
        Path managedPath = plugin.getPath();
        if (managedPath == null) {
            return false;
        }
        try {
            CodeSource codeSource = self.getClass().getProtectionDomain().getCodeSource();
            if (codeSource == null || codeSource.getLocation() == null) {
                return false;
            }
            Path selfPath = Path.of(codeSource.getLocation().toURI());
            if (!Files.exists(selfPath)) {
                return false;
            }
            Path normalizedManaged = managedPath.toAbsolutePath().normalize();
            Path normalizedSelf = selfPath.toAbsolutePath().normalize();
            return normalizedManaged.equals(normalizedSelf);
        } catch (Exception ex) {
            return false;
        }
    }

    private ItemStack createEnableItem(ManagedPlugin plugin) {
        if (isSelfPlugin(plugin)) {
            ItemStack item = new ItemStack(Material.BARRIER);
            ItemMeta meta = item.getItemMeta();
            if (meta != null) {
                meta.setDisplayName(ChatColor.DARK_GRAY + "Managed by NeverUp2Late");
                meta.setLore(List.of(
                        ChatColor.GRAY + "NeverUp2Late keeps itself enabled.",
                        ChatColor.GRAY + "Restart the server to reload it."
                ));
                item.setItemMeta(meta);
            }
            return item;
        }
        ItemStack item = new ItemStack(plugin.isEnabled() ? Material.RED_DYE : Material.LIME_DYE);
        ItemMeta meta = item.getItemMeta();
        if (meta != null) {
            if (!plugin.isLoaded()) {
                meta.setDisplayName(ChatColor.DARK_GRAY + "Cannot be enabled");
                meta.setLore(List.of(
                        ChatColor.GRAY + "The plugin is not currently loaded.",
                        ChatColor.GRAY + "Load it to enable it."
                ));
            } else if (plugin.isEnabled()) {
                meta.setDisplayName(ChatColor.RED + "Disable plugin");
                meta.setLore(List.of(
                        ChatColor.GRAY + "Stops the plugin without unloading it."
                ));
            } else {
                meta.setDisplayName(ChatColor.GREEN + "Enable plugin");
                meta.setLore(List.of(
                        ChatColor.GRAY + "Starts the plugin after it has been loaded."
                ));
            }
            item.setItemMeta(meta);
        }
        return item;
    }

    private ItemStack createLoadItem(ManagedPlugin plugin) {
        if (isSelfPlugin(plugin)) {
            ItemStack item = new ItemStack(Material.BARRIER);
            ItemMeta meta = item.getItemMeta();
            if (meta != null) {
                meta.setDisplayName(ChatColor.DARK_GRAY + "Cannot be unloaded");
                meta.setLore(List.of(
                        ChatColor.GRAY + "NeverUp2Late must stay loaded",
                        ChatColor.GRAY + "while it manages updates."
                ));
                item.setItemMeta(meta);
            }
            return item;
        }
        boolean loaded = plugin.isLoaded();
        ItemStack item = new ItemStack(loaded ? Material.BARRIER : Material.EMERALD_BLOCK);
        ItemMeta meta = item.getItemMeta();
        if (meta != null) {
            if (loaded) {
                meta.setDisplayName(ChatColor.GOLD + "Unload plugin");
                meta.setLore(List.of(
                        ChatColor.GRAY + "Fully unloads the plugin.",
                        ChatColor.GRAY + "Frees resources and unregisters listeners."
                ));
            } else {
                meta.setDisplayName(ChatColor.AQUA + "Load plugin");
                meta.setLore(List.of(
                        ChatColor.GRAY + "Loads the plugin from the jar file and enables it."
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
            meta.setDisplayName(ChatColor.AQUA + "Set update source");
            meta.setLore(List.of(
                    ChatColor.GRAY + "Click to enter a new download URL.",
                    ChatColor.GRAY + "Custom links allow installation and updates."
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
                meta.setDisplayName(ChatColor.RED + "Disable automatic updates");
                List<String> lore = new ArrayList<>();
                lore.add(ChatColor.GRAY + "Removes the update source from NU2L.");
                lore.add(ChatColor.GRAY + "The plugin remains installed.");
                String sourceName = source.get().getName();
                if (sourceName != null && !sourceName.isBlank()) {
                    lore.add(ChatColor.DARK_GRAY + "Source: " + ChatColor.AQUA + sourceName);
                }
                lore.add(ChatColor.YELLOW + "Click to disable updates.");
                meta.setLore(lore);
            } else {
                meta.setDisplayName(ChatColor.GREEN + "No update source linked");
                List<String> lore = new ArrayList<>();
                if (!settings.autoUpdateEnabled()) {
                    lore.add(ChatColor.GRAY + "Automatic updates are disabled.");
                } else {
                    lore.add(ChatColor.GRAY + "This plugin is not currently updating automatically.");
                }
                lore.add(ChatColor.GRAY + "Use \"Set update link\" to enable updates.");
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
            meta.setDisplayName(ChatColor.LIGHT_PURPLE + "Adjust update behaviour");
            String current;
            if (!settings.autoUpdateEnabled()) {
                current = ChatColor.RED + "Automatic updates disabled";
            } else if (settings.behaviour() == UpdateBehaviour.AUTO_RELOAD) {
                current = ChatColor.GREEN + "Automatically reload";
            } else {
                current = ChatColor.GOLD + "Restart server after updates";
            }
            meta.setLore(List.of(
                    ChatColor.GRAY + "Current mode:",
                    ChatColor.GRAY + " → " + current,
                    ChatColor.GRAY + "File name: "
                            + (settings.retainUpstreamFilename()
                            ? ChatColor.GREEN + "Upstream"
                            : ChatColor.YELLOW + "Configured"),
                    ChatColor.YELLOW + "Left/Right click for options"
            ));
            item.setItemMeta(meta);
        }
        return item;
    }

    private ItemStack createRemoveItem(ManagedPlugin plugin) {
        if (isSelfPlugin(plugin)) {
            ItemStack item = new ItemStack(Material.BARRIER);
            ItemMeta meta = item.getItemMeta();
            if (meta != null) {
                meta.setDisplayName(ChatColor.DARK_GRAY + "Cannot be removed");
                meta.setLore(List.of(
                        ChatColor.GRAY + "NeverUp2Late cannot remove itself."
                ));
                item.setItemMeta(meta);
            }
            return item;
        }
        ItemStack item = new ItemStack(Material.TNT);
        ItemMeta meta = item.getItemMeta();
        if (meta != null) {
            meta.setDisplayName(ChatColor.RED + "Remove plugin");
            meta.setLore(List.of(
                    ChatColor.GRAY + "Unloads the plugin, deletes the file",
                    ChatColor.GRAY + "and removes the update source."
            ));
            item.setItemMeta(meta);
        }
        return item;
    }

    private ItemStack createRenameItem(ManagedPlugin plugin) {
        if (isSelfPlugin(plugin)) {
            ItemStack item = new ItemStack(Material.NAME_TAG);
            ItemMeta meta = item.getItemMeta();
            if (meta != null) {
                meta.setDisplayName(ChatColor.DARK_GRAY + "Cannot be renamed");
                meta.setLore(List.of(
                        ChatColor.GRAY + "NeverUp2Late manages its own file name."
                ));
                item.setItemMeta(meta);
            }
            return item;
        }
        ItemStack item = new ItemStack(Material.NAME_TAG);
        ItemMeta meta = item.getItemMeta();
        if (meta != null) {
            meta.setDisplayName(ChatColor.AQUA + "Rename file");
            meta.setLore(List.of(
                    ChatColor.GRAY + "Changes the jar file name",
                    ChatColor.GRAY + "and updates the linked source."
            ));
            item.setItemMeta(meta);
        }
        return item;
    }

    private Optional<ItemStack> createQuickRenameItem(ManagedPlugin plugin) {
        if (isSelfPlugin(plugin)) {
            return Optional.empty();
        }
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
            meta.setDisplayName(ChatColor.GREEN + "Match data folder name");
            meta.setLore(List.of(
                    ChatColor.GRAY + "Automatically renames the file,",
                    ChatColor.GRAY + "so it matches the data folder name.",
                    " ",
                    ChatColor.DARK_GRAY + "Target: " + ChatColor.AQUA + sanitized
            ));
            item.setItemMeta(meta);
        }
        return Optional.of(item);
    }

    private ItemStack createBackItem() {
        ItemStack item = new ItemStack(Material.ARROW);
        ItemMeta meta = item.getItemMeta();
        if (meta != null) {
            meta.setDisplayName(ChatColor.YELLOW + "Back to overview");
            meta.setLore(List.of(ChatColor.GRAY + "Closes this view and shows all plugins."));
            item.setItemMeta(meta);
        }
        return item;
    }

    private ItemStack createCheckAllButton() {
        ItemStack item = new ItemStack(Material.SPYGLASS);
        ItemMeta meta = item.getItemMeta();
        if (meta != null) {
            meta.setDisplayName(ChatColor.AQUA + "Check for updates now");
            meta.setLore(List.of(
                    ChatColor.GRAY + "Asks every source what it has,",
                    ChatColor.GRAY + "instead of waiting for the next round.",
                    ChatColor.DARK_GRAY + "Nothing is installed without your settings allowing it."));
            item.setItemMeta(meta);
        }
        return item;
    }

    private ItemStack createCheckItem(ManagedPlugin plugin) {
        boolean linked = findMatchingSource(plugin).isPresent();
        ItemStack item = new ItemStack(linked ? Material.SPYGLASS : Material.GRAY_DYE);
        ItemMeta meta = item.getItemMeta();
        if (meta != null) {
            meta.setDisplayName((linked ? ChatColor.AQUA : ChatColor.DARK_GRAY) + "Check for updates now");
            meta.setLore(List.of(linked
                    ? ChatColor.GRAY + "Ask this plugin's source right away."
                    : ChatColor.GRAY + "Link an update source first."));
            item.setItemMeta(meta);
        }
        return item;
    }

    private Optional<ItemStack> createRollbackItem(ManagedPlugin plugin) {
        Optional<UpdateSource> source = findMatchingSource(plugin);
        if (source.isEmpty()) {
            return Optional.empty();
        }
        ItemStack item = new ItemStack(Material.CLOCK);
        ItemMeta meta = item.getItemMeta();
        if (meta != null) {
            meta.setDisplayName(ChatColor.GOLD + "Restore previous version");
            meta.setLore(List.of(
                    ChatColor.GRAY + "Puts the last backup back in place.",
                    ChatColor.GRAY + "Takes effect after a restart.",
                    ChatColor.DARK_GRAY + "The restored version is kept - it will not be",
                    ChatColor.DARK_GRAY + "replaced again until something newer appears."));
            item.setItemMeta(meta);
        }
        return Optional.of(item);
    }

    private void checkAllSourcesNow(Player player) {
        if (!checkPermission(player, Permissions.INSTALL)) {
            return;
        }
        List<UpdateSource> sources = List.copyOf(context.getUpdateSourceRegistry().getSources());
        if (sources.isEmpty()) {
            player.sendMessage(ChatColor.YELLOW + "No update sources are configured yet.");
            return;
        }
        player.closeInventory();
        context.getUpdateHandler().runJobsNow(sources, player);
    }

    private void checkSourceNow(Player player, ManagedPlugin plugin) {
        if (!checkPermission(player, Permissions.INSTALL)) {
            return;
        }
        Optional<UpdateSource> source = findMatchingSource(plugin);
        if (source.isEmpty()) {
            player.sendMessage(ChatColor.RED + "This plugin has no update source linked yet.");
            return;
        }
        player.closeInventory();
        context.getUpdateHandler().runJobNow(source.get(), player);
    }

    private void rollbackPlugin(Player player, ManagedPlugin plugin) {
        if (!checkPermission(player, Permissions.GUI_MANAGE_LIFECYCLE)) {
            return;
        }
        Optional<UpdateSource> source = findMatchingSource(plugin);
        if (source.isEmpty()) {
            player.sendMessage(ChatColor.RED + "This plugin has no update source, so there is no backup to restore.");
            return;
        }
        player.closeInventory();
        coordinator.rollback(player, source.get().getName());
    }

    private ItemStack createInstallButton() {
        ItemStack item = new ItemStack(Material.NETHER_STAR);
        ItemMeta meta = item.getItemMeta();
        if (meta != null) {
            meta.setDisplayName(ChatColor.AQUA + "Install new plugin…");
            meta.setLore(List.of(
                    ChatColor.GRAY + "Click to enter a download URL.",
                    ChatColor.GRAY + "Installation runs directly through NU2L."
            ));
            item.setItemMeta(meta);
        }
        return item;
    }

    private void toggleEnable(Player player, ManagedPlugin plugin) {
        if (!checkPermission(player, Permissions.GUI_MANAGE_LIFECYCLE)) {
            return;
        }
        if (isSelfPlugin(plugin)) {
            player.sendMessage(ChatColor.RED + "NeverUp2Late cannot toggle its own state while running.");
            return;
        }
        PluginLifecycleManager manager = context.getPluginLifecycleManager();
        if (manager == null) {
            player.sendMessage(ChatColor.RED + "Plugin management is disabled.");
            return;
        }

        String pluginName = plugin.getName();
        if (pluginName == null) {
            player.sendMessage(ChatColor.RED + "Could not determine plugin name.");
            return;
        }

        try {
            if (!plugin.isLoaded()) {
                player.sendMessage(ChatColor.RED + "Please load the plugin before enabling it.");
            } else if (plugin.isEnabled()) {
                if (manager.disablePlugin(pluginName)) {
                    player.sendMessage(ChatColor.YELLOW + "Plugin " + ChatColor.AQUA + pluginName
                            + ChatColor.YELLOW + " has been disabled.");
                } else {
                    player.sendMessage(ChatColor.RED + "Could not disable plugin.");
                }
            } else {
                if (manager.enablePlugin(pluginName)) {
                    player.sendMessage(ChatColor.GREEN + "Plugin " + ChatColor.AQUA + pluginName
                            + ChatColor.GREEN + " has been enabled.");
                } else {
                    player.sendMessage(ChatColor.RED + "Could not enable plugin.");
                }
            }
        } catch (PluginLifecycleException ex) {
            context.getPlugin().getLogger().log(Level.WARNING,
                    "Failed to toggle plugin " + pluginName, ex);
            player.sendMessage(ChatColor.RED + "Action failed: " + ex.getMessage());
        }

        openPluginDetails(player, plugin);
    }

    private void toggleLoad(Player player, ManagedPlugin plugin) {
        if (!checkPermission(player, Permissions.GUI_MANAGE_LIFECYCLE)) {
            return;
        }
        if (isSelfPlugin(plugin)) {
            player.sendMessage(ChatColor.RED + "NeverUp2Late must remain loaded to manage updates.");
            return;
        }
        PluginLifecycleManager manager = context.getPluginLifecycleManager();
        if (manager == null) {
            player.sendMessage(ChatColor.RED + "Plugin management is disabled.");
            return;
        }

        String pluginName = plugin.getName();
        if (pluginName == null) {
            player.sendMessage(ChatColor.RED + "Could not determine plugin name.");
            return;
        }

        try {
            if (plugin.isLoaded()) {
                if (manager.unloadPlugin(pluginName)) {
                    player.sendMessage(ChatColor.YELLOW + "Plugin " + ChatColor.AQUA + pluginName
                            + ChatColor.YELLOW + " has been unloaded.");
                } else {
                    player.sendMessage(ChatColor.RED + "Could not unload plugin.");
                }
            } else {
                Path path = plugin.getPath();
                if (path == null) {
                    player.sendMessage(ChatColor.RED + "No jar path is known for this plugin.");
                    return;
                }
                if (manager.loadPlugin(path)) {
                    player.sendMessage(ChatColor.GREEN + "Plugin " + ChatColor.AQUA + pluginName
                            + ChatColor.GREEN + " has been loaded and enabled.");
                } else {
                    player.sendMessage(ChatColor.RED + "Could not load plugin.");
                }
            }
        } catch (PluginLifecycleException ex) {
            context.getPlugin().getLogger().log(Level.WARNING,
                    "Failed to toggle plugin load state for " + pluginName, ex);
            player.sendMessage(ChatColor.RED + "Action failed: " + ex.getMessage());
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
            player.sendMessage(ChatColor.RED + "Update settings cannot be saved right now.");
            return;
        }

        String pluginName = plugin.getName();
        if (pluginName == null || pluginName.isBlank()) {
            player.sendMessage(ChatColor.RED + "Could not determine plugin name.");
            return;
        }

        PluginUpdateSettings current = readSettings(plugin);
        PluginUpdateSettings next;
        ChatColor color;
        String message;

        if (!current.autoUpdateEnabled()) {
            next = new PluginUpdateSettings(true, UpdateBehaviour.AUTO_RELOAD, current.retainUpstreamFilename());
            color = ChatColor.GREEN;
            message = "Automatic updates enabled. The plugin will reload after updates.";
        } else if (current.behaviour() == UpdateBehaviour.AUTO_RELOAD) {
            next = new PluginUpdateSettings(true, UpdateBehaviour.REQUIRE_RESTART, current.retainUpstreamFilename());
            color = ChatColor.GOLD;
            message = "Automatic updates now require a server restart.";
        } else {
            next = new PluginUpdateSettings(false, UpdateBehaviour.REQUIRE_RESTART, current.retainUpstreamFilename());
            color = ChatColor.RED;
            message = "Automatic updates for this plugin have been disabled.";
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
            player.sendMessage(ChatColor.RED + "Update settings cannot be saved right now.");
            return;
        }

        String pluginName = plugin.getName();
        if (pluginName == null || pluginName.isBlank()) {
            player.sendMessage(ChatColor.RED + "Could not determine plugin name.");
            return;
        }

        PluginUpdateSettings current = readSettings(plugin);
        boolean retain = !current.retainUpstreamFilename();
        updateSettingsRepository.saveSettings(pluginName,
                new PluginUpdateSettings(current.autoUpdateEnabled(), current.behaviour(), retain));

        if (retain) {
            player.sendMessage(ChatColor.GREEN + "Upstream file names will now be kept (old files will be deleted).");
        } else {
            player.sendMessage(ChatColor.YELLOW + "NU2L will once again use configured file names.");
        }

        openPluginDetails(player, plugin);
    }

    private void beginStandaloneInstall(Player player) {
        if (!checkPermission(player, Permissions.INSTALL)) {
            return;
        }
        openStandaloneInstall(player, List.of(), null);
        player.sendMessage(ChatColor.GRAY + "Search for new plugins on Modrinth or Hangar, or provide a direct link (including SpigotMC resources).");
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

        if (session.view() == View.NEW_INSTALL) {
            int clicked = event.getRawSlot();
            if (clicked == INSTALL_BACK_SLOT) {
                openOverview(player);
                return;
            }
            if (clicked == INSTALL_SEARCH_SLOT) {
                promptStandaloneSearch(player, session);
                return;
            }
            if (clicked == INSTALL_MANUAL_SLOT) {
                promptStandaloneManualInstall(player, session);
                return;
            }
            if (clicked == INSTALL_INFO_SLOT) {
                return;
            }
            PluginLinkSuggestion suggestion = session.suggestions().get(clicked);
            if (suggestion != null) {
                installStandaloneSuggestion(player, suggestion);
            }
            return;
        }

        if (session.view() == View.OVERVIEW) {
            int size = session.inventory().getSize();
            int installSlot = size - 1;
            int cleanupSlot = size - 2;
            if (event.getRawSlot() >= size - CONTROL_ROW_SIZE
                    && event.getRawSlot() != installSlot && event.getRawSlot() != cleanupSlot) {
                return; // reserved control row, nothing behind it
            }
            
            if (event.getRawSlot() == installSlot) {
                beginStandaloneInstall(player);
                return;
            }
            
            if (event.getRawSlot() == cleanupSlot) {
                cleanupAllJarNames(player);
                return;
            }

            if (event.getRawSlot() == size - 3) {
                checkAllSourcesNow(player);
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
        if (slot == DETAIL_CHECK_SLOT) {
            checkSourceNow(player, plugin);
            return;
        }
        if (slot == DETAIL_ROLLBACK_SLOT) {
            rollbackPlugin(player, plugin);
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

    /**
     * Clicks are cancelled, but a drag is a separate event. Without this an
     * operator could drag items into the GUI, where they are silently destroyed
     * when the (holder-less) inventory closes.
     */
    @EventHandler
    public void onInventoryDrag(InventoryDragEvent event) {
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
        int size = session.inventory().getSize();
        for (int rawSlot : event.getRawSlots()) {
            if (rawSlot < size) {
                event.setCancelled(true);
                return;
            }
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
        pendingInstallSearches.remove(playerId);
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
            player.sendMessage(ChatColor.RED + "Please enter a valid link or type 'cancel'.");
            return;
        }

        if (message.equalsIgnoreCase("cancel")) {
            pendingLinkRequests.remove(playerId);
            player.sendMessage(ChatColor.YELLOW + "Linking cancelled.");
            return;
        }

        pendingLinkRequests.remove(playerId);
        if (request.standalone()) {
            player.sendMessage(ChatColor.GREEN + "Starting installation of a new plugin…");
            coordinator.install(player, message);
            return;
        }

        String pluginName = request.pluginName();
        player.sendMessage(ChatColor.GREEN + "Processing link for " + ChatColor.AQUA + pluginName + ChatColor.GREEN + " …");
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
            player.sendMessage(ChatColor.RED + "Could not find a jar for this plugin.");
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
        player.sendMessage(ChatColor.GRAY + "Searching for matching sources…");

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
            player.sendMessage(ChatColor.YELLOW + "No matching sources found. Please enter the link manually.");
            promptManualLinkInput(player, plugin, Optional.empty());
            return;
        }

        player.sendMessage(ChatColor.GREEN + "Found " + suggestions.size()
                + " potential update sources.");
        player.sendMessage(ChatColor.GRAY + "Choose a suggestion or enter a link manually.");
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
                ChatColor.DARK_PURPLE + pluginName + " – Update sources");

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

        openInventories.put(player.getUniqueId(), InventorySession.suggestions(inventory, plugin, mapping, entries));
        player.openInventory(inventory);
    }

    private void applySuggestion(Player player, ManagedPlugin plugin, PluginLinkSuggestion suggestion) {
        String pluginName = Objects.requireNonNullElse(plugin.getName(),
                plugin.getPath() != null ? plugin.getPath().getFileName().toString() : "Plugin");
        player.closeInventory();
        openInventories.remove(player.getUniqueId());
        pendingLinkRequests.remove(player.getUniqueId());
        player.sendMessage(ChatColor.GREEN + "Applying suggestion " + ChatColor.AQUA
                + suggestion.provider() + ChatColor.GREEN + " for " + ChatColor.AQUA + pluginName + ChatColor.GREEN + " …");
        coordinator.installForPlugin(player, pluginName, suggestion.url());
    }

    private void openStandaloneInstall(Player player,
                                       List<PluginLinkSuggestion> suggestions,
                                       String searchTerm) {
        List<PluginLinkSuggestion> entries = suggestions == null ? List.of() : suggestions.stream()
                .limit(INSTALL_INFO_SLOT)
                .toList();

        Inventory inventory = Bukkit.createInventory(null, INSTALL_INVENTORY_SIZE,
                ChatColor.DARK_PURPLE + "NU2L – New plugin");

        ItemStack filler = createFiller();
        for (int i = 0; i < INSTALL_INVENTORY_SIZE; i++) {
            inventory.setItem(i, filler.clone());
        }

        Map<Integer, PluginLinkSuggestion> mapping = new HashMap<>();
        for (int i = 0; i < entries.size() && i < INSTALL_INFO_SLOT; i++) {
            PluginLinkSuggestion suggestion = entries.get(i);
            inventory.setItem(i, createSuggestionItem(suggestion));
            mapping.put(i, suggestion);
        }

        inventory.setItem(INSTALL_INFO_SLOT, createInstallInfoItem(entries.size(), searchTerm));
        inventory.setItem(INSTALL_SEARCH_SLOT, createInstallSearchItem(searchTerm));
        inventory.setItem(INSTALL_MANUAL_SLOT, createManualInstallItem());
        inventory.setItem(INSTALL_BACK_SLOT, createBackToOverviewItem());

        openInventories.put(player.getUniqueId(),
                InventorySession.newInstall(inventory, mapping, entries, searchTerm));
        player.openInventory(inventory);
    }

    private ItemStack createInstallInfoItem(int resultCount, String searchTerm) {
        ItemStack item = new ItemStack(Material.WRITTEN_BOOK);
        ItemMeta meta = item.getItemMeta();
        if (meta != null) {
            meta.setDisplayName(ChatColor.AQUA + "Plugin search");
            List<String> lore = new ArrayList<>();
            if (searchTerm == null || searchTerm.isBlank()) {
                lore.add(ChatColor.GRAY + "Use the search to browse Modrinth or Hangar.");
                lore.add(ChatColor.GRAY + "Manual links support SpigotMC via the Spiget API.");
            } else if (resultCount == 0) {
                lore.add(ChatColor.YELLOW + "No results for: " + ChatColor.WHITE + searchTerm);
                lore.add(ChatColor.GRAY + "Adjust the search term or try a direct link.");
            } else {
                lore.add(ChatColor.GREEN + "Projects found: " + ChatColor.WHITE + resultCount);
                lore.add(ChatColor.GRAY + "Click an entry to download the jar.");
            }
            lore.add(" ");
            lore.add(ChatColor.DARK_GRAY + "Plugins are not activated automatically.");
            meta.setLore(lore);
            item.setItemMeta(meta);
        }
        return item;
    }

    private ItemStack createInstallSearchItem(String searchTerm) {
        ItemStack item = new ItemStack(Material.COMPASS);
        ItemMeta meta = item.getItemMeta();
        if (meta != null) {
            meta.setDisplayName(ChatColor.GREEN + "Search for a plugin");
            List<String> lore = new ArrayList<>();
            lore.add(ChatColor.GRAY + "Opens a text input.");
            lore.add(ChatColor.GRAY + "Searches Modrinth and Hangar simultaneously.");
            lore.add(ChatColor.DARK_GRAY + "Use manual input for SpigotMC/Spiget links.");
            if (searchTerm != null && !searchTerm.isBlank()) {
                lore.add(" ");
                lore.add(ChatColor.DARK_GRAY + "Last search: " + ChatColor.WHITE + searchTerm);
            }
            meta.setLore(lore);
            item.setItemMeta(meta);
        }
        return item;
    }

    private ItemStack createManualInstallItem() {
        ItemStack item = new ItemStack(Material.OAK_SIGN);
        ItemMeta meta = item.getItemMeta();
        if (meta != null) {
            meta.setDisplayName(ChatColor.YELLOW + "Enter a direct link");
            meta.setLore(List.of(
                    ChatColor.GRAY + "Opens an anvil dialog",
                    ChatColor.GRAY + "for direct download links (e.g. SpigotMC/Spiget)."
            ));
            item.setItemMeta(meta);
        }
        return item;
    }

    private ItemStack createBackToOverviewItem() {
        ItemStack item = new ItemStack(Material.ARROW);
        ItemMeta meta = item.getItemMeta();
        if (meta != null) {
            meta.setDisplayName(ChatColor.YELLOW + "Back to overview");
            meta.setLore(List.of(ChatColor.GRAY + "Closes the install view and shows all plugins."));
            item.setItemMeta(meta);
        }
        return item;
    }

    private void promptStandaloneSearch(Player player, InventorySession session) {
        List<PluginLinkSuggestion> previous = new ArrayList<>(session.orderedSuggestions());
        String previousTerm = session.searchTerm();

        openInventories.remove(player.getUniqueId());
        player.closeInventory();

        anvilTextPrompt.open(player, AnvilTextPrompt.Prompt.builder()
                .title(ChatColor.DARK_PURPLE + "NU2L search")
                .initialText(previousTerm != null ? previousTerm : "")
                .validation(value -> {
                    String trimmed = value != null ? value.trim() : "";
                    if (trimmed.length() < 3) {
                        return Optional.of(ChatColor.RED + "Please enter at least three characters.");
                    }
                    return Optional.empty();
                })
                .onConfirm((p, value) -> performStandaloneSearch(p, value, previous, previousTerm))
                .onCancel(p -> openStandaloneInstall(p, previous, previousTerm))
                .build());
    }

    private void performStandaloneSearch(Player player,
                                         String query,
                                         List<PluginLinkSuggestion> previousSuggestions,
                                         String previousTerm) {
        String trimmed = query != null ? query.trim() : "";
        if (trimmed.length() < 3) {
            player.sendMessage(ChatColor.RED + "Please enter at least three characters.");
            openStandaloneInstall(player, previousSuggestions, previousTerm);
            return;
        }

        UUID playerId = player.getUniqueId();
        pendingInstallSearches.put(playerId, trimmed);
        player.sendMessage(ChatColor.GRAY + "Searching for " + ChatColor.AQUA + trimmed + ChatColor.GRAY + " …");

        context.getScheduler().runTaskAsynchronously(context.getPlugin(), () -> {
            List<PluginLinkSuggestion> suggestions;
            try {
                suggestions = linkSuggester.suggest(List.of(trimmed));
            } catch (Exception e) {
                context.getPlugin().getLogger().log(Level.FINE,
                        "Failed to resolve standalone search for " + trimmed, e);
                suggestions = List.of();
            }
            List<PluginLinkSuggestion> finalSuggestions = suggestions;
            context.getScheduler().runTask(context.getPlugin(),
                    () -> handleStandaloneSearchResult(player, trimmed, finalSuggestions));
        });
    }

    private void handleStandaloneSearchResult(Player player,
                                              String searchTerm,
                                              List<PluginLinkSuggestion> suggestions) {
        UUID playerId = player.getUniqueId();
        String expected = pendingInstallSearches.get(playerId);
        if (!Objects.equals(expected, searchTerm)) {
            return;
        }
        pendingInstallSearches.remove(playerId);

        if (!player.isOnline()) {
            return;
        }

        List<PluginLinkSuggestion> entries = suggestions == null ? List.of() : suggestions;
        if (entries.isEmpty()) {
            player.sendMessage(ChatColor.YELLOW + "No results found. Try another term.");
        } else {
            player.sendMessage(ChatColor.GREEN + "Projects found: " + entries.size());
        }

        openStandaloneInstall(player, entries, searchTerm);
    }

    private void promptStandaloneManualInstall(Player player, InventorySession session) {
        List<PluginLinkSuggestion> previous = new ArrayList<>(session.orderedSuggestions());
        String previousTerm = session.searchTerm();

        openInventories.remove(player.getUniqueId());
        player.closeInventory();

        anvilTextPrompt.open(player, AnvilTextPrompt.Prompt.builder()
                .title(ChatColor.DARK_PURPLE + "NU2L direct link")
                .initialText("https://")
                .onConfirm((p, value) -> installStandaloneFromUrl(p, value))
                .onCancel(p -> openStandaloneInstall(p, previous, previousTerm))
                .build());
    }

    private void installStandaloneSuggestion(Player player, PluginLinkSuggestion suggestion) {
        player.closeInventory();
        openInventories.remove(player.getUniqueId());
        String title = Objects.requireNonNullElse(suggestion.title(), suggestion.provider());
        player.sendMessage(ChatColor.GREEN + "Installing " + ChatColor.AQUA
                + suggestion.provider() + ChatColor.GREEN + " project " + ChatColor.AQUA
                + title + ChatColor.GREEN + " …");
        coordinator.install(player, suggestion.url());
    }

    private void installStandaloneFromUrl(Player player, String value) {
        String trimmed = value != null ? value.trim() : "";
        if (trimmed.isEmpty()) {
            player.sendMessage(ChatColor.RED + "Please provide a valid download link.");
            return;
        }
        player.sendMessage(ChatColor.GREEN + "Starting installation from direct link…");
        coordinator.install(player, trimmed);
    }

    private void promptManualLinkInput(Player player,
                                       ManagedPlugin plugin,
                                       Optional<UpdateSource> existingSource) {
        UUID playerId = player.getUniqueId();
        pendingSuggestionRequests.remove(playerId);
        String pluginName = Objects.requireNonNullElse(plugin.getName(),
                plugin.getPath() != null ? plugin.getPath().getFileName().toString() : "Plugin");
        pendingLinkRequests.put(playerId, new LinkRequest(pluginName, false));

        player.sendMessage(ChatColor.AQUA + "Set update link for " + pluginName + ".");
        existingSource.ifPresentOrElse(source ->
                        player.sendMessage(ChatColor.GRAY + "Current source: " + ChatColor.AQUA + source.getName()),
                () -> player.sendMessage(ChatColor.GRAY + "No source is currently linked."));
        player.sendMessage(ChatColor.YELLOW + "Please enter the download URL in chat or type 'cancel'.");
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
            lore.add(ChatColor.GRAY + suggestion.provider() + " suggestion");
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
            lore.add(ChatColor.GREEN + "Click to use this link.");
            meta.setLore(lore);
            item.setItemMeta(meta);
        }
        return item;
    }

    private ItemStack createManualLinkOptionItem() {
        ItemStack item = new ItemStack(Material.OAK_SIGN);
        ItemMeta meta = item.getItemMeta();
        if (meta != null) {
            meta.setDisplayName(ChatColor.AQUA + "Enter a link manually");
            meta.setLore(List.of(
                    ChatColor.GRAY + "Opens chat input,",
                    ChatColor.GRAY + "so you can enter a URL manually."
            ));
            item.setItemMeta(meta);
        }
        return item;
    }

    private ItemStack createSuggestionBackItem() {
        ItemStack item = new ItemStack(Material.ARROW);
        ItemMeta meta = item.getItemMeta();
        if (meta != null) {
            meta.setDisplayName(ChatColor.YELLOW + "Back to plugin details");
            meta.setLore(List.of(ChatColor.GRAY + "Returns to the plugin details."));
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
        if (isSelfPlugin(plugin)) {
            player.sendMessage(ChatColor.RED + "NeverUp2Late cannot remove itself while running.");
            return;
        }
        pendingRemovalRequests.put(player.getUniqueId(), plugin);
        player.closeInventory();
        player.sendMessage(ChatColor.RED + "Are you sure you want to remove "
                + ChatColor.AQUA + plugin.getName() + ChatColor.RED + "?");
        player.sendMessage(ChatColor.YELLOW + "Type \"yes\" to confirm or \"cancel\" to abort.");
    }

    private void handleRemovalInput(Player player, ManagedPlugin plugin, String message) {
        UUID playerId = player.getUniqueId();
        pendingRemovalRequests.remove(playerId);
        String trimmed = message != null ? message.trim() : "";
        if (trimmed.equalsIgnoreCase("yes")) {
            coordinator.removeManagedPlugin(player, plugin);
        } else {
            player.sendMessage(ChatColor.YELLOW + "Removal cancelled.");
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
        if (isSelfPlugin(plugin)) {
            player.sendMessage(ChatColor.RED + "NeverUp2Late manages its own filename automatically.");
            return;
        }
        Path path = plugin.getPath();
        if (path == null) {
            player.sendMessage(ChatColor.RED + "Could not determine a file path for this plugin.");
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
                .title(ChatColor.DARK_PURPLE + "NU2L Rename")
                .initialText(currentName)
                .inputItem(paper)
                .previewFactory(this::createRenamePreview)
                .validation(value -> {
                    if (value == null || value.trim().isEmpty()) {
                        return Optional.of(ChatColor.RED + "Please enter a valid name.");
                    }
                    return Optional.empty();
                })
                .onConfirm((p, value) -> {
                    requestRename(p, plugin, value);
                })
                .onCancel(p -> p.sendMessage(ChatColor.YELLOW + "Rename cancelled."))
                .build());

        player.sendMessage(ChatColor.GRAY + "Enter a new file name (\".jar\" will be added automatically).");
    }

    private void requestRename(Player player, ManagedPlugin plugin, String requestedName) {
        if (isSelfPlugin(plugin)) {
            player.sendMessage(ChatColor.RED + "NeverUp2Late cannot be renamed from within the GUI.");
            return;
        }
        String pluginName = plugin.getName();
        coordinator.renameManagedPlugin(player, plugin, requestedName);
        PluginLifecycleManager lifecycleManager = context.getPluginLifecycleManager();
        if (lifecycleManager != null && pluginName != null) {
            context.getScheduler().runTaskLater(context.getPlugin(), () ->
                    lifecycleManager.findByName(pluginName)
                            .ifPresent(updated -> openPluginDetails(player, updated)), 2L);
        }
    }

        private ItemStack createCleanupButton() {

            ItemStack item = new ItemStack(Material.RECOVERY_COMPASS);

            ItemMeta meta = item.getItemMeta();

            if (meta != null) {

                meta.setDisplayName(ChatColor.GOLD + "Tidy up jar names");

                meta.setLore(List.of(

                        ChatColor.GRAY + "Renames all plugin files to match",

                        ChatColor.GRAY + "their data folder names.",

                        ChatColor.YELLOW + "Ensures consistent and clean naming."

                ));

                item.setItemMeta(meta);

            }

            return item;

        }

    

        private void cleanupAllJarNames(Player player) {
            if (!checkPermission(player, Permissions.GUI_MANAGE_RENAME)) {
                return;
            }
            List<ManagedPlugin> plugins = context.getPluginLifecycleManager().getManagedPlugins().stream()
                    .filter(p -> !isSelfPlugin(p))
                    .toList();

            int renamed = 0;
            int blocked = 0;
            for (ManagedPlugin plugin : plugins) {
                String name = plugin.getName();
                Path currentPath = plugin.getPath();
                Path afterRename = tryQuickRename(plugin);
                if (afterRename != null) {
                    renamed++;
                    currentPath = afterRename;
                } else if (needsRename(plugin)) {
                    blocked++;
                }
                // Only ever hand the duplicate cleanup the path we know is
                // current - it deletes files based on it.
                if (currentPath != null) {
                    context.getPluginLifecycleManager().deleteAllDuplicates(name, currentPath);
                }
            }

            if (renamed > 0) {
                player.sendMessage(ChatColor.GREEN + "Renamed " + renamed
                        + " plugin file(s) and removed duplicates.");
                player.sendMessage(ChatColor.GRAY
                        + "The plugins keep running; the new names take effect on the next restart.");
            } else {
                player.sendMessage(ChatColor.GREEN + "All filenames are already correct; duplicates cleaned up.");
            }
            if (blocked > 0) {
                player.sendMessage(ChatColor.YELLOW + String.valueOf(blocked)
                        + " file(s) could not be renamed while the server is running.");
            }
            openOverview(player);
        }

        private boolean needsRename(ManagedPlugin plugin) {
            return desiredFileName(plugin) != null;
        }

        /**
         * Name the jar of {@code plugin} should carry, or {@code null} if it
         * already carries it or cannot be determined.
         */
        private String desiredFileName(ManagedPlugin plugin) {
            Path path = plugin.getPath();
            if (path == null) {
                return null;
            }
            Optional<Plugin> loadedPlugin = plugin.getPlugin();
            if (loadedPlugin.isEmpty()) {
                return null;
            }
            File dataFolder = loadedPlugin.get().getDataFolder();
            if (dataFolder == null) {
                return null;
            }
            String sanitized = FileNameSanitizer.sanitizeJarFilename(dataFolder.getName());
            if (sanitized == null || sanitized.equals(path.getFileName().toString())) {
                return null;
            }
            return sanitized;
        }

        /**
         * Renames a plugin jar in place, without touching its lifecycle.
         *
         * <p>The bulk cleanup used to route every plugin through the regular
         * rename, which disables and unloads it - one click could take most of
         * the server's plugins down and nothing brought them back. Renaming the
         * file is enough: the running plugin holds an open handle and keeps
         * working, and Bukkit picks up the new name on the next start.</p>
         *
         * @return the new path, or {@code null} if nothing was renamed
         */
        private Path tryQuickRename(ManagedPlugin plugin) {
            String sanitized = desiredFileName(plugin);
            if (sanitized == null) {
                return null;
            }
            Path path = plugin.getPath();
            Path parent = path.getParent();
            if (parent == null) {
                return null;
            }
            Path target = parent.resolve(sanitized).toAbsolutePath().normalize();
            if (!parent.toAbsolutePath().normalize().equals(target.getParent()) || Files.exists(target)) {
                return null;
            }
            try {
                Files.move(path, target);
            } catch (IOException ex) {
                // Typically a locked file on Windows. Leave it alone.
                context.getPlugin().getLogger().log(Level.FINE,
                        "Could not rename " + path.getFileName() + " while it is in use", ex);
                return null;
            }
            context.getPluginLifecycleManager().updateManagedPluginPath(path, target);
            return target;
        }

    

        private void quickRenameToDataDirectory(Player player, ManagedPlugin plugin) {

            if (!checkPermission(player, Permissions.GUI_MANAGE_RENAME)) {

                return;

            }

    

            if (isSelfPlugin(plugin)) {

                player.sendMessage(ChatColor.RED + "NeverUp2Late manages its own filename.");

                return;

            }

    

            Path path = plugin.getPath();

            if (path == null) {

                player.sendMessage(ChatColor.RED + "Could not determine a file path.");

                return;

            }

    

            Optional<Plugin> loadedPlugin = plugin.getPlugin();

            if (loadedPlugin.isEmpty()) {

                player.sendMessage(ChatColor.RED + "Plugin must be loaded to determine the data folder name.");

                return;

            }

    

            File dataFolder = loadedPlugin.get().getDataFolder();

            if (dataFolder == null) {

                player.sendMessage(ChatColor.RED + "Could not find a data folder.");

                return;

            }

    

            String sanitized = FileNameSanitizer.sanitizeJarFilename(dataFolder.getName());

            if (sanitized == null) {

                player.sendMessage(ChatColor.RED + "Data folder name is invalid.");

                return;

            }

    

            String current = path.getFileName().toString();

            if (sanitized.equals(current)) {

                player.sendMessage(ChatColor.YELLOW + "Filename is already correct.");

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

    /**
     * The two lines an operator opens this screen for: is there something newer,
     * and did the last look actually work. Everything else on the item describes
     * what is installed or which policy applies - neither answers the question.
     */
    private List<String> describeCheckState(UpdateStateRepository.CheckState state, ManagedPlugin plugin) {
        List<String> lines = new ArrayList<>();
        if (state.result() == UpdateStateRepository.CheckResult.FAILED) {
            String reason = state.error() == null || state.error().isBlank() ? "unknown reason" : state.error();
            lines.add(ChatColor.RED + "Last check failed: " + ChatColor.WHITE + reason);
        } else if (isNewerThanRunning(state, plugin)) {
            lines.add(ChatColor.GREEN + "" + ChatColor.BOLD + "Update ready: "
                    + ChatColor.WHITE + state.latestVersion());
            lines.add(ChatColor.GRAY + "Applied on the next restart.");
        } else {
            lines.add(ChatColor.GREEN + "Up to date.");
        }
        lines.add(ChatColor.DARK_GRAY + "Checked " + describeAge(state.checkedAt()));
        return lines;
    }

    /**
     * Whether the version already written to disk differs from the one the
     * running plugin reports - i.e. a jar is waiting for a restart.
     */
    private boolean isNewerThanRunning(UpdateStateRepository.CheckState state, ManagedPlugin plugin) {
        String available = state.latestVersion();
        if (available == null || available.isBlank()) {
            return false;
        }
        String running = plugin.getPlugin()
                .map(loaded -> loaded.getDescription().getVersion())
                .orElse(null);
        if (running == null || running.isBlank()) {
            return false;
        }
        return VERSION_COMPARATOR.compare(running, available) < 0;
    }

    private String describeAge(long epochMillis) {
        if (epochMillis <= 0L) {
            return "never";
        }
        long minutes = Math.max(0L, (System.currentTimeMillis() - epochMillis) / 60_000L);
        if (minutes < 1L) {
            return "just now";
        }
        if (minutes < 60L) {
            return minutes + " min ago";
        }
        long hours = minutes / 60L;
        if (hours < 24L) {
            return hours + (hours == 1L ? " hour ago" : " hours ago");
        }
        long days = hours / 24L;
        return days + (days == 1L ? " day ago" : " days ago");
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
        LINK_SUGGESTIONS,
        NEW_INSTALL
    }

    private record InventorySession(Inventory inventory,
                                    View view,
                                    Map<Integer, ManagedPlugin> plugins,
                                    ManagedPlugin plugin,
                                    Map<Integer, PluginLinkSuggestion> suggestions,
                                    List<PluginLinkSuggestion> orderedSuggestions,
                                    String searchTerm) {
        static InventorySession overview(Inventory inventory, Map<Integer, ManagedPlugin> plugins) {
            return new InventorySession(inventory, View.OVERVIEW, Map.copyOf(plugins), null, Map.of(), List.of(), null);
        }

        static InventorySession detail(Inventory inventory, ManagedPlugin plugin) {
            return new InventorySession(inventory, View.DETAIL, Map.of(), plugin, Map.of(), List.of(), null);
        }

        static InventorySession suggestions(Inventory inventory,
                                            ManagedPlugin plugin,
                                            Map<Integer, PluginLinkSuggestion> suggestions,
                                            List<PluginLinkSuggestion> ordered) {
            return new InventorySession(inventory, View.LINK_SUGGESTIONS, Map.of(), plugin,
                    Map.copyOf(suggestions), List.copyOf(ordered), null);
        }

        static InventorySession newInstall(Inventory inventory,
                                           Map<Integer, PluginLinkSuggestion> suggestions,
                                           List<PluginLinkSuggestion> ordered,
                                           String searchTerm) {
            return new InventorySession(inventory, View.NEW_INSTALL, Map.of(), null,
                    Map.copyOf(suggestions), List.copyOf(ordered), searchTerm);
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
        player.sendMessage(ChatColor.RED + "You are missing the permission " + permission + ".");
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
