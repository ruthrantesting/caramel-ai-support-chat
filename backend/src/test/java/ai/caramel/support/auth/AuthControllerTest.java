package ai.caramel.support.auth;

import static ai.caramel.support.AuthTestSupport.csrfCookieAndHeader;
import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.cookie;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.test.web.servlet.MockMvc;
import ai.caramel.support.chat.ChatService;

/**
 * Sign-up through the real HTTP stack (specs/002-user-auth, User Story 1, FR-001 to FR-006). Every
 * request carries the CSRF cookie and header, as the browser does. Each test uses its own email, so
 * the tests share one in-memory database without interfering.
 */
@SpringBootTest(properties = {
            // Every test request comes from one address. Raise the auth limits so the flows under test are
            // not cut short; the limits themselves are covered by AuthRateLimitFilterTest.
            "support.auth.sign-up.per-hour=1000",
            "support.auth.sign-in.per-minute=1000",
            "support.auth.reset-request.per-hour=1000",
    "spring.ai.google.genai.api-key=test-key-not-used",
    "spring.datasource.url=jdbc:h2:mem:auth-controller;MODE=PostgreSQL"
})
@AutoConfigureMockMvc
class AuthControllerTest {

    private static final String GOOD_PASSWORD = "correct-horse-battery-staple-42";

    @Autowired
    private MockMvc mvc;

    @Autowired
    private JdbcClient jdbc;

    /** Replaces the chat service, so the sign-up tests do not depend on the provider. */
    @MockitoBean
    private ChatService chatService;

    @Test
    void validSignUpReturns201WithTheLowercaseEmailAndNoPassword() throws Exception {
        String email = uniqueEmail("Ana");

        mvc.perform(register(email, GOOD_PASSWORD))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.id").isNotEmpty())
                .andExpect(jsonPath("$.email").value(email.toLowerCase()))
                .andExpect(content().string(not(containsString(GOOD_PASSWORD))))
                .andExpect(content().string(not(containsString("argon2"))));

        assertThat(accountsFor(email.toLowerCase())).isEqualTo(1);
    }

    @Test
    void invalidEmailIsReportedNextToTheEmailFieldAndCreatesNoAccount() throws Exception {
        String email = "not-an-address";

        mvc.perform(register(email, GOOD_PASSWORD))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"))
                .andExpect(jsonPath("$.fieldErrors[0].field").value("email"));

        assertThat(accountsFor(email)).isZero();
    }

    @Test
    void passwordOfElevenCharactersIsReportedNextToThePasswordField() throws Exception {
        String email = uniqueEmail("short");

        mvc.perform(register(email, "elevenchars"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"))
                .andExpect(jsonPath("$.fieldErrors[?(@.field == 'password')]").exists());

        assertThat(accountsFor(email.toLowerCase())).isZero();
    }

    @Test
    void commonPasswordIsRefusedWithARequestForADifferentOne() throws Exception {
        String email = uniqueEmail("common");

        mvc.perform(register(email, "password12345"))
                .andExpect(status().isBadRequest())
                .andExpect(content().string(containsString("different password")));

        assertThat(accountsFor(email.toLowerCase())).isZero();
    }

    @Test
    void passwordOfOneHundredTwentyNineCharactersStatesTheMaximumLength() throws Exception {
        String email = uniqueEmail("long");

        mvc.perform(register(email, "x".repeat(129)))
                .andExpect(status().isBadRequest())
                .andExpect(content().string(containsString("maximum length")));
    }

    @Test
    void passwordOfExactly128CharactersIsAccepted() throws Exception {
        String email = uniqueEmail("max");

        mvc.perform(register(email, "Aa1!".repeat(32)))
                .andExpect(status().isCreated());
    }

    @Test
    void registeredAddressIsRefusedWithANeutralMessageThatPointsToSignInOrReset() throws Exception {
        String email = uniqueEmail("dup");
        mvc.perform(register(email, GOOD_PASSWORD)).andExpect(status().isCreated());

        mvc.perform(register(email.toUpperCase(), GOOD_PASSWORD))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("EMAIL_UNAVAILABLE"))
                .andExpect(content().string(containsString("sign in")))
                .andExpect(content().string(containsString("reset your password")))
                .andExpect(content().string(not(containsString("already registered"))));

        assertThat(accountsFor(email.toLowerCase())).isEqualTo(1);
    }

    @Test
    void correctPasswordSignsInAndReturnsTheUser() throws Exception {
        String email = uniqueEmail("login-ok");
        mvc.perform(register(email, GOOD_PASSWORD)).andExpect(status().isCreated());

        mvc.perform(login(email, GOOD_PASSWORD))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.email").value(email.toLowerCase()))
                .andExpect(jsonPath("$.id").isNotEmpty());
    }

    @Test
    void wrongPasswordGetsTheGenericMessage() throws Exception {
        String email = uniqueEmail("login-wrong");
        mvc.perform(register(email, GOOD_PASSWORD)).andExpect(status().isCreated());

        mvc.perform(login(email, "a-different-passphrase-99"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("INVALID_CREDENTIALS"))
                .andExpect(jsonPath("$.message").value("Email or password is incorrect"));
    }

    @Test
    void unregisteredEmailGetsTheSameStatusCodeAndMessageAsAWrongPassword() throws Exception {
        String unknown = uniqueEmail("nobody");

        mvc.perform(login(unknown, GOOD_PASSWORD))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("INVALID_CREDENTIALS"))
                .andExpect(jsonPath("$.message").value("Email or password is incorrect"));
    }

    @Test
    void signInWithAMalformedEmailGetsTheSameGenericResponse() throws Exception {
        mvc.perform(login("not-an-address", GOOD_PASSWORD))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.message").value("Email or password is incorrect"));
    }

    @Test
    void signOutEndsTheSessionAndAReusedSessionIsRefused() throws Exception {
        String email = uniqueEmail("logout");
        MockHttpSession session = new MockHttpSession();
        mvc.perform(register(email, GOOD_PASSWORD).session(session)).andExpect(status().isCreated());

        mvc.perform(post("/api/auth/logout").session(session).with(csrfCookieAndHeader()))
                .andExpect(status().isNoContent());

        assertThat(session.isInvalid()).isTrue();
        mvc.perform(delete("/api/chat/" + UUID.randomUUID()).session(session).with(csrfCookieAndHeader()))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("UNAUTHENTICATED"));
    }

    @Test
    void signOutWithoutASessionGets401() throws Exception {
        mvc.perform(post("/api/auth/logout").with(csrfCookieAndHeader()))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("UNAUTHENTICATED"));
    }

    @Test
    void signOutWithoutTheCsrfHeaderIsRefused() throws Exception {
        String email = uniqueEmail("logout-csrf");
        MockHttpSession session = new MockHttpSession();
        mvc.perform(register(email, GOOD_PASSWORD).session(session)).andExpect(status().isCreated());

        mvc.perform(post("/api/auth/logout").session(session))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("CSRF_INVALID"));
    }

    @Test
    void meReturnsTheSignedInUser() throws Exception {
        String email = uniqueEmail("me");
        MockHttpSession session = new MockHttpSession();
        mvc.perform(register(email, GOOD_PASSWORD).session(session)).andExpect(status().isCreated());

        mvc.perform(get("/api/auth/me").session(session))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.email").value(email.toLowerCase()));
    }

    @Test
    void meWithoutASessionGets401AndTheCsrfCookie() throws Exception {
        mvc.perform(get("/api/auth/me"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("UNAUTHENTICATED"))
                .andExpect(cookie().exists("XSRF-TOKEN"));
    }

    private static org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder login(
            String email, String password) {
        return post("/api/auth/login")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"email\":\"" + email + "\",\"password\":\"" + password + "\"}")
                .with(csrfCookieAndHeader());
    }

    private static org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder register(
            String email, String password) {
        return post("/api/auth/register")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"email\":\"" + email + "\",\"password\":\"" + password + "\"}")
                .with(csrfCookieAndHeader());
    }

    private static String uniqueEmail(String prefix) {
        return prefix + "-" + UUID.randomUUID() + "@Example.test";
    }

    private long accountsFor(String normalizedEmail) {
        return jdbc.sql("SELECT COUNT(*) FROM users WHERE email = :email")
                .param("email", normalizedEmail)
                .query(Long.class)
                .single();
    }
}
