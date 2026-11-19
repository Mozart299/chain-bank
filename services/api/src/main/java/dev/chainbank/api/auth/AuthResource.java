package dev.chainbank.api.auth;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import jakarta.inject.Inject;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.Context;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.NewCookie;
import jakarta.ws.rs.core.Response;

import io.quarkus.security.Authenticated;
import io.vertx.core.http.HttpServerRequest;

import dev.chainbank.api.platform.AppClock;
import dev.chainbank.api.platform.ChainbankConfig;

@Path("/api/auth")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
public class AuthResource {

    public record RegisterRequest(
            @NotBlank @Email @Size(max = 254) String email,
            @NotBlank @Size(min = 12, max = 128) String password,
            @NotBlank @Size(max = 80) String displayName,
            @NotBlank @Pattern(regexp = "^[A-Z0-9_]{2,16}$") String institutionCode) {
    }

    public record RegisterResponse(UUID userId, String otpauthUri, String totpSecret) {
    }

    public record CredentialsRequest(
            @NotBlank @Email @Size(max = 254) String email,
            @NotBlank @Size(max = 128) String password,
            @NotBlank @Pattern(regexp = "^\\d{6}$") String code) {
    }

    public record CodeRequest(@NotBlank @Pattern(regexp = "^\\d{6}$") String code) {
    }

    public record Me(UUID userId, String email, String displayName, UUID institutionId, List<String> roles,
            String csrfToken, Instant stepUpValidUntil, Instant sessionExpiresAt) {
    }

    @Inject
    AuthService auth;

    @Inject
    CurrentSession current;

    @Inject
    ChainbankConfig config;

    @Inject
    AppClock clock;

    @Context
    HttpServerRequest httpRequest;

    @POST
    @Path("/register")
    public Response register(@Valid RegisterRequest req) {
        AuthService.Enrollment e = auth.register(req.email(), req.password(), req.displayName(), req.institutionCode());
        return Response.status(201).entity(new RegisterResponse(e.userId(), e.otpauthUri(), e.secret())).build();
    }

    @POST
    @Path("/totp/confirm")
    public Response confirm(@Valid CredentialsRequest req) {
        auth.confirmEnrollment(req.email(), req.password(), req.code(), clientIp());
        return Response.noContent().build();
    }

    @POST
    @Path("/login")
    public Response login(@Valid CredentialsRequest req) {
        SessionService.NewSession s = auth.login(req.email(), req.password(), req.code(), clientIp());
        return Response.noContent().cookie(sessionCookie(s.token(), (int) config.session().absoluteTimeout().toSeconds()))
                .header("X-CSRF-Token", s.csrfToken()).build();
    }

    @POST
    @Path("/logout")
    @Authenticated
    public Response logout() {
        auth.logout(current.get());
        return Response.noContent().cookie(sessionCookie("", 0)).build();
    }

    @GET
    @Path("/me")
    @Authenticated
    public Me me(@jakarta.ws.rs.CookieParam(SessionService.COOKIE) String token) {
        SessionService.ActiveSession s = current.get();
        Instant stepUpUntil = s.stepUpValid(clock.now(), config.session().stepUpValidity())
                ? s.stepUpAt().plus(config.session().stepUpValidity())
                : null;
        return new Me(s.userId(), s.email(), s.displayName(), s.institutionId(), s.roles().stream().sorted().toList(),
                SessionService.csrfTokenFor(token), stepUpUntil, s.expiresAt());
    }

    @POST
    @Path("/step-up")
    @Authenticated
    public Response stepUp(@Valid CodeRequest req) {
        auth.stepUp(current.get(), req.code(), clientIp());
        return Response.noContent().build();
    }

    @POST
    @Path("/sessions/revoke-all")
    @Authenticated
    public Response revokeAll() {
        auth.revokeAllSessions(current.requireStepUp());
        return Response.noContent().cookie(sessionCookie("", 0)).build();
    }

    private NewCookie sessionCookie(String value, int maxAgeSeconds) {
        return new NewCookie.Builder(SessionService.COOKIE)
                .value(value)
                .path("/")
                .httpOnly(true)
                .secure(config.session().cookieSecure())
                .sameSite(NewCookie.SameSite.LAX)
                .maxAge(maxAgeSeconds)
                .build();
    }

    private String clientIp() {
        return httpRequest.remoteAddress() == null ? "unknown" : httpRequest.remoteAddress().hostAddress();
    }
}
