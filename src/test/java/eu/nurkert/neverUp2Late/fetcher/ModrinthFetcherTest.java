package eu.nurkert.neverUp2Late.fetcher;

import eu.nurkert.neverUp2Late.net.HttpClient;
import org.bukkit.configuration.MemoryConfiguration;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.time.Duration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ModrinthFetcherTest {

    @Test
    void selectsLatestBuildAndFallsBackToHashForBuildNumber() throws Exception {
        Map<String, String> responses = new HashMap<>();
        responses.put("https://api.modrinth.com/v2/project/example/version",
                """
                        [
                          {
                            \"id\": \"1\",
                            \"version_number\": \"1.0.0\",
                            \"status\": \"listed\",
                            \"date_published\": \"2023-09-10T10:15:30Z\",
                            \"game_versions\": [\"1.20\"],
                            \"loaders\": [\"paper\"],
                            \"files\": [
                              { \"url\": \"https://example.com/1.0.0.jar\", \"primary\": false }
                            ]
                          },
                          {
                            \"id\": \"2\",
                            \"version_number\": \"1.1.0\",
                            \"status\": \"listed\",
                            \"date_published\": \"2023-09-12T10:15:30Z\",
                            \"game_versions\": [\"1.20\"],
                            \"loaders\": [\"paper\"],
                            \"files\": [
                              { \"url\": \"https://example.com/1.1.0.jar\", \"primary\": true }
                            ]
                          }
                        ]
                        """);

        ModrinthFetcher.Config config = ModrinthFetcher.builder("example")
                .loaders(List.of("paper"))
                .build();
        ModrinthFetcher fetcher = new ModrinthFetcher(config, new StubHttpClient(responses));
        fetcher.loadLatestBuildInfo();

        assertEquals("1.1.0", fetcher.getLatestVersion());
        assertEquals("https://example.com/1.1.0.jar", fetcher.getLatestDownloadUrl());

        int hash = Objects.hash("2", "1.1.0");
        int expectedBuild = hash == Integer.MIN_VALUE ? Integer.MAX_VALUE : Math.abs(hash);
        assertEquals(expectedBuild, fetcher.getLatestBuild());
        assertTrue(expectedBuild > 0);
    }

    @Test
    void respectsPreferredGameVersionsFromConfiguration() throws Exception {
        Map<String, String> responses = new HashMap<>();
        responses.put("https://api.modrinth.com/v2/project/example/version",
                """
                        [
                          {
                            \"id\": \"1\",
                            \"version_number\": \"1.1.0\",
                            \"status\": \"listed\",
                            \"date_published\": \"2023-09-12T10:15:30Z\",
                            \"game_versions\": [\"1.20\"],
                            \"loaders\": [\"paper\"],
                            \"files\": [
                              { \"url\": \"https://example.com/1.1.0.jar\", \"primary\": true }
                            ]
                          },
                          {
                            \"id\": \"2\",
                            \"version_number\": \"0.9.0\",
                            \"status\": \"listed\",
                            \"date_published\": \"2023-08-10T10:15:30Z\",
                            \"game_versions\": [\"1.19\"],
                            \"loaders\": [\"paper\"],
                            \"files\": [
                              { \"url\": \"https://example.com/0.9.0.jar\", \"primary\": true }
                            ]
                          }
                        ]
                        """);

        MemoryConfiguration options = new MemoryConfiguration();
        options.set("project", "example");
        options.set("loaders", List.of("paper"));
        options.set("gameVersions", List.of("1.19"));

        ModrinthFetcher.Config config = ModrinthFetcher.Config.fromConfiguration(options);
        ModrinthFetcher fetcher = new ModrinthFetcher(config, new StubHttpClient(responses));
        fetcher.loadLatestBuildInfo();

        assertEquals("0.9.0", fetcher.getLatestVersion());
        assertEquals("https://example.com/0.9.0.jar", fetcher.getLatestDownloadUrl());
    }

    @Test
    void ignoresBuildsWithoutPaperOrSpigotLoader() throws Exception {
        Map<String, String> responses = new HashMap<>();
        responses.put("https://api.modrinth.com/v2/project/example/version",
                """
                        [
                          {
                            \"id\": \"1\",
                            \"version_number\": \"2.0.0\",
                            \"status\": \"listed\",
                            \"date_published\": \"2023-10-10T10:15:30Z\",
                            \"game_versions\": [\"1.20\"],
                            \"loaders\": [\"fabric\"],
                            \"files\": [
                              { \"url\": \"https://example.com/2.0.0.jar\", \"primary\": true }
                            ]
                          },
                          {
                            \"id\": \"2\",
                            \"version_number\": \"1.5.0\",
                            \"status\": \"listed\",
                            \"date_published\": \"2023-09-10T10:15:30Z\",
                            \"game_versions\": [\"1.20\"],
                            \"loaders\": [\"paper\"],
                            \"files\": [
                              { \"url\": \"https://example.com/1.5.0.jar\", \"primary\": true }
                            ]
                          }
                        ]
                        """);

        ModrinthFetcher.Config config = ModrinthFetcher.builder("example")
                .loaders(List.of("paper", "spigot"))
                .build();
        ModrinthFetcher fetcher = new ModrinthFetcher(config, new StubHttpClient(responses));
        fetcher.loadLatestBuildInfo();

        assertEquals("1.5.0", fetcher.getLatestVersion());
        assertEquals("https://example.com/1.5.0.jar", fetcher.getLatestDownloadUrl());
    }

    @Test
    void limitsGameVersionToConfiguredMaximum() throws Exception {
        Map<String, String> responses = new HashMap<>();
        responses.put("https://api.modrinth.com/v2/project/geyser/version",
                """
                        [
                          {
                            \"id\": \"1\",
                            \"version_number\": \"2.1.0\",
                            \"status\": \"listed\",
                            \"date_published\": \"2024-10-01T10:15:30Z\",
                            \"game_versions\": [\"1.21.9\"],
                            \"loaders\": [\"paper\"],
                            \"files\": [
                              { \"url\": \"https://example.com/2.1.0.jar\", \"primary\": true }
                            ]
                          },
                          {
                            \"id\": \"2\",
                            \"version_number\": \"2.0.0\",
                            \"status\": \"listed\",
                            \"date_published\": \"2024-09-15T10:15:30Z\",
                            \"game_versions\": [\"1.21.8\"],
                            \"loaders\": [\"paper\"],
                            \"files\": [
                              { \"url\": \"https://example.com/2.0.0.jar\", \"primary\": true }
                            ]
                          }
                        ]
                        """);

        ModrinthFetcher.Config config = ModrinthFetcher.builder("geyser")
                .loaders(List.of("paper"))
                .maxGameVersion("1.21.8")
                .build();

        ModrinthFetcher fetcher = new ModrinthFetcher(config, new StubHttpClient(responses));
        fetcher.loadLatestBuildInfo();

        assertEquals("2.0.0", fetcher.getLatestVersion());
        assertEquals("https://example.com/2.0.0.jar", fetcher.getLatestDownloadUrl());
    }

    @Test
    void ignoresMaximumWhenCompatibilityWarningsAreDisabled() throws Exception {
        Map<String, String> responses = new HashMap<>();
        responses.put("https://api.modrinth.com/v2/project/example/version",
                """
                        [
                          {
                            \"id\": \"3\",
                            \"version_number\": \"2.0.0\",
                            \"status\": \"listed\",
                            \"date_published\": \"2024-11-01T10:15:30Z\",
                            \"game_versions\": [\"1.21.4\"],
                            \"loaders\": [\"paper\"],
                            \"files\": [
                              { \"url\": \"https://example.com/2.0.0.jar\", \"primary\": true }
                            ]
                          },
                          {
                            \"id\": \"2\",
                            \"version_number\": \"1.9.0\",
                            \"status\": \"listed\",
                            \"date_published\": \"2024-08-15T10:15:30Z\",
                            \"game_versions\": [\"1.20.4\"],
                            \"loaders\": [\"paper\"],
                            \"files\": [
                              { \"url\": \"https://example.com/1.9.0.jar\", \"primary\": true }
                            ]
                          }
                        ]
                        """);

        ModrinthFetcher.Config config = ModrinthFetcher.builder("example")
                .loaders(List.of("paper"))
                .maxGameVersion("1.20.4")
                .ignoreCompatibilityWarnings(true)
                .build();

        ModrinthFetcher fetcher = new ModrinthFetcher(config, new StubHttpClient(responses));
        fetcher.loadLatestBuildInfo();

        assertEquals("2.0.0", fetcher.getLatestVersion());
        assertEquals("https://example.com/2.0.0.jar", fetcher.getLatestDownloadUrl());
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
