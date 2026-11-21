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
 * Per-conversation bookkeeping held in process memory (data-model.md, Conversation):
 * last activity, whether a turn is in flight, and how to cancel that turn.
 *
 * <p>This does not store messages. Messages live in Spring AI chat memory. The registry only
 * decides whether a new turn may start and when a conversation is idle.
 */
@Component
public class ConversationRegistry {

    private final Clock clock;
    private final Map<String, Entry> entries = new ConcurrentHashMap<>();

    public ConversationRegistry(Clock clock) {
        this.clock = clock;
    }

    /**
     * Marks a turn as in flight. Returns {@code false} if a turn is already in flight for this
     * conversation, in which case the caller must reject the request with CONVERSATION_BUSY.
     */
    public boolean tryBeginTurn(String conversationId) {
        Entry entry = entries.computeIfAbsent(conversationId, id -> new Entry(clock.instant()));
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

    /** Cancels any in-flight turn and forgets the conversation. */
    public void remove(String conversationId) {
        Entry entry = entries.remove(conversationId);
        if (entry != null && entry.turn != null) {
            entry.turn.dispose();
        }
    }

    /** Ids of conversations with no turn in flight whose last activity is before the cutoff. */
    public List<String> idleBefore(Instant cutoff) {
        List<String> idle = new ArrayList<>();
        entries.forEach((id, entry) -> {
            if (!entry.inFlight.get() && entry.lastActivityAt.isBefore(cutoff)) {
                idle.add(id);
            }
        });
        return idle;
    }

    /** Whether any conversation is tracked under this id. Used by tests and cleanup. */
    public boolean isTracked(String conversationId) {
        return entries.containsKey(conversationId);
    }

    private static final class Entry {
        final AtomicBoolean inFlight = new AtomicBoolean(false);
        volatile Instant lastActivityAt;
        volatile Disposable turn;

        Entry(Instant createdAt) {
            this.lastActivityAt = createdAt;
        }
    }
}
