import type { Evidence, Question } from './types';

type AnswerContract = Pick<
    Question,
    | 'inputKind'
    | 'options'
    | 'constraints'
    | 'answerDependencies'
    | 'applicability'
>;

export interface ReviewedConditionIdentity {
    questionId: string;
    sourceTitle: string;
    acceptedTitles?: string[];
    sourceReason: string;
    acceptedReasons?: string[];
    sourceEvidence: { url: string; sha256?: string };
    answerContract?: AnswerContract;
}

function evidenceKey(evidence: Pick<Evidence, 'url'>) {
    // Digest is sometimes carried in the locator fragment, but page/paragraph is not optional.
    return evidence.url?.replace(/&sha256=[a-f0-9]{64}/gu, '');
}

function answerContractValues(question: AnswerContract) {
    return {
        inputKind: question.inputKind ?? 'SINGLE',
        options:
            question.options?.map(({ value, label, requiresFollowUp }) => [
                value,
                label,
                !!requiresFollowUp,
            ]) ?? null,
        constraints: Object.entries(question.constraints ?? {})
            .filter(([, value]) => value != null)
            .sort(([a], [b]) => a.localeCompare(b)),
        applicability: question.applicability
            ? [
                  question.applicability.questionId,
                  question.applicability.answerValues,
              ]
            : null,
        answerDependencies: (question.answerDependencies ?? []).map(
            ({
                questionId,
                answerValues,
                conflictingValues,
                requiredDocumentIds,
                message,
            }) => [
                questionId,
                answerValues,
                conflictingValues,
                requiredDocumentIds,
                message,
            ],
        ),
    };
}

function matchesAnswerContract(question: Question, contract?: AnswerContract) {
    if (!contract) return !question.applicability;
    const actual = answerContractValues(question);
    const expected = answerContractValues(contract);
    if (
        JSON.stringify(actual.applicability) !==
        JSON.stringify(expected.applicability)
    )
        return false;
    // A semantic patch can specify only the changed fields. Unspecified fields
    // must retain their original meaning instead of being inferred as defaults.
    return (Object.keys(contract) as (keyof AnswerContract)[]).every(
        (key) => JSON.stringify(actual[key]) === JSON.stringify(expected[key]),
    );
}

export function matchesReviewedCondition(
    question: Question,
    entry: ReviewedConditionIdentity,
) {
    if (
        question.reviewRequired ||
        question.id !== entry.questionId ||
        ![entry.sourceTitle, ...(entry.acceptedTitles ?? [])].includes(
            question.title,
        ) ||
        ![entry.sourceReason, ...(entry.acceptedReasons ?? [])].includes(
            question.reason,
        ) ||
        !matchesAnswerContract(question, entry.answerContract) ||
        evidenceKey(question.evidence) !== evidenceKey(entry.sourceEvidence)
    )
        return false;
    const digest = question.evidence.url?.match(/&sha256=([a-f0-9]{64})/u)?.[1];
    return !digest || digest === entry.sourceEvidence.sha256;
}
