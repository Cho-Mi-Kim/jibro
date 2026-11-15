import type { Notice } from './types';

/** Refetch authoritative status when a reviewed opening/closing boundary passes. */
export function noticeRefreshInterval(
    notices: readonly Pick<Notice, 'applicationTiming'>[],
    now = Date.now(),
) {
    const changes = notices
        .map((notice) => notice.applicationTiming?.nextChangeAt)
        .filter((value): value is string => !!value)
        .map((value) => Date.parse(value))
        .filter(Number.isFinite);
    if (!changes.length) return false;
    return Math.max(
        1000,
        Math.min(2_147_483_647, Math.min(...changes) - now + 100),
    );
}
