export type GuideState =
    'AVAILABLE' | 'REVIEWING' | 'UNSUPPORTED' | 'CHANGE_REVIEW';
export type DocumentState =
    'UNCHECKED' | 'NEEDED' | 'IN_PROGRESS' | 'READY' | 'RECHECK';
export type Answer = string | number | string[];
export type QuestionKind = 'SINGLE' | 'MULTIPLE' | 'NUMBER' | 'DATE';
export interface InputConstraints {
    min?: number | null;
    max?: number | null;
    step?: number | null;
    minDate?: string | null;
    maxDate?: string | null;
    maxSelections?: number | null;
    unit?: string | null;
}
export interface AnswerOption {
    value: string;
    label: string;
    requiresFollowUp?: boolean;
}
export interface AnswerDependency {
    questionId: string;
    answerValues: string[];
    conflictingValues: string[];
    requiredDocumentIds: string[];
    message: string;
}
export interface Evidence {
    label: string;
    url: string | null;
    checkedAt: string;
}
export interface ReviewIssue {
    id: string;
    area: 'CONDITION' | 'DOCUMENT' | 'SCHEDULE';
    title: string;
    detail: string;
    questionIds: string[];
    documentIds: string[];
    evidence: Evidence[];
}
export interface DocumentTemplate {
    id: string;
    title: string;
    conditionQuestionId?: string | null;
    method: string;
    issuer: string;
    issueUrl: string | null;
    issueLabel?: string | null;
    evidence: Evidence;
    conditionAnswerValues?: string[] | null;
}
export interface QuestionApplicability {
    questionId: string;
    answerValues: string[];
}
export interface Question {
    id: string;
    title: string;
    reason: string;
    evidence: Evidence;
    options?: AnswerOption[] | null;
    inputKind?: QuestionKind | null;
    constraints?: InputConstraints | null;
    reviewRequired?: boolean;
    answerDependencies?: AnswerDependency[] | null;
    applicability?: QuestionApplicability | null;
}
export type NoticeLifecycleState = 'NORMAL' | 'EARLY_CLOSED' | 'CANCELLED';
export interface NoticeLifecycle {
    state: NoticeLifecycleState;
    reason: string;
    evidence: Evidence | null;
    checkedAt: string | null;
    revision: number;
}
export interface DocumentSummaryGroup {
    items: { id: string; title: string; evidence: Evidence }[];
    remainingCount: number;
    remainingIds?: string[];
}
export interface Notice {
    id: string;
    title: string;
    agency: string;
    region: string;
    type: string;
    status: 'OPEN' | 'UPCOMING' | 'CLOSED' | 'UNKNOWN';
    lifecycle?: NoticeLifecycle | null;
    startDate: string | null;
    endDate: string | null;
    applicationTiming?: {
        startsAt: string | null;
        endsAt: string | null;
        nextChangeAt: string | null;
        untilFilled?: boolean;
    } | null;
    publishedDate: string | null;
    attachments: Evidence[];
    summary: string;
    documentSummary?: {
        common: DocumentSummaryGroup;
        conditional: DocumentSummaryGroup;
        reviewRequiredCount: number;
    } | null;
    qualificationSummary?: {
        eligibility: string;
        selection: string;
        detail: string | null;
        evidence: Evidence | null;
        reviewRequired: boolean;
    } | null;
    version: number;
    guideVersion: number | null;
    guideState: GuideState;
    canStart: boolean;
    basicCheckedAt?: string | null;
    guideCheckedAt?: string | null;
    collectedAt?: string | null;
    evidence: Evidence;
    questions: Question[];
    documents: string[];
    documentTemplates?: DocumentTemplate[];
    reviewIssues?: ReviewIssue[];
    schedules?: Omit<Schedule, 'recorded'>[];
}
export interface Page<T> {
    items: T[];
    page: number;
    size: number;
    totalElements: number;
    totalPages: number;
}
export type NoticeListItem = Pick<
    Notice,
    | 'id'
    | 'title'
    | 'agency'
    | 'region'
    | 'type'
    | 'status'
    | 'lifecycle'
    | 'startDate'
    | 'endDate'
    | 'applicationTiming'
    | 'publishedDate'
    | 'guideState'
    | 'collectedAt'
> & { partialGuidance: boolean };

export type PreparationListItem = Pick<
    Preparation,
    'id' | 'noticeId' | 'title' | 'version' | 'revision' | 'progress'
>;
export interface User {
    id: string;
    name: string;
    email: string;
    role: 'USER' | 'ADMIN';
    trial?: boolean;
}
export interface DocumentItem {
    id: string;
    title: string;
    necessity: 'REQUIRED' | 'CONDITIONAL' | 'UNKNOWN' | 'NOT_APPLICABLE';
    reviewRequired?: boolean;
    state: DocumentState;
    stateBeforeReady?: Exclude<DocumentState, 'READY'> | null;
    method: string;
    issuer: string;
    issueUrl: string | null;
    issueLabel?: string | null;
    note: string;
    evidence: Evidence;
}
export interface Schedule {
    timingDescription?: string | null;
    id: string;
    title: string;
    date: string;
    recorded: boolean;
    evidence: Evidence;
}
export interface FollowUpRecord {
    result: 'WAITING' | 'SELECTED' | 'WAITLIST' | 'NOT_SELECTED';
    waitlistNumber: number | null;
    documentsSubmitted: boolean;
    contractCompleted: boolean;
    movedIn: boolean;
}
export interface Preparation {
    followUp?: FollowUpRecord | null;
    documentConditions?: Record<string, string>;
    documentConditionValues?: Record<string, string[]>;
    id: string;
    noticeId: string;
    title: string;
    version: number;
    revision: number;
    guideState: GuideState;
    reviewIssues?: ReviewIssue[];
    questions: Question[];
    answers: Record<string, Answer>;
    documents: DocumentItem[];
    schedules: Schedule[];
    progress: number;
    houseStage: number;
    warnings: string[];
    nextAction: {
        label: string;
        reason: string;
        path: string;
        completed?: boolean;
    };
}
export interface Guide {
    id: string;
    category: string;
    title: string;
    body: string;
    evidence: Evidence;
    scope?: string;
    additionalEvidence?: Evidence[];
    freshness?: {
        status: 'CURRENT' | 'RECHECK' | 'UNREACHABLE';
        checkedAt?: string;
        lastAttemptAt?: string;
        message?: string;
    };
}
export interface GuidePageData {
    items: Guide[];
    page: number;
    hasMore: boolean;
    totalPages: number;
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
