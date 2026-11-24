import type { Evidence } from './types';
import { evidenceHref } from '../../shared/ui/helpers';
import { Icon } from '../../shared/ui/Icon';
import styles from './EvidenceLink.module.css';

export function EvidenceLink({
    evidence,
    label = evidence.label,
}: {
    evidence: Evidence;
    label?: string;
}) {
    const href = evidenceHref(evidence.url, evidence.label);
    return (
        <div className={styles.evidence}>
            <span className={styles.source}>
                {href ? (
                    <a
                        className={styles.link}
                        href={href}
                        target="_blank"
                        rel="noreferrer"
                    >
                        {evidence.url?.includes('fileid=69044666')
                            ? label.replace('신청 관련 14번', '신청 관련 15번')
                            : label}{' '}
                        <Icon name="external" />
                    </a>
                ) : (
                    label
                )}
            </span>
            <span className={styles.checkedAt}>확인 {evidence.checkedAt}</span>
            {!href && (
                <span className={styles.unavailable}>원문 경로 확인 필요</span>
            )}
        </div>
    );
}
