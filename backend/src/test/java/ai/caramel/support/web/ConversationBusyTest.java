package ai.caramel.support.web;

import static ai.caramel.support.AuthTestSupport.USER_A;
import static ai.caramel.support.AuthTestSupport.signedInAs;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.asyncDispatch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.request;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.List;
import java.util.UUID;
import org.hamcrest.Matchers;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.ChatOptions;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import reactor.core.publisher.Sinks;

/**
 * Data model, Conversation.turnInFlight: a second turn on a conversation that is still streaming
 * gets HTTP 409 CONVERSATION_BUSY, and is accepted once the first turn has finished.
 */
@SpringBootTest(properties = "spring.ai.google.genai.api-key=test-key-not-real")
@AutoConfigureMockMvc
class ConversationBusyTest {

    @Autowired
    private MockMvc mvc;

    @MockitoBean
    private ChatModel chatModel;

    @Test
    void secondTurnIsRejectedWhileFirstIsStreamingAndAcceptedAfterwards() throws Exception {
        String conversation = UUID.randomUUID().toString();
        when(chatModel.getOptions()).thenReturn(ChatOptions.builder().build());

        // First turn: the model holds the reply open.
        Sinks.Many<ChatResponse> firstReply = Sinks.many().unicast().onBackpressureBuffer();
        when(chatModel.stream(any(Prompt.class))).thenReturn(firstReply.asFlux());
        MvcResult firstTurn = mvc.perform(turn(conversation, "one"))
                .andExpect(request().asyncStarted())
                .andReturn();

        // Second turn while the first is in flight: rejected before any streaming.
        mvc.perform(turn(conversation, "two"))
                .andExpect(status().isConflict())
                .andExpect(content().string(Matchers.containsString("CONVERSATION_BUSY")));

        // Finish the first turn; the next turn on the same conversation is accepted.
        firstReply.tryEmitNext(chunk("answer"));
        firstReply.tryEmitComplete();
        mvc.perform(asyncDispatch(firstTurn)).andExpect(status().isOk());

        Sinks.Many<ChatResponse> thirdReply = Sinks.many().unicast().onBackpressureBuffer();
        when(chatModel.stream(any(Prompt.class))).thenReturn(thirdReply.asFlux());
        MvcResult thirdTurn = mvc.perform(turn(conversation, "three"))
                .andExpect(request().asyncStarted())
                .andReturn();
        thirdReply.tryEmitNext(chunk("again"));
        thirdReply.tryEmitComplete();
        mvc.perform(asyncDispatch(thirdTurn)).andExpect(status().isOk());
    }

    private static org.springframework.test.web.servlet.RequestBuilder turn(String conversation, String message) {
        return post("/api/chat").with(signedInAs(USER_A))
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"conversationId\":\"" + conversation + "\",\"message\":\"" + message + "\"}");
    }

    private static ChatResponse chunk(String text) {
        return new ChatResponse(List.of(new Generation(new AssistantMessage(text))));
    }
}
