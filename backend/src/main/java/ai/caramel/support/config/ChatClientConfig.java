package ai.caramel.support.config;

import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.client.advisor.MessageChatMemoryAdvisor;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.io.Resource;

/**
 * The single support {@link ChatClient}: persona and guardrails from the system prompt file, and
 * per-conversation memory through {@link MessageChatMemoryAdvisor}. Persona changes belong in
 * {@code prompts/support-system.st}, never in the UI (Constitution III).
 */
@Configuration(proxyBeanMethods = false)
class ChatClientConfig {

    @Bean
    ChatClient supportChatClient(
            ChatClient.Builder builder,
            ChatMemory chatMemory,
            @Value("classpath:prompts/support-system.st") Resource systemPrompt) {
        return builder
                .defaultSystem(systemPrompt)
                .defaultAdvisors(MessageChatMemoryAdvisor.builder(chatMemory).build())
                .build();
    }
}
