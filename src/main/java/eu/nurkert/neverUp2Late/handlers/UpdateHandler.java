package eu.nurkert.neverUp2Late.handlers;

import eu.nurkert.neverUp2Late.fetcher.UpdateFetcher;
import org.bukkit.Server;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitScheduler;

import java.io.File;
import java.io.IOException;
import java.net.UnknownHostException;
import java.util.Map;
import java.util.logging.Level;
import java.util.logging.Logger;

public class UpdateHandler {

    private final JavaPlugin plugin;
    private final Server server;
    private final BukkitScheduler scheduler;
    private final FileConfiguration configuration;
    private final PersistentPluginHandler persistentPluginHandler;
    private final InstallationHandler installationHandler;
    private final Map<String, UpdateFetcher> fetchers;
    private final Logger logger;

    private boolean networkWarningShown;

    public UpdateHandler(JavaPlugin plugin,
                         BukkitScheduler scheduler,
                         FileConfiguration configuration,
                         PersistentPluginHandler persistentPluginHandler,
                         InstallationHandler installationHandler,
                         Map<String, UpdateFetcher> fetchers) {
        this.plugin = plugin;
        this.server = plugin.getServer();
        this.scheduler = scheduler;
        this.configuration = configuration;
        this.persistentPluginHandler = persistentPluginHandler;
        this.installationHandler = installationHandler;
        this.fetchers = fetchers;
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

        for (Map.Entry<String, UpdateFetcher> entry : fetchers.entrySet()) {
            String key = entry.getKey();
            UpdateFetcher fetcher = entry.getValue();

            try {
                fetcher.loadLatestBuildInfo();

                if (persistentPluginHandler.getBuild(key) < fetcher.getLatestBuild()
                        || (fetcher.getInstalledVersion() != null
                        && compareVersions(fetcher.getInstalledVersion(), fetcher.getLatestVersion()) < 0)) {
                    String downloadURL = fetcher.getLatestDownloadUrl();

                    if (downloadURL == null || downloadURL.isBlank()) {
                        logger.log(Level.WARNING, "No download URL available for {0}; skipping update.", key);
                        continue;
                    }

                    String destinationPath = (key.equals("paper") ? serverFolder : pluginsFolder)
                            .getAbsolutePath() + "/" + configuration.getString("filenames." + key);

                    DownloadHandler.downloadJar(downloadURL, destinationPath);

                    persistentPluginHandler.set(key, fetcher.getLatestBuild());
                    installationHandler.updateAvailable();
                }
            } catch (UnknownHostException e) {
                networkIssueThisRun = true;
                if (!networkWarningShown) {
                    logger.log(Level.WARNING,
                            "Unable to reach update server while checking {0}: {1}. The plugin will retry automatically.",
                            new Object[]{key, e.getMessage()});
                    networkWarningShown = true;
                }
            } catch (IOException e) {
                logger.log(Level.WARNING,
                        "I/O error while updating {0}: {1}", new Object[]{key, e.getMessage()});
            } catch (Exception e) {
                logger.log(Level.SEVERE, "Unexpected error while checking updates for " + key, e);
            }
        }

        if (!networkIssueThisRun && networkWarningShown) {
            logger.log(Level.INFO, "Connection to update servers restored. Resuming normal update checks.");
            networkWarningShown = false;
        }
    }

    /**
     * Vergleicht zwei Versionsnummern.
     *
     * @param version1 die erste Versionsnummer (z.B. "1.16.5")
     * @param version2 die zweite Versionsnummer (z.B. "1.17.1")
     * @return -1, wenn version1 < version2; 0, wenn version1 == version2; 1, wenn version1 > version2
     */
    public static int compareVersions(String version1, String version2) {
        // Aufteilen der Versionsnummern in ihre Bestandteile
        String[] parts1 = version1.split("\\.");
        String[] parts2 = version2.split("\\.");

        int length = Math.max(parts1.length, parts2.length);

        for (int i = 0; i < length; i++) {
            // Holen des aktuellen Teils oder 0, falls nicht vorhanden
            int v1 = i < parts1.length ? Integer.parseInt(parts1[i]) : 0;
            int v2 = i < parts2.length ? Integer.parseInt(parts2[i]) : 0;

            // Vergleich der aktuellen Teile
            if (v1 < v2) {
                return -1;
            } else if (v1 > v2) {
                return 1;
            }
        }

        // Alle Teile sind gleich
        return 0;
    }
}
