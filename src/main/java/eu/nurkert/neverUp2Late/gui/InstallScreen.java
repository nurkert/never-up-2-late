package eu.nurkert.neverUp2Late.gui;

import eu.nurkert.neverUp2Late.util.Feedback;
import eu.nurkert.neverUp2Late.util.Theme;
import eu.nurkert.neverUp2Late.Permissions;
import eu.nurkert.neverUp2Late.command.QuickInstallCoordinator;
import eu.nurkert.neverUp2Late.core.PluginContext;
import eu.nurkert.neverUp2Late.gui.anvil.AnvilTextPrompt;
import eu.nurkert.neverUp2Late.update.suggestion.PluginLinkSuggester;
import eu.nurkert.neverUp2Late.update.suggestion.PluginLinkSuggestion;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;

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
            player.sendMessage(Theme.ERROR + "You do not have permission to open the NU2L GUI.");
            return;
        }
        if (!state.checkPermission(player, Permissions.INSTALL)) {
            return;
        }

        String trimmed = query != null ? query.trim() : "";
        if (trimmed.length() < 3) {
            player.sendMessage(Theme.ERROR + "Please enter at least three characters.");
            return;
        }

        UUID playerId = player.getUniqueId();
        pendingInstallSearches.put(playerId, trimmed);
        player.sendMessage(Theme.MUTED + "Searching for " + Theme.ACCENT + trimmed + Theme.MUTED + " …");

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
        openStandaloneInstall(player, suggestions, searchTerm, 0);
    }

    /** One page of search results; results beyond one chest used to be dropped. */
    private void openStandaloneInstall(Player player,
                                       List<PluginLinkSuggestion> suggestions,
                                       String searchTerm,
                                       int requestedPage) {
        List<PluginLinkSuggestion> entries = suggestions == null ? List.of() : List.copyOf(suggestions);
        Pagination pagination = Pagination.of(entries.size(), GuiLayout.CONTENT_SIZE, requestedPage);

        Inventory inventory = Bukkit.createInventory(null, GuiLayout.SIZE, GuiChrome.title("New plugin"));
        GuiChrome.drawFrame(inventory, items.summarize(
                context.getPluginLifecycleManager().getManagedPlugins()).frameState());

        Map<Integer, PluginLinkSuggestion> mapping = new HashMap<>();
        for (int index = pagination.fromIndex(); index < pagination.toIndex(); index++) {
            int slot = GuiLayout.contentSlot(index - pagination.fromIndex());
            PluginLinkSuggestion suggestion = entries.get(index);
            inventory.setItem(slot, items.createSuggestionItem(suggestion));
            mapping.put(slot, suggestion);
        }

        inventory.setItem(GuiLayout.SUMMARY_SLOT, createInstallInfoItem(entries.size(), searchTerm));
        inventory.setItem(GuiLayout.TOOL_RIGHT_SLOT, createManualInstallItem());
        inventory.setItem(GuiLayout.BACK_SLOT, GuiChrome.back("the plugin list"));
        if (!entries.isEmpty()) {
            GuiChrome.drawPager(inventory, pagination, Theme.MUTED + entries.size() + " result(s)");
        }
        inventory.setItem(GuiLayout.SEARCH_SLOT, GuiChrome.search("plugins on Modrinth and Hangar", searchTerm));
        inventory.setItem(GuiLayout.CLOSE_SLOT, GuiChrome.close());

        state.openInventories.put(player.getUniqueId(),
                InventorySession.newInstall(inventory, mapping, entries, searchTerm, pagination.page()));
        player.openInventory(inventory);
    }

    void handleClick(Player player, InventorySession session, InventoryClickEvent event) {
        int clicked = event.getRawSlot();
        if (clicked == GuiLayout.BACK_SLOT) {
            Feedback.pageTurn(player);
            gui.overview().openOverview(player);
            return;
        }
        if (clicked == GuiLayout.SEARCH_SLOT) {
            Feedback.click(player);
            if (event.isRightClick() && session.searchTerm() != null) {
                openStandaloneInstall(player, List.of(), null);
            } else {
                promptStandaloneSearch(player, session);
            }
            return;
        }
        if (clicked == GuiLayout.TOOL_RIGHT_SLOT) {
            Feedback.click(player);
            promptStandaloneManualInstall(player, session);
            return;
        }
        if (clicked == GuiLayout.PREVIOUS_SLOT || clicked == GuiLayout.NEXT_SLOT) {
            if (OverviewScreen.isPagerArrow(event)) {
                Feedback.pageTurn(player);
                int delta = clicked == GuiLayout.NEXT_SLOT ? 1 : -1;
                openStandaloneInstall(player, session.orderedSuggestions(), session.searchTerm(),
                        session.page() + delta);
            }
            return;
        }
        PluginLinkSuggestion suggestion = session.suggestions().get(clicked);
        if (suggestion != null) {
            Feedback.click(player);
            installStandaloneSuggestion(player, suggestion);
        }
    }

    private ItemStack createInstallInfoItem(int resultCount, String searchTerm) {
        List<String> lore = new ArrayList<>();
        if (searchTerm == null || searchTerm.isBlank()) {
            lore.add(Theme.MUTED + "Use the search to browse Modrinth or Hangar.");
            lore.add(Theme.MUTED + "Manual links support SpigotMC via the Spiget API.");
        } else if (resultCount == 0) {
            lore.add(Theme.WARNING + "No results for: " + Theme.TEXT + searchTerm);
            lore.add(Theme.MUTED + "Adjust the search term or try a direct link.");
        } else {
            lore.add(Theme.SUCCESS + "Projects found: " + Theme.TEXT + resultCount);
            lore.add(Theme.MUTED + "Click an entry to download the jar.");
        }
        lore.add(" ");
        lore.add(Theme.SUBTLE + "Plugins are not activated automatically.");
        return GuiChrome.button(Material.WRITTEN_BOOK, Theme.ACCENT + "Plugin search", lore);
    }

    private ItemStack createManualInstallItem() {
        return GuiChrome.button(Material.OAK_SIGN, Theme.WARNING + "Enter a direct link", List.of(
                Theme.MUTED + "Opens an anvil dialog",
                Theme.MUTED + "for direct download links (e.g. SpigotMC/Spiget).",
                " ",
                GuiChrome.hints("Click: enter a link")));
    }

    private void promptStandaloneSearch(Player player, InventorySession session) {
        List<PluginLinkSuggestion> previous = new ArrayList<>(session.orderedSuggestions());
        String previousTerm = session.searchTerm();

        state.openInventories.remove(player.getUniqueId());
        player.closeInventory();

        anvilTextPrompt.open(player, AnvilTextPrompt.Prompt.builder()
                .title(Theme.TITLE + "Search plugins")
                .initialText(previousTerm != null ? previousTerm : "")
                .validation(value -> {
                    String trimmed = value != null ? value.trim() : "";
                    if (trimmed.length() < 3) {
                        return Optional.of(Theme.ERROR + "Please enter at least three characters.");
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
            player.sendMessage(Theme.ERROR + "Please enter at least three characters.");
            openStandaloneInstall(player, previousSuggestions, previousTerm);
            return;
        }

        UUID playerId = player.getUniqueId();
        pendingInstallSearches.put(playerId, trimmed);
        player.sendMessage(Theme.MUTED + "Searching for " + Theme.ACCENT + trimmed + Theme.MUTED + " …");

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
            player.sendMessage(Theme.WARNING + "No results found. Try another term.");
        } else {
            player.sendMessage(Theme.SUCCESS + "Projects found: " + entries.size());
        }

        openStandaloneInstall(player, entries, searchTerm);
    }

    private void promptStandaloneManualInstall(Player player, InventorySession session) {
        List<PluginLinkSuggestion> previous = new ArrayList<>(session.orderedSuggestions());
        String previousTerm = session.searchTerm();

        state.openInventories.remove(player.getUniqueId());
        player.closeInventory();

        anvilTextPrompt.open(player, AnvilTextPrompt.Prompt.builder()
                .title(Theme.TITLE + "Direct link")
                .initialText("https://")
                .onConfirm((p, value) -> installStandaloneFromUrl(p, value))
                .onCancel(p -> openStandaloneInstall(p, previous, previousTerm))
                .build());
    }

    private void installStandaloneSuggestion(Player player, PluginLinkSuggestion suggestion) {
        player.closeInventory();
        state.openInventories.remove(player.getUniqueId());
        String title = Objects.requireNonNullElse(suggestion.title(), suggestion.provider());
        player.sendMessage(Theme.SUCCESS + "Installing " + Theme.ACCENT
                + suggestion.provider() + Theme.SUCCESS + " project " + Theme.ACCENT
                + title + Theme.SUCCESS + " …");
        coordinator.install(player, suggestion.url());
    }

    private void installStandaloneFromUrl(Player player, String value) {
        String trimmed = value != null ? value.trim() : "";
        if (trimmed.isEmpty()) {
            player.sendMessage(Theme.ERROR + "Please provide a valid download link.");
            return;
        }
        player.sendMessage(Theme.SUCCESS + "Starting installation from direct link…");
        coordinator.install(player, trimmed);
    }
}
