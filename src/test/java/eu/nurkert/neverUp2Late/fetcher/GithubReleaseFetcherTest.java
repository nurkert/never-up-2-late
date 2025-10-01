package eu.nurkert.neverUp2Late.fetcher;

import eu.nurkert.neverUp2Late.net.HttpClient;
import org.bukkit.configuration.MemoryConfiguration;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.time.Duration;
import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class GithubReleaseFetcherTest {

    @Test
    void skipsDraftsAndPrereleasesByDefault() throws Exception {
        Map<String, String> responses = new HashMap<>();
        responses.put("https://api.github.com/repos/example/demo/releases",
                """
                        [
                          {
                            "id": 1,
                            "tag_name": "v1.0.0",
                            "draft": false,
                            "prerelease": false,
                            "published_at": "2023-09-10T10:00:00Z",
                            "assets": [
                              { "browser_download_url": "https://example.com/v1.0.0.jar" }
                            ]
                          },
                          {
                            "id": 2,
                            "tag_name": "v1.1.0-beta",
                            "draft": false,
                            "prerelease": true,
                            "published_at": "2023-09-12T10:00:00Z",
                            "assets": [
                              { "browser_download_url": "https://example.com/v1.1.0-beta.jar" }
                            ]
                          },
                          {
                            "id": 3,
                            "tag_name": "v1.0.1",
                            "draft": false,
                            "prerelease": false,
                            "published_at": "2023-09-11T10:00:00Z",
                            "assets": [
                              { "browser_download_url": "https://example.com/v1.0.1.jar" }
                            ]
                          },
                          {
                            "id": 4,
                            "tag_name": "v0.9.9",
                            "draft": true,
                            "prerelease": false,
                            "published_at": "2023-09-13T10:00:00Z",
                            "assets": [
                              { "browser_download_url": "https://example.com/v0.9.9.jar" }
                            ]
                          }
                        ]
                        """);

        GithubReleaseFetcher fetcher = new GithubReleaseFetcher(options(false, null), new StubHttpClient(responses));
        fetcher.loadLatestBuildInfo();

        assertEquals("v1.0.1", fetcher.getLatestVersion());
        assertEquals(3, fetcher.getLatestBuild());
        assertEquals("https://example.com/v1.0.1.jar", fetcher.getLatestDownloadUrl());
    }

    @Test
    void allowsPrereleasesWhenConfigured() throws Exception {
        Map<String, String> responses = new HashMap<>();
        responses.put("https://api.github.com/repos/example/demo/releases",
                """
                        [
                          {
                            "id": 10,
                            "tag_name": "v2.0.0-beta1",
                            "draft": false,
                            "prerelease": true,
                            "published_at": "2023-09-20T10:00:00Z",
                            "assets": [
                              { "browser_download_url": "https://example.com/v2.0.0-beta1.jar" }
                            ]
                          },
                          {
                            "id": 9,
                            "tag_name": "v1.9.0",
                            "draft": false,
                            "prerelease": false,
                            "published_at": "2023-09-18T10:00:00Z",
                            "assets": [
                              { "browser_download_url": "https://example.com/v1.9.0.jar" }
                            ]
                          }
                        ]
                        """);

        GithubReleaseFetcher fetcher = new GithubReleaseFetcher(options(true, null), new StubHttpClient(responses));
        fetcher.loadLatestBuildInfo();

        assertEquals("v2.0.0-beta1", fetcher.getLatestVersion());
        assertEquals(10, fetcher.getLatestBuild());
        assertEquals("https://example.com/v2.0.0-beta1.jar", fetcher.getLatestDownloadUrl());
    }

    @Test
    void appliesAssetPatternFilter() throws Exception {
        Map<String, String> responses = new HashMap<>();
        responses.put("https://api.github.com/repos/example/demo/releases",
                """
                        [
                          {
                            "id": 5,
                            "tag_name": "v1.2.3",
                            "draft": false,
                            "prerelease": false,
                            "published_at": "2023-09-15T10:00:00Z",
                            "assets": [
                              { "browser_download_url": "https://example.com/v1.2.3.zip" },
                              { "browser_download_url": "https://example.com/v1.2.3-paper.jar" }
                            ]
                          }
                        ]
                        """);

        GithubReleaseFetcher matchingFetcher = new GithubReleaseFetcher(options(false, ".*-paper\\.jar$"), new StubHttpClient(responses));
        matchingFetcher.loadLatestBuildInfo();
        assertEquals("https://example.com/v1.2.3-paper.jar", matchingFetcher.getLatestDownloadUrl());

        GithubReleaseFetcher failingFetcher = new GithubReleaseFetcher(options(false, ".*linux\\.jar$"), new StubHttpClient(responses));
        assertThrows(IOException.class, failingFetcher::loadLatestBuildInfo);
    }

    private MemoryConfiguration options(boolean allowPrerelease, String assetPattern) {
        MemoryConfiguration configuration = new MemoryConfiguration();
        configuration.set("owner", "example");
        configuration.set("repository", "demo");
        configuration.set("allowPrerelease", allowPrerelease);
        if (assetPattern != null) {
            configuration.set("assetPattern", assetPattern);
        }
        return configuration;
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
