package eu.nurkert.neverUp2Late.fetcher;

import eu.nurkert.neverUp2Late.net.HttpClient;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.time.Duration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class SpigotFetcherTest {

    @Test
    void fetchesLatestVersionAndDownloadUrl() throws Exception {
        Map<String, String> responses = new HashMap<>();
        responses.put("https://api.spiget.org/v2/resources/12345",
                """
                        {
                          \"id\": 12345,
                          \"premium\": false,
                          \"testedVersions\": [\"1.21\", \"1.20\"],
                          \"versions\": [
                            { \"id\": 2 },
                            { \"id\": 1 }
                          ]
                        }
                        """);
        responses.put("https://api.spiget.org/v2/resources/12345/versions/2",
                """
                        {
                          \"id\": 2,
                          \"name\": \"2.1.0-b7\",
                          \"version\": \"2.1.0\"
                        }
                        """);

        SpigotFetcher.Config config = SpigotFetcher.builder(12345)
                .preferredGameVersions(List.of("1.21"))
                .build();
        SpigotFetcher fetcher = new SpigotFetcher(config, new StubHttpClient(responses));

        fetcher.loadLatestBuildInfo();

        assertEquals("2.1.0-b7", fetcher.getLatestVersion());
        assertEquals(7, fetcher.getLatestBuild());
        assertEquals("https://api.spiget.org/v2/resources/12345/versions/2/download", fetcher.getLatestDownloadUrl());
    }

    @Test
    void rejectsPremiumResources() {
        Map<String, String> responses = new HashMap<>();
        responses.put("https://api.spiget.org/v2/resources/55",
                """
                        {
                          \"id\": 55,
                          \"premium\": true,
                          \"versions\": []
                        }
                        """);

        SpigotFetcher fetcher = new SpigotFetcher(SpigotFetcher.builder(55).build(), new StubHttpClient(responses));

        assertThrows(IOException.class, fetcher::loadLatestBuildInfo);
    }

    @Test
    void allowsIgnoringCompatibilityWarnings() throws Exception {
        Map<String, String> responses = new HashMap<>();
        responses.put("https://api.spiget.org/v2/resources/321",
                """
                        {
                          \"id\": 321,
                          \"premium\": false,
                          \"testedVersions\": [\"1.19\"],
                          \"versions\": [
                            { \"id\": 99 }
                          ],
                          \"file\": {
                            \"externalUrl\": \"https://example.com/download.jar\"
                          }
                        }
                        """);
        responses.put("https://api.spiget.org/v2/resources/321/versions/99",
                """
                        {
                          \"id\": 99,
                          \"name\": \"Release\",
                          \"version\": null
                        }
                        """);

        SpigotFetcher.Config strictConfig = SpigotFetcher.builder(321)
                .preferredGameVersions(List.of("1.20"))
                .build();
        SpigotFetcher strictFetcher = new SpigotFetcher(strictConfig, new StubHttpClient(responses));

        assertThrows(IOException.class, strictFetcher::loadLatestBuildInfo);

        SpigotFetcher.Config relaxedConfig = SpigotFetcher.builder(321)
                .preferredGameVersions(List.of("1.20"))
                .ignoreCompatibilityWarnings(true)
                .build();
        SpigotFetcher relaxedFetcher = new SpigotFetcher(relaxedConfig, new StubHttpClient(responses));

        relaxedFetcher.loadLatestBuildInfo();

        assertEquals("Release", relaxedFetcher.getLatestVersion());
        assertEquals(99, relaxedFetcher.getLatestBuild());
        assertEquals("https://example.com/download.jar", relaxedFetcher.getLatestDownloadUrl());
    }

    private static class StubHttpClient extends HttpClient {
        private final Map<String, String> responses;

        StubHttpClient(Map<String, String> responses) {
            super(java.net.http.HttpClient.newBuilder().build(), Duration.ofSeconds(1), Map.of());
            this.responses = responses;
        }

        @Override
        protected String doGet(String url) throws IOException {
            String response = responses.get(url);
            if (response == null) {
                throw new IOException("No stubbed response for " + url);
            }
            return response;
        }
    }
}
