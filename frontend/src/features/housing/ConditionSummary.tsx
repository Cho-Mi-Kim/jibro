import type { ReactNode } from 'react';
import { Icon } from '../../shared/ui/Icon';
import { EvidenceLink } from './EvidenceLink';
import { ConditionDetailText } from './ConditionDetailText';
import { ReviewIssues } from './ReviewIssues';
import { answerOptions, inputDescription } from './questions';
import {
    conditionHeading,
    conditionOverview,
    showQuestionEvidence,
} from './conditionOverview';
import type { Question, ReviewIssue } from './types';
import styles from './ConditionSummary.module.css';

export function ConditionSummary({
    question,
    issues,
    heading: Heading = 'h2',
    children,
    historicalChoices,
    showChoices = false,
}: {
    question: Question;
    issues: ReviewIssue[];
    heading?: 'h2' | 'h3' | 'h4';
    children?: ReactNode;
    historicalChoices?: ReactNode;
    showChoices?: boolean;
}) {
    const { needsReview, overview, preview, detail, structured } =
        conditionOverview(question, issues);
    const heading = conditionHeading(question);
    const shortened = overview.length > 1 || preview.length > 30;
    const expandPreview = !!structured || (!!detail && detail !== preview);
    const historical = historicalChoices != null;
    const disclosure = historical
        ? '당시 조건·선택지와 근거'
        : showChoices
          ? '조건·선택지·공식 근거'
          : shortened && /예외|다만|제외|완화/.test(question.reason ?? '')
            ? '전체 조건·예외·공식 근거'
            : expandPreview
              ? '전체 조건·공식 근거'
              : '공식 근거';
    return (
        <div className={styles.card}>
            <Heading className={styles.title}>{heading}</Heading>
            <ReviewIssues
                issues={issues}
                flagged={needsReview ? '조건' : undefined}
                historical={historical}
            />
            {children}
            <div
                className={styles.body}
                data-replaces-preview={expandPreview}
                data-reviewed-preview={!!structured?.preview}
            >
                {preview && <p className={styles.preview}>{preview}</p>}
                <details data-card-disclosure className={styles.details}>
                    <summary aria-label={`${heading} ${disclosure} 보기`}>
                        <Icon name="down" />
                    </summary>
                    {expandPreview && (
                        <ConditionDetailText
                            text={detail}
                            structured={structured}
                        />
                    )}
                    {historical && heading !== question.title && (
                        <p>{question.title}</p>
                    )}
                    {showChoices && (
                        <>
                            <p>{inputDescription(question)}</p>
                            <p>
                                {answerOptions(question)
                                    .map((option) => option.label)
                                    .join(' / ')}
                            </p>
                        </>
                    )}
                    {historicalChoices}
                    {showQuestionEvidence(question, structured ?? null) && (
                        <EvidenceLink evidence={question.evidence} />
                    )}
                </details>
            </div>
        </div>
    );
}
