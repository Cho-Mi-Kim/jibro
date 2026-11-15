package org.jibro.collection.review;

import static org.jibro.collection.review.ScheduleCandidates.*;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

import org.jibro.notice.HousingModels.Evidence;
import org.jibro.notice.HousingModels.ReviewIssue;

/** Quarantines optional events without deciding which conflicting official source is correct. */
final class ScheduleReviewScope {
    private static final List<Kind> OPTIONAL = List.of(Kind.SELECTION, Kind.RESULT, Kind.DOCUMENTS);

    private ScheduleReviewScope() {}

    static List<ReviewIssue> isolate(Map<Kind, List<Candidate>> candidates, Map<Kind, Candidate> resolved,
                                     Map<Kind, List<Unresolved>> unresolved, Set<String> blockers) {
        List<ReviewIssue> issues = new ArrayList<>();
        for (Kind kind : OPTIONAL) {
            boolean conflict = blockers.contains("SCHEDULE_CONFLICT:" + kind);
            boolean uncertain = !unresolved.getOrDefault(kind, List.of()).isEmpty();
            boolean order = kind != Kind.DOCUMENTS && before(resolved.get(kind), resolved.get(Kind.END));
            if (!conflict && !uncertain && !order) continue;
            var dated = new ArrayList<>(candidates.getOrDefault(kind, List.of()));
            if (order) dated.addAll(candidates.getOrDefault(Kind.END, List.of()));
            List<Unresolved> undated = unresolved.getOrDefault(kind, List.of());
            List<Evidence> evidence = evidence(dated, undated);
            String detail = detail(kind, conflict, uncertain, order, blockers, dated, undated);
            // Never silently drop sources or truncate a conflicting value to satisfy the public contract.
            if (evidence.isEmpty() || evidence.size() > 10 || detail.length() > 3000) {
                blockers.add("SCHEDULE_REVIEW_SCOPE_UNVERIFIED:" + kind);
                continue;
            }
            issues.add(new ReviewIssue("auto-schedule-review-" + kind.name().toLowerCase(Locale.ROOT), "SCHEDULE",
                kind.title + " 확인 필요", detail, List.of(), List.of(), evidence));
            resolved.remove(kind);
            blockers.remove("SCHEDULE_CONFLICT:" + kind);
            blockers.remove("SCHEDULE_UNRESOLVED:" + kind);
            blockers.remove("SCHEDULE_EVENT_SCOPE_UNRESOLVED:" + kind);
        }
        if (!before(resolved.get(Kind.SELECTION), resolved.get(Kind.END))
            && !before(resolved.get(Kind.RESULT), resolved.get(Kind.END))) blockers.remove("SCHEDULE_ORDER_CONFLICT");
        return List.copyOf(issues);
    }

    private static List<Evidence> evidence(List<Candidate> dated, List<Unresolved> undated) {
        List<Evidence> evidence = new ArrayList<>();
        dated.forEach(candidate -> evidence.add(candidate.evidence()));
        undated.forEach(candidate -> evidence.add(candidate.evidence()));
        return evidence.stream().distinct().toList();
    }

    private static String detail(Kind kind, boolean conflict, boolean uncertain, boolean order, Set<String> blockers,
                                 List<Candidate> dated, List<Unresolved> undated) {
        List<String> reasons = new ArrayList<>();
        if (conflict) reasons.add("공식 자료에 기재된 날짜 또는 시각이 서로 다릅니다.");
        if (uncertain) reasons.add("공식 자료에 미정·변경 가능 등 확정되지 않은 안내가 있습니다.");
        if (order) reasons.add(blockers.contains("SCHEDULE_EVENT_SCOPE_UNRESOLVED:" + kind)
            ? "발표가 전체 접수 마감보다 이르며, 적용 순위·접수 방식 또는 조기 마감과의 관계가 확인되지 않았습니다."
            : "발표가 신청 접수 마감보다 이르게 기재되어 있어 일정 간 관계를 확인해야 합니다.");
        reasons.add("이 항목의 날짜는 확정 일정에서 제외했습니다. 공식 원문 또는 운영기관에서 확인해 주세요.");
        dated.stream().map(candidate -> candidate.evidence().label() + ": " + candidate.day()
            + (candidate.time() == null ? "" : " " + candidate.time())
            + (candidate.early() ? " (조기 중단 가능)" : "")).distinct().forEach(reasons::add);
        undated.stream().map(candidate -> candidate.evidence().label() + ": " + candidate.text())
            .distinct().forEach(reasons::add);
        return String.join("\n", reasons);
    }
}
