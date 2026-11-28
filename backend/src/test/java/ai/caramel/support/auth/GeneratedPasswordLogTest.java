package ai.caramel.support.auth;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import ai.caramel.support.chat.ChatService;

/**
 * research A-15, Constitution II: Spring Boot must not create its default in-memory user at start-up,
 * because that prints a generated password to the log. Our {@link JdbcUserDetailsService} replaces it.
 */
@SpringBootTest(properties = {
    "spring.ai.google.genai.api-key=test-key-not-used",
    "spring.datasource.url=jdbc:h2:mem:generated-password-log;MODE=PostgreSQL"
})
@ExtendWith(OutputCaptureExtension.class)
class GeneratedPasswordLogTest {

    @Autowired
    private UserDetailsService userDetailsService;

    /** Replaces the chat service, so this test does not depend on the provider. */
    @MockitoBean
    private ChatService chatService;

    @Test
    void startupLogHasNoGeneratedPassword(CapturedOutput output) {
        assertThat(output.getAll()).doesNotContain("Using generated security password");
    }

    @Test
    void theApplicationProvidesItsOwnUserDetailsService() {
        assertThat(userDetailsService).isInstanceOf(JdbcUserDetailsService.class);
    }
}
