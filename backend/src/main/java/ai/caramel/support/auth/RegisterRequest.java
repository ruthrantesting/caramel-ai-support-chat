package ai.caramel.support.auth;

/** Body of {@code POST /api/auth/register} (contracts/auth-api.openapi.yaml, schema Credentials). */
public record RegisterRequest(String email, String password) {}
