import type { Question, ReviewIssue } from '../housing/types';
import { conditionOverview } from '../housing/conditionOverview';
import { ReviewIssues } from '../housing/ReviewIssues';
import { questionReviewIssues } from '../housing/reviewIssueSelectors';
import styles from './Conditions.module.css';

export function QuestionCriteria({
    question,
    reviewIssues,
}: {
    question: Question;
    reviewIssues?: ReviewIssue[];
}) {
    const issues = questionReviewIssues(reviewIssues, question.id);
    const { needsReview, overview, hasDetails } = conditionOverview(
        question,
        issues,
    );
    if (!needsReview && !overview.length) return null;
    return (
        <div className={styles.criteria}>
            <ReviewIssues issues={issues} />
            {needsReview && !issues.length && (
                <p>공식 확인이 필요한 조건입니다.</p>
            )}
            {overview.map((line, index) => (
                <p key={index}>{line}</p>
            ))}
            {hasDetails && (
                <details>
                    <summary>전체 조건 보기</summary>
                    <p>{question.reason}</p>
                </details>
            )}
        </div>
    );
}
