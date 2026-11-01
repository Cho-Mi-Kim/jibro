import { displayDate, daysLeft, useNotice } from './housingHelpers';
import { Link } from 'react-router';
import type { Notice, Preparation } from '../../features/housing/types';
import { cx } from '../styles/classes';
import { Icon, type IconName } from './Icon';
import { guideLabels, safeUrl } from './helpers';
import { House } from './ui';
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
}: {
    notice?: Notice;
    preparation?: Preparation;
}) {
    const schedules = preparation?.schedules ?? notice?.schedules ?? [];
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
        [
            '대상자 발표',
            schedules.find((s) => /서류.*대상.*발표/.test(s.title))?.date ??
                schedules.find((s) => /발표/.test(s.title))?.date,
        ],
        [
            '서류 제출 마감',
            schedules.find((s) => /서류.*마감/.test(s.title))?.date,
        ],
    ];
    return (
        <dl className={cx('meta-dates')}>
            {dates.map(([label, date]) => (
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
    children,
}: {
    date?: string | null;
    children?: React.ReactNode;
}) {
    return (
        <div className={cx('deadline')}>
            <h3>신청 접수 마감</h3>
            <strong>{daysLeft(date)}</strong>
            <p>{displayDate(date)}</p>
            {children}
        </div>
    );
}
export function Journey({ p }: { p: Preparation }) {
    const conditions =
        p.questions.length > 0 &&
        p.questions.every(
            (q) => p.answers[q.id] && p.answers[q.id] !== 'UNKNOWN',
        );
    const docs = p.documents.filter((d) => d.necessity !== 'NOT_APPLICABLE');
    const ready =
        docs.length > 0 &&
        docs.every((d) => d.necessity !== 'UNKNOWN' && d.state === 'READY');
    const steps = [
        '공고 확인',
        '조건 확인',
        '서류 준비',
        '공식 신청',
        '후속 절차',
    ];
    const current = !conditions ? 1 : !ready ? 2 : 3;
    return (
        <ol className={cx('journey')} aria-label="준비 단계">
            {steps.map((label, i) => (
                <li
                    key={label}
                    data-done={i < current}
                    aria-current={i === current ? 'step' : undefined}
                >
                    <span>
                        {i < current ? (
                            <Icon name="check" />
                        ) : i === current ? (
                            <Icon name={i === 1 ? 'info' : 'file'} />
                        ) : null}
                    </span>
                    {label}
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
    const answered = p.questions.filter((q) => p.answers[q.id]).length;
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
                            p.questions.filter(
                                (q) =>
                                    !p.answers[q.id] ||
                                    p.answers[q.id] === 'UNKNOWN',
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
                        다음 일정<small>신청 접수 마감</small>
                        <strong className={cx('date-value')}>
                            {displayDate(notice.data?.endDate)}
                        </strong>
                    </div>
                    <Icon name="arrow" />
                </Link>
            )}
        </div>
    );
}
export function NextAction({ p }: { p: Preparation }) {
    return (
        <section className={cx('panel next-action')}>
            <div className={cx('next-icon')}>
                <Disc name="warning" tone="amber" />
            </div>
            <p className={cx('next-label')}>지금 할 일</p>
            <h2>{p.nextAction.label}</h2>
            <Link className={cx('link-button wide')} to={p.nextAction.path}>
                준비 방법 보기
            </Link>
        </section>
    );
}
export function PreparationSummary({ p }: { p: Preparation }) {
    const n = useNotice(p.noticeId);
    return (
        <section className={cx('panel prep-summary')}>
            <div className={cx('mobile-only summary-deadline')}>
                <Deadline date={n.data?.endDate} />
            </div>
            <Status tone="green">
                {p.progress === 100 ? '준비 기록 완료' : '신청 준비 중'}
            </Status>
            <h2>
                <Link to={'/preparations/' + p.id}>{p.title}</Link>
            </h2>
            <p className={cx('muted')}>
                {n.data
                    ? n.data.agency + ' · ' + n.data.region
                    : '공고 정보 확인 중'}
            </p>
            <NoticeDates notice={n.data} preparation={p} />
            <div className={cx('divider')} />
            <House progress={p.progress} stage={p.houseStage} />
        </section>
    );
}
export function NoticeContext({ p }: { p: Preparation }) {
    const n = useNotice(p.noticeId);
    return (
        <section className={cx('panel notice-context')}>
            <div className={cx('row')}>
                <Disc name="home" />
                <div>
                    <h2>{p.title}</h2>
                    <p className={cx('muted')}>
                        {n.data?.agency} · {n.data?.region} · 안내 v{p.version}
                    </p>
                </div>
            </div>
            <NoticeDates notice={n.data} preparation={p} />
        </section>
    );
}
export function OfficialLink({
    url,
    children,
    className,
}: {
    url?: string | null;
    children: React.ReactNode;
    className?: string;
}) {
    return safeUrl(url ?? null) ? (
        <a
            className={cx(className ?? 'link-button outline-button')}
            href={url!}
            target="_blank"
            rel="noreferrer"
        >
            {children}
            <Icon name="external" />
        </a>
    ) : (
        <span className={cx('muted')}>공식 링크 확인 필요</span>
    );
}
