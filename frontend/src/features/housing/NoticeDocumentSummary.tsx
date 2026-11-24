import { documentReviewCountNote } from './reviewIssueSelectors';
import { useState, type ReactNode } from 'react';
import { CoreDocuments } from './CoreDocuments';
import { Icon } from '../../shared/ui/Icon';
import { EvidenceLink } from './EvidenceLink';
import { NoticeSummaryCard } from './NoticeSummaryCard';
import { ConditionDetailText } from './ConditionDetailText';
import { gyeongjuSummaryCondition } from './gyeongjuConditionDetail';
import { samryeSubmissionSummary } from './samryeNoticeSummary';
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
    const [expanded, setExpanded] = useState(false);
    const rows: { label: string; text: ReactNode }[] = [];
    if (overview?.common.items.length) {
        rows.push({
            label: '공통 서류',
            text: (
                <CoreDocuments
                    kind="common"
                    notice={notice}
                    factsExpanded={expanded}
                />
            ),
        });
    }
    if (overview?.conditional.items.length) {
        rows.push({
            label: '해당 시 추가',
            text: (
                <CoreDocuments
                    kind="conditional"
                    notice={notice}
                    factsExpanded={expanded}
                />
            ),
        });
    }
    const reviewNote = documentReviewCountNote(overview?.reviewRequiredCount);
    const submission =
        !reviewNote &&
        (gyeongjuSummaryCondition(notice, 'submission') ??
            samryeSubmissionSummary(notice));
    const sourceItems = overview
        ? [...overview.common.items, ...overview.conditional.items]
        : [];
    const submissionPage = submission
        ? submission.sections[0].evidence[0].url
        : null;
    const hasItems = rows.length > 0;
    if (!hasItems && reviewNote) {
        rows.push({
            label: '추가 확인',
            text: reviewNote,
        });
    }
    if (!rows.length) {
        rows.push({
            label: '제출 기준',
            text: '필요한 서류는 제출서류 탭과 공식 공고에서 확인해요.',
        });
    }
    return (
        <NoticeSummaryCard
            title="필요한 서류 확인"
            rows={rows}
            explanationLabel="서류 요약과 제출 기준 보기"
            onToggle={setExpanded}
        >
            {hasItems && reviewNote && <p>{reviewNote}</p>}
            {submission && hasItems ? (
                <ConditionDetailText
                    text={submission.text}
                    structured={submission}
                />
            ) : (
                <p>
                    핵심 서류를 먼저 보여드려요. 해당자 서류는 내 조건에 따라
                    달라져요. 전체 목록과 발급·제출 기준을 확인해요.
                </p>
            )}
            {sourceItems
                .filter(
                    (item) =>
                        !submissionPage ||
                        (item.evidence.url !== submissionPage &&
                            item.evidence.url !==
                                submissionPage.split('&sha256=')[0]),
                )
                .map((item) => (
                    <EvidenceLink
                        key={item.id}
                        evidence={item.evidence}
                        label={item.title}
                    />
                ))}
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
