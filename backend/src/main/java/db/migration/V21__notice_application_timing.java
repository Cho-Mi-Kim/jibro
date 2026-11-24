package db.migration;

import java.sql.Date;
import java.sql.Timestamp;
import java.time.DateTimeException;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.util.HashSet;
import java.util.regex.Pattern;

import org.flywaydb.core.api.migration.BaseJavaMigration;
import org.flywaydb.core.api.migration.Context;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * Frozen V21 backfill: changing application DTOs/parsers must not alter migration replay.
 * Subsequent interpretation changes require a new migration. Content and snapshots remain untouched.
 */
public class V21__notice_application_timing extends BaseJavaMigration {
    private static final ZoneId SEOUL = ZoneId.of("Asia/Seoul");
    private static final Pattern CLOCK = Pattern.compile("(?<!\\d)(?:(오전|오후)\\s*)?([01]?\\d|2[0-3])(?::([0-5]\\d)(?!\\d)|시(?:\\s*([0-5]?\\d)분)?)");

    @Override
    public void migrate(Context context) throws Exception {
        var connection = context.getConnection();
        try (var ddl = connection.createStatement()) {
            ddl.execute("ALTER TABLE notice ADD COLUMN application_start_at timestamptz, ADD COLUMN application_end_at timestamptz");
            ddl.execute("CREATE INDEX notice_application_end_at_idx ON notice(application_end_at) WHERE basic_review = 'PASS'");
        }
        var json = new ObjectMapper();
        try (var read = connection.prepareStatement("SELECT id, start_date, end_date, content::text FROM notice");
             var update = connection.prepareStatement("UPDATE notice SET application_start_at=?, application_end_at=? WHERE id=?")) {
            read.setFetchSize(200);
            try (var rows = read.executeQuery()) {
                while (rows.next()) {
                    Date start = rows.getDate("start_date");
                    Date end = rows.getDate("end_date");
                    JsonNode schedules = json.readTree(rows.getString("content")).path("schedules");
                    Instant opens = boundary(start == null ? null : start.toLocalDate(), schedules, false);
                    Instant closes = boundary(end == null ? null : end.toLocalDate(), schedules, true);
                    update.setTimestamp(1, opens == null ? null : Timestamp.from(opens));
                    update.setTimestamp(2, closes == null ? null : Timestamp.from(closes));
                    update.setObject(3, rows.getObject("id"));
                    update.executeUpdate();
                }
            }
        }
    }

    private static Instant boundary(LocalDate date, JsonNode schedules, boolean closing) {
        if (date == null || !schedules.isArray()) return null;
        var candidates = new HashSet<Instant>();
        for (JsonNode schedule : schedules) {
            JsonNode titleNode = schedule.path("title");
            JsonNode dateNode = schedule.path("date");
            if (!titleNode.isTextual() || !dateNode.isTextual()) continue;
            String title = titleNode.asText();
            String value = dateNode.asText();
            if (!basicTitle(title, closing) || !date.equals(day(value))) continue;
            Instant time = instant(title, value);
            if (time != null) candidates.add(time);
        }
        return candidates.size() == 1 ? candidates.iterator().next() : null;
    }

    private static boolean basicTitle(String title, boolean closing) {
        String plain = title.replaceAll("\\([^)]*(?:\\d{1,2}:\\d{2}|\\d{1,2}시|조기\\s*중단)[^)]*\\)", "")
                .replaceAll("\\s", "");
        return plain.matches(closing ? "(?:신청접수|신청|접수)마감" : "(?:신청접수|신청|접수)(?:시작|개시)");
    }

    private static LocalDate day(String value) {
        try {
            if (value.length() == 10) return LocalDate.parse(value);
            return hasOffset(value) ? OffsetDateTime.parse(value).atZoneSameInstant(SEOUL).toLocalDate()
                    : LocalDateTime.parse(value).toLocalDate();
        } catch (DateTimeException error) {
            return null;
        }
    }

    private static Instant instant(String title, String value) {
        try {
            if (value.length() > 10)
                return hasOffset(value) ? OffsetDateTime.parse(value).toInstant()
                        : LocalDateTime.parse(value).atZone(SEOUL).toInstant();
            var match = CLOCK.matcher(title);
            if (!match.find()) return null;
            int hour = Integer.parseInt(match.group(2));
            String meridiem = match.group(1);
            if (meridiem != null) {
                if (hour < 1 || hour > 12) return null;
                hour = hour % 12 + (meridiem.equals("오후") ? 12 : 0);
            }
            String minutes = match.group(3) == null ? match.group(4) : match.group(3);
            LocalTime time = LocalTime.of(hour, minutes == null ? 0 : Integer.parseInt(minutes));
            if (match.find()) return null;
            return LocalDate.parse(value).atTime(time).atZone(SEOUL).toInstant();
        } catch (DateTimeException | NumberFormatException error) {
            return null;
        }
    }

    private static boolean hasOffset(String value) {
        return value.endsWith("Z") || value.matches(".*[+-]\\d{2}:\\d{2}$");
    }
}
