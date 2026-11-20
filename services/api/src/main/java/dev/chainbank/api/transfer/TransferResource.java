package dev.chainbank.api.transfer;

import java.util.List;
import java.util.UUID;

import jakarta.annotation.security.RolesAllowed;
import jakarta.inject.Inject;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.DefaultValue;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.HeaderParam;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.QueryParam;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;

import io.quarkus.security.Authenticated;

import dev.chainbank.api.auth.CurrentSession;

@Path("/api/transfers")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
public class TransferResource {

    public record CreateTransferRequest(
            @NotNull UUID sourceAccountId,
            @NotBlank @Size(max = 32) String destinationAccountNumber,
            @Positive @Max(1_000_000_000_000L) long amount,
            @NotBlank String currency,
            @Size(max = 140) String reference) {
    }

    public record ApproveRequest(@NotBlank @Pattern(regexp = "^\\d{6}$") String code) {
    }

    @Inject
    TransferService transfers;

    @Inject
    CurrentSession current;

    /**
     * Creates a transfer. Retrying with the same Idempotency-Key returns the original result (200 +
     * {@code Idempotent-Replayed: true}) instead of moving money again.
     */
    @POST
    @RolesAllowed("CUSTOMER")
    public Response create(@HeaderParam("Idempotency-Key") String idempotencyKey, @Valid CreateTransferRequest req) {
        TransferService.CreateResult r = transfers.create(current.get(), idempotencyKey,
                new TransferService.TransferRequest(req.sourceAccountId(), req.destinationAccountNumber(), req.amount(),
                        req.currency(), req.reference()));
        return Response.status(r.replayed() ? 200 : 201)
                .header("Idempotent-Replayed", Boolean.toString(r.replayed()))
                .entity(r.transfer())
                .build();
    }

    @GET
    @RolesAllowed("CUSTOMER")
    public List<TransferService.TransferView> list(@QueryParam("limit") @DefaultValue("50") int limit) {
        return transfers.listForUser(current.get().userId(), limit);
    }

    @GET
    @Path("/{id}")
    @Authenticated
    public TransferService.TransferView get(@PathParam("id") UUID id) {
        return transfers.get(current.get(), id);
    }

    @POST
    @Path("/{id}/approve")
    @RolesAllowed("CUSTOMER")
    public TransferService.TransferView approve(@PathParam("id") UUID id, @Valid ApproveRequest req) {
        return transfers.approve(current.get(), id, req.code());
    }

    @POST
    @Path("/{id}/cancel")
    @RolesAllowed("CUSTOMER")
    public TransferService.TransferView cancel(@PathParam("id") UUID id) {
        return transfers.cancel(current.get(), id);
    }
}
