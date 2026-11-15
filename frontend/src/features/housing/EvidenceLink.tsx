import type { Evidence } from './types';
import { bindClasses } from '../../shared/styles/classes';
import { evidenceHref } from '../../shared/ui/helpers';
import housingClasses from './NoticeFacts.module.css';

const cx = bindClasses(housingClasses);

export function EvidenceLink({
    evidence,
    className = '',
    checkedAtOnNewLine = false,
}: {
    evidence: Evidence;
    className?: string;
    checkedAtOnNewLine?: boolean;
}) {
    const href = evidenceHref(evidence.url, evidence.label);
    return (
        <div className={`${cx('evidence')} ${className}`}>
            <span>
                {href ? (
                    <a href={href} target="_blank" rel="noreferrer">
                        {evidence.url?.includes('fileid=69044666')
                            ? evidence.label.replace(
                                  '신청 관련 14번',
                                  '신청 관련 15번',
                              )
                            : evidence.label}{' '}
                        ↗
                    </a>
                ) : (
                    evidence.label
                )}
                {checkedAtOnNewLine ? <br /> : ' · '}
                {'확인 '}
                {evidence.checkedAt}
            </span>
            {!href && <span>원문 경로 확인 필요</span>}
        </div>
    );
}
