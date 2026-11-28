package ai.caramel.support.web;

import static org.assertj.core.api.Assertions.assertThat;

import ai.caramel.support.MutableClock;
import ai.caramel.support.TestSettings;
import java.time.Duration;
import java.time.Instant;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import tools.jackson.databind.ObjectMapper;

/**
 * Research A-11: sign-in 10 per minute, sign-up 5 per hour, and reset requests 5 per hour, each per
 * client address. Refusals are 429 with Retry-After.
 */
class AuthRateLimitFilterTest {

    private MutableClock clock;
    private AuthRateLimitFilter filter;

    @BeforeEach
    void setUp() {
        clock = new MutableClock(Instant.parse("2026-11-14T10:00:00Z"));
        filter = new AuthRateLimitFilter(
                TestSettings.authDefaults(), TestSettings.defaults(), clock, new ObjectMapper());
    }

    @Test
    void signInIsLimitedToTenPerMinutePerAddressWithARetryTime() throws Exception {
        for (int i = 0; i < 10; i++) {
            assertThat(send("POST", "/api/auth/login", "10.1.0.1").getStatus()).isEqualTo(200);
        }

        MockHttpServletResponse refused = send("POST", "/api/auth/login", "10.1.0.1");

        assertThat(refused.getStatus()).isEqualTo(429);
        assertThat(Integer.parseInt(refused.getHeader("Retry-After"))).isBetween(1, 60);
        assertThat(refused.getContentAsString()).contains("RATE_LIMITED").contains("retryAfterSeconds");
    }

    @Test
    void signUpIsLimitedToFivePerHour() throws Exception {
        for (int i = 0; i < 5; i++) {
            assertThat(send("POST", "/api/auth/register", "10.1.0.2").getStatus()).isEqualTo(200);
        }

        MockHttpServletResponse refused = send("POST", "/api/auth/register", "10.1.0.2");

        assertThat(refused.getStatus()).isEqualTo(429);
        assertThat(Integer.parseInt(refused.getHeader("Retry-After"))).isBetween(1, 3600);
    }

    @Test
    void resetRequestsAreLimitedToFivePerHour() throws Exception {
        for (int i = 0; i < 5; i++) {
            assertThat(send("POST", "/api/auth/password-reset", "10.1.0.3").getStatus()).isEqualTo(200);
        }

        assertThat(send("POST", "/api/auth/password-reset", "10.1.0.3").getStatus()).isEqualTo(429);
    }

    @Test
    void theLimitIsPerClientAddressSoAnotherAddressIsNotAffected() throws Exception {
        for (int i = 0; i < 10; i++) {
            send("POST", "/api/auth/login", "10.1.0.4");
        }

        assertThat(send("POST", "/api/auth/login", "10.1.0.4").getStatus()).isEqualTo(429);
        assertThat(send("POST", "/api/auth/login", "10.1.0.5").getStatus()).isEqualTo(200);
    }

    @Test
    void theSignInWindowPassesAfterAMinute() throws Exception {
        for (int i = 0; i < 10; i++) {
            send("POST", "/api/auth/login", "10.1.0.6");
        }
        clock.advance(Duration.ofSeconds(61));

        assertThat(send("POST", "/api/auth/login", "10.1.0.6").getStatus()).isEqualTo(200);
    }

    @Test
    void otherRequestsAreNotLimitedByThisFilter() throws Exception {
        for (int i = 0; i < 50; i++) {
            assertThat(send("GET", "/api/auth/me", "10.1.0.7").getStatus()).isEqualTo(200);
            assertThat(send("POST", "/api/chat", "10.1.0.7").getStatus()).isEqualTo(200);
        }
    }

    private MockHttpServletResponse send(String method, String path, String remoteAddress) throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest(method, path);
        request.setRemoteAddr(remoteAddress);
        MockHttpServletResponse response = new MockHttpServletResponse();
        filter.doFilter(request, response, new MockFilterChain());
        return response;
    }
}
