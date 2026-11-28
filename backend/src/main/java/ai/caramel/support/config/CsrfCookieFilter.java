package ai.caramel.support.config;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import org.springframework.security.web.csrf.CsrfToken;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Makes sure the {@code XSRF-TOKEN} cookie is written on every response, so a visitor's first call
 * (for example {@code GET /api/auth/me}) already carries a token (research A-5). The cookie holds the
 * CSRF token, not the session credential, so the page script may read it.
 */
public class CsrfCookieFilter extends OncePerRequestFilter {

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        CsrfToken token = (CsrfToken) request.getAttribute(CsrfToken.class.getName());
        if (token != null) {
            token.getToken(); // reading the token makes the repository write the cookie
        }
        chain.doFilter(request, response);
    }
}
