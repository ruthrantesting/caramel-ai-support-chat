package ai.caramel.support.auth;

import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpSession;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Authentication endpoints (contracts/auth-api.openapi.yaml). This controller only maps HTTP to
 * {@link AuthService} and {@link SessionLogin}.
 */
@RestController
@RequestMapping(path = "/api/auth", produces = MediaType.APPLICATION_JSON_VALUE)
public class AuthController {

    private final AuthService auth;
    private final SessionLogin sessionLogin;

    public AuthController(AuthService auth, SessionLogin sessionLogin) {
        this.auth = auth;
        this.sessionLogin = sessionLogin;
    }

    /** Creates the account and signs the new user in. Returns 201 with {@code {id, email}}. */
    @PostMapping("/register")
    public ResponseEntity<AuthenticatedUser> register(
            @RequestBody RegisterRequest body,
            HttpServletRequest request,
            HttpServletResponse response) {
        String email = body == null ? null : body.email();
        String password = body == null ? null : body.password();
        AuthenticatedUser user = auth.register(email, password);
        sessionLogin.open(request, response, user);
        return ResponseEntity.status(HttpStatus.CREATED).body(user);
    }

    /**
     * The signed-in user, to restore state after a reload (FR-010). The authorization rule has already
     * refused visitors with 401, so the principal here is always the signed-in user.
     */
    @GetMapping("/me")
    public ResponseEntity<AuthenticatedUser> me() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        return ResponseEntity.ok((AuthenticatedUser) authentication.getPrincipal());
    }

    /** Checks the credentials and starts a session. Every failure gets the same 401 (FR-008). */
    @PostMapping("/login")
    public ResponseEntity<AuthenticatedUser> login(
            @RequestBody LoginRequest body,
            HttpServletRequest request,
            HttpServletResponse response) {
        AuthenticatedUser user = auth.signIn(body == null ? null : body.email(), body == null ? null : body.password());
        sessionLogin.open(request, response, user);
        return ResponseEntity.ok(user);
    }

    /**
     * Ends the session on the server (FR-012). This sits behind the authentication rule, so a visitor
     * gets 401. CSRF is checked before it. The session is invalidated and the cookie is cleared.
     */
    @PostMapping("/logout")
    public ResponseEntity<Void> logout(HttpServletRequest request, HttpServletResponse response) {
        SecurityContextHolder.clearContext();
        HttpSession session = request.getSession(false);
        if (session != null) {
            session.invalidate();
        }
        Cookie expired = new Cookie("JSESSIONID", "");
        expired.setPath("/");
        expired.setMaxAge(0);
        expired.setHttpOnly(true);
        response.addCookie(expired);
        return ResponseEntity.noContent().build();
    }

    @ExceptionHandler(AuthService.InvalidCredentialsException.class)
    ResponseEntity<AuthApiError> invalidCredentials() {
        return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(AuthApiError.of(AuthErrorCode.INVALID_CREDENTIALS));
    }

    @ExceptionHandler(AuthService.ThrottledException.class)
    ResponseEntity<AuthApiError> throttled(AuthService.ThrottledException ex) {
        return ResponseEntity.status(HttpStatus.TOO_MANY_REQUESTS)
                .header("Retry-After", String.valueOf(ex.retryAfterSeconds()))
                .body(AuthApiError.rateLimited(ex.retryAfterSeconds()));
    }

    @ExceptionHandler(AuthService.ValidationFailedException.class)
    ResponseEntity<AuthApiError> validationFailed(AuthService.ValidationFailedException ex) {
        return ResponseEntity.badRequest().body(AuthApiError.validation(ex.fieldErrors()));
    }

    /**
     * Neutral refusal (FR-006): the message does not say the address is registered, and it points
     * to sign-in and password reset. Which code to use is an open question (tasks.md, Open decisions).
     */
    @ExceptionHandler(AuthService.EmailUnavailableException.class)
    ResponseEntity<AuthApiError> emailUnavailable() {
        return ResponseEntity.status(HttpStatus.CONFLICT).body(AuthApiError.of(AuthErrorCode.EMAIL_UNAVAILABLE));
    }
}
