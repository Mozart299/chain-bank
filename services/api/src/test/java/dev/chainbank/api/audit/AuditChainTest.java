package dev.chainbank.api.audit;

import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.greaterThan;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;

import jakarta.inject.Inject;

import org.junit.jupiter.api.Test;

import io.quarkus.test.junit.QuarkusTest;

import dev.chainbank.api.ApiTestBase;

@QuarkusTest
class AuditChainTest extends ApiTestBase {

    @Inject
    AuditLog audit;

    @Test
    void securityEventsFormAValidChain() {
        Session s = newCustomer("audited");
        stepUp(s);
        Session auditor = newUserWithRole("chain-auditor", "AUDITOR");
        as(auditor).get("/api/audit/chain/verify").then().statusCode(200)
                .body("valid", equalTo(true))
                .body("eventsChecked", greaterThan(3));
    }

    @Test
    void auditEventsCannotBeModified() {
        newCustomer("immutable");
        try (Connection c = dataSource.getConnection();
                PreparedStatement ps = c.prepareStatement("UPDATE audit_events SET action = 'X'")) {
            ps.executeUpdate();
            throw new AssertionError("update should have been rejected");
        } catch (SQLException e) {
            assertTrue(e.getMessage().contains("append-only"), e.getMessage());
        }
    }

    /**
     * Simulates an attacker with database-owner access who bypasses the append-only trigger and edits a
     * past event. The hash chain still exposes the edit.
     */
    @Test
    void tamperingIsDetectedEvenWhenTriggersAreBypassed() throws SQLException {
        newCustomer("tamper");
        assertTrue(audit.verifyChain().valid());
        long id;
        String original;
        try (Connection c = dataSource.getConnection()) {
            try (PreparedStatement ps = c.prepareStatement("SELECT id, action FROM audit_events ORDER BY id LIMIT 1");
                    ResultSet rs = ps.executeQuery()) {
                rs.next();
                id = rs.getLong(1);
                original = rs.getString(2);
            }
            try {
                c.createStatement().execute("ALTER TABLE audit_events DISABLE TRIGGER audit_events_immutable");
                c.createStatement().execute("UPDATE audit_events SET action = 'NOTHING_TO_SEE' WHERE id = " + id);
                AuditLog.Verification v = audit.verifyChain();
                assertFalse(v.valid());
                assertTrue(v.firstInvalidEventId() == id, "first broken event should be the edited one");
            } finally {
                try (PreparedStatement restore = c.prepareStatement("UPDATE audit_events SET action = ? WHERE id = ?")) {
                    restore.setString(1, original);
                    restore.setLong(2, id);
                    restore.executeUpdate();
                }
                c.createStatement().execute("ALTER TABLE audit_events ENABLE TRIGGER audit_events_immutable");
            }
        }
        assertTrue(audit.verifyChain().valid());
    }
}
