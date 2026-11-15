package db.migration;

import java.sql.Connection;
import java.sql.Date;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Statement;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.UUID;

import org.flywaydb.core.api.migration.Context;
import org.junit.jupiter.api.Test;

import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class NoticeApplicationTimingMigrationTest {
    @Test
    void readsOnlyStableScheduleFieldsWithoutRequiringCurrentApplicationDtos() throws Exception {
        verifyBackfill("""
            {"unrelatedLegacyField":true,"schedules":[
                {"title":"신청 접수 시작 (오전 9시)","date":"2026-12-31"},
                {"title":"신청 접수 마감 (오후 6시)","date":"2026-12-31"}]}
            """, "2026-12-31T00:00:00Z", "2026-12-31T09:00:00Z");
    }

    @Test
    void interpretsMidnightNoonAndExplicitOffsetsInSeoul() throws Exception {
        verifyBackfill("""
            {"schedules":[
                {"title":"접수 시작 (오전 12시)","date":"2026-12-31"},
                {"title":"접수 마감 (오후 12시)","date":"2026-12-31"}]}
            """, "2026-12-30T15:00:00Z", "2026-12-31T03:00:00Z");
        verifyBackfill("""
            {"schedules":[
                {"title":"접수 시작","date":"2026-12-30T15:00:00Z"},
                {"title":"접수 마감","date":"2026-12-31T18:00:00+09:00"}]}
            """, "2026-12-30T15:00:00Z", "2026-12-31T09:00:00Z");
    }

    @Test
    void conflictingOrUnknownTimesStayNullAndUnrelatedEventsAreNotInferred() throws Exception {
        verifyBackfill("""
            {"schedules":[
                {"title":"접수 시작","date":"2026-12-31"},
                {"title":"접수 마감 (17:00)","date":"2026-12-31"},
                {"title":"접수 마감 (18시)","date":"2026-12-31"}]}
            """, null, null);
        verifyBackfill("""
            {"schedules":[
                {"title":"접수 마감 (09:00~18:00)","date":"2026-12-31"},
                {"title":"서류 제출 마감","date":"2026-12-31T18:00:00"},
                {"title":"서울 지역 접수 마감","date":"2026-12-31T18:00:00"},
                {"title":"접수 마감","date":"2026-12-30T18:00:00"},
                {"title":"접수 마감","date":"2026-02-30"}]}
            """, null, null);
        verifyBackfill("{}", null, null);
    }

    private void verifyBackfill(String content, String startsAt, String endsAt) throws Exception {
        Context context = mock(Context.class);
        Connection connection = mock(Connection.class);
        Statement ddl = mock(Statement.class);
        PreparedStatement read = mock(PreparedStatement.class);
        PreparedStatement update = mock(PreparedStatement.class);
        ResultSet rows = mock(ResultSet.class);
        UUID notice = UUID.randomUUID();
        when(context.getConnection()).thenReturn(connection);
        when(connection.createStatement()).thenReturn(ddl);
        when(connection.prepareStatement(startsWith("SELECT"))).thenReturn(read);
        when(connection.prepareStatement(startsWith("UPDATE"))).thenReturn(update);
        when(read.executeQuery()).thenReturn(rows);
        when(rows.next()).thenReturn(true, false);
        when(rows.getDate("start_date")).thenReturn(Date.valueOf("2026-12-31"));
        when(rows.getDate("end_date")).thenReturn(Date.valueOf("2026-12-31"));
        when(rows.getString("content")).thenReturn(content);
        when(rows.getObject("id")).thenReturn(notice);
        new V21__notice_application_timing().migrate(context);
        verify(update).setTimestamp(1, startsAt == null ? null : Timestamp.from(Instant.parse(startsAt)));
        verify(update).setTimestamp(2, endsAt == null ? null : Timestamp.from(Instant.parse(endsAt)));
        verify(update).setObject(3, notice);
        verify(update).executeUpdate();
        verify(update).close();
        verify(connection).prepareStatement("UPDATE notice SET application_start_at=?, application_end_at=? WHERE id=?");
        verifyNoMoreInteractions(update);
    }
}
