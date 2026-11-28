package ai.caramel.support.auth;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import ai.caramel.support.chat.ChatService;

/**
 * Constitution VI and FR-013: the session cookie is HttpOnly, SameSite=Lax, and Secure by default,
 * and the session value never appears in the response body (research A-8).
 *
 * <p>Runs over a real port, because the session cookie is written by the servlet container. MockMvc
 * has no container, so it never produces a {@code Set-Cookie} for the session.
 */
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
            // Every test request comes from one address. Raise the auth limits so the flows under test are
            // not cut short; the limits themselves are covered by AuthRateLimitFilterTest.
            "support.auth.sign-up.per-hour=1000",
            "support.auth.sign-in.per-minute=1000",
            "support.auth.reset-request.per-hour=1000",
            "spring.ai.google.genai.api-key=test-key-not-used",
            "spring.datasource.url=jdbc:h2:mem:session-cookie;MODE=PostgreSQL"
        })
class SessionCookieTest {

    @LocalServerPort
    private int port;

    /** Replaces the chat service, so this test does not depend on the provider. */
    @MockitoBean
    private ChatService chatService;

    @Test
    void sessionCookieIsHttpOnlySameSiteLaxAndSecure() throws Exception {
        SignUp result = signUp();

        assertThat(result.sessionSetCookie())
                .as("the register response sets the session cookie")
                .isNotNull()
                .contains("HttpOnly")
                .contains("Secure")
                .contains("SameSite=Lax");
    }

    @Test
    void theSessionIdDoesNotAppearInTheResponseBody() throws Exception {
        SignUp result = signUp();

        assertThat(result.sessionValue()).isNotNull();
        assertThat(result.body()).doesNotContain(result.sessionValue());
    }

    /** One sign-up over HTTP, with the CSRF cookie and header, as the browser sends it. */
    private SignUp signUp() throws Exception {
        // A visitor's first response sets the CSRF cookie (research A-5).
        HttpURLConnection visit = open("GET", "/api/chat", null, null);
        String csrf = cookieValue(visit.getHeaderFields().get("Set-Cookie"), "XSRF-TOKEN");
        visit.disconnect();

        String body = "{\"email\":\"cookie-" + UUID.randomUUID() + "@example.test\","
                + "\"password\":\"correct-horse-battery-staple-42\"}";
        HttpURLConnection register = open("POST", "/api/auth/register", csrf, body);
        assertThat(register.getResponseCode()).isEqualTo(201);
        List<String> setCookies = register.getHeaderFields().get("Set-Cookie");
        String responseBody = readBody(register);
        register.disconnect();

        String sessionSetCookie = null;
        for (String header : setCookies) {
            if (header.startsWith("JSESSIONID=")) {
                sessionSetCookie = header;
            }
        }
        String sessionValue = sessionSetCookie == null
                ? null
                : sessionSetCookie.substring("JSESSIONID=".length()).split(";", 2)[0];
        return new SignUp(sessionSetCookie, sessionValue, responseBody);
    }

    private record SignUp(String sessionSetCookie, String sessionValue, String body) {}

    private HttpURLConnection open(String method, String path, String csrf, String body) throws Exception {
        HttpURLConnection connection = (HttpURLConnection)
                URI.create("http://localhost:" + port + path).toURL().openConnection();
        connection.setRequestMethod(method);
        if (csrf != null) {
            connection.setRequestProperty("Cookie", "XSRF-TOKEN=" + csrf);
            connection.setRequestProperty("X-XSRF-TOKEN", csrf);
        }
        if (body != null) {
            connection.setDoOutput(true);
            connection.setRequestProperty("Content-Type", "application/json");
            try (OutputStream out = connection.getOutputStream()) {
                out.write(body.getBytes(StandardCharsets.UTF_8));
            }
        }
        return connection;
    }

    private static String readBody(HttpURLConnection connection) throws Exception {
        try (InputStream in = connection.getInputStream()) {
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    /** The value of a named cookie among Set-Cookie headers, or null when absent. */
    private static String cookieValue(List<String> setCookies, String name) {
        if (setCookies != null) {
            for (String header : setCookies) {
                if (header.startsWith(name + "=")) {
                    return header.substring(name.length() + 1).split(";", 2)[0];
                }
            }
        }
        return null;
    }
}
