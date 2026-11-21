package ai.caramel.support.web;

import ai.caramel.support.chat.ChatErrorCode;
import ai.caramel.support.chat.ChatFailureException;
import ai.caramel.support.chat.ConversationBusyException;
import jakarta.validation.ConstraintViolationException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.HandlerMethodValidationException;

/**
 * Turns request failures into {@link ApiError} bodies. Responses never include stack traces,
 * request payloads, or provider text (spec FR-018). Only the exception class is logged.
 */
@RestControllerAdvice
public class GlobalExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    @ExceptionHandler({
        MethodArgumentNotValidException.class,
        HandlerMethodValidationException.class,
        ConstraintViolationException.class,
        HttpMessageNotReadableException.class
    })
    ResponseEntity<ApiError> invalidRequest() {
        return error(HttpStatus.BAD_REQUEST, ChatErrorCode.INVALID_REQUEST);
    }

    @ExceptionHandler(ChatFailureException.class)
    ResponseEntity<ApiError> chatFailure(ChatFailureException ex) {
        return error(statusFor(ex.code()), ex.code());
    }

    @ExceptionHandler(ConversationBusyException.class)
    ResponseEntity<ApiError> conversationBusy() {
        return error(HttpStatus.CONFLICT, ChatErrorCode.CONVERSATION_BUSY);
    }

    @ExceptionHandler(Exception.class)
    ResponseEntity<ApiError> unexpected(Exception ex) {
        log.warn("Unhandled request failure: {}", ex.getClass().getSimpleName());
        return error(HttpStatus.INTERNAL_SERVER_ERROR, ChatErrorCode.INTERNAL_ERROR);
    }

    /**
     * The chat client sends {@code Accept: text/event-stream}. Setting the content type explicitly
     * stops content negotiation from answering 406 to an error body that is not an event stream.
     */
    /** HTTP status for a failure that is reported before any stream has started. */
    static HttpStatus statusFor(ChatErrorCode code) {
        return switch (code) {
            case INVALID_REQUEST -> HttpStatus.BAD_REQUEST;
            case RATE_LIMITED -> HttpStatus.TOO_MANY_REQUESTS;
            case CONVERSATION_BUSY -> HttpStatus.CONFLICT;
            case PROVIDER_UNAVAILABLE, PROVIDER_RATE_LIMITED -> HttpStatus.SERVICE_UNAVAILABLE;
            case TIMEOUT -> HttpStatus.GATEWAY_TIMEOUT;
            case INTERNAL_ERROR -> HttpStatus.INTERNAL_SERVER_ERROR;
        };
    }

    private static ResponseEntity<ApiError> error(HttpStatus status, ChatErrorCode code) {
        return ResponseEntity.status(status)
                .contentType(MediaType.APPLICATION_JSON)
                .body(ApiError.of(code));
    }
}
