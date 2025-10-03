package eu.nurkert.neverUp2Late.fetcher;

import com.fasterxml.jackson.annotation.JsonProperty;
import eu.nurkert.neverUp2Late.net.HttpClient;
import org.bukkit.Bukkit;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.plugin.Plugin;
import org.bukkit.plugin.PluginManager;

import java.io.IOException;
import java.util.Collection;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.Set;
import java.util.Optional;

/**
 * Fetcher for plugins hosted on SpigotMC via the public Spiget API.
 */
public class SpigotFetcher extends JsonUpdateFetcher {

    private static final String API_ROOT = "https://api.spiget.org/v2/";

    private final int resourceId;
    private final Set<String> preferredGameVersions;
    private final boolean ignoreCompatibilityWarnings;
    private final String installedPluginName;

    public SpigotFetcher(ConfigurationSection options) {
        this(Config.fromConfiguration(options));
    }

    public SpigotFetcher(Config config) {
        this(config, new HttpClient());
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

        VersionRef latestVersionRef = resource.versions().stream()
                .max(Comparator.comparingInt(VersionRef::id))
                .orElseThrow(() -> new IOException("No versions available for resource " + resourceId));

        VersionResponse version = getJson(versionUrl(latestVersionRef.id()), VersionResponse.class);
        if (version == null) {
            throw new IOException("No data returned for version " + latestVersionRef.id());
        }

        String versionName = resolveVersionName(version);
        int buildNumber = resolveBuildNumber(versionName, version.version(), version.id());
        String downloadUrl = resolveDownloadUrl(resource, version);

        setLatestBuildInfo(versionName, buildNumber, downloadUrl);
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
                throw new IOException("Spigot resource " + resourceId
                        + " does not target preferred game versions " + preferredGameVersions);
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
            throw new IOException("Spigot resource " + resourceId + " was not tested with server version "
                    + serverVersion);
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
            return externalUrl.get();
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
