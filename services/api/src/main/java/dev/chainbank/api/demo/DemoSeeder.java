package dev.chainbank.api.demo;

import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.List;
import java.util.UUID;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;
import jakarta.inject.Inject;

import org.jboss.logging.Logger;

import io.quarkus.runtime.StartupEvent;

import dev.chainbank.api.auth.PasswordHasher;
import dev.chainbank.api.auth.SecretCipher;
import dev.chainbank.api.auth.Totp;
import dev.chainbank.api.ledger.AccountService;
import dev.chainbank.api.platform.ChainbankConfig;
import dev.chainbank.api.platform.Db;

/**
 * Creates fictional demo users when {@code chainbank.demo.seed=true} (Compose and dev only).
 * They share one documented password and one TOTP secret so a learner can sign in with any
 * authenticator app. Never enable this in a real deployment.
 */
@ApplicationScoped
public class DemoSeeder {

    /** Base32 TOTP secret shared by all demo users. Public on purpose, like the Anvil dev keys. */
    public static final String DEMO_TOTP_SECRET = "CHAINBANKDEMOTOTPSECRETXUGX23456";

    private static final Logger LOG = Logger.getLogger(DemoSeeder.class);

    record DemoUser(String email, String name, String institution, List<String> roles) {
    }

    static final List<DemoUser> USERS = List.of(
            new DemoUser("alice@nile.demo", "Alice (Nile customer)", "BANK_A", List.of("CUSTOMER")),
            new DemoUser("bob@rwenzori.demo", "Bob (Rwenzori customer)", "BANK_B", List.of("CUSTOMER")),
            new DemoUser("olivia.ops@nile.demo", "Olivia (Nile operator)", "BANK_A", List.of("BANK_OPERATOR")),
            new DemoUser("sam.admin@chainbank.demo", "Sam (system admin)", null, List.of("SYSTEM_ADMIN")),
            new DemoUser("aria.audit@chainbank.demo", "Aria (auditor)", null, List.of("AUDITOR")));

    @Inject
    ChainbankConfig config;

    @Inject
    Db db;

    @Inject
    PasswordHasher passwords;

    @Inject
    SecretCipher cipher;

    @Inject
    AccountService accounts;

    void onStart(@Observes StartupEvent ev) {
        if (!config.demo().seed()) {
            return;
        }
        String password = config.demo().password()
                .orElseThrow(() -> new IllegalStateException("chainbank.demo.password is required when seeding"));
        byte[] secret = Totp.base32Decode(DEMO_TOTP_SECRET);
        int created = db.tx(c -> {
            int n = 0;
            for (DemoUser u : USERS) {
                UUID id = null;
                try (PreparedStatement check = c.prepareStatement("SELECT id FROM users WHERE lower(email) = ?")) {
                    check.setString(1, u.email());
                    try (ResultSet rs = check.executeQuery()) {
                        if (rs.next()) {
                            id = rs.getObject(1, UUID.class);
                        }
                    }
                }
                if (id == null) {
                    id = UUID.randomUUID();
                    try (PreparedStatement ps = c.prepareStatement("""
                            INSERT INTO users (id, institution_id, email, display_name, password_hash, totp_secret_cipher,
                                               totp_enabled_at, status)
                            VALUES (?, (SELECT id FROM institutions WHERE code = ?), ?, ?, ?, ?, now(), 'ACTIVE')""")) {
                        ps.setObject(1, id);
                        ps.setString(2, u.institution());
                        ps.setString(3, u.email());
                        ps.setString(4, u.name());
                        ps.setString(5, passwords.hash(password));
                        ps.setBytes(6, cipher.encrypt(secret, id));
                        ps.executeUpdate();
                    }
                    for (String role : u.roles()) {
                        try (PreparedStatement ps = c.prepareStatement("INSERT INTO user_roles (user_id, role) VALUES (?, ?)")) {
                            ps.setObject(1, id);
                            ps.setString(2, role);
                            ps.executeUpdate();
                        }
                    }
                    n++;
                }
                if (u.roles().contains("CUSTOMER")) {
                    accounts.ensureDefaultAccount(c, id);
                }
            }
            return n;
        });
        LOG.warnf("Demo seeding enabled: %d demo user(s) created. Do not use in production.", created);
    }
}
