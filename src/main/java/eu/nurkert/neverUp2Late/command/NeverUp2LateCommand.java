package eu.nurkert.neverUp2Late.command;

import eu.nurkert.neverUp2Late.Permissions;
import eu.nurkert.neverUp2Late.core.PluginContext;
import eu.nurkert.neverUp2Late.gui.PluginOverviewGui;
import eu.nurkert.neverUp2Late.setup.InitialSetupManager;
import org.bukkit.ChatColor;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;

import java.net.URI;
import java.net.URISyntaxException;
import java.util.Collections;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Objects;

public class NeverUp2LateCommand implements CommandExecutor, TabCompleter {

    private final QuickInstallCoordinator coordinator;
    private final PluginOverviewGui overviewGui;
    private final InitialSetupManager setupManager;
    private final PluginContext context;

    public NeverUp2LateCommand(QuickInstallCoordinator coordinator,
                               PluginOverviewGui overviewGui,
                               InitialSetupManager setupManager,
                               PluginContext context) {
        this.coordinator = Objects.requireNonNull(coordinator, "coordinator");
        this.overviewGui = Objects.requireNonNull(overviewGui, "overviewGui");
        this.setupManager = setupManager;
        this.context = Objects.requireNonNull(context, "context");
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (args.length == 0 || "gui".equalsIgnoreCase(args[0])) {
            if (!sender.hasPermission(Permissions.GUI_OPEN)) {
                sender.sendMessage(ChatColor.RED + "You do not have permission to open the GUI.");
                return true;
            }
            if (sender instanceof Player player) {
                overviewGui.open(player);
            } else {
                sender.sendMessage(ChatColor.RED + "The graphical interface can only be opened by players.");
            }
            return true;
        }

        if (args.length > 0 && "status".equalsIgnoreCase(args[0])) {
            if (!sender.hasPermission(Permissions.INSTALL)) {
                sender.sendMessage(ChatColor.RED + "You do not have permission to view source status.");
                return true;
            }
            displayStatus(sender);
            return true;
        }

        if (args.length > 0 && "select".equalsIgnoreCase(args[0])) {
            if (!sender.hasPermission(Permissions.INSTALL)) {
                sender.sendMessage(ChatColor.RED + "You do not have permission to manage installations.");
                return true;
            }
            if (args.length < 2) {
                sender.sendMessage(ChatColor.RED + "Please specify the number of the desired file.");
                return true;
            }
            coordinator.handleAssetSelection(sender, args[1]);
            return true;
        }

        if (args.length > 0 && "ignore".equalsIgnoreCase(args[0])) {
            if (!sender.hasPermission(Permissions.INSTALL)) {
                sender.sendMessage(ChatColor.RED + "You do not have permission to manage installations.");
                return true;
            }
            coordinator.confirmCompatibilityOverride(sender);
            return true;
        }

        if (args.length > 0 && "cancel".equalsIgnoreCase(args[0])) {
            if (!sender.hasPermission(Permissions.INSTALL)) {
                sender.sendMessage(ChatColor.RED + "You do not have permission to manage installations.");
                return true;
            }
            coordinator.cancelCompatibilityOverride(sender);
            return true;
        }

        if (args.length > 0 && "setup".equalsIgnoreCase(args[0])) {
            if (setupManager == null) {
                sender.sendMessage(ChatColor.RED + "The setup utilities are not available.");
                return true;
            }
            if (!sender.hasPermission(Permissions.SETUP)) {
                sender.sendMessage(ChatColor.RED + "You do not have permission to manage the setup wizard.");
                return true;
            }

            if (args.length == 1) {
                if (sender instanceof Player player) {
                    setupManager.openWizard(player);
                } else {
                    sender.sendMessage(ChatColor.YELLOW + "Run /" + label + " setup complete to finish the wizard from the console.");
                }
                return true;
            }

            String subCommand = args[1];
            if ("complete".equalsIgnoreCase(subCommand)) {
                setupManager.completeSetup(sender);
                return true;
            }
            if ("apply".equalsIgnoreCase(subCommand)) {
                if (args.length < 3) {
                    sender.sendMessage(ChatColor.RED + "Please provide the path or identifier of the configuration file to apply.");
                    return true;
                }
                String configIdentifier = String.join(" ", Arrays.copyOfRange(args, 2, args.length));
                setupManager.applyConfiguration(sender, configIdentifier.trim());
                return true;
            }

            if (sender instanceof Player player) {
                setupManager.openWizard(player);
            } else {
                sender.sendMessage(ChatColor.RED + "Unknown setup sub-command: " + subCommand);
            }
            return true;
        }

        if (args.length > 0 && "remove".equalsIgnoreCase(args[0])) {
            if (!sender.hasPermission(Permissions.GUI_MANAGE_REMOVE)) {
                sender.sendMessage(ChatColor.RED + "You do not have permission to remove plugins.");
                return true;
            }
            if (args.length < 2) {
                sender.sendMessage(ChatColor.RED + "Please provide the plugin name.");
                return true;
            }
            String pluginName = String.join(" ", Arrays.copyOfRange(args, 1, args.length));
            coordinator.removePlugin(sender, pluginName.trim());
            return true;
        }

        if (args.length > 0 && "rollback".equalsIgnoreCase(args[0])) {
            if (!sender.hasPermission(Permissions.INSTALL)) {
                sender.sendMessage(ChatColor.RED + "You do not have permission to manage installations.");
                return true;
            }
            if (args.length < 2) {
                sender.sendMessage(ChatColor.RED + "Please provide the update source name to roll back.");
                return true;
            }
            String pluginName = String.join(" ", Arrays.copyOfRange(args, 1, args.length)).trim();
            if (pluginName.isEmpty()) {
                sender.sendMessage(ChatColor.RED + "Please provide the update source name to roll back.");
                return true;
            }
            coordinator.rollback(sender, pluginName);
            return true;
        }

        if (!sender.hasPermission(Permissions.INSTALL)) {
            sender.sendMessage(ChatColor.RED + "You do not have permission to manage installations.");
            return true;
        }

        String input = String.join(" ", args).trim();
        if (input.isEmpty()) {
            sender.sendMessage(ChatColor.RED + "Please provide a valid URL.");
            return true;
        }

        if (isLikelyUrl(input)) {
            coordinator.install(sender, input);
            return true;
        }

        if (!(sender instanceof Player player)) {
            sender.sendMessage(ChatColor.RED + "The search shortcut can only be used by players.");
            return true;
        }

        overviewGui.openStandaloneSearch(player, input);
        return true;
    }

    private boolean isLikelyUrl(String input) {
        try {
            URI uri = new URI(input);
            if (uri.getScheme() == null || uri.getHost() == null) {
                return false;
            }
            String scheme = uri.getScheme().toLowerCase(Locale.ROOT);
            return scheme.equals("http") || scheme.equals("https");
        } catch (URISyntaxException ignored) {
            return false;
        }
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        if (args.length == 1) {
            return List.of("gui", "status", "select", "ignore", "cancel", "remove", "setup", "rollback");
        }
        if (args.length == 2 && "select".equalsIgnoreCase(args[0])) {
            return Collections.singletonList("<number>");
        }
        if (args.length == 2 && "setup".equalsIgnoreCase(args[0])) {
            return List.of("complete", "apply");
        }
        if (args.length == 3 && "setup".equalsIgnoreCase(args[0]) && "apply".equalsIgnoreCase(args[1])) {
            return Collections.singletonList("<file>");
        }
        if (args.length == 2 && "rollback".equalsIgnoreCase(args[0])) {
            return coordinator.getRollbackSuggestions();
        }
        return Collections.emptyList();
    }

    private void displayStatus(CommandSender sender) {
        List<PluginContext.UpdateSourceStatus> statuses = context.getUpdateSourceStatuses();
        if (statuses.isEmpty()) {
            sender.sendMessage(ChatColor.YELLOW + "No update sources are currently registered.");
            return;
        }

        sender.sendMessage(ChatColor.GOLD + "Update source status:");
        for (PluginContext.UpdateSourceStatus status : statuses) {
            String displayName = status.displayName();
            String pluginInfo = ChatColor.GRAY + " | Plugin: " + ChatColor.AQUA + status.pluginDisplayName();
            String path = status.targetPath() != null ? status.targetPath().toString() : "(unknown)";
            String pathInfo = ChatColor.GRAY + " → " + ChatColor.YELLOW + path;
            String versionInfo = ChatColor.GRAY + " | " + ChatColor.BLUE + status.versionLabel();
            String autoInfo = ChatColor.GRAY + " | Auto-Update: "
                    + (status.autoUpdateEnabled() ? ChatColor.GREEN + "enabled" : ChatColor.RED + "disabled");

            sender.sendMessage(ChatColor.AQUA + displayName + pathInfo + versionInfo + autoInfo + pluginInfo);
        }
    }
}
