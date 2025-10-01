package eu.nurkert.neverUp2Late.fetcher;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.core.type.TypeReference;
import eu.nurkert.neverUp2Late.net.HttpClient;

import java.io.IOException;
import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.OptionalInt;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Fetcher for Geyser builds using the Modrinth API.
 */
public class GeyserFetcher extends JsonUpdateFetcher {

    private static final String API_URL = "https://api.modrinth.com/v2/project/geyser/version";
    private static final Set<String> SUPPORTED_LOADERS = Set.of("paper", "spigot");

    public GeyserFetcher() {
        this(new HttpClient());
    }

    GeyserFetcher(HttpClient httpClient) {
        super(httpClient);
    }

    @Override
    public void loadLatestBuildInfo() throws Exception {
        List<VersionResponse> versions = getJson(API_URL, new TypeReference<>() {});

        List<VersionResponse> listedVersions = versions.stream()
                .filter(version -> "listed".equalsIgnoreCase(version.status()))
                .collect(Collectors.toList());

        String latestMinecraftVersion = findLatestVersion(listedVersions.stream()
                        .flatMap(version -> version.gameVersions().stream())
                        .collect(Collectors.toSet()))
                .orElseThrow(() -> new IOException("No Minecraft versions available"));

        VersionResponse latestBuild = listedVersions.stream()
                .filter(version -> version.gameVersions().contains(latestMinecraftVersion))
                .filter(version -> version.loaders().stream().map(loader -> loader.toLowerCase(Locale.ROOT))
                        .anyMatch(SUPPORTED_LOADERS::contains))
                .max(Comparator.comparing(VersionResponse::datePublished))
                .orElseThrow(() -> new IOException("No builds available for version " + latestMinecraftVersion));

        OptionalInt buildNumber = extractBuildNumber(latestBuild.versionNumber());
        if (buildNumber.isEmpty()) {
            throw new IOException("Unable to determine build number from version " + latestBuild.versionNumber());
        }

        String downloadUrl = latestBuild.files().stream()
                .sorted(Comparator.comparing(GeyserFile::primary).reversed())
                .map(GeyserFile::url)
                .filter(url -> url != null && !url.isBlank())
                .findFirst()
                .orElseThrow(() -> new IOException("No download available for version " + latestBuild.versionNumber()));

        setLatestBuildInfo(latestBuild.versionNumber(), buildNumber.getAsInt(), downloadUrl);
    }

    @Override
    public String getInstalledVersion() {
        return null;
    }

    private record VersionResponse(
            @JsonProperty("id") String id,
            @JsonProperty("version_number") String versionNumber,
            @JsonProperty("status") String status,
            @JsonProperty("date_published") Instant datePublished,
            @JsonProperty("game_versions") List<String> gameVersions,
            @JsonProperty("loaders") List<String> loaders,
            @JsonProperty("files") List<GeyserFile> files
    ) {
        private VersionResponse {
            gameVersions = gameVersions == null ? List.of() : List.copyOf(gameVersions);
            loaders = loaders == null ? List.of() : List.copyOf(loaders);
            files = files == null ? List.of() : List.copyOf(files);
        }
    }

    private record GeyserFile(
            @JsonProperty("url") String url,
            @JsonProperty("primary") boolean primary
    ) {
    }
}
