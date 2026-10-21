package eu.nurkert.neverUp2Late.gui;

import eu.nurkert.neverUp2Late.util.Feedback;
import eu.nurkert.neverUp2Late.util.Theme;
import eu.nurkert.neverUp2Late.Permissions;
import eu.nurkert.neverUp2Late.command.QuickInstallCoordinator;
import eu.nurkert.neverUp2Late.core.PluginContext;
import eu.nurkert.neverUp2Late.gui.ChatPrompts.LinkRequest;
import eu.nurkert.neverUp2Late.gui.anvil.AnvilTextPrompt;
import eu.nurkert.neverUp2Late.plugin.ManagedPlugin;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.ClickType;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryCloseEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.event.player.AsyncPlayerChatEvent;
import org.bukkit.event.player.PlayerQuitEvent;

import java.util.UUID;

/**
 * Creates a simple chest based GUI that lists all plugins currently managed by NU2L.
 *
 * <p>This class is the entry point and the Bukkit listener; each screen lives in its
 * own class and this one routes clicks and chat answers to it.</p>
 */
public class PluginOverviewGui implements Listener {

    private final GuiState state;
    private final ChatPrompts prompts;
    private final OverviewScreen overview;
    private final PluginDetailScreen details;
    private final LinkSuggestionScreen links;
    private final InstallScreen install;
    private final ConfirmationScreen confirmations;

    public PluginOverviewGui(PluginContext context, QuickInstallCoordinator coordinator, AnvilTextPrompt anvilTextPrompt) {
        this.state = new GuiState(context, coordinator, anvilTextPrompt);
        this.prompts = state.prompts;
        this.overview = new OverviewScreen(state, this);
        this.details = new PluginDetailScreen(state, this);
        this.links = new LinkSuggestionScreen(state, this);
        this.install = new InstallScreen(state, this);
        this.confirmations = new ConfirmationScreen(state);
    }

    OverviewScreen overview() {
        return overview;
    }

    PluginDetailScreen details() {
        return details;
    }

    LinkSuggestionScreen links() {
        return links;
    }

    InstallScreen install() {
        return install;
    }

    ConfirmationScreen confirmations() {
        return confirmations;
    }

    public void open(Player player) {
        if (!player.hasPermission(Permissions.GUI_OPEN)) {
            player.sendMessage(Theme.ERROR + "You do not have permission to open the NU2L GUI.");
            return;
        }
        if (state.context.getPluginLifecycleManager() == null) {
            player.sendMessage(Theme.ERROR + "Plugin management is disabled.");
            return;
        }
        // Opening the GUI afresh starts on page one of everything; going back
        // from a plugin keeps the page, filter and search the operator chose.
        overview.resetView(player);
        overview.openOverview(player);
        Feedback.pageTurn(player);
    }

    public void openStandaloneSearch(Player player, String query) {
        install.openStandaloneSearch(player, query);
    }

    @EventHandler
    public void onInventoryClick(InventoryClickEvent event) {
        if (!(event.getWhoClicked() instanceof Player player)) {
            return;
        }

        InventorySession session = state.openInventories.get(player.getUniqueId());
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

        // A double click arrives as one more event after its two clicks. Acting
        // on it too would turn one page forward into two, or run an action twice.
        if (event.getClick() == ClickType.DOUBLE_CLICK) {
            return;
        }

        if (session.view() == InventorySession.View.CONFIRMATION) {
            confirmations.handleClick(player, session, event);
            return;
        }

        // Close sits in the same corner on every screen, so it is handled once.
        if (event.getRawSlot() == GuiLayout.CLOSE_SLOT
                && session.inventory().getSize() == GuiLayout.SIZE) {
            Feedback.click(player);
            player.closeInventory();
            return;
        }

        if (session.view() == InventorySession.View.NEW_INSTALL) {
            install.handleClick(player, session, event);
            return;
        }

        if (session.view() == InventorySession.View.OVERVIEW) {
            overview.handleClick(player, session, event);
            return;
        }

        ManagedPlugin plugin = session.plugin();
        if (plugin == null) {
            return;
        }

        if (session.view() == InventorySession.View.LINK_SUGGESTIONS) {
            links.handleClick(player, plugin, session, event);
            return;
        }

        details.handleClick(player, plugin, event);
    }

    /**
     * Clicks are cancelled, but a drag is a separate event. Without this an
     * operator could drag items into the GUI, where they are silently destroyed
     * when the (holder-less) inventory closes.
     */
    @EventHandler
    public void onInventoryDrag(InventoryDragEvent event) {
        if (!(event.getWhoClicked() instanceof Player player)) {
            return;
        }
        InventorySession session = state.openInventories.get(player.getUniqueId());
        if (session == null) {
            return;
        }
        if (!event.getView().getTopInventory().equals(session.inventory())) {
            return;
        }
        int size = session.inventory().getSize();
        for (int rawSlot : event.getRawSlots()) {
            if (rawSlot < size) {
                event.setCancelled(true);
                return;
            }
        }
    }

    @EventHandler
    public void onInventoryClose(InventoryCloseEvent event) {
        UUID playerId = event.getPlayer().getUniqueId();
        InventorySession session = state.openInventories.get(playerId);
        if (session != null && event.getInventory().equals(session.inventory())) {
            state.openInventories.remove(playerId);
        }
    }

    @EventHandler
    public void onPlayerQuit(PlayerQuitEvent event) {
        UUID playerId = event.getPlayer().getUniqueId();
        state.openInventories.remove(playerId);
        prompts.pendingLinkRequests.remove(playerId);
        prompts.pendingSuggestionRequests.remove(playerId);
        install.forget(playerId);
        overview.forget(playerId);
    }

    @EventHandler
    public void onPlayerChat(AsyncPlayerChatEvent event) {
        Player player = event.getPlayer();
        UUID playerId = player.getUniqueId();
        if (prompts.hasPromptExpired(playerId)) {
            prompts.clearPrompts(playerId);
            // This handler runs off the server thread; talking to a Player
            // belongs on it.
            state.context.getScheduler().runTask(state.context.getPlugin(), () -> player.sendMessage(
                    Theme.MUTED + "The previous NU2L prompt timed out - nothing was changed."));
            return;
        }
        LinkRequest request = prompts.pendingLinkRequests.get(playerId);
        if (request == null) {
            return;
        }

        prompts.promptDeadlines.remove(playerId);
        event.setCancelled(true);
        String message = event.getMessage() != null ? event.getMessage().trim() : "";
        state.context.getScheduler().runTask(state.context.getPlugin(),
                () -> links.handleChatInput(player, message, request));
    }
}
