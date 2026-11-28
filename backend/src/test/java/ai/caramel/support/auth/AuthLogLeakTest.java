package ai.caramel.support.auth;

import static org.assertj.core.api.Assertions.assertThat;

import ai.caramel.support.chat.ChatService;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

/**
 * FR-024, SC-006, research A-16: the log never holds a password, a session identifier, or a CSRF
 * token, across sign-up, a failed sign-in, a sign-in, and a sign-out.
 *
 * <p>Runs over a real port, so the session identifier is the one the container issues, and it is read
 * from the {@code Set-Cookie} header the way a browser receives it.
 */
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
            "spring.ai.google.genai.api-key=test-key-not-used",
            "spring.datasource.url=jdbc:h2:mem:auth-log-leak;MODE=PostgreSQL",
            "support.auth.sign-up.per-hour=1000",
            "support.auth.sign-in.per-minute=1000"
        })
@ExtendWith(OutputCaptureExtension.class)
class AuthLogLeakTest {

    private static final String PASSWORD = "correct-horse-battery-staple-42";
    private static final String WRONG_PASSWORD = "a-wrong-passphrase-that-is-long";

    @LocalServerPort
    private int port;

    /** Replaces the chat service, so this test does not depend on the provider. */
    @MockitoBean
    private ChatService chatService;

    @Test
    void theLogHoldsNoPasswordSessionIdOrCsrfTokenAcrossTheAuthFlows(CapturedOutput output) throws Exception {
        HttpURLConnection visit = request("GET", "/api/chat", null, null, null);
        String csrf = cookieValue(visit, "XSRF-TOKEN");
        visit.disconnect();
        String email = "leak-" + UUID.randomUUID() + "@example.test";

        HttpURLConnection register = request("POST", "/api/auth/register", "XSRF-TOKEN=" + csrf, csrf,
                body(email, PASSWORD));
        assertThat(register.getResponseCode()).isEqualTo(201);
        String sessionId = cookieValue(register, "JSESSIONID");
        register.disconnect();

        HttpURLConnection wrong = request("POST", "/api/auth/login", "XSRF-TOKEN=" + csrf, csrf,
                body(email, WRONG_PASSWORD));
        assertThat(wrong.getResponseCode()).isEqualTo(401);
        wrong.disconnect();

        HttpURLConnection login = request("POST", "/api/auth/login", "XSRF-TOKEN=" + csrf, csrf,
                body(email, PASSWORD));
        assertThat(login.getResponseCode()).isEqualTo(200);
        login.disconnect();

        HttpURLConnection logout = request("POST", "/api/auth/logout",
                "JSESSIONID=" + sessionId + "; XSRF-TOKEN=" + csrf, csrf, null);
        assertThat(logout.getResponseCode()).isEqualTo(204);
        logout.disconnect();

        assertThat(sessionId).as("the container issued a session id").isNotBlank();
        String log = output.getAll();
        assertThat(log).doesNotContain(PASSWORD).doesNotContain(WRONG_PASSWORD);
        assertThat(log).doesNotContain(sessionId);
        assertThat(log).doesNotContain(csrf);
    }

    private static String body(String email, String password) {
        return "{\"email\":\"" + email + "\",\"password\":\"" + password + "\"}";
    }

    private HttpURLConnection request(String method, String path, String cookies, String csrf, String body)
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
        if (body != null) {
            connection.setDoOutput(true);
            connection.setRequestProperty("Content-Type", "application/json");
            try (OutputStream out = connection.getOutputStream()) {
                out.write(body.getBytes(StandardCharsets.UTF_8));
            }
        }
        return connection;
    }

    private static String cookieValue(HttpURLConnection connection, String name) {
        List<String> setCookies = connection.getHeaderFields().get("Set-Cookie");
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
