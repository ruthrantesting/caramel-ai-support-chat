package ai.caramel.support.chat;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.ChatOptions;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.mockito.ArgumentCaptor;
import reactor.core.publisher.Flux;

/**
 * Spec FR-006: a message in one conversation must not influence replies in another. Uses the real
 * memory and service wiring, with the model stubbed.
 */
@SpringBootTest(properties = "spring.ai.google.genai.api-key=test-key-not-real")
class ConversationIsolationTest {

    @Autowired
    private ChatService chatService;

    private static final String USER = "user-a";

    @Autowired
    private ChatMemory chatMemory;

    @MockitoBean
    private ChatModel chatModel;

    @BeforeEach
    void stubModel() {
        when(chatModel.getOptions()).thenReturn(ChatOptions.builder().build());
        // First call (conversation A) gets one reply, second call (conversation B) another.
        when(chatModel.stream(any(Prompt.class))).thenReturn(
                Flux.just(chunk("reply-from-a")),
                Flux.just(chunk("reply-from-b")));
    }

    @Test
    void secondConversationPromptAndMemoryContainNoMessagesFromTheFirst() {
        String first = UUID.randomUUID().toString();
        String second = UUID.randomUUID().toString();

        chatService.stream(USER, first, "alpha-question").blockLast();
        chatService.stream(USER, second, "beta-question").blockLast();

        ArgumentCaptor<Prompt> prompts = ArgumentCaptor.forClass(Prompt.class);
        verify(chatModel, times(2)).stream(prompts.capture());
        List<String> sentToModelForSecond = prompts.getAllValues().get(1).getInstructions().stream()
                .map(Message::getText)
                .toList();

        assertThat(sentToModelForSecond).noneMatch(text -> text.contains("alpha-question"));
        assertThat(sentToModelForSecond).noneMatch(text -> text.contains("reply-from-a"));

        assertThat(chatMemory.get(ChatService.memoryKey(USER, second))).extracting(Message::getText)
                .noneMatch(text -> text.contains("alpha-question") || text.contains("reply-from-a"));
        // The second conversation's own reply is recorded, so its memory is not empty.
        assertThat(chatMemory.get(ChatService.memoryKey(USER, second))).extracting(Message::getText).contains("reply-from-b");
    }

    private static ChatResponse chunk(String text) {
        return new ChatResponse(List.of(new Generation(new AssistantMessage(text))));
    }
}
