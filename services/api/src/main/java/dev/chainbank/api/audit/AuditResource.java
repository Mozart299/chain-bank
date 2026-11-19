package dev.chainbank.api.audit;

import jakarta.annotation.security.RolesAllowed;
import jakarta.inject.Inject;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;

/** Read-only verification of the hash-chained security audit log. */
@Path("/api/audit")
@Produces(MediaType.APPLICATION_JSON)
public class AuditResource {

    @Inject
    AuditLog audit;

    @GET
    @Path("/chain/verify")
    @RolesAllowed({ "AUDITOR", "COMPLIANCE_REVIEWER", "SYSTEM_ADMIN" })
    public AuditLog.Verification verifyChain() {
        return audit.verifyChain();
    }
}
