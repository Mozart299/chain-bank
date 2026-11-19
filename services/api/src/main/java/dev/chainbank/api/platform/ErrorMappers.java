package dev.chainbank.api.platform;

import jakarta.validation.ConstraintViolationException;
import jakarta.ws.rs.WebApplicationException;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;

import org.jboss.logging.Logger;
import org.jboss.logging.MDC;
import org.jboss.resteasy.reactive.server.ServerExceptionMapper;

import io.quarkus.security.AuthenticationFailedException;
import io.quarkus.security.ForbiddenException;
import io.quarkus.security.UnauthorizedException;

public class ErrorMappers {

    private static final Logger LOG = Logger.getLogger(ErrorMappers.class);

    @ServerExceptionMapper
    public Response api(ApiException e) {
        return respond(e.status(), e.code(), e.getMessage());
    }

    @ServerExceptionMapper({ UnauthorizedException.class, AuthenticationFailedException.class })
    public Response unauthenticated(RuntimeException e) {
        return respond(401, "UNAUTHENTICATED", "Authentication required");
    }

    @ServerExceptionMapper
    public Response forbidden(ForbiddenException e) {
        return respond(403, "FORBIDDEN", "You do not have permission for this action");
    }

    @ServerExceptionMapper
    public Response validation(ConstraintViolationException e) {
        return respond(400, "VALIDATION_FAILED", "Request validation failed");
    }

    @ServerExceptionMapper
    public Response web(WebApplicationException e) {
        int status = e.getResponse().getStatus();
        return respond(status, Response.Status.fromStatusCode(status) != null
                ? Response.Status.fromStatusCode(status).name()
                : "HTTP_" + status, e.getMessage());
    }

    @ServerExceptionMapper
    public Response unexpected(Exception e) {
        LOG.error("Unhandled error", e);
        return respond(500, "INTERNAL_ERROR", "An unexpected error occurred");
    }

    private static Response respond(int status, String code, String message) {
        Object cid = MDC.get(CorrelationIdFilter.MDC_KEY);
        return Response.status(status)
                .type(MediaType.APPLICATION_JSON)
                .entity(new ApiError(status, code, message, cid == null ? null : cid.toString()))
                .build();
    }
}
