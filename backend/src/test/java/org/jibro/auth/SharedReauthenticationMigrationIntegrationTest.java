package org.jibro.auth;

import static org.junit.jupiter.api.Assertions.*;

import java.util.UUID;
import javax.sql.DataSource;

import org.flywaydb.core.Flyway;
import org.jibro.global.config.migration.V47__shared_reauthentication_limits;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

@SpringBootTest
@ActiveProfiles("test")
class SharedReauthenticationMigrationIntegrationTest {
    @Autowired
    DataSource source;
    @Autowired
    JdbcTemplate db;
    @Autowired
    LoginAttemptLimiter limits;
    private String schema;
    private static final String SECRET = "test-only-jwt-signing-key-at-least-32-bytes";

    @BeforeEach
    void isolatedSchema() {
        assertEquals("jibro_test", db.queryForObject("SELECT current_database()", String.class));
        schema = "reauth_migration_" + UUID.randomUUID().toString().replace("-", "");
        Flyway.configure().dataSource(source).schemas(schema).defaultSchema(schema)
                .locations("classpath:db/migration").target("46").load().migrate();
    }

    @AfterEach
    void cleanup() {
        if (schema != null && schema.matches("reauth_migration_[a-f0-9]{32}"))
            db.execute("DROP SCHEMA " + schema + " CASCADE");
    }

    private Flyway upgrade() {
        return Flyway.configure().dataSource(source).schemas(schema).defaultSchema(schema)
                .locations("classpath:db/migration").javaMigrations(new V47__shared_reauthentication_limits(SECRET)).load();
    }

    private UUID user(String name) {
        UUID id = UUID.randomUUID();
        db.update("INSERT INTO " + schema + ".app_user(id,name,email) VALUES(?,?,?)", id, name, name + "@example.invalid");
        return id;
    }

    @Test
    void emptyUpgradeRemovesTheRetiredTablesExactlyOnce() {
        assertEquals(1, upgrade().migrate().migrationsExecuted);
        assertEquals(0, upgrade().migrate().migrationsExecuted);
        assertNull(db.queryForObject("SELECT to_regclass(?)::text", String.class, schema + ".account_connection_attempt"));
        assertNull(db.queryForObject("SELECT to_regclass(?)::text", String.class, schema + ".preparation_deletion_attempt"));
    }

    @Test
    void failuresSurviveMigrationAndAFailedConversionRollsBackBeforeRetry() {
        UUID blocked = user("blocked"), combined = user("combined"), expired = user("expired");
        db.update("INSERT INTO " + schema + ".account_connection_attempt VALUES(?,5,clock_timestamp()+interval '6 minutes'),(?,2,NULL),(?,5,clock_timestamp()-interval '1 minute')", blocked, combined, expired);
        db.update("INSERT INTO " + schema + ".preparation_deletion_attempt VALUES(?,1,clock_timestamp()-interval '3 minutes',NULL)", combined);
        String existing = limits.key("account:combined@example.invalid");
        db.update("INSERT INTO " + schema + ".login_attempt(id,account_key,address_key,successful) VALUES(?,?,?,false)", UUID.randomUUID(), existing, "a".repeat(64));
        db.execute("CREATE FUNCTION " + schema + ".fail_conversion() RETURNS trigger LANGUAGE plpgsql AS $$ BEGIN RAISE EXCEPTION 'injected storage failure'; END $$");
        db.execute("CREATE TRIGGER reject_migration BEFORE INSERT ON " + schema + ".login_attempt FOR EACH ROW EXECUTE FUNCTION " + schema + ".fail_conversion()");
        assertThrows(org.flywaydb.core.api.FlywayException.class, () -> upgrade().migrate());
        assertEquals(3, db.queryForObject("SELECT count(*) FROM " + schema + ".account_connection_attempt", Integer.class));
        assertEquals(1, db.queryForObject("SELECT count(*) FROM " + schema + ".login_attempt", Integer.class));
        db.execute("DROP TRIGGER reject_migration ON " + schema + ".login_attempt");
        assertEquals(1, upgrade().migrate().migrationsExecuted);
        assertEquals(5, count("blocked"));
        assertEquals(4, count("combined"));
        assertEquals(0, count("expired"));
        var remaining = db.queryForObject("SELECT extract(epoch FROM min(created_at)+interval '15 minutes'-clock_timestamp()) FROM " + schema + ".login_attempt WHERE account_key=?", Double.class, limits.key("account:blocked@example.invalid"));
        assertTrue(remaining > 240 && remaining <= 360, "The remaining six-minute block must not become a fresh fifteen-minute block");
        assertEquals(0, upgrade().migrate().migrationsExecuted);
        assertEquals(4, count("combined"));
    }

    private int count(String name) {
        return db.queryForObject("SELECT count(*) FROM " + schema + ".login_attempt WHERE account_key=? AND successful IS DISTINCT FROM true", Integer.class,
                limits.key("account:" + name + "@example.invalid"));
    }
}
