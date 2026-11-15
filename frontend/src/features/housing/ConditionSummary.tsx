import type { ReactNode } from 'react';
import { Icon } from '../../shared/ui/Icon';
import { EvidenceLink } from './EvidenceLink';
import { ReviewIssues } from './ReviewIssues';
import { answerOptions, inputDescription } from './questions';
import { conditionOverview } from './conditionOverview';
import type { Question, ReviewIssue } from './types';
import styles from './NoticeDetail.module.css';

function conditionHeading(title: string) {
    return (
        title
            .replace(/[을를] 확인(?:했나요\?|해 주세요\.)$/, '')
            .replace(/ 제출 대상인가요\?$/, ' 제출 대상') || title
    );
}

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
    const { needsReview, overview, hasDetails } = conditionOverview(
        question,
        issues,
    );
    const heading = conditionHeading(question.title);
    const historical = historicalChoices != null;
    const disclosure = historical
        ? '당시 조건·선택지와 근거'
        : showChoices
          ? '조건·선택지·공식 근거'
          : hasDetails
            ? '전체 조건·공식 근거'
            : '공식 근거';
    return (
        <>
            <ReviewIssues issues={issues} />
            <div className={styles.conditionText}>
                <Heading>{heading}</Heading>
                {children}
                {needsReview && !issues.length && (
                    <p>
                        {historical
                            ? '저장 당시 공식 확인이 필요했던 조건입니다.'
                            : '공식 확인이 필요한 조건입니다.'}
                    </p>
                )}
                {overview.map((sentence, index) => (
                    <p key={index}>{sentence}</p>
                ))}
            </div>
            <details className={styles.conditionDetails}>
                <summary aria-label={`${question.title} ${disclosure} 보기`}>
                    {disclosure} 보기 <Icon name="down" />
                </summary>
                {historical && heading !== question.title && (
                    <p>{question.title}</p>
                )}
                {hasDetails && <p>{question.reason}</p>}
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
                <EvidenceLink
                    className={styles.evidence}
                    evidence={question.evidence}
                    checkedAtOnNewLine={historical || showChoices}
                />
            </details>
        </>
    );
}
