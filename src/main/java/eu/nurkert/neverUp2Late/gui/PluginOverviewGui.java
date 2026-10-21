package eu.nurkert.neverUp2Late.gui;

import eu.nurkert.neverUp2Late.Permissions;
import eu.nurkert.neverUp2Late.command.QuickInstallCoordinator;
import eu.nurkert.neverUp2Late.core.PluginContext;
import eu.nurkert.neverUp2Late.gui.ChatPrompts.LinkRequest;
import eu.nurkert.neverUp2Late.gui.anvil.AnvilTextPrompt;
import eu.nurkert.neverUp2Late.plugin.ManagedPlugin;
import org.bukkit.ChatColor;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
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

    public PluginOverviewGui(PluginContext context, QuickInstallCoordinator coordinator, AnvilTextPrompt anvilTextPrompt) {
        this.state = new GuiState(context, coordinator, anvilTextPrompt);
        this.prompts = state.prompts;
        this.overview = new OverviewScreen(state, this);
        this.details = new PluginDetailScreen(state, this);
        this.links = new LinkSuggestionScreen(state, this);
        this.install = new InstallScreen(state, this);
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

    public void open(Player player) {
        if (!player.hasPermission(Permissions.GUI_OPEN)) {
            player.sendMessage(ChatColor.RED + "You do not have permission to open the NU2L GUI.");
            return;
        }
        if (state.context.getPluginLifecycleManager() == null) {
            player.sendMessage(ChatColor.RED + "Plugin management is disabled.");
            return;
        }
        overview.openOverview(player);
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
        prompts.pendingRemovalRequests.remove(playerId);
        prompts.pendingSuggestionRequests.remove(playerId);
        install.forget(playerId);
        prompts.pendingCleanupConfirmations.remove(playerId);
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
                    ChatColor.GRAY + "The previous NU2L prompt timed out - nothing was changed."));
            return;
        }
        if (prompts.pendingCleanupConfirmations.remove(playerId)) {
            event.setCancelled(true);
            String answer = event.getMessage() == null ? "" : event.getMessage().trim();
            state.context.getScheduler().runTask(state.context.getPlugin(), () -> {
                prompts.promptDeadlines.remove(playerId);
                if (answer.equalsIgnoreCase("yes")) {
                    overview.cleanupAllJarNames(player);
                } else {
                    player.sendMessage(ChatColor.YELLOW + "Nothing was renamed.");
                }
            });
            return;
        }
        ManagedPlugin pendingRemoval = prompts.pendingRemovalRequests.get(playerId);
        if (pendingRemoval != null) {
            prompts.promptDeadlines.remove(playerId);
            event.setCancelled(true);
            state.context.getScheduler().runTask(state.context.getPlugin(),
                    () -> details.handleRemovalInput(player, pendingRemoval, event.getMessage()));
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
