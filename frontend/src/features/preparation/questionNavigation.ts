import { needsFollowUp, validAnswer } from '../housing/questions';
import type { Preparation } from '../housing/types';

export function getQuestionStep(
    { questions, answers }: Pick<Preparation, 'questions' | 'answers'>,
    requestedId?: string | null,
    dependencyQuestions = questions,
) {
    const requested = questions.findIndex(
        (question) => question.id === requestedId,
    );
    if (requested >= 0) return requested;

    const unanswered = questions.findIndex(
        (question) => !validAnswer(question, answers[question.id]),
    );
    if (unanswered >= 0) return unanswered;

    return Math.max(
        0,
        questions.findIndex((question) =>
            needsFollowUp(
                question,
                answers[question.id],
                answers,
                dependencyQuestions,
            ),
        ),
    );
}
