package ai.caramel.support.auth;

/**
 * Body of {@code POST /api/auth/login} (contracts/auth-api.openapi.yaml, schema LoginRequest). The
 * password is not length-checked here, so a wrong or short value gets the generic 401, not a 400.
 */
public record LoginRequest(String email, String password) {}
