import type { Answer, Question, ReviewIssue } from '../housing/types';
import { useEffect } from 'react';
import { answerConflictMessages } from '../housing/answerDependencies';
import { useScrollToCard } from '../../shared/ui/useScrollToCard';
import { conditionOverview } from '../housing/conditionOverview';
import { applicantQuestion } from './applicantQuestion';
import { ConditionDetailText } from '../housing/ConditionDetailText';
import { ReviewIssues } from '../housing/ReviewIssues';
import { questionReviewIssues } from '../housing/reviewIssueSelectors';
import styles from './Conditions.module.css';
import { AnswerConflictNotice } from './AnswerConflictNotice';

export function QuestionPrompt({
    question,
    reviewIssues,
    answers,
    questions,
}: {
    question: Question;
    reviewIssues?: ReviewIssue[];
    answers?: Readonly<Record<string, Answer>>;
    questions?: readonly Question[];
}) {
    const issues = questionReviewIssues(reviewIssues, question.id);
    const prompt = applicantQuestion(
        question,
        answers && questions ? { answers, questions } : undefined,
    );
    const { needsReview, preview, detail, structured } = conditionOverview(
        question,
        issues,
    );
    const inputPreview = prompt.preview ?? preview;
    const { cardRef, scrollToCard } = useScrollToCard<HTMLLegendElement>(
        '[data-question-page]',
    );
    const conflictKey = answerConflictMessages(
        question,
        answers?.[question.id],
        answers,
        questions,
    ).join('\n');
    useEffect(() => {
        if (conflictKey) scrollToCard();
    }, [conflictKey, scrollToCard]);
    return (
        <>
            <legend ref={cardRef} aria-live="polite">
                {prompt.title}
            </legend>
            <AnswerConflictNotice
                question={question}
                answer={answers?.[question.id]}
                answers={answers}
                questions={questions}
            />
            <div className={styles.criteria}>
                <p>{prompt.help}</p>
                {prompt.needsCriteria && inputPreview && <p>{inputPreview}</p>}
                <ReviewIssues
                    issues={issues}
                    flagged={needsReview ? '조건' : undefined}
                />
                {(structured || detail || preview) && (
                    <details
                        data-card-disclosure
                        onToggle={(event) => scrollToCard(event.currentTarget)}
                    >
                        <summary>
                            {prompt.preview
                                ? '전체 기준과 예외 보기'
                                : '답변을 고를 때 참고해요'}
                        </summary>
                        <ConditionDetailText
                            text={detail || preview}
                            structured={structured}
                        />
                    </details>
                )}
            </div>
        </>
    );
}
