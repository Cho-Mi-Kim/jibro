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

export function areaReviewIssues(
    issues: ReviewIssue[] | undefined,
    area: ReviewIssue['area'],
) {
    return (issues ?? []).filter((issue) => {
        if (issue.area !== area) return false;
        if (area === 'SCHEDULE') return true;
        if (area === 'CONDITION') return !issue.questionIds.length;
        return !issue.questionIds.length && !issue.documentIds.length;
    });
}

export function documentReviewCountNote(count?: number) {
    return count ? `${count}개 서류는 제출 기준 확인이 필요해요.` : null;
}

export const officialReviewLabel = '공식 확인 필요';

export function documentNeedsReview(
    document: DocumentItem,
    issues?: ReviewIssue[],
) {
    return (
        !!document.reviewRequired ||
        documentReviewIssues(issues, document.id).length > 0
    );
}
