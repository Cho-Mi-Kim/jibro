import dateStyles from './PreparationDates.module.css';
import { journeySteps } from '../../features/preparation/journey';
import { needsFollowUp } from '../../features/housing/questions';
import {
    currentPreparationSchedules,
    selectSchedule,
} from '../../features/housing/schedules';
import {
    displayDate,
    daysLeft,
    nextSchedule,
    homeScheduleSummary,
    useNotice,
} from './housingHelpers';
import { Link } from 'react-router';
import { useNoticeEntryState } from '../navigation/useNoticeEntryState';
import type { Notice, Preparation } from '../../features/housing/types';
import { cx } from '../styles/classes';
import { Icon, type IconName } from './Icon';
import { guideLabels, safeUrl } from './helpers';
import { House, InlineExplanation } from './ui';
export function Disc({
    name,
    tone = 'red',
}: {
    name: IconName;
    tone?: string;
}) {
    return (
        <span className={cx('icon-disc')} data-tone={tone}>
            <Icon name={name} />
        </span>
    );
}
export function Status({
    children,
    tone = 'gray',
}: {
    children: React.ReactNode;
    tone?: string;
}) {
    return (
        <span className={cx('status-badge')} data-tone={tone}>
            {children}
        </span>
    );
}
export function GuideBadge({ state }: { state: Notice['guideState'] }) {
    return (
        <Status
            tone={
                state === 'AVAILABLE'
                    ? 'green'
                    : state === 'UNSUPPORTED'
                      ? 'gray'
                      : 'amber'
            }
        >
            <Icon
                name={
                    state === 'AVAILABLE'
                        ? 'check'
                        : state === 'UNSUPPORTED'
                          ? 'info'
                          : 'clock'
                }
            />
            {guideLabels[state]}
        </Status>
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
                        label === '접수기간',
                )
                .map(([label, date]) => (
                    <div key={label}>
                        <dt>{label}</dt>
                        <dd>
                            {label === '접수기간'
                                ? (date ?? '확인 필요')
                                : displayDate(date)}
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
    children,
}: {
    label?: string;
    dateLabel?: string;
    date?: string | null;
    children?: React.ReactNode;
}) {
    return (
        <div className={cx('deadline')} data-today={daysLeft(date) === 'D-Day'}>
            <h3>{label}</h3>
            <strong>{daysLeft(date)}</strong>
            <p>{dateLabel ?? displayDate(date)}</p>
            {children}
        </div>
    );
}
export function Journey({ p }: { p: Preparation }) {
    const entryState = useNoticeEntryState();
    const conditions =
        p.questions.length > 0 &&
        p.questions.every((q) => !needsFollowUp(q, p.answers[q.id]));
    const docs = p.documents.filter((d) => d.necessity !== 'NOT_APPLICABLE');
    const ready =
        docs.length > 0 &&
        docs.every((d) => d.necessity !== 'UNKNOWN' && d.state === 'READY');
    const steps = journeySteps(p);
    const current = !conditions ? 1 : !ready ? 2 : 3;
    return (
        <ol className={cx('journey')} aria-label="준비 단계">
            {steps.map(({ label, path }, i) => (
                <li
                    key={label}
                    data-done={i < current}
                    aria-current={i === current ? 'step' : undefined}
                >
                    <Link to={path} state={entryState}>
                        <span>
                            {i < current ? (
                                <Icon name="check" />
                            ) : i === current ? (
                                <Icon name={i === 1 ? 'info' : 'file'} />
                            ) : null}
                        </span>
                        {label}
                    </Link>
                </li>
            ))}
        </ol>
    );
}
export function Metrics({
    p,
    schedule = false,
}: {
    p: Preparation;
    schedule?: boolean;
}) {
    const notice = useNotice(p.noticeId);
    const next = schedule
        ? nextSchedule(
              currentPreparationSchedules(p, notice.data),
              notice.data?.endDate,
          )
        : undefined;
    const answered = p.questions.filter((q) => p.answers[q.id]).length;
    const nextSummary = next ? homeScheduleSummary(next) : undefined;
    const docs = p.documents.filter(
        (d) => d.necessity === 'REQUIRED' || d.necessity === 'CONDITIONAL',
    );
    return (
        <div className={cx('metrics' + (!schedule ? ' two-metrics' : ''))}>
            <Link
                className={cx('metric')}
                to={'/preparations/' + p.id + '/conditions'}
            >
                <Disc name="file" tone="green" />
                <div>
                    질문 응답
                    <strong>
                        {answered} / {p.questions.length}
                    </strong>
                    <small>
                        추가 확인{' '}
                        {
                            p.questions.filter((q) =>
                                needsFollowUp(q, p.answers[q.id]),
                            ).length
                        }
                        개
                    </small>
                </div>
                <Icon name="arrow" />
            </Link>
            <Link
                className={cx('metric')}
                to={'/preparations/' + p.id + '/documents'}
            >
                <Disc name="file" tone="amber" />
                <div>
                    서류 준비
                    <strong>
                        {docs.filter((d) => d.state === 'READY').length} /{' '}
                        {docs.length}
                    </strong>
                    <small>완료</small>
                </div>
                <Icon name="arrow" />
            </Link>
            {schedule && (
                <Link
                    className={cx('metric')}
                    to={'/preparations/' + p.id + '/schedule'}
                >
                    <Disc name="calendar" />
                    <div>
                        다음 일정
                        <small>
                            {nextSummary?.title ?? '예정된 일정 없음'}
                        </small>
                        <strong className={cx('date-value')}>
                            {nextSummary?.date ?? '일정 보기'}
                        </strong>
                    </div>
                    <Icon name="arrow" />
                </Link>
            )}
        </div>
    );
}
export function PreparationSummary({
    p,
    identity,
}: {
    p: Preparation;
    identity: React.ReactNode;
}) {
    const n = useNotice(p.noticeId);
    return (
        <section className={cx('panel prep-summary explanation-card')}>
            {identity}
            <NoticeDates notice={n.data} preparation={p} />
            <div className={cx('divider')} />
            <House progress={p.progress} stage={p.houseStage} />
        </section>
    );
}
export function PreparationDates({
    notice,
    preparation,
}: {
    notice?: Notice;
    preparation: Preparation;
}) {
    return (
        <NoticeDates
            notice={notice}
            preparation={preparation}
            className={dateStyles.dates}
        />
    );
}

export function NoticeContext({ p }: { p: Preparation }) {
    const n = useNotice(p.noticeId);
    return (
        <section className={cx('panel notice-context explanation-card')}>
            <div className={cx('row')}>
                <Disc name="home" />
                <div>
                    <h2>{p.title}</h2>
                    <p className={cx('muted')}>
                        {n.data?.agency} · {n.data?.region} · 안내 v{p.version}
                    </p>
                </div>
            </div>
            <InlineExplanation label="공고 일정">
                <PreparationDates notice={n.data} preparation={p} />
            </InlineExplanation>
        </section>
    );
}
export function OfficialLink({
    url,
    children,
    className,
    showIcon = true,
}: {
    url?: string | null;
    children: React.ReactNode;
    className?: string;
    showIcon?: boolean;
}) {
    return safeUrl(url ?? null) ? (
        <a
            className={cx(className ?? 'link-button outline-button')}
            href={url!}
            target="_blank"
            rel="noreferrer"
        >
            {children}
            {showIcon && <Icon name="external" />}
        </a>
    ) : (
        <span className={cx('muted')}>공식 링크 확인 필요</span>
    );
}
