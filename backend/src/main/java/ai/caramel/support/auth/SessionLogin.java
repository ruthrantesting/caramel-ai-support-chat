package ai.caramel.support.auth;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.time.Clock;
import java.util.List;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.context.SecurityContextRepository;
import org.springframework.stereotype.Component;

/**
 * Starts a signed-in session after sign-up or sign-in (research A-6, A-7). It changes the session
 * ID to prevent fixation, records when the user signed in for the 7-day limit (FR-011), and saves the
 * security context explicitly, because Spring Security 6 and later do not save it for custom login code.
 */
@Component
public class SessionLogin {

    /** Session attribute holding the sign-in instant, read by the absolute-lifetime filter. */
    public static final String SIGNED_IN_AT = "support.auth.signedInAt";

    private static final List<GrantedAuthority> AUTHORITIES =
            List.of(new SimpleGrantedAuthority("ROLE_USER"));

    private final SecurityContextRepository securityContextRepository;
    private final Clock clock;

    public SessionLogin(SecurityContextRepository securityContextRepository, Clock clock) {
        this.securityContextRepository = securityContextRepository;
        this.clock = clock;
    }

    public void open(HttpServletRequest request, HttpServletResponse response, AuthenticatedUser user) {
        request.getSession(true).setAttribute(SIGNED_IN_AT, clock.instant());
        request.changeSessionId();

        SecurityContext context = SecurityContextHolder.createEmptyContext();
        context.setAuthentication(UsernamePasswordAuthenticationToken.authenticated(user, null, AUTHORITIES));
        securityContextRepository.saveContext(context, request, response);
    }
}
