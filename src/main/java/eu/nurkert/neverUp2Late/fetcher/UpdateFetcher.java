package eu.nurkert.neverUp2Late.fetcher;

/**
 * UpdateFetcher interface defines methods to fetch the latest build information.
 */
public interface UpdateFetcher {

    /**
     * Returned by {@link #getLatestBuild()} when the provider publishes no
     * monotonic build counter.
     *
     * <p>Several providers only ever name their releases ("2.4.6", "v3-beta").
     * Deriving a number from such a name - a hash, a checksum, a timestamp -
     * produces something that compares, but not something that orders: two
     * unrelated values then decide the direction of an update, and an older
     * release wins whenever its number happens to be larger. Say "unknown"
     * instead and let the version comparison answer the question.</p>
     */
    int UNKNOWN_BUILD = -1;

    /**
     * Loads the latest build information.
     *
     * @throws Exception If an error occurs during fetching or processing.
     */
    void loadLatestBuildInfo() throws Exception;

    /**
     * Gets the latest version.
     *
     * @return The latest version as a string.
     */
    String getLatestVersion();

    /**
     * Gets the latest build number.
     *
     * @return The latest build number as an integer.
     */
    int getLatestBuild();

    /**
     * Gets the download URL for the latest build.
     *
     * @return The latest download URL as a string.
     */
    String getLatestDownloadUrl();

    /**
     * Gets current installed Version
     *
     * @return The version of the current
     */
    String getInstalledVersion();

    /**
     * Allows fetchers to customise the update context after meta information has been loaded.
     * Implementations can use this hook to set checksum validators, download processors, or
     * additional metadata required by later pipeline steps.
     */
    default void configureContext(eu.nurkert.neverUp2Late.update.UpdateContext context) {
        // default no-op
    }
}
