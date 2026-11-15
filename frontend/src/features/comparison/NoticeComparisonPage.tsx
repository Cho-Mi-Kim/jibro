import { useRef, useState } from 'react';
import { Link, useSearchParams } from 'react-router';
import { useQueries, useQuery } from '@tanstack/react-query';
import { api } from '../housing/api';
import { Heading } from '../../shared/ui/ui';
import { comparisonApi } from './comparisonApi';
import { ComparisonContent } from './ComparisonContent';
import {
    comparisonSlots,
    selectComparisonNotice,
    selectedNotices,
    type ComparisonSlot,
} from './comparisonModel';
import s from './Comparison.module.css';

function LoadState({
    loading,
    error,
    retry,
}: {
    loading: boolean;
    error: Error | null;
    retry: () => void;
}) {
    if (loading)
        return (
            <p className={s.muted} role="status">
                공고를 불러오고 있어요…
            </p>
        );
    if (!error) return null;
    return (
        <div role="alert" className={s.item}>
            <p>
                공고를 불러오지 못했습니다. 다시 시도하거나 다른 공고를 선택해
                주세요.
            </p>
            <button type="button" className={s.control} onClick={retry}>
                다시 시도
            </button>
        </div>
    );
}

export function NoticeComparisonPage() {
    const [params, setParams] = useSearchParams();
    const selected = selectedNotices(params);
    const [editing, setEditing] = useState<ComparisonSlot | null>(null);
    const [search, setSearch] = useState('');
    const [page, setPage] = useState(0);
    const selectionButtons = useRef<
        Partial<Record<ComparisonSlot, HTMLButtonElement | null>>
    >({});
    const active =
        editing ?? comparisonSlots.find((slot) => !selected[slot]) ?? null;
    const queries = useQueries({
        queries: comparisonSlots.map((slot) => ({
            queryKey: [
                'notice-comparison',
                selected[slot] ?? `unselected-${slot}`,
            ],
            queryFn: () => comparisonApi.notice(selected[slot]!),
            enabled: !!selected[slot],
        })),
    });
    const catalog = useQuery({
        queryKey: ['compare-search', search, page],
        queryFn: () =>
            api.notices(
                new URLSearchParams({
                    keyword: search,
                    page: String(page),
                    size: '10',
                }).toString(),
            ),
        enabled: active !== null,
    });
    const options =
        catalog.data?.items.filter(
            (notice) => !Object.values(selected).includes(notice.id),
        ) ?? [];
    const entries = comparisonSlots.map((slot, index) => ({
        slot,
        data: queries[index].isSuccess ? queries[index].data : undefined,
    }));

    function edit(slot: ComparisonSlot) {
        setEditing(slot);
        setSearch('');
        setPage(0);
    }
    function pick(id?: string) {
        if (!active) return;
        selectionButtons.current[active]?.focus();
        setParams(selectComparisonNotice(params, active, id));
        setEditing(null);
        setSearch('');
        setPage(0);
    }

    return (
        <div className={s.page}>
            <Heading title="공고 비교" backTo="/notices">
                두 공고의 조건·서류·일정을 확인하세요. 신청 자격이나 선정
                가능성을 판단하지 않습니다.
            </Heading>
            <div className={s.pair}>
                {comparisonSlots.map((slot, index) => {
                    const query = queries[index];
                    const id = selected[slot];
                    return (
                        <section
                            className={s.selection}
                            key={slot}
                            aria-label={`${index + 1}번 비교 공고`}
                        >
                            <h2>{index + 1}번 공고</h2>
                            {id ? (
                                <>
                                    <LoadState
                                        loading={query.isPending}
                                        error={query.error}
                                        retry={() => void query.refetch()}
                                    />
                                    {query.isSuccess && (
                                        <>
                                            <p className={s.selectionTitle}>
                                                {query.data.notice.title}
                                            </p>
                                            <Link
                                                to={
                                                    '/notices/' +
                                                    encodeURIComponent(id)
                                                }
                                            >
                                                공고 상세 보기
                                            </Link>
                                        </>
                                    )}
                                </>
                            ) : (
                                <p className={s.muted}>
                                    비교할 공고를 선택하세요.
                                </p>
                            )}
                            <div className={s.actions}>
                                <button
                                    ref={(element) => {
                                        selectionButtons.current[slot] =
                                            element;
                                    }}
                                    type="button"
                                    className={s.control}
                                    onClick={() => edit(slot)}
                                    aria-expanded={active === slot}
                                    aria-controls="comparison-search"
                                    aria-label={`${index + 1}번 공고 ${id ? '변경' : '선택'}`}
                                >
                                    {id ? '공고 변경' : '공고 선택'}
                                </button>
                                {id && (
                                    <button
                                        type="button"
                                        className={s.control}
                                        aria-label={`${index + 1}번 공고 선택 해제`}
                                        onClick={() => {
                                            selectionButtons.current[
                                                slot
                                            ]?.focus();
                                            setParams(
                                                selectComparisonNotice(
                                                    params,
                                                    slot,
                                                ),
                                            );
                                            edit(slot);
                                        }}
                                    >
                                        선택 해제
                                    </button>
                                )}
                            </div>
                        </section>
                    );
                })}
            </div>
            {active && (
                <section
                    id="comparison-search"
                    className={s.search}
                    aria-label={`${active === 'a' ? '1' : '2'}번 공고 검색`}
                >
                    <label>
                        {active === 'a' ? '1' : '2'}번 공고 검색
                        <input
                            value={search}
                            onChange={(event) => {
                                setSearch(event.target.value);
                                setPage(0);
                            }}
                            placeholder="공고명 또는 기관"
                        />
                    </label>
                    <LoadState
                        loading={catalog.isPending}
                        error={catalog.error}
                        retry={() => void catalog.refetch()}
                    />
                    {!catalog.isError && (
                        <ul className={s.results}>
                            {options.map((notice) => (
                                <li key={notice.id}>
                                    <button
                                        type="button"
                                        className={s.result}
                                        onClick={() => pick(notice.id)}
                                    >
                                        <strong>{notice.title}</strong>
                                        <span>
                                            {notice.agency} · {notice.region} ·{' '}
                                            {notice.type}
                                        </span>
                                    </button>
                                </li>
                            ))}
                        </ul>
                    )}
                    {catalog.isSuccess && !options.length && (
                        <p className={s.muted}>
                            선택할 공고가 없습니다. 검색어를 바꾸거나 다음
                            페이지를 확인해 주세요.
                        </p>
                    )}
                    <div className={s.actions}>
                        <button
                            type="button"
                            className={s.control}
                            disabled={!page || catalog.isFetching}
                            onClick={() => setPage(page - 1)}
                        >
                            이전
                        </button>
                        <span className={s.muted}>{page + 1}페이지</span>
                        <button
                            type="button"
                            className={s.control}
                            disabled={
                                catalog.isFetching ||
                                !catalog.data ||
                                page + 1 >= catalog.data.totalPages
                            }
                            onClick={() => setPage(page + 1)}
                        >
                            다음
                        </button>
                        {selected.a && selected.b && (
                            <button
                                type="button"
                                className={s.control}
                                onClick={() => setEditing(null)}
                            >
                                변경 취소
                            </button>
                        )}
                    </div>
                </section>
            )}
            {entries.some((entry) => entry.data) && (
                <ComparisonContent entries={entries} />
            )}
            <p className={s.description}>
                공개된 자료만 비교합니다. 확인되지 않은 날짜는 ‘확인 필요’로
                표시하며, 최종 자격·제출 기준은 각 공고의 공식 근거를 확인해
                주세요.
            </p>
        </div>
    );
}
