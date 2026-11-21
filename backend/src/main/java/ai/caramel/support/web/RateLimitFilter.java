package ai.caramel.support.web;

import ai.caramel.support.chat.ChatErrorCode;
import ai.caramel.support.chat.ChatFailure;
import ai.caramel.support.config.SupportProperties;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;
import tools.jackson.databind.ObjectMapper;

/**
 * Per-client limit on {@code POST /api/chat} (spec FR-016, research R-8): at most
 * {@code support.rate-limit.per-minute} messages in any rolling minute. Over the limit, the client
 * gets HTTP 429 with {@code Retry-After} and a {@code RATE_LIMITED} body.
 *
 * <p>{@code DELETE} is not limited, so a customer can always start a new chat. State is per
 * instance and in memory, which matches the MVP scope.
 */
@Component
public class RateLimitFilter extends OncePerRequestFilter {

    private static final Duration WINDOW = Duration.ofMinutes(1);
    private static final String CHAT_PATH = "/api/chat";
    private static final int PURGE_EVERY_N_REQUESTS = 256;

    private final SupportProperties.RateLimit limits;
    private final Clock clock;
    private final ObjectMapper objectMapper;
    private final Map<String, Deque<Instant>> windows = new ConcurrentHashMap<>();
    private int requestsSincePurge;

    public RateLimitFilter(SupportProperties properties, Clock clock, ObjectMapper objectMapper) {
        this.limits = properties.rateLimit();
        this.clock = clock;
        this.objectMapper = objectMapper;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        return !("POST".equalsIgnoreCase(request.getMethod()) && CHAT_PATH.equals(request.getRequestURI()));
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        Instant now = clock.instant();
        long retryAfterSeconds = admit(clientKey(request), now);
        if (retryAfterSeconds > 0) {
            reject(response, retryAfterSeconds);
            return;
        }
        purgeIdleWindowsOccasionally(now);
        chain.doFilter(request, response);
    }

    /**
     * Records the request if the client is under the limit. Returns 0 when admitted, or the number
     * of seconds until the client may send again.
     */
    private long admit(String key, Instant now) {
        Deque<Instant> window = windows.computeIfAbsent(key, k -> new ArrayDeque<>());
        synchronized (window) {
            Instant cutoff = now.minus(WINDOW);
            while (!window.isEmpty() && !window.peekFirst().isAfter(cutoff)) {
                window.pollFirst();
            }
            if (window.size() >= limits.perMinute()) {
                long millisUntilFree = Duration.between(now, window.peekFirst().plus(WINDOW)).toMillis();
                return Math.max(1L, (millisUntilFree + 999L) / 1000L); // round up to whole seconds
            }
            window.addLast(now);
            return 0;
        }
    }

    private void reject(HttpServletResponse response, long retryAfterSeconds) throws IOException {
        ChatFailure failure = ChatFailure.rateLimited((int) retryAfterSeconds);
        response.setStatus(HttpStatus.TOO_MANY_REQUESTS.value());
        response.setHeader("Retry-After", String.valueOf(retryAfterSeconds));
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.getWriter().write(objectMapper.writeValueAsString(ApiError.from(failure)));
    }

    /** Client address, or the first forwarded address only when the deployment trusts it. */
    private String clientKey(HttpServletRequest request) {
        if (limits.trustForwardedFor()) {
            String forwarded = request.getHeader("X-Forwarded-For");
            if (forwarded != null && !forwarded.isBlank()) {
                return forwarded.split(",")[0].trim();
            }
        }
        return request.getRemoteAddr();
    }

    /** Drops clients with no requests in the last window, so the map cannot grow without bound. */
    private void purgeIdleWindowsOccasionally(Instant now) {
        if (++requestsSincePurge < PURGE_EVERY_N_REQUESTS) {
            return;
        }
        requestsSincePurge = 0;
        Instant cutoff = now.minus(WINDOW);
        windows.entrySet().removeIf(entry -> {
            synchronized (entry.getValue()) {
                return entry.getValue().isEmpty() || !entry.getValue().peekLast().isAfter(cutoff);
            }
        });
    }
}
