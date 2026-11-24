import { call, demoMode } from '../../../shared/api/transport';
import { ApiError } from '../../../shared/api/client';
import { withReviewedConditions } from '../../housing/reviewedConditionPresentation';
import type { Preparation } from '../../housing/types';
import type { Experience } from '../../member/memberApi';

export interface PreparationHistoryEntry {
    version: number;
    savedAt: string;
}

export interface PreparationHistory {
    preparationId: string;
    title: string;
    archived: boolean;
    entries: PreparationHistoryEntry[];
}

export interface PreparationHistoryDetail extends PreparationHistoryEntry {
    preparation: Preparation;
}

const path = (id: string) => `/preparations/${encodeURIComponent(id)}/history`;

export const preparationHistoryApi = {
    async list(id: string): Promise<PreparationHistory> {
        if (!demoMode) return call<PreparationHistory>(path(id));
        // The local demo has no persisted guidance-refresh snapshots. Do not invent one.
        const [active, archived] = await Promise.all([
            call<Preparation[]>('/preparations'),
            call<Experience[]>('/member/archive'),
        ]);
        const preparation = active.find((item) => item.id === id);
        const previous = archived.find((item) => item.id === id);
        if (!preparation && !previous) throw new ApiError(404);
        return {
            preparationId: id,
            title: (preparation ?? previous)!.title,
            archived: !preparation,
            entries: [],
        };
    },
    async get(id: string, version: number): Promise<PreparationHistoryDetail> {
        if (demoMode) throw new ApiError(404);
        const result = await call<PreparationHistoryDetail>(
            `${path(id)}/${version}`,
        );
        await withReviewedConditions(result.preparation);
        return result;
    },
};
