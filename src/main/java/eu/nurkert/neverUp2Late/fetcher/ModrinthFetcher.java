package eu.nurkert.neverUp2Late.fetcher;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.core.type.TypeReference;
import eu.nurkert.neverUp2Late.fetcher.exception.CompatibilityMismatchException;
import eu.nurkert.neverUp2Late.net.HttpClient;
import org.bukkit.Bukkit;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.plugin.Plugin;
import org.bukkit.plugin.PluginManager;

import java.io.IOException;
import java.time.Instant;
import java.util.Collection;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.OptionalInt;
import java.util.Set;
import java.util.function.Supplier;
import java.util.stream.Collectors;

/**
 * Generic Modrinth fetcher that can retrieve the latest build for any project.
 */
public class ModrinthFetcher extends JsonUpdateFetcher {

    private static final String API_TEMPLATE = "https://api.modrinth.com/v2/project/%s/version";

    private final String apiUrl;
    private final Set<String> supportedLoaders;
    private final Set<String> allowedStatuses;
    private final Set<String> allowedVersionTypes;
    private final Set<String> preferredGameVersions;
    private final boolean preferPrimaryFile;
    private final boolean requireBuildNumber;
    private final String installedPluginName;
    private final String maxGameVersion;
    private final boolean ignoreCompatibilityWarnings;

    public ModrinthFetcher(ConfigurationSection options) {
        this(Config.fromConfiguration(options));
    }

    public ModrinthFetcher(Config config) {
        this(config, new HttpClient());
    }

    ModrinthFetcher(Config config, HttpClient httpClient) {
        super(httpClient);
        Objects.requireNonNull(config, "config");

        this.apiUrl = String.format(API_TEMPLATE, config.projectSlug);
        this.supportedLoaders = config.supportedLoaders;
        this.allowedStatuses = config.allowedStatuses;
        this.allowedVersionTypes = config.allowedVersionTypes;
        this.preferredGameVersions = config.preferredGameVersions;
        this.preferPrimaryFile = config.preferPrimaryFile;
        this.requireBuildNumber = config.requireBuildNumber;
        this.installedPluginName = config.installedPluginName;
        this.maxGameVersion = config.maxGameVersion;
        this.ignoreCompatibilityWarnings = config.ignoreCompatibilityWarnings();
    }

    @Override
    public void loadLatestBuildInfo() throws Exception {
        List<VersionResponse> versions = getJson(apiUrl, new TypeReference<>() {});
        if (versions == null || versions.isEmpty()) {
            throw new IOException("No versions returned for " + apiUrl);
        }

        List<VersionResponse> eligible = versions.stream()
                .filter(version -> allowedStatuses.isEmpty() ||
                        allowedStatuses.contains(normalize(version.status())))
                .filter(version -> allowedVersionTypes.isEmpty() ||
                        allowedVersionTypes.contains(normalize(version.versionType())))
                .collect(Collectors.toList());

        if (eligible.isEmpty()) {
            throw new IOException("No versions matched the configured criteria for " + apiUrl);
        }

        String targetGameVersion = determineTargetGameVersion(eligible);

        VersionResponse latestBuild = eligible.stream()
                .filter(version -> matchesLoader(version, supportedLoaders))
                .filter(version -> matchesGameVersion(version, targetGameVersion))
                .max(Comparator
                        .comparing(ModrinthFetcher::publishedAtOrMin)
                        .thenComparing(VersionResponse::versionNumber, semanticVersionComparator()))
                .orElseThrow(() -> new IOException("No builds available" +
                        (targetGameVersion != null ? " for game version " + targetGameVersion : "")));

        int buildNumber = resolveBuildNumber(latestBuild);
        String downloadUrl = resolveDownloadUrl(latestBuild);

        setLatestBuildInfo(latestBuild.versionNumber(), buildNumber, downloadUrl);
    }

    @Override
    public String getInstalledVersion() {
        if (installedPluginName == null || installedPluginName.isBlank()) {
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

    private String determineTargetGameVersion(Collection<VersionResponse> versions) throws IOException {
        Comparator<String> comparator = semanticVersionComparator();
        String maximumGameVersion = determineMaximumGameVersion();

        if (preferredGameVersions.isEmpty()) {
            Set<String> available = versions.stream()
                    .flatMap(version -> version.gameVersions().stream())
                    .map(ModrinthFetcher::normalize)
                    .filter(Objects::nonNull)
                    .collect(Collectors.toSet());
            if (available.isEmpty()) {
                return null;
            }

            Set<String> compatible = filterByMaximumVersion(available, maximumGameVersion, comparator);
            Set<String> candidates = compatible.isEmpty() ? available : compatible;
            return requireLatestVersion(candidates);
        }

        Set<String> matching = versions.stream()
                .flatMap(version -> version.gameVersions().stream())
                .map(ModrinthFetcher::normalize)
                .filter(preferredGameVersions::contains)
                .collect(Collectors.toSet());

        if (matching.isEmpty()) {
            throw new CompatibilityMismatchException(
                    "No game versions available matching preferences " + preferredGameVersions,
                    null,
                    versions.stream()
                            .flatMap(version -> version.gameVersions().stream())
                            .collect(Collectors.toCollection(java.util.LinkedHashSet::new))
            );
        }

        Set<String> compatible = filterByMaximumVersion(matching, maximumGameVersion, comparator);
        Set<String> candidates = compatible.isEmpty() ? matching : compatible;
        return requireLatestVersion(candidates);
    }

    private Set<String> filterByMaximumVersion(Set<String> versions, String maximum, Comparator<String> comparator) {
        if (maximum == null || versions.isEmpty()) {
            return versions;
        }
        return versions.stream()
                .filter(version -> comparator.compare(version, maximum) <= 0)
                .collect(Collectors.toSet());
    }

    private String determineMaximumGameVersion() {
        if (ignoreCompatibilityWarnings) {
            return null;
        }
        if (maxGameVersion != null) {
            return maxGameVersion;
        }

        String serverVersion = extractServerGameVersion();
        return normalize(serverVersion);
    }

    private String extractServerGameVersion() {
        try {
            String fullVersion = Bukkit.getVersion();
            if (fullVersion == null) {
                return null;
            }

            int start = fullVersion.indexOf("MC: ");
            if (start == -1) {
                return fullVersion;
            }

            int end = fullVersion.indexOf(')', start);
            if (end == -1) {
                return fullVersion.substring(start + 4).trim();
            }
            return fullVersion.substring(start + 4, end).trim();
        } catch (Throwable ignored) {
            return null;
        }
    }

    private int resolveBuildNumber(VersionResponse version) throws IOException {
        OptionalInt buildNumber = extractBuildNumber(version.versionNumber());
        if (buildNumber.isPresent()) {
            return buildNumber.getAsInt();
        }

        if (requireBuildNumber) {
            throw new IOException("Unable to determine build number from version " + version.versionNumber());
        }

        int hash = Objects.hash(version.id(), version.versionNumber());
        if (hash == Integer.MIN_VALUE) {
            return Integer.MAX_VALUE;
        }
        return Math.abs(hash);
    }

    private String resolveDownloadUrl(VersionResponse version) throws IOException {
        Comparator<ModrinthFile> comparator = Comparator
                .comparing(ModrinthFile::primary)
                .reversed();

        Supplier<IOException> exceptionSupplier = () ->
                new IOException("No download available for version " + version.versionNumber());

        return (preferPrimaryFile
                ? version.files().stream().sorted(comparator)
                : version.files().stream())
                .map(ModrinthFile::url)
                .map(ModrinthFetcher::trimToNull)
                .filter(Objects::nonNull)
                .findFirst()
                .orElseThrow(exceptionSupplier);
    }

    private static boolean matchesLoader(VersionResponse version, Set<String> supportedLoaders) {
        if (supportedLoaders.isEmpty()) {
            return true;
        }
        return version.loaders().stream()
                .map(ModrinthFetcher::normalize)
                .anyMatch(supportedLoaders::contains);
    }

    private static boolean matchesGameVersion(VersionResponse version, String gameVersion) {
        if (gameVersion == null) {
            return true;
        }
        return version.gameVersions().stream()
                .map(ModrinthFetcher::normalize)
                .anyMatch(gameVersion::equals);
    }

    private static Instant publishedAtOrMin(VersionResponse version) {
        Instant publishedAt = version.datePublished();
        return publishedAt != null ? publishedAt : Instant.MIN;
    }

    private static String normalize(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed.toLowerCase(Locale.ROOT);
    }

    private static String trimToNull(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }

    private record VersionResponse(
            @JsonProperty("id") String id,
            @JsonProperty("version_number") String versionNumber,
            @JsonProperty("status") String status,
            @JsonProperty("version_type") String versionType,
            @JsonProperty("date_published") Instant datePublished,
            @JsonProperty("game_versions") List<String> gameVersions,
            @JsonProperty("loaders") List<String> loaders,
            @JsonProperty("files") List<ModrinthFile> files
    ) {
        private VersionResponse {
            gameVersions = gameVersions == null ? List.of() : List.copyOf(gameVersions);
            loaders = loaders == null ? List.of() : List.copyOf(loaders);
            files = files == null ? List.of() : List.copyOf(files);
        }
    }

    private record ModrinthFile(
            @JsonProperty("url") String url,
            @JsonProperty("primary") boolean primary
    ) implements Comparable<ModrinthFile> {
        @Override
        public int compareTo(ModrinthFile other) {
            return Boolean.compare(this.primary, other.primary);
        }
    }

    public static ConfigBuilder builder(String projectSlug) {
        return new ConfigBuilder(projectSlug);
    }

    public static final class Config {
        private final String projectSlug;
        private final Set<String> supportedLoaders;
        private final Set<String> allowedStatuses;
        private final Set<String> allowedVersionTypes;
        private final Set<String> preferredGameVersions;
        private final boolean preferPrimaryFile;
        private final boolean requireBuildNumber;
        private final String installedPluginName;
        private final String maxGameVersion;
        private final boolean ignoreCompatibilityWarnings;

        private Config(ConfigBuilder builder) {
            this.projectSlug = Objects.requireNonNull(trimToNull(builder.projectSlug), "projectSlug");
            this.supportedLoaders = builder.supportedLoaders;
            this.allowedStatuses = builder.allowedStatuses;
            this.allowedVersionTypes = builder.allowedVersionTypes;
            this.preferredGameVersions = builder.preferredGameVersions;
            this.preferPrimaryFile = builder.preferPrimaryFile;
            this.requireBuildNumber = builder.requireBuildNumber;
            this.installedPluginName = trimToNull(builder.installedPluginName);
            this.maxGameVersion = builder.maxGameVersion;
            this.ignoreCompatibilityWarnings = builder.ignoreCompatibilityWarnings;
        }

        public static Config fromConfiguration(ConfigurationSection options) {
            Objects.requireNonNull(options, "options");

            ConfigBuilder builder = builder(requireOption(options, "project"));
            builder.loaders(options.getStringList("loaders"));
            builder.statuses(options.getStringList("statuses"));
            builder.versionTypes(options.getStringList("versionTypes"));
            builder.gameVersions(options.getStringList("gameVersions"));
            builder.preferPrimaryFile(options.getBoolean("preferPrimaryFile", true));
            builder.requireBuildNumber(options.getBoolean("requireBuildNumber", false));
            builder.installedPlugin(options.getString("installedPlugin"));
            builder.maxGameVersion(options.getString("maxGameVersion"));
            if (options.contains("ignoreCompatibilityWarnings")) {
                builder.ignoreCompatibilityWarnings(options.getBoolean("ignoreCompatibilityWarnings"));
            }
            return builder.build();
        }

        public boolean ignoreCompatibilityWarnings() {
            return ignoreCompatibilityWarnings;
        }

        private static String requireOption(ConfigurationSection section, String key) {
            String value = trimToNull(section.getString(key));
            if (value == null) {
                throw new IllegalArgumentException("Missing required option '" + key + "'");
            }
            return value;
        }
    }

    public static final class ConfigBuilder {
        private final String projectSlug;
        private Set<String> supportedLoaders = Set.of();
        private Set<String> allowedStatuses = Set.of("listed");
        private Set<String> allowedVersionTypes = Set.of();
        private Set<String> preferredGameVersions = Set.of();
        private boolean preferPrimaryFile = true;
        private boolean requireBuildNumber = false;
        private String installedPluginName;
        private String maxGameVersion;
        private boolean ignoreCompatibilityWarnings;

        private ConfigBuilder(String projectSlug) {
            this.projectSlug = projectSlug;
        }

        public ConfigBuilder loaders(Collection<String> loaders) {
            this.supportedLoaders = normalizeToSet(loaders, true);
            return this;
        }

        public ConfigBuilder statuses(Collection<String> statuses) {
            this.allowedStatuses = normalizeToSet(statuses, true);
            return this;
        }

        public ConfigBuilder versionTypes(Collection<String> versionTypes) {
            this.allowedVersionTypes = normalizeToSet(versionTypes, true);
            return this;
        }

        public ConfigBuilder gameVersions(Collection<String> gameVersions) {
            this.preferredGameVersions = normalizeToSet(gameVersions, true);
            return this;
        }

        public ConfigBuilder preferPrimaryFile(boolean preferPrimaryFile) {
            this.preferPrimaryFile = preferPrimaryFile;
            return this;
        }

        public ConfigBuilder requireBuildNumber(boolean requireBuildNumber) {
            this.requireBuildNumber = requireBuildNumber;
            return this;
        }

        public ConfigBuilder installedPlugin(String installedPlugin) {
            this.installedPluginName = installedPlugin;
            return this;
        }

        public ConfigBuilder maxGameVersion(String maxGameVersion) {
            this.maxGameVersion = normalize(maxGameVersion);
            return this;
        }

        public ConfigBuilder ignoreCompatibilityWarnings(boolean ignoreCompatibilityWarnings) {
            this.ignoreCompatibilityWarnings = ignoreCompatibilityWarnings;
            return this;
        }

        public Config build() {
            return new Config(this);
        }

        private static Set<String> normalizeToSet(Collection<String> values, boolean toLowerCase) {
            if (values == null || values.isEmpty()) {
                return Set.of();
            }
            return values.stream()
                    .map(ModrinthFetcher::trimToNull)
                    .filter(Objects::nonNull)
                    .map(value -> toLowerCase ? value.toLowerCase(Locale.ROOT) : value)
                    .collect(Collectors.collectingAndThen(Collectors.toSet(), Set::copyOf));
        }
    }
}
