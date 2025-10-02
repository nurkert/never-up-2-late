package eu.nurkert.neverUp2Late.command;

import eu.nurkert.neverUp2Late.Permissions;
import eu.nurkert.neverUp2Late.gui.PluginOverviewGui;
import org.bukkit.ChatColor;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;

import java.util.Collections;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;

public class NeverUp2LateCommand implements CommandExecutor, TabCompleter {

    private final QuickInstallCoordinator coordinator;
    private final PluginOverviewGui overviewGui;

    public NeverUp2LateCommand(QuickInstallCoordinator coordinator, PluginOverviewGui overviewGui) {
        this.coordinator = Objects.requireNonNull(coordinator, "coordinator");
        this.overviewGui = Objects.requireNonNull(overviewGui, "overviewGui");
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (args.length == 0 || "gui".equalsIgnoreCase(args[0])) {
            if (!sender.hasPermission(Permissions.GUI_OPEN)) {
                sender.sendMessage(ChatColor.RED + "Dir fehlt die Berechtigung, die GUI zu öffnen.");
                return true;
            }
            if (sender instanceof Player player) {
                overviewGui.open(player);
            } else {
                sender.sendMessage(ChatColor.RED + "Die grafische Oberfläche kann nur von Spielern geöffnet werden.");
            }
            return true;
        }

        if (args.length > 0 && "select".equalsIgnoreCase(args[0])) {
            if (!sender.hasPermission(Permissions.INSTALL)) {
                sender.sendMessage(ChatColor.RED + "Dir fehlt die Berechtigung, Installationen zu verwalten.");
                return true;
            }
            if (args.length < 2) {
                sender.sendMessage(ChatColor.RED + "Bitte gib die Nummer der gewünschten Datei an.");
                return true;
            }
            coordinator.handleAssetSelection(sender, args[1]);
            return true;
        }

        if (args.length > 0 && "remove".equalsIgnoreCase(args[0])) {
            if (!sender.hasPermission(Permissions.GUI_MANAGE_REMOVE)) {
                sender.sendMessage(ChatColor.RED + "Dir fehlt die Berechtigung, Plugins zu entfernen.");
                return true;
            }
            if (args.length < 2) {
                sender.sendMessage(ChatColor.RED + "Bitte gib den Namen des Plugins an.");
                return true;
            }
            String pluginName = String.join(" ", Arrays.copyOfRange(args, 1, args.length));
            coordinator.removePlugin(sender, pluginName.trim());
            return true;
        }

        if (!sender.hasPermission(Permissions.INSTALL)) {
            sender.sendMessage(ChatColor.RED + "Dir fehlt die Berechtigung, Installationen zu verwalten.");
            return true;
        }

        String url = String.join(" ", args).trim();
        if (url.isEmpty()) {
            sender.sendMessage(ChatColor.RED + "Bitte gib eine gültige URL an.");
            return true;
        }

        coordinator.install(sender, url);
        return true;
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        if (args.length == 1) {
            return List.of("gui", "select", "remove");
        }
        if (args.length == 2 && "select".equalsIgnoreCase(args[0])) {
            return Collections.singletonList("<nummer>");
        }
        return Collections.emptyList();
    }
}
