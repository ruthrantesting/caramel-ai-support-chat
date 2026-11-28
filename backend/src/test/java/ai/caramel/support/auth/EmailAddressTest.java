package ai.caramel.support.auth;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;

/** FR-002: well-formed check, and letter case does not make a different account. */
class EmailAddressTest {

    @Test
    void addressesThatDifferOnlyInLetterCaseAreTheSameAddress() {
        assertThat(EmailAddress.normalize("Ana@Example.com")).contains("ana@example.com");
        assertThat(EmailAddress.normalize("ana@example.com")).isEqualTo(EmailAddress.normalize("ANA@EXAMPLE.COM"));
    }

    @Test
    void surroundingWhitespaceIsTrimmed() {
        assertThat(EmailAddress.normalize("  ana@example.com \t")).contains("ana@example.com");
    }

    @ParameterizedTest
    @ValueSource(strings = {"ana", "ana@", "@example.com", "ana@example", "ana example@x.com", "ana@@example.com"})
    void malformedAddressesAreRejected(String raw) {
        assertThat(EmailAddress.normalize(raw)).isEmpty();
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {"   "})
    void blankAddressesAreRejected(String raw) {
        assertThat(EmailAddress.normalize(raw)).isEmpty();
    }

    @Test
    void addressLongerThan254CharactersIsRejected() {
        String local = "a".repeat(64);
        String domainLabel = "b".repeat(60);
        String tooLong = local + "@" + domainLabel + "." + domainLabel + "." + domainLabel + "." + domainLabel + ".com";
        assertThat(tooLong.length()).isGreaterThan(EmailAddress.MAX_LENGTH);
        assertThat(EmailAddress.normalize(tooLong)).isEmpty();
    }
}
