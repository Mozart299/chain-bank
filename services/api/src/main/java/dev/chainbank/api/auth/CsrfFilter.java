package dev.chainbank.api.auth;

import java.nio.charset.StandardCharsets;
import java.util.Set;

import jakarta.inject.Inject;
import jakarta.ws.rs.container.ContainerRequestContext;
import jakarta.ws.rs.core.Cookie;

import org.jboss.resteasy.reactive.server.ServerRequestFilter;

import dev.chainbank.api.platform.ApiException;
import dev.chainbank.api.platform.ChainbankConfig;

/**
 * CSRF defence for every state-changing request:
 * <ol>
 * <li>If the browser sent an Origin header, it must be one of our configured web origins.</li>
 * <li>If the request carries a session cookie, it must also carry X-CSRF-Token equal to the
 * token derived from that cookie. A cross-site page cannot read the token, so it cannot forge
 * the header, even though the browser attaches the cookie.</li>
 * </ol>
 * SameSite=Lax cookies are a third, browser-side layer.
 */
public class CsrfFilter {

    public static final String HEADER = "X-CSRF-Token";
    private static final Set<String> SAFE_METHODS = Set.of("GET", "HEAD", "OPTIONS");

    @Inject
    ChainbankConfig config;

    @ServerRequestFilter
    public void check(ContainerRequestContext request) {
        if (SAFE_METHODS.contains(request.getMethod())) {
            return;
        }
        String origin = request.getHeaderString("Origin");
        if (origin != null && !config.web().allowedOrigins().contains(origin)) {
            throw ApiException.forbidden("ORIGIN_NOT_ALLOWED", "Cross-origin request rejected");
        }
        Cookie session = request.getCookies().get(SessionService.COOKIE);
        if (session != null && !session.getValue().isBlank()) {
            String provided = request.getHeaderString(HEADER);
            String expected = SessionService.csrfTokenFor(session.getValue());
            if (provided == null || !Crypto.constantTimeEquals(
                    expected.getBytes(StandardCharsets.US_ASCII), provided.getBytes(StandardCharsets.US_ASCII))) {
                throw ApiException.forbidden("CSRF_TOKEN_INVALID", "Missing or invalid CSRF token");
            }
        }
    }
}
