package dev.chainbank.api.ledger;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;

import javax.sql.DataSource;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import org.eclipse.microprofile.health.HealthCheck;
import org.eclipse.microprofile.health.HealthCheckResponse;
import org.eclipse.microprofile.health.Readiness;

/**
 * Readiness fails if any ledger invariant is broken:
 * unbalanced journals, or a balance projection that disagrees with the posted lines.
 */
@Readiness
@ApplicationScoped
public class LedgerInvariantCheck implements HealthCheck {

    static final String UNBALANCED_JOURNALS = """
            SELECT count(*) FROM (
              SELECT journal_id FROM journal_lines
              GROUP BY journal_id, currency
              HAVING sum(debit_minor) <> sum(credit_minor)
            ) x""";

    static final String DRIFTED_BALANCES = """
            SELECT count(*) FROM accounts a
            LEFT JOIN account_balances b ON b.account_id = a.id
            LEFT JOIN (
              SELECT l.account_id, sum(l.debit_minor) AS d, sum(l.credit_minor) AS c
              FROM journal_lines l GROUP BY l.account_id
            ) s ON s.account_id = a.id
            WHERE coalesce(b.ledger_minor, 0) <>
                  CASE a.normal_side WHEN 'DEBIT' THEN coalesce(s.d, 0) - coalesce(s.c, 0)
                                     ELSE coalesce(s.c, 0) - coalesce(s.d, 0) END""";

    @Inject
    DataSource dataSource;

    @Override
    public HealthCheckResponse call() {
        try (Connection c = dataSource.getConnection()) {
            long unbalanced = count(c, UNBALANCED_JOURNALS);
            long drifted = count(c, DRIFTED_BALANCES);
            return HealthCheckResponse.named("ledger-invariants")
                    .status(unbalanced == 0 && drifted == 0)
                    .withData("unbalancedJournals", unbalanced)
                    .withData("driftedBalances", drifted)
                    .build();
        } catch (SQLException e) {
            return HealthCheckResponse.named("ledger-invariants").down().withData("error", "database unavailable").build();
        }
    }

    private static long count(Connection c, String sql) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement(sql); ResultSet rs = ps.executeQuery()) {
            rs.next();
            return rs.getLong(1);
        }
    }
}
