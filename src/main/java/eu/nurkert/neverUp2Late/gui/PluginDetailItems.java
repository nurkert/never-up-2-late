package eu.nurkert.neverUp2Late.gui;

import eu.nurkert.neverUp2Late.persistence.PluginUpdateSettingsRepository.PluginUpdateSettings;
import eu.nurkert.neverUp2Late.persistence.PluginUpdateSettingsRepository.UpdateBehaviour;
import eu.nurkert.neverUp2Late.plugin.ManagedPlugin;
import eu.nurkert.neverUp2Late.update.UpdateSourceRegistry.UpdateSource;
import eu.nurkert.neverUp2Late.util.FileNameSanitizer;
import org.bukkit.ChatColor;
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

    ItemStack createLoadItem(ManagedPlugin plugin) {
        if (items.isSelfPlugin(plugin)) {
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

    ItemStack createLinkItem(ManagedPlugin plugin) {
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

    ItemStack createDisableUpdatesItem(ManagedPlugin plugin) {
        Optional<UpdateSource> source = items.findMatchingSource(plugin);
        PluginUpdateSettings settings = items.readSettings(plugin);

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

    ItemStack createUpdateSettingsItem(ManagedPlugin plugin) {
        PluginUpdateSettings settings = items.readSettings(plugin);
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

    ItemStack createRemoveItem(ManagedPlugin plugin) {
        if (items.isSelfPlugin(plugin)) {
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

    ItemStack createRenameItem(ManagedPlugin plugin) {
        if (items.isSelfPlugin(plugin)) {
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

    ItemStack createBackItem() {
        ItemStack item = new ItemStack(Material.ARROW);
        ItemMeta meta = item.getItemMeta();
        if (meta != null) {
            meta.setDisplayName(ChatColor.YELLOW + "Back to overview");
            meta.setLore(List.of(ChatColor.GRAY + "Closes this view and shows all plugins."));
            item.setItemMeta(meta);
        }
        return item;
    }

    ItemStack createCheckItem(ManagedPlugin plugin) {
        boolean linked = items.findMatchingSource(plugin).isPresent();
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

    Optional<ItemStack> createRollbackItem(ManagedPlugin plugin) {
        Optional<UpdateSource> source = items.findMatchingSource(plugin);
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
}
