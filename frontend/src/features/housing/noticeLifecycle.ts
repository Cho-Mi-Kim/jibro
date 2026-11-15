import type { Notice } from './types';
import { scheduleKind } from './schedules';
import { statusLabels } from './noticeLabels';

export function applicationInterrupted(
    notice?: Pick<Notice, 'lifecycle' | 'status'>,
) {
    return !!notice?.lifecycle && notice.lifecycle.state !== 'NORMAL';
}

export function effectiveNoticeStatus(
    notice: Pick<Notice, 'lifecycle' | 'status'>,
) {
    return applicationInterrupted(notice)
        ? (notice.lifecycle!.state as 'EARLY_CLOSED' | 'CANCELLED')
        : notice.status;
}

export function lifecycleLabel(notice?: Pick<Notice, 'lifecycle' | 'status'>) {
    return notice && applicationInterrupted(notice)
        ? statusLabels[effectiveNoticeStatus(notice)]
        : '';
}

// Original dates stay visible as evidence; only actionable dates/reminders are suppressed.
export function scheduleIsActive(notice: Notice | undefined, title: string) {
    if (notice?.lifecycle?.state === 'CANCELLED') return false;
    return (
        notice?.lifecycle?.state !== 'EARLY_CLOSED' ||
        !['start', 'end', 'application'].includes(scheduleKind(title))
    );
}

export function activeSchedules<T extends { title: string }>(
    notice: Notice | undefined,
    schedules: readonly T[],
) {
    return schedules.filter((event) => scheduleIsActive(notice, event.title));
}
