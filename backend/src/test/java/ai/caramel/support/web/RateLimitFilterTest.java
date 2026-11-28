package ai.caramel.support.web;

import static org.assertj.core.api.Assertions.assertThat;

import ai.caramel.support.MutableClock;
import ai.caramel.support.TestSettings;
import java.time.Duration;
import java.time.Instant;
import ai.caramel.support.auth.AuthenticatedUser;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import tools.jackson.databind.ObjectMapper;

/** Spec FR-016: 20 messages per minute per client, with 429 and Retry-After over the limit. */
class RateLimitFilterTest {

    private MutableClock clock;
    private RateLimitFilter filter;

    @BeforeEach
    void setUp() {
        clock = new MutableClock(Instant.parse("2026-11-14T10:00:00Z"));
        filter = new RateLimitFilter(TestSettings.defaults(), clock, new ObjectMapper());
    }

    @AfterEach
    void clearSecurityContext() {
        SecurityContextHolder.clearContext();
    }

    /** FR-021 and SC-009: the limit is per signed-in user, so two users on one address do not share it. */
    @Test
    void twoUsersOnOneAddressEachGetTheirOwnTwentyMessages() throws Exception {
        for (int i = 0; i < 20; i++) {
            assertThat(asUser("alice", "10.0.0.7").getStatus()).isEqualTo(200);
        }
        for (int i = 0; i < 20; i++) {
            assertThat(asUser("bob", "10.0.0.7").getStatus()).isEqualTo(200);
        }

        assertThat(asUser("alice", "10.0.0.7").getStatus()).isEqualTo(429);
        assertThat(asUser("bob", "10.0.0.7").getStatus()).isEqualTo(429);
    }

    @Test
    void allowsTwentyMessagesPerMinuteAndRejectsTheTwentyFirst() throws Exception {
        for (int i = 0; i < 20; i++) {
            assertThat(post("10.0.0.1").getStatus()).isEqualTo(200);
        }

        MockHttpServletResponse rejected = post("10.0.0.1");

        assertThat(rejected.getStatus()).isEqualTo(429);
        assertThat(rejected.getHeader("Retry-After")).isNotNull();
        assertThat(Integer.parseInt(rejected.getHeader("Retry-After"))).isBetween(1, 60);
        assertThat(rejected.getContentAsString()).contains("RATE_LIMITED").contains("retryAfterSeconds");
    }

    @Test
    void deleteIsNotLimited() throws Exception {
        for (int i = 0; i < 20; i++) {
            post("10.0.0.2");
        }
        for (int i = 0; i < 25; i++) {
            assertThat(delete("10.0.0.2").getStatus()).isEqualTo(200);
        }
    }

    @Test
    void theWindowSlidesAfterOneMinute() throws Exception {
        for (int i = 0; i < 20; i++) {
            post("10.0.0.3");
        }
        assertThat(post("10.0.0.3").getStatus()).isEqualTo(429);

        clock.advance(Duration.ofSeconds(61));

        assertThat(post("10.0.0.3").getStatus()).isEqualTo(200);
    }

    @Test
    void clientsAreLimitedIndependently() throws Exception {
        for (int i = 0; i < 20; i++) {
            post("10.0.0.4");
        }
        assertThat(post("10.0.0.4").getStatus()).isEqualTo(429);
        assertThat(post("10.0.0.5").getStatus()).isEqualTo(200);
    }

    /** Sends a chat message as a signed-in user, as the security chain would have set the principal. */
    private MockHttpServletResponse asUser(String name, String remoteAddress) throws Exception {
        AuthenticatedUser principal = new AuthenticatedUser(
                UUID.nameUUIDFromBytes(name.getBytes(java.nio.charset.StandardCharsets.UTF_8)), name + "@example.test");
        SecurityContextHolder.getContext().setAuthentication(UsernamePasswordAuthenticationToken.authenticated(
                principal, null, java.util.List.of(new SimpleGrantedAuthority("ROLE_USER"))));
        try {
            return post(remoteAddress);
        } finally {
            SecurityContextHolder.clearContext();
        }
    }

    private MockHttpServletResponse post(String remoteAddress) throws Exception {
        return send("POST", "/api/chat", remoteAddress);
    }

    private MockHttpServletResponse delete(String remoteAddress) throws Exception {
        return send("DELETE", "/api/chat/11111111-1111-4111-8111-111111111111", remoteAddress);
    }

    private MockHttpServletResponse send(String method, String path, String remoteAddress) throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest(method, path);
        request.setRemoteAddr(remoteAddress);
        MockHttpServletResponse response = new MockHttpServletResponse();
        filter.doFilter(request, response, new MockFilterChain());
        return response;
    }
}
