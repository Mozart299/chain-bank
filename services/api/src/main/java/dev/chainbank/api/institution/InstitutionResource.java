package dev.chainbank.api.institution;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import javax.sql.DataSource;

import jakarta.inject.Inject;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;

/** Public directory of sandbox institutions (no customer data). */
@Path("/api/institutions")
@Produces(MediaType.APPLICATION_JSON)
public class InstitutionResource {

    public record Institution(UUID id, String code, String name, String pseudonym, String status) {
    }

    @Inject
    DataSource dataSource;

    @GET
    public List<Institution> list() throws SQLException {
        String sql = "SELECT id, code, name, pseudonym, status FROM institutions ORDER BY code";
        try (Connection c = dataSource.getConnection();
                PreparedStatement ps = c.prepareStatement(sql);
                ResultSet rs = ps.executeQuery()) {
            List<Institution> out = new ArrayList<>();
            while (rs.next()) {
                out.add(new Institution(rs.getObject("id", UUID.class), rs.getString("code"),
                        rs.getString("name"), rs.getString("pseudonym"), rs.getString("status")));
            }
            return out;
        }
    }
}
