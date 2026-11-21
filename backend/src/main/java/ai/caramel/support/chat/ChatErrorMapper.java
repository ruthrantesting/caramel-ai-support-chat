package ai.caramel.support.chat;

import com.google.genai.errors.ClientException;
import com.google.genai.errors.GenAiIOException;
import com.google.genai.errors.ServerException;
import java.io.IOException;
import java.util.concurrent.TimeoutException;
import org.springframework.stereotype.Component;

/**
 * Maps any failure from the provider or the service to a {@link ChatFailure}. The original message
 * is deliberately discarded (spec FR-018, Constitution IV).
 *
 * <p>The cause chain is walked, because Spring AI and the SDK may wrap the transport exception.
 */
@Component
public class ChatErrorMapper {

    private static final int MAX_CAUSE_DEPTH = 10;

    public ChatFailure map(Throwable error) {
        return ChatFailure.of(classify(error));
    }

    private ChatErrorCode classify(Throwable error) {
        Throwable current = error;
        for (int depth = 0; current != null && depth < MAX_CAUSE_DEPTH; depth++) {
            if (current instanceof TimeoutException) {
                return ChatErrorCode.TIMEOUT;
            }
            if (current instanceof ClientException client) {
                return client.code() == 429
                        ? ChatErrorCode.PROVIDER_RATE_LIMITED
                        : ChatErrorCode.INTERNAL_ERROR;
            }
            if (current instanceof ServerException
                    || current instanceof GenAiIOException
                    || current instanceof IOException) {
                return ChatErrorCode.PROVIDER_UNAVAILABLE;
            }
            if (current instanceof ChatFailureException failure) {
                return failure.code();
            }
            current = current.getCause();
        }
        return ChatErrorCode.INTERNAL_ERROR;
    }
}
