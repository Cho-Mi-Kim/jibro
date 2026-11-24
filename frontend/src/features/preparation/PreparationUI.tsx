import { pendingAnswersLabel } from './conditionAnswerPresentation';
import { useNoticeDetailReturn } from '../housing/NoticeListVisit';
import { activeSchedules } from '../housing/noticeLifecycle';
import { NoticeLifecycleNotice } from '../housing/NoticeLifecycleNotice';
import { Link, useLocation } from 'react-router';
import { journeySteps, journeyStage } from './journey';
import { applicantQuestions, needsFollowUp } from '../housing/questions';
import {
    currentPreparationSchedules,
    currentPreparationScheduleSource,
} from '../housing/schedules';
import { nextNoticeSchedule, noticeListTitle } from '../housing/noticeDisplay';
import { useNotice } from '../housing/useNotice';
import { useNoticeEntryState } from '../../shared/navigation/useNoticeEntryState';
import type { Notice, Preparation } from '../housing/types';
import { bindClasses } from '../../shared/styles/classes';
import { Icon } from '../../shared/ui/Icon';
import { Disc } from '../../shared/ui/Disc';
import { InlineExplanation } from '../../shared/ui/ui';
import { ApplicationBadge } from '../housing/NoticeStatusBadges';
import { AgencyHousingSummary } from '../housing/AgencyMark';
import { UpcomingScheduleCard } from '../housing/ScheduleCard';
import { NoticeDates } from '../housing/NoticeFacts';
import { House } from './PreparationProgress';
import { PreparationScrollSection } from './PreparationScrollSection';
import housingClasses from '../housing/NoticeFacts.module.css';
import preparationClasses from './PreparationUI.module.css';
import {
    visiblePreparationQuestions,
    visiblePreparationDocuments,
} from '../housing/preparationApplicability';

const cx = bindClasses(housingClasses, preparationClasses);

export function Journey({ p }: { p: Preparation }) {
    const entryState = useNoticeEntryState();
    const detailReturn = useNoticeDetailReturn(p.noticeId);
    const { state } = useLocation();
    const steps = journeySteps(p);
    const current = journeyStage(p);
    return (
        <ol
            className={cx('journey')}
            aria-label="준비 단계"
            data-completed={current === steps.length}
        >
            {steps.map(({ label, path }, i) => (
                <li
                    key={label}
                    data-done={i < current}
                    aria-current={i === current ? 'step' : undefined}
                >
                    <Link
                        to={i === 0 && detailReturn ? detailReturn.to : path}
                        state={
                            detailReturn
                                ? i === 0
                                    ? detailReturn.state
                                    : state
                                : entryState
                        }
                    >
                        <span>
                            {i < current ? (
                                <Icon name="check" />
                            ) : i === current ? (
                                <Icon name={i === 1 ? 'info' : 'file'} />
                            ) : null}
                        </span>
                        {label}
                        {i === 4 && current === 4 && <small>진행 중</small>}
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
    const source = currentPreparationScheduleSource(p, notice.data);
    const schedules = source?.schedules ?? currentPreparationSchedules(p);
    const next = schedule
        ? nextNoticeSchedule(source, activeSchedules(source, schedules))
        : undefined;
    const questions = applicantQuestions(
        visiblePreparationQuestions(p),
        Object.values(p.documentConditions ?? {}),
    );
    const answered = questions.filter(
        (q) => p.answers[q.id] !== undefined,
    ).length;
    const docs = visiblePreparationDocuments(p).filter(
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
                        {answered} / {questions.length}
                    </strong>
                    <small>
                        {pendingAnswersLabel}{' '}
                        {
                            questions.filter((q) =>
                                needsFollowUp(
                                    q,
                                    p.answers[q.id],
                                    p.answers,
                                    p.questions,
                                ),
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
                        {next ? (
                            <UpcomingScheduleCard event={next} compact />
                        ) : (
                            <>
                                <small>예정된 일정 없음</small>
                                <strong>일정 보기</strong>
                            </>
                        )}
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
        <NoticeDates notice={notice} preparation={preparation} layout="grid" />
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
                    {n.data && <ApplicationBadge notice={n.data} />}
                    <h2>{noticeListTitle(p.title)}</h2>
                    {n.data && (
                        <p className={cx('muted')}>
                            <AgencyHousingSummary notice={n.data} />
                        </p>
                    )}
                </div>
            </div>
            <InlineExplanation label="공고 일정">
                <PreparationDates notice={n.data} preparation={p} />
            </InlineExplanation>
        </PreparationScrollSection>
    );
}
