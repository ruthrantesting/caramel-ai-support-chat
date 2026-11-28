package ai.caramel.support.auth;

import java.time.Instant;
import java.util.UUID;

/**
 * A stored account (data-model, User). The hash is never printed: {@link #toString()} leaves it out
 * so that logging a user cannot expose a password credential (spec FR-005, FR-024).
 */
public record User(UUID id, String email, String passwordHash, Instant createdAt) {

    @Override
    public String toString() {
        return "User[id=" + id + ", email=" + email + ", createdAt=" + createdAt + "]";
    }
}
