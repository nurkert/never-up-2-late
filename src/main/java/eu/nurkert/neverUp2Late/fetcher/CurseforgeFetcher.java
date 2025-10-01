package eu.nurkert.neverUp2Late.fetcher;

import com.fasterxml.jackson.annotation.JsonProperty;
import eu.nurkert.neverUp2Late.net.HttpClient;
import org.bukkit.Bukkit;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.plugin.Plugin;
import org.bukkit.plugin.PluginManager;

import java.io.IOException;
import java.time.Instant;
import java.util.Collection;
import java.util.Comparator;
import java.util.EnumSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.OptionalInt;
import java.util.Set;

/**
 * Fetcher for projects hosted on CurseForge.
 */
public class CurseforgeFetcher extends JsonUpdateFetcher {

    private static final String API_TEMPLATE = "https://api.curseforge.com/v1/mods/%d/files?pageSize=%d&index=%d";
    private static final int FILE_STATUS_APPROVED = 4;

    private final Config config;

    public CurseforgeFetcher(ConfigurationSection options) {
        this(Config.fromConfiguration(options));
    }

    public CurseforgeFetcher(Config config) {
        this(config, createHttpClient(config));
    }

    CurseforgeFetcher(Config config, HttpClient httpClient) {
        super(httpClient);
        this.config = Objects.requireNonNull(config, "config");
    }

    private static HttpClient createHttpClient(Config config) {
        if (config.apiKey() == null) {
            return new HttpClient();
        }
        return new HttpClient(Map.of("x-api-key", config.apiKey()));
    }

    @Override
    public void loadLatestBuildInfo() throws Exception {
        Comparator<CurseforgeFile> comparator = Comparator
                .comparing(CurseforgeFile::fileDate, Comparator.nullsLast(Comparator.naturalOrder()))
                .thenComparingInt(this::releasePriority)
                .thenComparingLong(CurseforgeFile::id);

        CurseforgeFile best = null;
        int index = 0;
        int pagesFetched = 0;

        while (pagesFetched < config.maxPages()) {
            FilesResponse response = getJson(String.format(API_TEMPLATE,
                    config.modId(),
                    config.pageSize(),
                    index), FilesResponse.class);

            List<CurseforgeFile> files = response != null ? response.data() : null;
            if (files == null || files.isEmpty()) {
                break;
            }

            for (CurseforgeFile file : files) {
                if (!isEligible(file)) {
                    continue;
                }
                if (best == null || comparator.compare(file, best) > 0) {
                    best = file;
                }
            }

            pagesFetched++;
            Pagination pagination = response.pagination();
            if (pagination == null) {
                break;
            }

            index += config.pageSize();
            if (pagination.totalCount() > 0 && index >= pagination.totalCount()) {
                break;
            }
        }

        if (best == null) {
            throw new IOException("No suitable CurseForge file found for mod " + config.modId());
        }

        String downloadUrl = trimToNull(best.downloadUrl());
        if (downloadUrl == null) {
            throw new IOException("Selected CurseForge file " + best.id() + " does not have a download URL");
        }

        String version = resolveVersion(best);
        int buildNumber = resolveBuildNumber(best);

        setLatestBuildInfo(version, buildNumber, downloadUrl);
    }

    @Override
    public String getInstalledVersion() {
        String pluginName = config.installedPluginName();
        if (pluginName == null || pluginName.isBlank()) {
            return null;
        }

        PluginManager pluginManager = Bukkit.getPluginManager();
        if (pluginManager == null) {
            return null;
        }

        Plugin plugin = pluginManager.getPlugin(pluginName);
        if (plugin == null) {
            return null;
        }

        return plugin.getDescription().getVersion();
    }

    private boolean isEligible(CurseforgeFile file) {
        if (file == null) {
            return false;
        }

        if (file.fileStatus() != FILE_STATUS_APPROVED) {
            return false;
        }

        ReleaseType releaseType = ReleaseType.fromId(file.releaseType());
        if (releaseType == null || !config.allowedReleaseTypes().contains(releaseType)) {
            return false;
        }

        if (!config.preferredGameVersions().isEmpty() && !matchesPreferredGameVersion(file)) {
            return false;
        }

        if (!config.gameVersionTypeIds().isEmpty() && !matchesGameVersionType(file)) {
            return false;
        }

        return trimToNull(file.downloadUrl()) != null;
    }

    private boolean matchesPreferredGameVersion(CurseforgeFile file) {
        for (String version : safeList(file.gameVersions())) {
            String normalized = normalizeGameVersion(version);
            if (normalized != null && config.preferredGameVersions().contains(normalized)) {
                return true;
            }
        }
        return false;
    }

    private boolean matchesGameVersionType(CurseforgeFile file) {
        for (SortableGameVersion sortable : safeList(file.sortableGameVersions())) {
            Integer typeId = sortable.gameVersionTypeId();
            if (typeId != null && config.gameVersionTypeIds().contains(typeId)) {
                return true;
            }
        }
        return false;
    }

    private int releasePriority(CurseforgeFile file) {
        ReleaseType releaseType = ReleaseType.fromId(file.releaseType());
        return releaseType != null ? releaseType.priority() : 0;
    }

    private String resolveVersion(CurseforgeFile file) {
        String displayName = trimToNull(file.displayName());
        if (displayName != null) {
            return displayName;
        }

        String fileName = trimToNull(file.fileName());
        if (fileName != null) {
            return fileName;
        }

        return Long.toString(file.id());
    }

    private int resolveBuildNumber(CurseforgeFile file) {
        OptionalInt buildNumber = extractBuildNumber(trimToNull(file.displayName()));
        if (buildNumber.isEmpty()) {
            buildNumber = extractBuildNumber(trimToNull(file.fileName()));
        }
        if (buildNumber.isPresent()) {
            return buildNumber.getAsInt();
        }

        long id = file.id();
        if (id >= 0 && id <= Integer.MAX_VALUE) {
            return (int) id;
        }

        int hash = Objects.hash(file.id(), file.fileName());
        if (hash == Integer.MIN_VALUE) {
            return Integer.MAX_VALUE;
        }
        return Math.abs(hash);
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

    private static <T> List<T> safeList(List<T> values) {
        return values != null ? values : List.of();
    }

    public static ConfigBuilder builder(long modId) {
        return new ConfigBuilder(modId);
    }

    public static final class Config {
        private final long modId;
        private final String apiKey;
        private final int pageSize;
        private final int maxPages;
        private final Set<String> preferredGameVersions;
        private final Set<Integer> gameVersionTypeIds;
        private final EnumSet<ReleaseType> allowedReleaseTypes;
        private final String installedPluginName;

        private Config(ConfigBuilder builder) {
            if (builder.modId <= 0) {
                throw new IllegalArgumentException("CurseForge modId must be positive");
            }
            this.modId = builder.modId;
            this.apiKey = trimToNull(builder.apiKey);
            this.pageSize = builder.pageSize;
            this.maxPages = builder.maxPages;
            this.preferredGameVersions = builder.preferredGameVersions;
            this.gameVersionTypeIds = builder.gameVersionTypeIds;
            this.allowedReleaseTypes = builder.allowedReleaseTypes.isEmpty()
                    ? EnumSet.of(ReleaseType.RELEASE)
                    : EnumSet.copyOf(builder.allowedReleaseTypes);
            this.installedPluginName = trimToNull(builder.installedPluginName);
        }

        public static Config fromConfiguration(ConfigurationSection options) {
            Objects.requireNonNull(options, "options");

            long modId = options.getLong("modId");
            if (modId <= 0) {
                throw new IllegalArgumentException("CurseForge fetcher requires a positive 'modId'");
            }

            ConfigBuilder builder = builder(modId);
            builder.apiKey(options.getString("apiKey"));
            builder.pageSize(options.getInt("pageSize", builder.pageSize));
            builder.maxPages(options.getInt("maxPages", builder.maxPages));
            if (options.contains("gameVersions")) {
                builder.gameVersions(options.getStringList("gameVersions"));
            }
            if (options.contains("gameVersionTypeIds")) {
                builder.gameVersionTypeIds(options.getIntegerList("gameVersionTypeIds"));
            }
            if (options.contains("releaseTypes")) {
                builder.releaseTypes(options.getStringList("releaseTypes"));
            }
            builder.installedPlugin(options.getString("installedPlugin"));
            return builder.build();
        }

        public long modId() {
            return modId;
        }

        public String apiKey() {
            return apiKey;
        }

        public int pageSize() {
            return pageSize;
        }

        public int maxPages() {
            return maxPages;
        }

        public Set<String> preferredGameVersions() {
            return preferredGameVersions;
        }

        public Set<Integer> gameVersionTypeIds() {
            return gameVersionTypeIds;
        }

        public Set<ReleaseType> allowedReleaseTypes() {
            return allowedReleaseTypes;
        }

        public String installedPluginName() {
            return installedPluginName;
        }
    }

    public static final class ConfigBuilder {
        private final long modId;
        private String apiKey;
        private int pageSize = 50;
        private int maxPages = 1;
        private Set<String> preferredGameVersions = Set.of();
        private Set<Integer> gameVersionTypeIds = Set.of();
        private EnumSet<ReleaseType> allowedReleaseTypes = EnumSet.of(ReleaseType.RELEASE);
        private String installedPluginName;

        private ConfigBuilder(long modId) {
            this.modId = modId;
        }

        public ConfigBuilder apiKey(String apiKey) {
            this.apiKey = apiKey;
            return this;
        }

        public ConfigBuilder pageSize(int pageSize) {
            if (pageSize > 0) {
                this.pageSize = pageSize;
            }
            return this;
        }

        public ConfigBuilder maxPages(int maxPages) {
            if (maxPages > 0) {
                this.maxPages = maxPages;
            }
            return this;
        }

        public ConfigBuilder gameVersions(Collection<String> versions) {
            this.preferredGameVersions = copyGameVersions(versions);
            return this;
        }

        public ConfigBuilder gameVersionTypeIds(Collection<Integer> typeIds) {
            this.gameVersionTypeIds = copyIntegers(typeIds);
            return this;
        }

        public ConfigBuilder releaseTypes(Collection<String> releaseTypes) {
            if (releaseTypes == null || releaseTypes.isEmpty()) {
                this.allowedReleaseTypes = EnumSet.of(ReleaseType.RELEASE);
            } else {
                EnumSet<ReleaseType> parsed = EnumSet.noneOf(ReleaseType.class);
                for (String value : releaseTypes) {
                    ReleaseType releaseType = ReleaseType.fromConfig(value);
                    if (releaseType != null) {
                        parsed.add(releaseType);
                    }
                }
                if (parsed.isEmpty()) {
                    parsed.add(ReleaseType.RELEASE);
                }
                this.allowedReleaseTypes = parsed;
            }
            return this;
        }

        public ConfigBuilder releaseTypesEnum(Collection<ReleaseType> releaseTypes) {
            if (releaseTypes == null || releaseTypes.isEmpty()) {
                this.allowedReleaseTypes = EnumSet.of(ReleaseType.RELEASE);
            } else {
                this.allowedReleaseTypes = EnumSet.copyOf(releaseTypes);
            }
            return this;
        }

        public ConfigBuilder installedPlugin(String pluginName) {
            this.installedPluginName = pluginName;
            return this;
        }

        public Config build() {
            if (allowedReleaseTypes == null || allowedReleaseTypes.isEmpty()) {
                allowedReleaseTypes = EnumSet.of(ReleaseType.RELEASE);
            }
            if (pageSize <= 0) {
                pageSize = 50;
            }
            if (maxPages <= 0) {
                maxPages = 1;
            }
            return new Config(this);
        }
    }

    private static Set<String> copyGameVersions(Collection<String> versions) {
        if (versions == null || versions.isEmpty()) {
            return Set.of();
        }
        Set<String> normalized = new LinkedHashSet<>();
        for (String version : versions) {
            String normalizedVersion = normalizeGameVersion(version);
            if (normalizedVersion != null) {
                normalized.add(normalizedVersion);
            }
        }
        return normalized.isEmpty() ? Set.of() : Set.copyOf(normalized);
    }

    private static Set<Integer> copyIntegers(Collection<Integer> values) {
        if (values == null || values.isEmpty()) {
            return Set.of();
        }
        Set<Integer> result = new LinkedHashSet<>();
        for (Integer value : values) {
            if (value != null && value > 0) {
                result.add(value);
            }
        }
        return result.isEmpty() ? Set.of() : Set.copyOf(result);
    }

    private enum ReleaseType {
        RELEASE(1, 3, "release"),
        BETA(2, 2, "beta"),
        ALPHA(3, 1, "alpha");

        private final int id;
        private final int priority;
        private final String configName;

        ReleaseType(int id, int priority, String configName) {
            this.id = id;
            this.priority = priority;
            this.configName = configName;
        }

        public int id() {
            return id;
        }

        public int priority() {
            return priority;
        }

        public static ReleaseType fromId(int id) {
            for (ReleaseType type : values()) {
                if (type.id == id) {
                    return type;
                }
            }
            return null;
        }

        public static ReleaseType fromConfig(String value) {
            if (value == null) {
                return null;
            }
            String normalized = value.trim().toLowerCase(Locale.ROOT);
            for (ReleaseType type : values()) {
                if (type.configName.equals(normalized) || Integer.toString(type.id).equals(normalized)) {
                    return type;
                }
            }
            return null;
        }
    }

    private record FilesResponse(List<CurseforgeFile> data, Pagination pagination) {
    }

    private record Pagination(int index, int pageSize, int resultCount, int totalCount) {
    }

    private record CurseforgeFile(@JsonProperty("id") long id,
                                  @JsonProperty("displayName") String displayName,
                                  @JsonProperty("fileName") String fileName,
                                  @JsonProperty("releaseType") int releaseType,
                                  @JsonProperty("fileStatus") int fileStatus,
                                  @JsonProperty("downloadUrl") String downloadUrl,
                                  @JsonProperty("fileDate") Instant fileDate,
                                  @JsonProperty("gameVersions") List<String> gameVersions,
                                  @JsonProperty("sortableGameVersions") List<SortableGameVersion> sortableGameVersions) {
    }

    private record SortableGameVersion(@JsonProperty("gameVersionTypeId") Integer gameVersionTypeId) {
    }
}
