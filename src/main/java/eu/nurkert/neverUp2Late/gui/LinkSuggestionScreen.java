package eu.nurkert.neverUp2Late.gui;

import eu.nurkert.neverUp2Late.Permissions;
import eu.nurkert.neverUp2Late.command.QuickInstallCoordinator;
import eu.nurkert.neverUp2Late.core.PluginContext;
import eu.nurkert.neverUp2Late.gui.ChatPrompts.LinkRequest;
import eu.nurkert.neverUp2Late.plugin.ManagedPlugin;
import eu.nurkert.neverUp2Late.update.UpdateSourceRegistry.UpdateSource;
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
            player.sendMessage(ChatColor.RED + "Could not find a jar for this plugin.");
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
        player.sendMessage(ChatColor.GRAY + "Searching for matching sources…");

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
            player.sendMessage(ChatColor.YELLOW + "No matching sources found. Please enter the link manually.");
            promptManualLinkInput(player, plugin, Optional.empty());
            return;
        }

        player.sendMessage(ChatColor.GREEN + "Found " + suggestions.size()
                + " potential update sources.");
        player.sendMessage(ChatColor.GRAY + "Choose a suggestion or enter a link manually.");
        openLinkSuggestions(player, plugin, suggestions);
    }

    private void openLinkSuggestions(Player player,
                                      ManagedPlugin plugin,
                                      List<PluginLinkSuggestion> suggestions) {
        String pluginName = Objects.requireNonNullElse(plugin.getName(), "Plugin");
        List<PluginLinkSuggestion> entries = suggestions == null ? List.of() : suggestions;
        int baseCount = Math.max(0, entries.size()) + 2;
        int size = Math.max(9, ((baseCount + 8) / 9) * 9);
        size = Math.min(size, GuiState.MAX_SIZE);

        Inventory inventory = Bukkit.createInventory(null, size,
                ChatColor.DARK_PURPLE + pluginName + " – Update sources");

        ItemStack filler = items.createFiller();
        for (int i = 0; i < size; i++) {
            inventory.setItem(i, filler.clone());
        }

        Map<Integer, PluginLinkSuggestion> mapping = new HashMap<>();
        int maxSuggestions = Math.max(0, size - 2);
        for (int i = 0; i < maxSuggestions && i < entries.size(); i++) {
            PluginLinkSuggestion suggestion = entries.get(i);
            inventory.setItem(i, items.createSuggestionItem(suggestion));
            mapping.put(i, suggestion);
        }

        int manualSlot = size - 2;
        inventory.setItem(manualSlot, createManualLinkOptionItem());
        inventory.setItem(size - 1, createSuggestionBackItem());

        state.openInventories.put(player.getUniqueId(), InventorySession.suggestions(inventory, plugin, mapping, entries));
        player.openInventory(inventory);
    }

    private void applySuggestion(Player player, ManagedPlugin plugin, PluginLinkSuggestion suggestion) {
        String pluginName = Objects.requireNonNullElse(plugin.getName(),
                plugin.getPath() != null ? plugin.getPath().getFileName().toString() : "Plugin");
        player.closeInventory();
        state.openInventories.remove(player.getUniqueId());
        prompts.pendingLinkRequests.remove(player.getUniqueId());
        player.sendMessage(ChatColor.GREEN + "Applying suggestion " + ChatColor.AQUA
                + suggestion.provider() + ChatColor.GREEN + " for " + ChatColor.AQUA + pluginName + ChatColor.GREEN + " …");
        coordinator.installForPlugin(player, pluginName, suggestion.url());
    }

    void handleClick(Player player, ManagedPlugin plugin, InventorySession session, InventoryClickEvent event) {
        int manualSlot = session.inventory().getSize() - 2;
        int backSlot = session.inventory().getSize() - 1;
        int clicked = event.getRawSlot();
        if (clicked == backSlot) {
            gui.details().openPluginDetails(player, plugin);
            return;
        }
        if (clicked == manualSlot) {
            player.closeInventory();
            promptManualLinkInput(player, plugin, Optional.empty());
            return;
        }
        PluginLinkSuggestion suggestion = session.suggestions().get(clicked);
        if (suggestion != null) {
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

        player.sendMessage(ChatColor.AQUA + "Set update link for " + pluginName + ".");
        existingSource.ifPresentOrElse(source ->
                        player.sendMessage(ChatColor.GRAY + "Current source: " + ChatColor.AQUA + source.getName()),
                () -> player.sendMessage(ChatColor.GRAY + "No source is currently linked."));
        player.sendMessage(ChatColor.YELLOW + "Please enter the download URL in chat or type 'cancel'.");
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
        ItemStack item = new ItemStack(Material.OAK_SIGN);
        ItemMeta meta = item.getItemMeta();
        if (meta != null) {
            meta.setDisplayName(ChatColor.AQUA + "Enter a link manually");
            meta.setLore(List.of(
                    ChatColor.GRAY + "Opens chat input,",
                    ChatColor.GRAY + "so you can enter a URL manually."
            ));
            item.setItemMeta(meta);
        }
        return item;
    }

    private ItemStack createSuggestionBackItem() {
        ItemStack item = new ItemStack(Material.ARROW);
        ItemMeta meta = item.getItemMeta();
        if (meta != null) {
            meta.setDisplayName(ChatColor.YELLOW + "Back to plugin details");
            meta.setLore(List.of(ChatColor.GRAY + "Returns to the plugin details."));
            item.setItemMeta(meta);
        }
        return item;
    }

    void handleChatInput(Player player, String message, LinkRequest request) {
        UUID playerId = player.getUniqueId();
        if (prompts.pendingLinkRequests.get(playerId) != request) {
            return;
        }

        if (message.isBlank()) {
            player.sendMessage(ChatColor.RED + "Please enter a valid link or type 'cancel'.");
            return;
        }

        if (message.equalsIgnoreCase("cancel")) {
            prompts.pendingLinkRequests.remove(playerId);
            player.sendMessage(ChatColor.YELLOW + "Linking cancelled.");
            return;
        }

        prompts.pendingLinkRequests.remove(playerId);
        if (request.standalone()) {
            player.sendMessage(ChatColor.GREEN + "Starting installation of a new plugin…");
            coordinator.install(player, message);
            return;
        }

        String pluginName = request.pluginName();
        player.sendMessage(ChatColor.GREEN + "Processing link for " + ChatColor.AQUA + pluginName + ChatColor.GREEN + " …");
        coordinator.installForPlugin(player, pluginName, message);
    }
}
