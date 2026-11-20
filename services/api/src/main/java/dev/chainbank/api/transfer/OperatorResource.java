package dev.chainbank.api.transfer;

import java.util.List;
import java.util.UUID;

import jakarta.annotation.security.RolesAllowed;
import jakarta.inject.Inject;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;

import dev.chainbank.api.auth.CurrentSession;

/** Bank-operator review of flagged cross-bank transfers (ADR 0005). */
@Path("/api/operator")
@RolesAllowed("BANK_OPERATOR")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
public class OperatorResource {

    public record ReviewRequest(@NotBlank @Pattern(regexp = "^(APPROVE|REJECT)$") String decision) {
    }

    @Inject
    TransferService transfers;

    @Inject
    CurrentSession current;

    @GET
    @Path("/reviews")
    public List<TransferService.TransferView> queue() {
        return transfers.reviewQueue(current.get());
    }

    /** Releasing or blocking customer money is privileged: requires a fresh step-up. */
    @POST
    @Path("/transfers/{id}/review")
    public TransferService.TransferView review(@PathParam("id") UUID id, @Valid ReviewRequest req) {
        return transfers.review(current.requireStepUp(), id, "APPROVE".equals(req.decision()));
    }
}
