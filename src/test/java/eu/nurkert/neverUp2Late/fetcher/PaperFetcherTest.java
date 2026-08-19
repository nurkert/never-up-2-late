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

class PaperFetcherTest {

    private static final String PROJECT_URL = "https://fill.papermc.io/v3/projects/paper";

    private static String buildsUrl(String version) {
        return PROJECT_URL + "/versions/" + version + "/builds";
    }

    /** URL exactly as Fill hands it out: content addressed, not derivable from version and build. */
    private static String downloadUrl(String version, int build) {
        return "https://fill-data.papermc.io/v1/objects/"
                + "5ffef465eeeb5f2a3c23a24419d97c51afd7dbb4923ff42df9a3f58bba1ccfba/"
                + "paper-" + version + "-" + build + ".jar";
    }

    /** One entry of the build list Fill returns, with the server jar attached. */
    private static String build(String version, int id, String channel) {
        return """
                {
                  "id": %d,
                  "time": "2026-05-11T11:43:09Z",
                  "channel": "%s",
                  "downloads": {
                    "server:default": {
                      "name": "paper-%s-%d.jar",
                      "checksums": { "sha256": "5ffef465eeeb5f2a3c23a24419d97c51afd7dbb4923ff42df9a3f58bba1ccfba" },
                      "size": 54846016,
                      "url": "%s"
                    }
                  }
                }
                """.formatted(id, channel, version, id, downloadUrl(version, id));
    }

    private static String builds(String version, Object... idChannelPairs) {
        StringBuilder json = new StringBuilder("[");
        for (int i = 0; i < idChannelPairs.length; i += 2) {
            if (i > 0) {
                json.append(',');
            }
            json.append(build(version, (Integer) idChannelPairs[i], (String) idChannelPairs[i + 1]));
        }
        return json.append(']').toString();
    }

    @Test
    void loadsLatestStableBuildFromApiResponses() throws Exception {
        Map<String, String> responses = new HashMap<>();
        responses.put(PROJECT_URL,
                """
                        {
                          "project": { "id": "paper", "name": "Paper" },
                          "versions": {
                            "1.20": ["1.20.2-rc1", "1.20.1", "1.20"],
                            "1.19": ["1.19.4"]
                          }
                        }
                        """);
        responses.put(buildsUrl("1.20.1"), builds("1.20.1", 16, "STABLE", 15, "STABLE", 14, "STABLE"));

        PaperFetcher fetcher = new PaperFetcher(true, new StubHttpClient(responses));
        fetcher.loadLatestBuildInfo();

        assertEquals("1.20.1", fetcher.getLatestVersion());
        assertEquals(16, fetcher.getLatestBuild());
        assertEquals(downloadUrl("1.20.1", 16), fetcher.getLatestDownloadUrl());
    }

    @Test
    void skipsBuildsOnUnstableChannelsWhenStableRequested() throws Exception {
        Map<String, String> responses = new HashMap<>();
        responses.put(PROJECT_URL,
                """
                        {
                          "versions": { "1.20": ["1.20.1"] }
                        }
                        """);
        // Newest build is BETA, so the newest STABLE one below it must win.
        responses.put(buildsUrl("1.20.1"), builds("1.20.1", 18, "BETA", 17, "ALPHA", 16, "STABLE"));

        PaperFetcher fetcher = new PaperFetcher(true, new StubHttpClient(responses));
        fetcher.loadLatestBuildInfo();

        assertEquals(16, fetcher.getLatestBuild());
        assertEquals(downloadUrl("1.20.1", 16), fetcher.getLatestDownloadUrl());
    }

    @Test
    void takesDownloadUrlFromPayloadRatherThanAssemblingIt() throws Exception {
        Map<String, String> responses = new HashMap<>();
        responses.put(PROJECT_URL,
                """
                        {
                          "versions": { "1.20": ["1.20.1"] }
                        }
                        """);
        responses.put(buildsUrl("1.20.1"),
                """
                        [
                          {
                            "id": 16,
                            "channel": "STABLE",
                            "downloads": {
                              "server:default": {
                                "name": "paper-1.20.1-16.jar",
                                "url": "https://fill-data.papermc.io/v1/objects/deadbeef/paper-1.20.1-16.jar"
                              }
                            }
                          }
                        ]
                        """);

        PaperFetcher fetcher = new PaperFetcher(true, new StubHttpClient(responses));
        fetcher.loadLatestBuildInfo();

        assertEquals("https://fill-data.papermc.io/v1/objects/deadbeef/paper-1.20.1-16.jar",
                fetcher.getLatestDownloadUrl());
    }

    @Test
    void throwsWhenBuildOffersNoServerDownload() {
        Map<String, String> responses = new HashMap<>();
        responses.put(PROJECT_URL,
                """
                        {
                          "versions": { "1.20": ["1.20.1"] }
                        }
                        """);
        responses.put(buildsUrl("1.20.1"),
                """
                        [
                          { "id": 16, "channel": "STABLE", "downloads": { "server:mojmap": { "url": "https://example.invalid/x.jar" } } }
                        ]
                        """);

        PaperFetcher fetcher = new PaperFetcher(true, new StubHttpClient(responses));
        assertThrows(IOException.class, fetcher::loadLatestBuildInfo);
    }

    @Test
    void includesUnstableVersionsWhenRequested() throws Exception {
        Map<String, String> responses = new HashMap<>();
        responses.put(PROJECT_URL,
                """
                        {
                          "versions": { "1.20": ["1.20.2-rc1", "1.20.1"] }
                        }
                        """);
        responses.put(buildsUrl("1.20.2-rc1"), builds("1.20.2-rc1", 2, "ALPHA", 1, "ALPHA"));

        PaperFetcher fetcher = new PaperFetcher(false, new StubHttpClient(responses));
        fetcher.loadLatestBuildInfo();

        assertEquals("1.20.2-rc1", fetcher.getLatestVersion());
        assertEquals(2, fetcher.getLatestBuild());
    }

    @Test
    void throwsWhenBuildInformationMissing() {
        Map<String, String> responses = new HashMap<>();
        responses.put(PROJECT_URL,
                """
                        {
                          "versions": { "1.20": ["1.20.1"] }
                        }
                        """);
        // Missing build information

        PaperFetcher fetcher = new PaperFetcher(true, new StubHttpClient(responses));
        assertThrows(IOException.class, fetcher::loadLatestBuildInfo);
    }

    @Test
    void prefersStableWhenOptionOverridesDefault() throws Exception {
        Map<String, String> responses = new HashMap<>();
        responses.put(PROJECT_URL,
                """
                        {
                          "versions": { "1.20": ["1.20.2-rc1", "1.20.1"] }
                        }
                        """);
        responses.put(buildsUrl("1.20.1"), builds("1.20.1", 16, "STABLE", 15, "STABLE"));

        MemoryConfiguration options = new MemoryConfiguration();
        options.set("_ignoreUnstableDefault", false);
        options.set("ignoreUnstable", true);

        PaperFetcher fetcher = new PaperFetcher(options, new StubHttpClient(responses));
        fetcher.loadLatestBuildInfo();

        assertEquals("1.20.1", fetcher.getLatestVersion());
        assertEquals(16, fetcher.getLatestBuild());
    }

    @Test
    void fallsBackToStableUntilMinimumUnstableBuildReached() throws Exception {
        Map<String, String> responses = new HashMap<>();
        responses.put(PROJECT_URL,
                """
                        {
                          "versions": { "1.20": ["1.20.2-rc1", "1.20.1"] }
                        }
                        """);
        responses.put(buildsUrl("1.20.2-rc1"), builds("1.20.2-rc1", 2, "ALPHA", 1, "ALPHA"));
        responses.put(buildsUrl("1.20.1"), builds("1.20.1", 16, "STABLE", 15, "STABLE"));

        MemoryConfiguration options = new MemoryConfiguration();
        options.set("_ignoreUnstableDefault", true);
        options.set("allowUnstable", true);

        PaperFetcher fetcher = new PaperFetcher(options, new StubHttpClient(responses));
        fetcher.loadLatestBuildInfo();

        assertEquals("1.20.1", fetcher.getLatestVersion());
        assertEquals(16, fetcher.getLatestBuild());
    }

    @Test
    void allowsUnstableOnceMinimumBuildReached() throws Exception {
        Map<String, String> responses = new HashMap<>();
        responses.put(PROJECT_URL,
                """
                        {
                          "versions": { "1.20": ["1.20.2-rc1", "1.20.1"] }
                        }
                        """);
        responses.put(buildsUrl("1.20.2-rc1"),
                builds("1.20.2-rc1", 51, "ALPHA", 50, "ALPHA", 49, "ALPHA", 48, "ALPHA"));

        MemoryConfiguration options = new MemoryConfiguration();
        options.set("_ignoreUnstableDefault", true);
        options.set("allowUnstable", true);

        PaperFetcher fetcher = new PaperFetcher(options, new StubHttpClient(responses));
        fetcher.loadLatestBuildInfo();

        assertEquals("1.20.2-rc1", fetcher.getLatestVersion());
        assertEquals(51, fetcher.getLatestBuild());
    }

    @Test
    void allowsCustomMinimumForUnstableBuilds() throws Exception {
        Map<String, String> responses = new HashMap<>();
        responses.put(PROJECT_URL,
                """
                        {
                          "versions": { "1.20": ["1.20.2-rc1", "1.20.1"] }
                        }
                        """);
        responses.put(buildsUrl("1.20.2-rc1"), builds("1.20.2-rc1", 5, "ALPHA", 4, "ALPHA"));

        MemoryConfiguration options = new MemoryConfiguration();
        options.set("_ignoreUnstableDefault", true);
        options.set("allowUnstable", true);
        options.set("minimumUnstableBuild", 5);

        PaperFetcher fetcher = new PaperFetcher(options, new StubHttpClient(responses));
        fetcher.loadLatestBuildInfo();

        assertEquals("1.20.2-rc1", fetcher.getLatestVersion());
        assertEquals(5, fetcher.getLatestBuild());
    }

    @Test
    void staysOnInstalledGameVersion() throws Exception {
        Map<String, String> responses = new HashMap<>();
        responses.put(PROJECT_URL,
                """
                        {
                          "versions": {
                            "1.21": ["1.21.9"],
                            "1.20": ["1.20.2", "1.20.1"]
                          }
                        }
                        """);
        responses.put(buildsUrl("1.21.9"), builds("1.21.9", 7, "BETA", 6, "ALPHA"));
        responses.put(buildsUrl("1.20.1"), builds("1.20.1", 15, "STABLE", 14, "STABLE"));

        PaperFetcher fetcher = new PaperFetcher(true, new StubHttpClient(responses)) {
            @Override
            public String getInstalledVersion() {
                return "1.20.1";
            }
        };

        fetcher.loadLatestBuildInfo();

        assertEquals("1.20.1", fetcher.getLatestVersion());
        assertEquals(15, fetcher.getLatestBuild());
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
