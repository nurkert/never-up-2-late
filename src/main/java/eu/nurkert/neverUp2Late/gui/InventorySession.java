package eu.nurkert.neverUp2Late.gui;

import eu.nurkert.neverUp2Late.plugin.ManagedPlugin;
import eu.nurkert.neverUp2Late.update.suggestion.PluginLinkSuggestion;
import org.bukkit.inventory.Inventory;

import java.util.List;
import java.util.Map;

/**
 * What a player currently has open in the NU2L GUI, so a click can be routed to the screen that drew it.
 */
record InventorySession(Inventory inventory,
                        InventorySession.View view,
                        Map<Integer, ManagedPlugin> plugins,
                        ManagedPlugin plugin,
                        Map<Integer, PluginLinkSuggestion> suggestions,
                        List<PluginLinkSuggestion> orderedSuggestions,
                        String searchTerm) {

    enum View {
        OVERVIEW,
        DETAIL,
        LINK_SUGGESTIONS,
        NEW_INSTALL
    }

    static InventorySession overview(Inventory inventory, Map<Integer, ManagedPlugin> plugins) {
        return new InventorySession(inventory, View.OVERVIEW, Map.copyOf(plugins), null, Map.of(), List.of(), null);
    }

    static InventorySession detail(Inventory inventory, ManagedPlugin plugin) {
        return new InventorySession(inventory, View.DETAIL, Map.of(), plugin, Map.of(), List.of(), null);
    }

    static InventorySession suggestions(Inventory inventory,
                                        ManagedPlugin plugin,
                                        Map<Integer, PluginLinkSuggestion> suggestions,
                                        List<PluginLinkSuggestion> ordered) {
        return new InventorySession(inventory, View.LINK_SUGGESTIONS, Map.of(), plugin,
                Map.copyOf(suggestions), List.copyOf(ordered), null);
    }

    static InventorySession newInstall(Inventory inventory,
                                       Map<Integer, PluginLinkSuggestion> suggestions,
                                       List<PluginLinkSuggestion> ordered,
                                       String searchTerm) {
        return new InventorySession(inventory, View.NEW_INSTALL, Map.of(), null,
                Map.copyOf(suggestions), List.copyOf(ordered), searchTerm);
    }
}
