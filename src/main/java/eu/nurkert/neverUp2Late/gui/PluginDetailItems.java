package eu.nurkert.neverUp2Late.gui;

import eu.nurkert.neverUp2Late.util.Theme;
import eu.nurkert.neverUp2Late.persistence.PluginUpdateSettingsRepository.PluginUpdateSettings;
import eu.nurkert.neverUp2Late.persistence.PluginUpdateSettingsRepository.UpdateBehaviour;
import eu.nurkert.neverUp2Late.plugin.ManagedPlugin;
import eu.nurkert.neverUp2Late.update.UpdateSourceRegistry.UpdateSource;
import eu.nurkert.neverUp2Late.util.FileNameSanitizer;
import org.bukkit.Material;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.plugin.Plugin;

import java.io.File;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * The buttons on the per-plugin screen, each reflecting what that plugin currently allows.
 */
final class PluginDetailItems {

    private final GuiItems items;

    PluginDetailItems(GuiItems items) {
        this.items = items;
    }

    ItemStack createEnableItem(ManagedPlugin plugin) {
        if (items.isSelfPlugin(plugin)) {
            ItemStack item = new ItemStack(Material.BARRIER);
            ItemMeta meta = item.getItemMeta();
            if (meta != null) {
                meta.setDisplayName(Theme.SUBTLE + "Managed by NeverUp2Late");
                meta.setLore(List.of(
                        Theme.MUTED + "NeverUp2Late keeps itself enabled.",
                        Theme.MUTED + "Restart the server to reload it."
                ));
                item.setItemMeta(meta);
            }
            return item;
        }
        ItemStack item = new ItemStack(plugin.isEnabled() ? Material.RED_DYE : Material.LIME_DYE);
        ItemMeta meta = item.getItemMeta();
        if (meta != null) {
            if (!plugin.isLoaded()) {
                meta.setDisplayName(Theme.SUBTLE + "Cannot be enabled");
                meta.setLore(List.of(
                        Theme.MUTED + "The plugin is not currently loaded.",
                        Theme.MUTED + "Load it to enable it."
                ));
            } else if (plugin.isEnabled()) {
                meta.setDisplayName(Theme.ERROR + "Disable plugin");
                meta.setLore(List.of(
                        Theme.MUTED + "Stops the plugin without unloading it."
                ));
            } else {
                meta.setDisplayName(Theme.SUCCESS + "Enable plugin");
                meta.setLore(List.of(
                        Theme.MUTED + "Starts the plugin after it has been loaded."
                ));
            }
            item.setItemMeta(meta);
        }
        return item;
    }

    ItemStack createLoadItem(ManagedPlugin plugin) {
        if (items.isSelfPlugin(plugin)) {
            ItemStack item = new ItemStack(Material.BARRIER);
            ItemMeta meta = item.getItemMeta();
            if (meta != null) {
                meta.setDisplayName(Theme.SUBTLE + "Cannot be unloaded");
                meta.setLore(List.of(
                        Theme.MUTED + "NeverUp2Late must stay loaded",
                        Theme.MUTED + "while it manages updates."
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
                meta.setDisplayName(Theme.HEADING + "Unload plugin");
                meta.setLore(List.of(
                        Theme.MUTED + "Fully unloads the plugin.",
                        Theme.MUTED + "Frees resources and unregisters listeners."
                ));
            } else {
                meta.setDisplayName(Theme.ACCENT + "Load plugin");
                meta.setLore(List.of(
                        Theme.MUTED + "Loads the plugin from the jar file and enables it."
                ));
            }
            item.setItemMeta(meta);
        }
        return item;
    }

    ItemStack createLinkItem(ManagedPlugin plugin) {
        ItemStack item = new ItemStack(Material.PAPER);
        ItemMeta meta = item.getItemMeta();
        if (meta != null) {
            meta.setDisplayName(Theme.ACCENT + "Set update source");
            meta.setLore(List.of(
                    Theme.MUTED + "Click to enter a new download URL.",
                    Theme.MUTED + "Custom links allow installation and updates."
            ));
            item.setItemMeta(meta);
        }
        return item;
    }

    ItemStack createDisableUpdatesItem(ManagedPlugin plugin) {
        Optional<UpdateSource> source = items.findMatchingSource(plugin);
        PluginUpdateSettings settings = items.readSettings(plugin);

        Material material = source.isPresent() ? Material.BARRIER : Material.LIME_DYE;
        ItemStack item = new ItemStack(material);
        ItemMeta meta = item.getItemMeta();
        if (meta != null) {
            if (source.isPresent()) {
                meta.setDisplayName(Theme.ERROR + "Disable automatic updates");
                List<String> lore = new ArrayList<>();
                lore.add(Theme.MUTED + "Removes the update source from NU2L.");
                lore.add(Theme.MUTED + "The plugin remains installed.");
                String sourceName = source.get().getName();
                if (sourceName != null && !sourceName.isBlank()) {
                    lore.add(Theme.SUBTLE + "Source: " + Theme.ACCENT + sourceName);
                }
                lore.add(Theme.WARNING + "Click to disable updates.");
                meta.setLore(lore);
            } else {
                meta.setDisplayName(Theme.SUCCESS + "No update source linked");
                List<String> lore = new ArrayList<>();
                if (!settings.autoUpdateEnabled()) {
                    lore.add(Theme.MUTED + "Automatic updates are disabled.");
                } else {
                    lore.add(Theme.MUTED + "This plugin is not currently updating automatically.");
                }
                lore.add(Theme.MUTED + "Use \"Set update link\" to enable updates.");
                meta.setLore(lore);
            }
            item.setItemMeta(meta);
        }
        return item;
    }

    ItemStack createUpdateSettingsItem(ManagedPlugin plugin) {
        PluginUpdateSettings settings = items.readSettings(plugin);
        ItemStack item = new ItemStack(Material.COMPARATOR);
        ItemMeta meta = item.getItemMeta();
        if (meta != null) {
            meta.setDisplayName(Theme.TITLE_LIGHT + "Adjust update behaviour");
            String current;
            if (!settings.autoUpdateEnabled()) {
                current = Theme.ERROR + "Automatic updates disabled";
            } else if (settings.behaviour() == UpdateBehaviour.AUTO_RELOAD) {
                current = Theme.SUCCESS + "Automatically reload";
            } else {
                current = Theme.HEADING + "Restart server after updates";
            }
            meta.setLore(List.of(
                    Theme.MUTED + "Current mode:",
                    Theme.MUTED + " → " + current,
                    Theme.MUTED + "File name: "
                            + (settings.retainUpstreamFilename()
                            ? Theme.SUCCESS + "Upstream"
                            : Theme.WARNING + "Configured"),
                    Theme.WARNING + "Left/Right click for options"
            ));
            item.setItemMeta(meta);
        }
        return item;
    }

    ItemStack createRemoveItem(ManagedPlugin plugin) {
        if (items.isSelfPlugin(plugin)) {
            ItemStack item = new ItemStack(Material.BARRIER);
            ItemMeta meta = item.getItemMeta();
            if (meta != null) {
                meta.setDisplayName(Theme.SUBTLE + "Cannot be removed");
                meta.setLore(List.of(
                        Theme.MUTED + "NeverUp2Late cannot remove itself."
                ));
                item.setItemMeta(meta);
            }
            return item;
        }
        ItemStack item = new ItemStack(Material.TNT);
        ItemMeta meta = item.getItemMeta();
        if (meta != null) {
            meta.setDisplayName(Theme.ERROR + "Remove plugin");
            meta.setLore(List.of(
                    Theme.MUTED + "Unloads the plugin, deletes the file",
                    Theme.MUTED + "and removes the update source."
            ));
            item.setItemMeta(meta);
        }
        return item;
    }

    ItemStack createRenameItem(ManagedPlugin plugin) {
        if (items.isSelfPlugin(plugin)) {
            ItemStack item = new ItemStack(Material.NAME_TAG);
            ItemMeta meta = item.getItemMeta();
            if (meta != null) {
                meta.setDisplayName(Theme.SUBTLE + "Cannot be renamed");
                meta.setLore(List.of(
                        Theme.MUTED + "NeverUp2Late manages its own file name."
                ));
                item.setItemMeta(meta);
            }
            return item;
        }
        ItemStack item = new ItemStack(Material.NAME_TAG);
        ItemMeta meta = item.getItemMeta();
        if (meta != null) {
            meta.setDisplayName(Theme.ACCENT + "Rename file");
            meta.setLore(List.of(
                    Theme.MUTED + "Changes the jar file name",
                    Theme.MUTED + "and updates the linked source."
            ));
            item.setItemMeta(meta);
        }
        return item;
    }

    Optional<ItemStack> createQuickRenameItem(ManagedPlugin plugin) {
        if (items.isSelfPlugin(plugin)) {
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
            meta.setDisplayName(Theme.SUCCESS + "Match data folder name");
            meta.setLore(List.of(
                    Theme.MUTED + "Automatically renames the file,",
                    Theme.MUTED + "so it matches the data folder name.",
                    " ",
                    Theme.SUBTLE + "Target: " + Theme.ACCENT + sanitized
            ));
            item.setItemMeta(meta);
        }
        return Optional.of(item);
    }

    ItemStack createBackItem() {
        ItemStack item = new ItemStack(Material.ARROW);
        ItemMeta meta = item.getItemMeta();
        if (meta != null) {
            meta.setDisplayName(Theme.WARNING + "Back to overview");
            meta.setLore(List.of(Theme.MUTED + "Closes this view and shows all plugins."));
            item.setItemMeta(meta);
        }
        return item;
    }

    ItemStack createCheckItem(ManagedPlugin plugin) {
        boolean linked = items.findMatchingSource(plugin).isPresent();
        ItemStack item = new ItemStack(linked ? Material.SPYGLASS : Material.GRAY_DYE);
        ItemMeta meta = item.getItemMeta();
        if (meta != null) {
            meta.setDisplayName((linked ? Theme.ACCENT : Theme.SUBTLE) + "Check for updates now");
            meta.setLore(List.of(linked
                    ? Theme.MUTED + "Ask this plugin's source right away."
                    : Theme.MUTED + "Link an update source first."));
            item.setItemMeta(meta);
        }
        return item;
    }

    Optional<ItemStack> createRollbackItem(ManagedPlugin plugin) {
        Optional<UpdateSource> source = items.findMatchingSource(plugin);
        if (source.isEmpty()) {
            return Optional.empty();
        }
        ItemStack item = new ItemStack(Material.CLOCK);
        ItemMeta meta = item.getItemMeta();
        if (meta != null) {
            meta.setDisplayName(Theme.HEADING + "Restore previous version");
            meta.setLore(List.of(
                    Theme.MUTED + "Puts the last backup back in place.",
                    Theme.MUTED + "Takes effect after a restart.",
                    Theme.SUBTLE + "The restored version is kept - it will not be",
                    Theme.SUBTLE + "replaced again until something newer appears."));
            item.setItemMeta(meta);
        }
        return Optional.of(item);
    }
}
