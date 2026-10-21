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
import eu.nurkert.neverUp2Late.update.UpdateSourceRegistry.UpdateSource;
import eu.nurkert.neverUp2Late.util.FileNameSanitizer;
import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.plugin.Plugin;

import java.io.File;
import java.nio.file.Path;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.logging.Level;

/**
 * The per-plugin screen: lifecycle toggles, update behaviour, linking, renaming,
 * rollback and removal of a single managed plugin.
 */
final class PluginDetailScreen {

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

    private final GuiState state;
    private final PluginOverviewGui gui;
    private final PluginContext context;
    private final QuickInstallCoordinator coordinator;
    private final AnvilTextPrompt anvilTextPrompt;
    private final PluginUpdateSettingsRepository updateSettingsRepository;
    private final ChatPrompts prompts;
    private final GuiItems items;
    private final PluginDetailItems detailItems;

    PluginDetailScreen(GuiState state, PluginOverviewGui gui) {
        this.state = state;
        this.gui = gui;
        this.context = state.context;
        this.coordinator = state.coordinator;
        this.anvilTextPrompt = state.anvilTextPrompt;
        this.updateSettingsRepository = state.updateSettingsRepository;
        this.prompts = state.prompts;
        this.items = state.items;
        this.detailItems = new PluginDetailItems(state.items);
    }

    void openPluginDetails(Player player, ManagedPlugin plugin) {
        Inventory inventory = Bukkit.createInventory(null, DETAIL_INVENTORY_SIZE,
                ChatColor.DARK_PURPLE + Objects.requireNonNullElse(plugin.getName(), "Plugin"));

        ItemStack filler = items.createFiller();
        for (int i = 0; i < DETAIL_INVENTORY_SIZE; i++) {
            inventory.setItem(i, filler.clone());
        }

        inventory.setItem(DETAIL_STATUS_SLOT, items.createPluginItem(plugin));
        inventory.setItem(DETAIL_ENABLE_SLOT, detailItems.createEnableItem(plugin));
        inventory.setItem(DETAIL_LOAD_SLOT, detailItems.createLoadItem(plugin));
        inventory.setItem(DETAIL_LINK_SLOT, detailItems.createLinkItem(plugin));
        inventory.setItem(DETAIL_DISABLE_SLOT, detailItems.createDisableUpdatesItem(plugin));
        inventory.setItem(DETAIL_UPDATE_SLOT, detailItems.createUpdateSettingsItem(plugin));
        inventory.setItem(DETAIL_RENAME_SLOT, detailItems.createRenameItem(plugin));
        detailItems.createQuickRenameItem(plugin).ifPresent(item ->
                inventory.setItem(DETAIL_QUICK_RENAME_SLOT, item));
        inventory.setItem(DETAIL_REMOVE_SLOT, detailItems.createRemoveItem(plugin));
        inventory.setItem(DETAIL_CHECK_SLOT, detailItems.createCheckItem(plugin));
        detailItems.createRollbackItem(plugin).ifPresent(item -> inventory.setItem(DETAIL_ROLLBACK_SLOT, item));
        inventory.setItem(DETAIL_BACK_SLOT, detailItems.createBackItem());

        state.openInventories.put(player.getUniqueId(), InventorySession.detail(inventory, plugin));
        player.openInventory(inventory);
    }

    void handleClick(Player player, ManagedPlugin plugin, InventoryClickEvent event) {
        int slot = event.getRawSlot();
        if (slot == DETAIL_BACK_SLOT) {
            gui.overview().openOverview(player);
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
            gui.links().beginLinking(player, plugin);
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

    private void checkSourceNow(Player player, ManagedPlugin plugin) {
        if (!state.checkPermission(player, Permissions.INSTALL)) {
            return;
        }
        Optional<UpdateSource> source = items.findMatchingSource(plugin);
        if (source.isEmpty()) {
            player.sendMessage(ChatColor.RED + "This plugin has no update source linked yet.");
            return;
        }
        player.closeInventory();
        context.getUpdateHandler().runJobNow(source.get(), player);
    }

    private void rollbackPlugin(Player player, ManagedPlugin plugin) {
        if (!state.checkPermission(player, Permissions.GUI_MANAGE_LIFECYCLE)) {
            return;
        }
        Optional<UpdateSource> source = items.findMatchingSource(plugin);
        if (source.isEmpty()) {
            player.sendMessage(ChatColor.RED + "This plugin has no update source, so there is no backup to restore.");
            return;
        }
        player.closeInventory();
        coordinator.rollback(player, source.get().getName());
    }

    private void toggleEnable(Player player, ManagedPlugin plugin) {
        if (!state.checkPermission(player, Permissions.GUI_MANAGE_LIFECYCLE)) {
            return;
        }
        if (items.isSelfPlugin(plugin)) {
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
        if (!state.checkPermission(player, Permissions.GUI_MANAGE_LIFECYCLE)) {
            return;
        }
        if (items.isSelfPlugin(plugin)) {
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
        if (!state.checkPermission(player, Permissions.GUI_MANAGE_SETTINGS)) {
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

        PluginUpdateSettings current = items.readSettings(plugin);
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
        if (!state.checkPermission(player, Permissions.GUI_MANAGE_SETTINGS)) {
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

        PluginUpdateSettings current = items.readSettings(plugin);
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

    private void confirmRemoval(Player player, ManagedPlugin plugin) {
        if (!state.checkPermission(player, Permissions.GUI_MANAGE_REMOVE)) {
            return;
        }
        if (items.isSelfPlugin(plugin)) {
            player.sendMessage(ChatColor.RED + "NeverUp2Late cannot remove itself while running.");
            return;
        }
        prompts.pendingRemovalRequests.put(player.getUniqueId(), plugin);
        prompts.armPrompt(player);
        player.closeInventory();
        player.sendMessage(ChatColor.RED + "Are you sure you want to remove "
                + ChatColor.AQUA + plugin.getName() + ChatColor.RED + "?");
        player.sendMessage(ChatColor.YELLOW + "Type \"yes\" to confirm or \"cancel\" to abort.");
    }

    void handleRemovalInput(Player player, ManagedPlugin plugin, String message) {
        UUID playerId = player.getUniqueId();
        prompts.pendingRemovalRequests.remove(playerId);
        String trimmed = message != null ? message.trim() : "";
        if (trimmed.equalsIgnoreCase("yes")) {
            coordinator.removeManagedPlugin(player, plugin);
        } else {
            player.sendMessage(ChatColor.YELLOW + "Removal cancelled.");
        }
    }

    private void disableAutoUpdates(Player player, ManagedPlugin plugin) {
        if (!state.checkPermission(player, Permissions.GUI_MANAGE_LINK)) {
            return;
        }
        coordinator.disableAutomaticUpdates(player, plugin);
        openPluginDetails(player, plugin);
    }

    private void beginRename(Player player, ManagedPlugin plugin) {
        if (!state.checkPermission(player, Permissions.GUI_MANAGE_RENAME)) {
            return;
        }
        if (items.isSelfPlugin(plugin)) {
            player.sendMessage(ChatColor.RED + "NeverUp2Late manages its own filename automatically.");
            return;
        }
        Path path = plugin.getPath();
        if (path == null) {
            player.sendMessage(ChatColor.RED + "Could not determine a file path for this plugin.");
            return;
        }

        String currentName = items.stripJarExtension(path.getFileName().toString());
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
        if (items.isSelfPlugin(plugin)) {
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

    private void quickRenameToDataDirectory(Player player, ManagedPlugin plugin) {
        if (!state.checkPermission(player, Permissions.GUI_MANAGE_RENAME)) {
            return;
        }

        if (items.isSelfPlugin(plugin)) {
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
