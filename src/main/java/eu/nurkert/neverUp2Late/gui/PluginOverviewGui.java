package eu.nurkert.neverUp2Late.gui;

import eu.nurkert.neverUp2Late.core.PluginContext;
import eu.nurkert.neverUp2Late.plugin.ManagedPlugin;
import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;

/**
 * Creates a simple chest based GUI that lists all plugins currently managed by NU2L.
 */
public class PluginOverviewGui {

    private static final int MAX_SIZE = 54;

    private final PluginContext context;

    public PluginOverviewGui(PluginContext context) {
        this.context = Objects.requireNonNull(context, "context");
    }

    public void open(Player player) {
        List<ManagedPlugin> plugins = context.getPluginLifecycleManager().getManagedPlugins()
                .stream()
                .sorted(Comparator.comparing(ManagedPlugin::getName, String.CASE_INSENSITIVE_ORDER))
                .toList();

        int size = Math.max(9, ((plugins.size() + 8) / 9) * 9);
        size = Math.min(size, MAX_SIZE);

        Inventory inventory = Bukkit.createInventory(null, size, ChatColor.DARK_PURPLE + "NU2L Plugins");

        ItemStack filler = createFiller();
        for (int i = 0; i < size; i++) {
            inventory.setItem(i, filler.clone());
        }

        boolean truncated = plugins.size() > size;
        for (int slot = 0; slot < size && slot < plugins.size(); slot++) {
            ManagedPlugin plugin = plugins.get(slot);
            inventory.setItem(slot, createPluginItem(plugin));
        }

        player.openInventory(inventory);

        if (truncated) {
            player.sendMessage(ChatColor.YELLOW + "Es werden nur die ersten " + size
                    + " Plugins angezeigt (" + plugins.size() + " insgesamt).");
        }
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
}
