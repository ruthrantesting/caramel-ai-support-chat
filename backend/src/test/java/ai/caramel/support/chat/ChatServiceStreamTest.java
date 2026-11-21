package ai.caramel.support.chat;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.io.IOException;
import ai.caramel.support.TestSettings;
import java.time.Clock;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.client.advisor.MessageChatMemoryAdvisor;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.ai.chat.memory.MessageWindowChatMemory;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.ChatOptions;
import org.springframework.ai.chat.prompt.Prompt;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Sinks;
import reactor.test.StepVerifier;

/**
 * Streaming behaviour of {@link ChatService} with a stub model: token order, no buffering, and
 * error mapping. Uses no network and no key.
 */
class ChatServiceStreamTest {

    private static final String CONVERSATION = "11111111-1111-4111-8111-111111111111";

    private ChatModel chatModel;
    private ChatService service;

    @BeforeEach
    void setUp() {
        chatModel = mock(ChatModel.class);
        // ChatClient reads the model's options; a bare mock returns null for them.
        when(chatModel.getOptions()).thenReturn(ChatOptions.builder().build());
        ChatMemory memory = MessageWindowChatMemory.builder().maxMessages(40).build();
        ChatClient client = ChatClient.builder(chatModel)
                .defaultAdvisors(MessageChatMemoryAdvisor.builder(memory).build())
                .build();
        service = new ChatService(client, memory, new ChatErrorMapper(),
                new ConversationRegistry(Clock.systemUTC()), TestSettings.defaults());
    }

    @Test
    void emitsTokensInOrderThenDone() {
        when(chatModel.stream(any(Prompt.class))).thenReturn(Flux.just(
                chunk("Hel"), chunk("lo"), chunk("!")));

        StepVerifier.create(service.stream(CONVERSATION, "hi"))
                .expectNext(new ChatEvent.Token("Hel"))
                .expectNext(new ChatEvent.Token("lo"))
                .expectNext(new ChatEvent.Token("!"))
                .expectNext(new ChatEvent.Done())
                .verifyComplete();
    }

    @Test
    void firstTokenIsEmittedBeforeTheModelFinishes() {
        Sinks.Many<ChatResponse> model = Sinks.many().unicast().onBackpressureBuffer();
        when(chatModel.stream(any(Prompt.class))).thenReturn(model.asFlux());

        StepVerifier.create(service.stream(CONVERSATION, "hi"))
                .then(() -> model.tryEmitNext(chunk("first")))
                .expectNext(new ChatEvent.Token("first"))
                .then(() -> model.tryEmitNext(chunk("second")))
                .expectNext(new ChatEvent.Token("second"))
                .then(model::tryEmitComplete)
                .expectNext(new ChatEvent.Done())
                .verifyComplete();
    }

    @Test
    void providerFailureBecomesFailedEventWithoutProviderText() {
        when(chatModel.stream(any(Prompt.class))).thenReturn(
                Flux.error(new IOException("connection to internal-host:443 refused")));

        StepVerifier.create(service.stream(CONVERSATION, "hi"))
                .assertNext(event -> {
                    ChatEvent.Failed failed = (ChatEvent.Failed) event;
                    assertCode(failed, ChatErrorCode.PROVIDER_UNAVAILABLE);
                    assertNoProviderText(failed);
                })
                .verifyComplete();
    }

    private static ChatResponse chunk(String text) {
        return new ChatResponse(List.of(new Generation(new AssistantMessage(text))));
    }

    private static void assertCode(ChatEvent.Failed failed, ChatErrorCode expected) {
        org.assertj.core.api.Assertions.assertThat(failed.failure().code()).isEqualTo(expected);
    }

    private static void assertNoProviderText(ChatEvent.Failed failed) {
        org.assertj.core.api.Assertions.assertThat(failed.failure().message())
                .doesNotContain("internal-host")
                .doesNotContain("refused")
                .isEqualTo(ChatErrorCode.PROVIDER_UNAVAILABLE.userMessage());
    }
}
