package ai.caramel.support.chat;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import ai.caramel.support.TestSettings;
import com.google.genai.errors.ClientException;
import java.io.IOException;
import java.time.Clock;
import java.time.Duration;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.client.advisor.MessageChatMemoryAdvisor;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.ai.chat.memory.MessageWindowChatMemory;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.ChatOptions;
import org.springframework.ai.chat.prompt.Prompt;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Sinks;
import reactor.test.StepVerifier;

/**
 * Failure, timeout, and cancellation behaviour of {@link ChatService} (spec User Story 5, FR-014,
 * FR-015, FR-018, FR-017; research R-5, R-11). Reply deadlines are short so the tests run in real
 * time without a clock abstraction.
 */
class ChatServiceFailureTest {

    private static final String CONVERSATION = UUID.randomUUID().toString();

    private ChatModel chatModel;
    private ChatMemory memory;
    private ChatService service;

    @BeforeEach
    void setUp() {
        chatModel = mock(ChatModel.class);
        when(chatModel.getOptions()).thenReturn(ChatOptions.builder().build());
        memory = MessageWindowChatMemory.builder().maxMessages(40).build();
        ChatClient client = ChatClient.builder(chatModel)
                .defaultAdvisors(MessageChatMemoryAdvisor.builder(memory).build())
                .build();
        service = new ChatService(client, memory, new ChatErrorMapper(),
                new ConversationRegistry(Clock.systemUTC()),
                TestSettings.withReplyLimits(Duration.ofMillis(300), Duration.ofMillis(1000)));
    }

    @Test
    void providerUnavailableIsMappedWithoutProviderText() {
        when(chatModel.stream(any(Prompt.class))).thenReturn(
                Flux.error(new IOException("connect to internal-gateway.example:443 refused")));

        StepVerifier.create(service.stream(CONVERSATION, "hi"))
                .assertNext(event -> {
                    ChatFailure failure = ((ChatEvent.Failed) event).failure();
                    assertThat(failure.code()).isEqualTo(ChatErrorCode.PROVIDER_UNAVAILABLE);
                    assertThat(failure.message()).doesNotContain("internal-gateway").doesNotContain("refused");
                })
                .verifyComplete();
    }

    @Test
    void providerRateLimitIsMappedToProviderRateLimited() {
        ClientException quota = new ClientException(429, "quota exceeded for project 12345", "RESOURCE_EXHAUSTED");
        when(chatModel.stream(any(Prompt.class))).thenReturn(Flux.error(quota));

        StepVerifier.create(service.stream(CONVERSATION, "hi"))
                .assertNext(event -> assertThat(((ChatEvent.Failed) event).failure().code())
                        .isEqualTo(ChatErrorCode.PROVIDER_RATE_LIMITED))
                .verifyComplete();
    }

    @Test
    void noFirstTokenWithinTheFirstTokenDeadlineEndsAsTimeout() {
        Sinks.Many<ChatResponse> silentModel = Sinks.many().unicast().onBackpressureBuffer();
        when(chatModel.stream(any(Prompt.class))).thenReturn(silentModel.asFlux());

        StepVerifier.create(service.stream(CONVERSATION, "hi"))
                .assertNext(event -> assertThat(((ChatEvent.Failed) event).failure().code())
                        .isEqualTo(ChatErrorCode.TIMEOUT))
                .verifyComplete();
    }

    @Test
    void replyThatRunsPastTheOverallDeadlineEndsAsTimeoutAfterItsTokens() {
        when(chatModel.stream(any(Prompt.class))).thenReturn(
                Flux.interval(Duration.ofMillis(100)).map(i -> chunk("tok" + i)));

        StepVerifier.create(service.stream(CONVERSATION, "hi"))
                .thenConsumeWhile(event -> event instanceof ChatEvent.Token)
                .assertNext(event -> assertThat(((ChatEvent.Failed) event).failure().code())
                        .isEqualTo(ChatErrorCode.TIMEOUT))
                .verifyComplete();
    }

    @Test
    void failureAfterSomeTokensKeepsThePartialTextAndReportsFailure() {
        when(chatModel.stream(any(Prompt.class))).thenReturn(
                Flux.concat(Flux.just(chunk("partial")), Flux.error(new IOException("dropped"))));

        StepVerifier.create(service.stream(CONVERSATION, "hi"))
                .expectNext(new ChatEvent.Token("partial"))
                .assertNext(event -> assertThat(((ChatEvent.Failed) event).failure().code())
                        .isEqualTo(ChatErrorCode.PROVIDER_UNAVAILABLE))
                .verifyComplete();
    }

    @Test
    void failedTurnLeavesMemoryAsItWasBeforeTheTurn() {
        String conversation = UUID.randomUUID().toString();
        when(chatModel.stream(any(Prompt.class))).thenReturn(
                Flux.just(chunk("first answer")),
                Flux.error(new IOException("boom")));

        service.stream(conversation, "first question").blockLast();
        service.stream(conversation, "second question").blockLast();

        List<String> texts = memory.get(conversation).stream().map(Message::getText).toList();
        assertThat(texts).containsExactly("first question", "first answer");
    }

    private static ChatResponse chunk(String text) {
        return new ChatResponse(List.of(new Generation(new AssistantMessage(text))));
    }
}
