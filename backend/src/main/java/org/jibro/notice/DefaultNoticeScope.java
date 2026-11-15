package org.jibro.notice;

import com.querydsl.core.types.dsl.BooleanExpression;
import com.querydsl.core.types.dsl.Expressions;

import java.time.Instant;

/**
 * Default discovery window, not a deletion/retention policy. Explicit status searches remain available.
 */
final class DefaultNoticeScope {
    private DefaultNoticeScope() {
    }

    static BooleanExpression visible(QNoticeEntity n, Instant now) {
        var today = now.atZone(ApplicationPeriod.SEOUL).toLocalDate();
        var closure = Expressions.stringTemplate("function('jsonb_extract_path_text', {0}, 'applicationClosure')", n.content);
        var dated = n.startDate.isNotNull().and(n.endDate.isNotNull());
        var ended = n.applicationEndAt.loe(now).or(n.applicationEndAt.isNull().and(n.endDate.lt(today)));
        var notEnded = n.applicationEndAt.gt(now).or(n.applicationEndAt.isNull().and(n.endDate.goe(today)));
        var officiallyClosed = closure.isNotNull().or(n.sourceClosed.isTrue()).or(n.serviceClosed.isTrue());
        var ongoingOrUnknown = officiallyClosed.not().and(dated.not().or(notEnded));
        // A verification timestamp is not a closing date. Unknown/early closure dates are never invented.
        var recentClosed = n.endDate.goe(today.minusDays(28)).and(n.endDate.loe(today))
            .and(officiallyClosed.or(dated.and(ended)));
        return n.lifecycleState.eq("NORMAL").and(ongoingOrUnknown.or(recentClosed));
    }
}
