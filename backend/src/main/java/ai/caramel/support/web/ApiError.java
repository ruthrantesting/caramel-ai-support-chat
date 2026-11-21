package ai.caramel.support.web;

import ai.caramel.support.chat.ChatErrorCode;
import ai.caramel.support.chat.ChatFailure;

/**
 * JSON error body for failures that happen before a stream starts (contracts/chat-api.openapi.yaml).
 * {@code retryAfterSeconds} is set only for {@code RATE_LIMITED}.
 */
public record ApiError(ChatErrorCode code, String message, Integer retryAfterSeconds) {

    public static ApiError of(ChatErrorCode code) {
        return new ApiError(code, code.userMessage(), null);
    }

    public static ApiError from(ChatFailure failure) {
        return new ApiError(failure.code(), failure.message(), failure.retryAfterSeconds());
    }
}
