package ai.caramel.support.auth;

import static org.assertj.core.api.Assertions.assertThat;

import ai.caramel.support.config.PasswordEncoderConfig;
import org.junit.jupiter.api.Test;
import org.springframework.security.crypto.password.PasswordEncoder;

/**
 * Quickstart step 2 and research A-3: a 128-character password is not truncated, and the encoder
 * stores an algorithm prefix so a later change of algorithm needs no migration.
 */
class PasswordEncoderTest {

    private final PasswordEncoder encoder = new PasswordEncoderConfig().passwordEncoder();

    @Test
    void aMaximumLengthPasswordRoundTrips() {
        String password = "Aa1!".repeat(32); // 128 characters
        assertThat(password).hasSize(128);

        String hash = encoder.encode(password);

        assertThat(encoder.matches(password, hash)).isTrue();
    }

    @Test
    void aPasswordThatDiffersOnlyInTheLastCharacterIsRefused() {
        String password = "Aa1!".repeat(32);
        String differsAtEnd = password.substring(0, 127) + "?";

        String hash = encoder.encode(password);

        assertThat(encoder.matches(differsAtEnd, hash)).isFalse();
    }

    @Test
    void hashCarriesTheAlgorithmPrefix() {
        assertThat(encoder.encode("correct-horse-battery-staple")).startsWith("{argon2}");
    }

    /**
     * Not an assertion: prints one hash time so research A-3 can record it. The 250 ms figure is a
     * target to check on the target machine, not a test pass condition.
     */
    @Test
    void measuresOneHashForResearchA3() {
        String password = "correct-horse-battery-staple";
        encoder.encode(password); // warm-up
        long start = System.nanoTime();
        encoder.encode(password);
        long millis = (System.nanoTime() - start) / 1_000_000;
        System.out.println("[research A-3] one Argon2id hash took " + millis + " ms");
        assertThat(millis).isNotNegative();
    }
}
