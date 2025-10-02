package eu.nurkert.neverUp2Late.fetcher;

import com.fasterxml.jackson.annotation.JsonProperty;
import eu.nurkert.neverUp2Late.net.HttpClient;
import org.bukkit.Bukkit;
import org.bukkit.configuration.ConfigurationSection;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.logging.Logger;

/**
 * Fetcher for Paper builds using the PaperMC public API.
 */
public class PaperFetcher extends JsonUpdateFetcher {

    private static final String API_URL = "https://api.papermc.io/v2/projects/paper";
    private static final Set<String> STABLE_CHANNELS = Set.of("default", "stable");
    private static final Logger LOGGER = Logger.getLogger(PaperFetcher.class.getName());

    private final boolean fetchStableVersions;

    public PaperFetcher() {
        this(true);
    }

    public PaperFetcher(boolean fetchStableVersions) {
        this(fetchStableVersions, new HttpClient());
    }

    public PaperFetcher(ConfigurationSection options) {
        this(options, new HttpClient());
    }

    PaperFetcher(boolean fetchStableVersions, HttpClient httpClient) {
        super(httpClient);
        this.fetchStableVersions = fetchStableVersions;
    }

    PaperFetcher(ConfigurationSection options, HttpClient httpClient) {
        this(determineStablePreference(options), httpClient);
    }

    @Override
    public void loadLatestBuildInfo() throws Exception {
        ProjectResponse project = getJson(API_URL, ProjectResponse.class);
        List<String> versions = new ArrayList<>(project.versions());
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
        String installedVersion = null;
        boolean restrictToInstalled = false;

        Exception lastError = null;
        for (String version : versions) {
            if (restrictToInstalled) {
                if (installedVersion == null) {
                    installedVersion = getInstalledVersion();
                    LOGGER.fine("Installed Minecraft version detected as " + installedVersion);
                }
                if (installedVersion != null && !installedVersion.isEmpty()
                        && comparator.compare(version, installedVersion) > 0) {
                    LOGGER.fine("Skipping version " + version
                            + " because it exceeds installed version " + installedVersion);
                    continue;
                }
            }
            try {
                VersionResponse versionResponse = getJson(API_URL + "/versions/" + version, VersionResponse.class);
                LOGGER.fine("Builds reported for version " + version + ": " + versionResponse.builds());
                int latestBuild = fetchStableVersions
                        ? selectLatestStableBuild(version, versionResponse.builds())
                        : selectLatestBuild(versionResponse.builds());
                String downloadUrl = API_URL + "/versions/" + version + "/builds/" + latestBuild
                        + "/downloads/paper-" + version + "-" + latestBuild + ".jar";

                setLatestBuildInfo(version, latestBuild, downloadUrl);
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

    private record ProjectResponse(@JsonProperty("versions") List<String> versions) {
        private ProjectResponse {
            versions = versions == null ? List.of() : List.copyOf(versions);
        }
    }

    private record VersionResponse(@JsonProperty("builds") List<Integer> builds) {
        private VersionResponse {
            builds = builds == null ? List.of() : List.copyOf(builds);
        }
    }

    private record BuildResponse(@JsonProperty("channel") String channel) {
    }

    private int selectLatestStableBuild(String version, List<Integer> builds) throws IOException {
        if (builds.isEmpty()) {
            throw new IOException("No builds available for version " + version);
        }

        List<Integer> sortedBuilds = new ArrayList<>(builds);
        sortedBuilds.sort(Comparator.reverseOrder());

        for (Integer build : sortedBuilds) {
            BuildResponse buildResponse = getJson(API_URL + "/versions/" + version + "/builds/" + build, BuildResponse.class);
            String channel = buildResponse.channel();
            LOGGER.fine("Version " + version + " build " + build + " reported channel " + channel);
            if (isStableChannel(channel)) {
                return build;
            }
        }

        throw new IOException("No stable builds available for version " + version);
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
}
