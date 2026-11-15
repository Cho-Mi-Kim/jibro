package org.jibro.preparation;

import static org.jibro.notice.HousingModels.*;
import java.util.*;
import org.jibro.global.common.ApiException;
import org.jibro.notice.NoticeService;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

@Service
public class OneTimeCheckService {
    private final NoticeService notices;

    public OneTimeCheckService(NoticeService notices) {
        this.notices = notices;
    }

    public record Result(String noticeId, String title, int version, List<Question> questions,
                         Map<String, Object> answers, List<Document> documents, List<String> warnings,
                         List<ReviewIssue> reviewIssues) {}

    // A consistent public version is read without locking/writing personal records.
    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public Result evaluate(UUID noticeId, int version, Map<String, ?> answers) {
        Notice notice = notices.notice(noticeId);
        if (!notice.canStart() || !notice.guideState().equals("AVAILABLE") || notice.version() != version)
            throw new ApiException(409, "CHECK_UNAVAILABLE", "공고 안내가 바뀌었거나 조건 확인을 시작할 수 없습니다. 공고를 다시 확인해 주세요.");
        var snapshot = PreparationSnapshots.fresh(notice, notices.content(noticeId));
        PreparationSnapshots.applyAnswers(snapshot, answers);
        var result = PreparationSnapshots.assess(snapshot.preparation());
        return new Result(result.noticeId, result.title, result.version, result.questions,
                result.answers, result.documents, result.warnings, result.reviewIssues);
    }
}
