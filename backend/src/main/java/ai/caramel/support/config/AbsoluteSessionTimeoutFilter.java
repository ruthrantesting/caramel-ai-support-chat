package ai.caramel.support.config;

import ai.caramel.support.auth.SessionLogin;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpSession;
import java.io.IOException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Ends a session once it is older than the absolute limit, counted from sign-in (FR-011, research
 * A-7). The servlet container enforces only the idle limit, so this filter is needed for the 7-day cap.
 *
 * <p>When the limit has passed, the session is invalidated and the security context for this request
 * is cleared, so the request is refused with 401 by the authorization step that follows.
 */
public class AbsoluteSessionTimeoutFilter extends OncePerRequestFilter {

    private final Clock clock;
    private final Duration absoluteLimit;

    public AbsoluteSessionTimeoutFilter(Clock clock, Duration absoluteLimit) {
        this.clock = clock;
        this.absoluteLimit = absoluteLimit;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        HttpSession session = request.getSession(false);
        if (session != null && session.getAttribute(SessionLogin.SIGNED_IN_AT) instanceof Instant signedInAt
                && !clock.instant().isBefore(signedInAt.plus(absoluteLimit))) {
            session.invalidate();
            SecurityContextHolder.clearContext();
        }
        chain.doFilter(request, response);
    }
}
