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
import java.util.HashMap;
import java.util.List;
import java.util.Map;
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
 * <p>Uses a placeholder key and a mocked model, so it needs no network and no real key.
 */
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = "spring.ai.google.genai.api-key=test-key-not-real")
class StreamingFlushTest {

    private static final String CONVERSATION = "11111111-1111-4111-8111-111111111111";

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
        Map<String, Long> firstSeenAtMillis = readEventTimes();

        long firstToken = firstSeenAtMillis.get("token");
        long done = firstSeenAtMillis.get("done");

        assertThat(done - firstToken)
                .as("gap between first token and done, in ms (buffered output would be ~0)")
                .isGreaterThan(500);
    }

    /** Returns, for each SSE event name, the time in ms at which its first line arrived. */
    private Map<String, Long> readEventTimes() throws Exception {
        HttpURLConnection connection = (HttpURLConnection)
                URI.create("http://localhost:" + port + "/api/chat").toURL().openConnection();
        connection.setRequestMethod("POST");
        connection.setDoOutput(true);
        connection.setRequestProperty("Content-Type", "application/json");
        connection.setRequestProperty("Accept", "text/event-stream");
        String body = "{\"conversationId\":\"" + CONVERSATION + "\",\"message\":\"hi\"}";
        try (OutputStream out = connection.getOutputStream()) {
            out.write(body.getBytes(UTF_8));
        }

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

    private static ChatResponse chunk(String text) {
        return new ChatResponse(List.of(new Generation(new AssistantMessage(text))));
    }
}
