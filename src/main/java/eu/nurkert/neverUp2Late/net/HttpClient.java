package eu.nurkert.neverUp2Late.net;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/**
 * Lightweight HTTP client wrapper that provides sane defaults for timeouts,
 * headers and error handling.
 */
public class HttpClient {

    public static final String DEFAULT_USER_AGENT =
            "Mozilla/5.0 (compatible; NeverUp2Late/1.0; +https://github.com/nurkert/never-up-2-late)";

    private static final Duration DEFAULT_CONNECT_TIMEOUT = Duration.ofSeconds(5);
    private static final Duration DEFAULT_REQUEST_TIMEOUT = Duration.ofSeconds(10);
    private static final Map<String, String> DEFAULT_HEADERS = Map.of(
            "User-Agent", DEFAULT_USER_AGENT,
            "Accept", "application/json"
    );

    private final java.net.http.HttpClient client;
    private final Duration requestTimeout;
    private final Map<String, String> defaultHeaders;

    public HttpClient() {
        this(builder());
    }

    /**
     * Creates a new HTTP client that includes the provided headers in addition to the defaults.
     *
     * @param additionalHeaders headers that should be sent with every request
     */
    public HttpClient(Map<String, String> additionalHeaders) {
        this(builder().headers(additionalHeaders));
    }

    protected HttpClient(java.net.http.HttpClient client, Duration requestTimeout, Map<String, String> defaultHeaders) {
        this.client = Objects.requireNonNull(client, "client");
        this.requestTimeout = Objects.requireNonNull(requestTimeout, "requestTimeout");
        this.defaultHeaders = Map.copyOf(defaultHeaders);
    }

    private HttpClient(Builder builder) {
        this(builder.client, builder.requestTimeout, builder.buildHeaders());
    }

    protected Map<String, String> getDefaultHeaders() {
        return defaultHeaders;
    }

    public static Builder builder() {
        return new Builder();
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

    public static final class Builder {

        private java.net.http.HttpClient client;
        private Duration requestTimeout;
        private final Map<String, String> headers;

        private Builder() {
            this.client = java.net.http.HttpClient.newBuilder()
                    .connectTimeout(DEFAULT_CONNECT_TIMEOUT)
                    .build();
            this.requestTimeout = DEFAULT_REQUEST_TIMEOUT;
            this.headers = new LinkedHashMap<>(DEFAULT_HEADERS);
        }

        public Builder client(java.net.http.HttpClient client) {
            this.client = Objects.requireNonNull(client, "client");
            return this;
        }

        public Builder requestTimeout(Duration requestTimeout) {
            this.requestTimeout = Objects.requireNonNull(requestTimeout, "requestTimeout");
            return this;
        }

        public Builder header(String key, String value) {
            if (key == null) {
                return this;
            }
            if (value == null) {
                headers.remove(key);
            } else {
                headers.put(key, value);
            }
            return this;
        }

        public Builder headers(Map<String, String> additionalHeaders) {
            if (additionalHeaders == null || additionalHeaders.isEmpty()) {
                return this;
            }
            additionalHeaders.forEach(this::header);
            return this;
        }

        public Builder removeHeader(String key) {
            if (key != null) {
                headers.remove(key);
            }
            return this;
        }

        public Builder accept(String mediaType) {
            return header("Accept", mediaType);
        }

        private Map<String, String> buildHeaders() {
            return Map.copyOf(headers);
        }

        public HttpClient build() {
            return new HttpClient(this);
        }
    }
}
