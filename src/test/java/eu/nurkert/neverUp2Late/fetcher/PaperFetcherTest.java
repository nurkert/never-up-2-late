package eu.nurkert.neverUp2Late.fetcher;

import eu.nurkert.neverUp2Late.net.HttpClient;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.time.Duration;
import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class PaperFetcherTest {

    @Test
    void loadsLatestStableBuildFromApiResponses() throws Exception {
        Map<String, String> responses = new HashMap<>();
        responses.put("https://api.papermc.io/v2/projects/paper",
                """
                        {
                          "versions": ["1.19.4", "1.20", "1.20.1", "1.20.2-rc1"]
                        }
                        """);
        responses.put("https://api.papermc.io/v2/projects/paper/versions/1.20.1",
                """
                        {
                          "builds": [14, 15, 16]
                        }
                        """);

        PaperFetcher fetcher = new PaperFetcher(true, new StubHttpClient(responses));
        fetcher.loadLatestBuildInfo();

        assertEquals("1.20.1", fetcher.getLatestVersion());
        assertEquals(16, fetcher.getLatestBuild());
        assertEquals("https://api.papermc.io/v2/projects/paper/versions/1.20.1/builds/16/downloads/paper-1.20.1-16.jar",
                fetcher.getLatestDownloadUrl());
    }

    @Test
    void includesUnstableVersionsWhenRequested() throws Exception {
        Map<String, String> responses = new HashMap<>();
        responses.put("https://api.papermc.io/v2/projects/paper",
                """
                        {
                          "versions": ["1.20.1", "1.20.2-rc1"]
                        }
                        """);
        responses.put("https://api.papermc.io/v2/projects/paper/versions/1.20.2-rc1",
                """
                        {
                          "builds": [1, 2]
                        }
                        """);

        PaperFetcher fetcher = new PaperFetcher(false, new StubHttpClient(responses));
        fetcher.loadLatestBuildInfo();

        assertEquals("1.20.2-rc1", fetcher.getLatestVersion());
        assertEquals(2, fetcher.getLatestBuild());
    }

    @Test
    void throwsWhenBuildInformationMissing() {
        Map<String, String> responses = new HashMap<>();
        responses.put("https://api.papermc.io/v2/projects/paper",
                """
                        {
                          "versions": ["1.20.1"]
                        }
                        """);
        // Missing build information

        PaperFetcher fetcher = new PaperFetcher(true, new StubHttpClient(responses));
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
