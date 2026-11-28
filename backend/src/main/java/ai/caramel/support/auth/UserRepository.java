package ai.caramel.support.auth;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.UUID;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/** JDBC access to the {@code users} table (data-model, User; research A-9). */
@Repository
public class UserRepository {

    private final JdbcClient jdbc;

    public UserRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /** {@code normalizedEmail} must already be trimmed and lowercased. */
    public Optional<User> findByEmail(String normalizedEmail) {
        return jdbc.sql("SELECT id, email, password_hash, created_at FROM users WHERE email = :email")
                .param("email", normalizedEmail)
                .query((rs, rowNum) -> new User(
                        rs.getObject("id", UUID.class),
                        rs.getString("email"),
                        rs.getString("password_hash"),
                        rs.getObject("created_at", OffsetDateTime.class).toInstant()))
                .optional();
    }

    /**
     * Inserts a new account. Returns {@code false} when the email is already registered. The unique
     * constraint is the final guard against two sign-ups for one address at the same moment.
     */
    public boolean insert(User user) {
        try {
            jdbc.sql("INSERT INTO users (id, email, password_hash, created_at) "
                            + "VALUES (:id, :email, :passwordHash, :createdAt)")
                    .param("id", user.id())
                    .param("email", user.email())
                    .param("passwordHash", user.passwordHash())
                    .param("createdAt", OffsetDateTime.ofInstant(user.createdAt(), ZoneOffset.UTC))
                    .update();
            return true;
        } catch (DuplicateKeyException duplicate) {
            return false;
        }
    }
}
