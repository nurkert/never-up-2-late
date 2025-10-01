package eu.nurkert.neverUp2Late.net;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Map;
import java.util.Objects;

/**
 * Lightweight HTTP client wrapper that provides sane defaults for timeouts,
 * headers and error handling.
 */
public class HttpClient {

    private static final Duration DEFAULT_CONNECT_TIMEOUT = Duration.ofSeconds(5);
    private static final Duration DEFAULT_REQUEST_TIMEOUT = Duration.ofSeconds(10);
    private static final Map<String, String> DEFAULT_HEADERS = Map.of("User-Agent", "never-up-2-late/1.0");

    private final java.net.http.HttpClient client;
    private final Duration requestTimeout;
    private final Map<String, String> defaultHeaders;

    public HttpClient() {
        this(java.net.http.HttpClient.newBuilder()
                        .connectTimeout(DEFAULT_CONNECT_TIMEOUT)
                        .build(),
                DEFAULT_REQUEST_TIMEOUT,
                DEFAULT_HEADERS);
    }

    /**
     * Creates a new HTTP client that includes the provided headers in addition to the defaults.
     *
     * @param additionalHeaders headers that should be sent with every request
     */
    public HttpClient(Map<String, String> additionalHeaders) {
        this(java.net.http.HttpClient.newBuilder()
                        .connectTimeout(DEFAULT_CONNECT_TIMEOUT)
                        .build(),
                DEFAULT_REQUEST_TIMEOUT,
                mergeHeaders(additionalHeaders));
    }

    protected HttpClient(java.net.http.HttpClient client, Duration requestTimeout, Map<String, String> defaultHeaders) {
        this.client = Objects.requireNonNull(client, "client");
        this.requestTimeout = Objects.requireNonNull(requestTimeout, "requestTimeout");
        this.defaultHeaders = Map.copyOf(defaultHeaders);
    }

    private static Map<String, String> mergeHeaders(Map<String, String> additionalHeaders) {
        if (additionalHeaders == null || additionalHeaders.isEmpty()) {
            return DEFAULT_HEADERS;
        }

        Map<String, String> merged = new java.util.LinkedHashMap<>(DEFAULT_HEADERS);
        for (Map.Entry<String, String> entry : additionalHeaders.entrySet()) {
            if (entry.getKey() != null && entry.getValue() != null) {
                merged.put(entry.getKey(), entry.getValue());
            }
        }
        return Map.copyOf(merged);
    }

    /**
     * Executes an HTTP GET request and returns the response body as a string.
     *
     * @param url the URL to invoke
     * @return response body
     * @throws IOException when the request fails or returns a non-successful status code
     */
    public String get(String url) throws IOException {
        try {
            return doGet(url);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("Request interrupted", e);
        }
    }

    protected String doGet(String url) throws IOException, InterruptedException {
        HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create(url))
                .timeout(requestTimeout)
                .GET();
        defaultHeaders.forEach(builder::header);

        HttpResponse<String> response = client.send(builder.build(), HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        int statusCode = response.statusCode();
        if (statusCode >= 200 && statusCode < 300) {
            return response.body();
        }
        throw new HttpException(url, statusCode, response.body());
    }
}
