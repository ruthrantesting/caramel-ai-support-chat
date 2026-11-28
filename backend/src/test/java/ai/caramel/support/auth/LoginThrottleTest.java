package ai.caramel.support.auth;

import static org.assertj.core.api.Assertions.assertThat;

import ai.caramel.support.MutableClock;
import ai.caramel.support.TestSettings;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * FR-023 and research A-12: five failed sign-ins for one address start a delay of 30 seconds. Each
 * further failure doubles it, up to 15 minutes. A success clears the count.
 */
class LoginThrottleTest {

    private static final String EMAIL = "ana@example.com";

    private MutableClock clock;
    private LoginThrottle throttle;

    @BeforeEach
    void setUp() {
        clock = new MutableClock(Instant.parse("2026-11-14T10:00:00Z"));
        throttle = new LoginThrottle(TestSettings.authDefaults(), clock);
    }

    @Test
    void theFifthFailureStartsAThirtySecondDelay() {
        for (int i = 0; i < 4; i++) {
            throttle.recordFailure(EMAIL);
        }
        assertThat(throttle.blockedFor(EMAIL)).isEmpty();

        throttle.recordFailure(EMAIL);

        assertThat(throttle.blockedFor(EMAIL)).contains(Duration.ofSeconds(30));
    }

    @Test
    void theDelayEndsWhenItsTimeHasPassed() {
        for (int i = 0; i < 5; i++) {
            throttle.recordFailure(EMAIL);
        }

        clock.advance(Duration.ofSeconds(31));

        assertThat(throttle.blockedFor(EMAIL)).isEmpty();
    }

    @Test
    void eachFurtherFailureDoublesTheDelayUpToFifteenMinutes() {
        for (int i = 0; i < 5; i++) {
            throttle.recordFailure(EMAIL);
        }
        long[] expectedSeconds = {60, 120, 240, 480, 900, 900};
        for (long expected : expectedSeconds) {
            clock.advance(throttle.blockedFor(EMAIL).orElseThrow());
            throttle.recordFailure(EMAIL);

            assertThat(throttle.blockedFor(EMAIL)).contains(Duration.ofSeconds(expected));
        }
    }

    @Test
    void aSuccessClearsTheCount() {
        for (int i = 0; i < 3; i++) {
            throttle.recordFailure(EMAIL);
        }
        throttle.recordSuccess(EMAIL);

        for (int i = 0; i < 4; i++) {
            throttle.recordFailure(EMAIL);
        }

        assertThat(throttle.blockedFor(EMAIL)).isEmpty();
    }

    @Test
    void failuresAfterAFifteenMinuteQuietPeriodStartAgain() {
        for (int i = 0; i < 4; i++) {
            throttle.recordFailure(EMAIL);
        }
        clock.advance(Duration.ofMinutes(16));

        throttle.recordFailure(EMAIL);

        assertThat(throttle.blockedFor(EMAIL)).isEmpty();
    }

    @Test
    void eachAddressIsTrackedSeparately() {
        for (int i = 0; i < 5; i++) {
            throttle.recordFailure(EMAIL);
        }

        assertThat(throttle.blockedFor("other@example.com")).isEqualTo(Optional.empty());
    }
}
