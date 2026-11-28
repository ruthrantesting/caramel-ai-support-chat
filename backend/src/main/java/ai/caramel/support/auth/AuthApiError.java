package ai.caramel.support.auth;

import com.fasterxml.jackson.annotation.JsonInclude;
import java.util.List;

/**
 * JSON error body for the authentication API (contracts/auth-api.openapi.yaml, schema AuthApiError).
 * {@code retryAfterSeconds} is present only for {@code RATE_LIMITED}, and {@code fieldErrors} only
 * for {@code VALIDATION_FAILED}. Absent values are left out of the JSON.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record AuthApiError(
        AuthErrorCode code,
        String message,
        Integer retryAfterSeconds,
        List<FieldError> fieldErrors) {

    /** One invalid field. {@code field} is one of {@code email}, {@code password}, {@code newPassword}. */
    public record FieldError(String field, String message) {}

    public static AuthApiError of(AuthErrorCode code) {
        return new AuthApiError(code, code.userMessage(), null, null);
    }

    public static AuthApiError validation(List<FieldError> fieldErrors) {
        return new AuthApiError(
                AuthErrorCode.VALIDATION_FAILED, AuthErrorCode.VALIDATION_FAILED.userMessage(), null, fieldErrors);
    }

    public static AuthApiError rateLimited(int retryAfterSeconds) {
        return new AuthApiError(AuthErrorCode.RATE_LIMITED, AuthErrorCode.RATE_LIMITED.userMessage(),
                retryAfterSeconds, null);
    }
}
