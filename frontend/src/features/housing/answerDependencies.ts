import type { Answer, AnswerDependency, DocumentItem, Question } from './types';

import { validAnswer } from './questionAnswerInput';

type Answers = Readonly<Record<string, Answer>>;

function matches(value: Answer, choices: readonly string[]) {
    return (Array.isArray(value) ? value : [value]).some(
        (item) => typeof item === 'string' && choices.includes(item),
    );
}

function dependencyState(
    dependency: AnswerDependency,
    answers: Answers,
    questions?: readonly Question[],
) {
    const parent = questions?.find((q) => q.id === dependency.questionId);
    const answer = answers[dependency.questionId];
    if (
        !parent ||
        parent.reviewRequired ||
        answer === undefined ||
        answer === 'UNKNOWN' ||
        !validAnswer(parent, answer)
    )
        return 'UNKNOWN';
    return matches(answer, dependency.answerValues) ? 'MATCH' : 'NO_MATCH';
}

export function answerConflictMessages(
    question: Question,
    answer: Answer | undefined,
    answers?: Answers,
    questions?: readonly Question[],
): string[] {
    if (
        !answers ||
        question.reviewRequired ||
        answer === undefined ||
        answer === 'UNKNOWN' ||
        !validAnswer(question, answer)
    )
        return [];
    return [
        ...new Set(
            (question.answerDependencies ?? [])
                .filter(
                    (dependency) =>
                        dependencyState(dependency, answers, questions) ===
                            'MATCH' &&
                        matches(answer, dependency.conflictingValues),
                )
                .map((dependency) => dependency.message),
        ),
    ];
}

/** A confirmed requirement cannot be removed by a conflicting answer elsewhere. */
export function dependentDocumentNecessity(
    base: DocumentItem['necessity'],
    documentId: string,
    questions: readonly Question[],
    answers: Answers,
): DocumentItem['necessity'] {
    const dependencies = questions.flatMap((question) =>
        (question.answerDependencies ?? [])
            .filter((dependency) =>
                dependency.requiredDocumentIds.includes(documentId),
            )
            .map((dependency) => ({ question, dependency })),
    );
    if (
        dependencies.some(
            ({ question, dependency }) =>
                question.reviewRequired ||
                questions.find((q) => q.id === dependency.questionId)
                    ?.reviewRequired,
        )
    )
        return 'UNKNOWN';
    if (base === 'REQUIRED') return base;
    const states = dependencies.map(({ dependency }) =>
        dependencyState(dependency, answers, questions),
    );
    if (states.includes('MATCH')) return 'CONDITIONAL';
    if (base === 'NOT_APPLICABLE' && states.includes('UNKNOWN'))
        return 'UNKNOWN';
    return base;
}
