package eu.nurkert.neverUp2Late.gui;

import eu.nurkert.neverUp2Late.Permissions;
import eu.nurkert.neverUp2Late.core.PluginContext;
import eu.nurkert.neverUp2Late.plugin.ManagedPlugin;
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
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.logging.Level;

/**
 * The plugin list the GUI opens on, with its control row: check everything now,
 * tidy up all jar names, and install a new plugin.
 */
final class OverviewScreen {

    /** Bottom row reserved for controls, so no plugin ever shares a slot with a button. */
    private static final int CONTROL_ROW_SIZE = 9;

    private final GuiState state;
    private final PluginOverviewGui gui;
    private final PluginContext context;
    private final ChatPrompts prompts;
    private final GuiItems items;

    OverviewScreen(GuiState state, PluginOverviewGui gui) {
        this.state = state;
        this.gui = gui;
        this.context = state.context;
        this.prompts = state.prompts;
        this.items = state.items;
    }

    void openOverview(Player player) {
        List<ManagedPlugin> plugins = context.getPluginLifecycleManager().getManagedPlugins()
                .stream()
                .sorted(Comparator.comparing(ManagedPlugin::getName, String.CASE_INSENSITIVE_ORDER))
                .toList();

        int requiredSlots = plugins.size() + CONTROL_ROW_SIZE;
        int size = Math.max(18, ((requiredSlots + 8) / 9) * 9);
        size = Math.min(size, GuiState.MAX_SIZE);

        Inventory inventory = Bukkit.createInventory(null, size, ChatColor.DARK_PURPLE + "NU2L Plugins");

        ItemStack filler = items.createFiller();
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
            inventory.setItem(slot, items.createPluginItem(plugin));
            slotMapping.put(slot, plugin);
        }

        inventory.setItem(size - 3, createCheckAllButton());
        inventory.setItem(size - 2, createCleanupButton());
        inventory.setItem(size - 1, createInstallButton());

        state.openInventories.put(player.getUniqueId(), InventorySession.overview(inventory, slotMapping));
        player.openInventory(inventory);

        if (truncated) {
            player.sendMessage(ChatColor.YELLOW + "Only the first " + availableSlots
                    + " plugins are shown (" + plugins.size() + " total).");
        }
    }

    void handleClick(Player player, InventorySession session, InventoryClickEvent event) {
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
            confirmCleanupAllJarNames(player);
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

        gui.details().openPluginDetails(player, plugin);
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

    private void checkAllSourcesNow(Player player) {
        if (!state.checkPermission(player, Permissions.INSTALL)) {
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

    private void beginStandaloneInstall(Player player) {
        if (!state.checkPermission(player, Permissions.INSTALL)) {
            return;
        }
        gui.install().openStandaloneInstall(player, List.of(), null);
        player.sendMessage(ChatColor.GRAY + "Search for new plugins on Modrinth or Hangar, or provide a direct link (including SpigotMC resources).");
    }

    /**
     * Asks first. This renames files across every plugin and deletes any
     * duplicate jars it finds, from a single click - while removing one
     * single plugin has always required typing "yes".
     */
    private void confirmCleanupAllJarNames(Player player) {
        if (!state.checkPermission(player, Permissions.GUI_MANAGE_RENAME)) {
            return;
        }
        List<ManagedPlugin> affected = context.getPluginLifecycleManager().getManagedPlugins().stream()
                .filter(p -> !items.isSelfPlugin(p))
                .filter(this::needsRename)
                .toList();
        player.closeInventory();
        if (affected.isEmpty()) {
            // No renames - but the action still deletes duplicate jars, and
            // deleting files is exactly what needs asking about.
            player.sendMessage(ChatColor.GOLD + "All filenames are already tidy.");
            player.sendMessage(ChatColor.GRAY + "Continuing removes duplicate jars of the same plugin.");
            player.sendMessage(ChatColor.YELLOW + "Type " + ChatColor.WHITE + "yes"
                    + ChatColor.YELLOW + " to go ahead.");
            prompts.pendingCleanupConfirmations.add(player.getUniqueId());
            prompts.armPrompt(player);
            return;
        }
        player.sendMessage(ChatColor.GOLD + "This renames " + affected.size() + " jar file(s):");
        affected.stream().limit(8).forEach(p -> player.sendMessage(ChatColor.GRAY + "  "
                + p.getPath().getFileName() + ChatColor.DARK_GRAY + " → " + ChatColor.WHITE
                + desiredFileName(p)));
        if (affected.size() > 8) {
            player.sendMessage(ChatColor.DARK_GRAY + "  … and " + (affected.size() - 8) + " more");
        }
        player.sendMessage(ChatColor.GRAY + "Duplicate jars of the same plugin are deleted.");
        player.sendMessage(ChatColor.YELLOW + "Type " + ChatColor.WHITE + "yes"
                + ChatColor.YELLOW + " to go ahead.");
        prompts.pendingCleanupConfirmations.add(player.getUniqueId());
        prompts.armPrompt(player);
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
}
