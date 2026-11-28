package ai.caramel.support.web;

import ai.caramel.support.auth.AuthenticatedUser;
import ai.caramel.support.chat.ChatFailure;
import ai.caramel.support.config.SupportProperties;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.time.Clock;
import java.time.Duration;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;
import tools.jackson.databind.ObjectMapper;

/**
 * Limit on {@code POST /api/chat}: at most {@code support.rate-limit.per-minute} messages in any rolling
 * minute, per signed-in user (FR-021, research A-11). The limit is per user, not per address, so users
 * behind one network do not block each other (SC-009). Over the limit, the caller gets HTTP 429 with
 * {@code Retry-After} and a {@code RATE_LIMITED} body.
 *
 * <p>{@code DELETE} is not limited, so a customer can always start a new chat. This filter runs after
 * the security chain, so the signed-in user is already known here.
 */
@Component
public class RateLimitFilter extends OncePerRequestFilter {

    private static final String CHAT_PATH = "/api/chat";

    private final SupportProperties.RateLimit limits;
    private final Clock clock;
    private final ObjectMapper objectMapper;
    private final SlidingWindowLimiter limiter;

    public RateLimitFilter(SupportProperties properties, Clock clock, ObjectMapper objectMapper) {
        this.limits = properties.rateLimit();
        this.clock = clock;
        this.objectMapper = objectMapper;
        this.limiter = new SlidingWindowLimiter(limits.perMinute(), Duration.ofMinutes(1));
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        return !("POST".equalsIgnoreCase(request.getMethod()) && CHAT_PATH.equals(request.getRequestURI()));
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        long retryAfterSeconds = limiter.admit(key(request), clock.instant());
        if (retryAfterSeconds > 0) {
            reject(response, retryAfterSeconds);
            return;
        }
        chain.doFilter(request, response);
    }

    /** The signed-in user. A request with no user (not expected here) falls back to its address. */
    private String key(HttpServletRequest request) {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication != null && authentication.getPrincipal() instanceof AuthenticatedUser user) {
            return "user:" + user.id();
        }
        return "address:" + ClientAddress.of(request, limits.trustForwardedFor());
    }

    private void reject(HttpServletResponse response, long retryAfterSeconds) throws IOException {
        ChatFailure failure = ChatFailure.rateLimited((int) retryAfterSeconds);
        response.setStatus(HttpStatus.TOO_MANY_REQUESTS.value());
        response.setHeader("Retry-After", String.valueOf(retryAfterSeconds));
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.getWriter().write(objectMapper.writeValueAsString(ApiError.from(failure)));
    }
}
