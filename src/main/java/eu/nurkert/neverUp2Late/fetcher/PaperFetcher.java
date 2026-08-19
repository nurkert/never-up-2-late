package eu.nurkert.neverUp2Late.fetcher;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.core.type.TypeReference;
import eu.nurkert.neverUp2Late.net.HttpClient;
import org.bukkit.Bukkit;
import org.bukkit.configuration.ConfigurationSection;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.logging.Logger;

/**
 * Fetcher for Paper builds using the PaperMC Fill API (v3).
 *
 * <p>The former v2 API at {@code api.papermc.io} was retired and now answers
 * every request with HTTP 410, which silently turned Paper updates into a
 * no-op. Fill is the replacement and differs in three ways that matter here:
 * versions arrive grouped by their major release instead of as one flat list,
 * the build list is returned in full (channel included) by a single request
 * instead of one request per build, and the download URL is handed to us
 * rather than assembled from version and build number.</p>
 */
public class PaperFetcher extends JsonUpdateFetcher {

    private static final String API_URL = "https://fill.papermc.io/v3/projects/paper";

    /**
     * Fill reports channels in upper case ({@code STABLE}, {@code BETA},
     * {@code ALPHA}); v2 used {@code default}. Both spellings are accepted so
     * the comparison keeps working no matter which wording the API settles on.
     */
    private static final Set<String> STABLE_CHANNELS = Set.of("default", "stable");

    /**
     * Key of the plain server jar inside a build's {@code downloads} map. Fill
     * can expose several artifacts per build; this is the one that replaces
     * paper.jar.
     */
    private static final String SERVER_DOWNLOAD_KEY = "server:default";

    private static final Logger LOGGER = Logger.getLogger(PaperFetcher.class.getName());
    private static final int DEFAULT_MINIMUM_UNSTABLE_BUILD_NUMBER = 50;

    private final boolean fetchStableVersions;
    private final int minimumUnstableBuildNumber;
    private final boolean allowGameVersionUpgrade;

    public PaperFetcher() {
        this(true);
    }

    public PaperFetcher(boolean fetchStableVersions) {
        this(fetchStableVersions, 0, new HttpClient());
    }

    public PaperFetcher(ConfigurationSection options) {
        this(options, new HttpClient());
    }

    PaperFetcher(boolean fetchStableVersions, HttpClient httpClient) {
        this(fetchStableVersions, 0, httpClient);
    }

    PaperFetcher(boolean fetchStableVersions, int minimumUnstableBuildNumber, HttpClient httpClient) {
        this(fetchStableVersions, minimumUnstableBuildNumber, false, httpClient);
    }

    PaperFetcher(boolean fetchStableVersions,
                 int minimumUnstableBuildNumber,
                 boolean allowGameVersionUpgrade,
                 HttpClient httpClient) {
        super(httpClient);
        this.fetchStableVersions = fetchStableVersions;
        this.minimumUnstableBuildNumber = Math.max(0, minimumUnstableBuildNumber);
        this.allowGameVersionUpgrade = allowGameVersionUpgrade;
    }

    PaperFetcher(ConfigurationSection options, HttpClient httpClient) {
        this(determineStablePreference(options),
                determineMinimumUnstableBuildNumber(options, determineStablePreference(options)),
                options != null && options.getBoolean("allowGameVersionUpgrade", false),
                httpClient);
    }

    @Override
    public void loadLatestBuildInfo() throws Exception {
        ProjectResponse project = getJson(API_URL, ProjectResponse.class);
        List<String> versions = new ArrayList<>(project.allVersions());
        if (fetchStableVersions) {
            versions = filterStableVersions(versions);
        }
        if (versions.isEmpty()) {
            throw new IOException("No versions available");
        }

        Comparator<String> comparator = semanticVersionComparator();
        versions.sort(comparator.reversed());

        LOGGER.fine("Paper API returned versions: " + versions);

        String newestVersion = versions.get(0);
        // Stay on the Minecraft version the server actually runs. Paper
        // publishes builds for the next major version long before an operator
        // is ready for it, and installing one silently restarts the server into
        // a version its worlds and plugins were never prepared for. Only an
        // explicit opt-in lifts the cap.
        String installedVersion = allowGameVersionUpgrade ? null : getInstalledVersion();
        boolean restrictToInstalled = !allowGameVersionUpgrade;
        if (restrictToInstalled) {
            LOGGER.fine("Restricting Paper updates to the installed Minecraft version " + installedVersion);
        }

        Exception lastError = null;
        for (String version : versions) {
            if (restrictToInstalled) {
                if (installedVersion == null) {
                    installedVersion = getInstalledVersion();
                    LOGGER.fine("Installed Minecraft version detected as " + installedVersion);
                }
                if (installedVersion != null && !installedVersion.isEmpty()) {
                    int order = comparator.compare(version, installedVersion);
                    if (order > 0) {
                        LOGGER.fine("Skipping version " + version
                                + " because it exceeds installed version " + installedVersion);
                        continue;
                    }
                    if (order < 0) {
                        // The list is sorted descending, so everything from here
                        // on is older than what the server runs. Walking on would
                        // hand out a build for a previous Minecraft version and
                        // downgrade the server.
                        LOGGER.warning("No Paper build found for the installed Minecraft version "
                                + installedVersion + "; leaving the server jar untouched.");
                        break;
                    }
                }
            }
            try {
                List<BuildResponse> builds = getJson(API_URL + "/versions/" + version + "/builds",
                        new TypeReference<List<BuildResponse>>() {
                        });
                LOGGER.fine("Builds reported for version " + version + ": " + buildNumbersOf(builds));
                boolean versionIsStable = isStableVersion(version);
                BuildResponse build = (fetchStableVersions || versionIsStable)
                        ? selectLatestStableBuild(version, builds)
                        : selectLatestUnstableBuild(version, builds);

                setLatestBuildInfo(version, build.id(), downloadUrlOf(version, build));
                return;
            } catch (Exception exception) {
                lastError = exception;
                if (!restrictToInstalled && version.equals(newestVersion)) {
                    if (installedVersion == null) {
                        installedVersion = getInstalledVersion();
                        LOGGER.fine("Installed Minecraft version detected as " + installedVersion);
                    }
                    if (installedVersion != null && !installedVersion.isEmpty()
                            && comparator.compare(version, installedVersion) > 0) {
                        restrictToInstalled = true;
                        LOGGER.fine("Enabling fallback to installed version " + installedVersion
                                + " after failure for newest version " + version);
                    }
                }
            }
        }

        if (lastError != null) {
            throw lastError;
        }

        throw new IOException("No suitable builds available");
    }

    @Override
    public String getInstalledVersion() {
        if (Bukkit.getServer() == null) {
            return "";
        }
        String fullVersion = Bukkit.getServer().getVersion();
        int start = fullVersion.indexOf("MC: ");
        if (start == -1) {
            return fullVersion;
        }
        return fullVersion.substring(start + 4, fullVersion.length() - 1);
    }

    /**
     * Fill groups versions by major release, e.g.
     * {@code {"1.21": ["1.21.11", "1.21.10"], "1.20": [...]}}. The selection
     * below sorts anyway, so the grouping is simply flattened away.
     */
    private record ProjectResponse(@JsonProperty("versions") Map<String, List<String>> versions) {
        private ProjectResponse {
            versions = versions == null ? Map.of() : new LinkedHashMap<>(versions);
        }

        List<String> allVersions() {
            List<String> flattened = new ArrayList<>();
            for (List<String> group : versions.values()) {
                if (group != null) {
                    group.stream().filter(entry -> entry != null && !entry.isBlank()).forEach(flattened::add);
                }
            }
            return flattened;
        }
    }

    private record BuildResponse(@JsonProperty("id") int id,
                                 @JsonProperty("channel") String channel,
                                 @JsonProperty("downloads") Map<String, DownloadResponse> downloads) {
        private BuildResponse {
            downloads = downloads == null ? Map.of() : new LinkedHashMap<>(downloads);
        }
    }

    private record DownloadResponse(@JsonProperty("name") String name, @JsonProperty("url") String url) {
    }

    private static List<Integer> buildNumbersOf(Collection<BuildResponse> builds) {
        return builds.stream().map(BuildResponse::id).toList();
    }

    /**
     * Fill hands out a content addressed URL per build. Assembling one by hand
     * (as the v2 code did) would point at a path that no longer exists, so a
     * build without a usable server download is treated as unusable and the
     * search moves on to the next candidate.
     */
    private String downloadUrlOf(String version, BuildResponse build) throws IOException {
        DownloadResponse download = build.downloads().get(SERVER_DOWNLOAD_KEY);
        if (download == null || download.url() == null || download.url().isBlank()) {
            throw new IOException("Build " + build.id() + " for version " + version
                    + " does not offer a '" + SERVER_DOWNLOAD_KEY + "' download");
        }
        return download.url();
    }

    private List<BuildResponse> sortedByBuildDescending(List<BuildResponse> builds) {
        List<BuildResponse> sorted = new ArrayList<>(builds);
        sorted.sort(Comparator.comparingInt(BuildResponse::id).reversed());
        return sorted;
    }

    private BuildResponse selectLatestStableBuild(String version, List<BuildResponse> builds) throws IOException {
        if (builds.isEmpty()) {
            throw new IOException("No builds available for version " + version);
        }

        for (BuildResponse build : sortedByBuildDescending(builds)) {
            LOGGER.fine("Version " + version + " build " + build.id() + " reported channel " + build.channel());
            if (isStableChannel(build.channel())) {
                return build;
            }
        }

        throw new IOException("No stable builds available for version " + version);
    }

    private BuildResponse selectLatestUnstableBuild(String version, List<BuildResponse> builds) throws IOException {
        if (builds.isEmpty()) {
            throw new IOException("No builds available for version " + version);
        }

        BuildResponse latest = sortedByBuildDescending(builds).get(0);
        if (minimumUnstableBuildNumber > 0 && latest.id() < minimumUnstableBuildNumber) {
            LOGGER.fine("Latest unstable build " + latest.id() + " for version " + version
                    + " is below minimum required build " + minimumUnstableBuildNumber);
            throw new IOException("No unstable builds meeting the minimum build number for version " + version);
        }
        return latest;
    }

    private boolean isStableChannel(String channel) {
        if (channel == null) {
            return true;
        }
        return STABLE_CHANNELS.contains(channel.toLowerCase(Locale.ROOT));
    }

    private static boolean determineStablePreference(ConfigurationSection options) {
        if (options == null) {
            return true;
        }

        if (options.contains("ignoreUnstable")) {
            return options.getBoolean("ignoreUnstable");
        }

        if (options.contains("allowUnstable")) {
            return !options.getBoolean("allowUnstable");
        }

        return options.getBoolean("_ignoreUnstableDefault", true);
    }

    private static int determineMinimumUnstableBuildNumber(ConfigurationSection options, boolean fetchStableVersions) {
        if (fetchStableVersions) {
            return 0;
        }

        int minimum = DEFAULT_MINIMUM_UNSTABLE_BUILD_NUMBER;
        if (options != null) {
            minimum = options.getInt("minimumUnstableBuild", minimum);
        }
        return Math.max(0, minimum);
    }

    private boolean isStableVersion(String version) {
        return version != null && !version.contains("-");
    }
}
