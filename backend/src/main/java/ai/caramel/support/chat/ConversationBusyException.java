package ai.caramel.support.chat;

/** Thrown synchronously when a second turn starts on a conversation that is still streaming. */
public class ConversationBusyException extends RuntimeException {

    public ConversationBusyException() {
        super(ChatErrorCode.CONVERSATION_BUSY.name(), null, false, false);
    }
}
