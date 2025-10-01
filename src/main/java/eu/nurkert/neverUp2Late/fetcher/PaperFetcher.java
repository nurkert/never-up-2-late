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

/**
 * Fetcher for Paper builds using the PaperMC public API.
 */
public class PaperFetcher extends JsonUpdateFetcher {

    private static final String API_URL = "https://api.papermc.io/v2/projects/paper";
    private static final Set<String> STABLE_CHANNELS = Set.of("default", "stable");

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

        versions.sort(semanticVersionComparator().reversed());

        Exception lastError = null;
        for (String version : versions) {
            try {
                VersionResponse versionResponse = getJson(API_URL + "/versions/" + version, VersionResponse.class);
                int latestBuild = fetchStableVersions
                        ? selectLatestStableBuild(version, versionResponse.builds())
                        : selectLatestBuild(versionResponse.builds());
                String downloadUrl = API_URL + "/versions/" + version + "/builds/" + latestBuild
                        + "/downloads/paper-" + version + "-" + latestBuild + ".jar";

                setLatestBuildInfo(version, latestBuild, downloadUrl);
                return;
            } catch (Exception exception) {
                lastError = exception;
            }
        }

        if (lastError != null) {
            throw lastError;
        }

        throw new IOException("No suitable builds available");
    }

    @Override
    public String getInstalledVersion() {
        String fullVersion = Bukkit.getVersion();
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
            if (isStableChannel(buildResponse.channel())) {
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
