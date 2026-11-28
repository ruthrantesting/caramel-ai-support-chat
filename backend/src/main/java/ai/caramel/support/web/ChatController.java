package ai.caramel.support.web;

import ai.caramel.support.auth.AuthenticatedUser;
import ai.caramel.support.chat.ChatErrorCode;
import ai.caramel.support.chat.ChatEvent;
import ai.caramel.support.chat.ChatFailureException;
import ai.caramel.support.chat.ChatService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Pattern;
import java.util.EnumSet;
import java.util.Set;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.http.codec.ServerSentEvent;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Flux;
import tools.jackson.databind.ObjectMapper;

/**
 * Controller layer: HTTP and SSE only. It validates the request, delegates to
 * {@link ChatService}, and encodes events. It never calls the model directly (Constitution V).
 */
@Validated
@RestController
@RequestMapping("/api/chat")
public class ChatController {

    private static final Set<ChatErrorCode> PRE_STREAM_FAILURES =
            EnumSet.of(ChatErrorCode.PROVIDER_UNAVAILABLE, ChatErrorCode.PROVIDER_RATE_LIMITED);

    private final ChatService chatService;
    private final ObjectMapper objectMapper;

    public ChatController(ChatService chatService, ObjectMapper objectMapper) {
        this.chatService = chatService;
        this.objectMapper = objectMapper;
    }

    /**
     * A provider failure that happens before the first event is reported as an HTTP 503 with an
     * {@link ApiError} body, because no bytes have been sent yet. Any other failure, and any failure
     * after the first event, is an in-stream {@code error} event (research R-12).
     */
    @PostMapping(produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public Flux<ServerSentEvent<String>> chat(
            @Valid @RequestBody ChatRequest request,
            @AuthenticationPrincipal AuthenticatedUser user) {
        return chatService.stream(user.id().toString(), request.conversationId(), request.message())
                .<ServerSentEvent<String>>switchOnFirst((first, events) -> {
                    if (first.hasValue() && first.get() instanceof ChatEvent.Failed failed
                            && PRE_STREAM_FAILURES.contains(failed.failure().code())) {
                        return Flux.error(new ChatFailureException(failed.failure().code()));
                    }
                    return events.map(this::toSse);
                });
    }

    /**
     * New chat: cancels any in-flight reply, clears the conversation's memory, and returns 204. An
     * unknown or another user's identifier returns 404 (contracts/chat-api-auth-changes.md).
     */
    @DeleteMapping("/{conversationId}")
    public ResponseEntity<Void> clear(
            @PathVariable @Pattern(regexp = ChatRequest.CONVERSATION_ID_REGEX) String conversationId,
            @AuthenticationPrincipal AuthenticatedUser user) {
        chatService.clear(user.id().toString(), conversationId);
        return ResponseEntity.noContent().build();
    }

    /**
     * Token text is sent as a JSON string, so newlines in model output cannot break SSE framing
     * (contracts/sse-events.md).
     */
    private ServerSentEvent<String> toSse(ChatEvent event) {
        return switch (event) {
            case ChatEvent.Token token -> ServerSentEvent.<String>builder(
                            objectMapper.writeValueAsString(token.text()))
                    .event("token")
                    .build();
            case ChatEvent.Done done -> ServerSentEvent.<String>builder("{}")
                    .event("done")
                    .build();
            case ChatEvent.Failed failed -> ServerSentEvent.<String>builder(
                            objectMapper.writeValueAsString(ApiError.from(failed.failure())))
                    .event("error")
                    .build();
        };
    }
}
