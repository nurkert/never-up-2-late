package eu.nurkert.neverUp2Late.command;

import eu.nurkert.neverUp2Late.Permissions;
import eu.nurkert.neverUp2Late.core.PluginContext;
import eu.nurkert.neverUp2Late.update.UpdateSourceRegistry.UpdateSource;
import eu.nurkert.neverUp2Late.persistence.UpdateStateRepository;
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
import java.util.Optional;
import java.util.ArrayList;
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

        if ("help".equalsIgnoreCase(args[0]) || "?".equals(args[0])) {
            sendHelp(sender, label);
            return true;
        }

        if ("check".equalsIgnoreCase(args[0]) || "plan".equalsIgnoreCase(args[0])) {
            boolean dryRun = "plan".equalsIgnoreCase(args[0]);
            if (!sender.hasPermission(Permissions.INSTALL)) {
                sender.sendMessage(ChatColor.RED + "You do not have permission to trigger update checks.");
                return true;
            }
            List<UpdateSource> sources;
            if (args.length >= 2) {
                Optional<UpdateSource> single = context.getUpdateSourceRegistry().findSource(args[1]);
                if (single.isEmpty()) {
                    sender.sendMessage(ChatColor.RED + "No update source named " + args[1] + ".");
                    return true;
                }
                sources = List.of(single.get());
            } else {
                sources = List.copyOf(context.getUpdateSourceRegistry().getSources());
            }
            if (sources.isEmpty()) {
                sender.sendMessage(ChatColor.YELLOW + "No update sources are configured yet.");
                return true;
            }
            if (dryRun) {
                context.getUpdateHandler().planNow(sources, sender);
            } else {
                context.getUpdateHandler().runJobsNow(sources, sender);
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
            return List.of("help", "status", "plan", "check", "gui", "install", "rollback", "setup", "select", "ignore", "cancel", "remove");
        }
        if (args.length == 2 && ("check".equalsIgnoreCase(args[0]) || "plan".equalsIgnoreCase(args[0]))) {
            return context.getUpdateSourceRegistry().getSources().stream()
                    .map(UpdateSource::getName).toList();
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

        int waiting = 0;
        int failing = 0;
        long newestCheck = 0L;
        List<String> lines = new ArrayList<>();

        for (PluginContext.UpdateSourceStatus status : statuses) {
            Optional<UpdateStateRepository.CheckState> check =
                    context.getPersistentPluginHandler().getCheckState(status.displayName());

            String state;
            if (check.isEmpty()) {
                state = ChatColor.DARK_GRAY + "not checked yet";
            } else {
                UpdateStateRepository.CheckState value = check.get();
                newestCheck = Math.max(newestCheck, value.checkedAt());
                switch (value.result()) {
                    case FAILED -> {
                        failing++;
                        state = ChatColor.RED + "check failed: "
                                + (value.error() == null ? "unknown reason" : value.error());
                    }
                    case UPDATED -> {
                        waiting++;
                        state = ChatColor.GREEN + "updated to " + value.latestVersion()
                                + ChatColor.GRAY + " (restart pending)";
                    }
                    default -> state = ChatColor.GRAY + "up to date";
                }
            }

            lines.add(ChatColor.AQUA + status.displayName()
                    + ChatColor.GRAY + " → " + ChatColor.YELLOW + status.versionLabel()
                    + ChatColor.GRAY + " | " + state
                    + ChatColor.GRAY + " | auto-update: "
                    + (status.autoUpdateEnabled() ? ChatColor.GREEN + "on" : ChatColor.RED + "off"));
        }

        // The headline first: an operator wants the answer, not a table to read.
        sender.sendMessage(ChatColor.GOLD + "NeverUp2Late: " + ChatColor.WHITE + statuses.size()
                + ChatColor.GRAY + " source(s), " + ChatColor.WHITE + waiting
                + ChatColor.GRAY + " waiting for a restart, " + ChatColor.WHITE + failing
                + ChatColor.GRAY + " failing.");
        sender.sendMessage(ChatColor.GRAY + "Last check: " + ChatColor.WHITE + describeAge(newestCheck)
                + ChatColor.DARK_GRAY + "  (/" + "nu2l check to look now)");
        lines.forEach(sender::sendMessage);
    }

    private String describeAge(long epochMillis) {
        if (epochMillis <= 0L) {
            return "never";
        }
        long minutes = Math.max(0L, (System.currentTimeMillis() - epochMillis) / 60_000L);
        if (minutes < 1L) {
            return "just now";
        }
        if (minutes < 60L) {
            return minutes + " min ago";
        }
        long hours = minutes / 60L;
        return hours < 24L ? hours + (hours == 1L ? " hour ago" : " hours ago")
                : (hours / 24L) + " day(s) ago";
    }

    private void sendHelp(CommandSender sender, String label) {
        sender.sendMessage(ChatColor.GOLD + "NeverUp2Late " + ChatColor.GRAY + "- keeps your server and plugins current.");
        sender.sendMessage(ChatColor.AQUA + "/" + label + " status" + ChatColor.GRAY + " - what is tracked, what is due, what failed");
        sender.sendMessage(ChatColor.AQUA + "/" + label + " plan [source]" + ChatColor.GRAY + " - show what an update would do, without changing anything");
        sender.sendMessage(ChatColor.AQUA + "/" + label + " check [source]" + ChatColor.GRAY + " - look for updates right now and install them");
        sender.sendMessage(ChatColor.AQUA + "/" + label + " gui" + ChatColor.GRAY + " - the plugin overview (players only)");
        sender.sendMessage(ChatColor.AQUA + "/" + label + " <url>" + ChatColor.GRAY + " - install a plugin from a link");
        sender.sendMessage(ChatColor.AQUA + "/" + label + " rollback <source>" + ChatColor.GRAY + " - restore the previous version");
        sender.sendMessage(ChatColor.AQUA + "/" + label + " setup" + ChatColor.GRAY + " - run the first-time setup");
    }
}
