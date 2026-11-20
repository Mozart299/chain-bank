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

    @Inject
    SettlementReconciler reconciler;

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
}
