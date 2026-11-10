import type { Question } from '../housing/types';
import styles from './QuestionSteps.module.css';
export function QuestionSteps({
    questions,
    step,
}: {
    questions: Question[];
    step: number;
}) {
    return (
        <aside className={styles.panel} aria-label="질문 진행 단계">
            <div className={styles.heading}>
                <h2>질문 단계</h2>
                <p
                    className={styles.count}
                    aria-label={`전체 ${questions.length}개 중 ${step + 1}번째 질문`}
                >
                    <strong>{step + 1}</strong>
                    <span>/ {questions.length}</span>
                </p>
            </div>
            <ol>
                {questions.map((q, i) => (
                    <li
                        key={q.id}
                        aria-current={i === step ? 'step' : undefined}
                        data-past={i < step}
                    >
                        <span aria-hidden="true">{i + 1}</span>
                        <small title={q.title}>{q.title}</small>
                    </li>
                ))}
            </ol>
        </aside>
    );
}
