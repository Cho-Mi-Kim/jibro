import { Icon } from '../../shared/ui/Icon';
import { cx } from '../../shared/styles/classes';
import { GuideBadge, Status } from '../../shared/ui/HousingUI';
import { daysLeft, displayDate } from '../../shared/ui/housingHelpers';
import { noticeDetailDates } from './noticeDetailDates';
import { NoticeGuidanceStatus } from './NoticeGuidanceStatus';
import { statusLabels } from './noticeLabels';
import type { Notice } from './types';
import styles from './NoticeDetail.module.css';

function shortDate(date: string) {
    const parts = date.match(/^\d{4}\.(\d{2})\.(\d{2})$/);
    return parts ? `${Number(parts[1])}/${Number(parts[2])}` : date;
}

function deadlineTime(time: string) {
    const parts = time.match(/^(\d{1,2}):(\d{2})$/);
    if (!parts) return time;
    return `${Number(parts[1])}시${parts[2] === '00' ? '' : ` ${Number(parts[2])}분`}`;
}

export function NoticeDetailOverview({ notice }: { notice: Notice }) {
    const dates = noticeDetailDates(notice);
    const deadline = daysLeft(notice.endDate);
    const periodStart = shortDate(dates.start.date);
    const periodEnd = shortDate(dates.end.date);
    return (
        <section
            className={`${styles.overview} ${cx('explanation-card')}`}
            aria-labelledby="notice-title"
        >
            <div className={styles.badges}>
                <Status tone={notice.status === 'OPEN' ? 'green' : 'gray'}>
                    <Icon name="file" />
                    {statusLabels[notice.status]}
                </Status>
                <GuideBadge state={notice.guideState} />
            </div>
            <h2 id="notice-title" className={styles.noticeTitle}>
                {notice.title}
            </h2>
            <p className={styles.publishedDate}>
                {notice.agency && notice.agency + ' · '}
                공고일 {displayDate(notice.publishedDate)}
            </p>
            <div className={styles.scheduleHeader}>
                <h3 id="notice-schedule-title">
                    <Icon name="calendar" />
                    신청 일정
                </h3>
                <span className={styles.deadline}>
                    신청 마감{deadline !== '일정 경과' && ` ${deadline}`}
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
                            aria-label={`${dates.start.date}부터 ${dates.end.date}까지`}
                        >
                            <span>{periodStart}</span>
                            <span>–</span>
                            <span>{periodEnd}</span>
                        </span>
                        {dates.end.time && (
                            <small>{deadlineTime(dates.end.time)}까지</small>
                        )}
                    </dd>
                </div>
                <div>
                    <dt>결과 발표</dt>
                    <dd
                        aria-label={dates.announcement.date || '날짜 정보 없음'}
                    >
                        {shortDate(dates.announcement.date)}
                        {dates.announcement.time && (
                            <small>{dates.announcement.time}</small>
                        )}
                    </dd>
                </div>
                <div>
                    <dt>서류 마감</dt>
                    <dd aria-label={dates.documents.date || '날짜 정보 없음'}>
                        {shortDate(dates.documents.date)}
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
