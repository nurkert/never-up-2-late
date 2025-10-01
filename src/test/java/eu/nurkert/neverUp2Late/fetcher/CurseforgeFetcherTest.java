package eu.nurkert.neverUp2Late.fetcher;

import eu.nurkert.neverUp2Late.net.HttpClient;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.time.Duration;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;

class CurseforgeFetcherTest {

    @Test
    void selectsLatestApprovedFileMatchingGameVersions() throws Exception {
        Map<String, String> responses = Map.of(
                "https://api.curseforge.com/v1/mods/12345/files?pageSize=50&index=0",
                """
                        {
                          \"data\": [
                            {
                              \"id\": 2000,
                              \"displayName\": \"Plugin 1.1.0\",
                              \"fileName\": \"plugin-1.1.0.jar\",
                              \"releaseType\": 1,
                              \"fileStatus\": 4,
                              \"downloadUrl\": \"https://example.com/plugin-1.1.0.jar\",
                              \"fileDate\": \"2024-01-10T10:00:00Z\",
                              \"gameVersions\": [\"1.20\"],
                              \"sortableGameVersions\": [
                                { \"gameVersionTypeId\": 73250 }
                              ]
                            },
                            {
                              \"id\": 2001,
                              \"displayName\": \"Plugin 1.2.0\",
                              \"fileName\": \"plugin-1.2.0.jar\",
                              \"releaseType\": 1,
                              \"fileStatus\": 4,
                              \"downloadUrl\": \"https://example.com/plugin-1.2.0.jar\",
                              \"fileDate\": \"2024-02-05T12:00:00Z\",
                              \"gameVersions\": [\"1.20.1\"],
                              \"sortableGameVersions\": [
                                { \"gameVersionTypeId\": 73250 }
                              ]
                            },
                            {
                              \"id\": 2002,
                              \"displayName\": \"Plugin 1.2.1\",
                              \"fileName\": \"plugin-1.2.1.jar\",
                              \"releaseType\": 1,
                              \"fileStatus\": 4,
                              \"downloadUrl\": \"https://example.com/plugin-1.2.1.jar\",
                              \"fileDate\": \"2024-03-01T12:00:00Z\",
                              \"gameVersions\": [\"1.19.4\"],
                              \"sortableGameVersions\": [
                                { \"gameVersionTypeId\": 73250 }
                              ]
                            }
                          ],
                          \"pagination\": {
                            \"index\": 0,
                            \"pageSize\": 50,
                            \"resultCount\": 3,
                            \"totalCount\": 3
                          }
                        }
                        """);

        CurseforgeFetcher.Config config = CurseforgeFetcher.builder(12345)
                .gameVersions(List.of("1.20.1"))
                .build();
        CurseforgeFetcher fetcher = new CurseforgeFetcher(config, new StubHttpClient(responses));
        fetcher.loadLatestBuildInfo();

        assertEquals("Plugin 1.2.0", fetcher.getLatestVersion());
        assertEquals("https://example.com/plugin-1.2.0.jar", fetcher.getLatestDownloadUrl());
        assertEquals(2001, fetcher.getLatestBuild());
    }

    @Test
    void respectsReleaseTypePreferences() throws Exception {
        Map<String, String> responses = Map.of(
                "https://api.curseforge.com/v1/mods/54321/files?pageSize=25&index=0",
                """
                        {
                          \"data\": [
                            {
                              \"id\": 3000,
                              \"displayName\": \"Plugin 1.2.0\",
                              \"fileName\": \"plugin-1.2.0.jar\",
                              \"releaseType\": 1,
                              \"fileStatus\": 4,
                              \"downloadUrl\": \"https://example.com/plugin-1.2.0.jar\",
                              \"fileDate\": \"2024-01-01T00:00:00Z\"
                            },
                            {
                              \"id\": 3001,
                              \"displayName\": \"Plugin 1.3.0-b42\",
                              \"fileName\": \"plugin-1.3.0-b42.jar\",
                              \"releaseType\": 2,
                              \"fileStatus\": 4,
                              \"downloadUrl\": \"https://example.com/plugin-1.3.0-b42.jar\",
                              \"fileDate\": \"2024-02-20T00:00:00Z\"
                            }
                          ],
                          \"pagination\": {
                            \"index\": 0,
                            \"pageSize\": 25,
                            \"resultCount\": 2,
                            \"totalCount\": 2
                          }
                        }
                        """);

        CurseforgeFetcher.Config config = CurseforgeFetcher.builder(54321)
                .pageSize(25)
                .releaseTypes(List.of("release", "Beta"))
                .build();
        CurseforgeFetcher fetcher = new CurseforgeFetcher(config, new StubHttpClient(responses));
        fetcher.loadLatestBuildInfo();

        assertEquals("Plugin 1.3.0-b42", fetcher.getLatestVersion());
        assertEquals("https://example.com/plugin-1.3.0-b42.jar", fetcher.getLatestDownloadUrl());
        assertEquals(42, fetcher.getLatestBuild());
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
