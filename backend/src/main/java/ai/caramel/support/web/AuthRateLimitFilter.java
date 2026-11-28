package ai.caramel.support.web;

import ai.caramel.support.auth.AuthApiError;
import ai.caramel.support.config.AuthProperties;
import ai.caramel.support.config.SupportProperties;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.time.Clock;
import java.time.Duration;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;
import tools.jackson.databind.ObjectMapper;

/**
 * Per-client-address limits on the auth endpoints (FR-022, research A-11): sign-in 10 per minute, sign-up
 * 5 per hour, and both password-reset paths 5 per hour. Over a limit the caller gets 429 with
 * {@code Retry-After}. Per-account sign-in delays are a separate check (research A-12).
 */
@Component
public class AuthRateLimitFilter extends OncePerRequestFilter {

    private static final String LOGIN = "/api/auth/login";
    private static final String REGISTER = "/api/auth/register";
    private static final String RESET = "/api/auth/password-reset";
    private static final String RESET_CONFIRM = "/api/auth/password-reset/confirm";

    private final SupportProperties.RateLimit support;
    private final Clock clock;
    private final ObjectMapper objectMapper;
    private final Map<String, SlidingWindowLimiter> limiters;

    public AuthRateLimitFilter(
            AuthProperties auth, SupportProperties support, Clock clock, ObjectMapper objectMapper) {
        this.support = support.rateLimit();
        this.clock = clock;
        this.objectMapper = objectMapper;
        SlidingWindowLimiter signIn = new SlidingWindowLimiter(auth.signIn().perMinute(), Duration.ofMinutes(1));
        SlidingWindowLimiter signUp = new SlidingWindowLimiter(auth.signUp().perHour(), Duration.ofHours(1));
        SlidingWindowLimiter reset = new SlidingWindowLimiter(auth.resetRequest().perHour(), Duration.ofHours(1));
        this.limiters = Map.of(LOGIN, signIn, REGISTER, signUp, RESET, reset, RESET_CONFIRM, reset);
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        return !"POST".equalsIgnoreCase(request.getMethod()) || !limiters.containsKey(request.getRequestURI());
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        SlidingWindowLimiter limiter = limiters.get(request.getRequestURI());
        long retryAfterSeconds = limiter.admit(ClientAddress.of(request, support.trustForwardedFor()), clock.instant());
        if (retryAfterSeconds > 0) {
            response.setStatus(HttpStatus.TOO_MANY_REQUESTS.value());
            response.setHeader("Retry-After", String.valueOf(retryAfterSeconds));
            response.setContentType(MediaType.APPLICATION_JSON_VALUE);
            response.getWriter().write(objectMapper.writeValueAsString(AuthApiError.rateLimited((int) retryAfterSeconds)));
            return;
        }
        chain.doFilter(request, response);
    }
}
