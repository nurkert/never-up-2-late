package eu.nurkert.neverUp2Late.fetcher;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import eu.nurkert.neverUp2Late.handlers.ArtifactDownloader;
import eu.nurkert.neverUp2Late.net.HttpClient;
import eu.nurkert.neverUp2Late.update.UpdateContext;

import org.bukkit.Bukkit;
import org.bukkit.plugin.Plugin;
import org.bukkit.plugin.PluginManager;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * Base class for update fetchers that communicate with JSON based HTTP APIs.
 */
public abstract class JsonUpdateFetcher implements UpdateFetcher {

    private static final Pattern NUMERIC_PATTERN = Pattern.compile("\\d+");
    private static final Pattern BUILD_NUMBER_PATTERN = Pattern.compile("-b(\\d+)$", Pattern.CASE_INSENSITIVE);
    private static final Pattern HEX_PATTERN = Pattern.compile("[0-9a-fA-F]+");

    private final HttpClient httpClient;
    private final ObjectMapper objectMapper;

    private String latestVersion;
    private int latestBuild;
    private String latestDownloadUrl;
    private Checksum latestChecksum;
    private String latestReleasePageUrl;

    protected JsonUpdateFetcher() {
        this(new HttpClient(), defaultMapper());
    }

    protected JsonUpdateFetcher(HttpClient httpClient) {
        this(httpClient, defaultMapper());
    }

    protected JsonUpdateFetcher(HttpClient httpClient, ObjectMapper objectMapper) {
        this.httpClient = Objects.requireNonNull(httpClient, "httpClient");
        this.objectMapper = Objects.requireNonNull(objectMapper, "objectMapper");
    }

    private static ObjectMapper defaultMapper() {
        ObjectMapper mapper = new ObjectMapper();
        mapper.registerModule(new JavaTimeModule());
        mapper.disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES);
        mapper.disable(DeserializationFeature.ADJUST_DATES_TO_CONTEXT_TIME_ZONE);
        return mapper;
    }

    protected <T> T getJson(String url, Class<T> type) throws IOException {
        String body = httpClient.get(url);
        try {
            return objectMapper.readValue(body, type);
        } catch (JsonProcessingException e) {
            throw new IOException("Failed to parse response from " + url, e);
        }
    }

    protected <T> T getJson(String url, TypeReference<T> type) throws IOException {
        String body = httpClient.get(url);
        try {
            return objectMapper.readValue(body, type);
        } catch (JsonProcessingException e) {
            throw new IOException("Failed to parse response from " + url, e);
        }
    }

    protected List<String> filterStableVersions(Collection<String> versions) {
        return versions.stream()
                .filter(version -> !version.contains("-"))
                .collect(Collectors.toCollection(ArrayList::new));
    }

    protected Optional<String> findLatestVersion(Collection<String> versions) {
        Comparator<String> comparator = semanticVersionComparator();
        return versions.stream().max(comparator);
    }

    protected String requireLatestVersion(Collection<String> versions) throws IOException {
        return findLatestVersion(versions)
                .orElseThrow(() -> new IOException("No versions available"));
    }

    protected Comparator<String> semanticVersionComparator() {
        return (left, right) -> {
            List<Integer> leftNumbers = extractNumbers(left);
            List<Integer> rightNumbers = extractNumbers(right);
            int max = Math.max(leftNumbers.size(), rightNumbers.size());
            for (int i = 0; i < max; i++) {
                int leftValue = i < leftNumbers.size() ? leftNumbers.get(i) : 0;
                int rightValue = i < rightNumbers.size() ? rightNumbers.get(i) : 0;
                if (leftValue != rightValue) {
                    return Integer.compare(leftValue, rightValue);
                }
            }
            return Integer.compare(leftNumbers.size(), rightNumbers.size());
        };
    }

    private List<Integer> extractNumbers(String value) {
        Matcher matcher = NUMERIC_PATTERN.matcher(value);
        List<Integer> numbers = new ArrayList<>();
        while (matcher.find()) {
            numbers.add(Integer.parseInt(matcher.group()));
        }
        return numbers;
    }

    protected int selectLatestBuild(Collection<Integer> builds) throws IOException {
        return builds.stream()
                .max(Integer::compareTo)
                .orElseThrow(() -> new IOException("No builds available"));
    }

    protected OptionalInt extractBuildNumber(String versionNumber) {
        Matcher matcher = BUILD_NUMBER_PATTERN.matcher(versionNumber);
        if (matcher.find()) {
            return OptionalInt.of(Integer.parseInt(matcher.group(1)));
        }
        return OptionalInt.empty();
    }

    /**
     * Reads the version a plugin currently reports.
     *
     * <p>This runs on the update thread while the server thread may be loading
     * or unloading plugins, and Bukkit's registry is a plain map. A hiccup there
     * must not take the whole update run down: an unknown version simply means
     * the decision falls back to the version recorded on disk.</p>
     */
    protected static String installedVersionOf(String pluginName) {
        if (pluginName == null || pluginName.isBlank()) {
            return null;
        }
        try {
            PluginManager pluginManager = Bukkit.getPluginManager();
            if (pluginManager == null) {
                return null;
            }
            Plugin plugin = pluginManager.getPlugin(pluginName);
            return plugin == null ? null : plugin.getDescription().getVersion();
        } catch (RuntimeException ex) {
            return null;
        }
    }

    protected void setLatestBuildInfo(String version, int build, String downloadUrl) {
        this.latestVersion = version;
        this.latestBuild = build;
        this.latestDownloadUrl = downloadUrl;
        // A checksum belongs to exactly one download; never let it outlive it.
        this.latestChecksum = null;
        this.latestReleasePageUrl = null;
    }

    /**
     * Records where the release set by the last {@link #setLatestBuildInfo}
     * call is described, so an operator can read what changed.
     */
    protected void setLatestReleasePage(String url) {
        this.latestReleasePageUrl = url == null || url.isBlank() ? null : url.trim();
    }

    @Override
    public Optional<String> getLatestReleasePageUrl() {
        return Optional.ofNullable(latestReleasePageUrl);
    }

    /**
     * Records the checksum the provider publishes for the download set by the
     * last {@link #setLatestBuildInfo} call, so the downloaded file is verified
     * before it replaces anything on disk.
     *
     * <p>A value that is not plausible hex of the expected length is dropped
     * rather than enforced: a provider changing its format must not turn into
     * every update failing, it only costs the extra check.</p>
     *
     * @param algorithm a {@link java.security.MessageDigest} name, e.g. {@code SHA-256}
     */
    protected void setLatestChecksum(String algorithm, String hex) {
        this.latestChecksum = Checksum.of(algorithm, hex).orElse(null);
    }

    /**
     * @return the checksum published for the latest download, if the provider offers one
     */
    public Optional<Checksum> getLatestChecksum() {
        return Optional.ofNullable(latestChecksum);
    }

    @Override
    public void configureContext(UpdateContext context) {
        context.setChecksumValidator(latestChecksum == null
                ? null
                : ArtifactDownloader.ChecksumValidator.fromHexChecksum(latestChecksum.algorithm(), latestChecksum.hex()));
    }

    /**
     * A checksum as published by a provider.
     *
     * @param algorithm a {@link java.security.MessageDigest} name
     * @param hex       the expected digest, lower case hex
     */
    public record Checksum(String algorithm, String hex) {

        static Optional<Checksum> of(String algorithm, String hex) {
            if (algorithm == null || hex == null) {
                return Optional.empty();
            }
            String normalized = hex.trim().toLowerCase(Locale.ROOT);
            int expectedLength = switch (algorithm) {
                case "SHA-1" -> 40;
                case "SHA-256" -> 64;
                case "SHA-512" -> 128;
                default -> -1;
            };
            if (normalized.length() != expectedLength || !HEX_PATTERN.matcher(normalized).matches()) {
                return Optional.empty();
            }
            return Optional.of(new Checksum(algorithm, normalized));
        }
    }

    @Override
    public String getLatestVersion() {
        return latestVersion;
    }

    @Override
    public int getLatestBuild() {
        return latestBuild;
    }

    @Override
    public String getLatestDownloadUrl() {
        return latestDownloadUrl;
    }
}
