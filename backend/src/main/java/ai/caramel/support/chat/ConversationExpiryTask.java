package ai.caramel.support.chat;

import ai.caramel.support.config.SupportProperties;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Clears conversations that have been idle longer than {@code support.memory.idle-ttl} (research
 * R-16). Without this, a public endpoint could grow memory without bound. Nothing is persisted, so
 * an expired conversation is simply gone.
 */
@Component
public class ConversationExpiryTask {

    private final ConversationRegistry conversations;
    private final ChatService chatService;
    private final Clock clock;
    private final Duration idleTtl;

    public ConversationExpiryTask(
            ConversationRegistry conversations,
            ChatService chatService,
            Clock clock,
            SupportProperties properties) {
        this.conversations = conversations;
        this.chatService = chatService;
        this.clock = clock;
        this.idleTtl = properties.memory().idleTtl();
    }

    @Scheduled(fixedDelayString = "PT5M", initialDelayString = "PT5M")
    public void expireIdleConversations() {
        Instant cutoff = clock.instant().minus(idleTtl);
        for (String conversationId : conversations.idleBefore(cutoff)) {
            chatService.clear(conversationId);
        }
    }
}
