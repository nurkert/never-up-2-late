package eu.nurkert.neverUp2Late.fetcher;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import eu.nurkert.neverUp2Late.net.HttpClient;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.List;
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

    private final HttpClient httpClient;
    private final ObjectMapper objectMapper;

    private String latestVersion;
    private int latestBuild;
    private String latestDownloadUrl;

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

    protected void setLatestBuildInfo(String version, int build, String downloadUrl) {
        this.latestVersion = version;
        this.latestBuild = build;
        this.latestDownloadUrl = downloadUrl;
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
