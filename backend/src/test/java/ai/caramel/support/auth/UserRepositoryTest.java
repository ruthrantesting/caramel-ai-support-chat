package ai.caramel.support.auth;

import static org.assertj.core.api.Assertions.assertThat;

import ai.caramel.support.config.PasswordEncoderConfig;
import java.time.Instant;
import java.util.UUID;
import javax.sql.DataSource;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.embedded.EmbeddedDatabase;
import org.springframework.jdbc.datasource.embedded.EmbeddedDatabaseBuilder;
import org.springframework.jdbc.datasource.embedded.EmbeddedDatabaseType;
import org.springframework.security.crypto.password.PasswordEncoder;

/**
 * data-model.md, User: the password is stored only as a hash (Constitution VI, "password storage as a
 * hash only"), and the email is unique after normalization. Runs schema.sql on an in-memory H2 with
 * no Spring context, so it needs no extra test artifact.
 */
class UserRepositoryTest {

    private static final String PASSWORD = "correct-horse-battery-staple";

    private EmbeddedDatabase database;
    private JdbcClient jdbc;
    private UserRepository users;
    private PasswordEncoder encoder;

    @BeforeEach
    void setUp() {
        database = new EmbeddedDatabaseBuilder()
                .generateUniqueName(true)
                .setType(EmbeddedDatabaseType.H2)
                .addScript("schema.sql")
                .build();
        jdbc = JdbcClient.create((DataSource) database);
        users = new UserRepository(jdbc);
        encoder = new PasswordEncoderConfig().passwordEncoder();
    }

    @AfterEach
    void tearDown() {
        database.shutdown();
    }

    @Test
    void theStoredPasswordIsAHashNeverThePlaintext() {
        users.insert(new User(UUID.randomUUID(), "ana@example.com", encoder.encode(PASSWORD), Instant.now()));

        String stored = jdbc.sql("SELECT password_hash FROM users WHERE email = 'ana@example.com'")
                .query(String.class)
                .single();

        assertThat(stored).doesNotContain(PASSWORD).startsWith("{argon2}");
        assertThat(encoder.matches(PASSWORD, stored)).isTrue();
    }

    @Test
    void theEmailIsStoredLowercaseAndAddressesDifferingOnlyInCaseCollide() {
        String first = EmailAddress.normalize("Ana@Example.com").orElseThrow();
        String second = EmailAddress.normalize("ANA@example.COM").orElseThrow();

        assertThat(users.insert(new User(UUID.randomUUID(), first, encoder.encode(PASSWORD), Instant.now())))
                .isTrue();
        assertThat(users.insert(new User(UUID.randomUUID(), second, encoder.encode(PASSWORD), Instant.now())))
                .as("a second sign-up for the same address is refused")
                .isFalse();
        assertThat(users.findByEmail("ana@example.com")).isPresent();
    }

    @Test
    void userToStringDoesNotShowTheHash() {
        User user = new User(UUID.randomUUID(), "ana@example.com", "{argon2}secret-hash-value", Instant.now());

        assertThat(user.toString()).doesNotContain("secret-hash-value");
    }
}
