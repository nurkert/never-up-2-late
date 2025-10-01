package eu.nurkert.neverUp2Late.fetcher;

import eu.nurkert.neverUp2Late.net.HttpClient;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.time.Duration;
import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class GeyserFetcherTest {

    @Test
    void selectsLatestListedBuildForLatestMinecraftVersion() throws Exception {
        Map<String, String> responses = new HashMap<>();
        responses.put("https://api.modrinth.com/v2/project/geyser/version",
                """
                        [
                          {
                            "id": "1",
                            "version_number": "2.1.0-b100",
                            "status": "listed",
                            "date_published": "2023-09-10T10:15:30Z",
                            "game_versions": ["1.20.1", "1.20"],
                            "loaders": ["spigot"],
                            "files": [
                              { "url": "https://example.com/100.jar", "primary": true }
                            ]
                          },
                          {
                            "id": "2",
                            "version_number": "2.1.1-b105",
                            "status": "listed",
                            "date_published": "2023-09-12T10:15:30Z",
                            "game_versions": ["1.20.1"],
                            "loaders": ["paper"],
                            "files": [
                              { "url": "https://example.com/105.jar", "primary": true }
                            ]
                          },
                          {
                            "id": "3",
                            "version_number": "2.0.0-b90",
                            "status": "unlisted",
                            "date_published": "2023-09-11T10:15:30Z",
                            "game_versions": ["1.19"],
                            "loaders": ["spigot"],
                            "files": [
                              { "url": "https://example.com/90.jar", "primary": true }
                            ]
                          }
                        ]
                        """);

        GeyserFetcher fetcher = new GeyserFetcher(new StubHttpClient(responses));
        fetcher.loadLatestBuildInfo();

        assertEquals("2.1.1-b105", fetcher.getLatestVersion());
        assertEquals(105, fetcher.getLatestBuild());
        assertEquals("https://example.com/105.jar", fetcher.getLatestDownloadUrl());
    }

    @Test
    void failsWhenBuildNumberCannotBeExtracted() {
        Map<String, String> responses = new HashMap<>();
        responses.put("https://api.modrinth.com/v2/project/geyser/version",
                """
                        [
                          {
                            "id": "1",
                            "version_number": "2.1.0",
                            "status": "listed",
                            "date_published": "2023-09-10T10:15:30Z",
                            "game_versions": ["1.20.1"],
                            "loaders": ["paper"],
                            "files": [
                              { "url": "https://example.com/100.jar", "primary": true }
                            ]
                          }
                        ]
                        """);

        GeyserFetcher fetcher = new GeyserFetcher(new StubHttpClient(responses));
        assertThrows(IOException.class, fetcher::loadLatestBuildInfo);
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
