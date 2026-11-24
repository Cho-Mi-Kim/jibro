import type { IconName } from '../../shared/ui/Icon';
import type { GuideState, Notice, NoticeLifecycleState } from './types';

interface StatusAppearance {
    label: string;
    icon: IconName;
    tone: 'green' | 'gray' | 'red' | 'amber';
}

type ApplicationStatus =
    Notice['status'] | Exclude<NoticeLifecycleState, 'NORMAL'>;
export const applicationStatuses = {
    EARLY_CLOSED: { label: '조기 마감', icon: 'file', tone: 'gray' },
    CANCELLED: { label: '공고 취소', icon: 'file', tone: 'gray' },
    OPEN: { label: '접수 중', icon: 'file', tone: 'green' },
    UPCOMING: { label: '접수 예정', icon: 'file', tone: 'gray' },
    CLOSED: { label: '접수 종료', icon: 'file', tone: 'gray' },
    UNKNOWN: { label: '일정 확인 필요', icon: 'file', tone: 'gray' },
} satisfies Record<ApplicationStatus, StatusAppearance>;

const guidanceStatuses = {
    AVAILABLE: { label: '준비 안내 제공 중', icon: 'check', tone: 'green' },
    PARTIAL: {
        label: '준비 안내 제공 · 일부 확인 필요',
        icon: 'exclamation',
        tone: 'amber',
    },
    REVIEWING: { label: '준비 안내 검수 중', icon: 'clock', tone: 'gray' },
    CHANGE_REVIEW: {
        label: '준비 안내 변경 검토 중',
        icon: 'clock',
        tone: 'gray',
    },
    UNSUPPORTED: {
        label: '준비 안내 미제공',
        icon: 'exclamation',
        tone: 'red',
    },
} satisfies Record<GuideState | 'PARTIAL', StatusAppearance>;

export function guidanceStatus(
    notice: Pick<Notice, 'guideState' | 'reviewIssues'> & {
        partialGuidance?: boolean;
    },
) {
    const partial = notice.partialGuidance || !!notice.reviewIssues?.length;
    return guidanceStatuses[
        notice.guideState === 'AVAILABLE' && partial
            ? 'PARTIAL'
            : notice.guideState
    ];
}
