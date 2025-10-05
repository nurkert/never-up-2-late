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
        Map<String, StubHttpClient.StubResponse> responses = new HashMap<>();
        responses.put("https://api.spiget.org/v2/resources/12345",
                StubHttpClient.body("""
                        {
                          \"id\": 12345,
                          \"premium\": false,
                          \"testedVersions\": [\"1.21\", \"1.20\"],
                          \"versions\": [
                            { \"id\": 2 },
                            { \"id\": 1 }
                          ]
                        }
                        """));
        responses.put("https://api.spiget.org/v2/resources/12345/versions/2",
                StubHttpClient.body("""
                        {
                          \"id\": 2,
                          \"name\": \"2.1.0-b7\",
                          \"version\": \"2.1.0\"
                        }
                        """));

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
        Map<String, StubHttpClient.StubResponse> responses = new HashMap<>();
        responses.put("https://api.spiget.org/v2/resources/55",
                StubHttpClient.body("""
                        {
                          \"id\": 55,
                          \"premium\": true,
                          \"versions\": []
                        }
                        """));

        SpigotFetcher fetcher = new SpigotFetcher(SpigotFetcher.builder(55).build(), new StubHttpClient(responses));

        assertThrows(IOException.class, fetcher::loadLatestBuildInfo);
    }

    @Test
    void allowsIgnoringCompatibilityWarnings() throws Exception {
        Map<String, StubHttpClient.StubResponse> responses = new HashMap<>();
        responses.put("https://api.spiget.org/v2/resources/321",
                StubHttpClient.body("""
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
                        """));
        responses.put("https://api.spiget.org/v2/resources/321/versions/99",
                StubHttpClient.body("""
                        {
                          \"id\": 99,
                          \"name\": \"Release\",
                          \"version\": null
                        }
                        """));

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

    @Test
    void fallsBackToPreviousVersionWhenLatestFails() throws Exception {
        Map<String, StubHttpClient.StubResponse> responses = new HashMap<>();
        responses.put("https://api.spiget.org/v2/resources/987",
                StubHttpClient.body("""
                        {
                          \"id\": 987,
                          \"premium\": false,
                          \"versions\": [
                            { \"id\": 5 },
                            { \"id\": 4 }
                          ]
                        }
                        """));
        responses.put("https://api.spiget.org/v2/resources/987/versions/5",
                StubHttpClient.error(new IOException("Simulated 404")));
        responses.put("https://api.spiget.org/v2/resources/987/versions/4",
                StubHttpClient.body("""
                        {
                          \"id\": 4,
                          \"name\": \"1.4.0\",
                          \"version\": \"1.4.0\",
                          \"file\": {
                            \"url\": \"/resources/987/download\"
                          }
                        }
                        """));

        SpigotFetcher fetcher = new SpigotFetcher(SpigotFetcher.builder(987).build(), new StubHttpClient(responses));

        fetcher.loadLatestBuildInfo();

        assertEquals("1.4.0", fetcher.getLatestVersion());
        assertEquals(4, fetcher.getLatestBuild());
        assertEquals("https://api.spiget.org/v2/resources/987/download", fetcher.getLatestDownloadUrl());
    }

    @Test
    void propagatesFailureWhenAllVersionsFail() {
        Map<String, StubHttpClient.StubResponse> responses = new HashMap<>();
        responses.put("https://api.spiget.org/v2/resources/654",
                StubHttpClient.body("""
                        {
                          \"id\": 654,
                          \"premium\": false,
                          \"versions\": [
                            { \"id\": 3 },
                            { \"id\": 2 }
                          ]
                        }
                        """));
        responses.put("https://api.spiget.org/v2/resources/654/versions/3",
                StubHttpClient.error(new IOException("Simulated failure for v3")));
        responses.put("https://api.spiget.org/v2/resources/654/versions/2",
                StubHttpClient.error(new IOException("Simulated failure for v2")));

        SpigotFetcher fetcher = new SpigotFetcher(SpigotFetcher.builder(654).build(), new StubHttpClient(responses));

        IOException exception = assertThrows(IOException.class, fetcher::loadLatestBuildInfo);
        assertEquals("Simulated failure for v2", exception.getMessage());
    }

    private static class StubHttpClient extends HttpClient {
        private final Map<String, StubResponse> responses;

        StubHttpClient(Map<String, StubResponse> responses) {
            super(java.net.http.HttpClient.newBuilder().build(), Duration.ofSeconds(1), Map.of());
            this.responses = responses;
        }

        static StubResponse body(String body) {
            return new StubResponse(body, null);
        }

        static StubResponse error(IOException exception) {
            return new StubResponse(null, exception);
        }

        @Override
        protected String doGet(String url) throws IOException {
            StubResponse response = responses.get(url);
            if (response == null) {
                throw new IOException("No stubbed response for " + url);
            }
            if (response.exception() != null) {
                throw response.exception();
            }
            if (response.body() == null) {
                throw new IOException("No stubbed response body for " + url);
            }
            return response.body();
        }

        private record StubResponse(String body, IOException exception) {
        }
    }
}
