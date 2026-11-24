import type { Notice, NoticeListItem } from './types';

export function demoNoticeListItem(n: Notice): NoticeListItem {
    return {
        id: n.id,
        title: n.title,
        agency: n.agency,
        region: n.region,
        type: n.type,
        status: n.status,
        lifecycle: n.lifecycle,
        startDate: n.startDate,
        endDate: n.endDate,
        applicationTiming: n.applicationTiming,
        publishedDate: n.publishedDate,
        guideState: n.guideState,
        collectedAt: n.collectedAt,
        partialGuidance: !!n.reviewIssues?.length,
    };
}
