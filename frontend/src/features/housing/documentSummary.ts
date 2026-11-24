import type {
    DocumentItem,
    DocumentTemplate,
    Notice,
    Question,
    Preparation,
} from './types';
import { conditionOverview } from './conditionOverview';
import { answerOptions } from './questions';
import { documentInstructions } from './documentInstructions';
import {
    documentReviewIssues,
    officialReviewLabel,
} from './reviewIssueSelectors';
import { safeUrl } from '../../shared/ui/helpers';
import { onlineIssuanceLinks } from './onlineIssuance';
import { noticeSummaryText } from './summaryTone';

type DocumentRule = Pick<
    DocumentTemplate,
    'conditionQuestionId' | 'conditionAnswerValues'
>;
type DocumentListing = Pick<DocumentTemplate, 'id' | 'title'> & {
    group: 'common' | 'conditional' | 'unspecified';
};
const listedNecessity = {
    common: 'REQUIRED',
    conditional: 'CONDITIONAL',
    unspecified: undefined,
} as const;

// Older notices can have names without templates. Only use a group if its
// published summary identifies this name unambiguously within the same notice.
export function listedDocument(title: string, notice: Notice): DocumentListing {
    const groups = ['common', 'conditional'] as const;
    const matches = groups.flatMap((group) =>
        (notice.documentSummary?.[group].items ?? [])
            .filter((item) => item.title === title)
            .map((item) => ({ id: item.id, title, group })),
    );
    return matches.length === 1
        ? matches[0]
        : { id: title, title, group: 'unspecified' };
}
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

export function documentSummary(
    input: DocumentItem | DocumentTemplate | DocumentListing,
    context: DocumentContext,
    {
        template,
        historical = false,
    }: { template?: DocumentRule; historical?: boolean } = {},
) {
    const document =
        'group' in input
            ? {
                  ...input,
                  necessity: listedNecessity[input.group],
                  issuer: '',
                  method: '',
                  issueUrl: null,
              }
            : input;
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
        ? officialReviewLabel
        : !necessity
          ? '공식 공고에서 제출 대상 확인'
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
    const {
        instruction,
        details,
        audience: describedAudience,
    } = documentInstructions(document.method ?? '');
    const how =
        [channel, instruction].filter(Boolean).join(' · ') ||
        (historical
            ? '당시 발급 방법 기록 없음'
            : '공식 공고의 발급 안내 확인');
    return {
        audience: noticeSummaryText(
            !needsReview &&
                (describedAudience.length || criteria.length > 2) &&
                necessity === 'UNKNOWN'
                ? '해당 여부 추가 확인'
                : !needsReview &&
                    (describedAudience.length || criteria.length > 2) &&
                    necessity === 'NOT_APPLICABLE'
                  ? '당시 해당 없음'
                  : !needsReview && describedAudience.length
                    ? '공고에 명시된 제출 대상'
                    : !needsReview && criteria.length > 2
                      ? '다음 중 하나에 해당하는 신청자'
                      : audience,
            context,
        ),
        method: noticeSummaryText(
            methodNeedsReview ? '공식 근거에서 발급 기준 확인' : how,
            context,
        ),
        methodDetails: methodNeedsReview
            ? []
            : details.map((text) => noticeSummaryText(text, context)),
        fullMethodShown:
            !methodNeedsReview && (!describedAudience.length || !needsReview),
        audienceDetails: needsReview
            ? []
            : [
                  ...new Set([
                      ...(criteria.length > 2 ? criteria : []),
                      ...describedAudience,
                  ]),
              ].map((text) => noticeSummaryText(text, context)),
        criteria,
        needsReview: !!needsReview,
        historical,
        issues,
        links: links.map((link) =>
            link.note
                ? { ...link, note: noticeSummaryText(link.note, context) }
                : link,
        ),
        issuer,
        question,
    };
}
