package eu.nurkert.neverUp2Late.update.suggestion;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import eu.nurkert.neverUp2Late.net.HttpClient;

import java.io.IOException;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.text.NumberFormat;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Queries known plugin hosting platforms for potential update sources.
 */
public class PluginLinkSuggester {

    private static final String MODRINTH_SEARCH_TEMPLATE =
            "https://api.modrinth.com/v2/search?limit=5&index=relevance&query=%s";
    private static final String HANGAR_SEARCH_TEMPLATE =
            "https://hangar.papermc.io/api/v1/projects?limit=5&query=%s";
    private static final String SPIGOT_SEARCH_TEMPLATE =
            "https://api.spiget.org/v2/search/resources/%s?size=5";
    private static final int MAX_RESULTS_PER_PROVIDER = 5;

    private final HttpClient httpClient;
    private final ObjectMapper objectMapper;
    private final Logger logger;

    public PluginLinkSuggester(Logger logger) {
        this(new HttpClient(), createMapper(), logger);
    }

    public PluginLinkSuggester(HttpClient httpClient, ObjectMapper objectMapper, Logger logger) {
        this.httpClient = Objects.requireNonNull(httpClient, "httpClient");
        this.objectMapper = Objects.requireNonNull(objectMapper, "objectMapper");
        this.logger = Objects.requireNonNull(logger, "logger");
    }

    public List<PluginLinkSuggestion> suggest(Collection<String> searchTerms) {
        if (searchTerms == null || searchTerms.isEmpty()) {
            return List.of();
        }

        Set<String> normalizedTerms = new LinkedHashSet<>();
        for (String term : searchTerms) {
            if (term == null) {
                continue;
            }
            String trimmed = term.trim();
            if (trimmed.isEmpty()) {
                continue;
            }
            normalizedTerms.add(trimmed);
        }

        if (normalizedTerms.isEmpty()) {
            return List.of();
        }

        List<PluginLinkSuggestion> suggestions = new ArrayList<>();
        suggestions.addAll(fetchModrinthSuggestions(normalizedTerms));
        suggestions.addAll(fetchHangarSuggestions(normalizedTerms));
        suggestions.addAll(fetchSpigotSuggestions(normalizedTerms));
        return suggestions;
    }

    private List<PluginLinkSuggestion> fetchModrinthSuggestions(Collection<String> searchTerms) {
        List<PluginLinkSuggestion> suggestions = new ArrayList<>();
        Set<String> seen = new LinkedHashSet<>();
        for (String term : searchTerms) {
            if (suggestions.size() >= MAX_RESULTS_PER_PROVIDER) {
                break;
            }
            String url = MODRINTH_SEARCH_TEMPLATE.formatted(encode(term));
            try {
                String body = httpClient.get(url);
                ModrinthSearchResponse response = objectMapper.readValue(body, ModrinthSearchResponse.class);
                if (response.hits() == null) {
                    continue;
                }
                for (ModrinthProject hit : response.hits()) {
                    if (hit.slug() == null || !matches(term, hit.slug(), hit.title())) {
                        continue;
                    }
                    if (!seen.add(hit.slug().toLowerCase(Locale.ROOT))) {
                        continue;
                    }
                    suggestions.add(hit.toSuggestion());
                    if (suggestions.size() >= MAX_RESULTS_PER_PROVIDER) {
                        break;
                    }
                }
            } catch (IOException e) {
                logger.log(Level.FINE, "Failed to query Modrinth for term " + term, e);
            }
        }
        return suggestions;
    }

    private List<PluginLinkSuggestion> fetchHangarSuggestions(Collection<String> searchTerms) {
        List<PluginLinkSuggestion> suggestions = new ArrayList<>();
        Set<String> seen = new LinkedHashSet<>();
        for (String term : searchTerms) {
            if (suggestions.size() >= MAX_RESULTS_PER_PROVIDER) {
                break;
            }
            String url = HANGAR_SEARCH_TEMPLATE.formatted(encode(term));
            try {
                String body = httpClient.get(url);
                HangarSearchResponse response = objectMapper.readValue(body, HangarSearchResponse.class);
                if (response.result() == null) {
                    continue;
                }
                for (HangarProject project : response.result()) {
                    if (!project.isPublic() || project.namespace() == null) {
                        continue;
                    }
                    String slug = project.namespace().slug();
                    if (slug == null || !matches(term, slug, project.name())) {
                        continue;
                    }
                    String key = project.namespace().owner() + '/' + slug;
                    if (!seen.add(key.toLowerCase(Locale.ROOT))) {
                        continue;
                    }
                    suggestions.add(project.toSuggestion());
                    if (suggestions.size() >= MAX_RESULTS_PER_PROVIDER) {
                        break;
                    }
                }
            } catch (IOException e) {
                logger.log(Level.FINE, "Failed to query Hangar for term " + term, e);
            }
        }
        return suggestions;
    }

    private List<PluginLinkSuggestion> fetchSpigotSuggestions(Collection<String> searchTerms) {
        List<PluginLinkSuggestion> suggestions = new ArrayList<>();
        Set<Long> seen = new LinkedHashSet<>();
        for (String term : searchTerms) {
            if (suggestions.size() >= MAX_RESULTS_PER_PROVIDER) {
                break;
            }
            String url = SPIGOT_SEARCH_TEMPLATE.formatted(encode(term));
            try {
                SpigotResource[] resources = objectMapper.readValue(httpClient.get(url), SpigotResource[].class);
                if (resources == null || resources.length == 0) {
                    continue;
                }
                for (SpigotResource resource : resources) {
                    if (resource == null || resource.id() == null) {
                        continue;
                    }
                    if (!matches(term, resource.name(), resource.tag())) {
                        continue;
                    }
                    if (!seen.add(resource.id())) {
                        continue;
                    }
                    suggestions.add(resource.toSuggestion());
                    if (suggestions.size() >= MAX_RESULTS_PER_PROVIDER) {
                        break;
                    }
                }
            } catch (IOException e) {
                logger.log(Level.FINE, "Failed to query SpigotMC for term " + term, e);
            }
        }
        return suggestions;
    }

    private boolean matches(String term, String slug, String title) {
        String normalizedTerm = normalize(term);
        if (normalizedTerm.isEmpty()) {
            return true;
        }
        if (slug != null) {
            String normalizedSlug = normalize(slug);
            if (normalizedSlug.contains(normalizedTerm) || normalizedTerm.contains(normalizedSlug)) {
                return true;
            }
        }
        if (title != null) {
            String normalizedTitle = normalize(title);
            if (normalizedTitle.contains(normalizedTerm) || normalizedTerm.contains(normalizedTitle)) {
                return true;
            }
        }
        return false;
    }

    private String normalize(String value) {
        if (value == null) {
            return "";
        }
        StringBuilder builder = new StringBuilder(value.length());
        for (char c : value.toLowerCase(Locale.ROOT).toCharArray()) {
            if (Character.isLetterOrDigit(c)) {
                builder.append(c);
            }
        }
        return builder.toString();
    }

    private String encode(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8);
    }

    private static ObjectMapper createMapper() {
        ObjectMapper mapper = new ObjectMapper();
        mapper.configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);
        return mapper;
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    private record ModrinthSearchResponse(List<ModrinthProject> hits) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    private record ModrinthProject(String slug,
                                   String title,
                                   String description,
                                   String author,
                                   @JsonProperty("downloads") long downloads,
                                   @JsonProperty("date_modified") String dateModified,
                                   @JsonProperty("server_side") String serverSide,
                                   @JsonProperty("client_side") String clientSide) {

        PluginLinkSuggestion toSuggestion() {
            String link = "https://modrinth.com/plugin/" + slug;
            List<String> highlights = new ArrayList<>();
            if (author != null && !author.isBlank()) {
                highlights.add("Author: " + author);
            }
            if (downloads > 0) {
                highlights.add("Downloads: " + NumberFormat.getInstance(Locale.GERMAN).format(downloads));
            }
            if (serverSide != null && !serverSide.isBlank()) {
                highlights.add("Server: " + serverSide);
            }
            if (clientSide != null && !clientSide.isBlank() && !clientSide.equalsIgnoreCase(serverSide)) {
                highlights.add("Client: " + clientSide);
            }
            if (dateModified != null) {
                parseDate(dateModified).ifPresent(instant ->
                        highlights.add("Aktualisiert: " + instant.atZone(java.time.ZoneId.systemDefault()).toLocalDate()));
            }
            return new PluginLinkSuggestion("Modrinth", titleOrSlug(), link, description, List.copyOf(highlights));
        }

        private String titleOrSlug() {
            return (title == null || title.isBlank()) ? slug : title;
        }
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    private record HangarSearchResponse(List<HangarProject> result) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    private record HangarProject(String name,
                                 String description,
                                 Namespace namespace,
                                 Stats stats,
                                 @JsonProperty("supportedPlatforms") Map<String, List<String>> supportedPlatforms,
                                 @JsonProperty("lastUpdated") String lastUpdated,
                                 @JsonProperty("visibility") String visibility) {

        PluginLinkSuggestion toSuggestion() {
            Namespace ns = Objects.requireNonNull(namespace, "namespace");
            String link = "https://hangar.papermc.io/" + ns.owner() + '/' + ns.slug();
            List<String> highlights = new ArrayList<>();
            if (stats != null && stats.downloads > 0) {
                highlights.add("Downloads: " + NumberFormat.getInstance(Locale.GERMAN).format(stats.downloads));
            }
            if (supportedPlatforms != null && !supportedPlatforms.isEmpty()) {
                List<String> platforms = supportedPlatforms.keySet().stream()
                        .sorted()
                        .toList();
                highlights.add("Plattformen: " + String.join(", ", platforms));
            }
            if (lastUpdated != null) {
                parseDate(lastUpdated).ifPresent(instant ->
                        highlights.add("Aktualisiert: " + instant.atZone(java.time.ZoneId.systemDefault()).toLocalDate()));
            }
            String title = (name == null || name.isBlank()) ? ns.slug() : name;
            return new PluginLinkSuggestion("Hangar", title, link, description, List.copyOf(highlights));
        }

        boolean isPublic() {
            return visibility == null || visibility.equalsIgnoreCase("public");
        }
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    private record SpigotResource(Long id,
                                  String name,
                                  String tag,
                                  @JsonProperty("testedVersions") List<String> testedVersions,
                                  SpigotFile file,
                                  SpigotRating rating,
                                  @JsonProperty("downloads") long downloads) {

        PluginLinkSuggestion toSuggestion() {
            List<String> highlights = new ArrayList<>();
            if (testedVersions != null && !testedVersions.isEmpty()) {
                highlights.add("Getestet: " + String.join(", ", testedVersions));
            }
            if (downloads > 0) {
                highlights.add("Downloads: " + NumberFormat.getInstance(Locale.GERMAN).format(downloads));
            }
            if (rating != null && rating.average() > 0) {
                NumberFormat decimalFormat = NumberFormat.getNumberInstance(Locale.GERMAN);
                decimalFormat.setMinimumFractionDigits(1);
                decimalFormat.setMaximumFractionDigits(1);
                String formattedAverage = decimalFormat.format(rating.average());
                String ratingHighlight = "Bewertung: " + formattedAverage;
                if (rating.count() > 0) {
                    ratingHighlight += " (" + NumberFormat.getInstance(Locale.GERMAN).format(rating.count()) + ')';
                }
                highlights.add(ratingHighlight);
            }
            downloadLink().ifPresent(link -> highlights.add("Download: " + link));
            String description = (tag == null || tag.isBlank()) ? null : tag;
            return new PluginLinkSuggestion("SpigotMC", title(), resourceLink(), description, List.copyOf(highlights));
        }

        private String title() {
            if (name != null && !name.isBlank()) {
                return name;
            }
            return "Resource " + (id == null ? "" : id);
        }

        private String resourceLink() {
            if (file != null && file.url() != null && !file.url().isBlank()) {
                String path = file.url();
                int downloadIndex = path.indexOf("/download");
                if (downloadIndex > 0) {
                    path = path.substring(0, downloadIndex);
                } else {
                    int queryIndex = path.indexOf('?');
                    if (queryIndex > 0) {
                        path = path.substring(0, queryIndex);
                    }
                }
                if (!path.endsWith("/")) {
                    path = path + '/';
                }
                return "https://www.spigotmc.org/" + path;
            }
            if (id != null && id > 0) {
                return "https://www.spigotmc.org/resources/" + id + '/';
            }
            return "https://www.spigotmc.org/resources";
        }

        private Optional<String> downloadLink() {
            if (file == null) {
                return Optional.empty();
            }
            if (file.externalUrl() != null && !file.externalUrl().isBlank()) {
                return Optional.of(file.externalUrl());
            }
            if (file.url() != null && !file.url().isBlank()) {
                return Optional.of("https://www.spigotmc.org/" + file.url());
            }
            return Optional.empty();
        }
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    private record SpigotFile(String url, String externalUrl) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    private record SpigotRating(double average, long count) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    private record Namespace(String owner, String slug) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    private record Stats(@JsonProperty("downloads") long downloads) {
    }

    private static Optional<Instant> parseDate(String value) {
        try {
            return value == null || value.isBlank() ? Optional.empty() : Optional.of(Instant.parse(value));
        } catch (DateTimeParseException ignored) {
            return Optional.empty();
        }
    }
}
