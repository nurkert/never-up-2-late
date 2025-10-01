package eu.nurkert.neverUp2Late.fetcher;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.core.type.TypeReference;
import eu.nurkert.neverUp2Late.net.HttpClient;
import org.bukkit.Bukkit;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.plugin.Plugin;
import org.bukkit.plugin.PluginManager;

import java.io.IOException;
import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.regex.Pattern;

/**
 * Fetcher that retrieves release information from the GitHub Releases API.
 */
public class GithubReleaseFetcher extends JsonUpdateFetcher {

    private static final String API_TEMPLATE = "https://api.github.com/repos/%s/%s/releases";

    private final String owner;
    private final String repository;
    private final Pattern assetPattern;
    private final boolean allowPrerelease;
    private final String installedPluginName;

    public GithubReleaseFetcher(ConfigurationSection options) {
        this(options, new HttpClient());
    }

    GithubReleaseFetcher(ConfigurationSection options, HttpClient httpClient) {
        super(httpClient);
        Objects.requireNonNull(options, "options");

        this.owner = requireOption(options, "owner");
        this.repository = requireOption(options, "repository");
        String patternValue = trimToNull(options.getString("assetPattern"));
        this.assetPattern = patternValue != null ? Pattern.compile(patternValue) : null;
        this.allowPrerelease = options.getBoolean("allowPrerelease", false);
        this.installedPluginName = trimToNull(options.getString("installedPlugin"));
    }

    @Override
    public void loadLatestBuildInfo() throws Exception {
        List<Release> releases = getJson(buildUrl(), new TypeReference<>() {});
        if (releases == null || releases.isEmpty()) {
            throw new IOException("No releases returned for " + owner + "/" + repository);
        }

        Release latest = releases.stream()
                .filter(release -> !release.draft())
                .filter(release -> allowPrerelease || !release.prerelease())
                .max(Comparator
                        .comparing(GithubReleaseFetcher::publishedAtOrMin)
                        .thenComparingLong(Release::id))
                .orElseThrow(() -> new IOException("No suitable releases found for " + owner + "/" + repository));

        String tagName = trimToNull(latest.tagName());
        if (tagName == null) {
            throw new IOException("Latest release for " + owner + "/" + repository + " is missing a tag name");
        }

        String downloadUrl = latest.assets().stream()
                .map(Asset::browserDownloadUrl)
                .map(GithubReleaseFetcher::trimToNull)
                .filter(Objects::nonNull)
                .filter(url -> assetPattern == null || assetPattern.matcher(url).find())
                .findFirst()
                .orElseThrow(() -> new IOException("No asset download URL" +
                        (assetPattern != null ? " matching pattern " + assetPattern.pattern() : "") +
                        " for release " + tagName));

        int build = Math.toIntExact(latest.id());
        setLatestBuildInfo(tagName, build, downloadUrl);
    }

    @Override
    public String getInstalledVersion() {
        if (installedPluginName == null) {
            return null;
        }

        PluginManager pluginManager = Bukkit.getPluginManager();
        if (pluginManager == null) {
            return null;
        }

        Plugin plugin = pluginManager.getPlugin(installedPluginName);
        if (plugin == null) {
            return null;
        }

        return plugin.getDescription().getVersion();
    }

    private static Instant publishedAtOrMin(Release release) {
        Instant publishedAt = release.publishedAt();
        if (publishedAt != null) {
            return publishedAt;
        }
        Instant createdAt = release.createdAt();
        return createdAt != null ? createdAt : Instant.MIN;
    }

    private static String requireOption(ConfigurationSection section, String key) {
        String value = trimToNull(section.getString(key));
        if (value == null) {
            throw new IllegalArgumentException("Missing required option '" + key + "'");
        }
        return value;
    }

    private static String trimToNull(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }

    private String buildUrl() {
        return String.format(API_TEMPLATE, owner, repository);
    }

    private record Release(
            @JsonProperty("id") long id,
            @JsonProperty("tag_name") String tagName,
            @JsonProperty("draft") boolean draft,
            @JsonProperty("prerelease") boolean prerelease,
            @JsonProperty("published_at") Instant publishedAt,
            @JsonProperty("created_at") Instant createdAt,
            @JsonProperty("assets") List<Asset> assets
    ) {
        private Release {
            assets = assets == null ? List.of() : List.copyOf(assets);
        }
    }

    private record Asset(
            @JsonProperty("browser_download_url") String browserDownloadUrl
    ) {
    }
}
