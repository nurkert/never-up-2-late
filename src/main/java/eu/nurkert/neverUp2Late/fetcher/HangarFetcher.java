package eu.nurkert.neverUp2Late.fetcher;

import com.fasterxml.jackson.annotation.JsonProperty;
import eu.nurkert.neverUp2Late.net.HttpClient;
import org.bukkit.Bukkit;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.plugin.Plugin;
import org.bukkit.plugin.PluginManager;

import java.io.IOException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.OptionalInt;
import java.util.Set;

/**
 * Fetcher for projects hosted on <a href="https://hangar.papermc.io/">Hangar</a>.
 */
public class HangarFetcher extends JsonUpdateFetcher {

    private static final String API_TEMPLATE = "https://hangar.papermc.io/api/v1/projects/%s/%s/versions?limit=%d&offset=%d";

    private final Config config;

    public HangarFetcher(ConfigurationSection options) {
        this(Config.fromConfiguration(options));
    }

    public HangarFetcher(Config config) {
        this(config, HttpClient.builder()
                .accept("application/json")
                .build());
    }

    HangarFetcher(Config config, HttpClient httpClient) {
        super(httpClient);
        this.config = Objects.requireNonNull(config, "config");
    }

    @Override
    public void loadLatestBuildInfo() throws Exception {
        Comparator<VersionResponse> comparator = buildComparator();
        VersionResponse best = null;

        int offset = 0;
        int pagesFetched = 0;
        while (pagesFetched < config.maxPages()) {
            VersionsResponse response = getJson(String.format(API_TEMPLATE,
                    config.owner(),
                    config.slug(),
                    config.pageSize(),
                    offset),
                    VersionsResponse.class);

            List<VersionResponse> versions = response.result();
            if (versions.isEmpty()) {
                break;
            }

            for (VersionResponse version : versions) {
                if (!isEligible(version)) {
                    continue;
                }
                if (best == null || comparator.compare(version, best) > 0) {
                    best = version;
                }
            }

            Pagination pagination = response.pagination();
            if (pagination == null) {
                break;
            }

            offset += Math.max(pagination.limit(), config.pageSize());
            pagesFetched++;

            if (offset >= pagination.count()) {
                break;
            }
        }

        if (best == null) {
            throw new IOException("No suitable Hangar version found for " + config.owner() + "/" + config.slug());
        }

        PlatformDownload download = best.downloads().get(config.platform());
        if (download == null) {
            throw new IOException("No downloads for platform " + config.platform() + " in version " + best.name());
        }

        String downloadUrl = resolveDownloadUrl(download);
        int buildNumber = resolveBuildNumber(best);

        setLatestBuildInfo(best.name(), buildNumber, downloadUrl);
    }

    @Override
    public String getInstalledVersion() {
        String installedPluginName = config.installedPluginName();
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

    private Comparator<VersionResponse> buildComparator() {
        return Comparator
                .comparingInt(this::pinnedPriority)
                .thenComparing(VersionResponse::createdAt, Comparator.nullsLast(Comparator.naturalOrder()))
                .thenComparing(VersionResponse::name, semanticVersionComparator());
    }

    private int pinnedPriority(VersionResponse version) {
        if (!config.preferPinned()) {
            return 0;
        }
        return PinnedStatus.from(version.pinnedStatus()).priority();
    }

    private boolean isEligible(VersionResponse version) {
        if (!"public".equalsIgnoreCase(trimToNull(version.visibility()))) {
            return false;
        }
        if (config.requireReviewed() && !"reviewed".equalsIgnoreCase(trimToNull(version.reviewState()))) {
            return false;
        }

        Channel channel = version.channel();
        if (!config.allowedChannels().isEmpty()) {
            String channelName = channel != null ? trimToNull(channel.name()) : null;
            if (channelName == null || !config.allowedChannels().contains(channelName.toLowerCase(Locale.ROOT))) {
                return false;
            }
        }

        if (config.ignoreUnstable() && channel != null) {
            for (String flag : channel.flags()) {
                if ("UNSTABLE".equalsIgnoreCase(flag)) {
                    return false;
                }
            }
        }

        PlatformDownload download = version.downloads().get(config.platform());
        if (download == null) {
            return false;
        }

        return trimToNull(download.downloadUrl()) != null || trimToNull(download.externalUrl()) != null;
    }

    private String resolveDownloadUrl(PlatformDownload download) throws IOException {
        String downloadUrl = trimToNull(download.downloadUrl());
        if (downloadUrl != null) {
            return downloadUrl;
        }

        downloadUrl = trimToNull(download.externalUrl());
        if (downloadUrl != null) {
            return downloadUrl;
        }

        throw new IOException("No download URL available for platform " + config.platform());
    }

    private int resolveBuildNumber(VersionResponse version) {
        OptionalInt buildNumber = extractBuildNumber(version.name());
        if (buildNumber.isPresent()) {
            return buildNumber.getAsInt();
        }

        long id = version.id();
        if (id >= 0 && id <= Integer.MAX_VALUE) {
            return (int) id;
        }

        int hash = Objects.hash(version.id(), version.name());
        if (hash == Integer.MIN_VALUE) {
            return Integer.MAX_VALUE;
        }
        return Math.abs(hash);
    }

    private static String trimToNull(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }

    private static Map<String, PlatformDownload> copyDownloads(Map<String, PlatformDownload> downloads) {
        if (downloads == null || downloads.isEmpty()) {
            return Map.of();
        }
        Map<String, PlatformDownload> copy = new HashMap<>();
        for (Map.Entry<String, PlatformDownload> entry : downloads.entrySet()) {
            String key = entry.getKey();
            PlatformDownload value = entry.getValue();
            if (key != null && value != null) {
                copy.put(key, value);
            }
        }
        return Map.copyOf(copy);
    }

    private static List<String> copyFlags(Collection<String> flags) {
        if (flags == null || flags.isEmpty()) {
            return List.of();
        }
        List<String> copy = new ArrayList<>();
        for (String flag : flags) {
            if (flag != null) {
                copy.add(flag);
            }
        }
        return List.copyOf(copy);
    }

    private static List<VersionResponse> copyVersions(Collection<VersionResponse> versions) {
        if (versions == null || versions.isEmpty()) {
            return List.of();
        }
        return List.copyOf(versions);
    }

    public static ConfigBuilder builder(String owner, String slug) {
        return new ConfigBuilder(owner, slug);
    }

    public static final class Config {
        private final String owner;
        private final String slug;
        private final String platform;
        private final Set<String> allowedChannels;
        private final boolean ignoreUnstable;
        private final boolean requireReviewed;
        private final boolean preferPinned;
        private final int pageSize;
        private final int maxPages;
        private final String installedPluginName;

        private Config(ConfigBuilder builder) {
            this.owner = Objects.requireNonNull(trimToNull(builder.owner), "owner");
            this.slug = Objects.requireNonNull(trimToNull(builder.slug), "slug");
            this.platform = Objects.requireNonNull(trimToNull(builder.platform), "platform");
            this.allowedChannels = builder.allowedChannels;
            this.ignoreUnstable = builder.ignoreUnstable;
            this.requireReviewed = builder.requireReviewed;
            this.preferPinned = builder.preferPinned;
            this.pageSize = builder.pageSize;
            this.maxPages = builder.maxPages;
            this.installedPluginName = trimToNull(builder.installedPluginName);
        }

        public static Config fromConfiguration(ConfigurationSection options) {
            Objects.requireNonNull(options, "options");

            String owner = trimToNull(options.getString("owner"));
            String slug = trimToNull(options.getString("slug"));
            String project = trimToNull(options.getString("project"));

            if (project != null) {
                int separator = project.indexOf('/');
                if (separator > 0 && separator < project.length() - 1) {
                    owner = trimToNull(project.substring(0, separator));
                    slug = trimToNull(project.substring(separator + 1));
                }
            }

            if (owner == null || slug == null) {
                throw new IllegalArgumentException("Hangar fetcher requires 'owner' and 'slug' (or combined 'project')");
            }

            ConfigBuilder builder = builder(owner, slug);
            builder.platform(options.getString("platform"));
            if (options.contains("allowedChannels")) {
                builder.allowedChannels(options.getStringList("allowedChannels"));
            } else if (options.contains("channel")) {
                builder.allowedChannels(List.of(options.getString("channel")));
            }
            if (options.contains("ignoreUnstable")) {
                builder.ignoreUnstable(options.getBoolean("ignoreUnstable"));
            } else if (options.contains("allowUnstable")) {
                builder.ignoreUnstable(!options.getBoolean("allowUnstable"));
            }
            builder.requireReviewed(options.getBoolean("requireReviewed", true));
            builder.preferPinned(options.getBoolean("preferPinned", true));
            builder.pageSize(options.getInt("pageSize", builder.pageSize));
            builder.maxPages(options.getInt("maxPages", builder.maxPages));
            builder.installedPlugin(options.getString("installedPlugin"));
            return builder.build();
        }

        public String owner() {
            return owner;
        }

        public String slug() {
            return slug;
        }

        public String platform() {
            return platform;
        }

        public Set<String> allowedChannels() {
            return allowedChannels;
        }

        public boolean ignoreUnstable() {
            return ignoreUnstable;
        }

        public boolean requireReviewed() {
            return requireReviewed;
        }

        public boolean preferPinned() {
            return preferPinned;
        }

        public int pageSize() {
            return pageSize;
        }

        public int maxPages() {
            return maxPages;
        }

        public String installedPluginName() {
            return installedPluginName;
        }
    }

    public static final class ConfigBuilder {
        private final String owner;
        private final String slug;
        private String platform = "PAPER";
        private Set<String> allowedChannels = Set.of();
        private boolean ignoreUnstable = true;
        private boolean requireReviewed = true;
        private boolean preferPinned = true;
        private int pageSize = 25;
        private int maxPages = 4;
        private String installedPluginName;

        private ConfigBuilder(String owner, String slug) {
            this.owner = owner;
            this.slug = slug;
        }

        public ConfigBuilder platform(String platform) {
            String normalized = trimToNull(platform);
            if (normalized != null) {
                this.platform = normalized.toUpperCase(Locale.ROOT);
            }
            return this;
        }

        public ConfigBuilder allowedChannels(Collection<String> channels) {
            if (channels == null || channels.isEmpty()) {
                this.allowedChannels = Set.of();
                return this;
            }

            List<String> normalized = new ArrayList<>();
            for (String channel : channels) {
                String trimmed = trimToNull(channel);
                if (trimmed != null) {
                    normalized.add(trimmed.toLowerCase(Locale.ROOT));
                }
            }
            this.allowedChannels = Set.copyOf(normalized);
            return this;
        }

        public ConfigBuilder ignoreUnstable(boolean ignoreUnstable) {
            this.ignoreUnstable = ignoreUnstable;
            return this;
        }

        public ConfigBuilder requireReviewed(boolean requireReviewed) {
            this.requireReviewed = requireReviewed;
            return this;
        }

        public ConfigBuilder preferPinned(boolean preferPinned) {
            this.preferPinned = preferPinned;
            return this;
        }

        public ConfigBuilder pageSize(int pageSize) {
            this.pageSize = Math.max(1, pageSize);
            return this;
        }

        public ConfigBuilder maxPages(int maxPages) {
            this.maxPages = Math.max(1, maxPages);
            return this;
        }

        public ConfigBuilder installedPlugin(String installedPluginName) {
            this.installedPluginName = installedPluginName;
            return this;
        }

        public Config build() {
            return new Config(this);
        }
    }

    private enum PinnedStatus {
        NONE(0),
        VERSION(1),
        CHANNEL(2),
        PROJECT(3),
        GLOBAL(4);

        private final int priority;

        PinnedStatus(int priority) {
            this.priority = priority;
        }

        public int priority() {
            return priority;
        }

        public static PinnedStatus from(String value) {
            if (value == null) {
                return NONE;
            }
            try {
                return PinnedStatus.valueOf(value.toUpperCase(Locale.ROOT));
            } catch (IllegalArgumentException ex) {
                return NONE;
            }
        }
    }

    private record VersionsResponse(@JsonProperty("pagination") Pagination pagination,
                                    @JsonProperty("result") List<VersionResponse> result) {
        private VersionsResponse {
            result = copyVersions(result);
        }
    }

    private record Pagination(@JsonProperty("count") int count,
                              @JsonProperty("limit") int limit,
                              @JsonProperty("offset") int offset) {
    }

    private record VersionResponse(@JsonProperty("createdAt") Instant createdAt,
                                   @JsonProperty("id") long id,
                                   @JsonProperty("name") String name,
                                   @JsonProperty("visibility") String visibility,
                                   @JsonProperty("reviewState") String reviewState,
                                   @JsonProperty("channel") Channel channel,
                                   @JsonProperty("pinnedStatus") String pinnedStatus,
                                   @JsonProperty("downloads") Map<String, PlatformDownload> downloads) {
        private VersionResponse {
            downloads = copyDownloads(downloads);
        }
    }

    private record Channel(@JsonProperty("name") String name,
                           @JsonProperty("flags") List<String> flags) {
        private Channel {
            flags = copyFlags(flags);
        }
    }

    private record PlatformDownload(@JsonProperty("fileInfo") FileInfo fileInfo,
                                     @JsonProperty("externalUrl") String externalUrl,
                                     @JsonProperty("downloadUrl") String downloadUrl) {
    }

    private record FileInfo(@JsonProperty("name") String name,
                            @JsonProperty("sizeBytes") long sizeBytes,
                            @JsonProperty("sha256Hash") String sha256Hash) {
    }
}
