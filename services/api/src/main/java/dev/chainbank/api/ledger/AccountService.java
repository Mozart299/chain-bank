package dev.chainbank.api.ledger;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import dev.chainbank.api.audit.AuditLog;
import dev.chainbank.api.platform.ApiException;
import dev.chainbank.api.platform.ChainbankConfig;
import dev.chainbank.api.platform.Db;

/** Customer deposit accounts: opening, balances, history. */
@ApplicationScoped
public class AccountService {

    public static final String CURRENCY = "xUGX";

    @Inject
    Db db;

    @Inject
    ChainbankConfig config;

    @Inject
    AuditLog audit;

    public record Institution(String code, String name) {
    }

    public record Account(UUID id, String accountNumber, Institution institution, String currency, long ledgerBalance,
            long reservedBalance, long availableBalance, String status) {
    }

    public record Entry(long lineId, Instant postedAt, String journalReference, String description, UUID transferId,
            long amount, String direction) {
    }

    /**
     * Opens the customer's xUGX deposit account if it does not exist yet and deposits the demo
     * opening funds (DR bank cash / CR customer deposit). Idempotent and safe under concurrency: the
     * bank's cash account row is locked first, so concurrent openings for one user serialise and the
     * second finds the first's account.
     */
    public Optional<UUID> ensureDefaultAccount(Connection c, UUID userId) throws SQLException {
        UUID institutionId;
        String prefix;
        try (PreparedStatement ps = c.prepareStatement("""
                SELECT u.institution_id, i.account_prefix FROM users u JOIN institutions i ON i.id = u.institution_id
                WHERE u.id = ?""")) {
            ps.setObject(1, userId);
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) {
                    return Optional.empty(); // staff without an institution have no deposit account
                }
                institutionId = rs.getObject(1, UUID.class);
                prefix = rs.getString(2);
            }
        }
        UUID cashAccount = lockSystemAccount(c, institutionId, "CASH");
        Optional<UUID> existing = depositAccountOf(c, userId);
        if (existing.isPresent()) {
            return existing;
        }
        UUID accountId = UUID.randomUUID();
        try (PreparedStatement ps = c.prepareStatement("""
                INSERT INTO accounts (id, institution_id, owner_user_id, account_type, normal_side, currency, name, account_number)
                VALUES (?, ?, ?, 'CUSTOMER_DEPOSIT', 'CREDIT', ?, 'Current account', ? || '-' || nextval('account_number_seq'))""")) {
            ps.setObject(1, accountId);
            ps.setObject(2, institutionId);
            ps.setObject(3, userId);
            ps.setString(4, CURRENCY);
            ps.setString(5, prefix);
            ps.executeUpdate();
        }
        long opening = config.transfers().openingBalance();
        UUID journal = UUID.randomUUID();
        try (PreparedStatement ps = c.prepareStatement(
                "INSERT INTO journals (id, reference, currency, description) VALUES (?, ?, ?, ?)")) {
            ps.setObject(1, journal);
            ps.setString(2, "OPEN-" + accountId);
            ps.setString(3, CURRENCY);
            ps.setString(4, "Demo opening deposit (fictional funds)");
            ps.executeUpdate();
        }
        Postings.line(c, journal, cashAccount, opening, 0);
        Postings.line(c, journal, accountId, 0, opening);
        audit.record(c, userId, "ACCOUNT_OPENED", "ACCOUNT", accountId.toString(),
                Map.of("openingBalance", Long.toString(opening), "currency", CURRENCY));
        return Optional.of(accountId);
    }

    public List<Account> listForUser(UUID userId, boolean isCustomer) {
        return db.tx(c -> {
            if (isCustomer) {
                ensureDefaultAccount(c, userId);
            }
            List<Account> out = new ArrayList<>();
            try (PreparedStatement ps = c.prepareStatement(ACCOUNT_SQL + " WHERE a.owner_user_id = ? ORDER BY a.created_at")) {
                ps.setObject(1, userId);
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) {
                        out.add(map(rs));
                    }
                }
            }
            return out;
        });
    }

    /** Ledger entries for an account the user owns; any other account id behaves as missing. */
    public List<Entry> history(UUID userId, UUID accountId, int limit) {
        return db.tx(c -> {
            String normalSide;
            try (PreparedStatement ps = c.prepareStatement(
                    "SELECT normal_side FROM accounts WHERE id = ? AND owner_user_id = ?")) {
                ps.setObject(1, accountId);
                ps.setObject(2, userId);
                try (ResultSet rs = ps.executeQuery()) {
                    if (!rs.next()) {
                        throw ApiException.notFound("Account not found");
                    }
                    normalSide = rs.getString(1);
                }
            }
            List<Entry> out = new ArrayList<>();
            try (PreparedStatement ps = c.prepareStatement("""
                    SELECT l.id, j.created_at, j.reference, j.description, j.transfer_id, l.debit_minor, l.credit_minor
                    FROM journal_lines l JOIN journals j ON j.id = l.journal_id
                    WHERE l.account_id = ? ORDER BY l.id DESC LIMIT ?""")) {
                ps.setObject(1, accountId);
                ps.setInt(2, Math.min(Math.max(limit, 1), 200));
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) {
                        long debit = rs.getLong(6), credit = rs.getLong(7);
                        // Signed from the customer's point of view: money in is positive.
                        long signed = "CREDIT".equals(normalSide) ? credit - debit : debit - credit;
                        out.add(new Entry(rs.getLong(1), rs.getTimestamp(2).toInstant(), rs.getString(3),
                                rs.getString(4), rs.getObject(5, UUID.class), signed, signed >= 0 ? "IN" : "OUT"));
                    }
                }
            }
            return out;
        });
    }

    static Optional<UUID> depositAccountOf(Connection c, UUID userId) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement(
                "SELECT id FROM accounts WHERE owner_user_id = ? AND account_type = 'CUSTOMER_DEPOSIT' AND currency = ?")) {
            ps.setObject(1, userId);
            ps.setString(2, CURRENCY);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? Optional.of(rs.getObject(1, UUID.class)) : Optional.empty();
            }
        }
    }

    /** Locks and returns the institution's system account of the given type (CASH, SETTLEMENT, ...). */
    public static UUID lockSystemAccount(Connection c, UUID institutionId, String type) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement("""
                SELECT id FROM accounts WHERE institution_id = ? AND account_type = ? AND currency = ?
                FOR UPDATE""")) {
            ps.setObject(1, institutionId);
            ps.setString(2, type);
            ps.setString(3, CURRENCY);
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) {
                    throw new IllegalStateException("missing " + type + " account for institution " + institutionId);
                }
                return rs.getObject(1, UUID.class);
            }
        }
    }

    static final String ACCOUNT_SQL = """
            SELECT a.id, a.account_number, i.code, i.name, a.currency, coalesce(b.ledger_minor, 0),
                   coalesce(b.reserved_minor, 0), a.status
            FROM accounts a JOIN institutions i ON i.id = a.institution_id
            LEFT JOIN account_balances b ON b.account_id = a.id""";

    static Account map(ResultSet rs) throws SQLException {
        long ledger = rs.getLong(6), reserved = rs.getLong(7);
        return new Account(rs.getObject(1, UUID.class), rs.getString(2), new Institution(rs.getString(3), rs.getString(4)),
                rs.getString(5), ledger, reserved, ledger - reserved, rs.getString(8));
    }
}
