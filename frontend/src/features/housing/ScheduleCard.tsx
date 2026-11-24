import type { ComponentProps, ReactNode } from 'react';
import { handleDisclosureCardClick } from '../../shared/ui/handleDisclosureCardClick';
import { Icon } from '../../shared/ui/Icon';
import { EvidenceLink } from './EvidenceLink';
import {
    daysLeft,
    displayDate,
    homeScheduleSummary,
    scheduleEventText,
} from './noticeDisplay';
import type { Evidence, Schedule } from './types';
import { IndividualAnnouncement } from './IndividualAnnouncement';
import { ScheduleInfoDialog } from './ScheduleInfoDialog';
import { noticeDetailDates, shortNoticeDate } from './noticeDetailDates';
import type { ScheduleSource } from './schedules';
import styles from './ScheduleCard.module.css';

type CardEvent = {
    title: string;
    date: string | null;
    timingDescription?: string | null;
};

function TargetedAnnouncements({
    events,
}: {
    events: readonly Omit<Schedule, 'recorded'>[];
}) {
    return (
        <ScheduleInfoDialog
            label="대상별 안내"
            title="대상별 결과 발표"
            buttonLabel="결과 발표 대상별 안내"
        >
            <ul className={styles.sessionTimes}>
                {events.map((event) => (
                    <li key={event.id}>
                        <strong>{event.title}</strong>
                        <p>{scheduleEventText(event)}</p>
                        <EvidenceLink evidence={event.evidence} />
                    </li>
                ))}
            </ul>
        </ScheduleInfoDialog>
    );
}

export function ScheduleCard({
    event,
    heading: Heading = 'strong',
    timing,
    status,
    layout = 'event',
    evidence,
    children,
    detailsProps,
}: {
    event: CardEvent;
    heading?: 'h3' | 'h4' | 'strong' | 'span';
    timing?: ReactNode;
    status?: ReactNode;
    layout?: 'event' | 'compact' | 'featured' | 'timeline' | 'deadline';
    evidence?: Evidence;
    children?: ReactNode;
    detailsProps?: Pick<ComponentProps<'details'>, 'ref' | 'onClick'>;
}) {
    const expandable = !!evidence || !!children;
    const header = (
        <>
            <Heading className={styles.title}>{event.title}</Heading>
            {status && <span className={styles.status}>{status}</span>}
            <span className={styles.timing}>
                {timing ?? scheduleEventText(event)}
            </span>
            {expandable && <Icon name="down" className={styles.chevron} />}
        </>
    );
    if (!expandable)
        return (
            <div className={styles.header} data-layout={layout}>
                {header}
            </div>
        );
    return (
        <details
            className={styles.card}
            onClick={handleDisclosureCardClick}
            {...detailsProps}
        >
            <summary className={styles.header} data-layout={layout}>
                {header}
            </summary>
            {evidence && <EvidenceLink evidence={evidence} />}
            {children}
        </details>
    );
}

export function TimelineScheduleCard({
    row,
}: {
    row: CardEvent & {
        dateLabel: string;
        weekday: string;
        time: string;
        status: string;
        timingLabel?: string;
        announcementExplanation?: Schedule;
        announcementAlternatives?: Omit<Schedule, 'recorded'>[];
    };
}) {
    return (
        <ScheduleCard
            layout="timeline"
            event={row}
            timing={
                row.timingLabel ? (
                    row.timingLabel
                ) : row.announcementAlternatives?.length ? (
                    <TargetedAnnouncements
                        events={row.announcementAlternatives}
                    />
                ) : row.date ? (
                    <time dateTime={row.date}>
                        {row.dateLabel}
                        <small>
                            ({row.weekday}){row.time && ` ${row.time}`}
                        </small>
                    </time>
                ) : (
                    <span
                        aria-label={
                            row.announcementExplanation
                                ? '정해진 발표일 없음'
                                : '날짜 확인 필요'
                        }
                    >
                        —
                    </span>
                )
            }
            status={
                row.announcementExplanation ? (
                    <IndividualAnnouncement
                        event={row.announcementExplanation}
                    />
                ) : row.announcementAlternatives?.length ? undefined : (
                    row.status
                )
            }
        />
    );
}

export function UpcomingScheduleCard({
    event,
    compact = false,
}: {
    event: { title: string; date: string };
    compact?: boolean;
}) {
    const short = homeScheduleSummary(event);
    const parts = event.title.match(/^(.*?)\s*\(([^()]*)\)\s*$/);
    return (
        <ScheduleCard
            layout={compact ? 'compact' : 'featured'}
            heading={compact ? 'span' : 'h4'}
            event={{
                ...event,
                title: compact ? short.title : (parts?.[1] ?? event.title),
            }}
            timing={
                compact ? (
                    short.date
                ) : (
                    <>
                        <time dateTime={event.date}>
                            {displayDate(event.date)}
                        </time>
                        {parts?.[2] && <span> {parts[2]}</span>}
                    </>
                )
            }
            status={compact ? undefined : daysLeft(event.date)}
        />
    );
}

export function ScheduleSummary({
    source,
    className,
    labelId,
    showPublished,
    showClosing,
    layout = 'rows',
    hideUnknown = false,
}: {
    source: ScheduleSource;
    className?: string;
    labelId?: string;
    showPublished?: boolean;
    showClosing?: boolean;
    layout?: 'rows' | 'columns' | 'grid' | 'strip';
    hideUnknown?: boolean;
}) {
    const shortDates = layout === 'strip';
    const {
        closing,
        start,
        end,
        period,
        sessions,
        showPeriod,
        announcement,
        documents,
        announcementExplanation,
        announcementAlternatives,
    } = noticeDetailDates(source, shortDates);
    const [firstDate, separator, lastDate] = period.values;
    const startTime = start.time && `${start.time}부터`;
    const endTime = end.time && `${end.time}까지`;
    const firstTime = lastDate ? startTime : startTime || endTime;
    return (
        <dl
            className={[styles[layout], className].filter(Boolean).join(' ')}
            aria-labelledby={labelId}
            data-short-dates={shortDates}
        >
            {showPublished && (
                <div>
                    <dt>공고일</dt>
                    <dd>{displayDate(source.publishedDate)}</dd>
                </div>
            )}
            <div>
                <dt>접수 기간</dt>
                <dd>
                    {showPeriod && (
                        <span
                            className={styles.period}
                            aria-label={period.label}
                        >
                            <span>
                                {firstDate}
                                {firstTime && <small>{firstTime}</small>}
                            </span>
                            {lastDate && (
                                <span className={styles.periodEnd}>
                                    <span aria-hidden="true">
                                        {' '}
                                        {separator}{' '}
                                    </span>
                                    <span>
                                        {lastDate}
                                        {endTime && <small>{endTime}</small>}
                                    </span>
                                </span>
                            )}
                        </span>
                    )}
                    {period.note && <small> {period.note}</small>}
                    {sessions.length > 0 && (
                        <ScheduleInfoDialog
                            label="회차별 시간"
                            title="회차별 접수 시간"
                        >
                            <ul className={styles.sessionTimes}>
                                {sessions.map((event) => (
                                    <li key={event.id}>
                                        {event.date && (
                                            <time dateTime={event.date}>
                                                {event.date.replaceAll(
                                                    '-',
                                                    '.',
                                                )}
                                            </time>
                                        )}{' '}
                                        {event.title}
                                        {event.timingDescription &&
                                            ` · ${event.timingDescription}`}
                                        <EvidenceLink
                                            evidence={event.evidence}
                                        />
                                    </li>
                                ))}
                            </ul>
                        </ScheduleInfoDialog>
                    )}
                </dd>
            </div>
            {(
                [
                    [
                        '결과 발표',
                        announcement,
                        announcementExplanation,
                        announcementAlternatives,
                    ],
                    ['서류 마감', documents, undefined, []],
                ] as const
            ).map(([label, value, individual, alternatives]) => {
                if (
                    hideUnknown &&
                    !value.date &&
                    !individual &&
                    !alternatives.length
                )
                    return null;
                const date = shortDates
                    ? shortNoticeDate(value.date)
                    : value.date;
                return (
                    <div className={styles.milestone} key={label}>
                        <dt>{label}</dt>
                        <dd
                            aria-label={
                                [
                                    value.date,
                                    value.time,
                                    individual && '개별 안내',
                                    alternatives.length > 0 && '대상별 안내',
                                ]
                                    .filter(Boolean)
                                    .join(' ') || '날짜 정보 없음'
                            }
                        >
                            {date ||
                                (!shortDates &&
                                !individual &&
                                !alternatives.length
                                    ? '확인 필요'
                                    : '')}
                            {value.time && (
                                <small>
                                    {' '}
                                    {value.time}
                                    {label === '서류 마감' && '까지'}
                                </small>
                            )}
                            {individual && (
                                <IndividualAnnouncement event={individual} />
                            )}
                            {alternatives.length > 0 && (
                                <TargetedAnnouncements events={alternatives} />
                            )}
                        </dd>
                    </div>
                );
            })}
            {showClosing && (
                <div>
                    <dt>접수 마감</dt>
                    <dd>
                        {[
                            end.date,
                            end.time,
                            /조기\s*중단\s*가능/.test(closing?.title ?? '') &&
                                '조기중단 가능',
                        ]
                            .filter(Boolean)
                            .join(' ')}
                    </dd>
                </div>
            )}
        </dl>
    );
}
