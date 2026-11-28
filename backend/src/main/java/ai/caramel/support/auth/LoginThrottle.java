package ai.caramel.support.auth;

import ai.caramel.support.config.AuthProperties;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.stereotype.Component;

/**
 * Per-account sign-in delay (FR-023, research A-12, data-model "Login throttle entry"). The key is the
 * normalized address, registered or not, so the response does not reveal whether it is registered.
 *
 * <p>After {@code threshold} failures, the next attempts are refused until a delay has passed. The delay
 * starts at {@code baseDelay} and doubles with each further failure, up to {@code maxDelay}. A success
 * clears the count. Failures count as consecutive: a failure after a quiet {@code window} starts the
 * count again, so the delay keeps growing while attempts continue.
 */
@Component
public class LoginThrottle {

    private final AuthProperties.Throttle settings;
    private final Clock clock;
    private final Map<String, State> states = new ConcurrentHashMap<>();

    public LoginThrottle(AuthProperties properties, Clock clock) {
        this.settings = properties.throttle();
        this.clock = clock;
    }

    /** The time left before the key may be checked again, or empty when it may be checked now. */
    public Optional<Duration> blockedFor(String key) {
        State state = states.get(key);
        if (state == null || state.blockedUntil == null) {
            return Optional.empty();
        }
        Duration remaining = Duration.between(clock.instant(), state.blockedUntil);
        return remaining.isPositive() ? Optional.of(remaining) : Optional.empty();
    }

    public void recordFailure(String key) {
        Instant now = clock.instant();
        states.compute(key, (k, existing) -> {
            State state = existing == null ? new State() : existing;
            if (state.lastFailureAt != null && Duration.between(state.lastFailureAt, now).compareTo(settings.window()) > 0) {
                state.failures = 0;
            }
            state.failures++;
            state.lastFailureAt = now;
            if (state.failures >= settings.threshold()) {
                state.blockedUntil = now.plus(delayFor(state.failures));
            }
            return state;
        });
    }

    public void recordSuccess(String key) {
        states.remove(key);
    }

    /** The delay after the given number of consecutive failures: base at the threshold, doubling, capped. */
    private Duration delayFor(int failures) {
        int doublings = failures - settings.threshold();
        if (doublings >= 16) {
            return settings.maxDelay();
        }
        Duration delay = settings.baseDelay().multipliedBy(1L << doublings);
        return delay.compareTo(settings.maxDelay()) > 0 ? settings.maxDelay() : delay;
    }

    private static final class State {
        int failures;
        Instant lastFailureAt;
        Instant blockedUntil;
    }
}
