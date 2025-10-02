package eu.nurkert.neverUp2Late.net;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class HttpClientTest {

    private HttpServer server;
    private String endpointUrl;

    @BeforeEach
    void setUp() throws IOException {
        server = HttpServer.create(new InetSocketAddress(0), 0);
        server.createContext("/versions", new ConditionalAcceptHandler());
        server.start();
        endpointUrl = "http://localhost:" + server.getAddress().getPort() + "/versions";
    }

    @AfterEach
    void tearDown() {
        if (server != null) {
            server.stop(0);
        }
    }

    @Test
    void returns406WhenAcceptHeaderDoesNotMatchPolicy() {
        HttpClient client = HttpClient.builder()
                .accept("application/vnd.github+json")
                .build();

        assertThrows(HttpException.class, () -> client.get(endpointUrl));
    }

    @Test
    void succeedsWhenAcceptHeaderMatchesPolicy() throws IOException {
        HttpClient client = HttpClient.builder()
                .accept("application/json")
                .build();

        String response = client.get(endpointUrl);
        assertEquals("{\"status\":\"ok\"}", response);
    }

    private static class ConditionalAcceptHandler implements HttpHandler {
        @Override
        public void handle(HttpExchange exchange) throws IOException {
            String accept = exchange.getRequestHeaders().getFirst("Accept");
            byte[] body;
            int statusCode;
            if ("application/json".equals(accept)) {
                body = "{\"status\":\"ok\"}".getBytes(StandardCharsets.UTF_8);
                statusCode = 200;
            } else {
                body = "Not acceptable".getBytes(StandardCharsets.UTF_8);
                statusCode = 406;
            }

            exchange.sendResponseHeaders(statusCode, body.length);
            try (OutputStream os = exchange.getResponseBody()) {
                os.write(body);
            }
        }
    }
}
