package eu.nurkert.neverUp2Late.gui;

import eu.nurkert.neverUp2Late.plugin.ManagedPlugin;
import eu.nurkert.neverUp2Late.update.suggestion.PluginLinkSuggestion;
import org.bukkit.inventory.Inventory;

import java.util.List;
import java.util.Map;

/**
 * What a player currently has open in the NU2L GUI, so a click can be routed to the screen that drew it.
 *
 * @param page         the page a paged list is showing
 * @param overviewView page, filter and search of the overview; only set there
 * @param confirmation the question a confirmation screen asks; only set there
 */
record InventorySession(Inventory inventory,
                        InventorySession.View view,
                        Map<Integer, ManagedPlugin> plugins,
                        ManagedPlugin plugin,
                        Map<Integer, PluginLinkSuggestion> suggestions,
                        List<PluginLinkSuggestion> orderedSuggestions,
                        String searchTerm,
                        int page,
                        OverviewView overviewView,
                        ConfirmationScreen.Confirmation confirmation) {

    enum View {
        OVERVIEW,
        DETAIL,
        LINK_SUGGESTIONS,
        NEW_INSTALL,
        CONFIRMATION
    }

    static InventorySession overview(Inventory inventory, Map<Integer, ManagedPlugin> plugins, OverviewView view) {
        return new InventorySession(inventory, View.OVERVIEW, Map.copyOf(plugins), null, Map.of(), List.of(), null,
                view.page(), view, null);
    }

    static InventorySession detail(Inventory inventory, ManagedPlugin plugin) {
        return new InventorySession(inventory, View.DETAIL, Map.of(), plugin, Map.of(), List.of(), null,
                0, null, null);
    }

    static InventorySession suggestions(Inventory inventory,
                                        ManagedPlugin plugin,
                                        Map<Integer, PluginLinkSuggestion> suggestions,
                                        List<PluginLinkSuggestion> ordered,
                                        int page) {
        return new InventorySession(inventory, View.LINK_SUGGESTIONS, Map.of(), plugin,
                Map.copyOf(suggestions), List.copyOf(ordered), null, page, null, null);
    }

    static InventorySession newInstall(Inventory inventory,
                                       Map<Integer, PluginLinkSuggestion> suggestions,
                                       List<PluginLinkSuggestion> ordered,
                                       String searchTerm,
                                       int page) {
        return new InventorySession(inventory, View.NEW_INSTALL, Map.of(), null,
                Map.copyOf(suggestions), List.copyOf(ordered), searchTerm, page, null, null);
    }

    static InventorySession confirmation(Inventory inventory, ConfirmationScreen.Confirmation confirmation) {
        return new InventorySession(inventory, View.CONFIRMATION, Map.of(), null, Map.of(), List.of(), null,
                0, null, confirmation);
    }
}
