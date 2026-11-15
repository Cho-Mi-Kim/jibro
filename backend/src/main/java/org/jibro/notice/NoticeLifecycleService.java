package org.jibro.notice;

import java.net.URI;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.Set;
import java.util.UUID;
import org.jibro.global.common.ApiException;
import org.jibro.notice.HousingModels.Evidence;
import org.jibro.notice.HousingModels.Review;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.ObjectMapper;

/** Official lifecycle is independent of date calculations and guidance publication. */
@Service
public class NoticeLifecycleService {
    private final NoticeRepository notices;
    private final NoticeService views;
    private final NoticeLifecycleRepository history;
    private final ObjectMapper json;

    public NoticeLifecycleService(NoticeRepository notices, NoticeService views, NoticeLifecycleRepository history,
                                  ObjectMapper json) {
        this.notices = notices;
        this.views = views;
        this.history = history;
        this.json = json;
    }

    @Transactional
    public Review change(UUID id, UUID actor, int revision, String state, String reason, Evidence evidence,
                         boolean officialConfirmed) {
        validate(state, reason, evidence, officialConfirmed);
        var notice = notices.locked(id).orElseThrow(ApiException::notFound);
        if (notice.revision != revision) throw ApiException.conflict();
        String previousState = notice.lifecycleState;
        notice.lifecycleState = state;
        notice.lifecycleReason = reason.trim();
        notice.lifecycleEvidence = json.writeValueAsString(evidence);
        notice.lifecycleCheckedAt = Instant.now();
        notice.lifecycleRevision++;
        notice.revision++;
        // Keep content version/review and all private preparation snapshots intact.
        history.record(id, actor, notice.revision, notice.lifecycleRevision, previousState, state,
                notice.lifecycleReason, notice.lifecycleEvidence);
        return new Review(id.toString(), views.view(notice, true), notice.basicReview, notice.guideReview,
                notice.reason, notice.revision);
    }

    private void validate(String state, String reason, Evidence evidence, boolean confirmed) {
        if (!confirmed || state == null || !Set.of("NORMAL", "EARLY_CLOSED", "CANCELLED").contains(state)
                || reason == null || reason.isBlank() || reason.length() > 2000 || evidence == null
                || evidence.label() == null || evidence.label().isBlank() || evidence.label().length() > 300
                || evidence.url() == null || evidence.url().length() > 2000 || evidence.checkedAt() == null)
            throw new IllegalArgumentException("공식 근거와 확인 내용을 입력해 주세요.");
        var url = URI.create(evidence.url());
        if (!"https".equals(url.getScheme()) || url.getHost() == null || url.getUserInfo() != null
                || LocalDate.parse(evidence.checkedAt()).isAfter(LocalDate.now(ZoneId.of("Asia/Seoul"))))
            throw new IllegalArgumentException("공식 근거 주소와 확인일을 확인해 주세요.");
    }
}
