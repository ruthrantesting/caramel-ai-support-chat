package ai.caramel.support.auth;

/**
 * Error codes for the authentication API (contracts/auth-api.openapi.yaml, schema AuthErrorCode).
 * Each message is fixed, user-safe text. Passwords, tokens, and session values never appear in a
 * message (spec FR-005, FR-024).
 */
public enum AuthErrorCode {

    UNAUTHENTICATED("Please sign in to continue."),
    CSRF_INVALID("Something went wrong with your request. Please try again."),
    INVALID_CREDENTIALS("Email or password is incorrect"),
    VALIDATION_FAILED("Please correct the highlighted fields."),
    EMAIL_UNAVAILABLE(
            "We couldn't create an account with this email. If you already have one, sign in or reset your password."),
    RATE_LIMITED("Too many attempts. Please wait and try again."),
    RESET_TOKEN_INVALID("This reset link is invalid or has expired. Request a new one."),
    INTERNAL_ERROR("Something went wrong. Please try again.");

    private final String userMessage;

    AuthErrorCode(String userMessage) {
        this.userMessage = userMessage;
    }

    public String userMessage() {
        return userMessage;
    }
}
