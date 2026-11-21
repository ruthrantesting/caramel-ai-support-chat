package ai.caramel.support.web;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/**
 * Body of {@code POST /api/chat} (data-model.md, ChatRequest).
 *
 * <p>{@code conversationId} is a canonical UUID chosen by the client. {@code message} is required,
 * is not blank after trimming, and is at most 4,000 characters (spec FR-020).
 */
public record ChatRequest(
        @NotBlank
        @Pattern(
                regexp = ChatRequest.CONVERSATION_ID_REGEX,
                message = "conversationId must be a canonical UUID")
        String conversationId,

        @NotBlank
        @Size(max = ChatRequest.MAX_MESSAGE_CHARS)
        String message) {

    /** Spec FR-020. A constant, because Bean Validation annotations need compile-time values. */
    public static final int MAX_MESSAGE_CHARS = 4000;

    /** Canonical UUID, 36 characters, case-insensitive. Shared with the DELETE path variable. */
    public static final String CONVERSATION_ID_REGEX =
            "^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}$";
}
