import type { Answer, Question } from './types';
import { reviewedAnswerOptions } from './reviewedConditionPresentation';

export const questionKind = (question: Question) =>
    question.inputKind ?? 'SINGLE';

export const answerOptions = (question: Question) =>
    questionKind(question) === 'NUMBER' || questionKind(question) === 'DATE'
        ? [{ value: 'UNKNOWN', label: '잘 모르겠어요' }]
        : reviewedAnswerOptions(
              question,
              question.options ?? [
                  { value: 'YES', label: '확인했어요' },
                  { value: 'NO', label: '해당하지 않아요' },
                  { value: 'UNKNOWN', label: '잘 모르겠어요' },
              ],
          );

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

// Validate the same JSON decimal as the server, without floating-point modulo.
function decimalUnits(value: number): bigint | null {
    const [mantissa, exponent = '0'] = value.toString().split('e');
    const [whole, fraction = ''] = mantissa.split('.');
    const scale = 6 + Number(exponent) - fraction.length;
    if (scale < 0) return null;
    return BigInt(whole + fraction) * 10n ** BigInt(scale);
}
