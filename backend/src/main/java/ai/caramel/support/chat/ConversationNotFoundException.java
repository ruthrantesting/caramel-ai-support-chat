package ai.caramel.support.chat;

/**
 * The identifier is unknown, or belongs to another user (FR-017). Both cases look the same to the
 * caller, so the response never shows that an identifier exists. Mapped to 404
 * {@code CONVERSATION_NOT_FOUND}.
 */
public class ConversationNotFoundException extends RuntimeException {

    public ConversationNotFoundException() {
        super("Conversation not found");
    }
}
