package dev.chainbank.api.auth;

import java.util.Optional;
import java.util.Set;

import jakarta.annotation.Priority;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import io.quarkus.security.AuthenticationFailedException;
import io.quarkus.security.identity.AuthenticationRequestContext;
import io.quarkus.security.identity.IdentityProvider;
import io.quarkus.security.identity.IdentityProviderManager;
import io.quarkus.security.identity.SecurityIdentity;
import io.quarkus.security.identity.request.AuthenticationRequest;
import io.quarkus.security.identity.request.BaseAuthenticationRequest;
import io.quarkus.security.runtime.QuarkusPrincipal;
import io.quarkus.security.runtime.QuarkusSecurityIdentity;
import io.quarkus.vertx.http.runtime.security.ChallengeData;
import io.quarkus.vertx.http.runtime.security.HttpAuthenticationMechanism;
import io.smallrye.mutiny.Uni;
import io.vertx.core.http.Cookie;
import io.vertx.ext.web.RoutingContext;

/**
 * Plugs cookie sessions into Quarkus Security, so endpoints can use the standard
 * {@code @Authenticated} / {@code @RolesAllowed} annotations. The resolved session is attached to the
 * identity as the "session" attribute.
 */
@ApplicationScoped
@Priority(1)
public class SessionAuthMechanism implements HttpAuthenticationMechanism {

    public static final String SESSION_ATTRIBUTE = "session";

    @Override
    public Uni<SecurityIdentity> authenticate(RoutingContext context, IdentityProviderManager identityProviderManager) {
        Cookie cookie = context.request().getCookie(SessionService.COOKIE);
        if (cookie == null || cookie.getValue().isBlank()) {
            return Uni.createFrom().optional(Optional.empty());
        }
        return identityProviderManager.authenticate(new SessionTokenRequest(cookie.getValue()));
    }

    @Override
    public Uni<ChallengeData> getChallenge(RoutingContext context) {
        return Uni.createFrom().item(new ChallengeData(401, null, null));
    }

    @Override
    public Set<Class<? extends AuthenticationRequest>> getCredentialTypes() {
        return Set.of(SessionTokenRequest.class);
    }

    public static final class SessionTokenRequest extends BaseAuthenticationRequest {
        final String token;

        SessionTokenRequest(String token) {
            this.token = token;
        }
    }

    @ApplicationScoped
    public static class SessionIdentityProvider implements IdentityProvider<SessionTokenRequest> {

        @Inject
        SessionService sessions;

        @Override
        public Class<SessionTokenRequest> getRequestType() {
            return SessionTokenRequest.class;
        }

        @Override
        public Uni<SecurityIdentity> authenticate(SessionTokenRequest request, AuthenticationRequestContext context) {
            return context.runBlocking(() -> sessions.resolve(request.token)
                    .map(s -> (SecurityIdentity) QuarkusSecurityIdentity.builder()
                            .setPrincipal(new QuarkusPrincipal(s.userId().toString()))
                            .addRoles(s.roles())
                            .addAttribute(SESSION_ATTRIBUTE, s)
                            .build())
                    .orElseThrow(AuthenticationFailedException::new));
        }
    }
}
