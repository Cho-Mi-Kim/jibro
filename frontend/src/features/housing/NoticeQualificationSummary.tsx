import { Icon } from '../../shared/ui/Icon';
import { qualificationSummary } from './qualificationSummary';
import { QualificationSummaryDetails } from './QualificationSummaryDetails';
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
    const summary = qualificationSummary(notice);
    return (
        <NoticeSummaryCard
            title="자격과 순위를 구분해요"
            rows={summary.rows}
            explanationLabel="자격과 순위 세부 기준 보기"
        >
            <QualificationSummaryDetails summary={summary} />
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
