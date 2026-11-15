import { EvidenceLink } from './EvidenceLink';
import { NoticeSummaryCard } from './NoticeSummaryCard';
import type { Notice } from './types';
import { supportSummaryRows } from './supportSummaryRows';
import styles from './NoticeDetail.module.css';

/** The API supplies the reviewed overview; only its source is collapsible. */
export function NoticeSupportSummary({ notice }: { notice: Notice }) {
    return (
        <NoticeSummaryCard
            title="어떤 지원인가요?"
            rows={supportSummaryRows(notice.summary)}
            explanationLabel="지원 내용 공식 근거 보기"
        >
            <EvidenceLink
                evidence={notice.evidence}
                className={styles.compactSummaryEvidence}
            />
        </NoticeSummaryCard>
    );
}
