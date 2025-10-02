package eu.nurkert.neverUp2Late.fetcher;

import com.fasterxml.jackson.annotation.JsonProperty;
import eu.nurkert.neverUp2Late.net.HttpClient;
import eu.nurkert.neverUp2Late.net.HttpException;
import org.bukkit.Bukkit;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.plugin.Plugin;
import org.bukkit.plugin.PluginManager;

import java.io.IOException;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * Fetcher for Jenkins jobs using the JSON REST API.
 */
public class JenkinsFetcher extends JsonUpdateFetcher {

    private static final String BUILD_QUERY = "?tree=number,url,displayName,id,result,artifacts[fileName,relativePath]";

    private final String jobBaseUrl;
    private final boolean preferLastSuccessful;
    private final String artifactName;
    private final Pattern artifactPattern;
    private final VersionSource versionSource;
    private final Pattern versionPattern;
    private final String installedPluginName;

    public JenkinsFetcher(ConfigurationSection options) {
        this(options, new HttpClient());
    }

    JenkinsFetcher(ConfigurationSection options, HttpClient httpClient) {
        super(httpClient);
        Objects.requireNonNull(options, "options");

        String baseUrl = requireOption(options, "baseUrl");
        String job = requireOption(options, "job");
        this.jobBaseUrl = buildJobBaseUrl(baseUrl, job);

        String artifact = trimToNull(options.getString("artifact"));
        String artifactPatternValue = trimToNull(options.getString("artifactPattern"));
        if (artifact != null && artifactPatternValue != null) {
            throw new IllegalArgumentException("Configure either artifact or artifactPattern, not both");
        }
        this.artifactName = artifact;
        this.artifactPattern = artifactPatternValue != null ? Pattern.compile(artifactPatternValue) : null;

        this.preferLastSuccessful = options.getBoolean("preferLastSuccessful", true);

        String versionSourceValue = trimToNull(options.getString("versionSource"));
        this.versionSource = VersionSource.from(versionSourceValue);

        String versionPatternValue = trimToNull(options.getString("versionPattern"));
        this.versionPattern = versionPatternValue != null ? Pattern.compile(versionPatternValue) : null;

        this.installedPluginName = trimToNull(options.getString("installedPlugin"));
    }

    @Override
    public void loadLatestBuildInfo() throws Exception {
        Build build = fetchPreferredBuild();
        if (build == null) {
            throw new IOException("No build information available for " + jobBaseUrl);
        }

        Artifact artifact = selectArtifact(build.artifacts());
        if (artifact == null) {
            throw new IOException("No matching artifact found for build " + build.number());
        }

        String downloadUrl = buildDownloadUrl(build, artifact);
        String version = determineVersion(build, artifact);
        setLatestBuildInfo(version, build.number(), downloadUrl);
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

    private Build fetchPreferredBuild() throws IOException {
        List<String> selectors = preferLastSuccessful
                ? List.of("lastSuccessfulBuild", "lastStableBuild", "lastCompletedBuild", "lastBuild")
                : List.of("lastBuild", "lastCompletedBuild", "lastSuccessfulBuild", "lastStableBuild");

        for (String selector : selectors) {
            Build build = fetchBuild(selector);
            if (build != null) {
                return build;
            }
        }
        return null;
    }

    private Build fetchBuild(String selector) throws IOException {
        String url = jobBaseUrl + selector + "/api/json" + BUILD_QUERY;
        try {
            return getJson(url, Build.class);
        } catch (IOException e) {
            if (e instanceof HttpException http && http.getStatusCode() == 404) {
                return null;
            }
            throw e;
        }
    }

    private Artifact selectArtifact(List<Artifact> artifacts) throws IOException {
        if (artifacts == null || artifacts.isEmpty()) {
            throw new IOException("No artifacts published by the Jenkins build");
        }

        if (artifactName != null) {
            return artifacts.stream()
                    .filter(artifact -> artifactName.equals(artifact.fileName()))
                    .findFirst()
                    .orElseThrow(() -> new IOException("Artifact " + artifactName + " not found"));
        }

        if (artifactPattern != null) {
            return artifacts.stream()
                    .filter(artifact -> artifactPattern.matcher(artifact.fileName()).find())
                    .findFirst()
                    .orElseThrow(() -> new IOException(
                            "No artifact matching pattern " + artifactPattern.pattern()));
        }

        if (artifacts.size() == 1) {
            return artifacts.get(0);
        }

        throw new IOException("Multiple artifacts found; specify artifact or artifactPattern");
    }

    private String buildDownloadUrl(Build build, Artifact artifact) {
        String buildUrl = trimToNull(build.url());
        if (buildUrl == null) {
            throw new IllegalStateException("Build URL not provided by Jenkins response");
        }
        String relativePath = encodePath(artifact.relativePath());
        return ensureTrailingSlash(buildUrl) + "artifact/" + relativePath;
    }

    private String determineVersion(Build build, Artifact artifact) {
        String candidate = switch (versionSource) {
            case DISPLAY_NAME -> trimToNull(build.displayName());
            case BUILD_ID -> trimToNull(build.id());
            case BUILD_NUMBER -> Integer.toString(build.number());
            case ARTIFACT_FILE -> trimToNull(artifact.fileName());
        };

        if (candidate == null) {
            candidate = trimToNull(artifact.fileName());
        }
        if (candidate == null) {
            candidate = Integer.toString(build.number());
        }

        return applyVersionPattern(candidate);
    }

    private String applyVersionPattern(String candidate) {
        if (versionPattern == null) {
            return candidate;
        }
        Matcher matcher = versionPattern.matcher(candidate);
        if (!matcher.find()) {
            return candidate;
        }
        if (matcher.groupCount() >= 1) {
            for (int i = 1; i <= matcher.groupCount(); i++) {
                String group = matcher.group(i);
                if (group != null && !group.isBlank()) {
                    return group.trim();
                }
            }
        }
        return matcher.group().trim();
    }

    private static String buildJobBaseUrl(String baseUrl, String job) {
        String normalizedBase = ensureTrailingSlash(trimToNull(baseUrl));
        if (normalizedBase == null) {
            throw new IllegalArgumentException("baseUrl must not be blank");
        }
        List<String> segments = Arrays.stream(job.split("/"))
                .map(JenkinsFetcher::trimToNull)
                .filter(Objects::nonNull)
                .collect(Collectors.toList());
        if (segments.isEmpty()) {
            throw new IllegalArgumentException("job must not be blank");
        }
        String jobPath = segments.stream()
                .map(segment -> "job/" + encodePathSegment(segment))
                .collect(Collectors.joining("/"));
        return normalizedBase + ensureTrailingSlash(jobPath);
    }

    private static String encodePath(String path) {
        return Arrays.stream(path.split("/"))
                .map(JenkinsFetcher::encodePathSegment)
                .collect(Collectors.joining("/"));
    }

    private static String encodePathSegment(String segment) {
        String encoded = URLEncoder.encode(segment, StandardCharsets.UTF_8);
        return encoded.replace("+", "%20");
    }

    private static String ensureTrailingSlash(String value) {
        if (value.endsWith("/")) {
            return value;
        }
        return value + "/";
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

    private enum VersionSource {
        DISPLAY_NAME,
        BUILD_ID,
        BUILD_NUMBER,
        ARTIFACT_FILE;

        static VersionSource from(String value) {
            if (value == null) {
                return DISPLAY_NAME;
            }
            return switch (value.trim().toLowerCase()) {
                case "displayname", "display_name" -> DISPLAY_NAME;
                case "id", "buildid", "build_id" -> BUILD_ID;
                case "number", "buildnumber", "build_number" -> BUILD_NUMBER;
                case "artifact", "artifactfile", "artifact_file" -> ARTIFACT_FILE;
                default -> DISPLAY_NAME;
            };
        }
    }

    private record Build(
            @JsonProperty("number") int number,
            @JsonProperty("url") String url,
            @JsonProperty("displayName") String displayName,
            @JsonProperty("id") String id,
            @JsonProperty("result") String result,
            @JsonProperty("artifacts") List<Artifact> artifacts
    ) {
        private Build {
            artifacts = artifacts == null ? List.of() : List.copyOf(artifacts);
        }
    }

    private record Artifact(
            @JsonProperty("fileName") String fileName,
            @JsonProperty("relativePath") String relativePath
    ) {
    }
}
