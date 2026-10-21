package eu.nurkert.neverUp2Late.gui;

import eu.nurkert.neverUp2Late.util.Feedback;
import eu.nurkert.neverUp2Late.util.Theme;
import eu.nurkert.neverUp2Late.Permissions;
import eu.nurkert.neverUp2Late.command.QuickInstallCoordinator;
import eu.nurkert.neverUp2Late.core.PluginContext;
import eu.nurkert.neverUp2Late.gui.ChatPrompts.LinkRequest;
import eu.nurkert.neverUp2Late.plugin.ManagedPlugin;
import eu.nurkert.neverUp2Late.update.UpdateSourceRegistry.UpdateSource;
import eu.nurkert.neverUp2Late.update.suggestion.PluginLinkSuggester;
import eu.nurkert.neverUp2Late.update.suggestion.PluginLinkSuggestion;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;

import java.nio.file.Path;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.logging.Level;

/**
 * Linking a plugin to an update source: the suggestion screen, and the chat prompt
 * for entering a link by hand.
 */
final class LinkSuggestionScreen {

    private final GuiState state;
    private final PluginOverviewGui gui;
    private final PluginContext context;
    private final QuickInstallCoordinator coordinator;
    private final PluginLinkSuggester linkSuggester;
    private final ChatPrompts prompts;
    private final GuiItems items;

    LinkSuggestionScreen(GuiState state, PluginOverviewGui gui) {
        this.state = state;
        this.gui = gui;
        this.context = state.context;
        this.coordinator = state.coordinator;
        this.linkSuggester = state.linkSuggester;
        this.prompts = state.prompts;
        this.items = state.items;
    }

    void beginLinking(Player player, ManagedPlugin plugin) {
        if (!state.checkPermission(player, Permissions.GUI_MANAGE_LINK)) {
            return;
        }
        UUID playerId = player.getUniqueId();
        player.closeInventory();
        state.openInventories.remove(playerId);
        prompts.pendingSuggestionRequests.remove(playerId);

        Path path = plugin.getPath();
        if (path == null) {
            player.sendMessage(Theme.ERROR + "Could not find a jar for this plugin.");
            return;
        }

        Optional<UpdateSource> existingSource = items.findMatchingSource(plugin);
        if (existingSource.isPresent()) {
            promptManualLinkInput(player, plugin, existingSource);
            return;
        }

        List<String> searchTerms = buildSearchTerms(plugin);
        if (searchTerms.isEmpty()) {
            promptManualLinkInput(player, plugin, Optional.empty());
            return;
        }

        prompts.pendingSuggestionRequests.put(playerId, plugin);
        prompts.armPrompt(player);
        player.sendMessage(Theme.MUTED + "Searching for matching sources…");

        context.getScheduler().runTaskAsynchronously(context.getPlugin(), () -> {
            List<PluginLinkSuggestion> suggestions;
            try {
                suggestions = linkSuggester.suggest(searchTerms);
            } catch (Exception e) {
                context.getPlugin().getLogger().log(Level.FINE,
                        "Failed to resolve link suggestions for " + plugin.getName(), e);
                suggestions = List.of();
            }
            List<PluginLinkSuggestion> finalSuggestions = suggestions;
            context.getScheduler().runTask(context.getPlugin(), () ->
                    handleSuggestionResults(player, plugin, finalSuggestions));
        });
    }

    private void handleSuggestionResults(Player player,
                                         ManagedPlugin plugin,
                                         List<PluginLinkSuggestion> suggestions) {
        UUID playerId = player.getUniqueId();
        ManagedPlugin expected = prompts.pendingSuggestionRequests.get(playerId);
        if (expected == null || expected != plugin) {
            return;
        }
        prompts.pendingSuggestionRequests.remove(playerId);

        if (!player.isOnline()) {
            return;
        }

        if (suggestions == null || suggestions.isEmpty()) {
            player.sendMessage(Theme.WARNING + "No matching sources found. Please enter the link manually.");
            promptManualLinkInput(player, plugin, Optional.empty());
            return;
        }

        player.sendMessage(Theme.SUCCESS + "Found " + suggestions.size()
                + " potential update sources.");
        player.sendMessage(Theme.MUTED + "Choose a suggestion or enter a link manually.");
        openLinkSuggestions(player, plugin, suggestions);
    }

    private void openLinkSuggestions(Player player,
                                      ManagedPlugin plugin,
                                      List<PluginLinkSuggestion> suggestions) {
        openLinkSuggestions(player, plugin, suggestions, 0);
    }

    /**
     * One page of suggestions. Searching by several names can return more
     * than fit in one chest; they used to be cut off at the bottom.
     */
    private void openLinkSuggestions(Player player,
                                     ManagedPlugin plugin,
                                     List<PluginLinkSuggestion> suggestions,
                                     int requestedPage) {
        String pluginName = Objects.requireNonNullElse(plugin.getName(), "Plugin");
        List<PluginLinkSuggestion> entries = suggestions == null ? List.of() : suggestions;
        Pagination pagination = Pagination.of(entries.size(), GuiLayout.CONTENT_SIZE, requestedPage);

        Inventory inventory = Bukkit.createInventory(null, GuiLayout.SIZE, GuiChrome.title("Sources"));
        GuiChrome.drawFrame(inventory, FrameState.of(items.statusOf(plugin)));

        inventory.setItem(GuiLayout.SUMMARY_SLOT, GuiChrome.button(Material.WRITTEN_BOOK,
                Theme.TEXT + "Update sources for " + Theme.ACCENT + pluginName, List.of(
                        Theme.MUTED + "Found " + Theme.TEXT + entries.size() + Theme.MUTED + " possible source(s).",
                        Theme.MUTED + "Pick one, or enter a link yourself.")));
        inventory.setItem(GuiLayout.TOOL_RIGHT_SLOT, createManualLinkOptionItem());

        Map<Integer, PluginLinkSuggestion> mapping = new HashMap<>();
        for (int index = pagination.fromIndex(); index < pagination.toIndex(); index++) {
            int slot = GuiLayout.contentSlot(index - pagination.fromIndex());
            PluginLinkSuggestion suggestion = entries.get(index);
            inventory.setItem(slot, items.createSuggestionItem(suggestion));
            mapping.put(slot, suggestion);
        }

        inventory.setItem(GuiLayout.BACK_SLOT, GuiChrome.back("the plugin details"));
        GuiChrome.drawPager(inventory, pagination, Theme.MUTED + entries.size() + " suggestion(s)");
        inventory.setItem(GuiLayout.CLOSE_SLOT, GuiChrome.close());

        state.openInventories.put(player.getUniqueId(),
                InventorySession.suggestions(inventory, plugin, mapping, entries, pagination.page()));
        player.openInventory(inventory);
    }

    private void applySuggestion(Player player, ManagedPlugin plugin, PluginLinkSuggestion suggestion) {
        String pluginName = Objects.requireNonNullElse(plugin.getName(),
                plugin.getPath() != null ? plugin.getPath().getFileName().toString() : "Plugin");
        player.closeInventory();
        state.openInventories.remove(player.getUniqueId());
        prompts.pendingLinkRequests.remove(player.getUniqueId());
        player.sendMessage(Theme.SUCCESS + "Applying suggestion " + Theme.ACCENT
                + suggestion.provider() + Theme.SUCCESS + " for " + Theme.ACCENT + pluginName + Theme.SUCCESS + " …");
        coordinator.installForPlugin(player, pluginName, suggestion.url());
    }

    void handleClick(Player player, ManagedPlugin plugin, InventorySession session, InventoryClickEvent event) {
        int clicked = event.getRawSlot();
        if (clicked == GuiLayout.BACK_SLOT) {
            Feedback.pageTurn(player);
            gui.details().openPluginDetails(player, plugin);
            return;
        }
        if (clicked == GuiLayout.TOOL_RIGHT_SLOT) {
            Feedback.click(player);
            player.closeInventory();
            promptManualLinkInput(player, plugin, Optional.empty());
            return;
        }
        if (clicked == GuiLayout.PREVIOUS_SLOT || clicked == GuiLayout.NEXT_SLOT) {
            if (OverviewScreen.isPagerArrow(event)) {
                Feedback.pageTurn(player);
                int delta = clicked == GuiLayout.NEXT_SLOT ? 1 : -1;
                openLinkSuggestions(player, plugin, session.orderedSuggestions(), session.page() + delta);
            }
            return;
        }
        PluginLinkSuggestion suggestion = session.suggestions().get(clicked);
        if (suggestion != null) {
            Feedback.click(player);
            applySuggestion(player, plugin, suggestion);
        }
    }

    private void promptManualLinkInput(Player player,
                                       ManagedPlugin plugin,
                                       Optional<UpdateSource> existingSource) {
        UUID playerId = player.getUniqueId();
        prompts.pendingSuggestionRequests.remove(playerId);
        String pluginName = Objects.requireNonNullElse(plugin.getName(),
                plugin.getPath() != null ? plugin.getPath().getFileName().toString() : "Plugin");
        prompts.pendingLinkRequests.put(playerId, new LinkRequest(pluginName, false));
        prompts.armPrompt(player);

        player.sendMessage(Theme.ACCENT + "Set update link for " + pluginName + ".");
        existingSource.ifPresentOrElse(source ->
                        player.sendMessage(Theme.MUTED + "Current source: " + Theme.ACCENT + source.getName()),
                () -> player.sendMessage(Theme.MUTED + "No source is currently linked."));
        player.sendMessage(Theme.WARNING + "Please enter the download URL in chat or type 'cancel'.");
    }

    private List<String> buildSearchTerms(ManagedPlugin plugin) {
        Set<String> terms = new LinkedHashSet<>();

        String pluginName = plugin.getName();
        if (pluginName != null) {
            terms.add(pluginName);
        }

        plugin.getPlugin().ifPresent(loaded -> {
            String descriptionName = loaded.getDescription().getName();
            if (descriptionName != null) {
                terms.add(descriptionName);
            }
            String fullName = loaded.getDescription().getFullName();
            if (fullName != null) {
                terms.add(fullName);
            }
        });

        Path path = plugin.getPath();
        if (path != null) {
            String baseName = items.stripJarExtension(path.getFileName().toString());
            if (baseName != null && !baseName.isBlank()) {
                terms.add(baseName);
                terms.add(baseName.replace('_', ' '));
                int dash = baseName.indexOf('-');
                if (dash > 0) {
                    terms.add(baseName.substring(0, dash));
                }
                int underscore = baseName.indexOf('_');
                if (underscore > 0) {
                    terms.add(baseName.substring(0, underscore));
                }
            }
        }

        return terms.stream()
                .map(String::trim)
                .filter(term -> !term.isEmpty())
                .limit(6)
                .toList();
    }

    private ItemStack createManualLinkOptionItem() {
        return GuiChrome.button(Material.OAK_SIGN, Theme.ACCENT + "Enter a link manually", List.of(
                Theme.MUTED + "Opens chat input,",
                Theme.MUTED + "so you can enter a URL manually.",
                " ",
                GuiChrome.hints("Click: type a link")));
    }

    void handleChatInput(Player player, String message, LinkRequest request) {
        UUID playerId = player.getUniqueId();
        if (prompts.pendingLinkRequests.get(playerId) != request) {
            return;
        }

        if (message.isBlank()) {
            player.sendMessage(Theme.ERROR + "Please enter a valid link or type 'cancel'.");
            return;
        }

        if (message.equalsIgnoreCase("cancel")) {
            prompts.pendingLinkRequests.remove(playerId);
            player.sendMessage(Theme.WARNING + "Linking cancelled.");
            return;
        }

        prompts.pendingLinkRequests.remove(playerId);
        if (request.standalone()) {
            player.sendMessage(Theme.SUCCESS + "Starting installation of a new plugin…");
            coordinator.install(player, message);
            return;
        }

        String pluginName = request.pluginName();
        player.sendMessage(Theme.SUCCESS + "Processing link for " + Theme.ACCENT + pluginName + Theme.SUCCESS + " …");
        coordinator.installForPlugin(player, pluginName, message);
    }
}
