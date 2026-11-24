import { answerConflictMessages } from '../housing/answerDependencies';
import type { Answer, Question } from '../housing/types';
import { Icon } from '../../shared/ui/Icon';
import styles from './AnswerConflictNotice.module.css';

export function AnswerConflictNotice({
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
    const messages = answerConflictMessages(
        question,
        answer,
        answers,
        questions,
    );
    if (!messages.length) return null;
    return (
        <div className={styles.notice} role="status">
            <Icon name="warning" />
            <div>
                {messages.map((message) => (
                    <p key={message}>{message}</p>
                ))}
            </div>
        </div>
    );
}
