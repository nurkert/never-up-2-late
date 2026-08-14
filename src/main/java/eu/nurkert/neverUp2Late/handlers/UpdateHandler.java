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
import eu.nurkert.neverUp2Late.util.ArchiveUtils;
import eu.nurkert.neverUp2Late.util.LogThrottle;
import org.bukkit.ChatColor;
import org.bukkit.Server;
import org.bukkit.command.CommandSender;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.plugin.IllegalPluginAccessException;
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
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.ReentrantLock;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.Map;
import java.util.HashMap;

public class UpdateHandler {

    private static final long MINIMUM_UPDATE_INTERVAL_MINUTES = 30L;

    /**
     * How long the first check waits after the plugin was enabled. Starting
     * immediately would put a network round trip, disk writes and possibly a
     * restart into the middle of the server boot, while worlds are loading and
     * the other plugins are still enabling.
     */
    private static final long DEFAULT_STARTUP_DELAY_SECONDS = 60L;

    /** Time the shutdown waits for an update run that is still in flight. */
    private static final long SHUTDOWN_GRACE_SECONDS = 5L;

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
    private final LogThrottle logThrottle;

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
        this.logThrottle = new LogThrottle(this.logger);
        this.messagePrefix = ChatColor.GRAY + "[" + ChatColor.AQUA + "nu2l" + ChatColor.GRAY + "] " + ChatColor.RESET;
        this.pluginLifecycleManager = pluginLifecycleManager;
        this.updateSettingsRepository = updateSettingsRepository;
        this.setupStateRepository = setupStateRepository;
    }

    /**
     * Starts the periodic check, holding the first run back by the configured
     * startup delay so it does not collide with the server boot.
     */
    public void start() {
        start(Math.max(0L, configuration.getLong("startupDelaySeconds", DEFAULT_STARTUP_DELAY_SECONDS)));
    }

    /**
     * Starts the periodic check with the first run right away. Used after the
     * setup wizard, where the server is long up and the user expects to see
     * something happen.
     */
    public void startNow() {
        start(0L);
    }

    private void start(long startupDelaySeconds) {
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
        scheduledTask = scheduler.runTaskTimerAsynchronously(
                plugin, this::checkForUpdates, startupDelaySeconds * 20L, intervalTicks);
    }

    public void stop() {
        shuttingDown = true;
        if (scheduledTask != null) {
            scheduledTask.cancel();
            scheduledTask = null;
        }
        awaitRunningUpdate();
    }

    /**
     * Lets an update run that is currently in flight finish before the server
     * tears everything down, so downloads and file moves are not interrupted
     * halfway through.
     */
    private void awaitRunningUpdate() {
        if (server.isPrimaryThread()) {
            // Never stall the server thread on shutdown. The volatile
            // shuttingDown flag already makes the update run bail out at its
            // next checkpoint, and every download writes to a staging file that
            // is moved into place atomically - so being cut off mid-transfer
            // cannot damage the installed artifact.
            return;
        }
        try {
            if (updateRunLock.tryLock(SHUTDOWN_GRACE_SECONDS, TimeUnit.SECONDS)) {
                updateRunLock.unlock();
            } else {
                logger.log(Level.FINE, "An update run was still active while shutting down.");
            }
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
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
                    logThrottle.log("duplicate-destination:" + source.getName(), Level.WARNING,
                            "Skipped update for source {0} because destination {1} is already handled by {2}.",
                            source.getName(), normalizedDest, existing);
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
                runJob(job, context);
                logThrottle.clear(failureKey(source));
            } catch (UnknownHostException e) {
                networkIssueThisRun = true;
                handleUnknownHost(source, e);
            } catch (IOException e) {
                logThrottle.log(failureKey(source), Level.WARNING,
                        "I/O error while updating {0}: {1}", source.getName(), e.getMessage());
            } catch (Exception e) {
                if (shuttingDown || !plugin.isEnabled()) {
                    logger.log(Level.FINEST, "Update check aborted while plugin is disabling", e);
                    break;
                }
                logThrottle.log(failureKey(source), Level.SEVERE,
                        "Unexpected error while checking updates for " + source.getName(), e);
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
            logThrottle.log("no-filename:" + source.getName(), Level.WARNING,
                    "Update source {0} has no filename configured; skipping.", source.getName());
            return null;
        }
        File destinationDirectory = source.getTargetDirectory() == TargetDirectory.SERVER
                ? serverFolder
                : pluginsFolder;
        Path expectedPath = new File(destinationDirectory, filename).toPath();

        // A filename is only ever a name. Anything that escapes the target
        // directory - whether it came from a config file or from a remote
        // header - would have us write outside of it.
        Path expectedParent = expectedPath.toAbsolutePath().normalize().getParent();
        if (expectedParent == null || !expectedParent.equals(destinationDirectory.toPath().toAbsolutePath().normalize())) {
            logThrottle.log("unsafe-filename:" + source.getName(), Level.WARNING,
                    "Skipping update source {0}: the configured filename {1} points outside {2}.",
                    source.getName(), filename, destinationDirectory);
            return null;
        }

        if (Files.exists(expectedPath)) {
            return expectedPath;
        }

        // Self-healing: If the expected file is missing, try to find the plugin via the LifecycleManager
        // and update the configuration if the file has moved (e.g. from a previous crash or manual rename).
        String installedPluginName = source.getInstalledPluginName();
        if (installedPluginName != null && !installedPluginName.isBlank() && pluginLifecycleManager != null) {
            Path actualPath = pluginLifecycleManager.findByName(installedPluginName)
                    .map(ManagedPlugin::getPath)
                    .orElse(null);

            if (actualPath != null && Files.exists(actualPath)) {
                // Ensure the found plugin is actually in the expected directory to avoid path traversal confusion
                if (actualPath.getParent() != null && actualPath.getParent().equals(destinationDirectory.toPath())) {
                    String actualFilename = actualPath.getFileName().toString();
                    if (!actualFilename.equalsIgnoreCase(source.getFilename())) {
                        logger.log(Level.INFO, "Detected filename mismatch for {0}; recovering from {1} to {2}.",
                                new Object[]{source.getName(), source.getFilename(), actualFilename});

                        persistFilename(source.getName(), actualFilename);

                        return actualPath;
                    }
                }
            }
        }

        return expectedPath;
    }

    /**
     * Runs a pipeline and only afterwards announces the finished installation.
     * The rename to the upstream filename and the duplicate cleanup happen on
     * this thread after the pipeline, so dispatching the completion earlier
     * would let the reload on the main thread race against a jar that is still
     * being moved.
     */
    private void runJob(UpdateJob job, UpdateContext context) throws Exception {
        try {
            job.run(context);
            handleFilenameRetention(context);
        } finally {
            context.dispatchCompletion();
        }
    }

    private String failureKey(UpdateSource source) {
        return "update-failure:" + source.getName();
    }

    /**
     * Creates the default update pipeline consisting of fetch, download and
     * install steps. Plugins can register new steps by overriding this method
     * or by modifying the returned {@link UpdateJob} prior to execution in
     * {@link #checkForUpdates()}.
     */
    private UpdateJob createDefaultJob() {
        return new UpdateJob()
                .addStep(new FetchUpdateStep(persistentPluginHandler, versionComparator,
                        configuration.getBoolean("updates.respectManualRollback", true)))
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
        runJobsNow(List.of(source), sender);
    }

    /**
     * Runs several sources one after another under a single lock.
     *
     * <p>Starting one task per source instead makes all of them race for the
     * same exclusive lock, and every loser is answered with "another update run
     * is in progress" and simply dropped - which is what happened when the setup
     * wizard kicked off its downloads.</p>
     */
    public void runJobsNow(List<UpdateSource> sources, CommandSender sender) {
        if (sources == null || sources.isEmpty()) {
            return;
        }
        if (shuttingDown || !plugin.isEnabled()) {
            notify(sender, ChatColor.RED + "The updater is currently shutting down. Please try again later.");
            return;
        }
        List<UpdateSource> queue = List.copyOf(sources);
        scheduler.runTaskAsynchronously(plugin, () -> {
            if (shuttingDown || !plugin.isEnabled()) {
                notify(sender, ChatColor.RED + "The updater is currently shutting down. Please try again later.");
                return;
            }
            if (!updateRunLock.tryLock()) {
                notify(sender, ChatColor.RED + "Another update run is currently in progress. Please try again shortly.");
                return;
            }
            try {
                for (UpdateSource source : queue) {
                    if (shuttingDown || !plugin.isEnabled()) {
                        break;
                    }
                    executeManualRun(source, sender);
                }
            } finally {
                updateRunLock.unlock();
            }
        });
    }

    /** Runs one manual job. The caller already holds {@link #updateRunLock}. */
    private void executeManualRun(UpdateSource source, CommandSender sender) {
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

            runJob(job, context);
            logThrottle.clear(failureKey(source));
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
        String text = messagePrefix + message;
        if (runOnMainThread(() -> sender.sendMessage(text))) {
            return;
        }
        // Nothing can be scheduled any more (the server is going down). Talking
        // to a Player off the server thread is not safe, so only the console -
        // which is - gets the message directly; for anyone else it goes to the
        // log rather than being lost silently.
        if (sender instanceof org.bukkit.command.ConsoleCommandSender) {
            sender.sendMessage(text);
        } else {
            logger.log(Level.FINE, "Dropped a message during shutdown: {0}", message);
        }
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
        if (destination != null && !Files.isRegularFile(destination)) {
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
        if (context.isCancelled() || context.getDownloadedArtifact().isEmpty()) {
            return;
        }
        Path currentPath = context.getDownloadedArtifact().orElse(null);
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
        // The upstream name comes from the download URL, which for a Spiget
        // download or a GitHub source archive is something like "download" or
        // "v2.4.6.zip". Renaming a plugin jar to that makes Bukkit ignore it
        // from the next start on, so the plugin would simply be gone.
        if (!remoteFilename.toLowerCase(Locale.ROOT).endsWith(".jar")) {
            logThrottle.log("upstream-filename:" + context.getSource().getName(), Level.WARNING,
                    "Keeping the current filename for {0}: the upstream name {1} is not a .jar.",
                    context.getSource().getName(), remoteFilename);
            return;
        }

        String pluginName = resolvePluginName(context.getSource(), currentPath);
        String detectedName = ArchiveUtils.getPluginInfo(currentPath)
                .map(ArchiveUtils.PluginInfo::name)
                .orElse(null);
        boolean nameMismatch = detectedName != null
                && pluginName != null
                && !detectedName.equalsIgnoreCase(pluginName);
        String effectiveName = detectedName != null ? detectedName : pluginName;
        if (nameMismatch) {
            logger.log(Level.WARNING,
                    "Downloaded artifact for {0} identifies as {1}; skipping duplicate cleanup to avoid data loss.",
                    new Object[]{context.getSource().getName(), detectedName});
        } else if (effectiveName != null && !effectiveName.isBlank() && pluginLifecycleManager != null) {
            // Cleanup only when we can confidently identify the plugin.
            pluginLifecycleManager.deleteAllDuplicates(effectiveName, currentPath);
        }

        Path newPath = parent.resolve(remoteFilename).toAbsolutePath().normalize();
        if (!newPath.getParent().equals(parent.toAbsolutePath().normalize())) {
            logger.log(Level.WARNING,
                    "Refusing to rename downloaded artifact outside of target directory. Requested filename: {0}",
                    remoteFilename);
            return;
        }

        Path renamedFrom = null;
        Path renamedTo = null;
        if (Files.exists(currentPath) && !currentPath.equals(newPath)) {
            boolean renamed = false;
            try {
                if (Files.exists(newPath) && !isSafeToReplace(newPath, effectiveName, currentPath)) {
                    logger.log(Level.WARNING,
                            "Refusing to replace existing file {0} because it appears to belong to a different plugin.",
                            newPath.getFileName());
                } else {
                    // Proactive cleanup: If a file already exists at newPath, delete it to prevent duplicates.
                    if (Files.exists(newPath)) {
                        Files.delete(newPath);
                        logger.log(Level.INFO, "Deleted existing file at {0} to prevent duplicate JARs after update.", newPath);
                    }

                    try {
                        Files.move(currentPath, newPath,
                                StandardCopyOption.REPLACE_EXISTING,
                                StandardCopyOption.ATOMIC_MOVE);
                    } catch (AtomicMoveNotSupportedException ignored) {
                        Files.move(currentPath, newPath, StandardCopyOption.REPLACE_EXISTING);
                    }
                    renamed = true;
                }
            } catch (IOException ex) {
                logger.log(Level.WARNING, "Failed to rename downloaded artifact to " + newPath, ex);
                return;
            }
            if (renamed) {
                context.setDownloadDestination(newPath);
                context.setDownloadedArtifact(newPath);
                if (pluginLifecycleManager != null) {
                    pluginLifecycleManager.updateManagedPluginPath(currentPath, newPath);
                }
                renamedFrom = currentPath;
                renamedTo = newPath;
                currentPath = newPath;
            }
        }

        if (!persistFilename(context.getSource().getName(), currentPath.getFileName().toString())
                && renamedTo != null) {
            // The server is shutting down and the config write could not be
            // scheduled. Undo the rename rather than leaving the file under a
            // name the configuration does not know about.
            try {
                Files.move(renamedTo, renamedFrom);
                context.setDownloadDestination(renamedFrom);
                context.setDownloadedArtifact(renamedFrom);
                if (pluginLifecycleManager != null) {
                    pluginLifecycleManager.updateManagedPluginPath(renamedTo, renamedFrom);
                }
                logger.log(Level.FINE, "Reverted the rename of {0} because the config write was not possible.",
                        renamedTo.getFileName());
            } catch (IOException ex) {
                logger.log(Level.WARNING, "Renamed artifact to " + renamedTo.getFileName()
                        + " but could not record it in the configuration", ex);
            }
        }
    }

    /**
     * Records a changed jar name in the registry and on disk.
     *
     * <p>Runs on the main thread: the update pipeline works asynchronously,
     * while {@code FileConfiguration} is shared with commands and the GUI and
     * is not thread-safe. Writing it from the update thread can interleave with
     * a read on the main thread and leave a damaged config behind.</p>
     */
    private boolean persistFilename(String sourceName, String filename) {
        return runOnMainThread(() -> {
            if (updateSourceRegistry.updateSourceFilename(sourceName, filename)) {
                configuration.set("filenames." + sourceName, filename);
                plugin.saveConfig();
            }
        });
    }

    /**
     * Executes {@code action} on the server thread, or right away if already
     * there.
     *
     * @return {@code false} if the plugin is already disabled and nothing could
     *         be scheduled. Bukkit answers a scheduling attempt on a disabled
     *         plugin with an exception, which would show up as a stack trace in
     *         the console every time the server stops mid-update.
     */
    private boolean runOnMainThread(Runnable action) {
        if (!plugin.isEnabled()) {
            return false;
        }
        if (server.isPrimaryThread()) {
            action.run();
            return true;
        }
        try {
            scheduler.runTask(plugin, action);
            return true;
        } catch (IllegalPluginAccessException | IllegalStateException ex) {
            logger.log(Level.FINE, "Skipped a main thread task because the server is shutting down", ex);
            return false;
        }
    }

    private boolean isSafeToReplace(Path existingPath, String expectedPluginName, Path downloadedPath) {
        if (existingPath == null) {
            return false;
        }
        if (expectedPluginName != null && !expectedPluginName.isBlank()) {
            return ArchiveUtils.getPluginInfo(existingPath)
                    .map(info -> info.name().equalsIgnoreCase(expectedPluginName))
                    .orElse(false);
        }
        if (downloadedPath == null) {
            return false;
        }
        Optional<ArchiveUtils.PluginInfo> existingInfo = ArchiveUtils.getPluginInfo(existingPath);
        Optional<ArchiveUtils.PluginInfo> downloadedInfo = ArchiveUtils.getPluginInfo(downloadedPath);
        if (existingInfo.isPresent() && downloadedInfo.isPresent()) {
            return existingInfo.get().name().equalsIgnoreCase(downloadedInfo.get().name());
        }
        return false;
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
