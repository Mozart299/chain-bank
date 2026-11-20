package dev.chainbank.api.chain;

import java.util.List;

import jakarta.annotation.security.RolesAllowed;
import jakarta.inject.Inject;
import jakarta.ws.rs.DefaultValue;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.QueryParam;
import jakarta.ws.rs.core.MediaType;

/** Settlement positions and reconciliation exceptions for bank staff. */
@Path("/api")
@Produces(MediaType.APPLICATION_JSON)
public class SettlementResource {

    public record ResolveRequest(@jakarta.validation.constraints.NotBlank
            @jakarta.validation.constraints.Size(min = 5, max = 500) String note) {
    }

    @Inject
    SettlementReconciler reconciler;

    @Inject
    dev.chainbank.api.auth.CurrentSession current;

    /** Spec section 10: mismatches and retry information for operators. */
    @GET
    @Path("/reconciliation/exceptions")
    @RolesAllowed({ "BANK_OPERATOR", "AUDITOR", "COMPLIANCE_REVIEWER", "SYSTEM_ADMIN" })
    public List<SettlementReconciler.ReconciliationException> exceptions(@QueryParam("open") @DefaultValue("false") boolean open) {
        return reconciler.exceptions(open);
    }

    @GET
    @Path("/settlement/positions")
    @RolesAllowed({ "BANK_OPERATOR", "AUDITOR", "COMPLIANCE_REVIEWER", "SYSTEM_ADMIN" })
    public List<SettlementReconciler.Position> positions() {
        return reconciler.positions();
    }

    @jakarta.ws.rs.POST
    @Path("/reconciliation/exceptions/{id}/resolve")
    @jakarta.ws.rs.Consumes(MediaType.APPLICATION_JSON)
    @RolesAllowed({ "BANK_OPERATOR", "SYSTEM_ADMIN" })
    public SettlementReconciler.ReconciliationException resolve(@jakarta.ws.rs.PathParam("id") java.util.UUID id,
            @jakarta.validation.Valid ResolveRequest req) {
        return reconciler.resolveManually(id, current.requireStepUp().userId(), req.note().trim());
    }

    @jakarta.ws.rs.POST
    @Path("/reconciliation/exceptions/{id}/retry")
    @RolesAllowed({ "BANK_OPERATOR", "SYSTEM_ADMIN" })
    public SettlementReconciler.ReconciliationException retry(@jakarta.ws.rs.PathParam("id") java.util.UUID id) {
        return reconciler.retry(id, current.requireStepUp().userId());
    }
}
