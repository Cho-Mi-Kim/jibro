import { Link } from 'react-router';
import type { Notice, Preparation } from '../housing/types';
import { currentPreparationSchedules } from '../housing/schedules';
import {
    daysLeft,
    displayDate,
    nextSchedule,
} from '../../shared/ui/housingHelpers';
import { Icon } from '../../shared/ui/Icon';
import { CalendarExport } from './CalendarExport';
import styles from './ScheduleNextCard.module.css';

export function ScheduleNextCard({
    notice,
    preparation,
}: {
    notice?: Notice;
    preparation: Preparation;
}) {
    const next = nextSchedule(
        currentPreparationSchedules(preparation, notice),
        notice?.endDate,
    );
    const titleParts = next?.title.match(/^(.*?)\s*\(([^()]*)\)\s*$/);
    return (
        <aside className={styles.card} aria-label="다음 중요한 일정">
            <h3>다음 중요한 일정</h3>
            {next ? (
                <>
                    <div className={styles.summary}>
                        <div>
                            <h4>{titleParts?.[1] ?? next.title}</h4>
                        </div>
                        <strong className={styles.countdown}>
                            {daysLeft(next.date)}
                        </strong>
                    </div>
                    <p className={styles.note}>
                        <time dateTime={next.date}>
                            {displayDate(next.date)}
                        </time>
                        {titleParts?.[2] && <span>{titleParts[2]}</span>}
                    </p>
                </>
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
        </aside>
    );
}
