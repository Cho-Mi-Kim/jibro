import { call } from '../../shared/api/transport';
import type { Evidence, Question, Schedule } from '../housing/types';

export interface GuidanceDocument {
    id: string;
    title: string;
    conditionQuestionId: string | null;
    conditionAnswerValues?: string[] | null;
    method: string;
    issuer: string;
    evidence: Evidence;
}

export interface GuidanceCandidate {
    summary: string;
    evidence: Evidence;
    attachments: Evidence[];
    questions: Question[];
    documents: GuidanceDocument[];
    schedules: Omit<Schedule, 'recorded'>[];
}

export interface GuidancePreview {
    profile: string;
    ready: boolean;
    candidate: GuidanceCandidate | null;
    issues: string[];
    findings: {
        code: string;
        message: string;
        evidence: Evidence | null;
    }[];
    startDate: string | null;
    endDate: string | null;
}

export function loadGuidancePreview(noticeId: string, extractionId: string) {
    return call<GuidancePreview>(
        `/admin/reviews/${encodeURIComponent(noticeId)}/extractions/${encodeURIComponent(extractionId)}/guidance-preview`,
    );
}
