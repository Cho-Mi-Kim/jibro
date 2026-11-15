import type { Notice } from './types';
import { effectiveNoticeStatus } from './noticeLifecycle';

export const DEFAULT_NOTICE_SORT = 'active';

function stateOrder(notice: Notice) {
    return {
        OPEN: 0,
        UPCOMING: 1,
        UNKNOWN: 3,
        CLOSED: 2,
        EARLY_CLOSED: 2,
        CANCELLED: 2,
    }[effectiveNoticeStatus(notice)];
}

function guidanceOrder(notice: Notice) {
    if (notice.guideState === 'AVAILABLE')
        return notice.reviewIssues?.length ? 1 : 0;
    return notice.guideState === 'UNSUPPORTED' ? 3 : 2;
}

function deadline(first: Notice, second: Notice) {
    return (first.endDate ?? '9999-12-31').localeCompare(
        second.endDate ?? '9999-12-31',
    );
}

function newest(first: Notice, second: Notice) {
    return (second.publishedDate ?? '').localeCompare(
        first.publishedDate ?? '',
    );
}

/** Demo ordering mirrors the server's order before pagination. Real API pages are not resorted. */
export function noticeComparator(
    sort: string = DEFAULT_NOTICE_SORT,
    regions: string[] = [],
) {
    const localFirst = regions.some((region) => region !== '전국');
    return (first: Notice, second: Notice) => {
        if (localFirst && first.region !== second.region) {
            if (first.region === '전국') return 1;
            if (second.region === '전국') return -1;
        }
        const compared =
            sort === 'newest'
                ? newest(first, second)
                : sort === 'deadline'
                  ? deadline(first, second)
                  : stateOrder(first) - stateOrder(second) ||
                    guidanceOrder(first) - guidanceOrder(second) ||
                    deadline(first, second) ||
                    newest(first, second);
        return compared || first.id.localeCompare(second.id);
    };
}
