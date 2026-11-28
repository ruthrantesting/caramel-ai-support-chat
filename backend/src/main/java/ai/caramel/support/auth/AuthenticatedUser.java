package ai.caramel.support.auth;

import java.util.UUID;

/**
 * The signed-in principal (data-model, Authenticated user). It carries no password and no hash. The
 * chat layer receives only {@link #id()} as a plain string (Constitution V).
 */
public record AuthenticatedUser(UUID id, String email) {}
