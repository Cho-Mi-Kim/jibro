import { cx } from '../shared/styles/classes';
import { guideLabels } from '../shared/ui/helpers';
import { useState, type FormEvent } from 'react';
import { Link, useNavigate, useParams, useSearchParams } from 'react-router';
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { api } from '../features/housing/api';
import { useSession } from '../app/useSession';
import {
    Badge,
    Button,
    Card,
    EvidenceLink,
    Heading,
    State,
    Soon,
} from '../shared/ui/ui';
import type { Notice } from '../features/housing/types';
const statusLabels = {
    OPEN: '접수 중',
    UPCOMING: '접수 예정',
    CLOSED: '접수 종료',
};
export function NoticeCard({ notice: n }: { notice: Notice }) {
    return (
        <Card>
            <div className={cx('row')}>
                <Badge>{statusLabels[n.status]}</Badge>
                <span className={cx('muted')}>
                    {n.region} · {n.type}
                </span>
            </div>
            <Link className={cx('card-title')} to={'/notices/' + n.id}>
                {n.title} <span>↗</span>
            </Link>
            <p>{n.agency}</p>
            <p className={cx('muted')}>
                신청 기간 {n.startDate} ~ {n.endDate}
            </p>
            <div className={cx('row')}>
                <Badge>{guideLabels[n.guideState]}</Badge>
                <Soon label="즐겨찾기" />
            </div>
        </Card>
    );
}
export function NoticesPage() {
    const [params, setParams] = useSearchParams();
    const filters = useQuery({
        queryKey: ['notice-filters'],
        queryFn: api.filters,
    });
    const normalized = new URLSearchParams(params);
    const page = Math.max(0, Math.floor(Number(params.get('page')) || 0));
    normalized.set('page', String(page));
    normalized.set('size', '20');
    const query = useQuery({
        queryKey: ['notices', normalized.toString()],
        queryFn: () => api.notices(normalized.toString()),
    });
    function change(key: string, value: string, multiple = false) {
        const next = new URLSearchParams(params);
        if (multiple) {
            const values = next.getAll(key);
            next.delete(key);
            (values.includes(value)
                ? values.filter((v) => v !== value)
                : [...values, value]
            ).forEach((v) => next.append(key, v));
        } else if (value) next.set(key, value);
        else next.delete(key);
        next.set('page', '0');
        setParams(next);
    }
    function search(e: FormEvent<HTMLFormElement>) {
        e.preventDefault();
        change(
            'keyword',
            String(new FormData(e.currentTarget).get('keyword') ?? ''),
        );
    }
    return (
        <>
            <Heading title="공고 찾기">
                나의 다음 시작을 위한 주거지원 공고
            </Heading>
            <form className={cx('search')} onSubmit={search}>
                <input
                    key={params.get('keyword')}
                    name="keyword"
                    aria-label="공고 검색"
                    placeholder="공고명, 기관, 지역 검색"
                    defaultValue={params.get('keyword') ?? ''}
                />
                <Button>검색</Button>
            </form>
            <details className={cx('card')}>
                <summary>
                    검색 조건 {params.size > 0 ? '· 적용 중' : ''}
                </summary>
                {[
                    ['region', '지역', filters.data?.regions ?? []],
                    ['type', '지원 유형', filters.data?.types ?? []],
                    ['status', '접수 상태', ['OPEN', 'UPCOMING', 'CLOSED']],
                ].map(([key, label, values]) => (
                    <fieldset key={String(key)}>
                        <legend>{String(label)}</legend>
                        {(values as string[]).map((value) => (
                            <label className={cx('chip')} key={value}>
                                <input
                                    type="checkbox"
                                    checked={params
                                        .getAll(String(key))
                                        .includes(value)}
                                    onChange={() =>
                                        change(String(key), value, true)
                                    }
                                />
                                {statusLabels[
                                    value as keyof typeof statusLabels
                                ] ?? value}
                            </label>
                        ))}
                    </fieldset>
                ))}
                <button
                    className={cx('text-button')}
                    onClick={() => setParams({})}
                >
                    필터 초기화
                </button>
            </details>
            <div className={cx('row')}>
                <span>{query.data?.totalElements ?? '—'}개 공고</span>
                <select
                    aria-label="공고 정렬"
                    value={params.get('sort') ?? 'deadline'}
                    onChange={(e) => change('sort', e.target.value)}
                >
                    <option value="deadline">마감 가까운 순</option>
                    <option value="newest">최신순</option>
                </select>
            </div>
            <State
                loading={query.isPending}
                error={query.error}
                retry={() => void query.refetch()}
            />
            {query.data?.items.map((n) => (
                <NoticeCard key={n.id} notice={n} />
            ))}
            {query.data?.items.length === 0 && (
                <Card>
                    <h2>조건에 맞는 공고가 없어요</h2>
                    <Button onClick={() => setParams({})}>
                        검색 조건 초기화
                    </Button>
                </Card>
            )}
            <div className={cx('row')}>
                <Button
                    disabled={page === 0}
                    onClick={() => {
                        normalized.set('page', String(page - 1));
                        setParams(normalized);
                    }}
                >
                    이전
                </Button>
                <span>
                    {page + 1} / {Math.max(1, query.data?.totalPages ?? 1)}
                </span>
                <Button
                    disabled={!query.data || page + 1 >= query.data.totalPages}
                    onClick={() => {
                        normalized.set('page', String(page + 1));
                        setParams(normalized);
                    }}
                >
                    다음
                </Button>
            </div>
        </>
    );
}
export function NoticeDetailPage() {
    const { noticeId = '' } = useParams();
    const session = useSession();
    const navigate = useNavigate();
    const client = useQueryClient();
    const [tab, setTab] = useState('요약');
    const query = useQuery({
        queryKey: ['notice', noticeId],
        queryFn: () => api.notice(noticeId),
    });
    const start = useMutation({
        mutationFn: () => api.start(noticeId, query.data!.version),
        onSuccess: (p) => {
            void client.invalidateQueries({ queryKey: ['preparations'] });
            navigate('/preparations/' + p.id + '/conditions/edit');
        },
    });
    const n = query.data;
    return (
        <>
            <Link to="/notices">← 공고 목록</Link>
            <State
                loading={query.isPending}
                error={query.error}
                retry={() => void query.refetch()}
            />
            {n && (
                <>
                    <Heading eyebrow={n.agency} title={n.title}>
                        {n.region} · {n.type}
                    </Heading>
                    <div className={cx('row')}>
                        <Badge>{statusLabels[n.status]}</Badge>
                        <Badge>{guideLabels[n.guideState]}</Badge>
                    </div>
                    <Card>
                        <p>{n.summary}</p>
                        <p>
                            공식 신청 기간
                            <br />
                            <strong>
                                {n.startDate} ~ {n.endDate}
                            </strong>
                        </p>
                        <EvidenceLink evidence={n.evidence} />
                        <p>공고일 {n.publishedDate}</p>
                        {n.attachments.map((attachment, index) => (
                            <EvidenceLink key={index} evidence={attachment} />
                        ))}
                        <small>
                            기본 정보 v{n.version} · 준비 안내{' '}
                            {n.guideVersion ? 'v' + n.guideVersion : '검수 전'}
                        </small>
                    </Card>
                    <div
                        className={cx('tabs')}
                        role="tablist"
                        aria-label="공고 정보"
                    >
                        {['요약', '조건', '서류', '일정'].map((x) => (
                            <button
                                role="tab"
                                aria-selected={x === tab}
                                key={x}
                                onClick={() => setTab(x)}
                            >
                                {x}
                            </button>
                        ))}
                    </div>
                    <Card>
                        {tab === '요약' ? (
                            <p>{n.summary}</p>
                        ) : tab === '일정' ? (
                            <p>
                                신청 기간 {n.startDate} ~ {n.endDate}
                            </p>
                        ) : n.guideState !== 'AVAILABLE' ? (
                            <p>
                                {guideLabels[n.guideState]}입니다. 검수되지 않은
                                개인별 안내는 제공하지 않습니다.
                            </p>
                        ) : tab === '조건' ? (
                            n.questions.map((q) => (
                                <div key={q.id}>
                                    <h3>{q.title}</h3>
                                    <p>{q.reason}</p>
                                    <EvidenceLink evidence={q.evidence} />
                                </div>
                            ))
                        ) : (
                            n.documents.map((d) => <p key={d}>{d}</p>)
                        )}
                    </Card>
                    {start.error && <p role="alert">{start.error.message}</p>}
                    {n.canStart && (
                        <Button
                            disabled={start.isPending || session.loading}
                            onClick={() =>
                                session.user
                                    ? start.mutate()
                                    : navigate(
                                          '/login?returnTo=' +
                                              encodeURIComponent(
                                                  '/notices/' + noticeId,
                                              ),
                                      )
                            }
                        >
                            {start.isPending
                                ? '준비 건 만드는 중…'
                                : '내 조건 확인하기'}
                        </Button>
                    )}
                </>
            )}
        </>
    );
}
