import type { Answer, Question } from '../housing/types';
import { applicantQuestion } from './applicantQuestion';
import styles from './QuestionSteps.module.css';
export function QuestionSteps({
    questions,
    step,
    answers,
    dependencyQuestions = questions,
}: {
    questions: Question[];
    step: number;
    answers?: Readonly<Record<string, Answer>>;
    dependencyQuestions?: readonly Question[];
}) {
    return (
        <aside className={styles.panel} aria-label="질문 진행 단계">
            <div className={styles.heading}>
                <h2>내 상황 확인</h2>
                <p
                    className={styles.count}
                    aria-label={`전체 ${questions.length}개 중 ${step + 1}번째 질문`}
                >
                    <strong>{step + 1}</strong>
                    <span>/ {questions.length}</span>
                </p>
            </div>
            <ol>
                {questions.map((q, i) => {
                    const title = applicantQuestion(
                        q,
                        answers
                            ? { answers, questions: dependencyQuestions }
                            : undefined,
                    ).title;
                    return (
                        <li
                            key={q.id}
                            aria-current={i === step ? 'step' : undefined}
                            data-past={i < step}
                        >
                            <span aria-hidden="true">{i + 1}</span>
                            <small title={title}>{title}</small>
                        </li>
                    );
                })}
            </ol>
        </aside>
    );
}
