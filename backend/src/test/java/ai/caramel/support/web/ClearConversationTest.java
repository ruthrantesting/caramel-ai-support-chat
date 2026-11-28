package ai.caramel.support.web;

import static ai.caramel.support.AuthTestSupport.USER_A;
import static ai.caramel.support.AuthTestSupport.signedInAs;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.asyncDispatch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.request;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.List;
import java.util.UUID;
import ai.caramel.support.chat.ChatService;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.ChatOptions;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import reactor.core.publisher.Flux;

/**
 * Spec FR-013 and User Story 4: {@code DELETE /api/chat/{id}} clears the conversation's memory
 * (SC-005). The contract returns 204 for an unknown id and 400 for a malformed one.
 */
@SpringBootTest(properties = "spring.ai.google.genai.api-key=test-key-not-real")
@AutoConfigureMockMvc
class ClearConversationTest {

    @Autowired
    private MockMvc mvc;

    @Autowired
    private ChatMemory chatMemory;

    @MockitoBean
    private ChatModel chatModel;

    @Test
    void clearingAnExistingConversationEmptiesItsMemoryAndReturns204() throws Exception {
        String conversation = UUID.randomUUID().toString();
        stubReply("earlier answer");
        MvcResult started = mvc.perform(post("/api/chat").with(signedInAs(USER_A))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(turn(conversation, "an earlier question")))
                .andExpect(request().asyncStarted())
                .andReturn();
        // Finish the stream so the turn is written to memory before the assertion.
        mvc.perform(asyncDispatch(started)).andExpect(status().isOk());
        assertThat(chatMemory.get(ChatService.memoryKey(USER_A.toString(), conversation))).isNotEmpty();

        mvc.perform(delete("/api/chat/" + conversation).with(signedInAs(USER_A))).andExpect(status().isNoContent());

        assertThat(chatMemory.get(ChatService.memoryKey(USER_A.toString(), conversation))).isEmpty();
    }

    @Test
    void clearingAnUnknownConversationReturns404() throws Exception {
        mvc.perform(delete("/api/chat/" + UUID.randomUUID()).with(signedInAs(USER_A))).andExpect(status().isNotFound());
    }

    @Test
    void malformedConversationIdReturns400() throws Exception {
        mvc.perform(delete("/api/chat/not-a-uuid").with(signedInAs(USER_A))).andExpect(status().isBadRequest());
    }

    private void stubReply(String text) {
        when(chatModel.getOptions()).thenReturn(ChatOptions.builder().build());
        when(chatModel.stream(any(Prompt.class))).thenReturn(
                Flux.just(new ChatResponse(List.of(new Generation(new AssistantMessage(text))))));
    }

    private static String turn(String conversation, String message) {
        return "{\"conversationId\":\"" + conversation + "\",\"message\":\"" + message + "\"}";
    }
}
