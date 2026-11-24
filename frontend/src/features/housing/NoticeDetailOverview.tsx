import { useState, type ReactNode } from 'react';
import { ApplicationBadge } from './NoticeStatusBadges';
import { NoticeHousingRows } from './NoticeHousingRows';
import { applicationInterrupted, lifecycleLabel } from './noticeLifecycle';
import { NoticeLifecycleNotice } from './NoticeLifecycleNotice';
import { Icon } from '../../shared/ui/Icon';
import { cx } from '../../shared/styles/classes';
import { noticeDaysLeft, displayDate } from './noticeDisplay';
import { ScheduleSummary } from './ScheduleCard';
import { NoticeGuidanceStatus } from './NoticeGuidanceStatus';
import { handleDisclosureCardClick } from '../../shared/ui/handleDisclosureCardClick';
import { PartialGuidanceNotice } from './ReviewIssues';
import type { Notice } from './types';
import styles from './NoticeDetail.module.css';
import { AgencyMark } from './AgencyMark';
import { NoticeTitle } from './NoticeTitle';

export function NoticeDetailOverview({
    notice,
    applicationRecord,
}: {
    notice: Notice;
    applicationRecord?: ReactNode;
}) {
    const deadline = noticeDaysLeft(notice);
    const [expanded, setExpanded] = useState(false);
    return (
        <section
            className={`${styles.overview} ${cx('explanation-card')}`}
            aria-labelledby="notice-title"
            onClick={handleDisclosureCardClick}
        >
            <div className={styles.badges}>
                <ApplicationBadge notice={notice} />
                {applicationRecord}
            </div>
            <NoticeTitle
                id="notice-title"
                className={styles.noticeTitle}
                title={notice.title}
                expanded={expanded}
            />
            <p className={styles.publishedDate}>
                <AgencyMark agency={notice.agency} /> · 공고일{' '}
                {displayDate(notice.publishedDate)}
            </p>
            <NoticeLifecycleNotice notice={notice} />
            <PartialGuidanceNotice issues={notice.reviewIssues} />
            <div className={styles.scheduleHeader}>
                <h3 id="notice-schedule-title">
                    <Icon name="calendar" />
                    {applicationInterrupted(notice)
                        ? '기존 공고 일정'
                        : '신청 일정'}
                </h3>
                <span className={styles.deadline}>
                    {applicationInterrupted(notice)
                        ? lifecycleLabel(notice)
                        : `신청 마감${deadline !== '일정 경과' ? ` ${deadline}` : ''}`}
                </span>
            </div>
            <ScheduleSummary
                source={notice}
                layout="strip"
                labelId="notice-schedule-title"
            />
            <dl className={styles.housingFacts}>
                <NoticeHousingRows notice={notice} />
            </dl>
            <NoticeGuidanceStatus
                notice={notice}
                onExpandedChange={setExpanded}
            />
        </section>
    );
}
