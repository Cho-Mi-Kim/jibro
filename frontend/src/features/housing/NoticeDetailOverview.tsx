import { applicationInterrupted, lifecycleLabel } from './noticeLifecycle';
import { NoticeLifecycleNotice } from './NoticeLifecycleNotice';
import { effectiveNoticeStatus } from './noticeLifecycle';
import { Icon } from '../../shared/ui/Icon';
import { cx } from '../../shared/styles/classes';
import { GuideBadge } from './NoticeFacts';
import { Status } from '../../shared/ui/Status';
import { noticeDaysLeft, displayDate } from './noticeDisplay';
import { noticeDetailDates, shortNoticeDate } from './noticeDetailDates';
import { NoticeGuidanceStatus } from './NoticeGuidanceStatus';
import { PartialGuidanceNotice } from './ReviewIssues';
import { statusLabels } from './noticeLabels';
import type { Notice } from './types';
import styles from './NoticeDetail.module.css';
import { IndividualAnnouncement } from './IndividualAnnouncement';
import { AgencyMark } from './AgencyMark';

function deadlineTime(time: string) {
    const parts = time.match(/^(\d{1,2}):(\d{2})$/);
    if (!parts) return time;
    return `${Number(parts[1])}시${parts[2] === '00' ? '' : ` ${Number(parts[2])}분`}`;
}

export function NoticeDetailOverview({ notice }: { notice: Notice }) {
    const dates = noticeDetailDates(notice);
    const deadline = noticeDaysLeft(notice);
    return (
        <section
            className={`${styles.overview} ${cx('explanation-card')}`}
            aria-labelledby="notice-title"
        >
            <div className={styles.badges}>
                <Status
                    tone={
                        effectiveNoticeStatus(notice) === 'OPEN'
                            ? 'green'
                            : 'gray'
                    }
                >
                    <Icon name="file" />
                    {statusLabels[effectiveNoticeStatus(notice)]}
                </Status>
                <GuideBadge
                    direction="right"
                    state={notice.guideState}
                    partial={!!notice.reviewIssues?.length}
                />
            </div>
            <h2 id="notice-title" className={styles.noticeTitle}>
                {notice.title}
            </h2>
            <p className={styles.publishedDate}>
                {notice.agency && (
                    <>
                        <AgencyMark agency={notice.agency} /> ·{' '}
                    </>
                )}
                공고일 {displayDate(notice.publishedDate)}
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
            <dl
                className={styles.dates}
                aria-labelledby="notice-schedule-title"
            >
                <div>
                    <dt>접수 기간</dt>
                    <dd>
                        <span
                            className={styles.period}
                            aria-label={dates.period.label}
                        >
                            {dates.period.values.map((value, index) => (
                                <span key={index}>{value}</span>
                            ))}
                        </span>
                        {dates.period.note && (
                            <small>{dates.period.note}</small>
                        )}
                        {dates.end.time && (
                            <small>{deadlineTime(dates.end.time)}까지</small>
                        )}
                    </dd>
                </div>
                <div>
                    <dt>결과 발표</dt>
                    <dd
                        aria-label={
                            dates.announcementExplanation
                                ? `${dates.announcement.date} 개별 안내`.trim()
                                : dates.announcement.date || '날짜 정보 없음'
                        }
                    >
                        {shortNoticeDate(dates.announcement.date)}
                        {dates.announcement.time && (
                            <small>{dates.announcement.time}</small>
                        )}
                        {dates.announcementExplanation && (
                            <IndividualAnnouncement
                                event={dates.announcementExplanation}
                            />
                        )}
                    </dd>
                </div>
                <div>
                    <dt>서류 마감</dt>
                    <dd aria-label={dates.documents.date || '날짜 정보 없음'}>
                        {shortNoticeDate(dates.documents.date)}
                        {dates.documents.time && (
                            <small>{dates.documents.time}까지</small>
                        )}
                    </dd>
                </div>
            </dl>
            <dl className={styles.housingFacts}>
                {(
                    [
                        ['지역', 'pin', notice.region],
                        ['주택 유형', 'building', notice.type],
                    ] as const
                ).map(([label, icon, value]) => (
                    <div key={label}>
                        <dt>
                            <Icon name={icon} />
                            {label}
                        </dt>
                        <dd>{value || '확인 필요'}</dd>
                    </div>
                ))}
            </dl>
            <NoticeGuidanceStatus notice={notice} />
        </section>
    );
}
