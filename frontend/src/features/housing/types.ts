export type GuideState =
    'AVAILABLE' | 'REVIEWING' | 'UNSUPPORTED' | 'CHANGE_REVIEW';
export type DocumentState =
    'UNCHECKED' | 'NEEDED' | 'IN_PROGRESS' | 'READY' | 'RECHECK';
export type Answer = 'YES' | 'NO' | 'UNKNOWN';
export interface Evidence {
    label: string;
    url: string | null;
    checkedAt: string;
}
export interface Question {
    id: string;
    title: string;
    reason: string;
    evidence: Evidence;
}
export interface Notice {
    id: string;
    title: string;
    agency: string;
    region: string;
    type: string;
    status: 'OPEN' | 'UPCOMING' | 'CLOSED';
    startDate: string;
    endDate: string;
    publishedDate: string;
    attachments: Evidence[];
    summary: string;
    version: number;
    guideVersion: number | null;
    guideState: GuideState;
    canStart: boolean;
    evidence: Evidence;
    questions: Question[];
    documents: string[];
}
export interface Page<T> {
    items: T[];
    page: number;
    size: number;
    totalElements: number;
    totalPages: number;
}
export interface User {
    id: string;
    name: string;
    email: string;
    role: 'USER' | 'ADMIN';
}
export interface DocumentItem {
    id: string;
    title: string;
    necessity: 'REQUIRED' | 'CONDITIONAL' | 'UNKNOWN' | 'NOT_APPLICABLE';
    state: DocumentState;
    method: string;
    issuer: string;
    issueUrl: string | null;
    note: string;
    evidence: Evidence;
}
export interface Schedule {
    id: string;
    title: string;
    date: string;
    recorded: boolean;
    evidence: Evidence;
}
export interface Preparation {
    id: string;
    noticeId: string;
    title: string;
    version: number;
    revision: number;
    guideState: GuideState;
    questions: Question[];
    answers: Record<string, Answer>;
    documents: DocumentItem[];
    schedules: Schedule[];
    progress: number;
    houseStage: number;
    warnings: string[];
    nextAction: { label: string; reason: string; path: string };
}
export interface Guide {
    id: string;
    category: string;
    title: string;
    body: string;
    evidence: Evidence;
}
export interface Review {
    id: string;
    notice: Notice;
    basic: 'PASS' | 'HOLD';
    guide: 'PASS' | 'HOLD' | 'UNSUPPORTED';
    reason: string;
    revision: number;
}
export interface Collection {
    id: string;
    status: string;
    startedAt: string;
    message: string;
}
