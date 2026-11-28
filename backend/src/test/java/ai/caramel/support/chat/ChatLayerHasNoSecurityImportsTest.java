package ai.caramel.support.chat;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;

/**
 * Constitution V: the chat layer receives only a user ID string and does not depend on how that ID
 * was obtained. Fails if any chat source file imports Spring Security.
 */
class ChatLayerHasNoSecurityImportsTest {

    private static final Path CHAT_SOURCES = Path.of("src/main/java/ai/caramel/support/chat");

    @Test
    void noChatSourceFileImportsSpringSecurity() throws IOException {
        List<String> offenders;
        try (Stream<Path> files = Files.walk(CHAT_SOURCES)) {
            offenders = files
                    .filter(path -> path.toString().endsWith(".java"))
                    .filter(path -> read(path).contains("org.springframework.security"))
                    .map(Path::toString)
                    .toList();
        }
        assertThat(offenders).as("chat sources that import Spring Security").isEmpty();
    }

    private static String read(Path path) {
        try {
            return Files.readString(path, StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new IllegalStateException("Cannot read " + path, e);
        }
    }
}
