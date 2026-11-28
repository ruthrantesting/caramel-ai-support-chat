package ai.caramel.support;

import ai.caramel.support.auth.AuthenticatedUser;
import jakarta.servlet.http.Cookie;
import java.util.List;
import java.util.UUID;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

/**
 * Helpers for MockMvc requests (specs/002-user-auth, research A-19). A signed-in request carries the
 * session principal and the CSRF cookie and header the real client sends.
 *
 * <p>The CSRF token is sent as a matching cookie and header rather than with Spring's {@code csrf()}
 * post-processor. That post-processor replaces the shared CSRF repository, and the replacement leaks
 * into the tests that run after it in the same context.
 */
public final class AuthTestSupport {

    public static final UUID USER_A = UUID.fromString("a0000000-0000-4000-8000-00000000000a");
    public static final UUID USER_B = UUID.fromString("b0000000-0000-4000-8000-00000000000b");

    /** Any value works, as long as the cookie and the header carry the same one. */
    public static final String CSRF_TOKEN = "test-csrf-token-0001";

    private AuthTestSupport() {}

    /** A signed-in session for {@code userId}, with the CSRF cookie and header. */
    public static RequestPostProcessor signedInAs(UUID userId) {
        return request -> csrfCookieAndHeader().postProcessRequest(
                signedInWithoutCsrf(userId).postProcessRequest(request));
    }

    /** A signed-in session with no CSRF cookie or header, for the 403 path. */
    public static RequestPostProcessor signedInWithoutCsrf(UUID userId) {
        AuthenticatedUser principal = new AuthenticatedUser(userId, userId + "@example.test");
        Authentication authentication = UsernamePasswordAuthenticationToken.authenticated(
                principal, null, List.of(new SimpleGrantedAuthority("ROLE_USER")));
        return SecurityMockMvcRequestPostProcessors.authentication(authentication);
    }

    /** The CSRF cookie and the {@code X-XSRF-TOKEN} header, with matching values, and no session. */
    public static RequestPostProcessor csrfCookieAndHeader() {
        return request -> {
            request.setCookies(new Cookie("XSRF-TOKEN", CSRF_TOKEN));
            request.addHeader("X-XSRF-TOKEN", CSRF_TOKEN);
            return request;
        };
    }
}
