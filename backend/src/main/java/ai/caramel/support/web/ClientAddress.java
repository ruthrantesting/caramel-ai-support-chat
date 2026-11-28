package ai.caramel.support.web;

import jakarta.servlet.http.HttpServletRequest;

/** The client address used for per-address limits (data-model, Rate-limit window; 001 rule). */
final class ClientAddress {

    private ClientAddress() {}

    /** The remote address, or the first forwarded address only when the deployment trusts the header. */
    static String of(HttpServletRequest request, boolean trustForwardedFor) {
        if (trustForwardedFor) {
            String forwarded = request.getHeader("X-Forwarded-For");
            if (forwarded != null && !forwarded.isBlank()) {
                return forwarded.split(",")[0].trim();
            }
        }
        return request.getRemoteAddr();
    }
}
