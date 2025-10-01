package eu.nurkert.neverUp2Late.handlers;

import eu.nurkert.neverUp2Late.NeverUp2Late;
import eu.nurkert.neverUp2Late.fetcher.GeyserFetcher;
import eu.nurkert.neverUp2Late.fetcher.PaperFetcher;
import eu.nurkert.neverUp2Late.fetcher.UpdateFetcher;
import org.bukkit.Bukkit;
import org.bukkit.scheduler.BukkitRunnable;

import java.io.File;
import java.io.IOException;
import java.net.UnknownHostException;
import java.util.Map;
import java.util.logging.Level;
import java.util.logging.Logger;

public class UpdateHandler {

    // Step 1: Create a private static instance of the class
    private static UpdateHandler instance;

    // Step 2: Make the constructor private to prevent instantiation
    private UpdateHandler() {
        // Initialization code here
        checkForUpdates();
    }

    // Step 3: Provide a public static method to get the instance of the class
    public static UpdateHandler getInstance() {
        if (instance == null) {
            instance = new UpdateHandler();
        }
        return instance;
    }

    private void checkForUpdates() {
        int interval = NeverUp2Late.getInstance().getConfig().getInt("updateInterval");
        new BukkitRunnable() {
            private final NeverUp2Late plugin = NeverUp2Late.getInstance();
            private final File pluginsFolder = plugin.getDataFolder().getParentFile();
            private final File serverFolder = Bukkit.getServer().getWorldContainer().getAbsoluteFile();
            private final PersistentPluginHandler persistents = PersistentPluginHandler.getInstance();
            private final Map<String, UpdateFetcher> map = Map.ofEntries(
                    Map.entry("paper", new PaperFetcher()),
                    Map.entry("geyser", new GeyserFetcher())
            );
            private final Logger logger = plugin.getLogger();
            private boolean networkWarningShown = false;

            @Override
            public void run() {
                boolean networkIssueThisRun = false;

                for (String key : map.keySet()) {
                    UpdateFetcher fetcher = map.get(key);

                    try {
                        fetcher.loadLatestBuildInfo();

                        if (persistents.getBuild(key) < fetcher.getLatestBuild()
                                || (fetcher.getInstalledVersion() != null
                                && compareVersions(fetcher.getInstalledVersion(), fetcher.getLatestVersion()) < 0)) {
                            String downloadURL = fetcher.getLatestDownloadUrl();

                            if (downloadURL == null || downloadURL.isBlank()) {
                                logger.log(Level.WARNING, "No download URL available for {0}; skipping update.", key);
                                continue;
                            }

                            String destinationPath = (key.equals("paper") ? serverFolder : pluginsFolder)
                                    .getAbsolutePath() + "/" + plugin.getConfig().getString("filenames." + key);

                            DownloadHandler.downloadJar(downloadURL, destinationPath);

                            persistents.set(key, fetcher.getLatestBuild());
                            InstallationHandler.getInstance().updateAvailable();
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
        }.runTaskTimerAsynchronously(NeverUp2Late.getInstance(), 0L, interval * 20L * 60L);
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
