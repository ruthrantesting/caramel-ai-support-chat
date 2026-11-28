package ai.caramel.support.auth;

import java.util.Locale;
import java.util.Optional;
import java.util.regex.Pattern;

/**
 * Email normalization and format check (spec FR-002). Addresses are trimmed and lowercased, so two
 * addresses that differ only in letter case are the same account (data-model, User).
 */
public final class EmailAddress {

    /** data-model, User: "text, max 254". */
    public static final int MAX_LENGTH = 254;

    private static final Pattern WELL_FORMED = Pattern.compile(
            "^[a-z0-9.!#$%&'*+/=?^_`{|}~-]+@[a-z0-9](?:[a-z0-9-]{0,61}[a-z0-9])?"
                    + "(?:\\.[a-z0-9](?:[a-z0-9-]{0,61}[a-z0-9])?)+$");

    private EmailAddress() {}

    /**
     * Returns the normalized address, or empty when the input is blank, too long, or not a
     * well-formed address with a dotted domain.
     */
    public static Optional<String> normalize(String raw) {
        if (raw == null) {
            return Optional.empty();
        }
        String candidate = raw.trim().toLowerCase(Locale.ROOT);
        if (candidate.isEmpty() || candidate.length() > MAX_LENGTH) {
            return Optional.empty();
        }
        return WELL_FORMED.matcher(candidate).matches() ? Optional.of(candidate) : Optional.empty();
    }
}
