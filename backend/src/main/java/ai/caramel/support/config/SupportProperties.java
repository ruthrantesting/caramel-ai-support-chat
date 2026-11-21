package ai.caramel.support.config;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.validation.annotation.Validated;

/**
 * Service-level settings for the support chat. Defaults match spec.md and research.md; they can
 * be overridden in application.properties.
 */
@Validated
@ConfigurationProperties("support")
public record SupportProperties(
        @Valid @DefaultValue Provider provider,
        @Valid @DefaultValue Reply reply,
        @Valid @DefaultValue Memory memory,
        @Valid @DefaultValue RateLimit rateLimit) {

    /** HTTP-level timeout applied to each call to the model provider (research R-4). */
    public record Provider(@NotNull @DefaultValue("55s") Duration timeout) {}

    /** Deadlines for one streamed reply (spec FR-015, research R-5). */
    public record Reply(
            @NotNull @DefaultValue("30s") Duration firstTokenTimeout,
            @NotNull @DefaultValue("60s") Duration deadline) {}

    /** Conversation memory bounds (research R-10, R-16). */
    public record Memory(
            @Min(2) @Max(200) @DefaultValue("40") int maxMessages,
            @NotNull @DefaultValue("2h") Duration idleTtl) {}

    /** Per-client message limit (spec FR-016, research R-8). */
    public record RateLimit(
            @Min(1) @Max(1000) @DefaultValue("20") int perMinute,
            @DefaultValue("false") boolean trustForwardedFor) {}
}
