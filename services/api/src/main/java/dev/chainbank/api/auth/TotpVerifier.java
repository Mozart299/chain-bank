package dev.chainbank.api.auth;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.UUID;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import dev.chainbank.api.platform.AppClock;

/**
 * Single-use TOTP verification shared by login, step-up and transfer approval. Each accepted code's
 * time step is recorded in the same transaction, so a code can never be used twice, even by two
 * concurrent requests.
 */
@ApplicationScoped
public class TotpVerifier {

    @Inject
    SecretCipher cipher;

    @Inject
    AppClock clock;

    public boolean consume(Connection c, UUID userId, String code) throws SQLException {
        byte[] blob;
        long lastStep;
        try (PreparedStatement ps = c.prepareStatement(
                "SELECT totp_secret_cipher, totp_last_step FROM users WHERE id = ? FOR UPDATE")) {
            ps.setObject(1, userId);
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next() || rs.getBytes(1) == null) {
                    return false;
                }
                blob = rs.getBytes(1);
                lastStep = rs.getLong(2);
            }
        }
        long step = Totp.verify(cipher.decrypt(blob, userId), code, clock.now(), lastStep);
        if (step < 0) {
            return false;
        }
        try (PreparedStatement ps = c.prepareStatement(
                "UPDATE users SET totp_last_step = ? WHERE id = ? AND totp_last_step < ?")) {
            ps.setLong(1, step);
            ps.setObject(2, userId);
            ps.setLong(3, step);
            return ps.executeUpdate() == 1;
        }
    }
}
