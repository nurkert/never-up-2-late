package eu.nurkert.neverUp2Late.command;

import eu.nurkert.neverUp2Late.command.QuickInstallCoordinator.InstallationPlan;
import eu.nurkert.neverUp2Late.net.HttpClient;
import eu.nurkert.neverUp2Late.update.UpdateSourceRegistry.TargetDirectory;
import eu.nurkert.neverUp2Late.util.FileNameSanitizer;
import org.bukkit.Bukkit;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.plugin.Plugin;
import org.bukkit.plugin.PluginDescriptionFile;
import org.bukkit.plugin.PluginManager;

import java.io.IOException;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalLong;
import java.util.Set;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Turns a pasted link into an {@link InstallationPlan}.
 *
 * <p>Each supported provider (Hangar, Modrinth, CurseForge, SpigotMC, GitHub,
 * Jenkins) has its own URL layout; the parsing helpers here pull the project
 * identity out of it, pick a default file name and look for an installed
 * plugin the plan should be linked with. Nothing in here touches the source
 * registry - that is left to the coordinator once the plan is complete.</p>
 */
final class InstallationPlanner {

    private static final Set<String> MODRINTH_SEGMENT_PREFIXES = Set.of(
            "plugin",
            "plugins",
            "project",
            "projects",
            "mod",
            "mods",
            "modpack",
            "modpacks",
            "datapack",
            "datapacks",
            "resourcepack",
            "resourcepacks",
            "shaderpack",
            "shaderpacks",
            "shader",
            "shaders"
    );

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

    private final QuickInstallCoordinator coordinator;
    private final FileConfiguration configuration;
    private final HttpClient httpClient;
    private final Logger logger;
    private final boolean ignoreCompatibilityWarnings;

    InstallationPlanner(QuickInstallCoordinator coordinator,
                        FileConfiguration configuration,
                        HttpClient httpClient,
                        Logger logger,
                        boolean ignoreCompatibilityWarnings) {
        this.coordinator = coordinator;
        this.configuration = configuration;
        this.httpClient = httpClient;
        this.logger = logger;
        this.ignoreCompatibilityWarnings = ignoreCompatibilityWarnings;
    }

    Optional<String> detectInstalledPlugin(InstallationPlan plan) {
        PluginManager pluginManager = Bukkit.getPluginManager();
        if (pluginManager == null) {
            return Optional.empty();
        }

        Set<String> candidates = new LinkedHashSet<>();
        candidates.add(plan.getDisplayName());
        candidates.addAll(plan.getPluginNameCandidates());
        plan.getHost().ifPresent(candidates::add);

        Map<String, Plugin> plugins = new LinkedHashMap<>();
        for (Plugin plugin : pluginManager.getPlugins()) {
            plugins.put(normalize(plugin.getName()), plugin);
        }

        for (String candidate : candidates) {
            String normalizedCandidate = normalize(candidate);
            Plugin match = plugins.get(normalizedCandidate);
            if (match != null) {
                return Optional.of(match.getName());
            }
        }

        // A substring match used to answer here, in both directions, so a short
        // installed plugin name matched almost any candidate. What was matched
        // becomes the source's "installedPlugin", and that name decides which
        // jar the download lands on - so a wrong guess writes one plugin over
        // another. Only an unambiguous signal is allowed to answer now; a plan
        // without an installed plugin is treated as a new install, which is the
        // harmless reading of "we do not know".
        for (String candidate : candidates) {
            String normalizedCandidate = normalize(candidate);
            if (normalizedCandidate.isBlank()) {
                continue;
            }
            List<Plugin> viaWebsite = new ArrayList<>();
            for (Map.Entry<String, Plugin> entry : plugins.entrySet()) {
                PluginDescriptionFile description = entry.getValue().getDescription();
                String website = description.getWebsite();
                if (website != null && plan.getHost().isPresent()
                        && websiteMatchesCandidate(website, plan.getHost().get(), normalizedCandidate)) {
                    viaWebsite.add(entry.getValue());
                }
            }
            if (viaWebsite.size() == 1) {
                return Optional.of(viaWebsite.get(0).getName());
            }
            if (viaWebsite.size() > 1) {
                logger.log(Level.INFO,
                        "Several installed plugins point at {0}; not guessing which one {1} updates.",
                        new Object[]{plan.getHost().orElse("the same site"), plan.getDisplayName()});
            }
        }

        return Optional.empty();
    }

    private boolean websiteMatchesCandidate(String website, String expectedHost, String normalizedCandidate) {
        if (normalizedCandidate == null || normalizedCandidate.isBlank()) {
            return false;
        }

        String trimmedHost = trimWww(expectedHost);
        if (trimmedHost.isEmpty()) {
            return false;
        }

        try {
            URI uri = new URI(website);
            String host = trimWww(Optional.ofNullable(uri.getHost()).orElse(""));
            if (!host.equalsIgnoreCase(trimmedHost)) {
                return false;
            }
            String path = normalize(uri.getPath());
            return !path.isBlank() && path.contains(normalizedCandidate);
        } catch (URISyntaxException ignored) {
            String normalizedWebsite = normalize(website);
            return normalizedWebsite.contains(normalizedCandidate)
                    && normalize(trimmedHost).equals(normalize(extractHostFallback(website)));
        }
    }

    private String trimWww(String host) {
        if (host == null) {
            return "";
        }
        String value = host.trim();
        if (value.regionMatches(true, 0, "www.", 0, 4)) {
            return value.substring(4);
        }
        return value;
    }

    private String extractHostFallback(String website) {
        int schemeSeparator = website.indexOf("//");
        int start = schemeSeparator >= 0 ? schemeSeparator + 2 : 0;
        int slash = website.indexOf('/', start);
        String host = slash >= 0 ? website.substring(start, slash) : website.substring(start);
        return trimWww(host);
    }

    InstallationPlan analyse(URI uri, String originalUrl) {
        String host = Optional.ofNullable(uri.getHost()).map(String::toLowerCase).orElse("");
        if (host.contains("hangar.papermc")) {
            return buildHangarPlan(uri, originalUrl, host);
        }
        if (host.contains("modrinth.com")) {
            return buildModrinthPlan(uri, originalUrl, host);
        }
        if (host.contains("spigotmc.org") || host.contains("api.spiget.org")) {
            return buildSpigotPlan(uri, originalUrl, host);
        }
        if (host.contains("github.com")) {
            return buildGithubPlan(uri, originalUrl, host);
        }
        if (host.contains("curseforge.com")) {
            return buildCurseforgePlan(uri, originalUrl, host);
        }
        if (uri.getPath() != null && uri.getPath().toLowerCase(Locale.ROOT).contains("/job/")) {
            return buildJenkinsPlan(uri, originalUrl, host);
        }
        throw new IllegalArgumentException("This URL is not currently supported.");
    }

    private InstallationPlan buildHangarPlan(URI uri, String originalUrl, String host) {
        List<String> segments = pathSegments(uri.getPath());
        OwnerSlug ownerSlug = extractOwnerAndSlug(segments, List.of("plugins", "plugin", "project"))
                .orElseThrow(() -> new IllegalArgumentException("Hangar URL must follow the /Owner/Project format."));
        String owner = ownerSlug.owner();
        String slug = ownerSlug.slug();
        Map<String, Object> options = new LinkedHashMap<>();
        options.put("project", owner + "/" + slug);
        options.put("platform", "PAPER");
        applyCompatibilityPreference(options);

        String defaultFilename = resolveDefaultFilename(slug, owner + "-" + slug);

        InstallationPlan plan = coordinator.new InstallationPlan(
                originalUrl,
                "Hangar",
                host,
                "hangar",
                slug,
                toDisplayName(slug),
                TargetDirectory.PLUGINS,
                options,
                defaultFilename
        );
        plan.addPluginNameCandidate(owner);
        plan.addPluginNameCandidate(slug);
        plan.addPluginNameCandidate(toDisplayName(slug));
        return plan;
    }

    private InstallationPlan buildModrinthPlan(URI uri, String originalUrl, String host) {
        List<String> segments = pathSegments(uri.getPath());
        String slug = extractModrinthSlug(segments)
                .orElseThrow(() -> new IllegalArgumentException("Could not parse Modrinth URL."));

        Map<String, Object> options = new LinkedHashMap<>();
        options.put("project", slug);
        options.put("loaders", List.of("paper", "spigot"));
        applyCompatibilityPreference(options);

        String defaultFilename = resolveDefaultFilename(slug);

        InstallationPlan plan = coordinator.new InstallationPlan(
                originalUrl,
                "Modrinth",
                host,
                "modrinth",
                slug,
                toDisplayName(slug),
                TargetDirectory.PLUGINS,
                options,
                defaultFilename
        );
        plan.addPluginNameCandidate(slug);
        plan.addPluginNameCandidate(toDisplayName(slug));
        return plan;
    }

    private InstallationPlan buildCurseforgePlan(URI uri, String originalUrl, String host) {
        List<String> segments = pathSegments(uri.getPath());
        CurseforgeProjectPath projectPath = extractCurseforgeProject(segments)
                .orElseThrow(() -> new IllegalArgumentException("Could not parse CurseForge URL."));

        String slug = projectPath.slug();
        OptionalLong projectId;
        try {
            projectId = fetchCurseforgeProjectId(uri, segments, projectPath.slugIndex());
        } catch (IOException e) {
            throw new IllegalArgumentException("Failed to query CurseForge: " + e.getMessage(), e);
        }

        if (projectId.isEmpty()) {
            throw new IllegalArgumentException("Could not determine the CurseForge project ID from the provided URL.");
        }

        Map<String, Object> options = new LinkedHashMap<>();
        options.put("modId", projectId.getAsLong());
        applyCompatibilityPreference(options);

        String defaultFilename = resolveDefaultFilename(slug);

        InstallationPlan plan = coordinator.new InstallationPlan(
                originalUrl,
                "CurseForge",
                host,
                "curseforge",
                slug,
                toDisplayName(slug),
                TargetDirectory.PLUGINS,
                options,
                defaultFilename
        );
        plan.addPluginNameCandidate(slug);
        plan.addPluginNameCandidate(toDisplayName(slug));
        return plan;
    }

    private InstallationPlan buildSpigotPlan(URI uri, String originalUrl, String host) {
        List<String> segments = pathSegments(uri.getPath());
        SpigotResource resource = extractSpigotResource(segments)
                .orElseThrow(() -> new IllegalArgumentException("Could not parse SpigotMC URL."));

        long resourceId = resource.resourceId();
        String slug = resource.slug();

        Map<String, Object> options = new LinkedHashMap<>();
        options.put("resourceId", resourceId);
        applyCompatibilityPreference(options);

        String defaultFilename = resolveDefaultFilename(slug, slug + "-" + resourceId, "resource-" + resourceId);

        String providerLabel = host != null && host.contains("api.spiget.org") ? "Spiget API" : "SpigotMC";

        InstallationPlan plan = coordinator.new InstallationPlan(
                originalUrl,
                providerLabel,
                host,
                "spigot",
                slug,
                toDisplayName(slug),
                TargetDirectory.PLUGINS,
                options,
                defaultFilename
        );
        plan.addPluginNameCandidate(slug);
        plan.addPluginNameCandidate(toDisplayName(slug));
        if (!slug.equalsIgnoreCase("resource-" + resourceId)) {
            plan.addPluginNameCandidate(slug.replace('-', ' '));
        }
        plan.addPluginNameCandidate(String.valueOf(resourceId));
        return plan;
    }

    private OptionalLong fetchCurseforgeProjectId(URI uri, List<String> segments, int slugIndex) throws IOException {
        String projectUrl = buildCurseforgeProjectUrl(uri, segments, slugIndex);
        try {
            String body = httpClient.get(projectUrl);
            OptionalLong projectId = extractCurseforgeProjectId(body);
            if (projectId.isEmpty()) {
                logger.log(Level.FINE, "CurseForge response from {0} did not include a project id.", projectUrl);
            }
            return projectId;
        } catch (IOException e) {
            logger.log(Level.FINE, "Failed to download CurseForge project page " + projectUrl, e);
            throw e;
        }
    }

    private String buildCurseforgeProjectUrl(URI uri, List<String> segments, int slugIndex) {
        if (segments == null || slugIndex < 0 || slugIndex >= segments.size()) {
            throw new IllegalArgumentException("Invalid CurseForge URL structure.");
        }
        String authority = uri.getAuthority();
        if (authority == null || authority.isBlank()) {
            throw new IllegalArgumentException("CurseForge URL must include a host.");
        }
        String scheme = Optional.ofNullable(uri.getScheme()).orElse("https");
        StringBuilder pathBuilder = new StringBuilder();
        for (int i = 0; i <= slugIndex; i++) {
            pathBuilder.append('/').append(segments.get(i));
        }
        return scheme + "://" + authority + pathBuilder;
    }

    private OptionalLong extractCurseforgeProjectId(String html) {
        if (html == null || html.isBlank()) {
            return OptionalLong.empty();
        }
        Pattern[] patterns = {CURSEFORGE_DATA_PROJECT_ID_PATTERN, CURSEFORGE_SPAN_PROJECT_ID_PATTERN};
        for (Pattern pattern : patterns) {
            Matcher matcher = pattern.matcher(html);
            if (!matcher.find()) {
                continue;
            }
            String value = matcher.group(1);
            if (value == null || value.isBlank()) {
                continue;
            }
            try {
                long id = Long.parseLong(value.trim());
                if (id > 0) {
                    return OptionalLong.of(id);
                }
            } catch (NumberFormatException ignored) {
                // Ignore malformed matches and continue with the next pattern
            }
        }
        return OptionalLong.empty();
    }

    static Optional<String> extractModrinthSlug(List<String> segments) {
        String slug = null;
        boolean expectSlug = false;

        for (String rawSegment : segments) {
            String segment = decode(rawSegment);
            if (segment.isBlank()) {
                continue;
            }

            String normalized = segment.toLowerCase(Locale.ROOT);

            if (expectSlug) {
                slug = segment;
                break;
            }

            if (MODRINTH_SEGMENT_PREFIXES.contains(normalized)) {
                expectSlug = true;
                continue;
            }

            if (slug == null) {
                slug = segment;
                break;
            }
        }

        return Optional.ofNullable(trimToNull(slug));
    }

    static Optional<SpigotResource> extractSpigotResource(List<String> segments) {
        if (segments == null || segments.isEmpty()) {
            return Optional.empty();
        }

        Long resourceId = null;
        String slug = null;

        for (int i = 0; i < segments.size(); i++) {
            String decoded = decode(segments.get(i));
            String trimmed = trimToNull(decoded);
            if (trimmed == null) {
                continue;
            }

            String normalized = trimmed.toLowerCase(Locale.ROOT);
            if ("resources".equals(normalized) || "resource".equals(normalized)) {
                for (int j = i + 1; j < segments.size(); j++) {
                    SpigotResource parsed = parseSpigotResourceSegment(segments.get(j));
                    if (parsed != null) {
                        resourceId = parsed.resourceId();
                        slug = parsed.slug();
                        break;
                    }
                }
                if (resourceId != null) {
                    break;
                }
                continue;
            }

            if (resourceId == null) {
                SpigotResource parsed = parseSpigotResourceSegment(segments.get(i));
                if (parsed != null) {
                    resourceId = parsed.resourceId();
                    slug = parsed.slug();
                    break;
                }
            }
        }

        if (resourceId == null) {
            return Optional.empty();
        }

        String finalSlug = slug;
        if (finalSlug == null || finalSlug.isBlank()) {
            finalSlug = "resource-" + resourceId;
        }

        return Optional.of(new SpigotResource(resourceId, finalSlug));
    }

    private static SpigotResource parseSpigotResourceSegment(String rawSegment) {
        String decoded = decode(rawSegment);
        String candidate = trimToNull(decoded);
        if (candidate == null) {
            return null;
        }

        int dotIndex = candidate.lastIndexOf('.');
        if (dotIndex > 0 && dotIndex + 1 < candidate.length()) {
            String idPart = candidate.substring(dotIndex + 1);
            if (isNumeric(idPart)) {
                long id = Long.parseLong(idPart);
                String slugPart = trimToNull(candidate.substring(0, dotIndex));
                return new SpigotResource(id, slugPart);
            }
        }

        if (isNumeric(candidate)) {
            long id = Long.parseLong(candidate);
            return new SpigotResource(id, null);
        }

        return null;
    }

    static Optional<CurseforgeProjectPath> extractCurseforgeProject(List<String> segments) {
        if (segments == null || segments.isEmpty()) {
            return Optional.empty();
        }

        int index = segments.size() - 1;
        while (index >= 0) {
            String decoded = decode(segments.get(index));
            String trimmed = decoded.trim();
            if (trimmed.isEmpty()) {
                index--;
                continue;
            }

            String normalized = trimmed.toLowerCase(Locale.ROOT);
            if (CURSEFORGE_TRAILING_SEGMENTS.contains(normalized)) {
                index--;
                continue;
            }

            if (CURSEFORGE_CATEGORY_SEGMENTS.contains(normalized)) {
                index--;
                continue;
            }

            if (isNumeric(normalized)) {
                if (index > 0) {
                    String previous = decode(segments.get(index - 1)).trim().toLowerCase(Locale.ROOT);
                    if (CURSEFORGE_TRAILING_SEGMENTS.contains(previous)) {
                        index -= 2;
                        continue;
                    }
                }
                index--;
                continue;
            }

            String slug = trimToNull(trimmed);
            if (slug == null) {
                index--;
                continue;
            }
            return Optional.of(new CurseforgeProjectPath(index, slug));
        }

        return Optional.empty();
    }

    static Optional<OwnerSlug> extractOwnerAndSlug(List<String> segments, Collection<String> segmentsToIgnore) {
        if (segments == null || segments.isEmpty()) {
            return Optional.empty();
        }

        Set<String> ignore = new HashSet<>();
        if (segmentsToIgnore != null) {
            for (String segment : segmentsToIgnore) {
                if (segment != null && !segment.isBlank()) {
                    ignore.add(segment.toLowerCase(Locale.ROOT));
                }
            }
        }

        String owner = null;
        String slug = null;

        for (String rawSegment : segments) {
            String segment = decode(rawSegment);
            if (segment.isBlank()) {
                continue;
            }

            String normalized = segment.toLowerCase(Locale.ROOT);
            if (ignore.contains(normalized)) {
                continue;
            }

            if (owner == null) {
                owner = segment;
                continue;
            }

            slug = segment;
            break;
        }

        owner = trimToNull(owner);
        slug = trimToNull(slug);
        if (owner == null || slug == null) {
            return Optional.empty();
        }

        return Optional.of(new OwnerSlug(owner, slug));
    }

    private InstallationPlan buildGithubPlan(URI uri, String originalUrl, String host) {
        List<String> segments = pathSegments(uri.getPath());
        OwnerSlug ownerSlug = extractOwnerAndSlug(segments, List.of("repos", "projects", "users", "orgs"))
                .orElseThrow(() -> new IllegalArgumentException("GitHub URL must follow the /Owner/Repository format."));
        String owner = ownerSlug.owner();
        String repository = ownerSlug.slug();

        Map<String, Object> options = new LinkedHashMap<>();
        options.put("owner", owner);
        options.put("repository", repository);
        applyCompatibilityPreference(options);
        String defaultFilename = resolveDefaultFilename(repository, owner + "-" + repository);

        InstallationPlan plan = coordinator.new InstallationPlan(
                originalUrl,
                "GitHub Releases",
                host,
                "githubRelease",
                repository,
                toDisplayName(repository),
                TargetDirectory.PLUGINS,
                options,
                defaultFilename
        );
        plan.addPluginNameCandidate(repository);
        plan.addPluginNameCandidate(toDisplayName(repository));
        plan.addPluginNameCandidate(owner + " " + repository);
        return plan;
    }

    private InstallationPlan buildJenkinsPlan(URI uri, String originalUrl, String host) {
        List<String> segments = pathSegments(uri.getPath());
        List<String> jobSegments = new ArrayList<>();
        for (int i = 0; i < segments.size(); i++) {
            if ("job".equalsIgnoreCase(segments.get(i)) && i + 1 < segments.size()) {
                jobSegments.add(decode(segments.get(++i)));
            }
        }
        if (jobSegments.isEmpty()) {
            throw new IllegalArgumentException("Jenkins URL must contain /job/<name>.");
        }

        String jobPath = String.join("/", jobSegments);
        String baseUrl = buildJenkinsBaseUrl(uri, segments);

        Map<String, Object> options = new LinkedHashMap<>();
        options.put("baseUrl", baseUrl);
        options.put("job", jobPath);
        options.put("artifactPattern", "(?i).*\\.jar$");
        applyCompatibilityPreference(options);

        String displayName = jobSegments.get(jobSegments.size() - 1);
        String providerLabel = (host == null || host.isBlank()) ? "Jenkins" : host;
        String defaultFilename = resolveDefaultFilename(displayName, jobPath);

        InstallationPlan plan = coordinator.new InstallationPlan(
                originalUrl,
                providerLabel,
                host,
                "jenkins",
                sanitizeKey(displayName),
                displayName,
                TargetDirectory.PLUGINS,
                options,
                defaultFilename
        );
        plan.addPluginNameCandidate(displayName);
        plan.addPluginNameCandidate(displayName.replace("-", " "));
        if (displayName.contains("-")) {
            plan.addPluginNameCandidate(displayName.substring(0, displayName.indexOf('-')).trim());
        }
        return plan;
    }

    private void applyCompatibilityPreference(Map<String, Object> options) {
        if (ignoreCompatibilityWarnings && options != null) {
            options.put("ignoreCompatibilityWarnings", true);
        }
    }

    private String buildJenkinsBaseUrl(URI uri, List<String> segments) {
        String scheme = Optional.ofNullable(uri.getScheme()).orElse("https");
        StringBuilder base = new StringBuilder();
        base.append(scheme).append("://").append(uri.getHost());
        if (uri.getPort() > 0) {
            base.append(":").append(uri.getPort());
        }
        int firstJob = -1;
        for (int i = 0; i < segments.size(); i++) {
            if ("job".equalsIgnoreCase(segments.get(i))) {
                firstJob = i;
                break;
            }
        }
        StringBuilder path = new StringBuilder();
        for (int i = 0; i < segments.size(); i++) {
            if (i == firstJob) {
                break;
            }
            path.append('/').append(segments.get(i));
        }
        if (path.length() == 0) {
            path.append('/');
        }
        if (path.charAt(path.length() - 1) != '/') {
            path.append('/');
        }
        base.append(path);
        return base.toString();
    }

    String determineFilename(String downloadUrl, String fallback) {
        String sanitizedFallback = fallback != null && !fallback.isBlank() ? fallback : null;
        try {
            URI uri = new URI(downloadUrl);
            String path = Optional.ofNullable(uri.getPath()).orElse("");
            int lastSlash = path.lastIndexOf('/');
            String candidate = lastSlash >= 0 ? path.substring(lastSlash + 1) : path;
            candidate = candidate.split("\\?")[0];

            // First guess: the Content-Disposition header, when the path has no usable name
            if (candidate.isBlank() || candidate.equalsIgnoreCase("download")) {
                String cdName = tryResolveContentDisposition(downloadUrl);
                if (cdName != null && !cdName.isBlank()) {
                    candidate = cdName;
                }
            }

            // Second guess: a host specific default
            if (candidate.isBlank() || candidate.equalsIgnoreCase("download")) {
                candidate = sanitizedFallback != null ? sanitizedFallback : null;
            }
            if (candidate == null || candidate.isBlank()) {
                candidate = "plugin-" + sanitizeKey(Optional.ofNullable(uri.getHost()).orElse("source")) + ".jar";
            }

            // The candidate can come straight from a remote Content-Disposition
            // header, so it is attacker controlled. Sanitising turns any path
            // separator into a dash and appends the .jar extension, which keeps
            // the result a plain file name inside the target directory.
            String safe = FileNameSanitizer.sanitizeJarFilename(candidate);
            return safe != null ? safe : "plugin-source.jar";
        } catch (Exception e) {
            logger.log(Level.FINE, "Failed to parse filename from " + downloadUrl, e);
            if (sanitizedFallback != null && !sanitizedFallback.isBlank()) {
                return sanitizedFallback.toLowerCase(Locale.ROOT).endsWith(".jar")
                        ? sanitizedFallback
                        : sanitizedFallback + ".jar";
            }
            return "plugin-source.jar";
        }
    }

    private String tryResolveContentDisposition(String url) {
        try {
            java.net.http.HttpClient client = eu.nurkert.neverUp2Late.net.HttpClient.sharedClient();
            java.net.http.HttpRequest request = java.net.http.HttpRequest.newBuilder()
                    .method("HEAD", java.net.http.HttpRequest.BodyPublishers.noBody())
                    .uri(URI.create(url))
                    .timeout(java.time.Duration.ofSeconds(5))
                    .build();
            java.net.http.HttpResponse<Void> response = client.send(request, java.net.http.HttpResponse.BodyHandlers.discarding());
            String disposition = response.headers().firstValue("Content-Disposition").orElse(null);
            if (disposition == null || disposition.isBlank()) {
                return null;
            }
            // Simple parsing for filename="..."
            int idx = disposition.toLowerCase(Locale.ROOT).indexOf("filename=");
            if (idx < 0) {
                return null;
            }
            String part = disposition.substring(idx + "filename=".length()).trim();
            part = part.replaceAll("^[\\\"']|[\\\"']$", "");
            if (part.isBlank()) {
                return null;
            }
            return part;
        } catch (Exception ignored) {
            return null;
        }
    }

    static String trimToNull(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }

    private String resolveDefaultFilename(String... candidates) {
        if (candidates == null || candidates.length == 0) {
            return null;
        }

        Set<String> keys = new LinkedHashSet<>();
        for (String candidate : candidates) {
            if (candidate == null || candidate.isBlank()) {
                continue;
            }
            keys.add(candidate);
            keys.add(candidate.toLowerCase(Locale.ROOT));
            keys.add(sanitizeKey(candidate));
        }

        for (String key : keys) {
            if (key == null || key.isBlank()) {
                continue;
            }
            String value = configuration.getString("filenames." + key);
            if (value != null && !value.isBlank()) {
                return value;
            }
        }

        return null;
    }

    private List<String> pathSegments(String path) {
        List<String> segments = new ArrayList<>();
        if (path == null || path.isEmpty()) {
            return segments;
        }
        for (String segment : path.split("/")) {
            if (!segment.isBlank()) {
                segments.add(segment);
            }
        }
        return segments;
    }

    private static String decode(String value) {
        return URLDecoder.decode(value, StandardCharsets.UTF_8);
    }

    private static boolean isNumeric(String value) {
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

    static String sanitizeKey(String value) {
        String normalized = value.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]+", "-");
        normalized = normalized.replaceAll("-+", "-");
        normalized = trimDashes(normalized);
        return normalized.isBlank() ? "source" : normalized;
    }

    private static String trimDashes(String value) {
        int start = 0;
        int end = value.length();
        while (start < end && value.charAt(start) == '-') {
            start++;
        }
        while (end > start && value.charAt(end - 1) == '-') {
            end--;
        }
        return value.substring(start, end);
    }

    private String toDisplayName(String slug) {
        if (slug == null || slug.isBlank()) {
            return "Plugin";
        }
        String[] parts = slug.replace('-', ' ').replace('_', ' ').split("\\s+");
        StringBuilder builder = new StringBuilder();
        for (String part : parts) {
            if (part.isBlank()) {
                continue;
            }
            builder.append(Character.toUpperCase(part.charAt(0)));
            if (part.length() > 1) {
                builder.append(part.substring(1));
            }
            builder.append(' ');
        }
        return builder.toString().trim();
    }

    private String normalize(String value) {
        return value == null ? "" : value.replaceAll("[^a-zA-Z0-9]", "").toLowerCase(Locale.ROOT);
    }

    static final class CurseforgeProjectPath {
        private final int slugIndex;
        private final String slug;

        CurseforgeProjectPath(int slugIndex, String slug) {
            this.slugIndex = slugIndex;
            this.slug = slug;
        }

        int slugIndex() {
            return slugIndex;
        }

        String slug() {
            return slug;
        }
    }

    static final class OwnerSlug {
        private final String owner;
        private final String slug;

        OwnerSlug(String owner, String slug) {
            this.owner = owner;
            this.slug = slug;
        }

        String owner() {
            return owner;
        }

        String slug() {
            return slug;
        }
    }

    static final class SpigotResource {
        private final long resourceId;
        private final String slug;

        SpigotResource(long resourceId, String slug) {
            this.resourceId = resourceId;
            this.slug = slug;
        }

        long resourceId() {
            return resourceId;
        }

        String slug() {
            return slug;
        }
    }
}
