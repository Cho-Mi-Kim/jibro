import { describe, expect, it } from 'vitest';
import {
    documentNeedsReview,
    documentReviewIssues,
    questionReviewIssues,
    scheduleReviewIssues,
} from './reviewIssueSelectors';
import type { DocumentItem, ReviewIssue } from './types';

const issue: ReviewIssue = {
    id: 'scope',
    area: 'CONDITION',
    title: '가구 범위',
    detail: '공식 자료의 적용 대상 확인 필요',
    questionIds: ['family'],
    documentIds: ['family-doc'],
    evidence: [],
};

describe('부분 준비 안내의 확인 대상', () => {
    it('공식 기준 미확정과 사용자 미응답으로 생긴 UNKNOWN 서류를 구분한다', () => {
        const document = {
            id: 'family-doc',
            necessity: 'UNKNOWN',
        } as DocumentItem;
        expect(documentNeedsReview(document, [issue])).toBe(true);
        expect(
            documentNeedsReview({ ...document, id: 'normal-doc' }, [issue]),
        ).toBe(false);
        expect(
            documentNeedsReview({
                ...document,
                id: 'normal-doc',
                reviewRequired: true,
            }),
        ).toBe(true);
        expect(documentNeedsReview(document)).toBe(false);
    });
    it('연결된 항목만 설명을 받으며 누락된 선택 필드는 기존 공고와 호환된다', () => {
        expect(questionReviewIssues([issue], 'family')).toEqual([issue]);
        expect(questionReviewIssues([issue], 'age')).toEqual([]);
        expect(documentReviewIssues([issue], 'family-doc')).toEqual([issue]);
        expect(
            documentReviewIssues([issue], 'dependent-doc', 'family'),
        ).toEqual([issue]);
        expect(documentReviewIssues(undefined, 'old-doc')).toEqual([]);
        expect(scheduleReviewIssues([issue])).toEqual([]);
    });
});
