package org.jibro.notice;

import java.time.Instant;
import java.util.Set;
import java.util.UUID;

import org.jibro.global.common.ApiException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Internal operator decisions; never presented as an official closure or an invented deadline.
 */
@Service
public class NoticeDispositionService {
    private final NoticeRepository notices;
    private final NoticeAuditRepository audit;

    public NoticeDispositionService(NoticeRepository notices, NoticeAuditRepository audit) {
        this.notices = notices;
        this.audit = audit;
    }

    @Transactional
    public void apply(UUID id, int expectedRevision, String decision, String reason) {
        if (!Set.of("CLOSE", "EXCLUDE").contains(decision) || reason == null || reason.isBlank() || reason.length() > 2000)
            throw new IllegalArgumentException("운영 결정과 사유가 필요합니다.");
        var n = notices.locked(id).orElseThrow(ApiException::notFound);
        if (n.revision != expectedRevision) throw ApiException.conflict();
        if (decision.equals("CLOSE")) n.serviceClosed = true;
        else n.serviceExcluded = true;
        n.serviceDecisionReason = reason.trim();
        n.serviceDecidedAt = Instant.now();
        n.automaticReviewBlocked = true;
        n.revision++;
        audit.recordServiceDecision(id, n.revision, decision, "USER_REQUEST", n.serviceDecisionReason);
    }
}
