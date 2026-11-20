package dev.chainbank.api.transfer;

import java.sql.Array;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.TreeSet;
import java.util.UUID;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import org.jboss.logging.MDC;

import dev.chainbank.api.audit.AuditLog;
import dev.chainbank.api.auth.Crypto;
import dev.chainbank.api.auth.RateLimiter;
import dev.chainbank.api.auth.SessionService.ActiveSession;
import dev.chainbank.api.auth.TotpVerifier;
import dev.chainbank.api.ledger.AccountService;
import dev.chainbank.api.ledger.Postings;
import dev.chainbank.api.platform.ApiException;
import dev.chainbank.api.platform.AppClock;
import dev.chainbank.api.platform.ChainbankConfig;
import dev.chainbank.api.platform.CorrelationIdFilter;
import dev.chainbank.api.platform.Db;

/**
 * Transfer lifecycle (spec section 8.2):
 *
 * <pre>
 * REQUESTED ─► VALIDATED ─► APPROVED ─► CONFIRMED            (same bank: book transfer)
 *     │            │            └─────► (Phase 3: SUBMITTED ─► CONFIRMED ─► RECONCILED)
 *     └► REJECTED  ├► REJECTED (operator)
 *                  └► CANCELLED (customer or expiry)
 * </pre>
 *
 * Concurrency rules, which make the Phase 2 exit tests hold:
 * <ul>
 * <li>Every operation first locks all involved account rows in UUID order. That serialises
 * work per account and rules out deadlocks.</li>
 * <li>Funds are reserved with a conditional UPDATE, so available balance can never go below zero.
 * The ledger trigger is a second, independent guard.</li>
 * <li>State changes are compare-and-set ({@code WHERE state = ?}), so racing approve and cancel
 * cannot both win.</li>
 * <li>Idempotency keys are unique per user. A replay returns the original transfer.</li>
 * </ul>
 */
@ApplicationScoped
public class TransferService {

    static final List<String> POSTED_STATES = List.of("APPROVED", "SUBMITTED", "CONFIRMED", "RECONCILED");

    @Inject
    Db db;

    @Inject
    AppClock clock;

    @Inject
    ChainbankConfig config;

    @Inject
    AuditLog audit;

    @Inject
    TotpVerifier totp;

    @Inject
    RateLimiter limiter;

    public record TransferRequest(UUID sourceAccountId, String destinationAccountNumber, long amount, String currency,
            String reference) {
    }

    public record Party(String accountNumber, String bank, String holder) {
    }

    public record Event(String fromState, String toState, String reason, Instant at) {
    }

    public record TransferView(UUID id, String state, String direction, long amount, String currency, String reference,
            Party source, Party destination, boolean crossBank, boolean requiresStepUp, boolean stepUpApproved,
            boolean requiresReview, boolean operatorApproved, String failureReason, Instant createdAt,
            Instant expiresAt, Instant approvedAt, List<Event> events) {
    }

    public record CreateResult(TransferView transfer, boolean replayed) {
    }

    record Row(UUID id, UUID source, UUID destination, UUID sourceInstitution, UUID destinationInstitution, long amount,
            String currency, String state, UUID initiatedBy, boolean requiresStepUp, boolean requiresReview,
            Instant expiresAt, byte[] fingerprint) {

        boolean crossBank() {
            return !sourceInstitution.equals(destinationInstitution);
        }
    }

    // --- create -----------------------------------------------------------------------------------

    public CreateResult create(ActiveSession session, String idempotencyKey, TransferRequest req) {
        if (idempotencyKey == null || !idempotencyKey.matches("^[A-Za-z0-9._:-]{8,128}$")) {
            throw ApiException.badRequest("IDEMPOTENCY_KEY_REQUIRED",
                    "Send a unique Idempotency-Key header (8-128 characters) with every transfer");
        }
        if (!AccountService.CURRENCY.equals(req.currency())) {
            throw ApiException.badRequest("UNSUPPORTED_CURRENCY", "Only xUGX is supported");
        }
        String payee = req.destinationAccountNumber().trim().toUpperCase();
        String reference = req.reference() == null ? "" : req.reference().trim();
        byte[] fingerprint = Crypto.sha256(String.join("|", req.sourceAccountId().toString(), payee,
                Long.toString(req.amount()), req.currency(), reference));
        try {
            return db.txRetrying(c -> createTx(c, session, idempotencyKey, payee, reference, req, fingerprint));
        } catch (RuntimeException e) {
            if (Db.isUniqueViolation(e)) {
                // A concurrent request with the same key won the insert: return its result.
                return db.tx(c -> {
                    Optional<Row> existing = findByKey(c, session.userId(), idempotencyKey);
                    if (existing.isEmpty()) {
                        throw e;
                    }
                    return replay(c, session.userId(), existing.get(), fingerprint);
                });
            }
            throw e;
        }
    }

    private CreateResult createTx(Connection c, ActiveSession session, String key, String payee, String reference,
            TransferRequest req, byte[] fingerprint) throws SQLException {
        UUID userId = session.userId();
        // 1. Resolve both accounts (unlocked), then lock everything involved in a fixed order.
        UUID sourceInstitution;
        try (PreparedStatement ps = c.prepareStatement("""
                SELECT institution_id FROM accounts
                WHERE id = ? AND owner_user_id = ? AND account_type = 'CUSTOMER_DEPOSIT'""")) {
            ps.setObject(1, req.sourceAccountId());
            ps.setObject(2, userId);
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) {
                    throw ApiException.notFound("Source account not found");
                }
                sourceInstitution = rs.getObject(1, UUID.class);
            }
        }
        UUID destination;
        UUID destinationInstitution;
        try (PreparedStatement ps = c.prepareStatement("""
                SELECT id, institution_id FROM accounts
                WHERE account_number = ? AND account_type = 'CUSTOMER_DEPOSIT' AND status = 'ACTIVE'""")) {
            ps.setString(1, payee);
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) {
                    throw ApiException.notFound("No active account with that account number");
                }
                destination = rs.getObject(1, UUID.class);
                destinationInstitution = rs.getObject(2, UUID.class);
            }
        }
        if (destination.equals(req.sourceAccountId())) {
            throw ApiException.badRequest("SAME_ACCOUNT", "Source and destination must differ");
        }
        lockAccounts(c, involvedAccounts(c, req.sourceAccountId(), destination, sourceInstitution, destinationInstitution));

        // 2. Idempotency, re-checked under the lock: a concurrent duplicate waits above, then lands here.
        Optional<Row> existing = findByKey(c, userId, key);
        if (existing.isPresent()) {
            return replay(c, userId, existing.get(), fingerprint);
        }
        requireActive(c, req.sourceAccountId());

        // 3. Policy: daily limit, then funds.
        Instant now = clock.now();
        long amount = req.amount();
        String rejection = null;
        if (spentToday(c, userId, now) + amount > config.transfers().dailyLimit()) {
            rejection = "DAILY_LIMIT";
        } else if (!reserve(c, req.sourceAccountId(), amount)) {
            rejection = "INSUFFICIENT_FUNDS";
        }
        boolean crossBank = !sourceInstitution.equals(destinationInstitution);
        boolean newBeneficiary = isNewBeneficiary(c, userId, destination);
        boolean requiresStepUp = amount > config.transfers().stepUpThreshold() || newBeneficiary;
        boolean requiresReview = crossBank && amount > config.transfers().reviewThreshold();

        UUID id = UUID.randomUUID();
        Instant expires = now.plus(config.transfers().approvalTtl());
        try (PreparedStatement ps = c.prepareStatement("""
                INSERT INTO transfers (id, source_account_id, destination_account_id, source_institution_id,
                    destination_institution_id, amount_minor, currency, state, failure_reason, initiated_by,
                    idempotency_key, request_fingerprint, correlation_id, reference, requires_step_up,
                    requires_review, expires_at, created_at, updated_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, 'REQUESTED', NULL, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)""")) {
            ps.setObject(1, id);
            ps.setObject(2, req.sourceAccountId());
            ps.setObject(3, destination);
            ps.setObject(4, sourceInstitution);
            ps.setObject(5, destinationInstitution);
            ps.setLong(6, amount);
            ps.setString(7, req.currency());
            ps.setObject(8, userId);
            ps.setString(9, key);
            ps.setBytes(10, fingerprint);
            Object cid = MDC.get(CorrelationIdFilter.MDC_KEY);
            ps.setString(11, cid == null ? null : cid.toString());
            ps.setString(12, reference.isEmpty() ? null : reference);
            ps.setBoolean(13, requiresStepUp);
            ps.setBoolean(14, requiresReview);
            ps.setTimestamp(15, Timestamp.from(expires));
            ps.setTimestamp(16, Timestamp.from(now));
            ps.setTimestamp(17, Timestamp.from(now));
            ps.executeUpdate();
        }
        event(c, id, null, "REQUESTED", null, now);
        if (rejection != null) {
            transition(c, id, "REQUESTED", "REJECTED", rejection, now);
        } else {
            transition(c, id, "REQUESTED", "VALIDATED", null, now);
        }
        audit.record(c, userId, "TRANSFER_REQUESTED", "TRANSFER", id.toString(), Map.of(
                "amount", Long.toString(amount), "crossBank", Boolean.toString(crossBank),
                "outcome", rejection == null ? "VALIDATED" : rejection));

        Row row = load(c, id).orElseThrow();
        if (rejection == null) {
            tryPost(c, row, now);
        }
        return new CreateResult(view(c, userId, id), false);
    }

    private CreateResult replay(Connection c, UUID userId, Row existing, byte[] fingerprint) throws SQLException {
        if (!Crypto.constantTimeEquals(existing.fingerprint(), fingerprint)) {
            throw new ApiException(422, "IDEMPOTENCY_KEY_REUSED",
                    "This Idempotency-Key was already used for a different transfer");
        }
        return new CreateResult(view(c, userId, existing.id()), true);
    }

    // --- approvals, review, cancel, expiry ---------------------------------------------------------

    /** Customer step-up approval: a fresh TOTP code, bound to exactly this transfer's details. */
    public TransferView approve(ActiveSession session, UUID id, String code) {
        String limitKey = "approve:" + session.userId();
        limiter.check(limitKey, config.rateLimit().perAccount(), config.rateLimit().window());
        Outcome outcome = db.txRetrying(c -> {
            Row t = lockTransfer(c, id, "initiated_by = ?", session.userId());
            Instant now = clock.now();
            if (!"VALIDATED".equals(t.state()) || !t.requiresStepUp() || hasApproval(c, id, "TOTP")) {
                throw ApiException.conflict("NOT_AWAITING_APPROVAL", "This transfer is not waiting for your approval");
            }
            if (!now.isBefore(t.expiresAt())) {
                release(c, t, "CANCELLED", "EXPIRED", now);
                return Outcome.EXPIRED;
            }
            if (!totp.consume(c, session.userId(), code)) {
                return Outcome.BAD_CODE;
            }
            insertApproval(c, t, session.userId(), "TOTP", now);
            audit.record(c, session.userId(), "TRANSFER_APPROVED", "TRANSFER", id.toString(), Map.of("method", "TOTP"));
            tryPost(c, lockTransferRow(c, id), now);
            return Outcome.OK;
        });
        if (outcome == Outcome.BAD_CODE) {
            limiter.recordFailure(limitKey, config.rateLimit().window());
            audit.recordStandalone(session.userId(), "TRANSFER_APPROVAL_FAILED", "TRANSFER", id.toString(),
                    Map.of("reason", "INVALID_CODE"));
            throw new ApiException(401, "INVALID_CODE", "Invalid or already used authenticator code");
        }
        if (outcome == Outcome.EXPIRED) {
            throw new ApiException(410, "TRANSFER_EXPIRED", "The approval window passed; the transfer was cancelled");
        }
        limiter.reset(limitKey);
        return db.tx(c -> view(c, session.userId(), id));
    }

    /** Bank-operator review of a flagged cross-bank transfer from the operator's own institution. */
    public TransferView review(ActiveSession operator, UUID id, boolean approve) {
        if (operator.institutionId() == null) {
            throw ApiException.forbidden("NO_INSTITUTION", "Operator is not assigned to an institution");
        }
        db.txRetrying(c -> {
            Row t = lockTransfer(c, id, "source_institution_id = ? AND requires_review", operator.institutionId());
            Instant now = clock.now();
            if (t.initiatedBy().equals(operator.userId())) {
                throw ApiException.forbidden("SELF_REVIEW", "You cannot review your own transfer");
            }
            if (!"VALIDATED".equals(t.state()) || hasApproval(c, id, "OPERATOR")) {
                throw ApiException.conflict("NOT_AWAITING_REVIEW", "This transfer is not waiting for review");
            }
            if (approve) {
                insertApproval(c, t, operator.userId(), "OPERATOR", now);
                audit.record(c, operator.userId(), "TRANSFER_REVIEW_APPROVED", "TRANSFER", id.toString(), Map.of());
                tryPost(c, lockTransferRow(c, id), now);
            } else {
                release(c, t, "REJECTED", "OPERATOR_REJECTED", now);
                audit.record(c, operator.userId(), "TRANSFER_REVIEW_REJECTED", "TRANSFER", id.toString(), Map.of());
            }
            return null;
        });
        return db.tx(c -> view(c, operator.userId(), id));
    }

    public TransferView cancel(ActiveSession session, UUID id) {
        db.txRetrying(c -> {
            Row t = lockTransfer(c, id, "initiated_by = ?", session.userId());
            if (!"VALIDATED".equals(t.state())) {
                throw ApiException.conflict("NOT_CANCELLABLE", "Only transfers awaiting approval can be cancelled");
            }
            release(c, t, "CANCELLED", "CANCELLED_BY_CUSTOMER", clock.now());
            audit.record(c, session.userId(), "TRANSFER_CANCELLED", "TRANSFER", id.toString(), Map.of());
            return null;
        });
        return db.tx(c -> view(c, session.userId(), id));
    }

    /** Cancels transfers whose approval window has passed and releases their reserved funds. */
    public int expireDue() {
        List<UUID> due = db.tx(c -> {
            List<UUID> ids = new ArrayList<>();
            try (PreparedStatement ps = c.prepareStatement(
                    "SELECT id FROM transfers WHERE state = 'VALIDATED' AND expires_at <= ? ORDER BY expires_at LIMIT 100")) {
                ps.setTimestamp(1, Timestamp.from(clock.now()));
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) {
                        ids.add(rs.getObject(1, UUID.class));
                    }
                }
            }
            return ids;
        });
        int expired = 0;
        for (UUID id : due) {
            boolean done = db.txRetrying(c -> {
                Row t = lockTransferRow(c, id);
                Instant now = clock.now();
                if (!"VALIDATED".equals(t.state()) || now.isBefore(t.expiresAt())) {
                    return false;
                }
                release(c, t, "CANCELLED", "EXPIRED", now);
                audit.record(c, null, "TRANSFER_EXPIRED", "TRANSFER", id.toString(), Map.of());
                return true;
            });
            if (done) {
                expired++;
            }
        }
        return expired;
    }

    // --- reads ------------------------------------------------------------------------------------

    public List<TransferView> listForUser(UUID userId, int limit) {
        return db.tx(c -> {
            List<UUID> ids = new ArrayList<>();
            try (PreparedStatement ps = c.prepareStatement("""
                    SELECT t.id FROM transfers t
                    WHERE t.initiated_by = ?
                       OR (t.state = ANY (?) AND t.destination_account_id IN
                             (SELECT id FROM accounts WHERE owner_user_id = ?))
                    ORDER BY t.created_at DESC LIMIT ?""")) {
                ps.setObject(1, userId);
                ps.setArray(2, c.createArrayOf("text", POSTED_STATES.toArray()));
                ps.setObject(3, userId);
                ps.setInt(4, Math.min(Math.max(limit, 1), 100));
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) {
                        ids.add(rs.getObject(1, UUID.class));
                    }
                }
            }
            List<TransferView> out = new ArrayList<>();
            for (UUID id : ids) {
                out.add(view(c, userId, id));
            }
            return out;
        });
    }

    /** Visible to the initiator, to the payee once posted, and to operators of either bank. */
    public TransferView get(ActiveSession session, UUID id) {
        return db.tx(c -> {
            Row t = load(c, id).orElseThrow(() -> ApiException.notFound("Transfer not found"));
            boolean visible = t.initiatedBy().equals(session.userId())
                    || (POSTED_STATES.contains(t.state()) && ownsAccount(c, session.userId(), t.destination()))
                    || (session.roles().contains("BANK_OPERATOR") && session.institutionId() != null
                            && (session.institutionId().equals(t.sourceInstitution())
                                    || session.institutionId().equals(t.destinationInstitution())));
            if (!visible) {
                throw ApiException.notFound("Transfer not found");
            }
            return view(c, session.userId(), id);
        });
    }

    public List<TransferView> reviewQueue(ActiveSession operator) {
        if (operator.institutionId() == null) {
            return List.of();
        }
        return db.tx(c -> {
            List<UUID> ids = new ArrayList<>();
            try (PreparedStatement ps = c.prepareStatement("""
                    SELECT t.id FROM transfers t
                    WHERE t.state = 'VALIDATED' AND t.requires_review AND t.source_institution_id = ?
                      AND NOT EXISTS (SELECT 1 FROM approvals a WHERE a.transfer_id = t.id AND a.method = 'OPERATOR')
                    ORDER BY t.created_at""")) {
                ps.setObject(1, operator.institutionId());
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) {
                        ids.add(rs.getObject(1, UUID.class));
                    }
                }
            }
            List<TransferView> out = new ArrayList<>();
            for (UUID id : ids) {
                out.add(view(c, operator.userId(), id));
            }
            return out;
        });
    }

    // --- posting ----------------------------------------------------------------------------------

    /** Posts the journal if every required approval is present; otherwise leaves the transfer waiting. */
    private void tryPost(Connection c, Row t, Instant now) throws SQLException {
        if (!"VALIDATED".equals(t.state())) {
            return;
        }
        if (t.requiresStepUp() && !approvalMatches(c, t, "TOTP")) {
            return;
        }
        if (t.requiresReview() && !approvalMatches(c, t, "OPERATOR")) {
            return;
        }
        unreserve(c, t);
        UUID journal = UUID.randomUUID();
        try (PreparedStatement ps = c.prepareStatement(
                "INSERT INTO journals (id, reference, transfer_id, currency, description) VALUES (?, ?, ?, ?, ?)")) {
            ps.setObject(1, journal);
            ps.setString(2, "TRF-" + t.id());
            ps.setObject(3, t.id());
            ps.setString(4, t.currency());
            ps.setString(5, t.crossBank() ? "Interbank transfer" : "Transfer");
            ps.executeUpdate();
        }
        if (t.crossBank()) {
            // Each bank's half balances on its own: the deposit moves against that bank's settlement position.
            UUID sourceSettlement = AccountService.lockSystemAccount(c, t.sourceInstitution(), "SETTLEMENT");
            UUID destinationSettlement = AccountService.lockSystemAccount(c, t.destinationInstitution(), "SETTLEMENT");
            Postings.line(c, journal, t.source(), t.amount(), 0);
            Postings.line(c, journal, sourceSettlement, 0, t.amount());
            Postings.line(c, journal, destinationSettlement, t.amount(), 0);
            Postings.line(c, journal, t.destination(), 0, t.amount());
        } else {
            Postings.line(c, journal, t.source(), t.amount(), 0);
            Postings.line(c, journal, t.destination(), 0, t.amount());
        }
        transition(c, t.id(), "VALIDATED", "APPROVED", null, now);
        try (PreparedStatement ps = c.prepareStatement("UPDATE transfers SET approved_at = ? WHERE id = ?")) {
            ps.setTimestamp(1, Timestamp.from(now));
            ps.setObject(2, t.id());
            ps.executeUpdate();
        }
        if (t.crossBank()) {
            enqueueSettlement(c, t, now);
        } else {
            transition(c, t.id(), "APPROVED", "CONFIRMED", "BOOK_TRANSFER", now);
        }
        audit.record(c, t.initiatedBy(), "TRANSFER_POSTED", "TRANSFER", t.id().toString(),
                Map.of("journal", "TRF-" + t.id(), "crossBank", Boolean.toString(t.crossBank())));
    }

    /** Transactional outbox: the Phase 3 worker turns this into an on-chain settlement token movement. */
    private void enqueueSettlement(Connection c, Row t, Instant now) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement("""
                INSERT INTO outbox (aggregate_type, aggregate_id, event_type, payload, created_at, next_attempt_at)
                SELECT 'TRANSFER', ?, 'SETTLEMENT_REQUESTED',
                       jsonb_build_object('transferId', ?::text, 'amount', ?::bigint, 'currency', ?::text,
                                          'from', si.pseudonym, 'to', di.pseudonym),
                       ?, ?
                FROM institutions si, institutions di WHERE si.id = ? AND di.id = ?""")) {
            ps.setObject(1, t.id());
            ps.setString(2, t.id().toString());
            ps.setLong(3, t.amount());
            ps.setString(4, t.currency());
            ps.setTimestamp(5, Timestamp.from(now));
            ps.setTimestamp(6, Timestamp.from(now));
            ps.setObject(7, t.sourceInstitution());
            ps.setObject(8, t.destinationInstitution());
            ps.executeUpdate();
        }
    }

    // --- helpers ----------------------------------------------------------------------------------

    enum Outcome {
        OK, BAD_CODE, EXPIRED
    }

    /**
     * Loads the transfer (visibility filter applied in SQL, so other users' ids look missing), locks
     * its accounts in canonical order, then re-reads the row under lock.
     */
    private Row lockTransfer(Connection c, UUID id, String filter, Object filterArg) throws SQLException {
        Row t;
        try (PreparedStatement ps = c.prepareStatement(ROW_SQL + " WHERE id = ? AND " + filter)) {
            ps.setObject(1, id);
            ps.setObject(2, filterArg);
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) {
                    throw ApiException.notFound("Transfer not found");
                }
                t = mapRow(rs);
            }
        }
        lockAccounts(c, involvedAccounts(c, t.source(), t.destination(), t.sourceInstitution(), t.destinationInstitution()));
        return lockTransferRow(c, id);
    }

    private Row lockTransferRow(Connection c, UUID id) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement(ROW_SQL + " WHERE id = ? FOR UPDATE")) {
            ps.setObject(1, id);
            try (ResultSet rs = ps.executeQuery()) {
                rs.next();
                return mapRow(rs);
            }
        }
    }

    private static TreeSet<UUID> involvedAccounts(Connection c, UUID source, UUID destination, UUID sourceInstitution,
            UUID destinationInstitution) throws SQLException {
        TreeSet<UUID> ids = new TreeSet<>((a, b) -> a.toString().compareTo(b.toString()));
        ids.add(source);
        ids.add(destination);
        if (!sourceInstitution.equals(destinationInstitution)) {
            try (PreparedStatement ps = c.prepareStatement("""
                    SELECT id FROM accounts WHERE account_type = 'SETTLEMENT' AND currency = ?
                    AND institution_id IN (?, ?)""")) {
                ps.setString(1, AccountService.CURRENCY);
                ps.setObject(2, sourceInstitution);
                ps.setObject(3, destinationInstitution);
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) {
                        ids.add(rs.getObject(1, UUID.class));
                    }
                }
            }
        }
        return ids;
    }

    /** Locks account rows in the same total order everywhere (text order of the UUID), so no deadlocks. */
    private static void lockAccounts(Connection c, TreeSet<UUID> ids) throws SQLException {
        Array arr = c.createArrayOf("uuid", ids.toArray());
        try (PreparedStatement ps = c.prepareStatement(
                "SELECT id FROM accounts WHERE id = ANY (?) ORDER BY id::text FOR UPDATE")) {
            ps.setArray(1, arr);
            ps.executeQuery().close();
        }
    }

    private static void requireActive(Connection c, UUID accountId) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement("SELECT status FROM accounts WHERE id = ?")) {
            ps.setObject(1, accountId);
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next() || !"ACTIVE".equals(rs.getString(1))) {
                    throw ApiException.conflict("ACCOUNT_NOT_ACTIVE", "The source account is not active");
                }
            }
        }
    }

    private static long spentToday(Connection c, UUID userId, Instant now) throws SQLException {
        Instant startOfDay = now.atZone(ZoneOffset.UTC).toLocalDate().atStartOfDay(ZoneOffset.UTC).toInstant();
        try (PreparedStatement ps = c.prepareStatement("""
                SELECT coalesce(sum(amount_minor), 0) FROM transfers
                WHERE initiated_by = ? AND created_at >= ? AND state NOT IN ('REJECTED', 'CANCELLED', 'FAILED')""")) {
            ps.setObject(1, userId);
            ps.setTimestamp(2, Timestamp.from(startOfDay));
            try (ResultSet rs = ps.executeQuery()) {
                rs.next();
                return rs.getLong(1);
            }
        }
    }

    /** Holds funds for a pending transfer. Fails (no row updated) if available balance is too low. */
    private static boolean reserve(Connection c, UUID account, long amount) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement("""
                UPDATE account_balances SET reserved_minor = reserved_minor + ?, updated_at = now()
                WHERE account_id = ? AND ledger_minor - reserved_minor >= ?""")) {
            ps.setLong(1, amount);
            ps.setObject(2, account);
            ps.setLong(3, amount);
            return ps.executeUpdate() == 1;
        }
    }

    private static void unreserve(Connection c, Row t) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement("""
                UPDATE account_balances SET reserved_minor = reserved_minor - ?, updated_at = now()
                WHERE account_id = ? AND reserved_minor >= ?""")) {
            ps.setLong(1, t.amount());
            ps.setObject(2, t.source());
            ps.setLong(3, t.amount());
            if (ps.executeUpdate() != 1) {
                throw new IllegalStateException("reservation missing for transfer " + t.id());
            }
        }
    }

    /** Ends a VALIDATED transfer without posting, returning its reserved funds. */
    private void release(Connection c, Row t, String toState, String reason, Instant now) throws SQLException {
        unreserve(c, t);
        transition(c, t.id(), "VALIDATED", toState, reason, now);
    }

    private static boolean isNewBeneficiary(Connection c, UUID userId, UUID destination) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement("""
                SELECT 1 FROM transfers WHERE initiated_by = ? AND destination_account_id = ? AND state = ANY (?) LIMIT 1""")) {
            ps.setObject(1, userId);
            ps.setObject(2, destination);
            ps.setArray(3, c.createArrayOf("text", POSTED_STATES.toArray()));
            try (ResultSet rs = ps.executeQuery()) {
                return !rs.next();
            }
        }
    }

    /** What an approval signs off on. If any of these details differed, the approval would not match. */
    private static byte[] contextHash(Row t) {
        return Crypto.sha256(String.join("|", t.id().toString(), t.source().toString(), t.destination().toString(),
                Long.toString(t.amount()), t.currency(), t.expiresAt().toString()));
    }

    private static void insertApproval(Connection c, Row t, UUID approver, String method, Instant now)
            throws SQLException {
        try (PreparedStatement ps = c.prepareStatement("""
                INSERT INTO approvals (transfer_id, approver_id, method, context_hash, expires_at, used_at, created_at)
                VALUES (?, ?, ?, ?, ?, ?, ?)""")) {
            ps.setObject(1, t.id());
            ps.setObject(2, approver);
            ps.setString(3, method);
            ps.setBytes(4, contextHash(t));
            ps.setTimestamp(5, Timestamp.from(t.expiresAt()));
            ps.setTimestamp(6, Timestamp.from(now));
            ps.setTimestamp(7, Timestamp.from(now));
            ps.executeUpdate();
        }
    }

    private static boolean hasApproval(Connection c, UUID id, String method) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement("SELECT 1 FROM approvals WHERE transfer_id = ? AND method = ?")) {
            ps.setObject(1, id);
            ps.setString(2, method);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next();
            }
        }
    }

    private static boolean approvalMatches(Connection c, Row t, String method) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement(
                "SELECT context_hash FROM approvals WHERE transfer_id = ? AND method = ?")) {
            ps.setObject(1, t.id());
            ps.setString(2, method);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() && Crypto.constantTimeEquals(rs.getBytes(1), contextHash(t));
            }
        }
    }

    private static void transition(Connection c, UUID id, String from, String to, String reason, Instant now)
            throws SQLException {
        try (PreparedStatement ps = c.prepareStatement("""
                UPDATE transfers SET state = ?, failure_reason = coalesce(?, failure_reason), updated_at = ?
                WHERE id = ? AND state = ?""")) {
            ps.setString(1, to);
            ps.setString(2, "REJECTED".equals(to) || "CANCELLED".equals(to) || "FAILED".equals(to) ? reason : null);
            ps.setTimestamp(3, Timestamp.from(now));
            ps.setObject(4, id);
            ps.setString(5, from);
            if (ps.executeUpdate() != 1) {
                throw ApiException.conflict("STATE_CHANGED", "The transfer changed state; refresh and try again");
            }
        }
        event(c, id, from, to, reason, now);
    }

    private static void event(Connection c, UUID id, String from, String to, String reason, Instant now)
            throws SQLException {
        try (PreparedStatement ps = c.prepareStatement("""
                INSERT INTO transfer_events (transfer_id, from_state, to_state, reason, occurred_at) VALUES (?, ?, ?, ?, ?)""")) {
            ps.setObject(1, id);
            ps.setString(2, from);
            ps.setString(3, to);
            ps.setString(4, reason);
            ps.setTimestamp(5, Timestamp.from(now));
            ps.executeUpdate();
        }
    }

    private static boolean ownsAccount(Connection c, UUID userId, UUID accountId) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement("SELECT 1 FROM accounts WHERE id = ? AND owner_user_id = ?")) {
            ps.setObject(1, accountId);
            ps.setObject(2, userId);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next();
            }
        }
    }

    private static final String ROW_SQL = """
            SELECT id, source_account_id, destination_account_id, source_institution_id, destination_institution_id,
                   amount_minor, currency, state, initiated_by, requires_step_up, requires_review, expires_at,
                   request_fingerprint
            FROM transfers""";

    private static Row mapRow(ResultSet rs) throws SQLException {
        return new Row(rs.getObject(1, UUID.class), rs.getObject(2, UUID.class), rs.getObject(3, UUID.class),
                rs.getObject(4, UUID.class), rs.getObject(5, UUID.class), rs.getLong(6), rs.getString(7),
                rs.getString(8), rs.getObject(9, UUID.class), rs.getBoolean(10), rs.getBoolean(11),
                rs.getTimestamp(12).toInstant(), rs.getBytes(13));
    }

    private static Optional<Row> load(Connection c, UUID id) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement(ROW_SQL + " WHERE id = ?")) {
            ps.setObject(1, id);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? Optional.of(mapRow(rs)) : Optional.empty();
            }
        }
    }

    private static Optional<Row> findByKey(Connection c, UUID userId, String key) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement(ROW_SQL + " WHERE initiated_by = ? AND idempotency_key = ?")) {
            ps.setObject(1, userId);
            ps.setString(2, key);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? Optional.of(mapRow(rs)) : Optional.empty();
            }
        }
    }

    private static TransferView view(Connection c, UUID viewer, UUID id) throws SQLException {
        TransferView v;
        try (PreparedStatement ps = c.prepareStatement("""
                SELECT t.id, t.state, t.amount_minor, t.currency, t.reference, t.initiated_by,
                       sa.account_number, si.name, su.display_name,
                       da.account_number, di.name, du.display_name,
                       t.source_institution_id <> t.destination_institution_id,
                       t.requires_step_up,
                       EXISTS (SELECT 1 FROM approvals a WHERE a.transfer_id = t.id AND a.method = 'TOTP'),
                       t.requires_review,
                       EXISTS (SELECT 1 FROM approvals a WHERE a.transfer_id = t.id AND a.method = 'OPERATOR'),
                       t.failure_reason, t.created_at, t.expires_at, t.approved_at
                FROM transfers t
                JOIN accounts sa ON sa.id = t.source_account_id
                JOIN institutions si ON si.id = sa.institution_id
                JOIN users su ON su.id = sa.owner_user_id
                JOIN accounts da ON da.id = t.destination_account_id
                JOIN institutions di ON di.id = da.institution_id
                JOIN users du ON du.id = da.owner_user_id
                WHERE t.id = ?""")) {
            ps.setObject(1, id);
            try (ResultSet rs = ps.executeQuery()) {
                rs.next();
                UUID initiator = rs.getObject(6, UUID.class);
                Timestamp approvedAt = rs.getTimestamp(21);
                v = new TransferView(rs.getObject(1, UUID.class), rs.getString(2),
                        initiator.equals(viewer) ? "OUTGOING" : "INCOMING", rs.getLong(3), rs.getString(4),
                        rs.getString(5), new Party(rs.getString(7), rs.getString(8), rs.getString(9)),
                        new Party(rs.getString(10), rs.getString(11), rs.getString(12)), rs.getBoolean(13),
                        rs.getBoolean(14), rs.getBoolean(15), rs.getBoolean(16), rs.getBoolean(17), rs.getString(18),
                        rs.getTimestamp(19).toInstant(), rs.getTimestamp(20).toInstant(),
                        approvedAt == null ? null : approvedAt.toInstant(), new ArrayList<>());
            }
        }
        try (PreparedStatement ps = c.prepareStatement(
                "SELECT from_state, to_state, reason, occurred_at FROM transfer_events WHERE transfer_id = ? ORDER BY id")) {
            ps.setObject(1, id);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    v.events().add(new Event(rs.getString(1), rs.getString(2), rs.getString(3),
                            rs.getTimestamp(4).toInstant()));
                }
            }
        }
        return v;
    }
}
