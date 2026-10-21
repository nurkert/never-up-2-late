package eu.nurkert.neverUp2Late.command;

import eu.nurkert.neverUp2Late.util.ChatMessages;
import eu.nurkert.neverUp2Late.util.Theme;
import eu.nurkert.neverUp2Late.Permissions;
import eu.nurkert.neverUp2Late.core.PluginContext;
import eu.nurkert.neverUp2Late.update.UpdateSourceRegistry.UpdateSource;
import eu.nurkert.neverUp2Late.persistence.UpdateStateRepository;
import eu.nurkert.neverUp2Late.gui.PluginOverviewGui;
import eu.nurkert.neverUp2Late.setup.InitialSetupManager;
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
                sender.sendMessage(Theme.ERROR + "You do not have permission to open the GUI.");
                return true;
            }
            if (sender instanceof Player player) {
                overviewGui.open(player);
            } else {
                sender.sendMessage(Theme.ERROR + "The graphical interface can only be opened by players.");
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
                sender.sendMessage(Theme.ERROR + "You do not have permission to trigger update checks.");
                return true;
            }
            List<UpdateSource> sources;
            if (args.length >= 2) {
                Optional<UpdateSource> single = context.getUpdateSourceRegistry().findSource(args[1]);
                if (single.isEmpty()) {
                    ChatMessages.send(sender, Theme.PREFIX + Theme.ERROR + "No update source named " + args[1] + ".",
                            ChatMessages.Button.command("[Show all]", "/nu2l status", "List every update source"));
                    return true;
                }
                sources = List.of(single.get());
            } else {
                sources = List.copyOf(context.getUpdateSourceRegistry().getSources());
            }
            if (sources.isEmpty()) {
                sender.sendMessage(Theme.WARNING + "No update sources are configured yet.");
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
                sender.sendMessage(Theme.ERROR + "You do not have permission to view source status.");
                return true;
            }
            displayStatus(sender);
            return true;
        }

        if (args.length > 0 && "select".equalsIgnoreCase(args[0])) {
            if (!sender.hasPermission(Permissions.INSTALL)) {
                sender.sendMessage(Theme.ERROR + "You do not have permission to manage installations.");
                return true;
            }
            if (args.length < 2) {
                sender.sendMessage(Theme.ERROR + "Please specify the number of the desired file.");
                return true;
            }
            coordinator.handleAssetSelection(sender, args[1]);
            return true;
        }

        if (args.length > 0 && "ignore".equalsIgnoreCase(args[0])) {
            if (!sender.hasPermission(Permissions.INSTALL)) {
                sender.sendMessage(Theme.ERROR + "You do not have permission to manage installations.");
                return true;
            }
            coordinator.confirmCompatibilityOverride(sender);
            return true;
        }

        if (args.length > 0 && "cancel".equalsIgnoreCase(args[0])) {
            if (!sender.hasPermission(Permissions.INSTALL)) {
                sender.sendMessage(Theme.ERROR + "You do not have permission to manage installations.");
                return true;
            }
            coordinator.cancelCompatibilityOverride(sender);
            return true;
        }

        if (args.length > 0 && "setup".equalsIgnoreCase(args[0])) {
            if (setupManager == null) {
                sender.sendMessage(Theme.ERROR + "The setup utilities are not available.");
                return true;
            }
            if (!sender.hasPermission(Permissions.SETUP)) {
                sender.sendMessage(Theme.ERROR + "You do not have permission to manage the setup wizard.");
                return true;
            }

            if (args.length == 1) {
                if (sender instanceof Player player) {
                    setupManager.openWizard(player);
                } else {
                    sender.sendMessage(Theme.WARNING + "Run /" + label + " setup complete to finish the wizard from the console.");
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
                    sender.sendMessage(Theme.ERROR + "Please provide the path or identifier of the configuration file to apply.");
                    return true;
                }
                String configIdentifier = String.join(" ", Arrays.copyOfRange(args, 2, args.length));
                setupManager.applyConfiguration(sender, configIdentifier.trim());
                return true;
            }

            if (sender instanceof Player player) {
                setupManager.openWizard(player);
            } else {
                sender.sendMessage(Theme.ERROR + "Unknown setup sub-command: " + subCommand);
            }
            return true;
        }

        if (args.length > 0 && "remove".equalsIgnoreCase(args[0])) {
            if (!sender.hasPermission(Permissions.GUI_MANAGE_REMOVE)) {
                sender.sendMessage(Theme.ERROR + "You do not have permission to remove plugins.");
                return true;
            }
            if (args.length < 2) {
                sender.sendMessage(Theme.ERROR + "Please provide the plugin name.");
                return true;
            }
            String pluginName = String.join(" ", Arrays.copyOfRange(args, 1, args.length));
            coordinator.removePlugin(sender, pluginName.trim());
            return true;
        }

        if (args.length > 0 && "rollback".equalsIgnoreCase(args[0])) {
            if (!sender.hasPermission(Permissions.INSTALL)) {
                sender.sendMessage(Theme.ERROR + "You do not have permission to manage installations.");
                return true;
            }
            if (args.length < 2) {
                sender.sendMessage(Theme.ERROR + "Please provide the update source name to roll back.");
                return true;
            }
            String pluginName = String.join(" ", Arrays.copyOfRange(args, 1, args.length)).trim();
            if (pluginName.isEmpty()) {
                sender.sendMessage(Theme.ERROR + "Please provide the update source name to roll back.");
                return true;
            }
            coordinator.rollback(sender, pluginName);
            return true;
        }

        if (!sender.hasPermission(Permissions.INSTALL)) {
            sender.sendMessage(Theme.ERROR + "You do not have permission to manage installations.");
            return true;
        }

        String input = String.join(" ", args).trim();
        if (input.isEmpty()) {
            sender.sendMessage(Theme.ERROR + "Please provide a valid URL.");
            return true;
        }

        if (isLikelyUrl(input)) {
            coordinator.install(sender, input);
            return true;
        }

        if (!(sender instanceof Player player)) {
            sender.sendMessage(Theme.ERROR + "The search shortcut can only be used by players.");
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
            ChatMessages.send(sender, Theme.PREFIX + Theme.WARNING + "No update sources are registered yet.",
                    ChatMessages.Button.suggest("[Add a plugin]", "/nu2l ", "Paste a Modrinth, Hangar, GitHub, … link"));
            return;
        }

        int waiting = 0;
        int failing = 0;
        int held = 0;
        long newestCheck = 0L;
        List<Runnable> rows = new ArrayList<>();

        for (PluginContext.UpdateSourceStatus status : statuses) {
            Optional<UpdateStateRepository.CheckState> check =
                    context.getPersistentPluginHandler().getCheckState(status.displayName());

            String icon;
            String state;
            String detail = null;
            if (check.isEmpty()) {
                icon = Theme.SUBTLE + Theme.DOT;
                state = Theme.SUBTLE + "not checked yet";
            } else {
                UpdateStateRepository.CheckState value = check.get();
                newestCheck = Math.max(newestCheck, value.checkedAt());
                switch (value.result()) {
                    case FAILED -> {
                        failing++;
                        icon = Theme.ERROR + Theme.CROSS;
                        state = Theme.ERROR + "check failed";
                        detail = value.error() == null ? "unknown reason" : value.error();
                    }
                    case UPDATED -> {
                        waiting++;
                        icon = Theme.WARNING + Theme.UP;
                        // The version is already in the line; what matters is that it is not live yet.
                        state = Theme.WARNING + "restart pending";
                    }
                    case HELD -> {
                        held++;
                        icon = Theme.HEADING + Theme.WARN;
                        state = Theme.HEADING + "held back";
                        detail = value.error() == null ? "installation refused" : value.error();
                    }
                    default -> {
                        icon = Theme.SUCCESS + Theme.CHECK;
                        state = Theme.MUTED + "up to date";
                    }
                }
            }

            String name = status.displayName();
            String line = " " + icon + " " + Theme.TEXT + name + Theme.SUBTLE + "  " + Theme.ACCENT
                    + status.versionLabel() + Theme.SUBTLE + "  " + state
                    + (status.autoUpdateEnabled() ? "" : Theme.SUBTLE + " · auto-update off");
            String hover = Theme.HEADING + name + "\n"
                    + Theme.MUTED + "Plugin: " + Theme.TEXT + status.pluginDisplayName() + "\n"
                    + Theme.MUTED + "File: " + Theme.TEXT + (status.targetPath() != null ? status.targetPath().getFileName() : "-") + "\n"
                    + Theme.MUTED + "Installed: " + Theme.TEXT + status.versionLabel() + "\n"
                    + Theme.MUTED + "Auto-update: " + (status.autoUpdateEnabled() ? Theme.SUCCESS + "on" : Theme.ERROR + "off")
                    + check.map(value -> "\n" + Theme.MUTED + "Checked: " + Theme.TEXT + describeAge(value.checkedAt())).orElse("")
                    + (detail != null ? "\n" + Theme.ERROR + detail : "");
            List<ChatMessages.Button> buttons = new ArrayList<>();
            buttons.add(ChatMessages.Button.command("[Check]", "/nu2l check " + name, "Look for a new " + name + " now"));
            if (detail != null) {
                buttons.add(ChatMessages.Button.suggest("[Rollback]", "/nu2l rollback " + name,
                        "Put the previous version back (press Enter to confirm)"));
            }
            rows.add(() -> ChatMessages.sendWithHover(sender, line, hover, buttons.toArray(ChatMessages.Button[]::new)));
        }

        // The headline first: an operator wants the answer, not a table to read.
        sender.sendMessage(Theme.rule(40));
        sender.sendMessage(" " + Theme.gradient("NeverUp2Late", 0x3DD6D0, 0x3B82F6) + Theme.SUBTLE + "  "
                + Theme.TEXT + statuses.size() + Theme.MUTED + " sources" + Theme.SUBTLE + " · "
                + (waiting > 0 ? Theme.WARNING : Theme.TEXT) + waiting + Theme.MUTED + " waiting for a restart" + Theme.SUBTLE + " · "
                + (failing > 0 ? Theme.ERROR : Theme.TEXT) + failing + Theme.MUTED + " failing"
                + (held > 0 ? Theme.SUBTLE + " · " + Theme.HEADING + held + Theme.MUTED + " held back" : ""));
        ChatMessages.send(sender, " " + Theme.MUTED + "Last check: " + Theme.TEXT + describeAge(newestCheck),
                ChatMessages.Button.command("[Check all]", "/nu2l check", "Look for updates to everything now"),
                ChatMessages.Button.command("[Plan]", "/nu2l plan", "See what an update would do, without changing anything"));
        sender.sendMessage("");
        rows.forEach(Runnable::run);
        sender.sendMessage(Theme.rule(40));
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
        String command = "/" + label;
        sender.sendMessage(Theme.rule(40));
        sender.sendMessage(" " + Theme.gradient("NeverUp2Late", 0x3DD6D0, 0x3B82F6)
                + Theme.MUTED + "  keeps your server and plugins current");
        sender.sendMessage("");
        helpLine(sender, command, "", "Open the plugin overview", true);
        helpLine(sender, command, " <url>", "Install a plugin from a Modrinth, Hangar, GitHub, … link", false);
        helpLine(sender, command, " status", "What is tracked, what is due, what failed", true);
        helpLine(sender, command, " plan", "Show what an update would do, changing nothing", true);
        helpLine(sender, command, " check", "Look for updates now and install them", true);
        helpLine(sender, command, " rollback <source>", "Put the previous version back", false);
        helpLine(sender, command, " setup", "Run the first-time setup", true);
        sender.sendMessage("");
        sender.sendMessage(" " + Theme.SUBTLE + "Click a command to " + Theme.MUTED + "run" + Theme.SUBTLE
                + " it, or to put it in your chat box when it needs more.");
        sender.sendMessage(Theme.rule(40));
    }

    /**
     * @param complete whether the command can run as it stands; otherwise
     *                 clicking only puts it into the chat box
     */
    private void helpLine(CommandSender sender, String command, String arguments, String description, boolean complete) {
        String full = command + arguments;
        String runnable = complete ? full : command + arguments.substring(0, Math.max(0, arguments.indexOf('<'))).stripTrailing() + " ";
        ChatMessages.Button button = complete
                ? ChatMessages.Button.command(full, full, description)
                : ChatMessages.Button.suggest(full, runnable, description);
        if (sender instanceof Player) {
            ChatMessages.send(sender, " " + Theme.SUBTLE + Theme.ARROW, button);
        } else {
            sender.sendMessage(" " + Theme.SUBTLE + Theme.ARROW + " " + Theme.ACCENT + full);
        }
        sender.sendMessage("     " + Theme.MUTED + description);
    }
}
