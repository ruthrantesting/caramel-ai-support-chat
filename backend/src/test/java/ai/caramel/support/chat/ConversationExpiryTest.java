package ai.caramel.support.chat;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import ai.caramel.support.MutableClock;
import ai.caramel.support.TestSettings;
import java.time.Duration;
import java.time.Instant;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** Research R-16: conversations idle longer than the TTL are cleared; active ones are kept. */
class ConversationExpiryTest {

    private final MutableClock clock = new MutableClock(Instant.parse("2026-11-14T10:00:00Z"));
    private ConversationRegistry registry;
    private ChatService chatService;
    private ConversationExpiryTask task;

    @BeforeEach
    void setUp() {
        registry = new ConversationRegistry(clock);
        chatService = mock(ChatService.class);
        task = new ConversationExpiryTask(registry, chatService, clock, TestSettings.defaults());
    }

    @Test
    void idleConversationIsClearedAndAConversationWithATurnInFlightIsKept() {
        registry.tryBeginTurn("idle");
        registry.endTurn("idle");
        clock.advance(Duration.ofHours(3));
        registry.tryBeginTurn("active"); // still streaming

        task.expireIdleConversations();

        verify(chatService).clear("idle");
        verify(chatService, never()).clear("active");
    }

    @Test
    void recentlyUsedConversationIsKept() {
        registry.tryBeginTurn("recent");
        registry.endTurn("recent");
        clock.advance(Duration.ofHours(1));

        task.expireIdleConversations();

        verify(chatService, never()).clear("recent");
    }
}
