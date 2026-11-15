import type {
    DocumentItem,
    DocumentTemplate,
    Question,
    Preparation,
} from './types';
import { conditionOverview } from './conditionOverview';
import { answerOptions } from './questions';
import { summarySentences } from './supportSummaryRows';
import { documentReviewIssues } from './reviewIssueSelectors';
import { safeUrl } from '../../shared/ui/helpers';
import { onlineIssuanceLinks } from './onlineIssuance';

type DocumentRule = Pick<
    DocumentTemplate,
    'conditionQuestionId' | 'conditionAnswerValues'
>;
type DocumentContext = Pick<
    Preparation,
    | 'questions'
    | 'reviewIssues'
    | 'documentConditions'
    | 'documentConditionValues'
>;

function audienceConditions(question: Question, template: DocumentRule) {
    const values = template.conditionAnswerValues ?? ['YES'];
    const options = answerOptions(question).filter((option) =>
        values.includes(option.value),
    );
    if (options.length !== new Set(values).size) return [];
    return options.map((option) => {
        if (
            option.value === 'YES' &&
            /^(제출 대상이에요|확인했어요|해당해요)$/.test(option.label)
        ) {
            if (question.title.endsWith('제출 대상인가요?')) {
                const [criterion] = conditionOverview(question, []).overview;
                if (criterion)
                    return criterion
                        .replace(/해당하지 않나요\?$/, '해당하지 않는 경우')
                        .replace(/있나요\?$/, '있는 경우')
                        .replace(/하려 하나요\?$/, '하려는 경우')
                        .replace(/하나요\?$/, '하는 경우');
            }
            return `‘${question.title}’에 해당하는 신청자`;
        }
        return option.label;
    });
}

function issuanceInstruction(method: string) {
    const sentences = summarySentences(method).flatMap((sentence) =>
        sentence.split(/(?<=(?:발급|제외|서명|제출|날인)\.)\s+/u),
    );
    const specific = sentences.filter(
        (sentence) =>
            sentence !== '공고일 이후 발급 기준을 확인하세요.' &&
            sentence !== '공고의 해당자 제출 기준을 확인하세요.',
    );
    if (!specific.length)
        return sentences.find(
            (sentence) => sentence === '공고일 이후 발급 기준을 확인하세요.',
        );
    const [first, next] = specific;
    return next &&
        /미만|제외|생략|열람용/.test(next) &&
        first.length + next.length < 85
        ? first + ' ' + next
        : first;
}

export function documentSummary(
    document: DocumentItem | DocumentTemplate,
    context: DocumentContext,
    {
        template,
        historical = false,
    }: { template?: DocumentRule; historical?: boolean } = {},
) {
    const rule =
        template ??
        ('necessity' in document
            ? {
                  conditionQuestionId:
                      context.documentConditions?.[document.id],
                  conditionAnswerValues:
                      context.documentConditionValues?.[document.id],
              }
            : document);
    const questionId = rule.conditionQuestionId;
    const question = questionId
        ? context.questions.find((q) => q.id === questionId)
        : undefined;
    const necessity =
        'necessity' in document
            ? document.necessity
            : questionId
              ? 'CONDITIONAL'
              : 'REQUIRED';
    const reviewRequired =
        'reviewRequired' in document && document.reviewRequired;
    const issues = documentReviewIssues(
        context.reviewIssues,
        document.id,
        questionId,
    );
    const methodNeedsReview =
        issues.some((issue) => issue.documentIds.includes(document.id)) ||
        (reviewRequired && !question?.reviewRequired && issues.length === 0);
    const needsReview =
        reviewRequired || question?.reviewRequired || issues.length > 0;
    const criteria =
        question && !needsReview ? audienceConditions(question, rule) : [];
    const who =
        criteria.slice(0, 2).join(' 또는 ') +
        (criteria.length > 2 ? ` 외 ${criteria.length - 2}개 유형` : '');
    const audience = needsReview
        ? '공식 확인 필요'
        : necessity === 'REQUIRED'
          ? '모든 신청자'
          : necessity === 'UNKNOWN'
            ? ['해당 여부 추가 확인', who].filter(Boolean).join(' · ')
            : who ||
              (necessity === 'NOT_APPLICABLE'
                  ? '당시 해당 없음'
                  : '공고의 해당자 제출 기준을 충족하는 신청자');

    // Archived records can use only the issuance route stored with that version.
    const links =
        historical && !safeUrl(document.issueUrl)
            ? []
            : onlineIssuanceLinks(document);
    const issuer = document.issuer?.trim();
    const channel =
        issuer && issuer !== '공고문 기재 발급처' ? issuer : links[0]?.label;
    const instruction = issuanceInstruction(document.method ?? '');
    const how =
        [channel, instruction].filter(Boolean).join(' · ') ||
        (historical
            ? '당시 발급 방법 기록 없음'
            : '공식 공고의 발급 안내 확인');
    return {
        audience,
        method: methodNeedsReview ? '공식 근거에서 발급 기준 확인' : how,
        criteria,
        issues,
        links,
        issuer,
        question,
    };
}
