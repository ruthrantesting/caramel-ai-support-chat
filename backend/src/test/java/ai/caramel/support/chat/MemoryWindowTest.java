package ai.caramel.support.chat;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
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
import reactor.core.publisher.Flux;

/**
 * Spec User Story 2, scenario 2: follow-ups still see the first turn after ten or more turns.
 * Research R-10 sets the window to 40 messages (configured in application.properties). The default
 * of 20 would have evicted the first turn at turn 11.
 */
@SpringBootTest(properties = "spring.ai.google.genai.api-key=test-key-not-real")
class MemoryWindowTest {

    private static final String USER = "user-a";

    @Autowired
    private ChatService chatService;

    @Autowired
    private ChatMemory chatMemory;

    @MockitoBean
    private ChatModel chatModel;

    @BeforeEach
    void stubModel() {
        when(chatModel.getOptions()).thenReturn(ChatOptions.builder().build());
        when(chatModel.stream(any(Prompt.class))).thenReturn(Flux.just(chunk("ok")));
    }

    @Test
    void firstTurnStaysInTheWindowAfterTwelveTurns() {
        String conversation = UUID.randomUUID().toString();
        for (int turn = 1; turn <= 12; turn++) {
            chatService.stream(USER, conversation, "question " + turn).blockLast();
        }

        List<String> texts = texts(conversation);
        assertThat(texts).contains("question 1");
        assertThat(texts).hasSizeLessThanOrEqualTo(40);
    }

    @Test
    void oldestWholeTurnsAreEvictedWhenTheWindowIsFull() {
        String conversation = UUID.randomUUID().toString();
        for (int turn = 1; turn <= 25; turn++) {
            chatService.stream(USER, conversation, "question " + turn).blockLast();
        }

        List<String> texts = texts(conversation);
        assertThat(texts).hasSizeLessThanOrEqualTo(40);
        assertThat(texts).doesNotContain("question 1");
        assertThat(texts).contains("question 25");
    }

    private List<String> texts(String conversation) {
        return chatMemory.get(ChatService.memoryKey(USER, conversation)).stream().map(Message::getText).toList();
    }

    private static ChatResponse chunk(String text) {
        return new ChatResponse(List.of(new Generation(new AssistantMessage(text))));
    }
}
