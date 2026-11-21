package ai.caramel.support.chat;

/**
 * A failure that ends a reply or a request. {@code retryAfterSeconds} is set only for
 * {@link ChatErrorCode#RATE_LIMITED}.
 */
public record ChatFailure(ChatErrorCode code, Integer retryAfterSeconds) {

    public static ChatFailure of(ChatErrorCode code) {
        return new ChatFailure(code, null);
    }

    public static ChatFailure rateLimited(int retryAfterSeconds) {
        return new ChatFailure(ChatErrorCode.RATE_LIMITED, retryAfterSeconds);
    }

    public String message() {
        return code.userMessage();
    }
}
