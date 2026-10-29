import { cx } from '../shared/styles/classes';
import { useState } from 'react';
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { api } from '../features/housing/api';
import type { Review } from '../features/housing/types';
import { Button, Card, EvidenceLink, Heading, State } from '../shared/ui/ui';
import { demoMode } from '../shared/api/transport';
import { useUnsavedChanges } from '../shared/ui/useUnsavedChanges';
function DraftEditor({
    review,
    onDirtyChange,
}: {
    review: Review;
    onDirtyChange: (dirty: boolean) => void;
}) {
    const [text, setText] = useState<string | null>(null);
    const [original, setOriginal] = useState('');
    useUnsavedChanges(text !== null && text !== original);
    const client = useQueryClient();
    const load = useMutation({
        mutationFn: () => api.draft(review.id),
        onSuccess: (draft) => {
            const value = JSON.stringify(draft, null, 4);
            setOriginal(value);
            setText(value);
        },
    });
    const save = useMutation({
        mutationFn: async () => {
            let draft: unknown;
            try {
                draft = JSON.parse(text ?? '');
            } catch {
                throw new Error('원고의 JSON 형식을 확인해 주세요.');
            }
            return api.saveDraft(review.id, review.revision, draft);
        },
        onSuccess: () => {
            onDirtyChange(false);
            setText(null);
            void client.invalidateQueries();
        },
    });
    return (
        <section>
            <h3>기본 정보·준비 안내 원고</h3>
            <p>
                원문과 대조해 질문, 제출 서류, 신청 기간을 보완하세요. 저장하면
                기존 공개를 보류하고 다시 검수합니다.
            </p>
            {text === null ? (
                <Button disabled={load.isPending} onClick={() => load.mutate()}>
                    구조화 원고 열기
                </Button>
            ) : (
                <>
                    <label>
                        구조화 원고 (JSON)
                        <textarea
                            rows={20}
                            value={text}
                            onChange={(e) => {
                                setText(e.target.value);
                                onDirtyChange(e.target.value !== original);
                            }}
                            spellCheck={false}
                        />
                    </label>
                    <Button
                        disabled={save.isPending || text === original}
                        onClick={() => save.mutate()}
                    >
                        원고 저장 후 재검수
                    </Button>
                    <Button
                        className={cx('secondary')}
                        disabled={save.isPending}
                        onClick={() => {
                            if (
                                text === original ||
                                window.confirm('저장하지 않은 원고를 닫을까요?')
                            ) {
                                onDirtyChange(false);
                                setText(null);
                            }
                        }}
                    >
                        편집 닫기
                    </Button>
                </>
            )}
            {(load.error || save.error) && (
                <p role="alert">{load.error?.message || save.error?.message}</p>
            )}
        </section>
    );
}
function ReviewEditor({ review: r }: { review: Review }) {
    const [draftDirty, setDraftDirty] = useState(false);
    const [basic, setBasic] = useState(r.basic),
        [guide, setGuide] = useState(r.guide),
        [reason, setReason] = useState(r.reason);
    const client = useQueryClient();
    const save = useMutation({
        mutationFn: () =>
            api.review(r.id, { basic, guide, reason, revision: r.revision }),
        onSuccess: () => client.invalidateQueries(),
    });
    return (
        <details className={cx('card')}>
            <summary>
                {r.notice.title} · 검수 v{r.revision}
            </summary>
            <EvidenceLink evidence={r.notice.evidence} />
            <p>
                {r.notice.region} · {r.notice.type} ·{' '}
                {r.notice.startDate ?? '시작일 확인 필요'} ~{' '}
                {r.notice.endDate ?? '종료일 확인 필요'}
            </p>
            <p>{r.notice.summary}</p>
            <h3>검토할 질문과 서류</h3>
            {r.notice.questions.map((q) => (
                <p key={q.id}>
                    {q.title} · {q.reason}
                </p>
            ))}
            {r.notice.documents.map((d) => (
                <p key={d}>{d}</p>
            ))}
            {!demoMode && (
                <DraftEditor review={r} onDirtyChange={setDraftDirty} />
            )}
            <p>기본 정보와 준비 안내를 함께 대조하고 각각 결과를 기록하세요.</p>
            <label>
                기본 정보
                <select
                    value={basic}
                    onChange={(e) =>
                        setBasic(e.target.value as Review['basic'])
                    }
                >
                    <option value="PASS">검증 통과 · 기본 정보 공개</option>
                    <option value="HOLD">보완 필요 · 양쪽 공개 보류</option>
                </select>
            </label>
            <label>
                준비 안내
                <select
                    value={guide}
                    onChange={(e) =>
                        setGuide(e.target.value as Review['guide'])
                    }
                >
                    <option value="PASS">검수 통과</option>
                    <option value="HOLD">보완 필요</option>
                    <option value="UNSUPPORTED">미지원</option>
                </select>
            </label>
            <label>
                검수 근거·보완 사유
                <textarea
                    value={reason}
                    onChange={(e) => setReason(e.target.value)}
                    maxLength={2000}
                />
            </label>
            <p>
                공개 결과:{' '}
                {basic === 'HOLD'
                    ? '전체 공개 보류'
                    : guide === 'PASS'
                      ? '기본 정보와 준비 안내 제공'
                      : '기본 정보만 공개'}
            </p>
            {save.error && <p role="alert">{save.error.message}</p>}
            {save.isSuccess && <p role="status">검수 결과를 저장했습니다.</p>}
            <Button
                disabled={!reason.trim() || save.isPending || draftDirty}
                onClick={() => save.mutate()}
            >
                검수 결과와 공개 상태 저장
            </Button>
            {draftDirty && (
                <p role="status">
                    편집 중인 원고를 먼저 저장하거나 닫은 뒤 검수해 주세요.
                </p>
            )}
        </details>
    );
}
export function AdminPage() {
    const [startPage, setStartPage] = useState(1);
    const client = useQueryClient();
    const q = useQuery({
        queryKey: ['admin', 'reviews'],
        queryFn: api.reviews,
    });
    const runs = useQuery({
        queryKey: ['admin', 'collections'],
        queryFn: api.collections,
    });
    const collect = useMutation({
        mutationFn: () => api.collect(startPage),
        onSuccess: () => client.invalidateQueries({ queryKey: ['admin'] }),
    });
    return (
        <>
            <Heading eyebrow="ADMIN · REVIEW DESK" title="공고 검수 작업실">
                수집과 공개를 구분하고 근거를 함께 확인합니다.
            </Heading>
            <Card>
                <h2>수집 실행</h2>
                <p>
                    LH 임대주택 목록을 수집합니다. 신규·변경 공고는 검수 대기로
                    저장됩니다.
                </p>
                <label>
                    수집 시작 페이지
                    <input
                        type="number"
                        min={1}
                        max={9999}
                        value={startPage}
                        onChange={(e) => setStartPage(Number(e.target.value))}
                    />
                </label>
                <Button
                    disabled={
                        collect.isPending ||
                        !Number.isInteger(startPage) ||
                        startPage < 1 ||
                        startPage > 9999
                    }
                    onClick={() => collect.mutate()}
                >
                    공고 수집 요청
                </Button>
                {collect.error && <p role="alert">{collect.error.message}</p>}
                <State error={runs.error} retry={() => void runs.refetch()} />
                {runs.data?.map((r) => (
                    <p key={r.id}>
                        {r.status} ·{' '}
                        {new Date(r.startedAt).toLocaleString('ko-KR', {
                            timeZone: 'Asia/Seoul',
                        })}
                        <br />
                        {r.message}
                    </p>
                ))}
                {runs.data?.length === 0 && <p>수집 실행 이력이 없습니다.</p>}
            </Card>
            <State
                loading={q.isPending}
                error={q.error}
                retry={() => void q.refetch()}
            />
            {q.data?.map((r) => (
                <ReviewEditor key={r.id + ':' + r.revision} review={r} />
            ))}
        </>
    );
}
