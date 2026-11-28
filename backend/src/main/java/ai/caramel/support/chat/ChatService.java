package ai.caramel.support.chat;

import ai.caramel.support.config.SupportProperties;
import java.util.List;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.ai.chat.messages.Message;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

/**
 * Service layer: runs one customer turn through the support {@link ChatClient} and returns the
 * reply as a stream of {@link ChatEvent}s. Nothing is sent to the model until the returned
 * {@link Flux} is subscribed.
 */
@Service
public class ChatService {

    private final ChatClient chatClient;
    private final ChatMemory chatMemory;
    private final ChatErrorMapper errorMapper;
    private final ConversationRegistry conversations;
    private final SupportProperties.Reply replyLimits;

    public ChatService(
            ChatClient chatClient,
            ChatMemory chatMemory,
            ChatErrorMapper errorMapper,
            ConversationRegistry conversations,
            SupportProperties properties) {
        this.chatClient = chatClient;
        this.chatMemory = chatMemory;
        this.errorMapper = errorMapper;
        this.conversations = conversations;
        this.replyLimits = properties.reply();
    }

    /**
     * Streams the reply to one message in the given conversation. Memory is scoped to
     * {@code conversationId} through {@link ChatMemory#CONVERSATION_ID}.
     *
     * <p>If the turn fails, times out, or is cancelled, the conversation's memory is restored to
     * its state before the turn. A failed user message is therefore not recorded, and a retry does
     * not duplicate it (research R-11).
     *
     * @throws ConversationBusyException synchronously, before any streaming, if a turn is already
     *     in flight for this conversation. The controller maps this to HTTP 409.
     */
    public Flux<ChatEvent> stream(String userId, String conversationId, String message) {
        // Refuses another user's identifier with ConversationNotFoundException before anything runs.
        conversations.claim(userId, conversationId);
        if (!conversations.tryBeginTurn(conversationId)) {
            throw new ConversationBusyException();
        }
        String memoryKey = memoryKey(userId, conversationId);
        List<Message> memoryBeforeTurn = List.copyOf(chatMemory.get(memoryKey));

        Flux<ChatEvent> reply = chatClient.prompt()
                .user(message)
                .advisors(a -> a.param(ChatMemory.CONVERSATION_ID, memoryKey))
                .stream()
                .content()
                .filter(text -> !text.isEmpty())
                .<ChatEvent>map(ChatEvent.Token::new)
                // First-token deadline (spec FR-015, research R-5). Later tokens have no per-item limit.
                .timeout(Mono.delay(replyLimits.firstTokenTimeout()), ignored -> Mono.never())
                // Overall deadline for the whole reply.
                .takeUntilOther(Mono.delay(replyLimits.deadline())
                        .then(Mono.error(() -> new ChatFailureException(ChatErrorCode.TIMEOUT))));

        return reply
                .concatWith(Flux.just(new ChatEvent.Done()))
                .onErrorResume(error -> {
                    restoreMemory(memoryKey, memoryBeforeTurn);
                    return Flux.just(new ChatEvent.Failed(errorMapper.map(error)));
                })
                .doOnSubscribe(subscription -> conversations.attachTurn(conversationId, subscription::cancel))
                // doOnTerminate and doOnCancel run before the terminal signal reaches the caller.
                // doFinally would run after it, so a caller that starts the next turn as soon as
                // the reply completes could still see the turn as in flight.
                .doOnTerminate(() -> conversations.endTurn(conversationId))
                .doOnCancel(() -> {
                    restoreMemory(memoryKey, memoryBeforeTurn);
                    conversations.endTurn(conversationId);
                });
    }

    /**
     * New chat (FR-019): cancels any in-flight turn, releases the identifier, and clears this user's
     * memory for it. Unknown and foreign identifiers throw {@link ConversationNotFoundException}.
     *
     * <p>Order matters. {@code release} cancels the in-flight turn, and that cancel restores the memory
     * from before the turn. Clearing memory after that guarantees nothing comes back.
     */
    public void clear(String userId, String conversationId) {
        conversations.release(userId, conversationId);
        chatMemory.clear(memoryKey(userId, conversationId));
    }

    /**
     * The chat memory key for one user's conversation (data-model, Chat memory key). Two users can
     * never share a history, even if they use the same identifier at different times.
     */
    public static String memoryKey(String userId, String conversationId) {
        return userId + ":" + conversationId;
    }

    private void restoreMemory(String memoryKey, List<Message> memoryBeforeTurn) {
        chatMemory.clear(memoryKey);
        if (!memoryBeforeTurn.isEmpty()) {
            chatMemory.add(memoryKey, memoryBeforeTurn);
        }
    }
}
