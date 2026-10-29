import type { GuideState } from '../../features/housing/types';
export const guideLabels: Record<GuideState, string> = {
    AVAILABLE: '준비 안내 제공 중',
    REVIEWING: '준비 안내 검수 중',
    UNSUPPORTED: '준비 안내 미제공',
    CHANGE_REVIEW: '준비 안내 변경 검토 중',
};
export function safeUrl(value: string | null) {
    if (!value) return false;
    try {
        const url = new URL(value);
        return url.protocol === 'https:' && !url.username && !url.password;
    } catch {
        return false;
    }
}
