package eu.nurkert.neverUp2Late.gui;

import eu.nurkert.neverUp2Late.Permissions;
import eu.nurkert.neverUp2Late.command.QuickInstallCoordinator;
import eu.nurkert.neverUp2Late.core.PluginContext;
import eu.nurkert.neverUp2Late.gui.anvil.AnvilTextPrompt;
import eu.nurkert.neverUp2Late.update.suggestion.PluginLinkSuggester;
import eu.nurkert.neverUp2Late.update.suggestion.PluginLinkSuggestion;
import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Level;

/**
 * Installing a plugin that is not managed yet: searching Modrinth and Hangar,
 * or entering a direct download link.
 */
final class InstallScreen {

    private static final int INSTALL_INVENTORY_SIZE = 27;
    private static final int INSTALL_INFO_SLOT = 18;
    private static final int INSTALL_SEARCH_SLOT = 20;
    private static final int INSTALL_MANUAL_SLOT = 22;
    private static final int INSTALL_BACK_SLOT = 26;

    private final GuiState state;
    private final PluginOverviewGui gui;
    private final PluginContext context;
    private final QuickInstallCoordinator coordinator;
    private final AnvilTextPrompt anvilTextPrompt;
    private final PluginLinkSuggester linkSuggester;
    private final GuiItems items;
    private final Map<UUID, String> pendingInstallSearches = new ConcurrentHashMap<>();

    InstallScreen(GuiState state, PluginOverviewGui gui) {
        this.state = state;
        this.gui = gui;
        this.context = state.context;
        this.coordinator = state.coordinator;
        this.anvilTextPrompt = state.anvilTextPrompt;
        this.linkSuggester = state.linkSuggester;
        this.items = state.items;
    }

    void forget(UUID playerId) {
        pendingInstallSearches.remove(playerId);
    }

    void openStandaloneSearch(Player player, String query) {
        Objects.requireNonNull(player, "player");

        if (!player.hasPermission(Permissions.GUI_OPEN)
                && !player.hasPermission(Permissions.GUI_MANAGE)) {
            player.sendMessage(ChatColor.RED + "You do not have permission to open the NU2L GUI.");
            return;
        }
        if (!state.checkPermission(player, Permissions.INSTALL)) {
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

    void openStandaloneInstall(Player player,
                               List<PluginLinkSuggestion> suggestions,
                               String searchTerm) {
        List<PluginLinkSuggestion> entries = suggestions == null ? List.of() : suggestions.stream()
                .limit(INSTALL_INFO_SLOT)
                .toList();

        Inventory inventory = Bukkit.createInventory(null, INSTALL_INVENTORY_SIZE,
                ChatColor.DARK_PURPLE + "NU2L – New plugin");

        ItemStack filler = items.createFiller();
        for (int i = 0; i < INSTALL_INVENTORY_SIZE; i++) {
            inventory.setItem(i, filler.clone());
        }

        Map<Integer, PluginLinkSuggestion> mapping = new HashMap<>();
        for (int i = 0; i < entries.size() && i < INSTALL_INFO_SLOT; i++) {
            PluginLinkSuggestion suggestion = entries.get(i);
            inventory.setItem(i, items.createSuggestionItem(suggestion));
            mapping.put(i, suggestion);
        }

        inventory.setItem(INSTALL_INFO_SLOT, createInstallInfoItem(entries.size(), searchTerm));
        inventory.setItem(INSTALL_SEARCH_SLOT, createInstallSearchItem(searchTerm));
        inventory.setItem(INSTALL_MANUAL_SLOT, createManualInstallItem());
        inventory.setItem(INSTALL_BACK_SLOT, createBackToOverviewItem());

        state.openInventories.put(player.getUniqueId(),
                InventorySession.newInstall(inventory, mapping, entries, searchTerm));
        player.openInventory(inventory);
    }

    void handleClick(Player player, InventorySession session, InventoryClickEvent event) {
        int clicked = event.getRawSlot();
        if (clicked == INSTALL_BACK_SLOT) {
            gui.overview().openOverview(player);
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

        state.openInventories.remove(player.getUniqueId());
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

        state.openInventories.remove(player.getUniqueId());
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
        state.openInventories.remove(player.getUniqueId());
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
}
