import { pohangReviewedEntry } from '../housing/pohangConditionDetail';
import {
    answerOptions,
    questionKind,
    sameAnswer,
    validAnswer,
} from '../housing/questions';
import type { Answer, Question } from '../housing/types';

export type ApplicantQuestionContext = {
    answers: Readonly<Record<string, Answer>>;
    questions: readonly Question[];
};

const categoryLabels: Readonly<Record<string, string>> = {
    YOUTH: '청년',
    EARLY_CAREER: '사회초년생',
    NEWLYWED: '신혼부부',
    PREMARITAL: '예비신혼부부',
    SINGLE_PARENT: '한부모가족',
    ELDER: '고령자',
    INDUSTRIAL: '산업단지 근로자',
    INTEGRATED: '통합공공임대 일반공급',
};

function categoryQuestion(questions: readonly Question[]) {
    const category = questions.find((question) => question.id === 'category');
    if (
        !category ||
        !pohangReviewedEntry(category) ||
        questionKind(category) !== 'SINGLE'
    )
        return;
    const options = category.options;
    if (
        !options ||
        Object.entries(categoryLabels).some(([value, label]) => {
            const option = options.find((item) => item.value === value);
            return (
                !option ||
                option.requiresFollowUp ||
                !(
                    option.label === label ||
                    option.label.startsWith(`${label} · `)
                )
            );
        })
    )
        return;
    return category;
}

function contextualQuestion(question: Question) {
    if (
        !['household', 'income-assets'].includes(question.id) ||
        !pohangReviewedEntry(question) ||
        questionKind(question) !== 'SINGLE'
    )
        return false;
    const options = question.options;
    return (
        options?.length === 3 &&
        [
            ['MEETS', '내 신청 유형의 기준에 맞아요', false],
            ['DOES_NOT_MEET', '내 신청 유형의 기준에 맞지 않아요', true],
            ['UNKNOWN', '잘 모르겠어요', true],
        ].every(([value, label, followUp]) =>
            options.some(
                (option) =>
                    option.value === value &&
                    option.label === label &&
                    !!option.requiresFollowUp === followUp,
            ),
        )
    );
}

/** Explain the existing "my application type meets the criteria" answer in its selected context. */
export function pohangApplicantQuestion(
    question: Question,
    context?: ApplicantQuestionContext,
) {
    if (!context || !contextualQuestion(question)) return;
    const category = categoryQuestion(context.questions);
    const selected = category && context.answers[category.id];
    if (
        !category ||
        typeof selected !== 'string' ||
        !categoryLabels[selected] ||
        !validAnswer(category, selected)
    )
        return;

    let title: string;
    let help: string;
    let preview: string;
    let meets: string;
    let doesNotMeet: string;
    if (question.id === 'household') {
        // Marital status changes the industrial-worker household scope; category alone cannot resolve it.
        if (selected === 'INDUSTRIAL') return;
        const self = selected === 'YOUTH' || selected === 'EARLY_CAREER';
        const future = selected === 'PREMARITAL';
        const integrated = selected === 'INTEGRATED';
        title = self
            ? '본인 명의의 집이 있나요?'
            : future
              ? '결혼 후 함께 살 가족에게 집이 있나요?'
              : '신청자와 심사에 포함되는 가족에게 집이 있나요?';
        help = self
            ? '부모 등 다른 가족의 집은 확인하지 않아요. 본인의 주택·분양권·입주권을 확인해 주세요.'
            : future
              ? '신청자와 예비배우자를 포함해 결혼 후 함께 살 세대원 모두 확인해 주세요.'
              : '신청자와 배우자, 공고의 심사 범위에 포함되는 가족을 확인해 주세요. 주소가 다른 배우자도 포함해요.';
        preview = integrated
            ? '통합공공임대 일반공급은 포항 밖 주택·소형·저가주택 완화를 적용하지 않아요.'
            : '포항 밖 주택이나 공고의 소형·저가주택 등 허용 예외도 함께 확인해요.';
        meets = integrated
            ? '집이 없거나, 공고의 무주택 인정 기준에 해당해요'
            : '없거나, 공고에서 허용하는 소형·저가주택 등이 있어요';
        doesNotMeet = '허용 기준에 해당하지 않는 집이 있어요';
    } else {
        const integrated = selected === 'INTEGRATED';
        title = integrated
            ? '소득·재산·자동차 기준에 맞나요?'
            : '자동차 기준에 맞나요?';
        help = integrated
            ? '심사 대상 세대의 소득·총자산과 자동차를 함께 확인해 주세요.'
            : '이번 행복주택 모집은 소득·총자산 한도를 적용하지 않아요. 자동차의 확인 대상과 한도·예외를 확인해 주세요.';
        preview = integrated
            ? '통합공공임대 일반공급의 소득·총자산·자동차 기준을 적용해요.'
            : '집을 본인만 확인하는 유형도 자동차는 가족을 함께 확인할 수 있어요.';
        meets = integrated
            ? '소득·총자산·자동차 기준과 적용 예외에 맞아요'
            : '자동차가 없거나, 한도·가산·제외 기준에 맞아요';
        doesNotMeet = integrated
            ? '소득·총자산·자동차 기준 중 맞지 않는 항목이 있어요'
            : '한도·가산·제외 기준에 맞지 않는 자동차가 있어요';
    }
    return {
        title,
        help,
        preview,
        needsCriteria: true,
        options: answerOptions(question).map((option) => ({
            ...option,
            label:
                option.value === 'MEETS'
                    ? meets
                    : option.value === 'DOES_NOT_MEET'
                      ? doesNotMeet
                      : option.label,
        })),
    };
}

/** A changed application type must not silently reuse criteria answered for the previous type. */
export function updateApplicantAnswer(
    questions: readonly Question[],
    answers: Readonly<Record<string, Answer>>,
    id: string,
    value: Answer,
): Record<string, Answer> {
    const next = { ...answers, [id]: value };
    if (
        id === categoryQuestion(questions)?.id &&
        !sameAnswer(answers[id], value)
    ) {
        for (const question of questions) {
            if (
                contextualQuestion(question) &&
                answers[question.id] !== undefined
            )
                next[question.id] = 'UNKNOWN';
        }
    }
    return next;
}
