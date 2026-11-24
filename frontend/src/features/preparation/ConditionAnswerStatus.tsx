import { Icon } from '../../shared/ui/Icon';
import { Status } from '../../shared/ui/Status';
import { conditionAnswerState } from '../housing/questions';
import type { Answer, Question } from '../housing/types';
import { conditionAnswerPresentation } from './conditionAnswerPresentation';
import { AnswerConflictNotice } from './AnswerConflictNotice';

export function ConditionAnswerStatus({
    question,
    answer,
    answers,
    questions,
}: {
    question: Question;
    answer?: Answer;
    answers?: Readonly<Record<string, Answer>>;
    questions?: readonly Question[];
}) {
    const { label, icon, tone } =
        conditionAnswerPresentation[
            conditionAnswerState(question, answer, answers, questions)
        ];
    return (
        <>
            <Status tone={tone}>
                <Icon name={icon} />
                {label}
            </Status>
            <AnswerConflictNotice
                question={question}
                answer={answer}
                answers={answers}
                questions={questions}
            />
        </>
    );
}
