import { Icon } from '../../shared/ui/Icon';
import { EvidenceLink } from './EvidenceLink';
import { qualificationSummaryRows } from './qualificationSummaryRows';
import { NoticeSummaryCard } from './NoticeSummaryCard';
import type { Notice } from './types';
import styles from './NoticeDetail.module.css';

export function NoticeQualificationSummary({
    notice,
    onConditions,
}: {
    notice: Notice;
    onConditions: () => void;
}) {
    const overview = notice.qualificationSummary;
    return (
        <NoticeSummaryCard
            title="자격과 순위를 구분해요"
            rows={qualificationSummaryRows(overview)}
            explanationLabel="자격과 순위 세부 기준 보기"
        >
            {overview?.detail && <p>{overview.detail}</p>}
            {overview?.evidence && (
                <EvidenceLink evidence={overview.evidence} />
            )}
            <button
                type="button"
                className={styles.cardAction}
                onClick={onConditions}
            >
                <Icon name="file" />
                조건과 공식 근거 확인
            </button>
        </NoticeSummaryCard>
    );
}
