package ai.caramel.support.chat;

/**
 * Error codes shared by the API and the UI (contracts/chat-api.openapi.yaml). Each message is
 * fixed, user-safe text owned by the backend. Provider text, stack traces, and payloads are never
 * included (spec FR-018).
 */
public enum ChatErrorCode {

    INVALID_REQUEST(
            "Your message can't be sent. Check that it isn't empty and is 4,000 characters or fewer.",
            false),
    RATE_LIMITED(
            "You're sending messages too quickly. Please wait a moment and try again.",
            true),
    CONVERSATION_BUSY(
            "A reply is still in progress. Please wait for it to finish.",
            true),
    PROVIDER_UNAVAILABLE(
            "The assistant is temporarily unavailable. Please try again shortly.",
            true),
    PROVIDER_RATE_LIMITED(
            "The assistant is busy right now. Please try again shortly.",
            true),
    TIMEOUT(
            "The reply took too long. Please try again.",
            true),
    INTERNAL_ERROR(
            "Something went wrong. Please try again.",
            true);

    private final String userMessage;
    private final boolean retryable;

    ChatErrorCode(String userMessage, boolean retryable) {
        this.userMessage = userMessage;
        this.retryable = retryable;
    }

    public String userMessage() {
        return userMessage;
    }

    public boolean retryable() {
        return retryable;
    }
}
