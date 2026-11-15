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
} from '../housing/schedules';
import { Deadline } from '../housing/NoticeFacts';
import { Status } from '../../shared/ui/Status';
import { Icon } from '../../shared/ui/Icon';
import { preparationDeadlineLabel } from '../housing/noticeDisplay';
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
    return (
        <div className={styles.identity}>
            <div className={styles.status}>
                <Status
                    tone={applicationInterrupted(notice) ? 'gray' : 'green'}
                >
                    {lifecycleLabel(notice) ||
                        (p.progress === 100
                            ? '준비 기록 완료'
                            : '신청 준비 중')}
                </Status>
            </div>
            <div
                className={styles.title}
                data-title-disclosure={!!titleContent}
            >
                {titleContent}
                <Link
                    className={styles.identityLink}
                    to={'/notices/' + p.noticeId}
                    state={entryState}
                >
                    {!titleContent && <h2>{p.title}</h2>}
                    <span className={styles.noticeLink}>
                        <span>
                            {notice
                                ? notice.agency + ' · ' + notice.region
                                : '공고 정보 확인 중'}
                            {' · 안내 v' + p.version}
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
                    date={notice ? applicationDate(notice, 'end') : undefined}
                    dateLabel={preparationDeadlineLabel(
                        notice ? applicationDate(notice, 'end') : undefined,
                        notice?.applicationTiming
                            ? []
                            : currentPreparationSchedules(p, notice),
                    )}
                />
            </div>
        </div>
    );
}
