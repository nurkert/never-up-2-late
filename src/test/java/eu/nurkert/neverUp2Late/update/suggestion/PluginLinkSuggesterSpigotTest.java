package eu.nurkert.neverUp2Late.update.suggestion;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import eu.nurkert.neverUp2Late.net.HttpClient;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PluginLinkSuggesterSpigotTest {

    private static final String MODRINTH_SEARCH_TEMPLATE =
            "https://api.modrinth.com/v2/search?limit=5&index=relevance&query=%s";
    private static final String HANGAR_SEARCH_TEMPLATE =
            "https://hangar.papermc.io/api/v1/projects?limit=5&query=%s";
    private static final String SPIGOT_SEARCH_TEMPLATE =
            "https://api.spiget.org/v2/search/resources/%s?size=5";

    private StubHttpClient httpClient;
    private PluginLinkSuggester suggester;

    @BeforeEach
    void setUp() {
        httpClient = new StubHttpClient();
        ObjectMapper mapper = new ObjectMapper();
        mapper.configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);
        suggester = new PluginLinkSuggester(httpClient, mapper, Logger.getAnonymousLogger());
    }

    @Test
    void shouldParseSpigotResponse() {
        String term = "TestPlugin";
        stubEmptyOtherProviders(term);
        httpClient.when(spigotUrl(term), """
                [
                  {
                    "id": 123,
                    "name": "TestPlugin",
                    "tag": "Powerful helper",
                    "testedVersions": ["1.19", "1.20"],
                    "file": {
                      "url": "resources/testplugin.123/download?version=456"
                    },
                    "rating": {
                      "count": 12,
                      "average": 4.5
                    },
                    "downloads": 1234
                  }
                ]
                """);

        List<PluginLinkSuggestion> suggestions = suggester.suggest(List.of(term));

        assertEquals(1, suggestions.size());
        PluginLinkSuggestion suggestion = suggestions.get(0);
        assertEquals("SpigotMC", suggestion.provider());
        assertEquals("TestPlugin", suggestion.title());
        assertEquals("https://www.spigotmc.org/resources/testplugin.123/", suggestion.url());
        assertEquals("Powerful helper", suggestion.description());
        assertTrue(suggestion.highlights().contains("Getestet: 1.19, 1.20"));
        assertTrue(suggestion.highlights().contains("Downloads: 1.234"));
        assertTrue(suggestion.highlights().stream().anyMatch(value -> value.startsWith("Bewertung: 4,5")));
        assertTrue(suggestion.highlights().contains(
                "Download: https://www.spigotmc.org/resources/testplugin.123/download?version=456"));
    }

    @Test
    void shouldDeduplicateResourcesById() {
        String termOne = "Alpha";
        String termTwo = "Beta";
        stubEmptyOtherProviders(termOne);
        stubEmptyOtherProviders(termTwo);

        httpClient.when(spigotUrl(termOne), """
                [
                  {"id": 1, "name": "First", "tag": "", "downloads": 10},
                  {"id": 2, "name": "Second", "tag": "", "downloads": 5}
                ]
                """);
        httpClient.when(spigotUrl(termTwo), """
                [
                  {"id": 2, "name": "Second", "tag": "", "downloads": 7},
                  {"id": 3, "name": "Third", "tag": "", "downloads": 2}
                ]
                """);

        List<PluginLinkSuggestion> suggestions = suggester.suggest(List.of(termOne, termTwo));

        assertEquals(3, suggestions.size());
        assertEquals(List.of("First", "Second", "Third"),
                suggestions.stream().map(PluginLinkSuggestion::title).toList());
    }

    @Test
    void shouldHandleSpigotFailures() {
        String term = "Broken";
        stubEmptyOtherProviders(term);
        httpClient.whenError(spigotUrl(term), new IOException("boom"));

        List<PluginLinkSuggestion> suggestions = suggester.suggest(List.of(term));

        assertTrue(suggestions.isEmpty());
    }

    private void stubEmptyOtherProviders(String term) {
        httpClient.when(modrinthUrl(term), "{\"hits\":[]}");
        httpClient.when(hangarUrl(term), "{\"result\":[]}");
    }

    private String modrinthUrl(String term) {
        return MODRINTH_SEARCH_TEMPLATE.formatted(encode(term));
    }

    private String hangarUrl(String term) {
        return HANGAR_SEARCH_TEMPLATE.formatted(encode(term));
    }

    private String spigotUrl(String term) {
        return SPIGOT_SEARCH_TEMPLATE.formatted(encode(term));
    }

    private String encode(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8);
    }

    private static final class StubHttpClient extends HttpClient {

        private final Map<String, StubResponse> responses = new LinkedHashMap<>();

        void when(String url, String body) {
            responses.put(url, StubResponse.success(body));
        }

        void whenError(String url, IOException error) {
            responses.put(url, StubResponse.failure(error));
        }

        @Override
        protected String doGet(String url) throws IOException {
            StubResponse response = responses.get(url);
            if (response == null) {
                throw new IOException("Unexpected URL: " + url);
            }
            if (response.error != null) {
                throw response.error;
            }
            return response.body;
        }

        private record StubResponse(String body, IOException error) {
            static StubResponse success(String body) {
                return new StubResponse(body, null);
            }

            static StubResponse failure(IOException error) {
                return new StubResponse(null, error);
            }
        }
    }
}
