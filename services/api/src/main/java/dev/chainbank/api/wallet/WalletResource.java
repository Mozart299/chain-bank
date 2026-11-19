package dev.chainbank.api.wallet;

import java.util.List;
import java.util.UUID;

import jakarta.annotation.security.RolesAllowed;
import jakarta.inject.Inject;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.DELETE;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;

import dev.chainbank.api.auth.CurrentSession;
import dev.chainbank.api.auth.SessionService;
import dev.chainbank.api.platform.AppClock;
import dev.chainbank.api.platform.ChainbankConfig;

@Path("/api/wallet")
@RolesAllowed("CUSTOMER")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
public class WalletResource {

    public record ChallengeRequest(@NotBlank String address, @NotNull @Positive Long chainId) {
    }

    public record VerifyRequest(@NotNull UUID challengeId, @NotBlank String signature) {
    }

    @Inject
    WalletService wallets;

    @Inject
    CurrentSession current;

    @Inject
    AppClock clock;

    @Inject
    ChainbankConfig config;

    @POST
    @Path("/challenge")
    public WalletService.Challenge challenge(@Valid ChallengeRequest req) {
        SessionService.ActiveSession s = current.get();
        return wallets.createChallenge(s, req.address(), req.chainId(),
                s.stepUpValid(clock.now(), config.session().stepUpValidity()));
    }

    @POST
    @Path("/verify")
    public Response verify(@Valid VerifyRequest req) {
        return Response.status(201).entity(wallets.verify(current.get(), req.challengeId(), req.signature())).build();
    }

    @GET
    public List<WalletService.Link> list() {
        return wallets.list(current.get().userId());
    }

    @DELETE
    @Path("/{id}")
    public Response unlink(@PathParam("id") UUID id) {
        wallets.unlink(current.requireStepUp().userId(), id);
        return Response.noContent().build();
    }
}
