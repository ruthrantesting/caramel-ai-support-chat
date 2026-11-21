package ai.caramel.support.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * Fails fast when the provider key is missing, so a developer who forgot {@code .env} sees a clear
 * message at startup instead of a confusing provider error on the first chat (research R-6).
 *
 * <p>The message names the variable and never includes the value (Constitution II).
 */
@Component
public class ApiKeyStartupCheck {

    private final String apiKey;

    public ApiKeyStartupCheck(@Value("${spring.ai.google.genai.api-key:}") String apiKey) {
        if (apiKey == null || apiKey.isBlank()) {
            throw new IllegalStateException(
                    "GOOGLE_API_KEY is not set. Add it to the .env file at the project root "
                            + "(copy .env.example) or export it in the environment.");
        }
        this.apiKey = apiKey;
    }

    /** The validated key. Pass it only to the provider client; never log or return it. */
    public String apiKey() {
        return apiKey;
    }
}
