package dev.chainbank.api;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.UUID;

import javax.sql.DataSource;

import jakarta.inject.Inject;

import org.junit.jupiter.api.Test;

import io.quarkus.test.junit.QuarkusTest;

/** Proves the ledger invariants are enforced by PostgreSQL itself, independent of application code. */
@QuarkusTest
class LedgerSchemaTest {

    static final String BANK_A_EQUITY = "00000000-0000-0000-0001-00000000000a";
    static final String BANK_A_SETTLEMENT = "00000000-0000-0000-0002-00000000000a";
    static final String BANK_A_SUSPENSE = "00000000-0000-0000-0003-00000000000a";

    @Inject
    DataSource dataSource;

    @Test
    void seededOpeningPositionsBalance() throws SQLException {
        // Equity is untouched by other tests, so its opening balance is stable.
        assertEquals(1_000_000_000L, balance(BANK_A_EQUITY));
    }

    @Test
    void balancedJournalCommitsAndUpdatesProjection() throws SQLException {
        long before = balance(BANK_A_SUSPENSE);
        inTx(c -> {
            UUID j = journal(c);
            line(c, j, BANK_A_SUSPENSE, 500, 0);
            line(c, j, BANK_A_SETTLEMENT, 0, 500);
        });
        assertEquals(before + 500, balance(BANK_A_SUSPENSE));
    }

    @Test
    void unbalancedJournalIsRejectedAtCommit() {
        SQLException e = assertThrows(SQLException.class, () -> inTx(c -> {
            UUID j = journal(c);
            line(c, j, BANK_A_SUSPENSE, 500, 0);
            line(c, j, BANK_A_SETTLEMENT, 0, 499);
        }));
        assertTrue(e.getMessage().contains("unbalanced"), e.getMessage());
    }

    @Test
    void singleLegJournalIsRejected() {
        assertThrows(SQLException.class, () -> inTx(c -> {
            UUID j = journal(c);
            line(c, j, BANK_A_SUSPENSE, 0, 0);
        }));
    }

    @Test
    void journalWithoutLinesIsRejected() {
        SQLException e = assertThrows(SQLException.class, () -> inTx(LedgerSchemaTest::journal));
        assertTrue(e.getMessage().contains("no lines"), e.getMessage());
    }

    @Test
    void nonNegativeAccountCannotGoNegative() {
        SQLException e = assertThrows(SQLException.class, () -> inTx(c -> {
            UUID j = journal(c);
            line(c, j, BANK_A_SUSPENSE, 2_000_000_000L, 0);
            line(c, j, BANK_A_SETTLEMENT, 0, 2_000_000_000L);
        }));
        assertTrue(e.getMessage().contains("negative"), e.getMessage());
    }

    @Test
    void postedLinesAreImmutable() {
        SQLException e = assertThrows(SQLException.class, () -> inTx(c -> {
            try (PreparedStatement ps = c.prepareStatement("UPDATE journal_lines SET debit_minor = debit_minor + 1")) {
                ps.executeUpdate();
            }
        }));
        assertTrue(e.getMessage().contains("append-only"), e.getMessage());
    }

    @Test
    void floatingPointAmountsAreImpossible() throws SQLException {
        try (Connection c = dataSource.getConnection();
                PreparedStatement ps = c.prepareStatement(
                        "SELECT data_type FROM information_schema.columns WHERE table_name = 'journal_lines' AND column_name = 'debit_minor'");
                ResultSet rs = ps.executeQuery()) {
            rs.next();
            assertEquals("bigint", rs.getString(1));
        }
    }

    // --- helpers -------------------------------------------------------------

    interface TxWork {
        void run(Connection c) throws SQLException;
    }

    private void inTx(TxWork work) throws SQLException {
        try (Connection c = dataSource.getConnection()) {
            c.setAutoCommit(false);
            try {
                work.run(c);
                c.commit();
            } catch (SQLException e) {
                c.rollback();
                throw e;
            }
        }
    }

    private static UUID journal(Connection c) throws SQLException {
        UUID id = UUID.randomUUID();
        try (PreparedStatement ps = c.prepareStatement(
                "INSERT INTO journals (id, reference, currency, description) VALUES (?, ?, 'xUGX', 'test')")) {
            ps.setObject(1, id);
            ps.setString(2, "TEST-" + id);
            ps.executeUpdate();
        }
        return id;
    }

    private static void line(Connection c, UUID journal, String account, long debit, long credit) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement(
                "INSERT INTO journal_lines (journal_id, account_id, debit_minor, credit_minor, currency) VALUES (?, ?::uuid, ?, ?, 'xUGX')")) {
            ps.setObject(1, journal);
            ps.setString(2, account);
            ps.setLong(3, debit);
            ps.setLong(4, credit);
            ps.executeUpdate();
        }
    }

    private long balance(String account) throws SQLException {
        try (Connection c = dataSource.getConnection();
                PreparedStatement ps = c.prepareStatement("SELECT ledger_minor FROM account_balances WHERE account_id = ?::uuid")) {
            ps.setString(1, account);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? rs.getLong(1) : 0;
            }
        }
    }
}
