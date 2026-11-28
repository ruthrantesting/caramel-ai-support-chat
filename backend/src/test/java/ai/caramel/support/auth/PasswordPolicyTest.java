package ai.caramel.support.auth;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

/** FR-003 (length 12 to 128, no composition rules) and FR-004 (common-password list). */
class PasswordPolicyTest {

    private final PasswordPolicy policy = new PasswordPolicy();

    @Test
    void elevenCharactersAreRefused() {
        assertThat(policy.violation("abcdefghijk")).hasValueSatisfying(
                message -> assertThat(message).contains("at least 12"));
    }

    @Test
    void twelveCharactersAreAccepted() {
        assertThat(policy.violation("tr7w!mq2zk9p")).isEmpty();
    }

    @Test
    void oneHundredTwentyEightCharactersAreAccepted() {
        assertThat(policy.violation("x".repeat(127) + "Z")).isEmpty();
    }

    @Test
    void oneHundredTwentyNineCharactersAreRefusedWithTheMaximumLength() {
        assertThat(policy.violation("x".repeat(129))).hasValueSatisfying(
                message -> assertThat(message).contains("128").contains("maximum length"));
    }

    @Test
    void lengthIsCountedInCodePointsNotUtf16Units() {
        // Twelve emoji are 24 UTF-16 units but 12 code points: accepted.
        assertThat(policy.violation("😀".repeat(12))).isEmpty();
    }

    @Test
    void commonPasswordIsRefusedWithARequestForADifferentOne() {
        assertThat(policy.violation("password12345")).hasValueSatisfying(
                message -> assertThat(message).contains("different password"));
    }

    @Test
    void commonPasswordCheckIgnoresLetterCase() {
        assertThat(policy.violation("PASSWORD12345")).isPresent();
        assertThat(policy.violation("Password12345")).isPresent();
    }

    @Test
    void nfkcEquivalentInputsAreTheSamePassword() {
        // Fullwidth "ABC" normalizes to ASCII "ABC" under NFKC.
        assertThat(PasswordPolicy.normalize("ＡＢＣ")).isEqualTo("ABC");
    }

    @Test
    void emptyPasswordIsRefused() {
        assertThat(policy.violation("")).isPresent();
        assertThat(policy.violation(null)).isPresent();
    }
}
