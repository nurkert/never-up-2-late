package eu.nurkert.neverUp2Late.update;

import eu.nurkert.neverUp2Late.fetcher.UpdateFetcher;
import eu.nurkert.neverUp2Late.handlers.PersistentPluginHandler;

import java.util.logging.Level;

/**
 * Loads meta information for an update source and decides whether a download
 * should be executed.
 */
public class FetchUpdateStep implements UpdateStep {

    private final PersistentPluginHandler persistentPluginHandler;
    private final VersionComparator versionComparator;

    public FetchUpdateStep(PersistentPluginHandler persistentPluginHandler, VersionComparator versionComparator) {
        this.persistentPluginHandler = persistentPluginHandler;
        this.versionComparator = versionComparator;
    }

    @Override
    public void execute(UpdateContext context) throws Exception {
        UpdateFetcher fetcher = context.getFetcher();
        fetcher.loadLatestBuildInfo();

        context.setLatestBuild(fetcher.getLatestBuild());
        context.setLatestVersion(fetcher.getLatestVersion());
        context.setDownloadUrl(fetcher.getLatestDownloadUrl());

        boolean updateRequired = isUpdateRequired(context, fetcher);
        if (!updateRequired) {
            context.cancel("No new build available");
            context.log(Level.FINE, "No update required for {0}", context.getSource().getName());
            return;
        }

        if (context.getDownloadUrl() == null || context.getDownloadUrl().isBlank()) {
            context.cancel("Missing download URL");
            context.log(Level.WARNING, "No download URL available for {0}; skipping update.",
                    context.getSource().getName());
        }
    }

    private boolean isUpdateRequired(UpdateContext context, UpdateFetcher fetcher) {
        String key = context.getSource().getName();
        int storedBuild = persistentPluginHandler.getStoredBuild(key);
        if (storedBuild < fetcher.getLatestBuild()) {
            return true;
        }

        String installedVersion = fetcher.getInstalledVersion();
        String latestVersion = fetcher.getLatestVersion();
        if (installedVersion != null && latestVersion != null) {
            return versionComparator.compare(installedVersion, latestVersion) < 0;
        }
        return false;
    }
}
