package eu.nurkert.neverUp2Late.gui;

import eu.nurkert.neverUp2Late.util.Theme;
import eu.nurkert.neverUp2Late.Permissions;
import eu.nurkert.neverUp2Late.command.QuickInstallCoordinator;
import eu.nurkert.neverUp2Late.core.PluginContext;
import eu.nurkert.neverUp2Late.gui.anvil.AnvilTextPrompt;
import eu.nurkert.neverUp2Late.persistence.PluginUpdateSettingsRepository;
import eu.nurkert.neverUp2Late.update.suggestion.PluginLinkSuggester;
import org.bukkit.entity.Player;

import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Everything the GUI screens share: their dependencies, which inventory each player has open,
 * and the chat prompts that are waiting on an answer.
 */
final class GuiState {

    static final int MAX_SIZE = 54;

    final PluginContext context;
    final QuickInstallCoordinator coordinator;
    final AnvilTextPrompt anvilTextPrompt;
    final PluginUpdateSettingsRepository updateSettingsRepository;
    final PluginLinkSuggester linkSuggester;
    final Map<UUID, InventorySession> openInventories = new ConcurrentHashMap<>();
    final ChatPrompts prompts = new ChatPrompts();
    final GuiItems items;

    GuiState(PluginContext context, QuickInstallCoordinator coordinator, AnvilTextPrompt anvilTextPrompt) {
        this.context = Objects.requireNonNull(context, "context");
        this.coordinator = Objects.requireNonNull(coordinator, "coordinator");
        this.anvilTextPrompt = Objects.requireNonNull(anvilTextPrompt, "anvilTextPrompt");
        this.updateSettingsRepository = context.getPluginUpdateSettingsRepository();
        this.linkSuggester = new PluginLinkSuggester(context.getPlugin().getLogger());
        this.items = new GuiItems(context, updateSettingsRepository);
    }

    boolean checkPermission(Player player, String permission) {
        if (permission == null || permission.isBlank()) {
            return true;
        }
        if (player.hasPermission(permission) || player.hasPermission(Permissions.GUI_MANAGE)) {
            return true;
        }
        player.sendMessage(Theme.ERROR + "You are missing the permission " + permission + ".");
        return false;
    }
}
