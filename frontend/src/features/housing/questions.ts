import type { Question } from './types';

export const answerOptions = (question: Question) =>
    question.options ?? [
        { value: 'YES', label: '확인했어요' },
        { value: 'NO', label: '해당하지 않아요' },
        { value: 'UNKNOWN', label: '잘 모르겠어요' },
    ];

export const answerLabel = (question: Question, value?: string) =>
    answerOptions(question).find((option) => option.value === value)?.label ??
    '아직 답변하지 않았어요';

export const needsFollowUp = (question: Question, value?: string) =>
    !value ||
    value === 'UNKNOWN' ||
    !answerOptions(question).some(
        (option) => option.value === value && !option.requiresFollowUp,
    );
