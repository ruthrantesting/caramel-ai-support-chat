package ai.caramel.support.config;

import ai.caramel.support.auth.JsonAccessDeniedHandler;
import ai.caramel.support.auth.JsonAuthenticationEntryPoint;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import jakarta.servlet.DispatcherType;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.context.HttpSessionSecurityContextRepository;
import org.springframework.security.web.context.SecurityContextHolderFilter;
import org.springframework.security.web.context.SecurityContextRepository;
import org.springframework.security.web.csrf.CookieCsrfTokenRepository;
import org.springframework.security.web.csrf.CsrfFilter;
import java.time.Clock;
import tools.jackson.databind.ObjectMapper;

/**
 * Filter chain for the whole app (specs/002-user-auth, plan Structure; Constitution VI).
 *
 * <ul>
 *   <li>Sign-up, sign-in, and the password-reset endpoints are public. Every other {@code /api/**}
 *       request needs a signed-in session, or it gets 401 {@code UNAUTHENTICATED}.</li>
 *   <li>State-changing requests need the {@code X-XSRF-TOKEN} header, or they get 403
 *       {@code CSRF_INVALID}.</li>
 *   <li>Static assets outside {@code /api/} are public, so the single-page app can load.</li>
 * </ul>
 */
@Configuration(proxyBeanMethods = false)
@EnableWebSecurity
public class SecurityConfig {

    @Bean
    SecurityFilterChain securityFilterChain(
            HttpSecurity http,
            ObjectMapper objectMapper,
            SecurityContextRepository securityContextRepository,
            Clock clock,
            AuthProperties authProperties) throws Exception {
        http
                .authorizeHttpRequests(auth -> auth
                        // A streamed reply completes on an async dispatch of the request that was already
                        // authorized. Checking it again would cut a reply in progress when the session ends
                        // (research A-14), so async dispatches are not authorized again.
                        .dispatcherTypeMatchers(DispatcherType.ASYNC).permitAll()
                        .requestMatchers(HttpMethod.POST,
                                "/api/auth/register",
                                "/api/auth/login",
                                "/api/auth/password-reset",
                                "/api/auth/password-reset/confirm").permitAll()
                        .requestMatchers("/api/**").authenticated()
                        .anyRequest().permitAll())
                .csrf(csrf -> csrf
                        .csrfTokenRepository(CookieCsrfTokenRepository.withHttpOnlyFalse())
                        .csrfTokenRequestHandler(new SpaCsrfTokenRequestHandler()))
                .addFilterAfter(new CsrfCookieFilter(), CsrfFilter.class)
                .addFilterAfter(
                        new AbsoluteSessionTimeoutFilter(clock, authProperties.session().absolute()),
                        SecurityContextHolderFilter.class)
                .securityContext(context -> context.securityContextRepository(securityContextRepository))
                .exceptionHandling(exceptions -> exceptions
                        .authenticationEntryPoint(new JsonAuthenticationEntryPoint(objectMapper))
                        .accessDeniedHandler(new JsonAccessDeniedHandler(objectMapper)));
        return http.build();
    }

    /**
     * Stores the security context in the HTTP session. Login code saves the context explicitly
     * (research A-6), so the repository is a named bean that the auth service can use.
     */
    @Bean
    SecurityContextRepository securityContextRepository() {
        return new HttpSessionSecurityContextRepository();
    }
}
