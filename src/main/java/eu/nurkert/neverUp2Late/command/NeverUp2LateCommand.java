package eu.nurkert.neverUp2Late.command;

import org.bukkit.ChatColor;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;

import java.util.Collections;
import java.util.List;
import java.util.Objects;

public class NeverUp2LateCommand implements CommandExecutor, TabCompleter {

    private final QuickInstallCoordinator coordinator;

    public NeverUp2LateCommand(QuickInstallCoordinator coordinator) {
        this.coordinator = Objects.requireNonNull(coordinator, "coordinator");
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!sender.hasPermission("neverup2late.install")) {
            sender.sendMessage(ChatColor.RED + "Du hast keine Berechtigung, um Updates zu verwalten.");
            return true;
        }

        if (args.length == 0) {
            sender.sendMessage(ChatColor.RED + "Verwendung: /" + label + " <url>");
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
        return Collections.emptyList();
    }
}
