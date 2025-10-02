package eu.nurkert.neverUp2Late.handlers;

import eu.nurkert.neverUp2Late.persistence.PluginUpdateSettingsRepository;
import eu.nurkert.neverUp2Late.plugin.ManagedPlugin;
import eu.nurkert.neverUp2Late.plugin.PluginLifecycleManager;
import eu.nurkert.neverUp2Late.update.DownloadUpdateStep;
import eu.nurkert.neverUp2Late.update.FetchUpdateStep;
import eu.nurkert.neverUp2Late.update.InstallUpdateStep;
import eu.nurkert.neverUp2Late.update.UpdateContext;
import eu.nurkert.neverUp2Late.update.UpdateJob;
import eu.nurkert.neverUp2Late.update.UpdateSourceRegistry;
import eu.nurkert.neverUp2Late.update.UpdateSourceRegistry.TargetDirectory;
import eu.nurkert.neverUp2Late.update.UpdateSourceRegistry.UpdateSource;
import eu.nurkert.neverUp2Late.update.VersionComparator;
import org.bukkit.ChatColor;
import org.bukkit.Server;
import org.bukkit.command.CommandSender;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitScheduler;
import org.bukkit.scheduler.BukkitTask;

import java.io.File;
import java.io.IOException;
import java.net.UnknownHostException;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Objects;
import java.util.logging.Level;
import java.util.logging.Logger;

public class UpdateHandler {

    private final JavaPlugin plugin;
    private final Server server;
    private final BukkitScheduler scheduler;
    private final FileConfiguration configuration;
    private final PersistentPluginHandler persistentPluginHandler;
    private final InstallationHandler installationHandler;
    private final UpdateSourceRegistry updateSourceRegistry;
    private final ArtifactDownloader artifactDownloader;
    private final VersionComparator versionComparator;
    private final Logger logger;
    private final String messagePrefix;
    private final PluginLifecycleManager pluginLifecycleManager;
    private final PluginUpdateSettingsRepository updateSettingsRepository;

    private volatile boolean shuttingDown;
    private BukkitTask scheduledTask;

    private boolean networkWarningShown;

    public UpdateHandler(JavaPlugin plugin,
                         BukkitScheduler scheduler,
                         FileConfiguration configuration,
                         PersistentPluginHandler persistentPluginHandler,
                         InstallationHandler installationHandler,
                         UpdateSourceRegistry updateSourceRegistry,
                         ArtifactDownloader artifactDownloader,
                         VersionComparator versionComparator,
                         PluginLifecycleManager pluginLifecycleManager,
                         PluginUpdateSettingsRepository updateSettingsRepository) {
        this.plugin = plugin;
        this.server = plugin.getServer();
        this.scheduler = scheduler;
        this.configuration = configuration;
        this.persistentPluginHandler = persistentPluginHandler;
        this.installationHandler = installationHandler;
        this.updateSourceRegistry = updateSourceRegistry;
        this.artifactDownloader = artifactDownloader;
        this.versionComparator = versionComparator;
        this.logger = plugin.getLogger();
        this.messagePrefix = ChatColor.GRAY + "[" + ChatColor.AQUA + "nu2l" + ChatColor.GRAY + "] " + ChatColor.RESET;
        this.pluginLifecycleManager = pluginLifecycleManager;
        this.updateSettingsRepository = updateSettingsRepository;
    }

    public void start() {
        long intervalMinutes = configuration.getInt("updateInterval");
        long intervalTicks = Math.max(1L, intervalMinutes) * 20L * 60L;
        if (scheduledTask != null) {
            scheduledTask.cancel();
        }
        shuttingDown = false;
        scheduledTask = scheduler.runTaskTimerAsynchronously(plugin, this::checkForUpdates, 0L, intervalTicks);
    }

    public void stop() {
        shuttingDown = true;
        if (scheduledTask != null) {
            scheduledTask.cancel();
            scheduledTask = null;
        }
    }

    private void checkForUpdates() {
        if (shuttingDown || !plugin.isEnabled()) {
            return;
        }
        boolean networkIssueThisRun = false;
        File pluginsFolder = plugin.getDataFolder().getParentFile();
        File serverFolder = server.getWorldContainer().getAbsoluteFile();

        for (UpdateSource source : updateSourceRegistry.getSources()) {
            if (shuttingDown || !plugin.isEnabled()) {
                break;
            }
            Path destination = resolveDestination(source, pluginsFolder, serverFolder);
            if (shouldSkipAutomaticUpdate(source, destination)) {
                continue;
            }
            UpdateJob job = createDefaultJob();
            UpdateContext context = new UpdateContext(source, destination, logger);
            configureRetention(context, destination);

            try {
                job.run(context);
                handleFilenameRetention(context);
            } catch (UnknownHostException e) {
                networkIssueThisRun = true;
                handleUnknownHost(source, e);
            } catch (IOException e) {
                logger.log(Level.WARNING,
                        "I/O error while updating {0}: {1}", new Object[]{source.getName(), e.getMessage()});
            } catch (Exception e) {
                if (shuttingDown || !plugin.isEnabled()) {
                    logger.log(Level.FINEST, "Update check aborted while plugin is disabling", e);
                    break;
                }
                logger.log(Level.SEVERE, "Unexpected error while checking updates for " + source.getName(), e);
            }
        }

        if (!networkIssueThisRun && networkWarningShown) {
            logger.log(Level.INFO, "Connection to update servers restored. Resuming normal update checks.");
            networkWarningShown = false;
        }
    }

    private Path resolveDestination(UpdateSource source, File pluginsFolder, File serverFolder) {
        File destinationDirectory = source.getTargetDirectory() == TargetDirectory.SERVER
                ? serverFolder
                : pluginsFolder;
        return new File(destinationDirectory, source.getFilename()).toPath();
    }

    /**
     * Creates the default update pipeline consisting of fetch, download and
     * install steps. Plugins can register new steps by overriding this method
     * or by modifying the returned {@link UpdateJob} prior to execution in
     * {@link #checkForUpdates()}.
     */
    private UpdateJob createDefaultJob() {
        return new UpdateJob()
                .addStep(new FetchUpdateStep(persistentPluginHandler, versionComparator))
                .addStep(new DownloadUpdateStep(artifactDownloader))
                .addStep(new InstallUpdateStep(plugin, persistentPluginHandler, installationHandler));
    }

    private void handleUnknownHost(UpdateSource source, UnknownHostException e) {
        if (!networkWarningShown) {
            logger.log(Level.WARNING,
                    "Unable to reach update server while checking {0}: {1}. The plugin will retry automatically.",
                    new Object[]{source.getName(), e.getMessage()});
            networkWarningShown = true;
        }
    }

    public void runJobNow(UpdateSource source, CommandSender sender) {
        Objects.requireNonNull(source, "source");
        if (shuttingDown || !plugin.isEnabled()) {
            notify(sender, ChatColor.RED + "The updater is currently shutting down. Please try again later.");
            return;
        }
        scheduler.runTaskAsynchronously(plugin, () -> executeManualRun(source, sender));
    }

    private void executeManualRun(UpdateSource source, CommandSender sender) {
        if (shuttingDown || !plugin.isEnabled()) {
            notify(sender, ChatColor.RED + "The updater is currently shutting down. Please try again later.");
            return;
        }
        File pluginsFolder = plugin.getDataFolder().getParentFile();
        File serverFolder = server.getWorldContainer().getAbsoluteFile();
        Path destination = resolveDestination(source, pluginsFolder, serverFolder);
        UpdateJob job = createDefaultJob();
        UpdateContext context = new UpdateContext(source, destination, logger);
        configureRetention(context, destination);

        notify(sender, ChatColor.YELLOW + "Checking " + displayName(source) + " for new versions…");

        try {
            job.run(context);
            handleFilenameRetention(context);
            if (context.isCancelled()) {
                String reason = context.getCancelReason().orElse("Installation cancelled.");
                notify(sender, ChatColor.GOLD + reason);
                return;
            }

            String version = context.getLatestVersion();
            String buildInfo = version != null ? "Version " + version : "Build " + context.getLatestBuild();
            String destinationFile = destination.getFileName() != null ? destination.getFileName().toString() : destination.toString();
            notify(sender, ChatColor.GREEN + "Installation complete: " + displayName(source) + " "
                    + buildInfo + " → " + destinationFile + ". Please restart the server.");
        } catch (UnknownHostException e) {
            notify(sender, ChatColor.RED + "Download failed: " + e.getMessage());
            handleUnknownHost(source, e);
        } catch (IOException e) {
            notify(sender, ChatColor.RED + "I/O error: " + e.getMessage());
            logger.log(Level.WARNING,
                    "I/O error while installing {0}: {1}", new Object[]{source.getName(), e.getMessage()});
        } catch (Exception e) {
            notify(sender, ChatColor.RED + "Unexpected error: " + e.getMessage());
            logger.log(Level.SEVERE, "Unexpected error while running manual update for " + source.getName(), e);
        }
    }

    private void notify(CommandSender sender, String message) {
        if (sender == null || message == null) {
            return;
        }
        scheduler.runTask(plugin, () -> sender.sendMessage(messagePrefix + message));
    }

    private String displayName(UpdateSource source) {
        String name = source.getName();
        return name == null ? "Unknown source" : name;
    }

    private boolean shouldSkipAutomaticUpdate(UpdateSource source, Path destination) {
        if (updateSettingsRepository == null) {
            return false;
        }
        if (source.getTargetDirectory() != TargetDirectory.PLUGINS) {
            return false;
        }

        String pluginName = resolvePluginName(source, destination);
        if (pluginName == null) {
            return false;
        }

        return !updateSettingsRepository.getSettings(pluginName).autoUpdateEnabled();
    }

    private String resolvePluginName(UpdateSource source, Path destination) {
        String installedPlugin = source.getInstalledPluginName();
        if (installedPlugin != null && !installedPlugin.isBlank()) {
            return installedPlugin;
        }
        if (pluginLifecycleManager == null || destination == null) {
            return null;
        }
        return pluginLifecycleManager.findByPath(destination)
                .map(ManagedPlugin::getName)
                .orElse(null);
    }

    private void configureRetention(UpdateContext context, Path destination) {
        if (updateSettingsRepository == null) {
            context.setRetainUpstreamFilename(false);
            return;
        }
        String pluginName = resolvePluginName(context.getSource(), destination);
        if (pluginName == null) {
            pluginName = context.getSource().getInstalledPluginName();
        }
        if (pluginName == null) {
            context.setRetainUpstreamFilename(false);
            return;
        }
        context.setRetainUpstreamFilename(updateSettingsRepository.getSettings(pluginName).retainUpstreamFilename());
    }

    private void handleFilenameRetention(UpdateContext context) {
        if (!context.shouldRetainUpstreamFilename()) {
            return;
        }
        Path currentPath = context.getDownloadDestination();
        if (currentPath == null) {
            return;
        }
        Path parent = currentPath.getParent();
        if (parent == null) {
            return;
        }
        String remoteFilename = context.getRemoteFilename().orElse(null);
        if (remoteFilename == null || remoteFilename.isBlank()) {
            return;
        }
        Path newPath = parent.resolve(remoteFilename).toAbsolutePath().normalize();

        if (Files.exists(currentPath) && !currentPath.equals(newPath)) {
            try {
                Files.deleteIfExists(newPath);
                try {
                    Files.move(currentPath, newPath,
                            StandardCopyOption.REPLACE_EXISTING,
                            StandardCopyOption.ATOMIC_MOVE);
                } catch (AtomicMoveNotSupportedException ignored) {
                    Files.move(currentPath, newPath, StandardCopyOption.REPLACE_EXISTING);
                }
            } catch (IOException ex) {
                logger.log(Level.WARNING, "Failed to rename downloaded artifact to " + newPath, ex);
                return;
            }
            context.setDownloadDestination(newPath);
            context.setDownloadedArtifact(newPath);
            if (pluginLifecycleManager != null) {
                pluginLifecycleManager.updateManagedPluginPath(currentPath, newPath);
            }
            currentPath = newPath;
        }

        boolean updated = updateSourceRegistry.updateSourceFilename(context.getSource().getName(), currentPath.getFileName().toString());
        if (updated) {
            configuration.set("filenames." + context.getSource().getName(), currentPath.getFileName().toString());
            plugin.saveConfig();
        }
    }
}
