import type { DocumentItem, ReviewIssue } from './types';

export function questionReviewIssues(
    issues: ReviewIssue[] | undefined,
    id: string,
) {
    return (issues ?? []).filter((issue) => issue.questionIds.includes(id));
}

export function documentReviewIssues(
    issues: ReviewIssue[] | undefined,
    id: string,
    questionId?: string | null,
) {
    return (issues ?? []).filter(
        (issue) =>
            issue.documentIds.includes(id) ||
            (!!questionId && issue.questionIds.includes(questionId)),
    );
}

export function scheduleReviewIssues(issues: ReviewIssue[] | undefined) {
    return (issues ?? []).filter((issue) => issue.area === 'SCHEDULE');
}

export function documentNeedsReview(
    document: DocumentItem,
    issues?: ReviewIssue[],
) {
    return (
        !!document.reviewRequired ||
        documentReviewIssues(issues, document.id).length > 0
    );
}
