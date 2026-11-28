package ai.caramel.support.chat;

import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import org.springframework.stereotype.Component;
import reactor.core.Disposable;

/**
 * Per-conversation bookkeeping held in process memory (data-model.md, Conversation and Conversation
 * ownership binding): the owning user, last activity, whether a turn is in flight, and how to cancel it.
 *
 * <p>This does not store messages. Messages live in Spring AI chat memory. The registry decides who
 * owns an identifier, whether a new turn may start, and when a conversation is idle.
 *
 * <p>Ownership is set once, atomically, by the first accepted request for an identifier (FR-017).
 * A different user is refused with {@link ConversationNotFoundException}, so the response never
 * shows that the identifier exists.
 */
@Component
public class ConversationRegistry {

    /** An identifier together with its owner, as the expiry task needs them. */
    public record OwnedConversation(String userId, String conversationId) {}

    private final Clock clock;
    private final Map<String, Entry> entries = new ConcurrentHashMap<>();

    public ConversationRegistry(Clock clock) {
        this.clock = clock;
    }

    /**
     * Binds the identifier to {@code userId} if it is free, or confirms the existing binding. The check
     * and the bind happen in one atomic step, so two users racing for a new identifier cannot both win.
     *
     * @throws ConversationNotFoundException when another user owns the identifier
     */
    public void claim(String userId, String conversationId) {
        Entry entry = entries.compute(conversationId,
                (id, existing) -> existing != null ? existing : new Entry(userId, clock.instant()));
        if (!entry.ownerUserId.equals(userId)) {
            throw new ConversationNotFoundException();
        }
    }

    /**
     * Marks a turn as in flight. The caller must have claimed the identifier first. Returns
     * {@code false} if a turn is already in flight, in which case the caller rejects the request with
     * CONVERSATION_BUSY.
     */
    public boolean tryBeginTurn(String conversationId) {
        Entry entry = entries.get(conversationId);
        if (entry == null) {
            throw new IllegalStateException("The conversation must be claimed before a turn starts");
        }
        if (!entry.inFlight.compareAndSet(false, true)) {
            return false;
        }
        entry.lastActivityAt = clock.instant();
        return true;
    }

    /** Clears the in-flight flag when a turn completes, fails, or is cancelled. */
    public void endTurn(String conversationId) {
        Entry entry = entries.get(conversationId);
        if (entry != null) {
            entry.turn = null;
            entry.lastActivityAt = clock.instant();
            entry.inFlight.set(false);
        }
    }

    /** Records the cancel handle for the in-flight turn, so New chat can stop it. */
    public void attachTurn(String conversationId, Disposable turn) {
        Entry entry = entries.get(conversationId);
        if (entry != null) {
            entry.turn = turn;
        }
    }

    /**
     * Releases the identifier for {@code userId} (New chat). Cancels any in-flight turn. An unknown
     * identifier and another user's identifier both throw {@link ConversationNotFoundException}, so a
     * caller cannot tell them apart.
     */
    public void release(String userId, String conversationId) {
        Entry entry = entries.get(conversationId);
        if (entry == null || !entry.ownerUserId.equals(userId)) {
            throw new ConversationNotFoundException();
        }
        entries.remove(conversationId, entry);
        if (entry.turn != null) {
            entry.turn.dispose();
        }
    }

    /** Owned conversations with no turn in flight whose last activity is before the cutoff. */
    public List<OwnedConversation> idleBefore(Instant cutoff) {
        List<OwnedConversation> idle = new ArrayList<>();
        entries.forEach((id, entry) -> {
            if (!entry.inFlight.get() && entry.lastActivityAt.isBefore(cutoff)) {
                idle.add(new OwnedConversation(entry.ownerUserId, id));
            }
        });
        return idle;
    }

    /** Whether any conversation is tracked under this id. Used by tests and cleanup. */
    public boolean isTracked(String conversationId) {
        return entries.containsKey(conversationId);
    }

    private static final class Entry {
        final String ownerUserId;
        final AtomicBoolean inFlight = new AtomicBoolean(false);
        volatile Instant lastActivityAt;
        volatile Disposable turn;

        Entry(String ownerUserId, Instant createdAt) {
            this.ownerUserId = ownerUserId;
            this.lastActivityAt = createdAt;
        }
    }
}
