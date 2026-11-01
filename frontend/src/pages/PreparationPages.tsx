import {
    answerOptions,
    answerLabel,
    needsFollowUp,
} from '../features/housing/questions';
import { displayDate, daysLeft, useNotice } from '../shared/ui/housingHelpers';
import { cx } from '../shared/styles/classes';
import { useState } from 'react';
import { useUnsavedChanges } from '../shared/ui/useUnsavedChanges';
import { Link, NavLink, useNavigate, useParams } from 'react-router';
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { api } from '../features/housing/api';
import type {
    Answer,
    DocumentItem,
    DocumentState,
    Preparation,
} from '../features/housing/types';
import { useSession } from '../app/useSession';
import {
    Button,
    Card,
    EvidenceLink,
    Heading,
    House,
    Soon,
    State,
} from '../shared/ui/ui';
import {
    Deadline,
    Disc,
    Journey,
    Metrics,
    NextAction,
    NoticeContext,
    OfficialLink,
    Status,
} from '../shared/ui/HousingUI';
import { Icon } from '../shared/ui/Icon';
function usePreparation() {
    const { preparationId = '' } = useParams();
    const { user } = useSession();
    return useQuery({
        queryKey: ['preparation', user?.id, preparationId],
        queryFn: () => api.preparation(preparationId),
        enabled: !!user,
    });
}
function useSaved() {
    const client = useQueryClient();
    const { user } = useSession();
    return (p: Preparation) => {
        client.setQueryData(['preparation', user?.id, p.id], p);
        void client.invalidateQueries({ queryKey: ['preparations'] });
    };
}
function GuidanceRefresh({
    p,
    onRefreshed,
}: {
    p: Preparation;
    onRefreshed?: () => void;
}) {
    const notice = useNotice(p.noticeId);
    const saved = useSaved();
    const refresh = useMutation({
        mutationFn: () =>
            api.refreshPreparation(p.id, p.revision, notice.data!.version),
        onSuccess: (next) => {
            saved(next);
            onRefreshed?.();
        },
    });
    if (
        !notice.data ||
        notice.data.guideState !== 'AVAILABLE' ||
        notice.data.version <= p.version
    )
        return null;
    return (
        <Card>
            <h2>새로운 질문과 준비 안내가 있어요</h2>
            <p>
                기존 답변은 이전 기록으로 보관하고, 바뀐 질문은 다시 답변합니다.
                서류 메모는 유지하며 완료한 서류는 재확인이 필요합니다.
            </p>
            <Button
                disabled={refresh.isPending}
                onClick={() => refresh.mutate()}
            >
                {refresh.isPending
                    ? '불러오는 중…'
                    : '새 안내로 질문 다시 확인하기'}
            </Button>
            {refresh.error && <p role="alert">{refresh.error.message}</p>}
        </Card>
    );
}
export function PreparationNav({
    p,
    context = true,
}: {
    p: Preparation;
    context?: boolean;
}) {
    return (
        <>
            {context && <NoticeContext p={p} />}
            <GuidanceRefresh p={p} />
            {p.guideState !== 'AVAILABLE' && (
                <p className={cx('callout')}>
                    현재 기록과 최신 안내가 다르거나 안내 검수 중입니다. 기존
                    기록은 보존되며 새 안내 확인 전에는 저장할 수 없습니다.
                </p>
            )}
            <nav className={cx('tabs preparation-tabs')} aria-label="개인 준비">
                {[
                    ['', '개요'],
                    ['/conditions', '조건'],
                    ['/documents', '서류'],
                    ['/schedule', '일정'],
                ].map(([suffix, label]) => (
                    <NavLink
                        key={suffix}
                        to={'/preparations/' + p.id + suffix}
                        end
                    >
                        {label}
                    </NavLink>
                ))}
            </nav>
        </>
    );
}
export function PreparationListPage() {
    const { user } = useSession();
    const q = useQuery({
        queryKey: ['preparations', user?.id],
        queryFn: api.preparations,
        enabled: !!user,
    });
    const [selected, setSelected] = useState('');
    const p = q.data?.find((x) => x.id === selected) ?? q.data?.[0];
    return (
        <>
            <div className={cx('inline-heading preparation-heading')}>
                <Heading title="내 준비" />
                {q.data && q.data.length > 0 && (
                    <label className={cx('preparation-select')}>
                        <span>준비 건 선택 · {q.data.length}건</span>
                        <select
                            aria-label="준비 건 선택"
                            value={p?.id ?? ''}
                            onChange={(e) => setSelected(e.target.value)}
                        >
                            {q.data.map((x) => (
                                <option key={x.id} value={x.id}>
                                    {x.title}
                                </option>
                            ))}
                        </select>
                    </label>
                )}
            </div>
            <State
                loading={q.isPending}
                error={q.error}
                retry={() => void q.refetch()}
            />
            {q.data?.length === 0 && (
                <Card>
                    <h2>첫 신청 준비를 시작해 보세요</h2>
                    <Link className={cx('link-button')} to="/notices">
                        공고 둘러보기
                    </Link>
                </Card>
            )}
            {p && <PreparationOverview p={p} />}
        </>
    );
}
function PreparationOverview({ p }: { p: Preparation }) {
    const n = useNotice(p.noticeId);
    return (
        <div className={cx('prep-overview')}>
            <div className={cx('prep-overview-main')}>
                <NoticeContext p={p} />
                <PreparationNav p={p} context={false} />
                <div className={cx('prep-next')}>
                    <NextAction p={p} />
                </div>
                <Metrics p={p} />
                <Journey p={p} />
                <section className={cx('panel official-application')}>
                    <Disc name="external" />
                    <h2>공식 신청</h2>
                    <OfficialLink url={n.data?.evidence.url}>
                        공식 공고·신청 경로 열기
                    </OfficialLink>
                    <Link
                        className={cx('link-button outline-button')}
                        to={'/preparations/' + p.id + '/schedule'}
                    >
                        신청 여부 기록
                    </Link>
                </section>
            </div>
            <aside className={cx('panel prep-overview-progress')}>
                <Status tone="green">신청 준비 중</Status>
                <House progress={p.progress} stage={p.houseStage} />
                <div className={cx('divider')} />
                <Deadline date={n.data?.endDate} />
                <Link className={cx('link-button wide')} to={p.nextAction.path}>
                    준비 계속하기 <Icon name="arrow" />
                </Link>
            </aside>
        </div>
    );
}
export function PreparationPage() {
    const q = usePreparation();
    return (
        <>
            <Heading title="내 준비" />
            <State
                loading={q.isPending}
                error={q.error}
                retry={() => void q.refetch()}
            />
            {q.data && <PreparationOverview p={q.data} />}
        </>
    );
}
export function ConditionsPage({ edit = false }: { edit?: boolean }) {
    const q = usePreparation();
    const p = q.data;
    const saved = useSaved();
    const navigate = useNavigate();
    const [answers, setAnswers] = useState<Record<string, Answer>>({});
    const [step, setStep] = useState(0);
    const [dirty, setDirty] = useState(false);
    useUnsavedChanges(dirty);
    const save = useMutation({
        mutationFn: () =>
            api.answers(p!.id, p!.revision, { ...p!.answers, ...answers }),
        onSuccess: (next) => {
            saved(next);
            setDirty(false);
            if (step < next.questions.length - 1) setStep(step + 1);
            else navigate('/preparations/' + next.id + '/conditions');
        },
    });
    const question = p?.questions[step];
    const n = useNotice(p?.noticeId);
    const uncertain =
        p?.questions.filter((x) => needsFollowUp(x, p.answers[x.id])) ?? [];
    const confirmed =
        p?.questions.filter((x) => !needsFollowUp(x, p.answers[x.id])) ?? [];
    return (
        <div className={cx(edit ? 'condition-page' : 'result-page')}>
            <Heading
                title={edit ? '조건 확인' : '내 조건 확인 결과'}
                backTo={p ? '/preparations/' + p.id : '/preparations'}
            >
                {edit && p ? p.title + ' · 안내 v' + p.version : undefined}
            </Heading>
            <State
                loading={q.isPending}
                error={q.error}
                retry={() => void q.refetch()}
            />
            {p && (
                <GuidanceRefresh
                    p={p}
                    onRefreshed={() => {
                        setAnswers({});
                        setStep(0);
                        setDirty(false);
                    }}
                />
            )}
            {p &&
                (edit && question ? (
                    <div className={cx('condition-layout')}>
                        <aside className={cx('question-steps')}>
                            <h3>
                                질문 단계 <em>{step + 1}</em> /{' '}
                                {p.questions.length}
                            </h3>
                            <ol>
                                {p.questions.map((x, i) => (
                                    <li
                                        key={x.id}
                                        aria-current={
                                            i === step ? 'step' : undefined
                                        }
                                        data-complete={i < step}
                                    >
                                        <span>
                                            {i < step ? (
                                                <Icon name="check" />
                                            ) : (
                                                i + 1
                                            )}
                                        </span>
                                        <small title={x.title}>{x.title}</small>
                                    </li>
                                ))}
                            </ol>
                        </aside>
                        <section className={cx('panel question-panel')}>
                            <fieldset>
                                <legend>{question.title}</legend>
                                <div className={cx('info-note')}>
                                    <Icon name="calendar" />
                                    공고 게시일:{' '}
                                    {displayDate(n.data?.publishedDate)}
                                </div>
                                <p className={cx('muted')}>
                                    자격 판단 기준일은 아래 질문 안내를
                                    확인하세요.
                                </p>
                                <p className={cx('muted')}>{question.reason}</p>
                                {answerOptions(question).map(
                                    ({ value: a, label }) => (
                                        <label className={cx('choice')} key={a}>
                                            <input
                                                type="radio"
                                                name={question.id}
                                                checked={
                                                    (answers[question.id] ??
                                                        p.answers[
                                                            question.id
                                                        ]) === a
                                                }
                                                disabled={save.isPending}
                                                onChange={() => {
                                                    setAnswers({
                                                        ...answers,
                                                        [question.id]: a,
                                                    });
                                                    setDirty(true);
                                                }}
                                            />
                                            {label}
                                        </label>
                                    ),
                                )}
                            </fieldset>
                            <div className={cx('question-evidence')}>
                                <Icon name="file" />
                                <EvidenceLink evidence={question.evidence} />
                            </div>
                            <div className={cx('info-note')}>
                                <Icon name="info" />
                                <span>
                                    답변과 준비 상태를 계정에 저장합니다.
                                    <br />
                                    주민등록번호와 증빙 원본은 입력하지 않아요.
                                </span>
                            </div>
                            {p.guideState !== 'AVAILABLE' && (
                                <p role="alert">
                                    안내 검토 중으로 저장이 보류됩니다.
                                </p>
                            )}
                            {save.error && (
                                <p role="alert">{save.error.message}</p>
                            )}
                            <div className={cx('question-buttons')}>
                                <Button
                                    className="secondary"
                                    disabled={step === 0 || save.isPending}
                                    onClick={() => setStep(step - 1)}
                                >
                                    이전 단계
                                </Button>
                                <Button
                                    disabled={
                                        save.isPending ||
                                        !(
                                            answers[question.id] ??
                                            p.answers[question.id]
                                        ) ||
                                        p.guideState !== 'AVAILABLE'
                                    }
                                    onClick={() => save.mutate()}
                                >
                                    {save.isPending
                                        ? '저장 중…'
                                        : step === p.questions.length - 1
                                          ? '저장 후 결과 보기'
                                          : '저장 후 다음'}
                                </Button>
                            </div>
                        </section>
                    </div>
                ) : (
                    <div className={cx('result-grid')}>
                        <div className={cx('result-context')}>
                            <NoticeContext p={p} />
                        </div>
                        <section className={cx('panel result-counts')}>
                            <div className={cx('row')}>
                                <div>
                                    <Disc name="check" tone="green" />
                                    <span>
                                        답변 완료
                                        <strong>{confirmed.length}개</strong>
                                    </span>
                                </div>
                                <div>
                                    <Disc name="warning" tone="amber" />
                                    <span>
                                        추가 확인
                                        <strong>{uncertain.length}개</strong>
                                    </span>
                                </div>
                            </div>
                            <p className={cx('info-note')}>
                                입력한 내용에 대한 안내이며 최종 신청 자격을
                                뜻하지 않습니다.
                            </p>
                        </section>
                        <section className={cx('result-questions')}>
                            <h2>추가로 확인할 조건</h2>
                            {uncertain.length === 0 && (
                                <Card>추가 확인으로 남긴 조건이 없습니다.</Card>
                            )}
                            {uncertain.map((x) => (
                                <article
                                    className={cx('panel result-question')}
                                    key={x.id}
                                >
                                    <div className={cx('row')}>
                                        <Disc name="warning" tone="amber" />
                                        <Status tone="amber">
                                            추가 확인 필요
                                        </Status>
                                    </div>
                                    <h2>{x.title}</h2>
                                    <p>
                                        내 답변:{' '}
                                        {answerLabel(x, p.answers[x.id])}
                                    </p>
                                    <p className={cx('muted')}>{x.reason}</p>
                                    <EvidenceLink evidence={x.evidence} />
                                    <Link
                                        className={cx('result-action')}
                                        to={
                                            '/preparations/' +
                                            p.id +
                                            '/conditions/edit'
                                        }
                                    >
                                        답변 이어서 작성 <Icon name="arrow" />
                                    </Link>
                                </article>
                            ))}
                            <details className={cx('card')}>
                                <summary>
                                    답변 완료 {confirmed.length}개 보기
                                </summary>
                                {confirmed.map((x) => (
                                    <div key={x.id}>
                                        <h3>{x.title}</h3>
                                        <p>{answerLabel(x, p.answers[x.id])}</p>
                                        <EvidenceLink evidence={x.evidence} />
                                    </div>
                                ))}
                            </details>
                        </section>
                        <aside className={cx('panel result-documents')}>
                            <div className={cx('row')}>
                                <Disc name="file" />
                                <h2>개인별 서류</h2>
                            </div>
                            <p>
                                필요 서류{' '}
                                {
                                    p.documents.filter(
                                        (d) =>
                                            d.necessity === 'REQUIRED' ||
                                            d.necessity === 'CONDITIONAL',
                                    ).length
                                }
                                개
                            </p>
                            <p className={cx('muted')}>
                                해당 여부 추가 확인{' '}
                                {
                                    p.documents.filter(
                                        (d) => d.necessity === 'UNKNOWN',
                                    ).length
                                }
                                개
                            </p>
                            <Link
                                className={cx('link-button wide')}
                                to={'/preparations/' + p.id + '/documents'}
                            >
                                내 준비 목록 보기
                            </Link>
                            <Link
                                className={cx('result-edit')}
                                to={
                                    '/preparations/' + p.id + '/conditions/edit'
                                }
                            >
                                답변 수정하기
                            </Link>
                        </aside>
                    </div>
                ))}
        </div>
    );
}
const stateNames: Record<DocumentState, string> = {
    UNCHECKED: '미확인',
    NEEDED: '발급 필요',
    IN_PROGRESS: '준비 중',
    READY: '준비 완료',
    RECHECK: '재확인',
};
function DocumentEditor({
    p,
    d,
    active,
    onSelect,
}: {
    p: Preparation;
    d: DocumentItem;
    active: boolean;
    onSelect: () => void;
}) {
    const [draftState, setState] = useState<DocumentState | null>(null);
    const [draftNote, setNote] = useState<string | null>(null);
    const state = draftState ?? d.state;
    const note = draftNote ?? d.note;
    useUnsavedChanges(state !== d.state || note !== d.note);
    const saved = useSaved();
    const mutation = useMutation({
        mutationFn: (ready: boolean) =>
            api.document(p.id, d.id, p.revision, ready ? 'READY' : state, note),
        onSuccess: (next) => {
            saved(next);
            setState(null);
            setNote(null);
        },
    });
    const unavailable =
        p.guideState !== 'AVAILABLE' ||
        d.necessity === 'UNKNOWN' ||
        d.necessity === 'NOT_APPLICABLE';
    const necessity = {
        REQUIRED: '공통 필수',
        CONDITIONAL: '해당 조건부',
        UNKNOWN: '해당 여부 미확인',
        NOT_APPLICABLE: '해당 없음',
    }[d.necessity];
    return (
        <div className={cx('document-item')} data-active={active}>
            <button
                className={cx('document-select')}
                aria-expanded={active}
                aria-controls={'document-' + d.id}
                onClick={onSelect}
            >
                <Disc
                    name="file"
                    tone={d.state === 'READY' ? 'green' : 'amber'}
                />
                <strong>{d.title}</strong>
                <span className={cx('necessity-label')}>{necessity}</span>
                <Status
                    tone={
                        d.state === 'READY'
                            ? 'green'
                            : d.state === 'NEEDED'
                              ? 'red'
                              : 'gray'
                    }
                >
                    {stateNames[d.state]}
                </Status>
                <Icon name="arrow" />
            </button>
            <section
                id={'document-' + d.id}
                className={cx('document-editor')}
                hidden={!active}
            >
                <header>
                    <Disc name="file" tone="amber" />
                    <div>
                        <h2>{d.title}</h2>
                        <Status tone="amber">{necessity}</Status>{' '}
                        <Status>{stateNames[d.state]}</Status>
                    </div>
                </header>
                <dl className={cx('document-facts')}>
                    <dt>발급처</dt>
                    <dd>{d.issuer}</dd>
                    <dt>발급 기준</dt>
                    <dd>{d.method}</dd>
                    <dt>공식 근거</dt>
                    <dd>
                        <EvidenceLink evidence={d.evidence} />
                    </dd>
                </dl>
                <OfficialLink
                    url={d.issueUrl}
                    className="link-button outline-button wide"
                >
                    공식 발급처 열기
                </OfficialLink>
                <details className={cx('document-options')}>
                    <summary>준비 상태와 메모 수정</summary>
                    <label>
                        준비 상태
                        <select
                            value={state}
                            disabled={mutation.isPending || unavailable}
                            onChange={(e) =>
                                setState(e.target.value as DocumentState)
                            }
                        >
                            {Object.entries(stateNames).map(([key, label]) => (
                                <option key={key} value={key}>
                                    {label}
                                </option>
                            ))}
                        </select>
                    </label>
                    <label>
                        메모 (개인정보·증빙 내용 입력 금지)
                        <textarea
                            maxLength={500}
                            disabled={mutation.isPending || unavailable}
                            value={note}
                            onChange={(e) => setNote(e.target.value)}
                        />
                    </label>
                    <Button
                        className="secondary wide"
                        disabled={mutation.isPending || unavailable}
                        onClick={() => mutation.mutate(false)}
                    >
                        이 서류 준비 상태 저장
                    </Button>
                </details>
                {mutation.error && <p role="alert">{mutation.error.message}</p>}
                {mutation.isSuccess && (
                    <p role="status">이 서류의 준비 기록을 저장했어요.</p>
                )}
                <Button
                    className="wide"
                    disabled={
                        mutation.isPending || unavailable || d.state === 'READY'
                    }
                    onClick={() => mutation.mutate(true)}
                >
                    {mutation.isPending
                        ? '저장 중…'
                        : d.state === 'READY'
                          ? '준비 완료로 기록했어요'
                          : '이 서류 준비 완료로 기록'}
                </Button>
                <p className={cx('muted info-line')}>
                    <Icon name="info" />
                    서류 원본은 올리지 않고 준비 상태만 기록합니다.
                </p>
            </section>
        </div>
    );
}
export function DocumentsPage() {
    const q = usePreparation();
    const p = q.data;
    const [selected, setSelected] = useState<string | null>(null);
    const docs =
        p?.documents.filter(
            (d) => d.necessity === 'REQUIRED' || d.necessity === 'CONDITIONAL',
        ) ?? [];
    const ready = docs.filter((d) => d.state === 'READY').length;
    const unresolved =
        p?.documents.filter((d) => d.necessity === 'UNKNOWN') ?? [];
    const active =
        selected === ''
            ? ''
            : docs.some((d) => d.id === selected)
              ? selected
              : docs[0]?.id;
    return (
        <>
            <Heading title="서류 준비" backTo="/preparations" />
            <State
                loading={q.isPending}
                error={q.error}
                retry={() => void q.refetch()}
            />
            {p && (
                <>
                    <PreparationNav p={p} />
                    {unresolved.length > 0 && (
                        <Card>
                            <h2>해당 여부 추가 확인 {unresolved.length}개</h2>
                            <p>
                                아직 준비 대상인지 정하지 못한 서류가 있어요.
                                조건에 답하면 필요한 서류만 목록에 추가됩니다.
                            </p>
                            <details>
                                <summary>확인이 필요한 항목 보기</summary>
                                <ul>
                                    {unresolved.map((d) => (
                                        <li key={d.id}>{d.title}</li>
                                    ))}
                                </ul>
                            </details>
                            <Link
                                to={
                                    '/preparations/' + p.id + '/conditions/edit'
                                }
                            >
                                조건 답변 보완하기
                            </Link>
                        </Card>
                    )}
                    <div className={cx('documents-layout')}>
                        <div className={cx('document-progress')}>
                            <h2>
                                준비 대상 서류 {docs.length}개 중{' '}
                                <em>{ready}개</em> 준비 완료
                            </h2>
                            <div className={cx('row')}>
                                <strong>
                                    {ready} / {docs.length}
                                </strong>
                                <progress
                                    aria-label="서류 준비 진행률"
                                    max={Math.max(1, docs.length)}
                                    value={ready}
                                />
                                <span>
                                    {docs.length
                                        ? Math.round(
                                              (ready / docs.length) * 100,
                                          )
                                        : 0}
                                    %
                                </span>
                            </div>
                            <small>
                                준비 완료 {ready}개 · 해당 여부 미확인{' '}
                                {
                                    p.documents.filter(
                                        (d) => d.necessity === 'UNKNOWN',
                                    ).length
                                }
                                개
                            </small>
                        </div>
                        {docs.map((d) => (
                            <DocumentEditor
                                key={p.id + ':' + d.id}
                                p={p}
                                d={d}
                                active={active === d.id}
                                onSelect={() =>
                                    setSelected(active === d.id ? '' : d.id)
                                }
                            />
                        ))}
                        {docs.length === 0 && (
                            <Card>
                                현재 답변으로 확인된 준비 대상 서류가 없습니다.
                            </Card>
                        )}
                    </div>
                </>
            )}
        </>
    );
}
export function SchedulePage() {
    const q = usePreparation();
    const p = q.data;
    const n = useNotice(p?.noticeId);
    const saved = useSaved();
    const [recording, setRecording] = useState(false);
    const mutation = useMutation({
        mutationFn: ({ id, recorded }: { id: string; recorded: boolean }) =>
            api.schedule(p!.id, id, p!.revision, recorded),
        onSuccess: saved,
    });
    return (
        <>
            <Heading title="신청 일정" backTo="/preparations" />
            <State
                loading={q.isPending}
                error={q.error}
                retry={() => void q.refetch()}
            />
            {p && (
                <div className={cx('schedule-grid')}>
                    <div className={cx('schedule-main-column')}>
                        <div className={cx('schedule-context')}>
                            <PreparationNav p={p} />
                        </div>
                        <section className={cx('panel schedule-timeline')}>
                            <h2>공식 일정</h2>
                            <ol>
                                {p.schedules.map((s) => (
                                    <li key={s.id}>
                                        <span className={cx('timeline-dot')} />
                                        <time>{displayDate(s.date)}</time>
                                        <strong>{s.title}</strong>
                                        <Status
                                            tone={
                                                s.recorded
                                                    ? 'green'
                                                    : daysLeft(s.date) ===
                                                        '일정 경과'
                                                      ? 'gray'
                                                      : 'red'
                                            }
                                        >
                                            {s.recorded
                                                ? '완료 기록'
                                                : daysLeft(s.date)}
                                        </Status>
                                        <div
                                            className={cx('timeline-evidence')}
                                        >
                                            <EvidenceLink
                                                evidence={s.evidence}
                                            />
                                        </div>
                                    </li>
                                ))}
                            </ol>
                            <p className={cx('info-note')}>
                                <Icon name="info" />
                                일정 경과와 실제 신청 완료는 별개입니다.
                            </p>
                        </section>
                    </div>
                    <div className={cx('schedule-side-column')}>
                        <aside className={cx('panel schedule-next')}>
                            <h3>다음 중요한 일정</h3>
                            <Deadline date={n.data?.endDate} />
                            <div className={cx('divider')} />
                            <div className={cx('schedule-soon')}>
                                <Icon name="bell" />
                                <Soon label="알림 설정" />
                            </div>
                            <div className={cx('schedule-soon')}>
                                <Icon name="calendar" />
                                <Soon label="캘린더 파일 내보내기" />
                            </div>
                        </aside>
                        <section className={cx('panel schedule-record')}>
                            <h2>나의 신청 기록</h2>
                            <div className={cx('row')}>
                                <Disc name="file" />
                                <strong>
                                    {p.schedules.some((s) => s.recorded)
                                        ? '완료로 기록한 절차가 있어요.'
                                        : '아직 기록하지 않았어요.'}
                                </strong>
                                <Button
                                    className="secondary"
                                    onClick={() => setRecording(!recording)}
                                >
                                    {recording ? '기록 닫기' : '신청 여부 기록'}
                                </Button>
                            </div>
                            {recording &&
                                p.schedules.map((s) => (
                                    <label className={cx('choice')} key={s.id}>
                                        <input
                                            type="checkbox"
                                            checked={s.recorded}
                                            disabled={
                                                mutation.isPending ||
                                                p.guideState !== 'AVAILABLE'
                                            }
                                            onChange={(e) =>
                                                mutation.mutate({
                                                    id: s.id,
                                                    recorded: e.target.checked,
                                                })
                                            }
                                        />
                                        {s.title} 절차를 직접 확인하고
                                        완료했다고 기록합니다
                                    </label>
                                ))}
                            {mutation.error && (
                                <p role="alert">{mutation.error.message}</p>
                            )}
                            <div className={cx('divider')} />
                            <OfficialLink
                                url={n.data?.evidence.url}
                                className="link-button wide"
                            >
                                공식 공고·신청 경로 열기
                            </OfficialLink>
                        </section>
                    </div>
                </div>
            )}
        </>
    );
}
