package dev.chainbank.api.auth;

import jakarta.enterprise.context.RequestScoped;
import jakarta.inject.Inject;

import io.quarkus.security.identity.SecurityIdentity;

import dev.chainbank.api.platform.ApiException;
import dev.chainbank.api.platform.AppClock;
import dev.chainbank.api.platform.ChainbankConfig;

/** Request-scoped access to the authenticated session, plus the step-up gate. */
@RequestScoped
public class CurrentSession {

    @Inject
    SecurityIdentity identity;

    @Inject
    AppClock clock;

    @Inject
    ChainbankConfig config;

    public SessionService.ActiveSession get() {
        SessionService.ActiveSession s = identity.getAttribute(SessionAuthMechanism.SESSION_ATTRIBUTE);
        if (s == null) {
            throw ApiException.unauthorized();
        }
        return s;
    }

    /** Sensitive actions require a TOTP re-verification within the last few minutes (spec section 7.3). */
    public SessionService.ActiveSession requireStepUp() {
        SessionService.ActiveSession s = get();
        if (!s.stepUpValid(clock.now(), config.session().stepUpValidity())) {
            throw ApiException.forbidden("STEP_UP_REQUIRED", "Re-verify with your authenticator code first");
        }
        return s;
    }
}
