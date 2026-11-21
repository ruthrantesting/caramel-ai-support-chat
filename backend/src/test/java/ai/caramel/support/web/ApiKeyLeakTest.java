package ai.caramel.support.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.asyncDispatch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.prompt.ChatOptions;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import reactor.core.publisher.Flux;

/**
 * Constitution II and spec FR-018, FR-019, SC-007: the provider key must not appear in any response
 * or log line, including error responses and a provider error whose text contains the key.
 */
@SpringBootTest(properties = "spring.ai.google.genai.api-key=" + ApiKeyLeakTest.CANARY)
@AutoConfigureMockMvc
@ExtendWith(OutputCaptureExtension.class)
class ApiKeyLeakTest {

    static final String CANARY = "CANARY-KEY-do-not-leak-98765";

    @Autowired
    private MockMvc mvc;

    @MockitoBean
    private ChatModel chatModel;

    @Test
    void keyNeverAppearsInAnyResponseOrLog(CapturedOutput output) throws Exception {
        when(chatModel.getOptions()).thenReturn(ChatOptions.builder().build());
        // A provider error that echoes the key in its message.
        when(chatModel.stream(any(Prompt.class)))
                .thenReturn(Flux.error(new IOException("authentication failed for key " + CANARY)));

        String conversation = UUID.randomUUID().toString();
        List<String> seen = new ArrayList<>();
        seen.add(record(mvc.perform(turn(conversation, "a question")).andReturn()));
        seen.add(record(mvc.perform(post("/api/chat")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{not json")).andReturn()));
        seen.add(record(mvc.perform(delete("/api/chat/not-a-uuid")).andReturn()));
        seen.add(record(mvc.perform(delete("/api/chat/" + conversation)).andReturn()));
        for (int i = 0; i < 25; i++) {
            seen.add(record(mvc.perform(turn(conversation, "burst " + i)).andReturn()));
        }

        assertThat(seen).allSatisfy(response -> assertThat(response).doesNotContain(CANARY));
        assertThat(output.getAll()).doesNotContain(CANARY);
    }

    /** Status, headers, and body of one response, as one string, after any async dispatch. */
    private String record(MvcResult result) throws Exception {
        if (result.getRequest().isAsyncStarted()) {
            result = mvc.perform(asyncDispatch(result)).andReturn();
        }
        StringBuilder text = new StringBuilder();
        text.append(result.getResponse().getStatus()).append('\n');
        for (String name : result.getResponse().getHeaderNames()) {
            text.append(name).append(": ").append(result.getResponse().getHeaderValues(name)).append('\n');
        }
        text.append(result.getResponse().getContentAsString());
        return text.toString();
    }

    private static org.springframework.test.web.servlet.RequestBuilder turn(String conversation, String message) {
        return post("/api/chat")
                .contentType(MediaType.APPLICATION_JSON)
                .accept(MediaType.TEXT_EVENT_STREAM)
                .content("{\"conversationId\":\"" + conversation + "\",\"message\":\"" + message + "\"}");
    }
}
