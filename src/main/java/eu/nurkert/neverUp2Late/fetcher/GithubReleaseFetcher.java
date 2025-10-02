package eu.nurkert.neverUp2Late.fetcher;

import com.fasterxml.jackson.annotation.JsonProperty;
import eu.nurkert.neverUp2Late.net.HttpClient;
import eu.nurkert.neverUp2Late.update.DownloadProcessor;
import eu.nurkert.neverUp2Late.update.UpdateContext;
import eu.nurkert.neverUp2Late.util.ArchiveUtils;
import eu.nurkert.neverUp2Late.util.ArchiveUtils.ArchiveEntry;
import org.bukkit.Bukkit;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.plugin.Plugin;
import org.bukkit.plugin.PluginManager;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

import eu.nurkert.neverUp2Late.fetcher.exception.AssetSelectionRequiredException;

import java.util.Locale;
import java.util.logging.Level;

/**
 * Fetcher that retrieves release information from the GitHub Releases API.
 */
public class GithubReleaseFetcher extends JsonUpdateFetcher {

    private static final String API_TEMPLATE = "https://api.github.com/repos/%s/%s/releases";
    private static final Map<String, String> GITHUB_HEADERS = Map.of(
            "Accept", "application/vnd.github+json",
            "X-GitHub-Api-Version", "2022-11-28"
    );

    private final String owner;
    private final String repository;
    private final Pattern assetPattern;
    private final Pattern archiveEntryPattern;
    private final boolean allowPrerelease;
    private final String installedPluginName;

    private Asset selectedAsset;
    private boolean selectedAssetIsArchive;

    public GithubReleaseFetcher(ConfigurationSection options) {
        this(options, HttpClient.builder()
                .headers(GITHUB_HEADERS)
                .build());
    }

    GithubReleaseFetcher(ConfigurationSection options, HttpClient httpClient) {
        super(httpClient);
        Objects.requireNonNull(options, "options");

        this.owner = requireOption(options, "owner");
        this.repository = requireOption(options, "repository");
        String patternValue = trimToNull(options.getString("assetPattern"));
        this.assetPattern = patternValue != null ? Pattern.compile(patternValue) : null;
        String archivePattern = trimToNull(options.getString("archiveEntryPattern"));
        this.archiveEntryPattern = archivePattern != null ? Pattern.compile(archivePattern) : null;
        this.allowPrerelease = options.getBoolean("allowPrerelease", false);
        this.installedPluginName = trimToNull(options.getString("installedPlugin"));
        this.selectedAsset = null;
        this.selectedAssetIsArchive = false;
    }

    @Override
    public void loadLatestBuildInfo() throws Exception {
        Release[] releaseArray = getJson(buildUrl(), Release[].class);
        List<Release> releases = releaseArray != null ? List.of(releaseArray) : List.of();
        if (releases.isEmpty()) {
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

        List<Asset> assets = latest.assets();
        List<Asset> matchingAssets = findMatchingAssets(tagName, assets);

        if (matchingAssets.isEmpty()) {
            throw new IOException("No asset download URL"
                    + (assetPattern != null ? " matching pattern " + assetPattern.pattern() : "")
                    + " for release " + tagName);
        }

        Asset selected = matchingAssets.get(0);
        this.selectedAsset = selected;
        this.selectedAssetIsArchive = isArchiveAsset(selected);

        int build = Math.toIntExact(latest.id());
        setLatestBuildInfo(tagName, build, selected.browserDownloadUrl());
    }

    @Override
    public void configureContext(UpdateContext context) {
        if (selectedAsset == null) {
            context.setDownloadProcessor(null);
            return;
        }
        if (selectedAssetIsArchive) {
            context.setDownloadProcessor(new GithubArchiveDownloadProcessor(
                    archiveEntryPattern,
                    Optional.ofNullable(assetDisplayName(selectedAsset)).orElse(selectedAsset.browserDownloadUrl())));
        } else {
            context.setDownloadProcessor(null);
        }
    }

    public boolean isSelectedAssetArchive() {
        return selectedAssetIsArchive;
    }

    public Optional<String> getSelectedAssetDownloadUrl() {
        return selectedAsset != null ? Optional.ofNullable(selectedAsset.browserDownloadUrl()) : Optional.empty();
    }

    public Optional<Pattern> getArchiveEntryPattern() {
        return Optional.ofNullable(archiveEntryPattern);
    }

    private List<Asset> findMatchingAssets(String tagName, List<Asset> assets)
            throws AssetSelectionRequiredException {
        List<Asset> validAssets = assets.stream()
                .map(asset -> asset)
                .filter(asset -> asset.browserDownloadUrl() != null)
                .collect(Collectors.toList());

        if (assetPattern != null) {
            return validAssets.stream()
                    .filter(asset -> matchesPattern(assetPattern, asset))
                    .collect(Collectors.toList());
        }

        List<Asset> jarAssets = validAssets.stream()
                .filter(GithubReleaseFetcher::isJarAsset)
                .collect(Collectors.toList());

        if (jarAssets.size() == 1) {
            return jarAssets;
        }
        if (jarAssets.size() > 1) {
            throw new AssetSelectionRequiredException(tagName, toReleaseAssets(jarAssets),
                    AssetSelectionRequiredException.AssetType.JAR);
        }

        List<Asset> archiveAssets = validAssets.stream()
                .filter(GithubReleaseFetcher::isArchiveAsset)
                .collect(Collectors.toList());

        if (archiveAssets.size() == 1) {
            return archiveAssets;
        }
        if (archiveAssets.size() > 1) {
            throw new AssetSelectionRequiredException(tagName, toReleaseAssets(archiveAssets),
                    AssetSelectionRequiredException.AssetType.ARCHIVE);
        }

        return validAssets;
    }

    private List<AssetSelectionRequiredException.ReleaseAsset> toReleaseAssets(List<Asset> assets) {
        return assets.stream()
                .map(asset -> new AssetSelectionRequiredException.ReleaseAsset(
                        Optional.ofNullable(assetDisplayName(asset)).orElse(asset.browserDownloadUrl()),
                        asset.browserDownloadUrl(),
                        isArchiveAsset(asset)))
                .collect(Collectors.toList());
    }

    private static boolean matchesPattern(Pattern pattern, Asset asset) {
        String url = asset.browserDownloadUrl();
        if (url != null && pattern.matcher(url).find()) {
            return true;
        }
        String name = asset.name();
        if (name != null && pattern.matcher(name).find()) {
            return true;
        }
        String fileName = assetDisplayName(asset);
        return fileName != null && pattern.matcher(fileName).find();
    }

    private static boolean isJarAsset(Asset asset) {
        return hasExtension(asset, ".jar");
    }

    private static boolean isArchiveAsset(Asset asset) {
        return hasExtension(asset, ".zip")
                || hasExtension(asset, ".tar.gz")
                || hasExtension(asset, ".tgz")
                || hasExtension(asset, ".tar")
                || hasExtension(asset, ".tar.xz")
                || hasExtension(asset, ".tar.bz2");
    }

    private static boolean hasExtension(Asset asset, String extension) {
        String name = asset.name();
        if (name != null && name.toLowerCase(Locale.ROOT).endsWith(extension)) {
            return true;
        }
        String fileName = assetDisplayName(asset);
        if (fileName != null && fileName.toLowerCase(Locale.ROOT).endsWith(extension)) {
            return true;
        }
        String url = stripQuery(asset.browserDownloadUrl());
        return url != null && url.toLowerCase(Locale.ROOT).endsWith(extension);
    }

    private static String assetDisplayName(Asset asset) {
        String name = asset.name();
        if (name != null) {
            return name;
        }
        return extractFileName(asset.browserDownloadUrl());
    }

    private static String extractFileName(String value) {
        String sanitized = stripQuery(value);
        if (sanitized == null || sanitized.isBlank()) {
            return null;
        }
        int lastSlash = sanitized.lastIndexOf('/');
        return lastSlash >= 0 ? sanitized.substring(lastSlash + 1) : sanitized;
    }

    private static String stripQuery(String value) {
        if (value == null) {
            return null;
        }
        int queryIndex = value.indexOf('?');
        if (queryIndex >= 0) {
            return value.substring(0, queryIndex);
        }
        int hashIndex = value.indexOf('#');
        return hashIndex >= 0 ? value.substring(0, hashIndex) : value;
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
            @JsonProperty("name") String name,
            @JsonProperty("browser_download_url") String browserDownloadUrl
    ) {
        private Asset {
            name = trimToNull(name);
            browserDownloadUrl = trimToNull(browserDownloadUrl);
        }
    }

    private static final class GithubArchiveDownloadProcessor implements DownloadProcessor {

        private final Pattern entryPattern;
        private final String assetLabel;

        private GithubArchiveDownloadProcessor(Pattern entryPattern, String assetLabel) {
            this.entryPattern = entryPattern;
            this.assetLabel = assetLabel;
        }

        @Override
        public Path process(UpdateContext context, Path downloadedFile) throws IOException {
            List<ArchiveEntry> entries = ArchiveUtils.listJarEntries(downloadedFile);
            if (entries.isEmpty()) {
                throw new IOException("Das Archiv enthält keine JAR-Dateien: " + downloadedFile);
            }

            ArchiveEntry selected = selectEntry(entries);

            Path parent = downloadedFile.getParent();
            Path tempJar = parent != null
                    ? Files.createTempFile(parent, "nu2l-", ".jar")
                    : Files.createTempFile("nu2l-", ".jar");
            try {
                ArchiveUtils.extractEntry(downloadedFile, selected.fullPath(), tempJar);
                moveReplacing(tempJar, downloadedFile);
                context.log(Level.FINE, "Extrahierte {0} aus {1}", selected.fileName(), assetLabel);
            } finally {
                Files.deleteIfExists(tempJar);
            }

            return downloadedFile;
        }

        private ArchiveEntry selectEntry(List<ArchiveEntry> entries) throws IOException {
            if (entryPattern != null) {
                return entries.stream()
                        .filter(entry -> entryPattern.matcher(entry.fullPath()).matches()
                                || entryPattern.matcher(entry.fileName()).matches())
                        .findFirst()
                        .orElseThrow(() -> new IOException("Keine zur Regex passende JAR im Archiv gefunden: "
                                + entryPattern.pattern()));
            }
            if (entries.size() == 1) {
                return entries.get(0);
            }
            String candidates = entries.stream()
                    .map(ArchiveEntry::fullPath)
                    .collect(Collectors.joining(", "));
            throw new IOException("Mehrere JAR-Dateien im Archiv gefunden. Bitte konfiguriere 'archiveEntryPattern'. Kandidaten: "
                    + candidates);
        }

        private void moveReplacing(Path source, Path target) throws IOException {
            try {
                Files.move(source, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            } catch (IOException ex) {
                Files.move(source, target, StandardCopyOption.REPLACE_EXISTING);
            }
        }
    }
}
