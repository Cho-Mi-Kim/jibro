import { activeSchedules } from '../housing/noticeLifecycle';
import { Link } from 'react-router';
import type { Notice, Preparation } from '../housing/types';
import { nextSchedule } from '../housing/noticeDisplay';
import { buildScheduleTimeline } from './scheduleTimelineModel';
import { Icon } from '../../shared/ui/Icon';
import { UpcomingScheduleCard } from '../housing/ScheduleCard';
import { CalendarExport } from './CalendarExport';
import styles from './ScheduleNextCard.module.css';
import { PreparationScrollSection } from './PreparationScrollSection';

export function ScheduleNextCard({
    notice,
    preparation,
}: {
    notice?: Notice;
    preparation: Preparation;
}) {
    const milestones = buildScheduleTimeline(notice, preparation)
        .rows.filter(
            (row) =>
                row.id !== 'published' &&
                !(
                    notice?.status === 'CLOSED' &&
                    (row.id === 'start' || row.id === 'end')
                ),
        )
        .flatMap((row) =>
            row.date ? [{ title: row.title, date: row.date }] : [],
        );
    const next = nextSchedule(activeSchedules(notice, milestones));
    return (
        <PreparationScrollSection
            as="aside"
            className={styles.card}
            aria-label="다음 중요한 일정"
        >
            <h3>다음 중요한 일정</h3>
            {next ? (
                <UpcomingScheduleCard event={next} />
            ) : (
                <p>예정된 공식 일정이 없습니다.</p>
            )}
            <div className={styles.actions}>
                <Link className={styles.action} to="/alerts">
                    <Icon name="bell" />
                    알림 설정
                </Link>
                {notice && (
                    <CalendarExport
                        notice={notice}
                        preparation={preparation}
                        inline
                        triggerClassName={styles.action}
                        showIcon
                    />
                )}
            </div>
        </PreparationScrollSection>
    );
}
