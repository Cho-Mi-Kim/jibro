import {
    currentPreparationSchedules,
    currentPreparationScheduleSource,
} from './schedules';
import { ScheduleCard, ScheduleSummary } from './ScheduleCard';
import { displayDate, daysLeft } from './noticeDisplay';
import type { Notice, Preparation } from './types';
import { bindClasses } from '../../shared/styles/classes';
import housingClasses from './NoticeFacts.module.css';

const cx = bindClasses(housingClasses);

export function NoticeDates({
    notice,
    preparation,
    compact = false,
    layout = 'columns',
}: {
    compact?: boolean;
    layout?: 'columns' | 'grid';
    notice?: Notice;
    preparation?: Preparation;
}) {
    const source = preparation
        ? currentPreparationScheduleSource(preparation, notice)
        : notice;
    const schedules =
        source?.schedules ??
        (preparation ? currentPreparationSchedules(preparation) : []);
    return (
        <ScheduleSummary
            layout={layout}
            className={layout === 'columns' ? cx('meta-dates') : undefined}
            source={source ?? { title: preparation?.title, schedules }}
            hideUnknown={compact}
            showPublished
        />
    );
}
export function Deadline({
    date,
    label = '신청 접수 마감',
    dateLabel,
    statusLabel,
    children,
}: {
    label?: string;
    dateLabel?: string;
    statusLabel?: string;
    date?: string | null;
    children?: React.ReactNode;
}) {
    return (
        <div
            className={cx('deadline')}
            data-today={!statusLabel && daysLeft(date) === 'D-Day'}
        >
            <ScheduleCard
                layout="deadline"
                heading="h3"
                event={{ title: label, date: date ?? null }}
                status={statusLabel ?? daysLeft(date)}
                timing={
                    statusLabel
                        ? '공식 안내 확인'
                        : (dateLabel ?? displayDate(date))
                }
            />
            {children}
        </div>
    );
}
