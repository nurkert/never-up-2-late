package eu.nurkert.neverUp2Late.update;

import eu.nurkert.neverUp2Late.fetcher.UpdateFetcher;
import eu.nurkert.neverUp2Late.handlers.PersistentPluginHandler;

import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.logging.Level;

/**
 * Loads meta information for an update source and decides whether a download
 * should be executed.
 */
public class FetchUpdateStep implements UpdateStep {

    private final PersistentPluginHandler persistentPluginHandler;
    private final VersionComparator versionComparator;
    private final boolean respectManualRollback;

    public FetchUpdateStep(PersistentPluginHandler persistentPluginHandler, VersionComparator versionComparator) {
        this(persistentPluginHandler, versionComparator, true);
    }

    public FetchUpdateStep(PersistentPluginHandler persistentPluginHandler,
                           VersionComparator versionComparator,
                           boolean respectManualRollback) {
        this.persistentPluginHandler = persistentPluginHandler;
        this.versionComparator = versionComparator;
        this.respectManualRollback = respectManualRollback;
    }

    @Override
    public void execute(UpdateContext context) throws Exception {
        UpdateFetcher fetcher = context.getFetcher();
        fetcher.loadLatestBuildInfo();

        context.setLatestBuild(fetcher.getLatestBuild());
        context.setLatestVersion(fetcher.getLatestVersion());
        context.setDownloadUrl(fetcher.getLatestDownloadUrl());
        context.setRemoteFilename(extractFilename(context.getDownloadUrl()));

        fetcher.configureContext(context);

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
        if (isDestinationMissing(context)) {
            return true;
        }

        String key = context.getSource().getName();
        int storedBuild = persistentPluginHandler.getStoredBuild(key);
        String storedVersion = persistentPluginHandler.getStoredVersion(key);
        String latestVersion = fetcher.getLatestVersion();

        if (storedBuild < fetcher.getLatestBuild()) {
            return true;
        }

        // We already wrote exactly this version to disk. Whether the *running*
        // plugin reports it yet is irrelevant - it will after the pending
        // restart. Trusting the live value here is what made the updater
        // re-download and re-install the same build on every single cycle
        // whenever the two sides spell a version differently (a "v" prefix, a
        // "-SNAPSHOT" suffix), and what silently undid a manual rollback.
        if (respectManualRollback && storedVersion != null && latestVersion != null
                && storedVersion.equalsIgnoreCase(latestVersion)) {
            return false;
        }

        String installedVersion = fetcher.getInstalledVersion();
        if (installedVersion != null && latestVersion != null) {
            return versionComparator.compare(installedVersion, latestVersion) < 0;
        }

        // Fallback: compare the stored version string when the fetcher does not report an installed version itself.
        if (storedVersion != null && latestVersion != null) {
            return !storedVersion.equalsIgnoreCase(latestVersion);
        }

        // If neither build nor version indicates a change, no update is required.
        return storedBuild < 0; // Allow initial installation
    }

    private boolean isDestinationMissing(UpdateContext context) {
        Path destination = context.getDownloadDestination();
        if (destination == null) {
            return false;
        }
        return !Files.isRegularFile(destination);
    }

    private String extractFilename(String url) {
        if (url == null || url.isBlank()) {
            return null;
        }
        try {
            URI uri = new URI(url);
            String path = uri.getPath();
            if (path == null || path.isBlank()) {
                return null;
            }
            int lastSlash = path.lastIndexOf('/');
            String candidate = lastSlash >= 0 ? path.substring(lastSlash + 1) : path;
            int queryIndex = candidate.indexOf('?');
            if (queryIndex >= 0) {
                candidate = candidate.substring(0, queryIndex);
            }
            return candidate.isBlank() ? null : candidate;
        } catch (Exception ignored) {
            return null;
        }
    }
}
