package eu.nurkert.neverUp2Late.handlers;

import eu.nurkert.neverUp2Late.NeverUp2Late;
import eu.nurkert.neverUp2Late.fetcher.GeyserFetcher;
import eu.nurkert.neverUp2Late.fetcher.PaperFetcher;
import eu.nurkert.neverUp2Late.fetcher.UpdateFetcher;
import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.scheduler.BukkitRunnable;

import java.io.File;
import java.util.Arrays;
import java.util.Map;

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
            File pluginsFolder = NeverUp2Late.getInstance().getDataFolder().getParentFile();
            File serverFolder = Bukkit.getServer().getWorldContainer().getAbsoluteFile();

            PersistentPluginHandler persistents = PersistentPluginHandler.getInstance();

            boolean updateAvailable = false;

            Map<String, UpdateFetcher> map = Map.ofEntries(
                    Map.entry("paper", new PaperFetcher()),
                    Map.entry("geyser", new GeyserFetcher())
            );


            @Override
            public void run() {
                try {

                    for(String key : map.keySet()) {
                        UpdateFetcher fetcher = map.get(key);
                        fetcher.loadLatestBuildInfo();

                        if(persistents.getBuild(key) < fetcher.getLatestBuild() || (fetcher.getInstalledVersion() != null && compareVersions(fetcher.getInstalledVersion(), fetcher.getLatestVersion()) < 0)) {
                            String downloadURL = fetcher.getLatestDownloadUrl();
                            DownloadHandler.downloadJar(downloadURL, (key.equals("paper") ? serverFolder : pluginsFolder).getAbsolutePath() + "/" + NeverUp2Late.getInstance().getConfig().getString("filenames." + key));

                            persistents.set(key, fetcher.getLatestBuild());
                            InstallationHandler.getInstance().updateAvailable();
                        }
                    }
                } catch (Exception e) {
                    e.printStackTrace();
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
