package ai.caramel.support.config;

import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.ai.chat.memory.MessageWindowChatMemory;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Conversation memory: a message window held in process memory (Constitution V). Nothing is
 * persisted, so a restart clears all conversations (spec FR-007).
 */
@Configuration(proxyBeanMethods = false)
class ChatMemoryConfig {

    @Bean
    ChatMemory chatMemory(SupportProperties props) {
        return MessageWindowChatMemory.builder()
                .maxMessages(props.memory().maxMessages())
                .build();
    }
}
