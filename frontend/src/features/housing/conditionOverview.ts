import {
    noticeConditionHeading,
    noticeConditionSentence,
} from './plainNoticeCondition';
import {
    isOsanSubmissionGuide,
    osanConditionDetail,
} from './osanConditionDetail';
import { gyeongjuConditionDetail } from './gyeongjuConditionDetail';
import { samryeConditionDetail } from './samryeConditionDetail';
import {
    pohangConditionDetail,
    pohangConditionHeading,
} from './pohangConditionDetail';
import {
    conciseConditionPreview,
    explanationLines,
    generalConditionDetail,
} from './generalConditionDetail';
import type { StructuredConditionDetail } from './structuredConditionDetail';
import { questionReviewIssues } from './reviewIssueSelectors';
import type { Notice, Question, ReviewIssue } from './types';
import {
    listedConditionDetail,
    reviewedConditionPresentation,
} from './reviewedConditionPresentation';

function reviewedDetail(question: Question) {
    return (
        osanConditionDetail(question) ??
        gyeongjuConditionDetail(question) ??
        samryeConditionDetail(question) ??
        pohangConditionDetail(question) ??
        listedConditionDetail(question)
    );
}

export function conditionHeading(question: Question) {
    return (
        pohangConditionHeading(question) ??
        reviewedConditionPresentation(question)?.heading ??
        noticeConditionHeading(question.title)
    );
}

export function noticeConditionQuestions(questions: Question[]) {
    return questions.filter(
        (question) =>
            !isOsanSubmissionGuide(question) &&
            !(
                question.id === 'applies-mydata' &&
                samryeConditionDetail(question)
            ),
    );
}

export function reviewedSummaryDetail(notice: Notice, id: string) {
    if (notice.guideState !== 'AVAILABLE' || !notice.qualificationSummary)
        return;
    const question = notice.questions.find((item) => item.id === id);
    if (
        question &&
        !question.reviewRequired &&
        !questionReviewIssues(notice.reviewIssues, id).length
    )
        return reviewedDetail(question);
}

export function showQuestionEvidence(
    question: Question,
    detail:
        StructuredConditionDetail | null | undefined = question.reviewRequired
        ? null
        : reviewedDetail(question),
) {
    if (/\d+\s*(?:쪽\s*)?[~～〜–—-]\s*\d+\s*쪽/u.test(question.evidence.label))
        return false;
    // The same PDF page can be stored with or without its verified digest.
    const target = question.evidence.url?.split('&sha256=')[0];
    return !detail?.sections.some((section) =>
        section.evidence.some(
            (item) => item.url?.split('&sha256=')[0] === target,
        ),
    );
}

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

function conditionPreview(lines: string[]) {
    const [first = '', next] = lines;
    // A general introduction can precede the actual limit on the next line.
    const adult = first.match(
        /^(공고일 기준 )?(?:원칙적으로 |원칙은 )?(만\s*)?(\d+)세 이상(?:이 기본입니다|이 원칙이에요|이 신청할 수 있어요|이에요)\.$/u,
    );
    if (
        adult &&
        lines
            .slice(1)
            .some((line) =>
                /^미성년 예외는|미성년 세대주도 (?:다음 경우에는 )?신청할 수 있어요|미성년 세대주는 예외가 있습니다/u.test(
                    line,
                ),
            )
    )
        return (
            (adult[1] ?? '') +
            (adult[2] ? '만 ' : '') +
            adult[3] +
            '세부터 신청 · 미성년자는 일부 예외'
        );
    if (first === '자동차 기준은 완화하지 않습니다.' && next)
        return next.replace(
            /^영업용이 아닌 승용차 중 가장 비싼 차의 평가액이 ([\d,]+만 원) 이하여야 해요\.$/u,
            '영업용이 아닌 승용차 중 가장 비싼 차가 $1 이하',
        );
    if (first === '자격은 신청일 기준으로 확인합니다.' && next)
        return '신청일 기준 · ' + next;
    return first;
}

function answerInstruction(sentence: string) {
    return (
        /^(?:해당 항목을 함께 선택하되, )?해당 없음(?:·잘 모르겠어요)?은 다른 (?:선택|항목)과 함께 (?:고르지|선택하지) 마세요\.$/u.test(
            sentence,
        ) ||
        /^(?:해당 항목을 함께 선택하되, )?해당 없음·잘 모르겠어요는 다른 항목과 함께 선택하지 마세요\.$/u.test(
            sentence,
        )
    );
}

export function conditionOverview(question: Question, issues: ReviewIssue[]) {
    const needsReview = !!question.reviewRequired || issues.length > 0;
    const original = question.reason ?? '';
    const text = question.id.startsWith('e-section-')
        ? original.replace(
              /^원문 구간 [^\n]+입니다\. 연결된 앞뒤 구간과 예외를 함께 확인하세요\.\n\n/u,
              '',
          )
        : original;
    const sentences = explanationLines(text).filter(
        (sentence) => !answerInstruction(sentence),
    );
    if (needsReview) {
        return {
            needsReview,
            overview: [],
            preview: '',
            detail: original,
        };
    }
    const isDocumentCheck = question.title.endsWith('제출 대상인가요?');
    const overview = sentences.flatMap((sentence) => {
        const lines = /^1순위는 /.test(sentence)
            ? sentence.split(/, (?=\d+순위는 )/)
            : [sentence];
        return lines
            .flatMap((line) => noticeConditionSentence(line).split('\n'))
            .map((line) => conditionLine(line, isDocumentCheck));
    });
    const reviewed = reviewedDetail(question);
    const detail =
        reviewed?.text ?? sentences.map(noticeConditionSentence).join('\n');
    const structured =
        reviewed ?? generalConditionDetail(detail, question.title);
    const preview = reviewed
        ? (reviewed.preview ?? conditionPreview(overview))
        : conciseConditionPreview(
              overview,
              structured?.preview ?? conditionPreview(overview),
          );
    return {
        needsReview,
        overview,
        preview,
        detail,
        structured,
    };
}
