package eu.nurkert.neverUp2Late.gui;

import eu.nurkert.neverUp2Late.util.Feedback;
import eu.nurkert.neverUp2Late.util.Theme;
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
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.plugin.Plugin;

import java.io.File;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.logging.Level;

/**
 * The per-plugin screen: lifecycle toggles, update behaviour, linking, renaming,
 * rollback and removal of a single managed plugin.
 */
final class PluginDetailScreen {

    // Content area of the shared grid: lifecycle on the first row, updates on
    // the second, the file on the third, and removal alone at the bottom,
    // well away from everything else.
    private static final int DETAIL_ENABLE_SLOT = 10;
    private static final int DETAIL_LOAD_SLOT = 12;
    private static final int DETAIL_CHECK_SLOT = 14;
    private static final int DETAIL_LINK_SLOT = 16;
    private static final int DETAIL_UPDATE_SLOT = 20;
    private static final int DETAIL_DISABLE_SLOT = 22;
    private static final int DETAIL_ROLLBACK_SLOT = 24;
    private static final int DETAIL_RENAME_SLOT = 30;
    private static final int DETAIL_QUICK_RENAME_SLOT = 32;
    private static final int DETAIL_REMOVE_SLOT = 40;

    private final GuiState state;
    private final PluginOverviewGui gui;
    private final PluginContext context;
    private final QuickInstallCoordinator coordinator;
    private final AnvilTextPrompt anvilTextPrompt;
    private final PluginUpdateSettingsRepository updateSettingsRepository;
    private final GuiItems items;
    private final PluginDetailItems detailItems;

    PluginDetailScreen(GuiState state, PluginOverviewGui gui) {
        this.state = state;
        this.gui = gui;
        this.context = state.context;
        this.coordinator = state.coordinator;
        this.anvilTextPrompt = state.anvilTextPrompt;
        this.updateSettingsRepository = state.updateSettingsRepository;
        this.items = state.items;
        this.detailItems = new PluginDetailItems(state.items);
    }

    void openPluginDetails(Player player, ManagedPlugin plugin) {
        Inventory inventory = Bukkit.createInventory(null, GuiLayout.SIZE,
                GuiChrome.title(Objects.requireNonNullElse(plugin.getName(), "Plugin")));

        GuiChrome.drawFrame(inventory, FrameState.of(items.statusOf(plugin)));

        inventory.setItem(GuiLayout.SUMMARY_SLOT, items.createPluginItem(plugin, List.of()));
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
        inventory.setItem(GuiLayout.BACK_SLOT, GuiChrome.back("the plugin list"));
        inventory.setItem(GuiLayout.CLOSE_SLOT, GuiChrome.close());

        state.openInventories.put(player.getUniqueId(), InventorySession.detail(inventory, plugin));
        player.openInventory(inventory);
    }

    void handleClick(Player player, ManagedPlugin plugin, InventoryClickEvent event) {
        int slot = event.getRawSlot();
        if (slot == GuiLayout.BACK_SLOT) {
            Feedback.pageTurn(player);
            gui.overview().openOverview(player);
            return;
        }
        if (slot == DETAIL_CHECK_SLOT) {
            Feedback.click(player);
            checkSourceNow(player, plugin);
            return;
        }
        if (slot == DETAIL_ROLLBACK_SLOT) {
            ItemStack clickedItem = event.getCurrentItem();
            if (clickedItem != null && clickedItem.getType() == Material.CLOCK) {
                Feedback.click(player);
                confirmRollback(player, plugin);
            }
            return;
        }
        if (slot == DETAIL_ENABLE_SLOT) {
            Feedback.click(player);
            toggleEnable(player, plugin);
            return;
        }
        if (slot == DETAIL_LOAD_SLOT) {
            Feedback.click(player);
            toggleLoad(player, plugin);
            return;
        }
        if (slot == DETAIL_LINK_SLOT) {
            Feedback.click(player);
            gui.links().beginLinking(player, plugin);
            return;
        }
        if (slot == DETAIL_DISABLE_SLOT) {
            Feedback.click(player);
            disableAutoUpdates(player, plugin);
            return;
        }
        if (slot == DETAIL_UPDATE_SLOT) {
            Feedback.click(player);
            if (event.isRightClick()) {
                toggleFilenameRetention(player, plugin);
            } else {
                cycleUpdateBehaviour(player, plugin);
            }
            return;
        }
        if (slot == DETAIL_RENAME_SLOT) {
            Feedback.click(player);
            beginRename(player, plugin);
            return;
        }
        if (slot == DETAIL_QUICK_RENAME_SLOT) {
            ItemStack clickedItem = event.getCurrentItem();
            if (clickedItem != null && clickedItem.getType() == Material.CHEST) {
                Feedback.click(player);
                quickRenameToDataDirectory(player, plugin);
            }
            return;
        }
        if (slot == DETAIL_REMOVE_SLOT) {
            Feedback.click(player);
            confirmRemoval(player, plugin);
        }
    }

    /** Asks this plugin's source right away - from this screen, or by right-clicking it in the list. */
    void checkSourceNow(Player player, ManagedPlugin plugin) {
        if (!state.checkPermission(player, Permissions.INSTALL)) {
            return;
        }
        Optional<UpdateSource> source = items.findMatchingSource(plugin);
        if (source.isEmpty()) {
            player.sendMessage(Theme.ERROR + "This plugin has no update source linked yet.");
            Feedback.error(player);
            return;
        }
        player.closeInventory();
        context.getUpdateHandler().runJobNow(source.get(), player);
    }

    private void confirmRollback(Player player, ManagedPlugin plugin) {
        if (!state.checkPermission(player, Permissions.GUI_MANAGE_LIFECYCLE)) {
            return;
        }
        Optional<UpdateSource> source = items.findMatchingSource(plugin);
        if (source.isEmpty()) {
            player.sendMessage(Theme.ERROR + "This plugin has no update source, so there is no backup to restore.");
            Feedback.error(player);
            return;
        }
        String pluginName = Objects.requireNonNullElse(plugin.getName(), "this plugin");
        ItemStack description = GuiChrome.button(Material.CLOCK,
                Theme.HEADING + "Restore " + Theme.ACCENT + pluginName, List.of(
                        Theme.MUTED + "Puts the last backup back in place.",
                        Theme.MUTED + "Takes effect after a restart.",
                        Theme.MUTED + "Source: " + Theme.ACCENT + source.get().getName(),
                        " ",
                        Theme.SUBTLE + "The restored version is kept - it will not be",
                        Theme.SUBTLE + "replaced again until something newer appears."));
        gui.confirmations().open(player, new ConfirmationScreen.Confirmation(
                "Rollback",
                description,
                "Restore the previous version.",
                p -> rollbackPlugin(p, plugin),
                p -> openPluginDetails(p, plugin)));
    }

    private void rollbackPlugin(Player player, ManagedPlugin plugin) {
        if (!state.checkPermission(player, Permissions.GUI_MANAGE_LIFECYCLE)) {
            return;
        }
        Optional<UpdateSource> source = items.findMatchingSource(plugin);
        if (source.isEmpty()) {
            player.sendMessage(Theme.ERROR + "This plugin has no update source, so there is no backup to restore.");
            Feedback.error(player);
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
            player.sendMessage(Theme.ERROR + "NeverUp2Late cannot toggle its own state while running.");
            Feedback.error(player);
            return;
        }
        PluginLifecycleManager manager = context.getPluginLifecycleManager();
        if (manager == null) {
            player.sendMessage(Theme.ERROR + "Plugin management is disabled.");
            return;
        }

        String pluginName = plugin.getName();
        if (pluginName == null) {
            player.sendMessage(Theme.ERROR + "Could not determine plugin name.");
            return;
        }

        try {
            if (!plugin.isLoaded()) {
                player.sendMessage(Theme.ERROR + "Please load the plugin before enabling it.");
                Feedback.error(player);
            } else if (plugin.isEnabled()) {
                if (manager.disablePlugin(pluginName)) {
                    player.sendMessage(Theme.WARNING + "Plugin " + Theme.ACCENT + pluginName
                            + Theme.WARNING + " has been disabled.");
                    Feedback.success(player);
                } else {
                    player.sendMessage(Theme.ERROR + "Could not disable plugin.");
                    Feedback.error(player);
                }
            } else {
                if (manager.enablePlugin(pluginName)) {
                    player.sendMessage(Theme.SUCCESS + "Plugin " + Theme.ACCENT + pluginName
                            + Theme.SUCCESS + " has been enabled.");
                    Feedback.success(player);
                } else {
                    player.sendMessage(Theme.ERROR + "Could not enable plugin.");
                    Feedback.error(player);
                }
            }
        } catch (PluginLifecycleException ex) {
            context.getPlugin().getLogger().log(Level.WARNING,
                    "Failed to toggle plugin " + pluginName, ex);
            player.sendMessage(Theme.ERROR + "Action failed: " + ex.getMessage());
            Feedback.error(player);
        }

        openPluginDetails(player, plugin);
    }

    private void toggleLoad(Player player, ManagedPlugin plugin) {
        if (!state.checkPermission(player, Permissions.GUI_MANAGE_LIFECYCLE)) {
            return;
        }
        if (items.isSelfPlugin(plugin)) {
            player.sendMessage(Theme.ERROR + "NeverUp2Late must remain loaded to manage updates.");
            Feedback.error(player);
            return;
        }
        PluginLifecycleManager manager = context.getPluginLifecycleManager();
        if (manager == null) {
            player.sendMessage(Theme.ERROR + "Plugin management is disabled.");
            return;
        }

        String pluginName = plugin.getName();
        if (pluginName == null) {
            player.sendMessage(Theme.ERROR + "Could not determine plugin name.");
            return;
        }

        try {
            if (plugin.isLoaded()) {
                if (manager.unloadPlugin(pluginName)) {
                    player.sendMessage(Theme.WARNING + "Plugin " + Theme.ACCENT + pluginName
                            + Theme.WARNING + " has been unloaded.");
                    Feedback.success(player);
                } else {
                    player.sendMessage(Theme.ERROR + "Could not unload plugin.");
                    Feedback.error(player);
                }
            } else {
                Path path = plugin.getPath();
                if (path == null) {
                    player.sendMessage(Theme.ERROR + "No jar path is known for this plugin.");
                    return;
                }
                if (manager.loadPlugin(path)) {
                    player.sendMessage(Theme.SUCCESS + "Plugin " + Theme.ACCENT + pluginName
                            + Theme.SUCCESS + " has been loaded and enabled.");
                    Feedback.success(player);
                } else {
                    player.sendMessage(Theme.ERROR + "Could not load plugin.");
                    Feedback.error(player);
                }
            }
        } catch (PluginLifecycleException ex) {
            context.getPlugin().getLogger().log(Level.WARNING,
                    "Failed to toggle plugin load state for " + pluginName, ex);
            player.sendMessage(Theme.ERROR + "Action failed: " + ex.getMessage());
            Feedback.error(player);
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
            player.sendMessage(Theme.ERROR + "Update settings cannot be saved right now.");
            return;
        }

        String pluginName = plugin.getName();
        if (pluginName == null || pluginName.isBlank()) {
            player.sendMessage(Theme.ERROR + "Could not determine plugin name.");
            return;
        }

        PluginUpdateSettings current = items.readSettings(plugin);
        PluginUpdateSettings next;
        String color;
        String message;

        if (!current.autoUpdateEnabled()) {
            next = new PluginUpdateSettings(true, UpdateBehaviour.AUTO_RELOAD, current.retainUpstreamFilename());
            color = Theme.SUCCESS;
            message = "Automatic updates enabled. The plugin will reload after updates.";
        } else if (current.behaviour() == UpdateBehaviour.AUTO_RELOAD) {
            next = new PluginUpdateSettings(true, UpdateBehaviour.REQUIRE_RESTART, current.retainUpstreamFilename());
            color = Theme.HEADING;
            message = "Automatic updates now require a server restart.";
        } else {
            next = new PluginUpdateSettings(false, UpdateBehaviour.REQUIRE_RESTART, current.retainUpstreamFilename());
            color = Theme.ERROR;
            message = "Automatic updates for this plugin have been disabled.";
        }

        updateSettingsRepository.saveSettings(pluginName, next);
        player.sendMessage(color + message);
        Feedback.success(player);
        openPluginDetails(player, plugin);
    }

    private void toggleFilenameRetention(Player player, ManagedPlugin plugin) {
        if (!state.checkPermission(player, Permissions.GUI_MANAGE_SETTINGS)) {
            return;
        }

        if (updateSettingsRepository == null) {
            player.sendMessage(Theme.ERROR + "Update settings cannot be saved right now.");
            return;
        }

        String pluginName = plugin.getName();
        if (pluginName == null || pluginName.isBlank()) {
            player.sendMessage(Theme.ERROR + "Could not determine plugin name.");
            return;
        }

        PluginUpdateSettings current = items.readSettings(plugin);
        boolean retain = !current.retainUpstreamFilename();
        updateSettingsRepository.saveSettings(pluginName,
                new PluginUpdateSettings(current.autoUpdateEnabled(), current.behaviour(), retain));

        if (retain) {
            player.sendMessage(Theme.SUCCESS + "Upstream file names will now be kept (old files will be deleted).");
        } else {
            player.sendMessage(Theme.WARNING + "NU2L will once again use configured file names.");
        }
        Feedback.success(player);

        openPluginDetails(player, plugin);
    }

    private void confirmRemoval(Player player, ManagedPlugin plugin) {
        if (!state.checkPermission(player, Permissions.GUI_MANAGE_REMOVE)) {
            return;
        }
        if (items.isSelfPlugin(plugin)) {
            player.sendMessage(Theme.ERROR + "NeverUp2Late cannot remove itself while running.");
            Feedback.error(player);
            return;
        }
        List<String> lore = new ArrayList<>();
        lore.add(Theme.MUTED + "Unloads the plugin, deletes the file");
        lore.add(Theme.MUTED + "and removes the update source.");
        if (plugin.getPath() != null) {
            lore.add(Theme.MUTED + "File: " + Theme.TEXT + plugin.getPath().getFileName());
        }
        lore.add(" ");
        lore.add(Theme.ERROR + Theme.WARN + " This cannot be undone.");
        ItemStack description = GuiChrome.button(Material.TNT,
                Theme.ERROR + "Remove " + Theme.ACCENT + plugin.getName() + Theme.ERROR + "?", lore);
        gui.confirmations().open(player, new ConfirmationScreen.Confirmation(
                "Remove plugin",
                description,
                "Remove " + plugin.getName() + ".",
                p -> {
                    p.closeInventory();
                    coordinator.removeManagedPlugin(p, plugin);
                },
                p -> openPluginDetails(p, plugin)));
    }

    /**
     * Unlinking throws the source configuration away, so it is confirmed like
     * any other removal. Without a source the button only explains itself.
     */
    private void disableAutoUpdates(Player player, ManagedPlugin plugin) {
        if (!state.checkPermission(player, Permissions.GUI_MANAGE_LINK)) {
            return;
        }
        Optional<UpdateSource> source = items.findMatchingSource(plugin);
        if (source.isEmpty()) {
            applyDisableAutoUpdates(player, plugin);
            return;
        }
        ItemStack description = GuiChrome.button(Material.BARRIER,
                Theme.ERROR + "Disable automatic updates", List.of(
                        Theme.MUTED + "Removes the update source "
                                + Theme.ACCENT + source.get().getName(),
                        Theme.MUTED + "from NU2L. The plugin remains installed."));
        gui.confirmations().open(player, new ConfirmationScreen.Confirmation(
                "Unlink source",
                description,
                "Disable updates for " + plugin.getName() + ".",
                p -> applyDisableAutoUpdates(p, plugin),
                p -> openPluginDetails(p, plugin)));
    }

    private void applyDisableAutoUpdates(Player player, ManagedPlugin plugin) {
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
            player.sendMessage(Theme.ERROR + "NeverUp2Late manages its own filename automatically.");
            return;
        }
        Path path = plugin.getPath();
        if (path == null) {
            player.sendMessage(Theme.ERROR + "Could not determine a file path for this plugin.");
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
                .title(Theme.TITLE + "Rename file")
                .initialText(currentName)
                .inputItem(paper)
                .previewFactory(this::createRenamePreview)
                .validation(value -> {
                    if (value == null || value.trim().isEmpty()) {
                        return Optional.of(Theme.ERROR + "Please enter a valid name.");
                    }
                    return Optional.empty();
                })
                .onConfirm((p, value) -> {
                    requestRename(p, plugin, value);
                })
                .onCancel(p -> p.sendMessage(Theme.WARNING + "Rename cancelled."))
                .build());

        player.sendMessage(Theme.MUTED + "Enter a new file name (\".jar\" will be added automatically).");
    }

    private void requestRename(Player player, ManagedPlugin plugin, String requestedName) {
        if (items.isSelfPlugin(plugin)) {
            player.sendMessage(Theme.ERROR + "NeverUp2Late cannot be renamed from within the GUI.");
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
            player.sendMessage(Theme.ERROR + "NeverUp2Late manages its own filename.");
            return;
        }

        Path path = plugin.getPath();
        if (path == null) {
            player.sendMessage(Theme.ERROR + "Could not determine a file path.");
            return;
        }

        Optional<Plugin> loadedPlugin = plugin.getPlugin();
        if (loadedPlugin.isEmpty()) {
            player.sendMessage(Theme.ERROR + "Plugin must be loaded to determine the data folder name.");
            return;
        }

        File dataFolder = loadedPlugin.get().getDataFolder();
        if (dataFolder == null) {
            player.sendMessage(Theme.ERROR + "Could not find a data folder.");
            return;
        }

        String sanitized = FileNameSanitizer.sanitizeJarFilename(dataFolder.getName());
        if (sanitized == null) {
            player.sendMessage(Theme.ERROR + "Data folder name is invalid.");
            return;
        }

        String current = path.getFileName().toString();
        if (sanitized.equals(current)) {
            player.sendMessage(Theme.WARNING + "Filename is already correct.");
            Feedback.error(player);
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
            meta.setDisplayName(Theme.SUCCESS + sanitized);
            result.setItemMeta(meta);
        }
        return result;
    }
}
