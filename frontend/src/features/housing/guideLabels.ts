import type { GuideState } from './types';
export const guideLabels: Record<GuideState, string> = {
    AVAILABLE: '준비 안내 제공 중',
    REVIEWING: '준비 안내 검수 중',
    UNSUPPORTED: '준비 안내 미제공',
    CHANGE_REVIEW: '준비 안내 변경 검토 중',
};
