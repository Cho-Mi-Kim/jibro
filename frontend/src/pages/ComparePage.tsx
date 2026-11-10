import { useState } from 'react';
import { Link, useParams, useSearchParams } from 'react-router';
import { useQuery } from '@tanstack/react-query';
import { api } from '../features/housing/api';
import { memberApi } from '../features/member/memberApi';
import { useNotice, displayDate } from '../shared/ui/housingHelpers';
import { guideLabels } from '../shared/ui/helpers';
import { Heading, State } from '../shared/ui/ui';
import s from '../features/member/Controls.module.css';
const fields: Record<string, string> = {
    title: '공고명',
    agency: '운영기관',
    region: '지역',
    type: '지원 유형',
    publishedDate: '공고일',
    startDate: '접수 시작',
    endDate: '접수 마감',
    summary: '공고 요약',
    source: '공식 근거',
    guidance: '안내 상태',
    questions: '질문',
    documents: '제출서류',
    schedules: '일정',
};
function NoticeColumn({ id }: { id: string }) {
    const q = useNotice(id);
    return (
        <section className={s.section}>
            <State
                loading={q.isPending}
                error={q.error}
                retry={() => void q.refetch()}
            />
            {q.data && (
                <>
                    <h2>{q.data.title}</h2>
                    <dl className={s.difference}>
                        {Object.entries({
                            운영기관: q.data.agency,
                            지역: q.data.region,
                            '지원 유형': q.data.type,
                            '접수 기간':
                                displayDate(q.data.startDate) +
                                ' ~ ' +
                                displayDate(q.data.endDate),
                            '준비 안내': guideLabels[q.data.guideState],
                            '안내 버전': q.data.version,
                            요약: q.data.summary,
                        }).map(([k, v]) => (
                            <div key={k}>
                                <dt>{k}</dt>
                                <dd>{v}</dd>
                            </div>
                        ))}
                    </dl>
                    <Link to={'/notices/' + id}>공고 상세 보기</Link>
                </>
            )}
        </section>
    );
}
export function ComparePage() {
    const [params, set] = useSearchParams(),
        [search, setSearch] = useState(''),
        [page, setPage] = useState(0);
    const first = params.get('a'),
        second = params.get('b');
    const q = useQuery({
        queryKey: ['compare-search', search, page],
        queryFn: () =>
            api.notices(
                new URLSearchParams({
                    keyword: search,
                    page: String(page),
                    size: '10',
                }).toString(),
            ),
    });
    function pick(id: string) {
        const next = new URLSearchParams(params);
        if (!first) next.set('a', id);
        else next.set('b', id);
        set(next);
    }
    return (
        <>
            <Heading title="공고 비교" backTo="/notices">
                공개된 두 공고의 항목을 나란히 확인하세요.
            </Heading>
            <div className={s.actions}>
                {[first, second].map(
                    (id, i) =>
                        id && (
                            <button
                                className={s.link}
                                key={i}
                                onClick={() => {
                                    const next = new URLSearchParams(params);
                                    next.delete(i ? 'b' : 'a');
                                    set(next);
                                }}
                            >
                                {i + 1}번 공고 선택 해제
                            </button>
                        ),
                )}
            </div>
            {(!first || !second) && (
                <section className={s.section}>
                    <label>
                        비교할 공고 검색
                        <input
                            value={search}
                            onChange={(e) => {
                                setSearch(e.target.value);
                                setPage(0);
                            }}
                            placeholder="공고명 또는 기관"
                        />
                    </label>
                    <State
                        loading={q.isPending}
                        error={q.error}
                        retry={() => void q.refetch()}
                    />
                    <ul className={s.list}>
                        {q.data?.items
                            .filter((n) => n.id !== first && n.id !== second)
                            .map((n) => (
                                <li key={n.id}>
                                    <button
                                        className={s.link}
                                        onClick={() => pick(n.id)}
                                    >
                                        {n.title}
                                    </button>
                                </li>
                            ))}
                    </ul>
                    {q.isSuccess &&
                        !q.data.items.some(
                            (n) => n.id !== first && n.id !== second,
                        ) && <p>선택할 공고가 없습니다.</p>}
                    <div className={s.actions}>
                        <button
                            className={s.link}
                            disabled={!page}
                            onClick={() => setPage(page - 1)}
                        >
                            이전
                        </button>
                        <button
                            className={s.link}
                            disabled={!q.data || page + 1 >= q.data.totalPages}
                            onClick={() => setPage(page + 1)}
                        >
                            다음
                        </button>
                    </div>
                </section>
            )}
            <div className={s.compare}>
                {first && <NoticeColumn id={first} />}{' '}
                {second && second !== first && <NoticeColumn id={second} />}
            </div>
        </>
    );
}
function value(v: string | string[] | null | undefined) {
    return Array.isArray(v) ? v.join('\n') || '없음' : (v ?? '확인 필요');
}
export function VersionsPage() {
    const { noticeId = '' } = useParams(),
        [chosen, setChosen] = useState('');
    const q = useQuery({
        queryKey: ['editions', noticeId],
        queryFn: () => memberApi.editions(noticeId),
    });
    const latest = q.data?.[0],
        previous = q.data?.slice(1),
        old =
            previous?.find((x) => x.version + '.' + x.revision === chosen) ??
            previous?.[0];
    const changes =
        old && latest
            ? Object.keys(fields).filter(
                  (k) =>
                      k in latest.facts &&
                      k in old.facts &&
                      value(old.facts[k]) !== value(latest.facts[k]),
              )
            : [];
    return (
        <>
            <Heading
                title="수정 공고 전후 비교"
                backTo={'/notices/' + noticeId}
            >
                공개된 이력에서 변경된 항목만 표시합니다.
            </Heading>
            <State
                loading={q.isPending}
                error={q.error}
                retry={() => void q.refetch()}
            />
            {latest && (
                <section className={s.section}>
                    <h2>{value(latest.facts.title)}</h2>
                    <p>
                        현재 안내 v{latest.version} · 검수 기록{' '}
                        {latest.revision}
                    </p>
                    {old ? (
                        <>
                            <label>
                                이전 공개본
                                <select
                                    value={old.version + '.' + old.revision}
                                    onChange={(e) => setChosen(e.target.value)}
                                >
                                    {previous?.map((x) => (
                                        <option
                                            key={x.version + '.' + x.revision}
                                            value={x.version + '.' + x.revision}
                                        >
                                            안내 v{x.version} · 검수 기록{' '}
                                            {x.revision}
                                        </option>
                                    ))}
                                </select>
                            </label>
                            {!changes.length && (
                                <p>선택한 두 공개본의 표시 항목은 같습니다.</p>
                            )}
                            {changes.map((k) => (
                                <div key={k}>
                                    <h3>{fields[k]}</h3>
                                    <dl className={s.difference}>
                                        <dt>이전</dt>
                                        <dd>{value(old.facts[k])}</dd>
                                        <dt>현재</dt>
                                        <dd>{value(latest.facts[k])}</dd>
                                    </dl>
                                </div>
                            ))}
                        </>
                    ) : (
                        <p>
                            비교할 이전 공개본이 없습니다. 이력 수집을 시작한
                            이후의 검수본을 비교할 수 있습니다.
                        </p>
                    )}
                    <Link to={'/notices/' + noticeId}>현재 공식 근거 보기</Link>
                </section>
            )}
        </>
    );
}
