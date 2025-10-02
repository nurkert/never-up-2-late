package eu.nurkert.neverUp2Late.command;

import eu.nurkert.neverUp2Late.gui.PluginOverviewGui;
import org.bukkit.ChatColor;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;

import java.util.Collections;
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
        if (!sender.hasPermission("neverup2late.install")) {
            sender.sendMessage(ChatColor.RED + "Du hast keine Berechtigung, um Updates zu verwalten.");
            return true;
        }

        if (args.length == 0 || "gui".equalsIgnoreCase(args[0])) {
            if (sender instanceof Player player) {
                overviewGui.open(player);
            } else {
                sender.sendMessage(ChatColor.RED + "Die grafische Oberfläche kann nur von Spielern geöffnet werden.");
            }
            return true;
        }

        if (args.length > 0 && "select".equalsIgnoreCase(args[0])) {
            if (args.length < 2) {
                sender.sendMessage(ChatColor.RED + "Bitte gib die Nummer der gewünschten Datei an.");
                return true;
            }
            coordinator.handleAssetSelection(sender, args[1]);
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
            return List.of("gui", "select");
        }
        if (args.length == 2 && "select".equalsIgnoreCase(args[0])) {
            return Collections.singletonList("<nummer>");
        }
        return Collections.emptyList();
    }
}
