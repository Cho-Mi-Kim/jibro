import { cx } from '../shared/styles/classes';
import { safeUrl } from '../shared/ui/helpers';
import { useState } from 'react';
import { useUnsavedChanges } from '../shared/ui/useUnsavedChanges';
import { Link, useNavigate, useParams } from 'react-router';
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
    Badge,
    Button,
    Card,
    EvidenceLink,
    Heading,
    House,
    Soon,
    State,
} from '../shared/ui/ui';
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
export function PreparationNav({ p }: { p: Preparation }) {
    return (
        <>
            <p className={cx('context')}>
                {p.title} · 기준 v{p.version}
            </p>
            {p.guideState !== 'AVAILABLE' && (
                <p className={cx('callout')}>
                    수정 공고 검토 중입니다. 기존 기록을 보존하며 변경 저장은
                    잠시 보류합니다.
                </p>
            )}
            <nav className={cx('tabs')} aria-label="개인 준비">
                <Link to={'/preparations/' + p.id}>개요</Link>
                <Link to={'/preparations/' + p.id + '/conditions'}>조건</Link>
                <Link to={'/preparations/' + p.id + '/documents'}>서류</Link>
                <Link to={'/preparations/' + p.id + '/schedule'}>일정</Link>
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
    return (
        <>
            <Heading title="내 준비">하나씩 확인하며 나의 집에 가까이</Heading>
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
            {q.data?.map((p) => (
                <Card key={p.id}>
                    <Link
                        className={cx('card-title')}
                        to={'/preparations/' + p.id}
                    >
                        {p.title}
                    </Link>
                    <House progress={p.progress} stage={p.houseStage} />
                </Card>
            ))}
        </>
    );
}
export function PreparationPage() {
    const q = usePreparation();
    const p = q.data;
    return (
        <>
            <Heading title="나의 준비 여정" />
            <State
                loading={q.isPending}
                error={q.error}
                retry={() => void q.refetch()}
            />
            {p && (
                <>
                    <PreparationNav p={p} />
                    <Card>
                        <House progress={p.progress} stage={p.houseStage} />
                        <ol className={cx('journey')}>
                            {[
                                '집터',
                                '기초',
                                '벽과 지붕',
                                '문과 창문',
                                '열쇠',
                            ].map((x, i) => (
                                <li
                                    key={x}
                                    aria-current={
                                        p.houseStage === i + 1
                                            ? 'step'
                                            : undefined
                                    }
                                >
                                    {i + 1}. {x}
                                </li>
                            ))}
                        </ol>
                    </Card>
                    <Card>
                        <span className={cx('eyebrow')}>지금 할 일</span>
                        <h2>{p.nextAction.label}</h2>
                        <p>{p.nextAction.reason}</p>
                        <Link
                            className={cx('link-button')}
                            to={p.nextAction.path}
                        >
                            준비 방법 보기
                        </Link>
                    </Card>
                    {p.warnings.length > 0 && (
                        <div className={cx('callout')}>
                            <h3>추가 확인이 필요해요</h3>
                            {p.warnings.map((w) => (
                                <p key={w}>{w}</p>
                            ))}
                        </div>
                    )}
                    <Link to="/preparations">다른 준비 건 보기</Link>
                </>
            )}
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
    return (
        <>
            <Heading title={edit ? '내 조건 확인' : '내 조건 확인 결과'} />
            <State
                loading={q.isPending}
                error={q.error}
                retry={() => void q.refetch()}
            />
            {p && (
                <>
                    <PreparationNav p={p} />
                    <p className={cx('callout')}>
                        입력 내용에 따른 안내이며 최종 자격 판단은 기관에서
                        진행합니다.
                    </p>
                    {edit && question ? (
                        <Card>
                            <p>
                                {step + 1} / {p.questions.length} 질문
                            </p>
                            <fieldset>
                                <legend>{question.title}</legend>
                                <p>{question.reason}</p>
                                {(['YES', 'NO', 'UNKNOWN'] as const).map(
                                    (a, i) => (
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
                                                onChange={() => {
                                                    setAnswers({
                                                        ...answers,
                                                        [question.id]: a,
                                                    });
                                                    setDirty(true);
                                                }}
                                            />
                                            {
                                                [
                                                    '확인했어요',
                                                    '해당하지 않아요',
                                                    '잘 모르겠어요',
                                                ][i]
                                            }
                                        </label>
                                    ),
                                )}
                            </fieldset>
                            <EvidenceLink evidence={question.evidence} />
                            {save.error && (
                                <p role="alert">{save.error.message}</p>
                            )}
                            <div className={cx('row')}>
                                <Button
                                    disabled={step === 0 || save.isPending}
                                    onClick={() => setStep(step - 1)}
                                >
                                    이전
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
                            <small>답변과 준비 상태를 계정에 저장합니다.</small>
                        </Card>
                    ) : (
                        <>
                            {p.questions.map((question) => (
                                <Card key={question.id}>
                                    <Badge>
                                        {p.answers[question.id] === 'YES'
                                            ? '입력 기준 확인'
                                            : p.answers[question.id] === 'NO'
                                              ? '해당 없음으로 답변'
                                              : p.answers[question.id] ===
                                                  'UNKNOWN'
                                                ? '추가 확인 필요'
                                                : '미입력'}
                                    </Badge>
                                    <h2>{question.title}</h2>
                                    <p>{question.reason}</p>
                                    <EvidenceLink
                                        evidence={question.evidence}
                                    />
                                </Card>
                            ))}
                            <Link
                                to={
                                    '/preparations/' + p.id + '/conditions/edit'
                                }
                            >
                                답변 수정
                            </Link>
                            <Link
                                className={cx('link-button')}
                                to={'/preparations/' + p.id + '/documents'}
                            >
                                내 준비 목록 보기
                            </Link>
                        </>
                    )}
                </>
            )}
        </>
    );
}
const stateNames: Record<DocumentState, string> = {
    UNCHECKED: '미확인',
    NEEDED: '발급 필요',
    IN_PROGRESS: '준비 중',
    READY: '준비 완료',
    RECHECK: '재확인',
};
function DocumentEditor({ p, d }: { p: Preparation; d: DocumentItem }) {
    const [state, setState] = useState(d.state);
    const [note, setNote] = useState(d.note);
    useUnsavedChanges(state !== d.state || note !== d.note);
    const saved = useSaved();
    const mutation = useMutation({
        mutationFn: () => api.document(p.id, d.id, p.revision, state, note),
        onSuccess: saved,
    });
    return (
        <details className={cx('card')}>
            <summary>
                {d.title} · {stateNames[d.state]}
                <small>
                    {
                        {
                            REQUIRED: '공통 필수',
                            CONDITIONAL: '해당 조건부',
                            UNKNOWN: '해당 여부 미확인',
                            NOT_APPLICABLE: '해당 없음',
                        }[d.necessity]
                    }
                </small>
            </summary>
            <p>{d.method}</p>
            <p>발급기관: {d.issuer}</p>
            <EvidenceLink evidence={d.evidence} />
            {safeUrl(d.issueUrl) ? (
                <a href={d.issueUrl!} target="_blank" rel="noreferrer">
                    공식 발급처 열기 ↗
                </a>
            ) : (
                <p>발급 경로 확인 필요 · 공식 공고의 발급 방법을 확인하세요.</p>
            )}
            <label>
                준비 상태
                <select
                    value={state}
                    onChange={(e) => setState(e.target.value as DocumentState)}
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
                    value={note}
                    onChange={(e) => setNote(e.target.value)}
                />
            </label>
            {mutation.error && <p role="alert">{mutation.error.message}</p>}
            {mutation.isSuccess && (
                <p role="status">이 서류의 준비 기록을 저장했어요.</p>
            )}
            <Button
                disabled={
                    mutation.isPending ||
                    p.guideState !== 'AVAILABLE' ||
                    d.necessity === 'UNKNOWN' ||
                    d.necessity === 'NOT_APPLICABLE'
                }
                onClick={() => mutation.mutate()}
            >
                {mutation.isPending ? '저장 중…' : '이 서류 준비 상태 저장'}
            </Button>
        </details>
    );
}
export function DocumentsPage() {
    const q = usePreparation();
    const p = q.data;
    return (
        <>
            <Heading title="서류 체크리스트" />
            <State
                loading={q.isPending}
                error={q.error}
                retry={() => void q.refetch()}
            />
            {p && (
                <>
                    <PreparationNav p={p} />
                    <p>
                        준비 완료{' '}
                        {
                            p.documents.filter(
                                (d) =>
                                    d.state === 'READY' &&
                                    d.necessity !== 'UNKNOWN' &&
                                    d.necessity !== 'NOT_APPLICABLE',
                            ).length
                        }
                        개 · 해당 여부 미확인{' '}
                        {
                            p.documents.filter((d) => d.necessity === 'UNKNOWN')
                                .length
                        }
                        개
                    </p>
                    {p.documents.map((d) => (
                        <DocumentEditor
                            key={d.id + ':' + p.revision}
                            p={p}
                            d={d}
                        />
                    ))}
                    <p className={cx('muted')}>
                        준비 완료는 사용자 기록입니다. 원본 검증이나 기관 접수를
                        의미하지 않습니다.
                    </p>
                </>
            )}
        </>
    );
}
export function SchedulePage() {
    const q = usePreparation();
    const p = q.data;
    const saved = useSaved();
    const mutation = useMutation({
        mutationFn: ({ id, recorded }: { id: string; recorded: boolean }) =>
            api.schedule(p!.id, id, p!.revision, recorded),
        onSuccess: saved,
    });
    return (
        <>
            <Heading title="신청 일정" />
            <State
                loading={q.isPending}
                error={q.error}
                retry={() => void q.refetch()}
            />
            {p && (
                <>
                    <PreparationNav p={p} />
                    {p.schedules.map((s) => (
                        <Card key={s.id}>
                            <Badge>공식 일정</Badge>
                            <h2>{s.title}</h2>
                            <strong>{s.date}</strong>
                            <EvidenceLink evidence={s.evidence} />
                            <label className={cx('choice')}>
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
                                해당 절차를 직접 확인하고 완료했다고 기록합니다
                            </label>
                        </Card>
                    ))}
                    {mutation.error && (
                        <p role="alert">{mutation.error.message}</p>
                    )}
                    <p>
                        일정 경과와 실제 신청 완료는 다릅니다. 공식 기관에서
                        접수 결과를 확인해 주세요.
                    </p>
                    <Soon label="알림 설정" />
                    <Soon label="캘린더 파일 내보내기" />
                </>
            )}
        </>
    );
}
