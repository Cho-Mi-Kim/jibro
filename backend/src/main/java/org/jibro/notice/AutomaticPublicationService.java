package org.jibro.notice;

import java.time.*;
import java.util.*;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.ObjectMapper;

import static org.jibro.notice.HousingModels.*;

/**
 * Applies only evidence-backed decisions, under the same notice lock as human edits.
 */
@Service
public class AutomaticPublicationService {
    private final NoticeRepository notices;
    private final NoticeService validation;
    private final AutomaticReviewRepository reviews;
    private final ObjectMapper json;

    public AutomaticPublicationService(NoticeRepository notices, NoticeService validation, AutomaticReviewRepository reviews, ObjectMapper json) {
        this.notices = notices;
        this.validation = validation;
        this.reviews = reviews;
        this.json = json;
    }

    @Transactional
    public boolean basic(UUID id) {
        var n = notices.locked(id).orElseThrow();
        if (!eligible(n) || !n.basicReview.equals("HOLD") || reviews.decided(id, n.revision, "BASIC")) return false;
        Content c = null;
        try {
            c = json.readValue(n.content, Content.class);
        } catch (RuntimeException malformed) { /* Invalid source content receives a recorded hold below. */ }
        if (AutomaticReviewEligibility.closed(n, c, Instant.now())) return false;
        boolean pass = LhBasicReview.valid(n, c);
        int before = n.revision;
        if (pass && n.changeReview) {
            // Public basic cards expose dates and schedules even while guidance is held. Withdraw stale
            // auto-generated facts until the new source is compiled; personal preparation snapshots stay intact.
            n.content = json.writeValueAsString(new Content("공식 목록 정보입니다. 변경된 신청 기간·조건·서류를 확인하고 있습니다.",
                    c.evidence(), c.attachments(), List.of(), List.of(), List.of()));
            n.startDate = null;
            n.endDate = null;
            n.applicationStartAt = null;
            n.applicationEndAt = null;
            n.guideReview = "HOLD";
            n.guideCheckedAt = null;
        }
        n.basicReview = pass ? "PASS" : "HOLD";
        n.basicCheckedAt = pass ? Instant.now() : null;
        n.reason = pass ? "자동 기본 검수 통과 · LH 공식 API 출처·식별자·필수 정보 확인. 준비 안내는 별도 검수합니다."
                : "자동 기본 검수 보류 · 공식 출처·공고일·지역·유형 중 확인되지 않은 정보가 있습니다.";
        n.revision++;
        notices.saveAndFlush(n);
        reviews.record(n, before, null, null, "BASIC", pass, n.reason);
        return pass;
    }

    @Transactional
    public boolean guidance(UUID id, int revision, UUID extraction, String evidenceFingerprint, Content content, LocalDate start, LocalDate end, List<String> issues) {
        var n = notices.locked(id).orElseThrow();
        if (n.revision != revision || !eligible(n) || !n.basicReview.equals("PASS")
                || !n.guideReview.equals("HOLD") || !reviews.matchesCurrentEvidence(id, revision, extraction, evidenceFingerprint)
                || reviews.decidedExtraction(extraction, evidenceFingerprint)) return false;
        if (AutomaticReviewEligibility.closed(n, json.readValue(n.content, Content.class), Instant.now())
                || content != null && AutomaticReviewEligibility.closed(start, end, content.schedules(), Instant.now()))
            return false;
        var findings = new ArrayList<>(issues);
        if (reviews.requiresFollowupComparison(id)) findings.add("OFFICIAL_CORRECTION_COMPARISON_REQUIRED");
        if (content != null && content.applicationClosure() != null)
            findings.add("OFFICIAL_CLOSURE_REQUIRES_SOURCE_REVIEW");
        boolean pass = findings.isEmpty() && content != null && start != null && end != null && !start.isAfter(end);
        ApplicationPeriod.Window timing = null;
        if (pass) {
            try {
                validation.validateContent(content);
                if (content.questions().isEmpty() || content.documents().isEmpty() || content.schedules().isEmpty())
                    pass = false;
                if (!containsBoundary(content, start, false) || !containsBoundary(content, end, true)) {
                    pass = false;
                    findings.add("APPLICATION_BOUNDARY_MISSING");
                }
                timing = ApplicationPeriod.resolve(start, end, content.schedules());
            } catch (RuntimeException invalid) {
                pass = false;
                findings.add("COMPILED_CONTENT_INVALID");
            }
        }
        if (pass) {
            n.content = json.writeValueAsString(content);
            n.startDate = start;
            n.endDate = end;
            n.applicationStartAt = timing.startsAt();
            n.applicationEndAt = timing.endsAt();
            n.guideReview = "PASS";
            n.guideCheckedAt = Instant.now();
            n.changeReview = false;
            n.version++;
        }
        boolean partial = pass && !content.reviewIssues().isEmpty();
        n.reason = partial ? "자동 준비 안내 일부 제공 · 영향 범위를 확인한 " + content.reviewIssues().size()
                + "개 항목은 추가 확인으로 남기고, 나머지 조건·서류·확인된 일정을 제공합니다. 최종 자격은 운영기관에서 심사합니다."
                : pass ? "자동 준비 안내 검수 통과 · 지원하는 규칙의 전체 문맥·출처·일정을 확인했습니다. 최종 자격은 운영기관에서 심사합니다."
                : "자동 준비 안내 검수 보류 · " + String.join(" / ", findings.isEmpty() ? List.of("필수 규칙 또는 신청 기간 확인 불가") : findings);
        if (n.reason.length() > 1900) n.reason = n.reason.substring(0, 1900);
        // A hold does not change the source revision: explicit extraction retries remain possible.
        if (pass) n.revision++;
        notices.saveAndFlush(n);
        reviews.record(n, revision, extraction, evidenceFingerprint, "GUIDANCE", pass ? partial ? "PARTIAL" : "PASS" : "HOLD",
                n.reason, partial ? content.reviewIssues() : List.of());
        return pass;
    }

    static boolean containsBoundary(Content content, LocalDate day, boolean closing) {
        return content.schedules().stream().anyMatch(schedule -> ApplicationPeriod.basicTitle(schedule.title(), closing)
                && day.equals(ApplicationPeriod.day(schedule.date())));
    }

    private boolean eligible(NoticeEntity n) {
        return n.sourceKey != null && n.sourceKey.startsWith("LH:") && n.sourceFingerprint != null && n.collectedAt != null
                && !n.automaticReviewBlocked && !reviews.manual(n.id) && n.lifecycleState.equals("NORMAL");
    }

}
