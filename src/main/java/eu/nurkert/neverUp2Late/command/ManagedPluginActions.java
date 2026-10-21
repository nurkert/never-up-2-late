package eu.nurkert.neverUp2Late.command;

import eu.nurkert.neverUp2Late.util.Theme;
import eu.nurkert.neverUp2Late.Permissions;
import eu.nurkert.neverUp2Late.handlers.ArtifactDownloader;
import eu.nurkert.neverUp2Late.handlers.PersistentPluginHandler;
import eu.nurkert.neverUp2Late.persistence.PluginUpdateSettingsRepository;
import eu.nurkert.neverUp2Late.persistence.PluginUpdateSettingsRepository.PluginUpdateSettings;
import eu.nurkert.neverUp2Late.plugin.ManagedPlugin;
import eu.nurkert.neverUp2Late.plugin.PluginLifecycleException;
import eu.nurkert.neverUp2Late.plugin.PluginLifecycleManager;
import eu.nurkert.neverUp2Late.update.UpdateSourceRegistry;
import eu.nurkert.neverUp2Late.update.UpdateSourceRegistry.UpdateSource;
import eu.nurkert.neverUp2Late.util.FileNameSanitizer;
import org.bukkit.command.CommandSender;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitScheduler;

import java.io.IOException;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.CodeSource;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.function.BiConsumer;
import java.util.function.BiPredicate;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * The actions the plugin overview offers on an already installed plugin:
 * removing it, renaming its jar and switching its automatic updates off.
 *
 * <p>Each of them has to keep the update sources, the stored plugin info and
 * the per-plugin settings in step with what happens to the jar, or the next
 * update run would bring back what was just removed.</p>
 */
final class ManagedPluginActions {

    private final JavaPlugin plugin;
    private final BukkitScheduler scheduler;
    private final FileConfiguration configuration;
    private final UpdateSourceRegistry updateSourceRegistry;
    private final PersistentPluginHandler persistentPluginHandler;
    private final PluginUpdateSettingsRepository pluginUpdateSettingsRepository;
    private final PluginLifecycleManager pluginLifecycleManager;
    private final ArtifactDownloader artifactDownloader;
    private final Logger logger;
    private final BiConsumer<CommandSender, String> messenger;
    private final BiPredicate<CommandSender, String> permissionCheck;

    ManagedPluginActions(JavaPlugin plugin,
                         BukkitScheduler scheduler,
                         FileConfiguration configuration,
                         UpdateSourceRegistry updateSourceRegistry,
                         PersistentPluginHandler persistentPluginHandler,
                         PluginUpdateSettingsRepository pluginUpdateSettingsRepository,
                         PluginLifecycleManager pluginLifecycleManager,
                         ArtifactDownloader artifactDownloader,
                         Logger logger,
                         BiConsumer<CommandSender, String> messenger,
                         BiPredicate<CommandSender, String> permissionCheck) {
        this.plugin = plugin;
        this.scheduler = scheduler;
        this.configuration = configuration;
        this.updateSourceRegistry = updateSourceRegistry;
        this.persistentPluginHandler = persistentPluginHandler;
        this.pluginUpdateSettingsRepository = pluginUpdateSettingsRepository;
        this.pluginLifecycleManager = pluginLifecycleManager;
        this.artifactDownloader = artifactDownloader;
        this.logger = logger;
        this.messenger = messenger;
        this.permissionCheck = permissionCheck;
    }

    private boolean isSelfPlugin(ManagedPlugin target) {
        if (target == null) {
            return false;
        }
        if (target.getPlugin().map(plugin::equals).orElse(false)) {
            return true;
        }
        String targetName = target.getName();
        if (targetName != null && targetName.equalsIgnoreCase(plugin.getName())) {
            return true;
        }
        Path path = target.getPath();
        if (path == null) {
            return false;
        }
        try {
            CodeSource codeSource = plugin.getClass().getProtectionDomain().getCodeSource();
            if (codeSource == null || codeSource.getLocation() == null) {
                return false;
            }
            Path selfPath = Path.of(codeSource.getLocation().toURI());
            if (!Files.exists(selfPath)) {
                return false;
            }
            return path.toAbsolutePath().normalize().equals(selfPath.toAbsolutePath().normalize());
        } catch (Exception ex) {
            return false;
        }
    }

    void removeManagedPlugin(CommandSender sender, ManagedPlugin target) {
        if (target == null) {
            messenger.accept(sender, Theme.ERROR + "Unknown plugin – action cancelled.");
            return;
        }
        if (!permissionCheck.test(sender, Permissions.GUI_MANAGE_REMOVE)) {
            return;
        }
        if (isSelfPlugin(target)) {
            messenger.accept(sender, Theme.ERROR + "NeverUp2Late cannot remove itself while it is running.");
            return;
        }
        if (pluginLifecycleManager == null) {
            messenger.accept(sender, Theme.ERROR + "Plugin management is disabled; removal not possible.");
            return;
        }

        scheduler.runTask(plugin, () -> {
            String pluginName = Optional.ofNullable(target.getName()).orElse("Plugin");
            Path jarPath = target.getPath();
            ArtifactDownloader.BackupRecord backupRecord = null;

            try {
                if (target.isEnabled()) {
                    pluginLifecycleManager.disablePlugin(pluginName);
                }
                if (target.isLoaded()) {
                    pluginLifecycleManager.unloadPlugin(pluginName);
                }
            } catch (PluginLifecycleException ex) {
                messenger.accept(sender, Theme.ERROR + "Failed to unload " + pluginName + ": " + ex.getMessage());
                logger.log(Level.WARNING, "Failed to unload plugin " + pluginName, ex);
                return;
            }

            if (jarPath != null) {
                try {
                    backupRecord = artifactDownloader.backupExistingFile(jarPath, pluginName, pluginName)
                            .orElse(null);
                    Files.deleteIfExists(jarPath);
                } catch (IOException ex) {
                    // rollback backup if deletion failed after moving
                    if (backupRecord != null) {
                        try {
                            Files.move(backupRecord.getPath(), jarPath, StandardCopyOption.REPLACE_EXISTING);
                            messenger.accept(sender, Theme.WARNING + "Removal aborted; restored original file from backup.");
                        } catch (IOException restoreEx) {
                            messenger.accept(sender, Theme.ERROR + "Could not delete or restore file: " + jarPath.getFileName());
                            logger.log(Level.SEVERE, "Failed to restore backup for " + jarPath, restoreEx);
                        }
                    } else {
                        messenger.accept(sender, Theme.ERROR + "Could not delete file: " + jarPath.getFileName());
                    }
                    logger.log(Level.WARNING, "Failed to delete plugin jar " + jarPath, ex);
                    return;
                }
            }

            List<UpdateSource> matchingSources = updateSourceRegistry.getSources().stream()
                    .filter(source -> matchesPluginSource(source, pluginName, jarPath))
                    .toList();

            for (UpdateSource source : matchingSources) {
                if (updateSourceRegistry.unregisterSource(source.getName())) {
                    configuration.set("filenames." + source.getName(), null);
                    persistentPluginHandler.removePluginInfo(source.getName());
                }
            }

            if (!matchingSources.isEmpty()) {
                plugin.saveConfig();
            }

            if (pluginUpdateSettingsRepository != null) {
                pluginUpdateSettingsRepository.removeSettings(pluginName);
            }

            messenger.accept(sender, Theme.SUCCESS + "Plugin " + Theme.ACCENT + pluginName + Theme.SUCCESS + " has been removed.");
        });
    }

    private boolean matchesPluginSource(UpdateSource source, String pluginName, Path jarPath) {
        if (source == null) {
            return false;
        }
        String filename = source.getFilename();
        if (jarPath != null && jarPath.getFileName() != null
                && filename != null
                && jarPath.getFileName().toString().equalsIgnoreCase(filename)) {
            return true;
        }
        String installedPlugin = source.getInstalledPluginName();
        return installedPlugin != null && installedPlugin.equalsIgnoreCase(pluginName);
    }

    void removePlugin(CommandSender sender, String pluginName) {
        if (pluginLifecycleManager == null) {
            messenger.accept(sender, Theme.ERROR + "Plugin management is disabled; removal not possible.");
            return;
        }
        ManagedPlugin target = pluginLifecycleManager.findByName(pluginName).orElse(null);
        if (target == null) {
            messenger.accept(sender, Theme.ERROR + "Plugin " + pluginName + " was not found.");
            return;
        }
        removeManagedPlugin(sender, target);
    }

    void disableAutomaticUpdates(CommandSender sender, ManagedPlugin target) {
        if (target == null) {
            messenger.accept(sender, Theme.ERROR + "Unknown plugin – action cancelled.");
            return;
        }
        if (!permissionCheck.test(sender, Permissions.GUI_MANAGE_LINK)) {
            return;
        }

        String initialPluginName = Optional.ofNullable(target.getName()).orElse("");
        Path jarPath = target.getPath();

        List<UpdateSource> matchingSources = updateSourceRegistry.getSources().stream()
                .filter(source -> matchesPluginSource(source, initialPluginName, jarPath))
                .toList();

        String pluginName = initialPluginName;
        String displayName = pluginName.isBlank() ? "Plugin" : pluginName;

        if (matchingSources.isEmpty()) {
            messenger.accept(sender, Theme.WARNING + "No update source is configured for "
                    + Theme.ACCENT + displayName + Theme.WARNING + ".");
            return;
        }

        if (pluginName.isBlank()) {
            pluginName = matchingSources.stream()
                    .map(UpdateSource::getInstalledPluginName)
                    .filter(Objects::nonNull)
                    .findFirst()
                    .orElse("");
            if (!pluginName.isBlank()) {
                displayName = pluginName;
            }
        }

        int removed = 0;
        for (UpdateSource source : matchingSources) {
            if (updateSourceRegistry.unregisterSource(source.getName())) {
                configuration.set("filenames." + source.getName(), null);
                persistentPluginHandler.removePluginInfo(source.getName());
                removed++;
            }
        }

        if (removed > 0) {
            plugin.saveConfig();
        }

        if (pluginUpdateSettingsRepository != null && !pluginName.isBlank()) {
            PluginUpdateSettings current = pluginUpdateSettingsRepository.getSettings(pluginName);
            if (current.autoUpdateEnabled()) {
                pluginUpdateSettingsRepository.saveSettings(pluginName,
                        new PluginUpdateSettings(false, current.behaviour(), current.retainUpstreamFilename()));
            }
        }

        if (removed > 0) {
            messenger.accept(sender, Theme.WARNING + "Automatic updates for " + Theme.ACCENT + displayName
                    + Theme.WARNING + " have been disabled.");
            messenger.accept(sender, Theme.MUTED + "The plugin remains installed; only the update source was removed.");
        } else {
            messenger.accept(sender, Theme.ERROR + "Could not disable automatic updates.");
        }
    }

    void renameManagedPlugin(CommandSender sender, ManagedPlugin target, String requestedName) {
        if (target == null) {
            messenger.accept(sender, Theme.ERROR + "Unknown plugin – action cancelled.");
            return;
        }
        if (!permissionCheck.test(sender, Permissions.GUI_MANAGE_RENAME)) {
            return;
        }
        if (isSelfPlugin(target)) {
            messenger.accept(sender, Theme.ERROR + "NeverUp2Late manages its own file name and cannot be renamed here.");
            return;
        }
        if (pluginLifecycleManager == null) {
            messenger.accept(sender, Theme.ERROR + "Plugin management is disabled; rename not possible.");
            return;
        }

        scheduler.runTask(plugin, () -> {
            Path currentPath = target.getPath();
            if (currentPath == null) {
                messenger.accept(sender, Theme.ERROR + "Could not determine a file path.");
                return;
            }

            String sanitized = FileNameSanitizer.sanitizeJarFilename(requestedName);
            if (sanitized == null) {
                messenger.accept(sender, Theme.ERROR + "Please provide a valid file name.");
                return;
            }

            Path parent = currentPath.getParent();
            if (parent == null) {
                messenger.accept(sender, Theme.ERROR + "Could not determine target directory.");
                return;
            }
            Path targetPath = parent.resolve(sanitized).toAbsolutePath().normalize();
            if (targetPath.equals(currentPath)) {
                messenger.accept(sender, Theme.MUTED + "The file name remains unchanged.");
                return;
            }
            if (Files.exists(targetPath)) {
                messenger.accept(sender, Theme.ERROR + "A file with that name already exists: " + targetPath.getFileName());
                return;
            }

            boolean wasLoaded = target.isLoaded();
            String pluginName = Optional.ofNullable(target.getName()).orElse("Plugin");

            try {
                if (target.isEnabled()) {
                    pluginLifecycleManager.disablePlugin(pluginName);
                }
                if (target.isLoaded()) {
                    pluginLifecycleManager.unloadPlugin(pluginName);
                }
            } catch (PluginLifecycleException ex) {
                messenger.accept(sender, Theme.ERROR + "Failed to unload " + pluginName + ": " + ex.getMessage());
                logger.log(Level.WARNING, "Failed to unload plugin for rename " + pluginName, ex);
                return;
            }

            try {
                Files.move(currentPath, targetPath,
                        StandardCopyOption.REPLACE_EXISTING,
                        StandardCopyOption.ATOMIC_MOVE);
            } catch (AtomicMoveNotSupportedException ex) {
                try {
                    Files.move(currentPath, targetPath, StandardCopyOption.REPLACE_EXISTING);
                } catch (IOException inner) {
                    messenger.accept(sender, Theme.ERROR + "Rename failed: " + inner.getMessage());
                    logger.log(Level.WARNING, "Failed to rename plugin jar", inner);
                    return;
                }
            } catch (IOException ex) {
                messenger.accept(sender, Theme.ERROR + "Rename failed: " + ex.getMessage());
                logger.log(Level.WARNING, "Failed to rename plugin jar", ex);
                return;
            }

            List<UpdateSource> matchingSources = updateSourceRegistry.getSources().stream()
                    .filter(source -> matchesPluginSource(source, pluginName, currentPath))
                    .toList();

            for (UpdateSource source : matchingSources) {
                if (updateSourceRegistry.updateSourceFilename(source.getName(), sanitized)) {
                    configuration.set("filenames." + source.getName(), sanitized);
                }
            }
            if (!matchingSources.isEmpty()) {
                plugin.saveConfig();
            }

            pluginLifecycleManager.updateManagedPluginPath(currentPath, targetPath);

            messenger.accept(sender, Theme.SUCCESS + "Renamed plugin file to " + Theme.ACCENT + sanitized + Theme.SUCCESS + ".");
            if (wasLoaded) {
                messenger.accept(sender, Theme.WARNING + "Please reload or restart the server to activate the plugin again.");
            }
        });
    }
}
