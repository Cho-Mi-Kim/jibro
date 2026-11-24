import { useNoticeDetailReturn } from '../housing/NoticeListVisit';
import {
    applicationInterrupted,
    lifecycleLabel,
} from '../housing/noticeLifecycle';
import { Link } from 'react-router';
import type { ReactNode } from 'react';
import { useNoticeEntryState } from '../../shared/navigation/useNoticeEntryState';
import type { Notice, Preparation } from '../housing/types';
import {
    applicationDate,
    currentPreparationSchedules,
    currentPreparationScheduleSource,
} from '../housing/schedules';
import { ApplicationBadge } from '../housing/NoticeStatusBadges';
import { AgencyHousingSummary } from '../housing/AgencyMark';
import { Deadline } from '../housing/NoticeFacts';
import { Icon } from '../../shared/ui/Icon';
import {
    noticeListTitle,
    preparationDeadlineLabel,
} from '../housing/noticeDisplay';
import styles from './PreparationOverviewSummary.module.css';
export function PreparationIdentity({
    preparation: p,
    notice,
    titleContent,
}: {
    preparation: Preparation;
    notice?: Notice;
    titleContent?: ReactNode;
}) {
    const entryState = useNoticeEntryState();
    const detailReturn = useNoticeDetailReturn(p.noticeId);
    const source = currentPreparationScheduleSource(p, notice);
    return (
        <div className={styles.identity}>
            {notice && (
                <div className={styles.status}>
                    <ApplicationBadge notice={notice} />
                </div>
            )}
            <div
                className={styles.title}
                data-title-disclosure={!!titleContent}
            >
                {titleContent}
                <Link
                    className={styles.identityLink}
                    to={detailReturn?.to ?? '/notices/' + p.noticeId}
                    state={detailReturn?.state ?? entryState}
                >
                    {!titleContent && (
                        <h2 title={p.title}>{noticeListTitle(p.title)}</h2>
                    )}
                    <span className={styles.noticeLink}>
                        <span>
                            {notice ? (
                                <AgencyHousingSummary notice={notice} />
                            ) : (
                                '공고 정보 확인 중'
                            )}
                        </span>
                        <Icon name="arrow" />
                    </span>
                </Link>
            </div>
            <div className={styles.deadline}>
                <Deadline
                    label={
                        applicationInterrupted(notice) ? '공식 상태' : undefined
                    }
                    statusLabel={
                        lifecycleLabel(notice) ||
                        (notice?.status === 'CLOSED' ? '신청 마감' : undefined)
                    }
                    date={source ? applicationDate(source, 'end') : undefined}
                    dateLabel={preparationDeadlineLabel(
                        source ? applicationDate(source, 'end') : undefined,
                        source?.applicationTiming
                            ? []
                            : currentPreparationSchedules(p, source),
                    )}
                />
            </div>
        </div>
    );
}
