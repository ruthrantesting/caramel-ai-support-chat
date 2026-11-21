package ai.caramel.support.config;

import com.google.genai.Client;
import com.google.genai.types.HttpOptions;
import com.google.genai.types.HttpRetryOptions;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Provides the Google GenAI SDK client with an explicit HTTP timeout (research R-4).
 *
 * <p>The starter exposes no timeout property, so this bean replaces Spring AI's own
 * {@code Client} bean, which is {@code @ConditionalOnMissingBean}. SDK-level retries are disabled
 * because Spring AI's retry template is the single retry policy (research R-7).
 */
@Configuration(proxyBeanMethods = false)
class GenAiClientConfig {

    @Bean
    Client googleGenAiClient(ApiKeyStartupCheck apiKeyCheck, SupportProperties props) {
        int timeoutMillis = Math.toIntExact(props.provider().timeout().toMillis());
        return Client.builder()
                .apiKey(apiKeyCheck.apiKey())
                .httpOptions(HttpOptions.builder()
                        .timeout(timeoutMillis)
                        .retryOptions(HttpRetryOptions.builder().attempts(1).build())
                        .build())
                .build();
    }
}
