package ai.caramel.support.auth;

import static org.assertj.core.api.Assertions.assertThat;

import ai.caramel.support.MutableClock;
import ai.caramel.support.config.AbsoluteSessionTimeoutFilter;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;

/**
 * FR-011: a session ends 7 days after sign-in, however active it is. The filter is tested directly
 * with a {@link MutableClock}, so the test moves time without waiting.
 *
 * <p>The 8-hour idle limit is enforced by the servlet container ({@code server.servlet.session.timeout}),
 * which MockMvc does not run. That limit is checked in quickstart step 15, not here.
 */
class SessionExpiryTest {

    private static final Instant SIGNED_IN = Instant.parse("2026-11-01T09:00:00Z");
    private static final Duration SEVEN_DAYS = Duration.ofDays(7);

    private final MutableClock clock = new MutableClock(SIGNED_IN);
    private final AbsoluteSessionTimeoutFilter filter =
            new AbsoluteSessionTimeoutFilter(clock, Duration.ofDays(7));

    @AfterEach
    void clearContext() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void aSessionSixDaysAndTwentyThreeHoursOldIsKept() throws Exception {
        MockHttpSession session = signedInSession();
        clock.advance(SEVEN_DAYS.minusHours(1));

        run(session);

        assertThat(session.isInvalid()).isFalse();
        assertThat(SecurityContextHolder.getContext().getAuthentication()).isNotNull();
    }

    @Test
    void aSessionSevenDaysAndOneSecondOldIsEndedAndTheRequestIsNotAuthenticated() throws Exception {
        MockHttpSession session = signedInSession();
        clock.advance(SEVEN_DAYS.plusSeconds(1));

        run(session);

        assertThat(session.isInvalid()).isTrue();
        assertThat(SecurityContextHolder.getContext().getAuthentication()).isNull();
    }

    @Test
    void aSessionWithoutASigninTimeIsLeftAlone() throws Exception {
        MockHttpSession session = new MockHttpSession();
        clock.advance(SEVEN_DAYS.plusDays(30));

        run(session);

        assertThat(session.isInvalid()).isFalse();
        assertThat(session.getAttribute(SessionLogin.SIGNED_IN_AT)).isNull();
    }

    private MockHttpSession signedInSession() {
        MockHttpSession session = new MockHttpSession();
        session.setAttribute(SessionLogin.SIGNED_IN_AT, SIGNED_IN);
        SecurityContextHolder.getContext().setAuthentication(UsernamePasswordAuthenticationToken.authenticated(
                new AuthenticatedUser(UUID.randomUUID(), "ana@example.test"),
                null,
                List.of(new SimpleGrantedAuthority("ROLE_USER"))));
        return session;
    }

    private void run(MockHttpSession session) throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/auth/me");
        request.setSession(session);
        filter.doFilter(request, new MockHttpServletResponse(), new MockFilterChain());
    }
}
