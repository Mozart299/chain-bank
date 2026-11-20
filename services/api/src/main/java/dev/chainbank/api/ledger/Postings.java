package dev.chainbank.api.ledger;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.util.UUID;

/**
 * Writes journal lines. The database does the bookkeeping checks: currency match, non-negative
 * balances, and (at commit) debits = credits overall and per institution.
 */
public final class Postings {

    private Postings() {
    }

    public static void line(Connection c, UUID journalId, UUID accountId, long debit, long credit) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement("""
                INSERT INTO journal_lines (journal_id, account_id, debit_minor, credit_minor, currency)
                VALUES (?, ?, ?, ?, ?)""")) {
            ps.setObject(1, journalId);
            ps.setObject(2, accountId);
            ps.setLong(3, debit);
            ps.setLong(4, credit);
            ps.setString(5, AccountService.CURRENCY);
            ps.executeUpdate();
        }
    }
}
