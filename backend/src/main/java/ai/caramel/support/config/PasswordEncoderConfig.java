package ai.caramel.support.config;

import java.util.Map;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.crypto.argon2.Argon2PasswordEncoder;
import org.springframework.security.crypto.password.DelegatingPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;

/**
 * Password hashing (specs/002-user-auth, research A-3; Constitution VI). Argon2id is used because
 * bcrypt reads only 72 bytes and the policy allows 128 characters. The delegating encoder stores an
 * algorithm prefix with each hash, so the algorithm can change later without a migration.
 */
@Configuration(proxyBeanMethods = false)
public class PasswordEncoderConfig {

    static final String ARGON2 = "argon2";

    @Bean
    public PasswordEncoder passwordEncoder() {
        return new DelegatingPasswordEncoder(
                ARGON2, Map.of(ARGON2, Argon2PasswordEncoder.defaultsForSpringSecurity_v5_8()));
    }
}
