package eu.nurkert.neverUp2Late.gui;

import eu.nurkert.neverUp2Late.util.Feedback;
import eu.nurkert.neverUp2Late.util.Theme;
import eu.nurkert.neverUp2Late.Permissions;
import eu.nurkert.neverUp2Late.core.PluginContext;
import eu.nurkert.neverUp2Late.gui.anvil.AnvilTextPrompt;
import eu.nurkert.neverUp2Late.plugin.ManagedPlugin;
import eu.nurkert.neverUp2Late.update.UpdateSourceRegistry.UpdateSource;
import eu.nurkert.neverUp2Late.util.FileNameSanitizer;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.Plugin;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Level;

/**
 * The plugin list the GUI opens on: a page of plugins, the state of everything
 * in the header, and the tools that act on all plugins at once - check
 * everything now, tidy up all jar names, and install a new plugin.
 */
final class OverviewScreen {

    private final GuiState state;
    private final PluginOverviewGui gui;
    private final PluginContext context;
    private final GuiItems items;
    /** The page, filter and search each player last looked at. */
    private final Map<UUID, OverviewView> views = new ConcurrentHashMap<>();

    OverviewScreen(GuiState state, PluginOverviewGui gui) {
        this.state = state;
        this.gui = gui;
        this.context = state.context;
        this.items = state.items;
    }

    void resetView(Player player) {
        views.remove(player.getUniqueId());
    }

    void forget(UUID playerId) {
        views.remove(playerId);
    }

    /** Opens the overview where the player last left it. */
    void openOverview(Player player) {
        openOverview(player, views.getOrDefault(player.getUniqueId(), OverviewView.DEFAULT));
    }

    void openOverview(Player player, OverviewView requested) {
        List<ManagedPlugin> plugins = context.getPluginLifecycleManager().getManagedPlugins()
                .stream()
                .sorted(Comparator.comparing(ManagedPlugin::getName, String.CASE_INSENSITIVE_ORDER))
                .toList();
        List<PluginStatus> statuses = plugins.stream().map(items::statusOf).toList();
        PluginSummary summary = PluginSummary.of(statuses);

        List<ManagedPlugin> shown = new ArrayList<>();
        List<PluginStatus> shownStatuses = new ArrayList<>();
        for (int i = 0; i < plugins.size(); i++) {
            if (requested.matches(plugins.get(i).getName(), statuses.get(i))) {
                shown.add(plugins.get(i));
                shownStatuses.add(statuses.get(i));
            }
        }

        // Every plugin gets a page. This screen used to grow until the chest
        // was full and then drop whatever did not fit, with only a chat line
        // saying so.
        Pagination pagination = Pagination.of(shown.size(), GuiLayout.CONTENT_SIZE, requested.page());
        OverviewView view = requested.withPage(pagination.page());
        views.put(player.getUniqueId(), view);

        Inventory inventory = Bukkit.createInventory(null, GuiLayout.SIZE, GuiChrome.title("Plugins"));
        GuiChrome.drawFrame(inventory, summary.frameState());

        inventory.setItem(GuiLayout.SUMMARY_SLOT, createSummaryItem(summary, shown.size(), view));
        inventory.setItem(GuiLayout.TOOL_LEFT_SLOT, createCheckAllButton());
        inventory.setItem(GuiLayout.TOOL_RIGHT_SLOT, createInstallButton());
        inventory.setItem(GuiLayout.TOOL_FAR_RIGHT_SLOT, createCleanupButton());

        Map<Integer, ManagedPlugin> slotMapping = new HashMap<>();
        for (int index = pagination.fromIndex(); index < pagination.toIndex(); index++) {
            int slot = GuiLayout.contentSlot(index - pagination.fromIndex());
            ManagedPlugin plugin = shown.get(index);
            boolean linked = shownStatuses.get(index) != PluginStatus.NOT_LINKED;
            List<String> hints = List.of(linked
                    ? GuiChrome.hints("Left-click: details", "Right-click: check now")
                    : GuiChrome.hints("Left-click: details"));
            inventory.setItem(slot, items.createPluginItem(plugin, hints));
            slotMapping.put(slot, plugin);
        }
        if (shown.isEmpty()) {
            inventory.setItem(GuiLayout.EMPTY_NOTICE_SLOT, createEmptyNotice(view, plugins.isEmpty()));
        }

        GuiChrome.drawPager(inventory, pagination, Theme.MUTED + shown.size() + " of "
                + plugins.size() + " plugins shown");
        inventory.setItem(GuiLayout.FILTER_SLOT, GuiChrome.filter(view.filter()));
        inventory.setItem(GuiLayout.SEARCH_SLOT, GuiChrome.search("plugins", view.query()));
        inventory.setItem(GuiLayout.CLOSE_SLOT, GuiChrome.close());

        state.openInventories.put(player.getUniqueId(), InventorySession.overview(inventory, slotMapping, view));
        player.openInventory(inventory);
    }

    void handleClick(Player player, InventorySession session, InventoryClickEvent event) {
        int slot = event.getRawSlot();
        OverviewView view = session.overviewView() != null ? session.overviewView() : OverviewView.DEFAULT;

        switch (slot) {
            case GuiLayout.TOOL_LEFT_SLOT -> {
                Feedback.click(player);
                checkAllSourcesNow(player);
            }
            case GuiLayout.TOOL_RIGHT_SLOT -> {
                Feedback.click(player);
                beginStandaloneInstall(player);
            }
            case GuiLayout.TOOL_FAR_RIGHT_SLOT -> {
                Feedback.click(player);
                confirmCleanupAllJarNames(player);
            }
            case GuiLayout.PREVIOUS_SLOT -> {
                if (isPagerArrow(event)) {
                    Feedback.pageTurn(player);
                    openOverview(player, view.withPage(view.page() - 1));
                }
            }
            case GuiLayout.NEXT_SLOT -> {
                if (isPagerArrow(event)) {
                    Feedback.pageTurn(player);
                    openOverview(player, view.withPage(view.page() + 1));
                }
            }
            case GuiLayout.FILTER_SLOT -> {
                Feedback.click(player);
                OverviewFilter next = event.isRightClick() ? view.filter().previous() : view.filter().next();
                openOverview(player, view.withFilter(next));
            }
            case GuiLayout.SEARCH_SLOT -> {
                Feedback.click(player);
                if (event.isRightClick() && view.query() != null) {
                    openOverview(player, view.withQuery(null));
                } else {
                    promptSearch(player, view);
                }
            }
            default -> {
                if (slot == GuiLayout.EMPTY_NOTICE_SLOT && session.plugins().isEmpty()) {
                    if (view.isNarrowed()) {
                        Feedback.click(player);
                        openOverview(player, OverviewView.DEFAULT);
                    }
                    return;
                }
                ManagedPlugin plugin = session.plugins().get(slot);
                if (plugin == null) {
                    return;
                }
                if (event.isRightClick()) {
                    // The very same action as the button on the plugin's own screen.
                    Feedback.click(player);
                    gui.details().checkSourceNow(player, plugin);
                    return;
                }
                Feedback.pageTurn(player);
                gui.details().openPluginDetails(player, plugin);
            }
        }
    }

    static boolean isPagerArrow(InventoryClickEvent event) {
        ItemStack clicked = event.getCurrentItem();
        return clicked != null && clicked.getType() == Material.SPECTRAL_ARROW;
    }

    /** Asks for a name in the anvil; an empty answer clears the search. */
    private void promptSearch(Player player, OverviewView view) {
        state.openInventories.remove(player.getUniqueId());
        player.closeInventory();
        state.anvilTextPrompt.open(player, AnvilTextPrompt.Prompt.builder()
                .title(Theme.TITLE + "Search plugins")
                .initialText(view.query() != null ? view.query() : "")
                .validation(value -> Optional.empty())
                .onConfirm((p, value) -> {
                    Feedback.pageTurn(p);
                    openOverview(p, view.withQuery(value));
                })
                .onCancel(p -> openOverview(p, view))
                .build());
    }

    private ItemStack createSummaryItem(PluginSummary summary, int shownCount, OverviewView view) {
        List<String> lore = new ArrayList<>();
        lore.add(summary.frameState().describe());
        if (summary.notLinked() > 0) {
            lore.add(Theme.MUTED + "Not linked: " + Theme.TEXT + summary.notLinked()
                    + Theme.SUBTLE + " (no automatic updates)");
        }
        if (view.isNarrowed()) {
            lore.add(" ");
            lore.add(Theme.MUTED + "Showing " + Theme.TEXT + shownCount + Theme.MUTED + " of "
                    + Theme.TEXT + summary.total());
            if (view.filter() != OverviewFilter.ALL) {
                lore.add(Theme.MUTED + "Filter: " + Theme.ACCENT + view.filter().label());
            }
            if (view.query() != null) {
                lore.add(Theme.MUTED + "Search: " + Theme.ACCENT + view.query());
            }
        }
        return GuiChrome.button(Material.BEACON, Theme.TEXT + summary.headline(), lore);
    }

    private ItemStack createEmptyNotice(OverviewView view, boolean nothingManaged) {
        if (nothingManaged) {
            return GuiChrome.button(Material.STRUCTURE_VOID, Theme.MUTED + "No plugins managed yet",
                    List.of(Theme.SUBTLE + "Install one with the star above."));
        }
        List<String> lore = new ArrayList<>();
        if (view.filter() != OverviewFilter.ALL) {
            lore.add(Theme.MUTED + "Filter: " + Theme.ACCENT + view.filter().label());
        }
        if (view.query() != null) {
            lore.add(Theme.MUTED + "Search: " + Theme.ACCENT + view.query());
        }
        lore.add(" ");
        lore.add(GuiChrome.hints("Click: show all plugins"));
        return GuiChrome.button(Material.STRUCTURE_VOID, Theme.MUTED + "No plugins match", lore);
    }

    private ItemStack createCheckAllButton() {
        return GuiChrome.button(Material.SPYGLASS, Theme.ACCENT + "Check for updates now", List.of(
                Theme.MUTED + "Asks every source what it has,",
                Theme.MUTED + "instead of waiting for the next round.",
                Theme.SUBTLE + "Nothing is installed without your settings allowing it.",
                " ",
                GuiChrome.hints("Click: check all")));
    }

    private ItemStack createInstallButton() {
        return GuiChrome.button(Material.NETHER_STAR, Theme.ACCENT + "Install new plugin…", List.of(
                Theme.MUTED + "Search Modrinth and Hangar,",
                Theme.MUTED + "or enter a download URL.",
                Theme.MUTED + "Installation runs directly through NU2L.",
                " ",
                GuiChrome.hints("Click: open")));
    }

    private ItemStack createCleanupButton() {
        return GuiChrome.button(Material.RECOVERY_COMPASS, Theme.HEADING + "Tidy up jar names", List.of(
                Theme.MUTED + "Renames all plugin files to match",
                Theme.MUTED + "their data folder names.",
                Theme.WARNING + "Ensures consistent and clean naming.",
                " ",
                GuiChrome.hints("Click: review and confirm")));
    }

    private void checkAllSourcesNow(Player player) {
        if (!state.checkPermission(player, Permissions.INSTALL)) {
            return;
        }
        List<UpdateSource> sources = List.copyOf(context.getUpdateSourceRegistry().getSources());
        if (sources.isEmpty()) {
            player.sendMessage(Theme.WARNING + "No update sources are configured yet.");
            Feedback.error(player);
            return;
        }
        player.closeInventory();
        context.getUpdateHandler().runJobsNow(sources, player);
    }

    private void beginStandaloneInstall(Player player) {
        if (!state.checkPermission(player, Permissions.INSTALL)) {
            return;
        }
        Feedback.pageTurn(player);
        gui.install().openStandaloneInstall(player, List.of(), null);
        player.sendMessage(Theme.MUTED + "Search for new plugins on Modrinth or Hangar, or provide a direct link (including SpigotMC resources).");
    }

    /**
     * Asks first. This renames files across every plugin and deletes any
     * duplicate jars it finds, from a single click - while removing one
     * single plugin has always required a confirmation.
     */
    private void confirmCleanupAllJarNames(Player player) {
        if (!state.checkPermission(player, Permissions.GUI_MANAGE_RENAME)) {
            return;
        }
        List<ManagedPlugin> affected = context.getPluginLifecycleManager().getManagedPlugins().stream()
                .filter(p -> !items.isSelfPlugin(p))
                .filter(this::needsRename)
                .toList();
        List<String> lore = new ArrayList<>();
        if (affected.isEmpty()) {
            // No renames - but the action still deletes duplicate jars, and
            // deleting files is exactly what needs asking about.
            lore.add(Theme.MUTED + "All filenames are already tidy.");
            lore.add(Theme.WARNING + "Continuing removes duplicate jars");
            lore.add(Theme.WARNING + "of the same plugin.");
        } else {
            lore.add(Theme.MUTED + "This renames " + Theme.TEXT + affected.size() + Theme.MUTED + " jar file(s):");
            affected.stream().limit(8).forEach(p -> lore.add(Theme.MUTED + "  "
                    + p.getPath().getFileName() + Theme.SUBTLE + " " + Theme.ARROW + " " + Theme.TEXT
                    + desiredFileName(p)));
            if (affected.size() > 8) {
                lore.add(Theme.SUBTLE + "  … and " + (affected.size() - 8) + " more");
            }
            lore.add(" ");
            lore.add(Theme.WARNING + "Duplicate jars of the same plugin are deleted.");
        }
        ItemStack description = GuiChrome.button(Material.RECOVERY_COMPASS,
                Theme.HEADING + "Tidy up jar names", lore);
        gui.confirmations().open(player, new ConfirmationScreen.Confirmation(
                "Tidy jar names",
                description,
                affected.isEmpty() ? "Remove duplicate jars." : "Rename " + affected.size() + " file(s).",
                this::cleanupAllJarNames,
                this::openOverview));
    }

    void cleanupAllJarNames(Player player) {
        if (!state.checkPermission(player, Permissions.GUI_MANAGE_RENAME)) {
            return;
        }
        List<ManagedPlugin> plugins = context.getPluginLifecycleManager().getManagedPlugins().stream()
                .filter(p -> !items.isSelfPlugin(p))
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
            player.sendMessage(Theme.SUCCESS + "Renamed " + renamed
                    + " plugin file(s) and removed duplicates.");
            player.sendMessage(Theme.MUTED
                    + "The plugins keep running; the new names take effect on the next restart.");
        } else {
            player.sendMessage(Theme.SUCCESS + "All filenames are already correct; duplicates cleaned up.");
        }
        Feedback.success(player);
        if (blocked > 0) {
            player.sendMessage(Theme.WARNING + String.valueOf(blocked)
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
}
