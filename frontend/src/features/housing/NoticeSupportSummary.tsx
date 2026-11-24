import { EvidenceLink } from './EvidenceLink';
import { NoticeSummaryCard } from './NoticeSummaryCard';
import type { Notice } from './types';
import { noticeSupportSummaryRows } from './supportSummaryRows';
import { samryeSupportSummary } from './samryeNoticeSummary';
import { gyeongjuSummaryCondition } from './gyeongjuConditionDetail';
import { ConditionDetailText } from './ConditionDetailText';

export function NoticeSupportSummary({ notice }: { notice: Notice }) {
    const method = gyeongjuSummaryCondition(notice, 'method');
    const support = samryeSupportSummary(notice)?.detail;
    return (
        <NoticeSummaryCard
            title={
                method
                    ? '어떤 지원이고, 청약과 계약은 언제인가요?'
                    : '어떤 지원인가요?'
            }
            rows={noticeSupportSummaryRows(notice)}
            explanationLabel={
                method
                    ? '청약·계약 일정과 공식 근거 보기'
                    : '지원 내용 공식 근거 보기'
            }
        >
            {support && (
                <ConditionDetailText text={support.text} structured={support} />
            )}
            {method && (
                <ConditionDetailText text={method.text} structured={method} />
            )}
            <EvidenceLink evidence={notice.evidence} />
        </NoticeSummaryCard>
    );
}
