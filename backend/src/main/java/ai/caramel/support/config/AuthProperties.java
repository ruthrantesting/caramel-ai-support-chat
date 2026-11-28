package ai.caramel.support.config;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.validation.annotation.Validated;

/**
 * Authentication settings (specs/002-user-auth, research A-7, A-11, A-12). Defaults match spec.md
 * Assumptions and can be overridden in application.properties under {@code support.auth}.
 */
@Validated
@ConfigurationProperties("support.auth")
public record AuthProperties(
        @Valid @DefaultValue Session session,
        @Valid @DefaultValue SignIn signIn,
        @Valid @DefaultValue SignUp signUp,
        @Valid @DefaultValue ResetRequest resetRequest,
        @Valid @DefaultValue Throttle throttle) {

    /** FR-011: 8 hours without activity, or 7 days after sign-in, whichever comes first. */
    public record Session(
            @NotNull @DefaultValue("8h") Duration idle,
            @NotNull @DefaultValue("7d") Duration absolute) {}

    /** Sign-in requests per client address per minute (research A-11). */
    public record SignIn(@Min(1) @DefaultValue("10") int perMinute) {}

    /** Sign-up requests per client address per hour (research A-11). */
    public record SignUp(@Min(1) @DefaultValue("5") int perHour) {}

    /** Password-reset requests per client address per hour (research A-11). */
    public record ResetRequest(@Min(1) @DefaultValue("5") int perHour) {}

    /**
     * Per-email sign-in throttle (FR-023, research A-12): after {@code threshold} failures within
     * {@code window}, the delay starts at {@code baseDelay}, doubles with each further failure, and
     * is capped at {@code maxDelay}.
     */
    public record Throttle(
            @Min(1) @DefaultValue("5") int threshold,
            @NotNull @DefaultValue("15m") Duration window,
            @NotNull @DefaultValue("30s") Duration baseDelay,
            @NotNull @DefaultValue("15m") Duration maxDelay) {}
}
