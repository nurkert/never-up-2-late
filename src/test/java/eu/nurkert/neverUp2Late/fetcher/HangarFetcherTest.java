package eu.nurkert.neverUp2Late.fetcher;

import eu.nurkert.neverUp2Late.net.HttpClient;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.time.Duration;
import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class HangarFetcherTest {

    @Test
    void skipsUnstableVersionsAndFallsBackToLaterPages() throws Exception {
        Map<String, String> responses = new HashMap<>();
        responses.put("https://hangar.papermc.io/api/v1/projects/testOwner/testProject/versions?limit=2&offset=0",
                """
                        {
                          "pagination": {"count": 3, "limit": 2, "offset": 0},
                          "result": [
                            {
                              "createdAt": "2024-07-01T10:15:30Z",
                              "id": 101,
                              "name": "1.2.0-beta",
                              "visibility": "public",
                              "reviewState": "reviewed",
                              "channel": {"name": "Beta", "flags": ["UNSTABLE"]},
                              "pinnedStatus": "NONE",
                              "downloads": {
                                "PAPER": {"fileInfo": null, "externalUrl": null, "downloadUrl": "https://example.com/beta.jar"}
                              }
                            }
                          ]
                        }
                        """);
        responses.put("https://hangar.papermc.io/api/v1/projects/testOwner/testProject/versions?limit=2&offset=2",
                """
                        {
                          "pagination": {"count": 3, "limit": 2, "offset": 2},
                          "result": [
                            {
                              "createdAt": "2024-06-15T08:00:00Z",
                              "id": 42,
                              "name": "1.1.0",
                              "visibility": "public",
                              "reviewState": "reviewed",
                              "channel": {"name": "Release", "flags": ["PINNED"]},
                              "pinnedStatus": "CHANNEL",
                              "downloads": {
                                "PAPER": {"fileInfo": null, "externalUrl": null, "downloadUrl": "https://example.com/1.1.0.jar"}
                              }
                            }
                          ]
                        }
                        """);

        HangarFetcher.Config config = HangarFetcher.builder("testOwner", "testProject")
                .pageSize(2)
                .maxPages(3)
                .build();
        HangarFetcher fetcher = new HangarFetcher(config, new StubHttpClient(responses));

        fetcher.loadLatestBuildInfo();

        assertEquals("1.1.0", fetcher.getLatestVersion());
        assertEquals(42, fetcher.getLatestBuild());
        assertEquals("https://example.com/1.1.0.jar", fetcher.getLatestDownloadUrl());
    }

    @Test
    void allowsUnstableVersionsWhenConfigured() throws Exception {
        Map<String, String> responses = new HashMap<>();
        responses.put("https://hangar.papermc.io/api/v1/projects/testOwner/testProject/versions?limit=25&offset=0",
                """
                        {
                          "pagination": {"count": 1, "limit": 25, "offset": 0},
                          "result": [
                            {
                              "createdAt": "2024-07-01T10:15:30Z",
                              "id": 7,
                              "name": "1.2.0-b5",
                              "visibility": "public",
                              "reviewState": "reviewed",
                              "channel": {"name": "Beta", "flags": ["UNSTABLE"]},
                              "pinnedStatus": "NONE",
                              "downloads": {
                                "PAPER": {"fileInfo": null, "externalUrl": null, "downloadUrl": "https://example.com/beta.jar"}
                              }
                            }
                          ]
                        }
                        """);

        HangarFetcher.Config config = HangarFetcher.builder("testOwner", "testProject")
                .ignoreUnstable(false)
                .build();
        HangarFetcher fetcher = new HangarFetcher(config, new StubHttpClient(responses));

        fetcher.loadLatestBuildInfo();

        assertEquals("1.2.0-b5", fetcher.getLatestVersion());
        assertEquals(5, fetcher.getLatestBuild());
        assertEquals("https://example.com/beta.jar", fetcher.getLatestDownloadUrl());
    }

    @Test
    void prefersPinnedVersionsOverNewerNonPinned() throws Exception {
        Map<String, String> responses = new HashMap<>();
        responses.put("https://hangar.papermc.io/api/v1/projects/testOwner/testProject/versions?limit=25&offset=0",
                """
                        {
                          "pagination": {"count": 2, "limit": 25, "offset": 0},
                          "result": [
                            {
                              "createdAt": "2024-07-10T10:00:00Z",
                              "id": 30,
                              "name": "1.3.0",
                              "visibility": "public",
                              "reviewState": "reviewed",
                              "channel": {"name": "Release", "flags": ["PINNED"]},
                              "pinnedStatus": "NONE",
                              "downloads": {
                                "PAPER": {"fileInfo": null, "externalUrl": null, "downloadUrl": "https://example.com/1.3.0.jar"}
                              }
                            },
                            {
                              "createdAt": "2024-06-10T10:00:00Z",
                              "id": 20,
                              "name": "1.2.0",
                              "visibility": "public",
                              "reviewState": "reviewed",
                              "channel": {"name": "Release", "flags": ["PINNED"]},
                              "pinnedStatus": "GLOBAL",
                              "downloads": {
                                "PAPER": {"fileInfo": null, "externalUrl": null, "downloadUrl": "https://example.com/1.2.0.jar"}
                              }
                            }
                          ]
                        }
                        """);

        HangarFetcher fetcher = new HangarFetcher(HangarFetcher.builder("testOwner", "testProject").build(),
                new StubHttpClient(responses));

        fetcher.loadLatestBuildInfo();

        assertEquals("1.2.0", fetcher.getLatestVersion());
        assertEquals(20, fetcher.getLatestBuild());
        assertEquals("https://example.com/1.2.0.jar", fetcher.getLatestDownloadUrl());
    }

    @Test
    void fallsBackToExternalDownloadUrl() throws Exception {
        Map<String, String> responses = new HashMap<>();
        responses.put("https://hangar.papermc.io/api/v1/projects/testOwner/testProject/versions?limit=25&offset=0",
                """
                        {
                          "pagination": {"count": 1, "limit": 25, "offset": 0},
                          "result": [
                            {
                              "createdAt": "2024-07-01T10:15:30Z",
                              "id": 12,
                              "name": "1.0.0",
                              "visibility": "public",
                              "reviewState": "reviewed",
                              "channel": {"name": "Release", "flags": ["PINNED"]},
                              "pinnedStatus": "CHANNEL",
                              "downloads": {
                                "PAPER": {"fileInfo": null, "externalUrl": "https://example.com/external.jar", "downloadUrl": null}
                              }
                            }
                          ]
                        }
                        """);

        HangarFetcher fetcher = new HangarFetcher(HangarFetcher.builder("testOwner", "testProject").build(),
                new StubHttpClient(responses));

        fetcher.loadLatestBuildInfo();

        assertEquals("1.0.0", fetcher.getLatestVersion());
        assertEquals(12, fetcher.getLatestBuild());
        assertEquals("https://example.com/external.jar", fetcher.getLatestDownloadUrl());
    }

    @Test
    void throwsWhenNoEligibleVersionFound() {
        Map<String, String> responses = new HashMap<>();
        responses.put("https://hangar.papermc.io/api/v1/projects/testOwner/testProject/versions?limit=25&offset=0",
                """
                        {
                          "pagination": {"count": 1, "limit": 25, "offset": 0},
                          "result": [
                            {
                              "createdAt": "2024-07-01T10:15:30Z",
                              "id": 1,
                              "name": "1.0.0",
                              "visibility": "draft",
                              "reviewState": "unreviewed",
                              "channel": {"name": "Release", "flags": ["PINNED"]},
                              "pinnedStatus": "NONE",
                              "downloads": {
                                "PAPER": {"fileInfo": null, "externalUrl": null, "downloadUrl": null}
                              }
                            }
                          ]
                        }
                        """);

        HangarFetcher fetcher = new HangarFetcher(HangarFetcher.builder("testOwner", "testProject").build(),
                new StubHttpClient(responses));

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
