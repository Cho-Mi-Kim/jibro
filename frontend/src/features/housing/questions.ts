import type { Answer, Question } from './types';
import { answerConflictMessages } from './answerDependencies';
import {
    questionKind,
    answerOptions,
    validAnswer,
} from './questionAnswerInput';
export {
    questionKind,
    answerOptions,
    validAnswer,
} from './questionAnswerInput';

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

export function conditionAnswerState(
    question: Question,
    value?: Answer,
    answers?: Readonly<Record<string, Answer>>,
    questions?: readonly Question[],
) {
    if (question.reviewRequired) return 'official';
    if (value === undefined) return 'unanswered';
    if (value === 'UNKNOWN' || !validAnswer(question, value)) return 'followUp';
    if (answerConflictMessages(question, value, answers, questions).length)
        return 'followUp';
    if (
        questionKind(question) === 'NUMBER' ||
        questionKind(question) === 'DATE'
    )
        return 'complete';
    const values = Array.isArray(value) ? value : [value];
    const followUp = answerOptions(question).some(
        (option) =>
            option.requiresFollowUp &&
            values.some((item) => item === option.value),
    );
    return followUp ? 'followUp' : 'complete';
}

export function needsFollowUp(
    question: Question,
    value?: Answer,
    answers?: Readonly<Record<string, Answer>>,
    questions?: readonly Question[],
) {
    return (
        conditionAnswerState(question, value, answers, questions) !== 'complete'
    );
}

const acknowledgementLabels = new Set([
    '확인했어요',
    '안내를 확인했어요',
    '안내 내용을 확인했어요',
    '안내 내용을 모두 확인했어요',
    '기준과 예외를 함께 확인했어요',
    '안내를 읽었어요',
]);
const pendingAcknowledgementLabels = new Set([
    '추가 확인이 필요해요',
    '아직 확인할 내용이 남아 있어요',
    '확인 전',
    '아직 확인 전이에요',
    '아직 확인하지 않았어요',
]);

/** Classify the stored answer contract, never a rewritten title or option label. */
export function isAcknowledgementQuestion(question: Question) {
    const options = question.options;
    if (questionKind(question) !== 'SINGLE' || !options || options.length < 2)
        return false;
    const acknowledged = (option: (typeof options)[number]) =>
        ['ACK', 'YES', 'CHECKED', 'CONFIRMED'].includes(option.value) &&
        acknowledgementLabels.has(option.label.trim()) &&
        !option.requiresFollowUp;
    return (
        options.some(acknowledged) &&
        options.some((option) => option.value === 'UNKNOWN') &&
        options.every(
            (option) =>
                acknowledged(option) ||
                option.value === 'UNKNOWN' ||
                (['NO', 'REVIEW', 'PENDING', 'NOT_YET'].includes(
                    option.value,
                ) &&
                    pendingAcknowledgementLabels.has(option.label.trim())),
        )
    );
}

/** Keep every answer that controls a document or another answer, including old snapshots. */
export function applicantQuestions(
    questions: Question[],
    documentQuestionIds: readonly string[] = [],
) {
    const linked = new Set(documentQuestionIds);
    for (const question of questions) {
        if (question.applicability)
            linked.add(question.applicability.questionId);
        for (const dependency of question.answerDependencies ?? []) {
            linked.add(question.id);
            linked.add(dependency.questionId);
        }
    }
    return questions.filter(
        (question) =>
            question.reviewRequired ||
            linked.has(question.id) ||
            !isAcknowledgementQuestion(question),
    );
}

export function answerableQuestions(
    questions: Question[],
    documentQuestionIds: readonly string[] = [],
) {
    return applicantQuestions(questions, documentQuestionIds).filter(
        (question) => !question.reviewRequired,
    );
}
