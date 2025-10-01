package eu.nurkert.neverUp2Late.fetcher;

import eu.nurkert.neverUp2Late.net.HttpClient;

import java.util.Set;

/**
 * Fetcher for Geyser builds using the Modrinth API.
 */
public class GeyserFetcher extends ModrinthFetcher {

    private static final Set<String> SUPPORTED_LOADERS = Set.of("paper", "spigot");

    public GeyserFetcher() {
        this(new HttpClient());
    }

    GeyserFetcher(HttpClient httpClient) {
        super(ModrinthFetcher.builder("geyser")
                        .loaders(SUPPORTED_LOADERS)
                        .requireBuildNumber(true)
                        .build(),
                httpClient);
    }
}
