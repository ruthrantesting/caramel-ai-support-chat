package ai.caramel.support.live;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

/**
 * Gate G-1 (research R-1, R-14): one real call to the configured model through the Google GenAI
 * starter. It checks that the model answers and accepts a system instruction.
 *
 * <p>Skipped unless {@code GOOGLE_API_KEY} is set. Never runs in CI.
 */
@SpringBootTest
@EnabledIfEnvironmentVariable(named = "GOOGLE_API_KEY", matches = ".+")
class ProviderSmokeIT {

    @Autowired
    ChatModel chatModel;

    @Test
    void modelAnswersAndAcceptsSystemInstruction() {
        Prompt prompt = new Prompt(List.of(
                new SystemMessage("You are a terse assistant. Answer with one word."),
                new UserMessage("Reply with the single word: pong")));

        String answer = chatModel.call(prompt).getResult().getOutput().getText();

        assertThat(answer).isNotBlank();
        assertThat(answer.toLowerCase()).contains("pong");
    }
}
