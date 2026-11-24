import { areaReviewIssues } from './reviewIssueSelectors';
import { Icon } from '../../shared/ui/Icon';
import { EvidenceLink } from './EvidenceLink';
import type { ReviewIssue } from './types';
import styles from './ReviewIssues.module.css';

function issueHeading(issue: ReviewIssue) {
    return issue.title.includes('확인 필요')
        ? issue.title
        : '확인 필요 · ' + issue.title;
}

export function PartialGuidanceNotice({ issues }: { issues?: ReviewIssue[] }) {
    if (!issues?.length) return null;
    return (
        <p className={styles.notice}>
            <Icon name="info" />
            <span>
                일부 항목은 공식 확인이 필요해요. 확인된 조건과 서류는 계속
                준비할 수 있어요.
            </span>
        </p>
    );
}

export function AreaReviewIssues({
    issues,
    area,
}: {
    issues?: ReviewIssue[];
    area: ReviewIssue['area'];
}) {
    return <ReviewIssues issues={areaReviewIssues(issues, area)} />;
}

export function ReviewIssues({
    issues,
    flagged,
    historical = false,
}: {
    issues: ReviewIssue[];
    flagged?: '조건' | '서류';
    historical?: boolean;
}) {
    if (!issues.length) {
        if (!flagged) return null;
        return (
            <p className={styles.notice}>
                <Icon name="info" />
                <span>
                    {historical
                        ? `저장 당시 공식 확인이 필요했던 ${flagged}입니다.`
                        : `공식 확인이 필요한 ${flagged}입니다.`}
                </span>
            </p>
        );
    }
    return (
        <div className={styles.list}>
            {issues.map((issue) => (
                <section
                    key={issue.id}
                    className={styles.issue}
                    aria-label={issueHeading(issue)}
                >
                    <strong>
                        <Icon name="info" />
                        {issueHeading(issue)}
                    </strong>
                    {(issue.detail || issue.evidence.length > 0) && (
                        <details className={styles.sources}>
                            <summary>
                                {issue.evidence.length
                                    ? issue.detail
                                        ? '확인 사유·공식 근거'
                                        : '확인할 공식 근거'
                                    : '확인 사유'}
                                <Icon name="down" />
                            </summary>
                            {issue.detail && <p>{issue.detail}</p>}
                            {issue.evidence.map((evidence, index) => (
                                <EvidenceLink
                                    key={issue.id + ':' + index}
                                    evidence={evidence}
                                />
                            ))}
                        </details>
                    )}
                </section>
            ))}
        </div>
    );
}
