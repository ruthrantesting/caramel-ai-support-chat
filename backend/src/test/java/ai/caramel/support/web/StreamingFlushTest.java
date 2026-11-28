package ai.caramel.support.web;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URI;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.ChatOptions;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import reactor.core.publisher.Flux;

/**
 * Checks over a real HTTP port that tokens are flushed as the model produces them (spec SC-001,
 * research R-9). The model is stubbed to emit one chunk every 400 ms. If the stack buffered the
 * reply, every event would arrive together at the end, and the gap between the first token and
 * the done event would be near zero.
 *
 * <p>The chat is a signed-in request, so the test first signs up over HTTP, the same way the
 * browser does: a visitor's first response gives the CSRF cookie, sign-up is sent with that cookie
 * and header, and the chat request reuses the session cookie. Uses a placeholder key and a mocked
 * model, so it needs no network and no real key.
 */
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
            "spring.ai.google.genai.api-key=test-key-not-real",
            "spring.datasource.url=jdbc:h2:mem:streaming-flush;MODE=PostgreSQL",
            "support.auth.sign-up.per-hour=1000"
        })
class StreamingFlushTest {

    /** One identifier per test: each is owned by the user who first uses it (FR-017). */
    private final String conversation = UUID.randomUUID().toString();
    private static final String PASSWORD = "correct-horse-battery-staple-42";

    @LocalServerPort
    private int port;

    @MockitoBean
    private ChatModel chatModel;

    @BeforeEach
    void stubSlowModel() {
        when(chatModel.getOptions()).thenReturn(ChatOptions.builder().build());
        when(chatModel.stream(any(Prompt.class))).thenReturn(
                Flux.interval(Duration.ofMillis(400)).take(3).map(i -> chunk("tok" + i)));
    }

    @Test
    void tokensArriveWhileTheReplyIsStillBeingGenerated() throws Exception {
        Map<String, Long> firstSeenAtMillis = readEventTimes(signUpAndGetSession());

        long firstToken = firstSeenAtMillis.get("token");
        long done = firstSeenAtMillis.get("done");

        assertThat(done - firstToken)
                .as("gap between first token and done, in ms (buffered output would be ~0)")
                .isGreaterThan(500);
    }

    /**
     * Research A-14 and Constitution IV: a sign-out while a reply is streaming does not cut the reply that
     * is already in progress, and the next request on that session is refused with 401.
     */
    @Test
    void signingOutMidReplyDoesNotCutTheReplyThatIsAlreadyStreaming() throws Exception {
        Session session = signUpAndGetSession();
        String body = "{\"conversationId\":\"" + conversation + "\",\"message\":\"hi\"}";
        HttpURLConnection chat = open(
                "POST", "/api/chat", session.cookieHeader(), session.csrfToken(), body, "text/event-stream");

        List<String> events = new ArrayList<>();
        boolean signedOut = false;
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(chat.getInputStream(), UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                if (line.startsWith("event:")) {
                    String name = line.substring("event:".length()).trim();
                    events.add(name);
                    if ("token".equals(name) && !signedOut) {
                        HttpURLConnection logout = open(
                                "POST", "/api/auth/logout", session.cookieHeader(), session.csrfToken(), null, null);
                        assertThat(logout.getResponseCode()).isEqualTo(204);
                        logout.disconnect();
                        signedOut = true;
                    }
                }
            }
        } finally {
            chat.disconnect();
        }

        assertThat(signedOut).as("the sign-out happened while the reply was streaming").isTrue();
        assertThat(events).contains("done").doesNotContain("error");
        assertThat(events.stream().filter("token"::equals).count()).isEqualTo(3);

        HttpURLConnection after = open(
                "POST", "/api/chat", session.cookieHeader(), session.csrfToken(), body, "text/event-stream");
        assertThat(after.getResponseCode()).as("the next request on the ended session").isEqualTo(401);
        after.disconnect();
    }

    /** A signed-in session: the session cookie, and the CSRF token that must be sent as a header. */
    private record Session(String cookieHeader, String csrfToken) {}

    /** Visitor request, then sign-up with the CSRF cookie and header, as the browser does. */
    private Session signUpAndGetSession() throws Exception {
        HttpURLConnection visit = open("GET", "/api/chat", null, null, null, null);
        String csrf = cookieValue(visit, "XSRF-TOKEN");
        visit.disconnect();

        String email = "stream-" + UUID.randomUUID() + "@example.test";
        String body = "{\"email\":\"" + email + "\",\"password\":\"" + PASSWORD + "\"}";
        HttpURLConnection register = open("POST", "/api/auth/register", "XSRF-TOKEN=" + csrf, csrf, body, null);
        assertThat(register.getResponseCode()).as("sign-up should succeed").isEqualTo(201);
        String session = cookieValue(register, "JSESSIONID");
        // Use the rotated token if sign-up sent one; otherwise the token from the visit still holds.
        String rotated = cookieValue(register, "XSRF-TOKEN");
        String token = rotated != null ? rotated : csrf;
        register.disconnect();

        return new Session("JSESSIONID=" + session + "; XSRF-TOKEN=" + token, token);
    }

    /** Returns, for each SSE event name, the time in ms at which its first line arrived. */
    private Map<String, Long> readEventTimes(Session session) throws Exception {
        String body = "{\"conversationId\":\"" + conversation + "\",\"message\":\"hi\"}";
        HttpURLConnection connection = open(
                "POST", "/api/chat", session.cookieHeader(), session.csrfToken(), body, "text/event-stream");

        Map<String, Long> seen = new HashMap<>();
        long start = System.nanoTime();
        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(connection.getInputStream(), UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                if (line.startsWith("event:")) {
                    String name = line.substring("event:".length()).trim();
                    long elapsed = (System.nanoTime() - start) / 1_000_000L;
                    seen.putIfAbsent(name, elapsed);
                }
            }
        } finally {
            connection.disconnect();
        }
        assertThat(seen).containsKeys("token", "done");
        return seen;
    }

    /**
     * Opens a request to the running app. Sends cookies, a CSRF header, and an Accept header when
     * given. Null means the header is not sent.
     */
    private HttpURLConnection open(
            String method, String path, String cookies, String csrf, String body, String accept)
            throws Exception {
        HttpURLConnection connection = (HttpURLConnection)
                URI.create("http://localhost:" + port + path).toURL().openConnection();
        connection.setRequestMethod(method);
        if (cookies != null) {
            connection.setRequestProperty("Cookie", cookies);
        }
        if (csrf != null) {
            connection.setRequestProperty("X-XSRF-TOKEN", csrf);
        }
        if (accept != null) {
            connection.setRequestProperty("Accept", accept);
        }
        if (body != null) {
            // Headers must be set before the body is written, because writing connects the request.
            connection.setDoOutput(true);
            connection.setRequestProperty("Content-Type", "application/json");
            try (OutputStream out = connection.getOutputStream()) {
                out.write(body.getBytes(UTF_8));
            }
        }
        return connection;
    }

    /** The value of a named cookie in the response's Set-Cookie headers, or null if absent. */
    private static String cookieValue(HttpURLConnection connection, String name) {
        List<String> setCookies = connection.getHeaderFields().get("Set-Cookie");
        if (setCookies == null) {
            return null;
        }
        for (String header : setCookies) {
            String pair = header.split(";", 2)[0];
            if (pair.startsWith(name + "=")) {
                return pair.substring(name.length() + 1);
            }
        }
        return null;
    }

    private static ChatResponse chunk(String text) {
        return new ChatResponse(List.of(new Generation(new AssistantMessage(text))));
    }
}
