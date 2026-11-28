package ai.caramel.support.auth;

import java.util.Collection;
import java.util.List;
import java.util.UUID;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.stereotype.Service;

/**
 * Looks up accounts for the sign-in check. Defining this bean also stops Spring Boot from creating
 * its default in-memory user, whose generated password would be printed to the log (research A-15,
 * Constitution II).
 */
@Service
public class JdbcUserDetailsService implements UserDetailsService {

    private final UserRepository users;

    public JdbcUserDetailsService(UserRepository users) {
        this.users = users;
    }

    @Override
    public UserDetails loadUserByUsername(String username) throws UsernameNotFoundException {
        String email = EmailAddress.normalize(username)
                .orElseThrow(() -> new UsernameNotFoundException("No account for this address"));
        return users.findByEmail(email)
                .map(user -> (UserDetails) new AccountDetails(user.id(), user.email(), user.passwordHash()))
                .orElseThrow(() -> new UsernameNotFoundException("No account for this address"));
    }

    /**
     * Holds the hash only while the sign-in check runs. It is never stored in the session: after a
     * successful check the controller puts an {@link AuthenticatedUser} in the security context.
     */
    record AccountDetails(UUID id, String email, String passwordHash) implements UserDetails {

        private static final List<GrantedAuthority> AUTHORITIES =
                List.of(new SimpleGrantedAuthority("ROLE_USER"));

        @Override
        public Collection<? extends GrantedAuthority> getAuthorities() {
            return AUTHORITIES;
        }

        @Override
        public String getPassword() {
            return passwordHash;
        }

        @Override
        public String getUsername() {
            return email;
        }

        @Override
        public String toString() {
            return "AccountDetails[id=" + id + ", email=" + email + "]";
        }
    }
}
