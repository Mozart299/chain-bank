package dev.chainbank.api.ledger;

import java.util.List;
import java.util.UUID;

import jakarta.annotation.security.RolesAllowed;
import jakarta.inject.Inject;
import jakarta.ws.rs.DefaultValue;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.QueryParam;
import jakarta.ws.rs.core.MediaType;

import dev.chainbank.api.auth.CurrentSession;

@Path("/api/accounts")
@RolesAllowed("CUSTOMER")
@Produces(MediaType.APPLICATION_JSON)
public class AccountResource {

    @Inject
    AccountService accounts;

    @Inject
    CurrentSession current;

    @GET
    public List<AccountService.Account> list() {
        return accounts.listForUser(current.get().userId(), true);
    }

    @GET
    @Path("/{id}/transactions")
    public List<AccountService.Entry> transactions(@PathParam("id") UUID id, @QueryParam("limit") @DefaultValue("50") int limit) {
        return accounts.history(current.get().userId(), id, limit);
    }
}
