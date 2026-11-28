package ai.caramel.support;

import ai.caramel.support.config.AuthProperties;
import ai.caramel.support.config.SupportProperties;
import java.time.Duration;

/** Builds {@link SupportProperties} for unit tests, with the reply deadlines chosen by the test. */
public final class TestSettings {

    private TestSettings() {}

    public static SupportProperties withReplyLimits(Duration firstToken, Duration deadline) {
        return new SupportProperties(
                new SupportProperties.Provider(Duration.ofSeconds(55)),
                new SupportProperties.Reply(firstToken, deadline),
                new SupportProperties.Memory(40, Duration.ofHours(2)),
                new SupportProperties.RateLimit(20, false));
    }

    /** The defaults from spec.md Assumptions, for web-slice tests that load the security chain. */
    public static AuthProperties authDefaults() {
        return new AuthProperties(
                new AuthProperties.Session(Duration.ofHours(8), Duration.ofDays(7)),
                new AuthProperties.SignIn(10),
                new AuthProperties.SignUp(5),
                new AuthProperties.ResetRequest(5),
                new AuthProperties.Throttle(5, Duration.ofMinutes(15), Duration.ofSeconds(30), Duration.ofMinutes(15)));
    }

    public static SupportProperties defaults() {
        return withReplyLimits(Duration.ofSeconds(30), Duration.ofSeconds(60));
    }
}
