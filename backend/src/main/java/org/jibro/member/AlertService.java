package org.jibro.member;

import java.time.*;
import java.util.*;

import jakarta.validation.constraints.*;
import org.jibro.global.common.ApiException;
import org.jibro.notice.HousingModels.Notice;
import org.jibro.notice.NoticeService;
import org.jibro.notice.NoticeSchedules;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class AlertService {
    public record Settings(boolean enabled, @Min(1) @Max(7) int leadDays, boolean includeSaved) {
    }

    public record Alert(String key, UUID noticeId, String title, String label, LocalDate date, boolean read,
                        Instant occurredAt) {
    }

    public record Inbox(Settings settings, List<Alert> items) {
    }

    private final AlertRepository repository;
    private final NoticeService notices;
    private static final ZoneId SEOUL = ZoneId.of("Asia/Seoul");

    public AlertService(AlertRepository repository, NoticeService notices) {
        this.repository = repository;
        this.notices = notices;
    }

    public void configure(UUID owner, Settings s) {
        if (!List.of(1, 3, 7).contains(s.leadDays()))
            throw new ApiException(400, "INVALID_LEAD", "1일, 3일, 7일 중 선택해 주세요.");
        repository.savePreferences(owner, s.enabled(), s.leadDays(), s.includeSaved());
    }

    @Transactional(readOnly = true)
    public Inbox inbox(UUID owner) {
        var settings = repository.findPreferences(owner).map(value -> new Settings(value.enabled(), value.leadDays(), value.includeSaved()))
                .orElse(new Settings(false, 3, true));
        if (!settings.enabled()) return new Inbox(settings, List.of());
        var read = new HashSet<>(repository.findReadKeys(owner));
        var rows = repository.findTargets(owner, settings.includeSaved());
        var result = new ArrayList<Alert>();
        var today = LocalDate.now(SEOUL);
        for (var row : rows) {
            UUID id = row.id();
            Notice notice = notices.notice(id);
            if (notice.version() > row.initialVersion()) {
                add(result, read, notice, "version-" + notice.version(), "공고 안내가 변경됐어요", null, row.versionPublishedAt());
            }
            if (notice.lifecycle() != null && notice.lifecycle().revision() > 0) {
                String label = switch (notice.lifecycle().state()) {
                    case "EARLY_CLOSED" -> "공식 안내에 따라 신청이 조기 마감됐어요";
                    case "CANCELLED" -> "공식 안내에 따라 공고가 취소됐어요";
                    default -> "공식 안내에 따라 공고가 정상 상태로 복원됐어요";
                };
                add(result, read, notice, "lifecycle-" + notice.lifecycle().revision(), label, null, notice.lifecycle().checkedAt());
            }
            for (var due : NoticeSchedules.reminders(notice, Instant.now()))
                addDue(result, read, notice, due.id(), due.title(), due.date(), today, settings.leadDays());
        }
        result.sort(Comparator.comparing(Alert::occurredAt, Comparator.nullsLast(Comparator.reverseOrder())).thenComparing(Alert::key));
        return new Inbox(settings, result);
    }

    private void addDue(List<Alert> out, Set<String> read, Notice n, String id, String label, LocalDate date, LocalDate today, int days) {
        if (date != null && !date.isBefore(today) && !date.isAfter(today.plusDays(days)))
            add(out, read, n, "date-" + id + "-" + date, label, date, date.minusDays(days).atStartOfDay(SEOUL).toInstant());
    }

    private void add(List<Alert> result, Set<String> read, Notice n, String suffix, String label, LocalDate date, Instant occurredAt) {
        String key = UUID.nameUUIDFromBytes((n.id() + ":" + suffix).getBytes(java.nio.charset.StandardCharsets.UTF_8)).toString();
        result.add(new Alert(key, UUID.fromString(n.id()), n.title(), label, date, read.contains(key), occurredAt));
    }

    @Transactional
    public void read(UUID owner, List<String> keys) {
        var allowed = new HashSet<>(inbox(owner).items().stream().map(Alert::key).toList());
        for (String key : new HashSet<>(keys))
            if (allowed.contains(key))
                repository.markRead(owner, key);
    }
}
