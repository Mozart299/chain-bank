package dev.chainbank.api.admin;

import java.sql.PreparedStatement;
import java.util.Map;
import java.util.UUID;

import jakarta.annotation.security.RolesAllowed;
import jakarta.inject.Inject;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;

import dev.chainbank.api.audit.AuditLog;
import dev.chainbank.api.auth.CurrentSession;
import dev.chainbank.api.auth.SessionService;
import dev.chainbank.api.platform.ApiException;
import dev.chainbank.api.platform.Db;

@Path("/api/admin")
@RolesAllowed("SYSTEM_ADMIN")
@Produces(MediaType.APPLICATION_JSON)
public class AdminResource {

    @Inject
    Db db;

    @Inject
    CurrentSession current;

    @Inject
    SessionService sessions;

    @Inject
    AuditLog audit;

    /** Emergency suspension (spec section 7.3): blocks login and kills every live session immediately. */
    @POST
    @Path("/users/{id}/suspend")
    public Response suspend(@PathParam("id") UUID userId) {
        SessionService.ActiveSession admin = current.requireStepUp();
        if (admin.userId().equals(userId)) {
            throw ApiException.badRequest("CANNOT_SUSPEND_SELF", "Administrators cannot suspend themselves");
        }
        db.tx(c -> {
            try (PreparedStatement ps = c.prepareStatement(
                    "UPDATE users SET status = 'SUSPENDED' WHERE id = ? AND status <> 'SUSPENDED'")) {
                ps.setObject(1, userId);
                if (ps.executeUpdate() != 1) {
                    throw ApiException.notFound("User not found or already suspended");
                }
            }
            int revoked = sessions.revokeAllForUser(c, userId);
            audit.record(c, admin.userId(), "USER_SUSPENDED", "USER", userId.toString(),
                    Map.of("sessionsRevoked", Integer.toString(revoked)));
            return null;
        });
        return Response.noContent().build();
    }
}
