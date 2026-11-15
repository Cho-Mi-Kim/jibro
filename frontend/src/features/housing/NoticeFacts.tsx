import { useEffect, useRef, useState } from 'react';
import {
    currentPreparationSchedules,
    selectSchedule,
    individualAnnouncement,
} from './schedules';
import { IndividualAnnouncement } from './IndividualAnnouncement';
import { displayDate, daysLeft } from './noticeDisplay';
import type { Notice, Preparation } from './types';
import { guideLabels } from './guideLabels';
import { bindClasses } from '../../shared/styles/classes';
import housingClasses from './NoticeFacts.module.css';

const cx = bindClasses(housingClasses);

export function GuideBadge({
    state,
    partial = false,
    direction = 'left',
}: {
    state: Notice['guideState'];
    partial?: boolean;
    direction?: 'left' | 'right';
}) {
    const [expanded, setExpanded] = useState(false);
    const badgeRef = useRef<HTMLButtonElement>(null);
    useEffect(() => {
        if (!expanded) return;
        const closeOutside = (event: PointerEvent) => {
            if (
                event.target instanceof Node &&
                !badgeRef.current?.contains(event.target)
            ) {
                setExpanded(false);
            }
        };
        const closeOnEscape = (event: KeyboardEvent) => {
            if (event.key === 'Escape') setExpanded(false);
        };
        document.addEventListener('pointerdown', closeOutside);
        document.addEventListener('keydown', closeOnEscape);
        return () => {
            document.removeEventListener('pointerdown', closeOutside);
            document.removeEventListener('keydown', closeOnEscape);
        };
    }, [expanded]);
    const available = state === 'AVAILABLE';
    const label =
        available && partial
            ? '준비 안내 제공 · 일부 확인 필요'
            : guideLabels[state];
    const tone =
        state === 'UNSUPPORTED'
            ? 'red'
            : available
              ? partial
                  ? 'amber'
                  : 'green'
              : 'gray';
    const path =
        tone === 'green'
            ? 'm5 12 5 5L20 7'
            : tone === 'gray'
              ? 'M12 7v5l3 2M21 12a9 9 0 1 1-18 0 9 9 0 1 1 18 0'
              : 'M12 5v9m0 4v.1';
    return (
        <button
            ref={badgeRef}
            type="button"
            className={cx('guide-toggle')}
            data-tone={tone}
            data-direction={direction}
            aria-label={label}
            aria-expanded={expanded}
            onClick={(event) => {
                event.stopPropagation();
                setExpanded((value) => !value);
            }}
        >
            <span className={cx('guide-indicator')} data-tone={tone}>
                <svg
                    viewBox="0 0 24 24"
                    aria-hidden="true"
                    fill="none"
                    stroke="currentColor"
                    strokeWidth="2.5"
                    strokeLinecap="round"
                    strokeLinejoin="round"
                >
                    <path d={path} />
                </svg>
            </span>
            <span className={cx('guide-label')} aria-hidden={!expanded}>
                {label}
            </span>
        </button>
    );
}
export function NoticeDates({
    notice,
    preparation,
    compact = false,
    className = cx('meta-dates'),
}: {
    compact?: boolean;
    className?: string;
    notice?: Notice;
    preparation?: Preparation;
}) {
    const schedules = preparation
        ? currentPreparationSchedules(preparation, notice)
        : (notice?.schedules ?? []);
    const announcementExplanation = individualAnnouncement(schedules);
    const dates = [
        ['공고일', notice?.publishedDate],
        [
            '접수기간',
            notice?.startDate
                ? displayDate(notice.startDate) +
                  ' ~ ' +
                  displayDate(notice.endDate)
                : null,
        ],
        ['대상자 발표', selectSchedule(schedules, 'result')?.date],
        ['서류 제출 마감', selectSchedule(schedules, 'documents')?.date],
    ];
    return (
        <dl className={className}>
            {dates
                .filter(
                    ([label, date]) =>
                        !compact ||
                        date ||
                        label === '공고일' ||
                        (label === '대상자 발표' &&
                            !!announcementExplanation) ||
                        label === '접수기간',
                )
                .map(([label, date]) => (
                    <div key={label}>
                        <dt>{label}</dt>
                        <dd>
                            {label === '대상자 발표' &&
                            announcementExplanation ? (
                                <>
                                    {date && displayDate(date)}
                                    <IndividualAnnouncement
                                        event={announcementExplanation}
                                    />
                                </>
                            ) : label === '접수기간' ? (
                                (date ?? '확인 필요')
                            ) : (
                                displayDate(date)
                            )}
                        </dd>
                    </div>
                ))}
        </dl>
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
            <h3>{label}</h3>
            <strong>{statusLabel ?? daysLeft(date)}</strong>
            <p>
                {statusLabel
                    ? '공식 안내 확인'
                    : (dateLabel ?? displayDate(date))}
            </p>
            {children}
        </div>
    );
}
