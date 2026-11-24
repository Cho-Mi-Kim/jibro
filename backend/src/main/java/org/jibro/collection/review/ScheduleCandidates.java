package org.jibro.collection.review;

import java.time.LocalDate;
import java.time.LocalTime;

import org.jibro.notice.HousingModels.Evidence;

/**
 * Source-bound parsing values shared by strict compilation and item-scoped review.
 */
final class ScheduleCandidates {
    private ScheduleCandidates() {
    }

    enum Kind {
        START("auto-application-start", "신청 접수 시작"), END("auto-application-end", "신청 접수 마감"),
        SELECTION("auto-document-selection", "서류 제출 대상자 발표"), RESULT("auto-result", "결과 발표"),
        DOCUMENTS("auto-documents-end", "서류 제출 마감");

        final String id;
        final String title;

        Kind(String id, String title) {
            this.id = id;
            this.title = title;
        }
    }

    record Candidate(LocalDate day, LocalTime time, Evidence evidence, boolean early, String description) {
    }

    record Unresolved(String text, Evidence evidence) {
    }

    static boolean before(Candidate first, Candidate second) {
        return first != null && second != null && (first.day().isBefore(second.day()) || first.day().equals(second.day())
                && first.time() != null && second.time() != null && first.time().isBefore(second.time()));
    }
}
