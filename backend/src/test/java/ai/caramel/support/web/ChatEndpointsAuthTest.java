package ai.caramel.support.web;

import static ai.caramel.support.AuthTestSupport.csrfCookieAndHeader;
import static org.hamcrest.Matchers.containsString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import ai.caramel.support.TestSettings;
import ai.caramel.support.chat.ChatService;
import ai.caramel.support.config.AuthProperties;
import ai.caramel.support.config.SecurityConfig;
import ai.caramel.support.config.SupportProperties;
import java.time.Clock;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

/**
 * FR-016 and Constitution VI: both chat endpoints refuse a request with no signed-in session. The
 * refusal comes before the stream starts, and the chat service is never called.
 */
@WebMvcTest(ChatController.class)
@Import(SecurityConfig.class)
class ChatEndpointsAuthTest {

    private static final String CONVERSATION = "11111111-1111-4111-8111-111111111111";

    @Autowired
    private MockMvc mvc;

    @MockitoBean
    private ChatService chatService;

    @TestConfiguration
    static class FilterDependencies {

        @Bean
        SupportProperties supportProperties() {
            return TestSettings.defaults();
        }

        @Bean
        AuthProperties authProperties() {
            return TestSettings.authDefaults();
        }

        @Bean
        Clock clock() {
            return Clock.systemUTC();
        }
    }

    @Test
    void postWithoutASessionIsRefusedWith401AndNeverReachesTheService() throws Exception {
        mvc.perform(post("/api/chat")
                        .contentType(MediaType.APPLICATION_JSON)
                        .accept(MediaType.TEXT_EVENT_STREAM)
                        .content("{\"conversationId\":\"" + CONVERSATION + "\",\"message\":\"hi\"}")
                        .with(csrfCookieAndHeader()))
                .andExpect(status().isUnauthorized())
                .andExpect(content().string(containsString("UNAUTHENTICATED")))
                .andExpect(header().doesNotExist("WWW-Authenticate"));

        verify(chatService, never()).stream(org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.any());
    }

    @Test
    void deleteWithoutASessionIsRefusedWith401AndNeverReachesTheService() throws Exception {
        mvc.perform(delete("/api/chat/" + UUID.randomUUID()).with(csrfCookieAndHeader()))
                .andExpect(status().isUnauthorized())
                .andExpect(content().string(containsString("UNAUTHENTICATED")));

        verify(chatService, never()).clear(org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any());
    }
}
