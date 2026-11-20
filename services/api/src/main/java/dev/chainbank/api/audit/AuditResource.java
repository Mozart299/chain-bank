package dev.chainbank.api.audit;

import java.util.List;
import java.util.Map;

import jakarta.annotation.security.RolesAllowed;
import jakarta.inject.Inject;
import jakarta.ws.rs.DefaultValue;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.QueryParam;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;

import dev.chainbank.api.auth.CurrentSession;
import dev.chainbank.api.platform.ApiException;
import dev.chainbank.api.platform.Db;

/** Audit log verification, batch commitments and exports (ADR 0007). Auditors are read-only. */
@Path("/api/audit")
@Produces(MediaType.APPLICATION_JSON)
@RolesAllowed({ "AUDITOR", "COMPLIANCE_REVIEWER", "SYSTEM_ADMIN" })
public class AuditResource {

    @Inject
    AuditLog audit;

    @Inject
    AuditBatcher batcher;

    @Inject
    AuditVerifier verifier;

    @Inject
    CurrentSession current;

    @Inject
    Db db;

    @GET
    @Path("/chain/verify")
    public AuditLog.Verification verifyChain() {
        return audit.verifyChain();
    }

    @GET
    @Path("/batches")
    public List<AuditBatcher.Batch> batches(@QueryParam("limit") @DefaultValue("50") int limit) {
        return batcher.list(Math.min(Math.max(limit, 1), 200));
    }

    /** Self-contained JSON for independent verification with tools/audit-verifier. */
    @GET
    @Path("/batches/{id}/export")
    public Response export(@PathParam("id") long id) {
        AuditVerifier.Export export = verifier.export(id);
        db.tx(c -> {
            audit.record(c, current.get().userId(), "AUDIT_BATCH_EXPORTED", "AUDIT_BATCH", Long.toString(id), Map.of());
            return null;
        });
        return Response.ok(export)
                .header("Content-Disposition", "attachment; filename=\"chainbank-audit-batch-" + id + ".json\"")
                .build();
    }

    /** Spec section 10: verify a journal/audit batch against its chain commitment. */
    @GET
    @Path("/{batch}/verify")
    public AuditVerifier.Verification verify(@PathParam("batch") long batch) {
        return verifier.verify(batch);
    }

    /** "Checkpoint now": seal whatever is pending into a batch (privileged, step-up). */
    @POST
    @Path("/batches/seal")
    @RolesAllowed("SYSTEM_ADMIN")
    public Map<String, Object> seal() {
        var admin = current.requireStepUp();
        Long id = batcher.sealNow().orElseThrow(() -> ApiException.conflict("NOTHING_TO_SEAL", "No unsealed audit events"));
        db.tx(c -> {
            audit.record(c, admin.userId(), "AUDIT_CHECKPOINT_REQUESTED", "AUDIT_BATCH", Long.toString(id), Map.of());
            return null;
        });
        return Map.of("batchId", id);
    }
}
