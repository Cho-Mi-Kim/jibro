package org.jibro.global.config.migration;

import java.nio.charset.StandardCharsets;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.HexFormat;
import java.util.Locale;
import java.util.UUID;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

import org.flywaydb.core.api.migration.BaseJavaMigration;
import org.flywaydb.core.api.migration.Context;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * One-time schema conversion. Keep this historic key format independent of mutable auth services.
 * Stop old application instances before migrating: their per-feature attempt tables are removed.
 */
@Component
public class V47__shared_reauthentication_limits extends BaseJavaMigration {
    private final byte[] secret;

    public V47__shared_reauthentication_limits(@Value("${app.auth.jwt-secret}") String secret) {
        this.secret = secret.getBytes(StandardCharsets.UTF_8);
    }

    @Override
    public void migrate(Context context) throws Exception {
        var connection = context.getConnection();
        Instant now;
        try (var statement = connection.createStatement(); var rows = statement.executeQuery("SELECT clock_timestamp()")) {
            rows.next();
            now = rows.getTimestamp(1).toInstant();
        }
        try (var statement = connection.createStatement(); var rows = statement.executeQuery("""
                SELECT a.user_id,u.email,a.failures,a.blocked_until,NULL::timestamptz AS started_at
                FROM account_connection_attempt a JOIN app_user u ON u.id=a.user_id
                UNION ALL
                SELECT a.user_id,u.email,a.failures,a.blocked_until,a.started_at
                FROM preparation_deletion_attempt a JOIN app_user u ON u.id=a.user_id
                """); var insert = connection.prepareStatement("INSERT INTO login_attempt(id,account_key,address_key,created_at,successful) VALUES(?,?,?,?,false)")) {
            while (rows.next()) {
                var blocked = rows.getTimestamp("blocked_until");
                var started = rows.getTimestamp("started_at");
                if (blocked != null && !blocked.toInstant().isAfter(now)) continue;
                Instant created = blocked != null ? blocked.toInstant().minus(15, ChronoUnit.MINUTES)
                        : started == null ? now : started.toInstant();
                if (!created.isAfter(now.minus(15, ChronoUnit.MINUTES))) continue;
                int failures = blocked != null ? 5 : Math.min(5, rows.getInt("failures"));
                String account = key("account:" + rows.getString("email").trim().toLowerCase(Locale.ROOT));
                // The former tables never recorded IPs; do not charge a guessed/shared address.
                String address = key("legacy-reauthentication:" + rows.getObject("user_id"));
                for (int i = 0; i < failures; i++) {
                    insert.setObject(1, UUID.randomUUID());
                    insert.setString(2, account);
                    insert.setString(3, address);
                    insert.setTimestamp(4, Timestamp.from(created));
                    insert.executeUpdate();
                }
            }
        }
        try (var statement = connection.createStatement()) {
            statement.execute("DROP TABLE account_connection_attempt, preparation_deletion_attempt");
        }
    }

    private String key(String value) throws Exception {
        var mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(secret, "HmacSHA256"));
        return HexFormat.of().formatHex(mac.doFinal(value.getBytes(StandardCharsets.UTF_8)));
    }
}
