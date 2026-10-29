import { cx } from '../shared/styles/classes';
import { useState } from 'react';
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { api } from '../features/housing/api';
import type { Review } from '../features/housing/types';
import { Button, Card, EvidenceLink, Heading, State } from '../shared/ui/ui';
function ReviewEditor({ review: r }: { review: Review }) {
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
                disabled={!reason.trim() || save.isPending}
                onClick={() => save.mutate()}
            >
                검수 결과와 공개 상태 저장
            </Button>
        </details>
    );
}
export function AdminPage() {
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
        mutationFn: api.collect,
        onSuccess: () =>
            client.invalidateQueries({ queryKey: ['admin', 'collections'] }),
    });
    return (
        <>
            <Heading eyebrow="ADMIN · REVIEW DESK" title="공고 검수 작업실">
                수집과 공개를 구분하고 근거를 함께 확인합니다.
            </Heading>
            <Card>
                <h2>수집 실행</h2>
                <Button
                    disabled={collect.isPending}
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
