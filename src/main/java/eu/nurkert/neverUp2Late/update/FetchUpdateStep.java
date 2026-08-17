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

    /**
     * Decides whether the remote artifact should be installed.
     *
     * <p>There is exactly one reason to say yes: what the source offers is
     * <em>newer</em> than what the server has. Every earlier shortcut around
     * that question - a destination file that could not be found, a build
     * number that happened to be larger, a version string that merely differed
     * - had the same failure mode, which is installing an older build over a
     * newer one. They are gone; when the comparison cannot be made, the answer
     * is no and the source is reported instead.</p>
     */
    private boolean isUpdateRequired(UpdateContext context, UpdateFetcher fetcher) {
        String key = context.getSource().getName();
        int storedBuild = persistentPluginHandler.getStoredBuild(key);
        String storedVersion = persistentPluginHandler.getStoredVersion(key);
        String latestVersion = fetcher.getLatestVersion();
        int latestBuild = fetcher.getLatestBuild();

        boolean neverInstalled = storedVersion == null && storedBuild < 0;
        if (neverInstalled && isDestinationMissing(context)) {
            // Nothing recorded and nothing on disk: this is the first install of
            // a source the operator just added, so there is no older version to
            // protect. Anything else falls through to the comparison below - a
            // destination that merely cannot be found under its configured name
            // must not license reinstalling an arbitrary version next to the
            // copy that is actually there.
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

        // The version the plugin itself reports beats the one we recorded: a
        // manual swap of the jar is visible in the former and invisible in the
        // latter.
        String localVersion = firstNonBlank(fetcher.getInstalledVersion(), storedVersion);
        if (localVersion != null && latestVersion != null) {
            return versionComparator.compare(localVersion, latestVersion) < 0;
        }

        // No usable version on one of the sides. A build counter can still
        // decide it, but only a real one - see UpdateFetcher.UNKNOWN_BUILD.
        if (storedBuild >= 0 && latestBuild >= 0) {
            return storedBuild < latestBuild;
        }

        if (neverInstalled) {
            return true;
        }

        context.log(Level.FINE,
                "Cannot tell whether {0} offers something newer (local version {1}, remote version {2}); "
                        + "leaving the installed file alone.",
                new Object[]{key, localVersion, latestVersion});
        return false;
    }

    private String firstNonBlank(String first, String second) {
        if (first != null && !first.isBlank()) {
            return first;
        }
        return second != null && !second.isBlank() ? second : null;
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
