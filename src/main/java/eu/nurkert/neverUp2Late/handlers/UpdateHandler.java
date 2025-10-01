package eu.nurkert.neverUp2Late.handlers;

import eu.nurkert.neverUp2Late.update.DownloadUpdateStep;
import eu.nurkert.neverUp2Late.update.FetchUpdateStep;
import eu.nurkert.neverUp2Late.update.InstallUpdateStep;
import eu.nurkert.neverUp2Late.update.UpdateContext;
import eu.nurkert.neverUp2Late.update.UpdateJob;
import eu.nurkert.neverUp2Late.update.UpdateSourceRegistry;
import eu.nurkert.neverUp2Late.update.UpdateSourceRegistry.TargetDirectory;
import eu.nurkert.neverUp2Late.update.UpdateSourceRegistry.UpdateSource;
import eu.nurkert.neverUp2Late.update.VersionComparator;
import org.bukkit.Server;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitScheduler;

import java.io.File;
import java.io.IOException;
import java.net.UnknownHostException;
import java.nio.file.Path;
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

    private boolean networkWarningShown;

    public UpdateHandler(JavaPlugin plugin,
                         BukkitScheduler scheduler,
                         FileConfiguration configuration,
                         PersistentPluginHandler persistentPluginHandler,
                         InstallationHandler installationHandler,
                         UpdateSourceRegistry updateSourceRegistry,
                         ArtifactDownloader artifactDownloader,
                         VersionComparator versionComparator) {
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
    }

    public void start() {
        long intervalMinutes = configuration.getInt("updateInterval");
        long intervalTicks = Math.max(1L, intervalMinutes) * 20L * 60L;
        scheduler.runTaskTimerAsynchronously(plugin, this::checkForUpdates, 0L, intervalTicks);
    }

    private void checkForUpdates() {
        boolean networkIssueThisRun = false;
        File pluginsFolder = plugin.getDataFolder().getParentFile();
        File serverFolder = server.getWorldContainer().getAbsoluteFile();

        for (UpdateSource source : updateSourceRegistry.getSources()) {
            Path destination = resolveDestination(source, pluginsFolder, serverFolder);
            UpdateJob job = createDefaultJob();
            UpdateContext context = new UpdateContext(source, destination, logger);

            try {
                job.run(context);
            } catch (UnknownHostException e) {
                networkIssueThisRun = true;
                handleUnknownHost(source, e);
            } catch (IOException e) {
                logger.log(Level.WARNING,
                        "I/O error while updating {0}: {1}", new Object[]{source.getName(), e.getMessage()});
            } catch (Exception e) {
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
                .addStep(new InstallUpdateStep(persistentPluginHandler, installationHandler));
    }

    private void handleUnknownHost(UpdateSource source, UnknownHostException e) {
        if (!networkWarningShown) {
            logger.log(Level.WARNING,
                    "Unable to reach update server while checking {0}: {1}. The plugin will retry automatically.",
                    new Object[]{source.getName(), e.getMessage()});
            networkWarningShown = true;
        }
    }
}
