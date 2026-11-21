package ai.caramel.support.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.asyncDispatch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.request;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import ai.caramel.support.TestSettings;
import ai.caramel.support.chat.ChatErrorCode;
import ai.caramel.support.chat.ChatEvent;
import ai.caramel.support.chat.ChatFailure;
import ai.caramel.support.chat.ChatService;
import ai.caramel.support.config.SupportProperties;
import java.time.Clock;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Bean;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import reactor.core.publisher.Flux;

/**
 * SSE framing and request validation for {@code POST /api/chat}, with the service stubbed.
 */
@WebMvcTest(ChatController.class)
class ChatControllerStreamTest {

    private static final String CONVERSATION = "11111111-1111-4111-8111-111111111111";

    @Autowired
    private MockMvc mvc;

    @MockitoBean
    private ChatService chatService;

    /**
     * {@code @WebMvcTest} includes servlet filters, so {@link RateLimitFilter} is created here and
     * needs its settings and clock.
     */
    @TestConfiguration
    static class FilterDependencies {

        @Bean
        SupportProperties supportProperties() {
            return TestSettings.defaults();
        }

        @Bean
        Clock clock() {
            return Clock.systemUTC();
        }
    }

    @Test
    void streamsTokensThenDoneAsServerSentEvents() throws Exception {
        when(chatService.stream(eq(CONVERSATION), eq("hi"))).thenReturn(Flux.just(
                new ChatEvent.Token("Hel"), new ChatEvent.Token("lo"), new ChatEvent.Done()));

        String body = streamBody(validRequest("hi"));

        assertThat(body).contains("event:token").contains("\"Hel\"").contains("\"lo\"");
        assertThat(body.indexOf("\"Hel\"")).isLessThan(body.indexOf("\"lo\""));
        assertThat(body.indexOf("\"lo\"")).isLessThan(body.indexOf("event:done"));
    }

    @Test
    void newlinesInTokensAreEncodedInsideTheJsonString() throws Exception {
        when(chatService.stream(any(), any())).thenReturn(Flux.just(
                new ChatEvent.Token("line one\nline two"), new ChatEvent.Done()));

        String body = streamBody(validRequest("hi"));

        // The JSON string carries a literal backslash-n, so the SSE data line is not split.
        assertThat(body).contains("\"line one\\nline two\"");
    }

    @Test
    void failureIsSentAsErrorEventWithFixedMessage() throws Exception {
        when(chatService.stream(any(), any())).thenReturn(Flux.just(
                new ChatEvent.Failed(ChatFailure.of(ChatErrorCode.TIMEOUT))));

        String body = streamBody(validRequest("hi"));

        assertThat(body).contains("event:error").contains("\"TIMEOUT\"");
        assertThat(body).contains(ChatErrorCode.TIMEOUT.userMessage());
    }

    @Test
    void providerFailureBeforeFirstTokenIsHttp503WithApiError() throws Exception {
        when(chatService.stream(any(), any())).thenReturn(Flux.just(
                new ChatEvent.Failed(ChatFailure.of(ChatErrorCode.PROVIDER_UNAVAILABLE))));

        MvcResult result = mvc.perform(post("/api/chat")
                        .contentType(MediaType.APPLICATION_JSON)
                        .accept(MediaType.TEXT_EVENT_STREAM)
                        .content(validRequest("hi")))
                .andReturn();
        if (result.getRequest().isAsyncStarted()) {
            result = mvc.perform(asyncDispatch(result)).andReturn();
        }

        assertThat(result.getResponse().getStatus()).isEqualTo(503);
        assertThat(result.getResponse().getContentAsString()).contains("PROVIDER_UNAVAILABLE");
        assertThat(result.getResponse().getContentType()).startsWith("application/json");
    }

    @Test
    void rejectsMalformedConversationIdWith400() throws Exception {
        mvc.perform(post("/api/chat")
                        .contentType(MediaType.APPLICATION_JSON)
                        .accept(MediaType.TEXT_EVENT_STREAM) // as the real client sends it
                        .content("{\"conversationId\":\"not-a-uuid\",\"message\":\"hi\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(content().string(org.hamcrest.Matchers.containsString("INVALID_REQUEST")));
    }

    @Test
    void rejectsBlankMessageWith400() throws Exception {
        mvc.perform(post("/api/chat")
                        .contentType(MediaType.APPLICATION_JSON)
                        .accept(MediaType.TEXT_EVENT_STREAM) // as the real client sends it
                        .content(validRequest("   ")))
                .andExpect(status().isBadRequest());
    }

    @Test
    void rejectsMessageLongerThan4000CharactersWith400() throws Exception {
        String tooLong = "a".repeat(4001);
        mvc.perform(post("/api/chat")
                        .contentType(MediaType.APPLICATION_JSON)
                        .accept(MediaType.TEXT_EVENT_STREAM) // as the real client sends it
                        .content(validRequest(tooLong)))
                .andExpect(status().isBadRequest());
    }

    private String streamBody(String json) throws Exception {
        MvcResult started = mvc.perform(post("/api/chat")
                        .contentType(MediaType.APPLICATION_JSON)
                        .accept(MediaType.TEXT_EVENT_STREAM)
                        .content(json))
                .andExpect(request().asyncStarted())
                .andReturn();
        return mvc.perform(asyncDispatch(started))
                .andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith(MediaType.TEXT_EVENT_STREAM))
                .andReturn()
                .getResponse()
                .getContentAsString();
    }

    private static String validRequest(String message) {
        return "{\"conversationId\":\"" + CONVERSATION + "\",\"message\":\"" + message + "\"}";
    }
}
