package eu.nurkert.neverUp2Late.fetcher;

import com.fasterxml.jackson.annotation.JsonProperty;
import eu.nurkert.neverUp2Late.fetcher.exception.CompatibilityMismatchException;
import eu.nurkert.neverUp2Late.net.HttpClient;
import org.bukkit.Bukkit;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.plugin.Plugin;
import org.bukkit.plugin.PluginManager;

import java.io.IOException;
import java.net.URI;
import java.net.URISyntaxException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Fetcher for plugins hosted on SpigotMC via the public Spiget API.
 */
public class SpigotFetcher extends JsonUpdateFetcher {

    private static final String API_ROOT = "https://api.spiget.org/v2/";
    private static final Set<String> CURSEFORGE_TRAILING_SEGMENTS = Set.of(
            "files",
            "download",
            "relations",
            "changelog",
            "description",
            "issues",
            "images",
            "comments",
            "wiki",
            "source"
    );
    private static final Set<String> CURSEFORGE_CATEGORY_SEGMENTS = Set.of(
            "minecraft",
            "mc-mods",
            "bukkit-plugins",
            "customization",
            "texture-packs",
            "worlds",
            "maps",
            "addons",
            "modpacks",
            "shaderpacks",
            "shaders",
            "datapacks",
            "data-packs",
            "resourcepacks",
            "resource-packs"
    );
    private static final Pattern CURSEFORGE_DATA_PROJECT_ID_PATTERN = Pattern.compile(
            "data-project-id\\s*=\\s*\"?(\\d+)\"?",
            Pattern.CASE_INSENSITIVE
    );
    private static final Pattern CURSEFORGE_SPAN_PROJECT_ID_PATTERN = Pattern.compile(
            "project-id[^>]*>(\\d+)<",
            Pattern.CASE_INSENSITIVE
    );

    private final int resourceId;
    private final Set<String> preferredGameVersions;
    private final boolean ignoreCompatibilityWarnings;
    private final String installedPluginName;

    public SpigotFetcher(ConfigurationSection options) {
        this(Config.fromConfiguration(options));
    }

    public SpigotFetcher(Config config) {
        this(config, HttpClient.builder()
                .header("Spiget-User-Agent", buildSpigetUserAgent(config))
                .accept("application/json")
                .build());
    }

    SpigotFetcher(Config config, HttpClient httpClient) {
        super(httpClient);
        Objects.requireNonNull(config, "config");
        this.resourceId = config.resourceId();
        this.preferredGameVersions = config.preferredGameVersions();
        this.ignoreCompatibilityWarnings = config.ignoreCompatibilityWarnings();
        this.installedPluginName = config.installedPluginName();
    }

    @Override
    public void loadLatestBuildInfo() throws Exception {
        ResourceResponse resource = getJson(resourceUrl(), ResourceResponse.class);
        if (resource == null) {
            throw new IOException("No response received for resource " + resourceId);
        }

        if (resource.premium()) {
            throw new IOException("Spigot resource " + resourceId + " is premium and cannot be downloaded");
        }

        ensureCompatibility(resource);

        List<VersionRef> versions = new ArrayList<>(resource.versions());
        versions.sort(Comparator.comparingInt(VersionRef::id).reversed());

        if (versions.isEmpty()) {
            throw new IOException("No versions available for resource " + resourceId);
        }

        IOException lastFailure = null;

        for (VersionRef ref : versions) {
            VersionResponse version;
            try {
                version = getJson(versionUrl(ref.id()), VersionResponse.class);
            } catch (IOException e) {
                lastFailure = e;
                continue;
            }

            if (version == null) {
                lastFailure = new IOException("No data returned for version " + ref.id());
                continue;
            }

            String downloadUrl;
            try {
                downloadUrl = resolveDownloadUrl(resource, version);
            } catch (IOException e) {
                lastFailure = e;
                continue;
            }

            if (downloadUrl == null || downloadUrl.isBlank()) {
                continue;
            }

            String versionName = resolveVersionName(version);
            int buildNumber = resolveBuildNumber(versionName, version.version(), version.id());

            setLatestBuildInfo(versionName, buildNumber, downloadUrl);
            return;
        }

        if (lastFailure != null) {
            throw lastFailure;
        }

        throw new IOException("No downloadable versions available for resource " + resourceId);
    }

    @Override
    public String getInstalledVersion() {
        if (installedPluginName == null || installedPluginName.isBlank()) {
            return null;
        }

        PluginManager manager = Bukkit.getPluginManager();
        if (manager == null) {
            return null;
        }

        Plugin plugin = manager.getPlugin(installedPluginName);
        if (plugin == null) {
            return null;
        }

        return plugin.getDescription().getVersion();
    }

    private void ensureCompatibility(ResourceResponse resource) throws IOException {
        Set<String> availableVersions = collectAvailableVersions(resource);

        boolean matchesPreferred = preferredGameVersions.isEmpty()
                || matchesAny(preferredGameVersions, availableVersions);
        if (!matchesPreferred) {
            if (!ignoreCompatibilityWarnings) {
                throw new CompatibilityMismatchException("Spigot resource " + resourceId
                        + " does not target preferred game versions " + preferredGameVersions,
                        null,
                        availableVersions);
            }
            return;
        }

        if (ignoreCompatibilityWarnings || availableVersions.isEmpty()) {
            return;
        }

        String serverVersion = extractServerGameVersion();
        if (serverVersion == null) {
            return;
        }

        Set<String> candidates = expandVersionCandidates(serverVersion);
        if (!matchesAny(candidates, availableVersions)) {
            throw new CompatibilityMismatchException("Spigot resource " + resourceId
                    + " was not tested with server version " + serverVersion,
                    serverVersion,
                    availableVersions);
        }
    }

    private Set<String> collectAvailableVersions(ResourceResponse resource) {
        Set<String> versions = new LinkedHashSet<>();
        addNormalizedVersions(versions, resource.testedVersions());
        addNormalizedVersions(versions, resource.supportedVersions());
        addNormalizedVersions(versions, resource.compatibleVersions());
        return versions;
    }

    private void addNormalizedVersions(Set<String> versions, Collection<String> values) {
        if (values == null) {
            return;
        }
        for (String value : values) {
            versions.addAll(expandVersionCandidates(value));
        }
    }

    private boolean matchesAny(Collection<String> requested, Set<String> available) {
        if (requested == null || requested.isEmpty()) {
            return true;
        }
        for (String candidate : requested) {
            if (matchesAny(expandVersionCandidates(candidate), available)) {
                return true;
            }
        }
        return false;
    }

    private boolean matchesAny(Set<String> candidates, Set<String> available) {
        for (String candidate : candidates) {
            if (available.contains(candidate)) {
                return true;
            }
        }
        return false;
    }

    private Set<String> expandVersionCandidates(String version) {
        Set<String> candidates = new LinkedHashSet<>();
        String normalized = normalizeGameVersion(version);
        if (normalized == null) {
            return candidates;
        }

        if (normalized.endsWith(".x")) {
            normalized = normalized.substring(0, normalized.length() - 2);
        } else if (normalized.endsWith("x")) {
            normalized = normalized.substring(0, normalized.length() - 1);
        }

        String current = normalized;
        while (current != null && !current.isEmpty()) {
            candidates.add(current);
            int index = current.lastIndexOf('.');
            if (index <= 0) {
                break;
            }
            current = current.substring(0, index);
        }
        return candidates;
    }

    private String resolveVersionName(VersionResponse version) {
        String name = trimToNull(version.name());
        if (name != null) {
            return name;
        }
        name = trimToNull(version.version());
        if (name != null) {
            return name;
        }
        return Integer.toString(version.id());
    }

    private int resolveBuildNumber(String versionName, String semanticVersion, int versionId) {
        OptionalInt buildNumber = extractBuildNumber(versionName);
        if (buildNumber.isEmpty()) {
            String normalizedSemantic = trimToNull(semanticVersion);
            if (normalizedSemantic != null) {
                buildNumber = extractBuildNumber(normalizedSemantic);
            }
        }
        if (buildNumber.isPresent()) {
            return buildNumber.getAsInt();
        }
        return versionId;
    }

    private String resolveDownloadUrl(ResourceResponse resource, VersionResponse version) throws IOException {
        Optional<String> externalUrl = firstNonBlank(
                optionalExternalUrl(version.file()),
                optionalExternalUrl(resource.file())
        );
        if (externalUrl.isPresent()) {
            String resolved = resolveCurseforgeDownloadUrl(externalUrl.get());
            return resolved != null ? resolved : externalUrl.get();
        }

        Optional<String> fileUrl = firstNonBlank(
                optionalUrl(version.file()),
                optionalUrl(resource.file())
        );
        if (fileUrl.isPresent()) {
            return toAbsoluteUrl(fileUrl.get());
        }

        return versionDownloadUrl(version.id());
    }

    private String resolveCurseforgeDownloadUrl(String url) {
        if (url == null || url.isBlank()) {
            return null;
        }

        URI uri;
        try {
            uri = new URI(url);
        } catch (URISyntaxException e) {
            return null;
        }

        String host = Optional.ofNullable(uri.getHost()).map(String::toLowerCase).orElse("");
        if (!host.contains("curseforge.com")) {
            return null;
        }

        List<Segment> segments = splitSegments(uri.getPath());
        if (segments.isEmpty()) {
            return null;
        }

        int slugIndex = findCurseforgeSlugIndex(segments);
        if (slugIndex == -1) {
            return null;
        }

        String fileId = extractCurseforgeFileId(segments);
        if (fileId == null) {
            return null;
        }

        long projectId = fetchCurseforgeProjectId(uri, segments, slugIndex);

        if (projectId <= 0) {
            return null;
        }

        try {
            CurseforgeFileResponse response = getJson(
                    String.format("https://api.curseforge.com/v1/mods/%d/files/%s", projectId, fileId),
                    CurseforgeFileResponse.class
            );
            if (response == null || response.data() == null) {
                return null;
            }
            return trimToNull(response.data().downloadUrl());
        } catch (IOException e) {
            return null;
        }
    }

    private long fetchCurseforgeProjectId(URI uri, List<Segment> segments, int slugIndex) {
        String scheme = Optional.ofNullable(uri.getScheme()).orElse("https");
        String authority = uri.getAuthority();
        if (authority == null || authority.isBlank()) {
            return -1;
        }

        StringBuilder path = new StringBuilder();
        for (int i = 0; i <= slugIndex; i++) {
            path.append('/').append(segments.get(i).original());
        }

        String projectUrl = scheme + "://" + authority + path;

        String body;
        try {
            body = new HttpClient().get(projectUrl);
        } catch (IOException e) {
            return -1;
        }

        Matcher dataMatcher = CURSEFORGE_DATA_PROJECT_ID_PATTERN.matcher(body);
        if (dataMatcher.find()) {
            return parseProjectId(dataMatcher.group(1));
        }

        Matcher spanMatcher = CURSEFORGE_SPAN_PROJECT_ID_PATTERN.matcher(body);
        if (spanMatcher.find()) {
            return parseProjectId(spanMatcher.group(1));
        }
        return -1;
    }

    private long parseProjectId(String value) {
        if (value == null) {
            return -1;
        }
        try {
            long id = Long.parseLong(value.trim());
            return id > 0 ? id : -1;
        } catch (NumberFormatException ignored) {
            return -1;
        }
    }

    private List<Segment> splitSegments(String path) {
        List<Segment> segments = new ArrayList<>();
        if (path == null || path.isBlank()) {
            return segments;
        }
        for (String segment : path.split("/")) {
            if (segment == null || segment.isBlank()) {
                continue;
            }
            segments.add(new Segment(segment, segment.toLowerCase(Locale.ROOT)));
        }
        return segments;
    }

    private int findCurseforgeSlugIndex(List<Segment> segments) {
        for (int i = 0; i < segments.size(); i++) {
            String normalized = segments.get(i).normalized();
            if (CURSEFORGE_TRAILING_SEGMENTS.contains(normalized)) {
                break;
            }
            if (!CURSEFORGE_CATEGORY_SEGMENTS.contains(normalized)) {
                return i;
            }
        }
        return -1;
    }

    private String extractCurseforgeFileId(List<Segment> segments) {
        for (int i = 0; i < segments.size() - 1; i++) {
            if ("download".equals(segments.get(i).normalized()) && isNumeric(segments.get(i + 1).normalized())) {
                return segments.get(i + 1).original();
            }
        }
        return null;
    }

    private boolean isNumeric(String value) {
        if (value == null || value.isBlank()) {
            return false;
        }
        for (int i = 0; i < value.length(); i++) {
            if (!Character.isDigit(value.charAt(i))) {
                return false;
            }
        }
        return true;
    }

    private record Segment(String original, String normalized) {
    }

    private Optional<String> firstNonBlank(Optional<String> first, Optional<String> second) {
        String value = first.orElse(null);
        if (value != null) {
            return Optional.of(value);
        }
        value = second.orElse(null);
        return value != null ? Optional.of(value) : Optional.empty();
    }

    private Optional<String> optionalExternalUrl(FileInfo file) {
        if (file == null) {
            return Optional.empty();
        }
        return Optional.ofNullable(trimToNull(file.externalUrl()));
    }

    private Optional<String> optionalUrl(FileInfo file) {
        if (file == null) {
            return Optional.empty();
        }
        return Optional.ofNullable(trimToNull(file.url()));
    }

    private String toAbsoluteUrl(String url) {
        String trimmed = trimToNull(url);
        if (trimmed == null) {
            return null;
        }
        if (trimmed.startsWith("http://") || trimmed.startsWith("https://")) {
            return trimmed;
        }
        if (trimmed.startsWith("/")) {
            return API_ROOT + trimmed.substring(1);
        }
        return API_ROOT + trimmed;
    }

    private String resourceUrl() {
        return API_ROOT + "resources/" + resourceId;
    }

    private String versionUrl(int versionId) {
        return API_ROOT + "resources/" + resourceId + "/versions/" + versionId;
    }

    private String versionDownloadUrl(int versionId) {
        return versionUrl(versionId) + "/download";
    }

    private static String buildSpigetUserAgent(Config config) {
        StringBuilder builder = new StringBuilder("NeverUp2Late-Spiget/1.0 (resourceId=")
                .append(config.resourceId());
        String pluginName = config.installedPluginName();
        if (pluginName != null && !pluginName.isBlank()) {
            builder.append("; plugin=").append(pluginName);
        }
        builder.append(')');
        return builder.toString();
    }

    private String extractServerGameVersion() {
        try {
            String fullVersion = Bukkit.getVersion();
            if (fullVersion == null) {
                return null;
            }
            int start = fullVersion.indexOf("MC: ");
            if (start == -1) {
                return normalizeGameVersion(fullVersion);
            }
            int end = fullVersion.indexOf(')', start);
            if (end == -1) {
                return normalizeGameVersion(fullVersion.substring(start + 4));
            }
            return normalizeGameVersion(fullVersion.substring(start + 4, end));
        } catch (Throwable ignored) {
            return null;
        }
    }

    private static String normalizeGameVersion(String value) {
        String trimmed = trimToNull(value);
        return trimmed != null ? trimmed.toLowerCase(Locale.ROOT) : null;
    }

    private static String trimToNull(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }

    private record ResourceResponse(
            @JsonProperty("id") int id,
            @JsonProperty("premium") boolean premium,
            @JsonProperty("versions") List<VersionRef> versions,
            @JsonProperty("testedVersions") List<String> testedVersions,
            @JsonProperty("supportedVersions") List<String> supportedVersions,
            @JsonProperty("compatibleVersions") List<String> compatibleVersions,
            @JsonProperty("file") FileInfo file
    ) {
        private ResourceResponse {
            versions = versions == null ? List.of() : List.copyOf(versions);
            testedVersions = testedVersions == null ? List.of() : List.copyOf(testedVersions);
            supportedVersions = supportedVersions == null ? List.of() : List.copyOf(supportedVersions);
            compatibleVersions = compatibleVersions == null ? List.of() : List.copyOf(compatibleVersions);
        }
    }

    private record VersionRef(@JsonProperty("id") int id) {
    }

    private record VersionResponse(
            @JsonProperty("id") int id,
            @JsonProperty("name") String name,
            @JsonProperty("version") String version,
            @JsonProperty("file") FileInfo file
    ) {
    }

    private record FileInfo(
            @JsonProperty("url") String url,
            @JsonProperty("externalUrl") String externalUrl
    ) {
    }

    private record CurseforgeFileResponse(@JsonProperty("data") CurseforgeFileData data) {
    }

    private record CurseforgeFileData(@JsonProperty("downloadUrl") String downloadUrl) {
    }

    public static ConfigBuilder builder(int resourceId) {
        return new ConfigBuilder(resourceId);
    }

    public static final class Config {
        private final int resourceId;
        private final Set<String> preferredGameVersions;
        private final boolean ignoreCompatibilityWarnings;
        private final String installedPluginName;

        private Config(ConfigBuilder builder) {
            if (builder.resourceId <= 0) {
                throw new IllegalArgumentException("Spigot resourceId must be positive");
            }
            this.resourceId = builder.resourceId;
            this.preferredGameVersions = builder.preferredGameVersions;
            this.ignoreCompatibilityWarnings = builder.ignoreCompatibilityWarnings;
            this.installedPluginName = trimToNull(builder.installedPluginName);
        }

        public static Config fromConfiguration(ConfigurationSection options) {
            Objects.requireNonNull(options, "options");
            int resourceId = options.getInt("resourceId");
            if (resourceId <= 0) {
                throw new IllegalArgumentException("Spigot fetcher requires a positive 'resourceId'");
            }

            ConfigBuilder builder = builder(resourceId);
            if (options.contains("preferredGameVersions")) {
                builder.preferredGameVersions(options.getStringList("preferredGameVersions"));
            }
            if (options.contains("ignoreCompatibilityWarnings")) {
                builder.ignoreCompatibilityWarnings(options.getBoolean("ignoreCompatibilityWarnings"));
            }
            builder.installedPlugin(options.getString("installedPlugin"));
            return builder.build();
        }

        public int resourceId() {
            return resourceId;
        }

        public Set<String> preferredGameVersions() {
            return preferredGameVersions;
        }

        public boolean ignoreCompatibilityWarnings() {
            return ignoreCompatibilityWarnings;
        }

        public String installedPluginName() {
            return installedPluginName;
        }
    }

    public static final class ConfigBuilder {
        private final int resourceId;
        private Set<String> preferredGameVersions = Set.of();
        private boolean ignoreCompatibilityWarnings;
        private String installedPluginName;

        private ConfigBuilder(int resourceId) {
            this.resourceId = resourceId;
        }

        public ConfigBuilder preferredGameVersions(Collection<String> versions) {
            this.preferredGameVersions = normalizeToSet(versions);
            return this;
        }

        public ConfigBuilder ignoreCompatibilityWarnings(boolean ignoreCompatibilityWarnings) {
            this.ignoreCompatibilityWarnings = ignoreCompatibilityWarnings;
            return this;
        }

        public ConfigBuilder installedPlugin(String pluginName) {
            this.installedPluginName = pluginName;
            return this;
        }

        public Config build() {
            return new Config(this);
        }

        private static Set<String> normalizeToSet(Collection<String> values) {
            if (values == null || values.isEmpty()) {
                return Set.of();
            }
            Set<String> normalized = new LinkedHashSet<>();
            for (String value : values) {
                String normalizedValue = normalizeGameVersion(value);
                if (normalizedValue != null) {
                    normalized.add(normalizedValue);
                }
            }
            return Set.copyOf(normalized);
        }
    }
}
