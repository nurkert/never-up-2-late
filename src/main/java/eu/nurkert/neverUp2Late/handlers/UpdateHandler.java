package eu.nurkert.neverUp2Late.handlers;

import eu.nurkert.neverUp2Late.net.HttpException;
import eu.nurkert.neverUp2Late.persistence.PluginUpdateSettingsRepository;
import eu.nurkert.neverUp2Late.plugin.ManagedPlugin;
import eu.nurkert.neverUp2Late.plugin.PluginLifecycleManager;
import eu.nurkert.neverUp2Late.persistence.SetupStateRepository;
import eu.nurkert.neverUp2Late.persistence.SetupStateRepository.SetupPhase;
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
import java.net.URI;
import java.net.UnknownHostException;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Locale;
import java.util.Objects;
import java.util.concurrent.locks.ReentrantLock;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.Map;
import java.util.HashMap;

public class UpdateHandler {

    private static final long MINIMUM_UPDATE_INTERVAL_MINUTES = 30L;

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
    private final SetupStateRepository setupStateRepository;

    private volatile boolean shuttingDown;
    private BukkitTask scheduledTask;
    private final ReentrantLock updateRunLock = new ReentrantLock();

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
                         PluginUpdateSettingsRepository updateSettingsRepository,
                         SetupStateRepository setupStateRepository) {
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
        this.setupStateRepository = setupStateRepository;
    }

    public void start() {
        long configuredIntervalMinutes = configuration.getInt("updateInterval");
        long intervalMinutes = Math.max(MINIMUM_UPDATE_INTERVAL_MINUTES, configuredIntervalMinutes);
        if (configuredIntervalMinutes < MINIMUM_UPDATE_INTERVAL_MINUTES) {
            logger.log(Level.INFO,
                    "Configured update interval of {0} minutes is below the minimum. Using {1} minutes instead.",
                    new Object[]{configuredIntervalMinutes, MINIMUM_UPDATE_INTERVAL_MINUTES});
        }
        long intervalTicks = intervalMinutes * 20L * 60L;
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
        if (!updateRunLock.tryLock()) {
            logger.log(Level.FINE, "Skipping update check because another update run is still in progress.");
            return;
        }
        try {
        if (setupStateRepository != null && setupStateRepository.getPhase() != SetupPhase.COMPLETED) {
            logger.log(Level.FINE, "Running updates while setup is incomplete (phase={0}).", setupStateRepository.getPhase());
        }
        boolean networkIssueThisRun = false;
        File pluginsFolder = plugin.getDataFolder().getParentFile();
        File serverFolder = server.getWorldContainer().getAbsoluteFile();

        // Avoid duplicate writes to the same destination within a single run
        Map<Path, String> destinationsSeen = new HashMap<>();

        for (UpdateSource source : updateSourceRegistry.getSources()) {
            if (shuttingDown || !plugin.isEnabled()) {
                break;
            }
            Path destination = resolveDestination(source, pluginsFolder, serverFolder);
            if (destination == null) {
                continue;
            }
            Path normalizedDest = destination != null ? destination.toAbsolutePath().normalize() : null;
            if (normalizedDest != null) {
                String existing = destinationsSeen.putIfAbsent(normalizedDest, source.getName());
                if (existing != null) {
                    logger.log(Level.WARNING,
                            "Skipped update for source {0} because destination {1} is already handled by {2}.",
                            new Object[]{source.getName(), normalizedDest, existing});
                    continue;
                }
            }
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
        } finally {
            updateRunLock.unlock();
        }
    }

    private Path resolveDestination(UpdateSource source, File pluginsFolder, File serverFolder) {
        if (source == null) {
            return null;
        }
        String filename = source.getFilename();
        if (filename == null || filename.isBlank()) {
            logger.log(Level.WARNING, "Update source {0} has no filename configured; skipping.", source.getName());
            return null;
        }
        File destinationDirectory = source.getTargetDirectory() == TargetDirectory.SERVER
                ? serverFolder
                : pluginsFolder;
        return new File(destinationDirectory, filename).toPath();
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
        if (!updateRunLock.tryLock()) {
            notify(sender, ChatColor.RED + "Another update run is currently in progress. Please try again shortly.");
            return;
        }
        try {
            File pluginsFolder = plugin.getDataFolder().getParentFile();
            File serverFolder = server.getWorldContainer().getAbsoluteFile();
            Path destination = resolveDestination(source, pluginsFolder, serverFolder);
            if (destination == null) {
                notify(sender, ChatColor.RED + "No filename configured for " + displayName(source) + "; update aborted.");
                return;
            }
            UpdateJob job = createDefaultJob();
            UpdateContext context = new UpdateContext(source, destination, logger);
            configureRetention(context, destination);

            notify(sender, ChatColor.YELLOW + "Checking " + displayName(source) + " for new versions…");

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
            if (e instanceof HttpException httpException) {
                handleHttpError(sender, source, httpException);
            } else {
                notify(sender, ChatColor.RED + "I/O error: " + e.getMessage());
                logger.log(Level.WARNING,
                        "I/O error while installing {0}: {1}", new Object[]{source.getName(), e.getMessage()});
            }
        } catch (Exception e) {
            notify(sender, ChatColor.RED + "Unexpected error: " + e.getMessage());
            logger.log(Level.SEVERE, "Unexpected error while running manual update for " + source.getName(), e);
        } finally {
            updateRunLock.unlock();
        }
    }

    private void handleHttpError(CommandSender sender, UpdateSource source, HttpException exception) {
        int statusCode = exception.getStatusCode();
        String hostDescription = describeHost(exception.getUrl());
        String displayHost = hostDescription != null ? hostDescription : "the remote server";

        String message;
        if (statusCode == 401 || statusCode == 403) {
            message = "Download blocked by " + displayHost + " (HTTP " + statusCode
                    + "). This release may require authentication or a paid subscription.";
        } else {
            message = "HTTP " + statusCode + " error while downloading from " + displayHost + ".";
        }

        notify(sender, ChatColor.RED + message);
        logger.log(Level.WARNING,
                "HTTP error {0} while installing {1} from {2}: {3}",
                new Object[]{statusCode, source.getName(), exception.getUrl(), truncate(exception.getResponseBody())});
    }

    private String describeHost(String url) {
        if (url == null || url.isBlank()) {
            return null;
        }
        try {
            URI uri = URI.create(url);
            String host = uri.getHost();
            if (host == null || host.isBlank()) {
                return null;
            }
            String normalized = host.toLowerCase(Locale.ROOT);
            if (normalized.startsWith("www.")) {
                normalized = normalized.substring(4);
            }
            return normalized;
        } catch (IllegalArgumentException ignored) {
            return null;
        }
    }

    private String truncate(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        if (trimmed.length() <= 200) {
            return trimmed;
        }
        return trimmed.substring(0, 200) + "…";
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
            pluginName = source.getInstalledPluginName();
        }
        if (pluginName == null || pluginName.isBlank()) {
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
        remoteFilename = sanitizeFilename(remoteFilename);
        if (remoteFilename == null || remoteFilename.isBlank()) {
            return;
        }
        Path newPath = parent.resolve(remoteFilename).toAbsolutePath().normalize();
        if (!newPath.getParent().equals(parent.toAbsolutePath().normalize())) {
            logger.log(Level.WARNING,
                    "Refusing to rename downloaded artifact outside of target directory. Requested filename: {0}",
                    remoteFilename);
            return;
        }

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

    private String sanitizeFilename(String candidate) {
        if (candidate == null) {
            return null;
        }
        String trimmed = candidate.trim();
        if (trimmed.isEmpty()) {
            return null;
        }
        int lastSlash = Math.max(trimmed.lastIndexOf('/'), trimmed.lastIndexOf('\\'));
        String baseName = lastSlash >= 0 ? trimmed.substring(lastSlash + 1) : trimmed;
        if (baseName.isEmpty()) {
            return null;
        }
        if (baseName.contains("..")) {
            return null;
        }
        return baseName;
    }
}
