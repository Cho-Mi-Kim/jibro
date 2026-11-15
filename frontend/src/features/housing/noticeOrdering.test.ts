import { describe, expect, it } from 'vitest';
import { noticeComparator } from './noticeOrdering';
import type { Notice } from './types';

function notice(
    id: string,
    status: Notice['status'],
    endDate: string | null,
    publishedDate = '2026-11-01',
): Notice {
    return {
        id,
        status,
        endDate,
        publishedDate,
        title: id,
        agency: 'LH',
        region: '서울',
        type: '임대',
        startDate: '2026-10-01',
        attachments: [],
        summary: '',
        version: 1,
        guideVersion: null,
        guideState: 'REVIEWING',
        canStart: false,
        evidence: { label: '', url: null, checkedAt: '' },
        questions: [],
        documents: [],
    };
}

describe('default notice ordering', () => {
    it('places open, upcoming, closures and then unknown application status', () => {
        const closed = notice('closed', 'CLOSED', '2026-10-31');
        const unknown = notice('unknown', 'UNKNOWN', null);
        const upcoming = notice('upcoming', 'UPCOMING', '2026-11-09');
        const open = notice('open', 'OPEN', '2026-12-31');
        const early = {
            ...notice('early', 'OPEN', '2026-12-01'),
            lifecycle: {
                state: 'EARLY_CLOSED' as const,
                reason: '',
                evidence: null,
                checkedAt: null,
                revision: 1,
            },
        };
        const cancelled = {
            ...early,
            id: 'cancelled',
            lifecycle: { ...early.lifecycle, state: 'CANCELLED' as const },
        };
        expect(
            [closed, unknown, upcoming, early, cancelled, open]
                .sort(noticeComparator())
                .map((n) => n.id),
        ).toEqual([
            'open',
            'upcoming',
            'closed',
            'cancelled',
            'early',
            'unknown',
        ]);
    });

    it('preserves explicit newest and deadline ordering', () => {
        const closed = notice('closed', 'CLOSED', '2026-10-31', '2026-11-02');
        const open = notice('open', 'OPEN', '2026-12-31');
        const unknown = notice('unknown', 'UNKNOWN', null);
        expect(
            [open, closed].sort(noticeComparator('newest')).map((n) => n.id),
        ).toEqual(['closed', 'open']);
        expect(
            [unknown, open, closed]
                .sort(noticeComparator('deadline'))
                .map((n) => n.id),
        ).toEqual(['closed', 'open', 'unknown']);
    });

    it('orders active notices by deadline then posting date and stable ID', () => {
        const items = [
            notice('b', 'OPEN', '2026-12-02'),
            notice('z', 'OPEN', '2026-12-01'),
            notice('a', 'OPEN', '2026-12-02'),
            notice('newer', 'OPEN', '2026-12-02', '2026-11-02'),
        ];
        expect(items.sort(noticeComparator()).map((n) => n.id)).toEqual([
            'z',
            'newer',
            'a',
            'b',
        ]);
    });

    it('orders every status by full guidance, partial guidance, review and unavailable', () => {
        const expected: Notice[] = [];
        for (const status of ['OPEN', 'UPCOMING', 'CLOSED'] as const) {
            for (const guide of ['full', 'partial', 'review', 'unavailable']) {
                const item = notice(status + '-' + guide, status, '2026-12-01');
                item.guideState =
                    guide === 'review'
                        ? 'REVIEWING'
                        : guide === 'unavailable'
                          ? 'UNSUPPORTED'
                          : 'AVAILABLE';
                if (guide === 'partial') item.reviewIssues = [issue];
                // A farther deadline must not push full guidance below partial guidance.
                if (guide === 'full') item.endDate = '2026-12-30';
                expected.push(item);
            }
        }
        expect([...expected].reverse().sort(noticeComparator())).toEqual(
            expected,
        );
    });

    it('keeps changed guidance in the review group despite retained old issues', () => {
        const available = {
            ...notice('full', 'OPEN', '2026-12-30'),
            guideState: 'AVAILABLE' as const,
        };
        const partial = {
            ...notice('partial', 'OPEN', '2026-12-20'),
            guideState: 'AVAILABLE' as const,
            reviewIssues: [issue],
        };
        const changed = {
            ...notice('changed', 'OPEN', '2026-12-01'),
            guideState: 'CHANGE_REVIEW' as const,
            reviewIssues: [issue],
        };
        const unsupported = {
            ...notice('unsupported', 'OPEN', '2026-11-30'),
            guideState: 'UNSUPPORTED' as const,
        };
        expect(
            [unsupported, changed, partial, available]
                .sort(noticeComparator())
                .map((n) => n.id),
        ).toEqual(['full', 'partial', 'changed', 'unsupported']);
        expect(
            [available, partial, changed, unsupported]
                .sort(noticeComparator('deadline'))
                .map((n) => n.id),
        ).toEqual(['unsupported', 'changed', 'partial', 'full']);
    });
});

const issue = {
    id: 'date-conflict',
    area: 'SCHEDULE' as const,
    title: '발표일 확인 필요',
    detail: '공식 자료의 발표일이 다릅니다.',
    questionIds: [],
    documentIds: [],
    evidence: [],
};
