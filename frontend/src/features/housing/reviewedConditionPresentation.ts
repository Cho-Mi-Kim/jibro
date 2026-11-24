import type { AnswerOption, Question } from './types';
import type { StructuredConditionDetail } from './structuredConditionDetail';
import {
    matchesReviewedCondition,
    type ReviewedConditionIdentity,
} from './reviewedConditionIdentity';
import catalogIndex from './reviewedConditions/index.json';

export interface ReviewedConditionPresentation extends ReviewedConditionIdentity {
    noticeId: string;
    sourceOptions?: AnswerOption[] | null;
    heading: string;
    preview: string;
    sections: StructuredConditionDetail['sections'];
    optionLabels?: Record<string, string>;
}

type Catalog = { entries: ReviewedConditionPresentation[] };
const catalogs = import.meta.glob<Catalog>(
    './reviewedConditions/notice-*.json',
    {
        import: 'default',
    },
);
const byQuestion = new Map<string, ReviewedConditionPresentation[]>();
const loaded = new Map<string, Promise<void>>();
function register({ entries }: Catalog) {
    for (const entry of entries) {
        const current = byQuestion.get(entry.questionId) ?? [];
        current.push(entry);
        byQuestion.set(entry.questionId, current);
    }
}

/** Load only the original documents used by this response; list pages never load all notices. */
export async function withReviewedConditions<
    T extends { questions?: Question[] },
>(data: T): Promise<T> {
    const index: Record<string, string[]> = catalogIndex;
    const ids = new Set(
        (data.questions ?? []).flatMap(
            (q) => index[q.evidence.url?.split('#')[0] ?? ''] ?? [],
        ),
    );
    await Promise.all(
        [...ids].map((id) => {
            const loader = catalogs[`./reviewedConditions/notice-${id}.json`];
            if (!loader)
                throw new Error(
                    '검수한 설명을 불러오지 못했어요. 다시 시도해 주세요.',
                );
            if (!loaded.has(id)) {
                loaded.set(
                    id,
                    loader()
                        .then(register)
                        .catch(() => {
                            loaded.delete(id);
                            throw new Error(
                                '검수한 설명을 불러오지 못했어요. 다시 시도해 주세요.',
                            );
                        }),
                );
            }
            return loaded.get(id);
        }),
    );
    return data;
}

export function reviewedConditionPresentation(question: Question) {
    return byQuestion
        .get(question.id)
        ?.find((entry) => matchesReviewedCondition(question, entry));
}

export function listedConditionDetail(
    question: Question,
): StructuredConditionDetail | undefined {
    const entry = reviewedConditionPresentation(question);
    if (!entry) return;
    return {
        preview: entry.preview,
        sections: entry.sections,
        text: entry.sections
            .flatMap(({ text, table }) =>
                table
                    ? [
                          text,
                          table.title,
                          ...table.rows.map((row) => row.join(' ')),
                      ]
                    : [text],
            )
            .join('\n'),
    };
}

export function reviewedAnswerOptions(
    question: Question,
    options: AnswerOption[],
): AnswerOption[] {
    const entry = reviewedConditionPresentation(question);
    const shape = (values?: AnswerOption[] | null) =>
        values?.map(({ value, label, requiresFollowUp }) => [
            value,
            label,
            !!requiresFollowUp,
        ]) ?? null;
    if (
        !entry?.optionLabels ||
        JSON.stringify(shape(question.options)) !==
            JSON.stringify(shape(entry.sourceOptions))
    )
        return options;
    return options.map((option) => ({
        ...option,
        label: entry.optionLabels?.[option.value] ?? option.label,
    }));
}
