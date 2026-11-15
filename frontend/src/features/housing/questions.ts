import type { Answer, Question } from './types';

export const questionKind = (question: Question) =>
    question.inputKind ?? 'SINGLE';

export const answerOptions = (question: Question) =>
    questionKind(question) === 'NUMBER' || questionKind(question) === 'DATE'
        ? [{ value: 'UNKNOWN', label: '잘 모르겠어요' }]
        : (question.options ?? [
              { value: 'YES', label: '확인했어요' },
              { value: 'NO', label: '해당하지 않아요' },
              { value: 'UNKNOWN', label: '잘 모르겠어요' },
          ]);

export function inputDescription(question: Question) {
    const c = question.constraints;
    switch (questionKind(question)) {
        case 'NUMBER':
            return [
                '숫자 입력',
                c?.min != null ? `최소 ${c.min}` : '',
                c?.max != null ? `최대 ${c.max}` : '',
                `입력 단위 ${c?.step ?? 1}`,
                c?.unit ?? '',
            ]
                .filter(Boolean)
                .join(' · ');
        case 'DATE':
            return [
                '날짜 입력',
                c?.minDate ? `${c.minDate}부터` : '',
                c?.maxDate ? `${c.maxDate}까지` : '',
            ]
                .filter(Boolean)
                .join(' · ');
        case 'MULTIPLE':
            return `여러 항목 선택 · 최대 ${c?.maxSelections ?? answerOptions(question).length - 1}개`;
        default:
            return '한 항목 선택';
    }
}

export function answerLabel(question: Question, value?: Answer) {
    if (value === undefined) return '아직 답변하지 않았어요';
    if (value === 'UNKNOWN') return '잘 모르겠어요';
    if (questionKind(question) === 'NUMBER' && typeof value === 'number')
        return `${value.toLocaleString('ko-KR', { maximumFractionDigits: 6 })}${question.constraints?.unit ? ' ' + question.constraints.unit : ''}`;
    if (questionKind(question) === 'DATE' && typeof value === 'string')
        return value;
    const options = answerOptions(question);
    if (Array.isArray(value))
        return value
            .map(
                (item) =>
                    options.find((option) => option.value === item)?.label ??
                    item,
            )
            .join(', ');
    return (
        options.find((option) => option.value === value)?.label ??
        `저장된 답변: ${value}`
    );
}

export function validAnswer(
    question: Question,
    value: unknown,
): value is Answer {
    if (value === 'UNKNOWN') return true;
    const constraints = question.constraints;
    const kind = questionKind(question);
    if (kind === 'NUMBER') {
        if (
            typeof value !== 'number' ||
            !Number.isFinite(value) ||
            Math.abs(value) > Number.MAX_SAFE_INTEGER
        )
            return false;
        if (
            (constraints?.min != null && value < constraints.min) ||
            (constraints?.max != null && value > constraints.max)
        )
            return false;
        const units = decimalUnits(value);
        const origin = decimalUnits(constraints?.min ?? 0);
        const step = decimalUnits(constraints?.step ?? 1);
        return (
            units !== null &&
            origin !== null &&
            step !== null &&
            step > 0n &&
            (units - origin) % step === 0n
        );
    }
    if (kind === 'DATE') {
        if (
            typeof value !== 'string' ||
            !/^\d{4}-\d{2}-\d{2}$/.test(value) ||
            value.startsWith('0000')
        )
            return false;
        const parsed = new Date(value + 'T00:00:00Z');
        return (
            !Number.isNaN(parsed.valueOf()) &&
            parsed.toISOString().slice(0, 10) === value &&
            !(constraints?.minDate && value < constraints.minDate) &&
            !(constraints?.maxDate && value > constraints.maxDate)
        );
    }
    const options = answerOptions(question);
    if (kind === 'MULTIPLE')
        return (
            Array.isArray(value) &&
            value.length > 0 &&
            value.length <= (constraints?.maxSelections ?? 19) &&
            new Set(value).size === value.length &&
            value.every(
                (item) =>
                    typeof item === 'string' &&
                    item !== 'UNKNOWN' &&
                    options.some((option) => option.value === item),
            )
        );
    return (
        typeof value === 'string' &&
        options.some((option) => option.value === value)
    );
}

/** Submission rule only; saved-answer labels and assessments retain their existing interpretation. */
export function validSubmittedAnswer(
    question: Question,
    value: unknown,
): value is Answer {
    return (
        validAnswer(question, value) &&
        !(
            questionKind(question) === 'MULTIPLE' &&
            Array.isArray(value) &&
            value.includes('NONE') &&
            value.length !== 1
        )
    );
}

export function sameAnswer(
    left: Answer | undefined,
    right: Answer | undefined,
) {
    return Array.isArray(left) && Array.isArray(right)
        ? left.length === right.length &&
              left.every((value, index) => value === right[index])
        : left === right;
}

// Validate the same JSON decimal as the server, without floating-point modulo.
function decimalUnits(value: number): bigint | null {
    const [mantissa, exponent = '0'] = value.toString().split('e');
    const [whole, fraction = ''] = mantissa.split('.');
    const scale = 6 + Number(exponent) - fraction.length;
    if (scale < 0) return null;
    return BigInt(whole + fraction) * 10n ** BigInt(scale);
}

export function needsFollowUp(question: Question, value?: Answer) {
    if (question.reviewRequired) return true;
    if (value === 'UNKNOWN' || !validAnswer(question, value)) return true;
    if (
        questionKind(question) === 'NUMBER' ||
        questionKind(question) === 'DATE'
    )
        return false;
    const values = Array.isArray(value) ? value : [value];
    return values.some((item) =>
        answerOptions(question).some(
            (option) => option.value === item && option.requiresFollowUp,
        ),
    );
}

export function answerableQuestions(questions: Question[]) {
    return questions.filter((question) => !question.reviewRequired);
}
