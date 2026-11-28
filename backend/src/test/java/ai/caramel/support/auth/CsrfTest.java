package ai.caramel.support.auth;

import static ai.caramel.support.AuthTestSupport.USER_A;
import static ai.caramel.support.AuthTestSupport.signedInAs;
import static ai.caramel.support.AuthTestSupport.csrfCookieAndHeader;
import static ai.caramel.support.AuthTestSupport.signedInWithoutCsrf;
import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.cookie;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import ai.caramel.support.TestSettings;
import ai.caramel.support.chat.ChatService;
import ai.caramel.support.config.SecurityConfig;
import ai.caramel.support.config.SupportProperties;
import ai.caramel.support.web.ChatController;
import java.time.Clock;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

/**
 * research A-5 and Constitution VI: state-changing requests need the CSRF header and get 403 without
 * it; a visitor gets 401, not 403, when the header is right but there is no session; the first
 * response to a visitor already sets the readable {@code XSRF-TOKEN} cookie.
 */
@WebMvcTest(ChatController.class)
@Import(SecurityConfig.class)
class CsrfTest {

    private static final String CONVERSATION = "11111111-1111-4111-8111-111111111111";

    @Autowired
    private MockMvc mvc;

    @MockitoBean
    private ChatService chatService;

    /** {@code @WebMvcTest} loads the servlet filters, so {@code RateLimitFilter} needs these beans. */
    @TestConfiguration
    static class FilterDependencies {

        @Bean
        SupportProperties supportProperties() {
            return TestSettings.defaults();
        }

        @Bean
        ai.caramel.support.config.AuthProperties authProperties() {
            return TestSettings.authDefaults();
        }

        @Bean
        Clock clock() {
            return Clock.systemUTC();
        }
    }

    @Test
    void signedInDeleteWithoutCsrfHeaderIsRefusedWith403CsrfInvalid() throws Exception {
        mvc.perform(delete("/api/chat/" + CONVERSATION).with(signedInWithoutCsrf(USER_A)))
                .andExpect(status().isForbidden())
                .andExpect(content().string(containsString("CSRF_INVALID")));
    }

    @Test
    void signedInDeleteWithCsrfHeaderIsAccepted() throws Exception {
        mvc.perform(delete("/api/chat/" + CONVERSATION).with(signedInAs(USER_A)))
                .andExpect(status().isNoContent());
    }

    @Test
    void visitorWithCsrfHeaderButNoSessionGets401WithoutWwwAuthenticate() throws Exception {
        mvc.perform(delete("/api/chat/" + CONVERSATION).with(csrfCookieAndHeader()))
                .andExpect(status().isUnauthorized())
                .andExpect(content().string(containsString("UNAUTHENTICATED")))
                .andExpect(header().doesNotExist("WWW-Authenticate"));
    }

    @Test
    void firstVisitorResponseSetsAReadableXsrfTokenCookie() throws Exception {
        mvc.perform(get("/api/chat"))
                .andExpect(cookie().exists("XSRF-TOKEN"))
                .andExpect(cookie().httpOnly("XSRF-TOKEN", false));
    }
}
