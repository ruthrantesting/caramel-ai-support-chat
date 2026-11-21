package ai.caramel.support;

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

    public static SupportProperties defaults() {
        return withReplyLimits(Duration.ofSeconds(30), Duration.ofSeconds(60));
    }
}
