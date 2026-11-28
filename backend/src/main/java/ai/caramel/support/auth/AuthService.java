package ai.caramel.support.auth;

import java.time.Clock;
import java.util.ArrayList;
import java.util.List;
import java.time.Duration;
import java.util.Optional;
import java.util.UUID;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.ProviderManager;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.authentication.dao.DaoAuthenticationProvider;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;

/**
 * Account rules for sign-up (specs/002-user-auth, FR-001 to FR-006). Controllers call this service.
 * It holds no session state: opening a session is the controller's job, through {@link SessionLogin}.
 */
@Service
public class AuthService {

    private final UserRepository users;
    private final PasswordEncoder encoder;
    private final PasswordPolicy passwordPolicy;
    private final Clock clock;
    private final AuthenticationManager authenticationManager;
    private final LoginThrottle loginThrottle;

    public AuthService(
            UserRepository users,
            PasswordEncoder encoder,
            PasswordPolicy passwordPolicy,
            Clock clock,
            JdbcUserDetailsService userDetails,
            LoginThrottle loginThrottle) {
        this.users = users;
        this.encoder = encoder;
        this.passwordPolicy = passwordPolicy;
        this.clock = clock;
        this.loginThrottle = loginThrottle;
        // The DAO provider hides "no such user" behind the same BadCredentialsException as a wrong
        // password, and runs a dummy password check for an unknown user, so response time does not
        // reveal registration (research A-6, FR-008).
        DaoAuthenticationProvider provider = new DaoAuthenticationProvider(userDetails);
        provider.setPasswordEncoder(encoder);
        this.authenticationManager = new ProviderManager(provider);
    }

    /**
     * Checks the password and returns the signed-in user. Every failure, including an unknown or
     * malformed address, is the same {@link InvalidCredentialsException} (FR-008). A throttled
     * address is refused earlier, by the login throttle (User Story 5).
     */
    public AuthenticatedUser signIn(String rawEmail, String rawPassword) {
        // The raw email goes to the provider even when malformed, so every path runs the same check.
        String username = rawEmail == null ? "" : rawEmail;
        String password = rawPassword == null ? "" : PasswordPolicy.normalize(rawPassword);
        String throttleKey = username.trim().toLowerCase(java.util.Locale.ROOT);

        // A delayed account is refused before any password check (data-model, Login throttle entry).
        Optional<Duration> blocked = loginThrottle.blockedFor(throttleKey);
        if (blocked.isPresent()) {
            throw new ThrottledException(retryAfterSeconds(blocked.get()));
        }
        try {
            Authentication result = authenticationManager.authenticate(
                    UsernamePasswordAuthenticationToken.unauthenticated(username, password));
            JdbcUserDetailsService.AccountDetails account =
                    (JdbcUserDetailsService.AccountDetails) result.getPrincipal();
            loginThrottle.recordSuccess(throttleKey);
            return new AuthenticatedUser(account.id(), account.email());
        } catch (AuthenticationException failure) {
            loginThrottle.recordFailure(throttleKey);
            throw new InvalidCredentialsException();
        }
    }

    private static int retryAfterSeconds(Duration remaining) {
        return (int) Math.max(1L, (remaining.toMillis() + 999L) / 1000L);
    }

    /** Thrown when the account is in a sign-in delay (FR-023). Mapped to 429 with Retry-After. */
    public static final class ThrottledException extends RuntimeException {
        private final int retryAfterSeconds;

        ThrottledException(int retryAfterSeconds) {
            super("Too many failed sign-in attempts");
            this.retryAfterSeconds = retryAfterSeconds;
        }

        public int retryAfterSeconds() {
            return retryAfterSeconds;
        }
    }

    /**
     * Creates an account and returns it. Field problems are reported together, so the user can fix
     * both fields at once (FR-030).
     *
     * @throws ValidationFailedException when the email or the password breaks a rule; no account is created
     * @throws EmailUnavailableException when the address is already registered (FR-006)
     */
    public AuthenticatedUser register(String rawEmail, String rawPassword) {
        List<AuthApiError.FieldError> errors = new ArrayList<>();
        Optional<String> email = EmailAddress.normalize(rawEmail);
        if (email.isEmpty()) {
            errors.add(new AuthApiError.FieldError("email", "Enter a valid email address."));
        }
        Optional<String> passwordProblem = passwordPolicy.violation(rawPassword);
        passwordProblem.ifPresent(message -> errors.add(new AuthApiError.FieldError("password", message)));
        if (!errors.isEmpty()) {
            throw new ValidationFailedException(errors);
        }

        String normalizedPassword = PasswordPolicy.normalize(rawPassword);
        User user = new User(
                UUID.randomUUID(),
                email.orElseThrow(),
                encoder.encode(normalizedPassword),
                clock.instant());
        if (!users.insert(user)) {
            throw new EmailUnavailableException();
        }
        return new AuthenticatedUser(user.id(), user.email());
    }

    /** Thrown when sign-up fields break a rule. Carries one entry per invalid field. */
    public static final class ValidationFailedException extends RuntimeException {
        private final transient List<AuthApiError.FieldError> fieldErrors;

        ValidationFailedException(List<AuthApiError.FieldError> fieldErrors) {
            super("Sign-up fields are invalid");
            this.fieldErrors = List.copyOf(fieldErrors);
        }

        public List<AuthApiError.FieldError> fieldErrors() {
            return fieldErrors;
        }
    }

    /** Thrown for every sign-in failure. The response is the same whatever the cause (FR-008). */
    public static final class InvalidCredentialsException extends RuntimeException {
        InvalidCredentialsException() {
            super("Email or password is incorrect");
        }
    }

    /** Thrown when the address is already registered. The message never says so (FR-006). */
    public static final class EmailUnavailableException extends RuntimeException {
        EmailUnavailableException() {
            super("Address cannot be used for sign-up");
        }
    }
}
