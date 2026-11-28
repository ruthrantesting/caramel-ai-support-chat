package ai.caramel.support.auth;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.text.Normalizer;
import java.util.HashSet;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;

/**
 * Password rules (spec FR-003, FR-004; data-model, User). Length is counted in code points after
 * Unicode NFKC normalization, from 12 to 128. No composition rules apply. A password on the bundled
 * common-password list is refused, compared case-insensitively.
 */
@Component
public class PasswordPolicy {

    public static final int MIN_LENGTH = 12;
    public static final int MAX_LENGTH = 128;

    private static final String COMMON_PASSWORDS = "auth/common-passwords.txt";

    private final Set<String> commonPasswords;

    public PasswordPolicy() {
        this.commonPasswords = loadCommonPasswords();
    }

    /** The password after NFKC normalization. Hashing and checks both use this form. */
    public static String normalize(String raw) {
        return Normalizer.normalize(raw, Normalizer.Form.NFKC);
    }

    /**
     * Returns a user-safe message when the password breaks a rule, or empty when it is acceptable.
     * The argument is the raw input; it is normalized here.
     */
    public Optional<String> violation(String raw) {
        if (raw == null || raw.isEmpty()) {
            return Optional.of("Enter a password.");
        }
        String normalized = normalize(raw);
        int length = normalized.codePointCount(0, normalized.length());
        if (length < MIN_LENGTH) {
            return Optional.of("Password must be at least " + MIN_LENGTH + " characters.");
        }
        if (length > MAX_LENGTH) {
            return Optional.of("Password must be no more than " + MAX_LENGTH + " characters (maximum length).");
        }
        if (commonPasswords.contains(normalized.toLowerCase(Locale.ROOT))) {
            return Optional.of("Choose a different password. This one is too common.");
        }
        return Optional.empty();
    }

    private static Set<String> loadCommonPasswords() {
        Set<String> entries = new HashSet<>();
        ClassPathResource resource = new ClassPathResource(COMMON_PASSWORDS);
        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(resource.getInputStream(), StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                String entry = line.strip();
                if (!entry.isEmpty() && !entry.startsWith("#")) {
                    entries.add(entry.toLowerCase(Locale.ROOT));
                }
            }
        } catch (IOException e) {
            throw new IllegalStateException("Cannot read " + COMMON_PASSWORDS, e);
        }
        return Set.copyOf(entries);
    }
}
