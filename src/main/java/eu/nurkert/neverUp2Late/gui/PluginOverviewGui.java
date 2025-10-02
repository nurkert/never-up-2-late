package eu.nurkert.neverUp2Late.gui;

import eu.nurkert.neverUp2Late.command.QuickInstallCoordinator;
import eu.nurkert.neverUp2Late.core.PluginContext;
import eu.nurkert.neverUp2Late.plugin.ManagedPlugin;
import eu.nurkert.neverUp2Late.update.UpdateSourceRegistry.TargetDirectory;
import eu.nurkert.neverUp2Late.update.UpdateSourceRegistry.UpdateSource;
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

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Creates a simple chest based GUI that lists all plugins currently managed by NU2L.
 */
public class PluginOverviewGui implements Listener {

    private static final int MAX_SIZE = 54;

    private final PluginContext context;
    private final QuickInstallCoordinator coordinator;
    private final Map<UUID, InventorySession> openInventories = new ConcurrentHashMap<>();
    private final Map<UUID, LinkRequest> pendingLinkRequests = new ConcurrentHashMap<>();

    public PluginOverviewGui(PluginContext context, QuickInstallCoordinator coordinator) {
        this.context = Objects.requireNonNull(context, "context");
        this.coordinator = Objects.requireNonNull(coordinator, "coordinator");
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

        Map<Integer, ManagedPlugin> slotMapping = new HashMap<>();
        boolean truncated = plugins.size() > size;
        for (int slot = 0; slot < size && slot < plugins.size(); slot++) {
            ManagedPlugin plugin = plugins.get(slot);
            inventory.setItem(slot, createPluginItem(plugin));
            slotMapping.put(slot, plugin);
        }

        openInventories.put(player.getUniqueId(), new InventorySession(inventory, Map.copyOf(slotMapping)));
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
                findMatchingSource(plugin).ifPresentOrElse(source -> {
                    lore.add(ChatColor.GRAY + "Update-Quelle: " + ChatColor.AQUA + source.getName());
                    lore.add(ChatColor.YELLOW + "Klicke, um den Link zu aktualisieren.");
                }, () -> lore.add(ChatColor.RED + "Keine Update-Quelle verknüpft – klicken, um Link zu setzen."));
            } else {
                lore.add(ChatColor.RED + "Kein JAR-Pfad gefunden – Link nicht möglich.");
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
        ManagedPlugin plugin = session.plugins().get(event.getRawSlot());
        if (plugin == null) {
            return;
        }

        beginLinking(player, plugin);
    }

    @EventHandler
    public void onInventoryClose(InventoryCloseEvent event) {
        openInventories.remove(event.getPlayer().getUniqueId());
    }

    @EventHandler
    public void onPlayerQuit(PlayerQuitEvent event) {
        UUID playerId = event.getPlayer().getUniqueId();
        openInventories.remove(playerId);
        pendingLinkRequests.remove(playerId);
    }

    @EventHandler
    public void onPlayerChat(AsyncPlayerChatEvent event) {
        Player player = event.getPlayer();
        UUID playerId = player.getUniqueId();
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
        player.sendMessage(ChatColor.GREEN + "Verarbeite Link für " + ChatColor.AQUA + request.pluginName() + ChatColor.GREEN + " …");
        coordinator.installForPlugin(player, request.pluginName(), message);
    }

    private void beginLinking(Player player, ManagedPlugin plugin) {
        player.closeInventory();
        openInventories.remove(player.getUniqueId());

        Path path = plugin.getPath();
        if (path == null) {
            player.sendMessage(ChatColor.RED + "Für dieses Plugin konnte kein JAR gefunden werden.");
            return;
        }

        String pluginName = plugin.getName();
        pendingLinkRequests.put(player.getUniqueId(), new LinkRequest(pluginName));

        player.sendMessage(ChatColor.AQUA + "Update-Link für " + pluginName + " festlegen.");
        findMatchingSource(plugin).ifPresentOrElse(source ->
                        player.sendMessage(ChatColor.GRAY + "Aktuelle Quelle: " + ChatColor.AQUA + source.getName()),
                () -> player.sendMessage(ChatColor.GRAY + "Aktuell ist keine Quelle verknüpft."));
        player.sendMessage(ChatColor.YELLOW + "Bitte gib die Download-URL im Chat ein oder tippe 'abbrechen'.");
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

    private record InventorySession(Inventory inventory, Map<Integer, ManagedPlugin> plugins) {
    }

    private record LinkRequest(String pluginName) {
    }
}
