import { summarySentences } from './supportSummaryRows';
import type { Question, ReviewIssue } from './types';

function conditionLine(sentence: string, isDocumentCheck: boolean) {
    if (isDocumentCheck && sentence.endsWith('?')) {
        return sentence
            .replace(/(으로|로) 신청하나요\?$/, '$1 신청하는 경우')
            .replace(/외국인인가요\?$/, '외국인인 경우');
    }
    return sentence
        .replace(/ 중 공고에 정한 유형을 확인하세요\.$/, ' 중 해당 유형')
        .replace(/(이상|이하|미만|이내|초과|기준)입니다\.$/, '$1')
        .replace(
            /(요건|범위|규칙|여부|방법|시점|기간|예외)[을를] 확인하세요\.$/,
            '$1 확인',
        );
}

export function conditionOverview(question: Question, issues: ReviewIssue[]) {
    const needsReview = !!question.reviewRequired || issues.length > 0;
    const sentences = summarySentences(question.reason ?? '');
    // Keep complete clauses, including numbers, dates and parenthetical qualifications.
    const count =
        sentences.length > 1 && sentences[0].length + sentences[1].length <= 140
            ? 2
            : 1;
    const isDocumentCheck = question.title.endsWith('제출 대상인가요?');
    const overview = needsReview
        ? []
        : sentences
              .slice(0, count)
              .map((sentence) => conditionLine(sentence, isDocumentCheck));
    const hasDetails =
        sentences.length > overview.length ||
        overview.some((line, index) => line !== sentences[index]);
    return { needsReview, overview, hasDetails };
}
