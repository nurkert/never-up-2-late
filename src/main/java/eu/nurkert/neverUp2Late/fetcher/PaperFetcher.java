package eu.nurkert.neverUp2Late.fetcher;

import com.fasterxml.jackson.annotation.JsonProperty;
import eu.nurkert.neverUp2Late.net.HttpClient;
import org.bukkit.Bukkit;

import java.util.ArrayList;
import java.util.List;

/**
 * Fetcher for Paper builds using the PaperMC public API.
 */
public class PaperFetcher extends JsonUpdateFetcher {

    private static final String API_URL = "https://api.papermc.io/v2/projects/paper";

    private final boolean fetchStableVersions;

    public PaperFetcher() {
        this(true);
    }

    public PaperFetcher(boolean fetchStableVersions) {
        this(fetchStableVersions, new HttpClient());
    }

    PaperFetcher(boolean fetchStableVersions, HttpClient httpClient) {
        super(httpClient);
        this.fetchStableVersions = fetchStableVersions;
    }

    @Override
    public void loadLatestBuildInfo() throws Exception {
        ProjectResponse project = getJson(API_URL, ProjectResponse.class);
        List<String> versions = new ArrayList<>(project.versions());
        if (fetchStableVersions) {
            versions = filterStableVersions(versions);
        }
        String latestVersion = requireLatestVersion(versions);

        VersionResponse versionResponse = getJson(API_URL + "/versions/" + latestVersion, VersionResponse.class);
        int latestBuild = selectLatestBuild(versionResponse.builds());
        String downloadUrl = API_URL + "/versions/" + latestVersion + "/builds/" + latestBuild
                + "/downloads/paper-" + latestVersion + "-" + latestBuild + ".jar";

        setLatestBuildInfo(latestVersion, latestBuild, downloadUrl);
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
}
