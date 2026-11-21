package ai.caramel.support.chat;

/**
 * Carries a known {@link ChatErrorCode} through the reactive pipeline, so the mapper can classify
 * errors raised by the service itself (for example, the reply deadline).
 */
public class ChatFailureException extends RuntimeException {

    private final ChatErrorCode code;

    public ChatFailureException(ChatErrorCode code) {
        super(code.name(), null, false, false);
        this.code = code;
    }

    public ChatErrorCode code() {
        return code;
    }
}
