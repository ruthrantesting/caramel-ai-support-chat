package ai.caramel.support.chat;

import static ai.caramel.support.AuthTestSupport.USER_A;
import static ai.caramel.support.AuthTestSupport.USER_B;
import static ai.caramel.support.AuthTestSupport.signedInAs;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.asyncDispatch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import ai.caramel.support.AuthTestSupport;
import java.time.Clock;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.prompt.ChatOptions;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.RequestPostProcessor;
import reactor.core.publisher.Flux;

/**
 * User Story 4, FR-017 to FR-019, SC-002: a conversation belongs to the user who first used its
 * identifier. Any other user gets 404, whether the identifier is someone else's or unused, so the
 * response never shows that the identifier exists.
 */
@SpringBootTest(properties = "spring.ai.google.genai.api-key=test-key-not-used")
@AutoConfigureMockMvc
class ConversationOwnershipTest {

    @Autowired
    private MockMvc mvc;

    @MockitoBean
    private ChatModel chatModel;

    @BeforeEach
    void stubModel() {
        when(chatModel.getOptions()).thenReturn(ChatOptions.builder().build());
        when(chatModel.stream(any(Prompt.class))).thenReturn(Flux.just(chunk("reply")));
        clearInvocations(chatModel);
    }

    @Test
    void theOwnerChatsAndAnotherUsersRequestForTheSameIdentifierIsRefusedWithoutCallingTheModel() throws Exception {
        String conversation = UUID.randomUUID().toString();
        assertThat(turn(signedInAs(USER_A), conversation, "mine")).isEqualTo(200);

        assertThat(turn(signedInAs(USER_B), conversation, "theirs")).isEqualTo(404);

        verify(chatModel, times(1)).stream(any(Prompt.class));
    }

    @Test
    void aForeignDeleteIsRefusedAndTheOwnersHistoryIsIntact() throws Exception {
        String conversation = UUID.randomUUID().toString();
        assertThat(turn(signedInAs(USER_A), conversation, "first question")).isEqualTo(200);

        assertThat(mvc.perform(delete("/api/chat/" + conversation).with(signedInAs(USER_B)))
                .andReturn().getResponse().getStatus()).isEqualTo(404);
        assertThat(turn(signedInAs(USER_A), conversation, "follow-up")).isEqualTo(200);

        assertThat(lastPromptText()).contains("first question");
    }

    @Test
    void aForeignDeleteAndADeleteOfAnUnknownIdentifierGiveTheSameResponse() throws Exception {
        String conversation = UUID.randomUUID().toString();
        assertThat(turn(signedInAs(USER_A), conversation, "mine")).isEqualTo(200);

        String foreign = mvc.perform(delete("/api/chat/" + conversation).with(signedInAs(USER_B)))
                .andReturn().getResponse().getContentAsString();
        String unknown = mvc.perform(delete("/api/chat/" + UUID.randomUUID()).with(signedInAs(USER_B)))
                .andReturn().getResponse().getContentAsString();

        assertThat(foreign).isNotBlank().isEqualTo(unknown);
    }

    @Test
    void theOwnerDeletesThenAnotherUserStartsAnEmptyConversationOnTheSameIdentifier() throws Exception {
        String conversation = UUID.randomUUID().toString();
        assertThat(turn(signedInAs(USER_A), conversation, "first")).isEqualTo(200);

        assertThat(mvc.perform(delete("/api/chat/" + conversation).with(signedInAs(USER_A)))
                .andReturn().getResponse().getStatus()).isEqualTo(204);
        assertThat(turn(signedInAs(USER_B), conversation, "hello")).isEqualTo(200);

        assertThat(lastPromptText()).doesNotContain("first");
    }

    /** Thirty-two users race for one new identifier. Exactly one becomes the owner. */
    @Test
    void concurrentFirstUseOfAnIdentifierHasExactlyOneOwner() throws Exception {
        ConversationRegistry registry = new ConversationRegistry(Clock.systemUTC());
        String conversation = UUID.randomUUID().toString();
        int contenders = 32;
        ExecutorService pool = Executors.newFixedThreadPool(contenders);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<Boolean>> results = new ArrayList<>();
        try {
            for (int i = 0; i < contenders; i++) {
                String user = "user-" + i;
                results.add(pool.submit(() -> {
                    start.await();
                    try {
                        registry.claim(user, conversation);
                        return true;
                    } catch (ConversationNotFoundException refused) {
                        return false;
                    }
                }));
            }
            start.countDown();
            int winners = 0;
            for (Future<Boolean> result : results) {
                if (result.get()) {
                    winners++;
                }
            }
            assertThat(winners).isEqualTo(1);
        } finally {
            pool.shutdownNow();
        }
    }

    private int turn(RequestPostProcessor user, String conversation, String message) throws Exception {
        MvcResult started = mvc.perform(post("/api/chat")
                        .with(user)
                        .contentType(MediaType.APPLICATION_JSON)
                        .accept(MediaType.TEXT_EVENT_STREAM)
                        .content("{\"conversationId\":\"" + conversation + "\",\"message\":\"" + message + "\"}"))
                .andReturn();
        if (started.getRequest().isAsyncStarted()) {
            return mvc.perform(asyncDispatch(started)).andReturn().getResponse().getStatus();
        }
        return started.getResponse().getStatus();
    }

    /** The text of every message sent to the model in the most recent call. */
    private String lastPromptText() {
        ArgumentCaptor<Prompt> prompts = ArgumentCaptor.forClass(Prompt.class);
        verify(chatModel, org.mockito.Mockito.atLeastOnce()).stream(prompts.capture());
        List<Prompt> all = prompts.getAllValues();
        return all.get(all.size() - 1).getInstructions().stream().map(Message::getText).reduce("", (a, b) -> a + "\n" + b);
    }

    private static ChatResponse chunk(String text) {
        return new ChatResponse(List.of(new Generation(new AssistantMessage(text))));
    }
}
