import { call, demoMode } from '../../shared/api/transport';
import type { Evidence } from './types';

export interface NoticeCorrection {
    noticeId: string;
    title: string;
    reason: string;
    evidence: Evidence;
    checkedAt: string;
}

export async function noticeCorrection(id: string) {
    if (demoMode) return null;
    return (
        (await call<NoticeCorrection | undefined>(
            '/notices/' + encodeURIComponent(id) + '/correction',
        )) ?? null
    );
}
