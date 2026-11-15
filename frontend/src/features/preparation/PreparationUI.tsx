import { activeSchedules } from '../housing/noticeLifecycle';
import { NoticeLifecycleNotice } from '../housing/NoticeLifecycleNotice';
import dateStyles from './PreparationDates.module.css';
import { Link } from 'react-router';
import { journeySteps } from './journey';
import { needsFollowUp } from '../housing/questions';
import { currentPreparationSchedules } from '../housing/schedules';
import {
    nextNoticeSchedule,
    homeScheduleSummary,
    noticeListTitle,
} from '../housing/noticeDisplay';
import { useNotice } from '../housing/useNotice';
import { useNoticeEntryState } from '../../shared/navigation/useNoticeEntryState';
import type { Notice, Preparation } from '../housing/types';
import { bindClasses } from '../../shared/styles/classes';
import { Icon } from '../../shared/ui/Icon';
import { Disc } from '../../shared/ui/Disc';
import { InlineExplanation } from '../../shared/ui/ui';
import { NoticeDates } from '../housing/NoticeFacts';
import { House } from './PreparationProgress';
import { PreparationScrollSection } from './PreparationScrollSection';
import housingClasses from '../housing/NoticeFacts.module.css';
import preparationClasses from './PreparationUI.module.css';

const cx = bindClasses(housingClasses, preparationClasses);

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
        ? nextNoticeSchedule(
              notice.data,
              activeSchedules(
                  notice.data,
                  currentPreparationSchedules(p, notice.data),
              ),
          )
        : undefined;
    const answered = p.questions.filter(
        (q) => p.answers[q.id] !== undefined,
    ).length;
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
    onExplanationToggle,
}: {
    p: Preparation;
    identity: React.ReactNode;
    onExplanationToggle?: (open: boolean) => void;
}) {
    const n = useNotice(p.noticeId);
    return (
        <section className={cx('panel prep-summary explanation-card')}>
            {identity}
            <NoticeLifecycleNotice notice={n.data} preparation />
            <NoticeDates notice={n.data} preparation={p} />
            <div className={cx('divider')} />
            <House
                progress={p.progress}
                stage={p.houseStage}
                onExplanationToggle={onExplanationToggle}
            />
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
        <PreparationScrollSection
            className={cx('panel notice-context explanation-card')}
        >
            <div className={cx('row')}>
                <div>
                    <h2>{noticeListTitle(p.title)}</h2>
                    <p className={cx('muted')}>
                        {n.data?.agency} · {n.data?.region} · 안내 v{p.version}
                    </p>
                </div>
            </div>
            <InlineExplanation label="공고 일정">
                <PreparationDates notice={n.data} preparation={p} />
            </InlineExplanation>
        </PreparationScrollSection>
    );
}
