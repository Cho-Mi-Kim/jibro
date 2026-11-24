import { api } from '../housing/api';
import { withReviewedConditions } from '../housing/reviewedConditionPresentation';
import type { Evidence, Notice } from '../housing/types';
import { call, demoMode } from '../../shared/api/transport';

export interface DocumentRequirement {
    id: string;
    title: string;
    conditionQuestionId: string | null;
    conditionAnswerValues: string[] | null;
    method: string;
    issuer: string;
    issueUrl: string | null;
    issueLabel?: string | null;
    evidence: Evidence;
}

export interface NoticeComparison {
    notice: Notice;
    // Static previews only have document titles. Do not invent their rules.
    documentRequirements: DocumentRequirement[] | null;
}

export const comparisonApi = {
    notice: async (id: string): Promise<NoticeComparison> => {
        if (demoMode)
            return { notice: await api.notice(id), documentRequirements: null };
        const result = await call<NoticeComparison>(
            '/notices/' + encodeURIComponent(id) + '/comparison',
        );
        await withReviewedConditions(result.notice);
        return result;
    },
};
