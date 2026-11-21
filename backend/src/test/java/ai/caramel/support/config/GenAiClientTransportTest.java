package ai.caramel.support.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;

import com.google.genai.Client;
import com.google.genai.errors.ApiException;
import com.google.genai.errors.ClientException;
import com.google.genai.errors.GenAiIOException;
import com.google.genai.errors.ServerException;
import com.google.genai.types.HttpOptions;
import com.google.genai.types.HttpRetryOptions;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Verifies, against a local stub server and without a real key, the two SDK behaviours the backend
 * depends on:
 *
 * <ul>
 *   <li>{@code HttpOptions.timeout} is in milliseconds (research R-4). A 500 ms timeout against a
 *       3 s response must fail quickly. If the unit were seconds, the call would not return within
 *       the preemptive limit and this test fails.
 *   <li>Provider HTTP 429 and 5xx responses surface as {@link ClientException} and
 *       {@link ServerException}, which {@code ChatErrorMapper} classifies (research R-4, T015).
 * </ul>
 */
class GenAiClientTransportTest {

    private HttpServer server;
    private String baseUrl;
    private Client client;

    @BeforeEach
    void startStubProvider() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            String path = exchange.getRequestURI().getPath();
            if (path.contains("slow-model")) {
                sleep(3_000);
                respond(exchange, 200, "{}");
            } else if (path.contains("limited-model")) {
                respond(exchange, 429, errorBody(429, "RESOURCE_EXHAUSTED"));
            } else if (path.contains("down-model")) {
                respond(exchange, 503, errorBody(503, "UNAVAILABLE"));
            } else {
                respond(exchange, 404, errorBody(404, "NOT_FOUND"));
            }
        });
        server.start();
        baseUrl = "http://127.0.0.1:" + server.getAddress().getPort();
    }

    @AfterEach
    void stopStubProvider() {
        if (client != null) {
            client.close();
        }
        if (server != null) {
            server.stop(0);
        }
    }

    /**
     * A client with the given HTTP timeout. Status-code tests use a generous timeout because a
     * cold JVM's first HTTP call can exceed 500 ms; only the timeout test uses a tight one.
     */
    private Client clientWithTimeoutMillis(int timeoutMillis) {
        client = Client.builder()
                .apiKey("test-key-not-real")
                .httpOptions(HttpOptions.builder()
                        .baseUrl(baseUrl)
                        .timeout(timeoutMillis)
                        .retryOptions(HttpRetryOptions.builder().attempts(1).build())
                        .build())
                .build();
        return client;
    }

    @Test
    void httpTimeoutIsInMillisecondsAndFailsFast() {
        Client tight = clientWithTimeoutMillis(1_000);
        // If the unit were seconds, a 1000 s timeout would wait out the 3 s response and this
        // preemptive limit would fire, failing the test.
        assertTimeoutPreemptively(Duration.ofSeconds(3), () ->
                assertThrows(GenAiIOException.class, () ->
                        tight.models.generateContent("slow-model", "ping", null)));
    }

    @Test
    void providerRateLimitSurfacesAsClientExceptionWith429() {
        Client generous = clientWithTimeoutMillis(10_000);
        ApiException error = assertThrows(ClientException.class, () ->
                generous.models.generateContent("limited-model", "ping", null));
        assertThat(error.code()).isEqualTo(429);
    }

    @Test
    void providerOutageSurfacesAsServerExceptionWith503() {
        Client generous = clientWithTimeoutMillis(10_000);
        ApiException error = assertThrows(ServerException.class, () ->
                generous.models.generateContent("down-model", "ping", null));
        assertThat(error.code()).isEqualTo(503);
    }

    private static String errorBody(int code, String status) {
        return "{\"error\":{\"code\":" + code + ",\"message\":\"stub\",\"status\":\"" + status + "\"}}";
    }

    private static void respond(com.sun.net.httpserver.HttpExchange exchange, int status, String body)
            throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().add("Content-Type", "application/json");
        exchange.sendResponseHeaders(status, bytes.length);
        try (OutputStream out = exchange.getResponseBody()) {
            out.write(bytes);
        }
    }

    private static void sleep(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
