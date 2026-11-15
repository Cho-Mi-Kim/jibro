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

export function ReviewIssues({ issues }: { issues: ReviewIssue[] }) {
    if (!issues.length) return null;
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
                    <p>{issue.detail}</p>
                    {issue.evidence.length > 0 && (
                        <details className={styles.sources}>
                            <summary>
                                확인할 공식 근거 <Icon name="down" />
                            </summary>
                            {issue.evidence.map((evidence, index) => (
                                <EvidenceLink
                                    key={issue.id + ':' + index}
                                    evidence={evidence}
                                    className={styles.evidence}
                                />
                            ))}
                        </details>
                    )}
                </section>
            ))}
        </div>
    );
}
