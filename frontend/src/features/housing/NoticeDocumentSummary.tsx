import type { ReactNode } from 'react';
import { CoreDocuments } from './CoreDocuments';
import { Icon } from '../../shared/ui/Icon';
import { EvidenceLink } from './EvidenceLink';
import { NoticeSummaryCard } from './NoticeSummaryCard';
import type { Notice } from './types';
import styles from './NoticeDetail.module.css';

export function NoticeDocumentSummary({
    notice,
    onDocuments,
}: {
    notice: Notice;
    onDocuments: () => void;
}) {
    const overview = notice.documentSummary;
    const rows: { label: string; text: ReactNode }[] = [];
    if (overview?.common.items.length) {
        rows.push({
            label: '공통 서류',
            text: <CoreDocuments kind="common" notice={notice} />,
        });
    }
    if (overview?.conditional.items.length) {
        rows.push({
            label: '해당 시 추가',
            text: <CoreDocuments kind="conditional" notice={notice} />,
        });
    }
    if (overview?.reviewRequiredCount) {
        rows.push({
            label: '추가 확인',
            text: `${overview.reviewRequiredCount}개 서류는 제출 기준 확인이 필요합니다.`,
        });
    }
    if (!rows.length) {
        rows.push({
            label: '제출 기준',
            text: '필요한 서류는 제출서류 탭과 공식 공고에서 확인해 주세요.',
        });
    }
    return (
        <NoticeSummaryCard
            title="필요한 서류 확인"
            rows={rows}
            explanationLabel="서류 요약과 제출 기준 보기"
        >
            <p>
                핵심 서류를 먼저 보여드려요. 해당자 서류는 내 조건에 따라
                달라집니다. 전체 목록과 발급·제출 기준을 확인해 주세요.
            </p>
            {overview &&
                [...overview.common.items, ...overview.conditional.items].map(
                    (item) => (
                        <EvidenceLink
                            key={item.id}
                            className={styles.compactSummaryEvidence}
                            evidence={{ ...item.evidence, label: item.title }}
                        />
                    ),
                )}
            <button
                type="button"
                className={styles.cardAction}
                onClick={onDocuments}
            >
                <Icon name="file" />
                제출서류 확인
            </button>
        </NoticeSummaryCard>
    );
}
