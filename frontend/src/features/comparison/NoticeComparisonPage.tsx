import { useRef, useState } from 'react';
import { Link, useLocation, useSearchParams } from 'react-router';
import { useQueries, useQuery } from '@tanstack/react-query';
import {
    useNoticeDetailReturn,
    useNoticeListPosition,
} from '../housing/NoticeListVisit';
import { noticeListTitle } from '../housing/noticeDisplay';
import { AgencyHousingSummary } from '../housing/AgencyMark';
import type { NoticeListItem } from '../housing/types';
import { noticeAgency } from '../housing/noticeAgency';
import { api } from '../housing/api';
import { Heading } from '../../shared/ui/ui';
import { comparisonApi } from './comparisonApi';
import { ComparisonContent } from './ComparisonContent';
import { useComparisonPreparations } from './useComparisonPreparations';
import {
    comparisonSlots,
    selectComparisonNotice,
    selectedNotices,
    personalizeComparison,
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

const pageSize = 10;

export function NoticeComparisonPage({
    savedNotices,
    backTo,
}: {
    savedNotices?: NoticeListItem[];
    backTo?: string;
}) {
    const [params, setParams] = useSearchParams();
    const location = useLocation();
    const { state } = location;
    const detailReturn = useNoticeDetailReturn();
    const listSearch =
        typeof state?.noticeSearch === 'string' ? state.noticeSearch : '';
    const selected = selectedNotices(params);
    if (savedNotices) {
        for (const slot of comparisonSlots) {
            if (!savedNotices.some((notice) => notice.id === selected[slot]))
                selected[slot] = undefined;
        }
    }
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
    const preparations = useComparisonPreparations([
        queries[0].isSuccess ? queries[0].data : undefined,
        queries[1].isSuccess ? queries[1].data : undefined,
    ]);
    const catalog = useQuery({
        queryKey: ['compare-search', search, page],
        queryFn: () =>
            api.notices(
                new URLSearchParams({
                    keyword: search,
                    page: String(page),
                    size: String(pageSize),
                }).toString(),
            ),
        enabled: active !== null && !savedNotices,
    });
    const available = (notice: NoticeListItem) =>
        notice.id !== selected.a && notice.id !== selected.b;
    const term = search.trim().toLocaleLowerCase();
    const savedMatches = savedNotices?.filter((notice) => {
        if (!available(notice)) return false;
        const agency = noticeAgency(notice.agency);
        return [
            notice.title,
            noticeListTitle(notice.title),
            agency.name,
            agency.fullName,
        ].some((value) => value?.toLocaleLowerCase().includes(term));
    });
    const totalPages = savedMatches
        ? Math.ceil(savedMatches.length / pageSize)
        : (catalog.data?.totalPages ?? 0);
    const currentPage = savedMatches
        ? Math.min(page, Math.max(0, totalPages - 1))
        : page;
    const options = savedMatches
        ? savedMatches.slice(
              currentPage * pageSize,
              (currentPage + 1) * pageSize,
          )
        : (catalog.data?.items.filter(available) ?? []);
    const fetching = !savedNotices && catalog.isFetching;
    const entries = comparisonSlots.map((slot, index) => {
        const data = queries[index].isSuccess ? queries[index].data : undefined;
        const preparation = preparations[index].preparation;
        return {
            slot,
            data:
                data && preparation
                    ? personalizeComparison(data, preparation)
                    : data,
        };
    });
    const ready =
        comparisonSlots.every(
            (slot, index) => !selected[slot] || !queries[index].isPending,
        ) &&
        preparations.every((item) => item.status !== 'loading') &&
        (!active || !!savedNotices || !catalog.isPending);
    const { list, position, remember, clear } =
        useNoticeListPosition<HTMLDivElement>(ready);
    const listState = {
        noticeListKey: state?.noticeListKey,
        noticeSearch: listSearch,
        detailReturn: state?.detailReturn,
    };

    function edit(slot: ComparisonSlot) {
        setEditing(slot);
        setSearch('');
        setPage(0);
    }
    function pick(id?: string) {
        if (!active) return;
        selectionButtons.current[active]?.focus();
        setSelection(active, id);
        setEditing(null);
        setSearch('');
        setPage(0);
    }
    function setSelection(slot: ComparisonSlot, id?: string) {
        clear();
        setParams(selectComparisonNotice(params, slot, id), {
            state: listState,
        });
    }

    return (
        <div
            ref={list}
            className={s.page}
            data-restored-list={position ? true : undefined}
        >
            <Heading
                title={savedNotices ? '관심 공고 비교' : '공고 비교'}
                backTo={detailReturn?.to ?? backTo ?? '/notices' + listSearch}
                backState={detailReturn?.state ?? listState}
            >
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
                                            <p
                                                className={s.selectionTitle}
                                                title={query.data.notice.title}
                                            >
                                                {noticeListTitle(
                                                    query.data.notice.title,
                                                )}
                                            </p>
                                            <p
                                                className={s.muted}
                                                role="status"
                                            >
                                                {preparations[index].status ===
                                                'personal'
                                                    ? '내 답변에 맞는 안내 · 공통·확인 필요 항목 포함'
                                                    : preparations[index]
                                                            .status ===
                                                        'loading'
                                                      ? '저장된 답변을 확인하고 있어요…'
                                                      : preparations[index]
                                                              .status ===
                                                          'outdated'
                                                        ? '안내가 바뀌어 전체 내용을 표시해요. 내 준비에서 다시 확인해 주세요.'
                                                        : preparations[index]
                                                                .status ===
                                                            'error'
                                                          ? '저장된 답변을 불러오지 못해 전체 안내를 표시해요.'
                                                          : '전체 안내'}
                                            </p>
                                            {preparations[index].status ===
                                                'error' && (
                                                <button
                                                    type="button"
                                                    className={s.control}
                                                    onClick={() =>
                                                        void preparations[
                                                            index
                                                        ].retry?.()
                                                    }
                                                >
                                                    답변 다시 불러오기
                                                </button>
                                            )}
                                            <Link
                                                onClick={() => remember()}
                                                state={{
                                                    comparisonReturn: {
                                                        pathname:
                                                            location.pathname,
                                                        search: location.search,
                                                    },
                                                    comparisonKey: location.key,
                                                    ...listState,
                                                }}
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
                                            setSelection(slot);
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
                    {!savedNotices && (
                        <LoadState
                            loading={catalog.isPending}
                            error={catalog.error}
                            retry={() => void catalog.refetch()}
                        />
                    )}
                    {(savedNotices || !catalog.isError) && (
                        <ul className={s.results}>
                            {options.map((notice) => (
                                <li key={notice.id}>
                                    <button
                                        type="button"
                                        className={s.result}
                                        onClick={() => pick(notice.id)}
                                    >
                                        <strong title={notice.title}>
                                            {noticeListTitle(notice.title)}
                                        </strong>
                                        <span>
                                            <AgencyHousingSummary
                                                notice={notice}
                                            />
                                        </span>
                                    </button>
                                </li>
                            ))}
                        </ul>
                    )}
                    {(savedNotices || catalog.isSuccess) && !options.length && (
                        <p className={s.muted}>
                            {savedNotices
                                ? term
                                    ? '검색어와 일치하는 관심 공고가 없습니다.'
                                    : '선택할 관심 공고가 없습니다. 공고 목록에서 비교할 공고를 더 저장해 주세요.'
                                : '선택할 공고가 없습니다. 검색어를 바꾸거나 다음 페이지를 확인해 주세요.'}
                        </p>
                    )}
                    <div className={s.actions}>
                        {(!savedNotices || totalPages > 1) && (
                            <>
                                <button
                                    type="button"
                                    className={s.control}
                                    disabled={!currentPage || fetching}
                                    onClick={() => setPage(currentPage - 1)}
                                >
                                    이전
                                </button>
                                <span className={s.muted}>
                                    {currentPage + 1}페이지
                                </span>
                                <button
                                    type="button"
                                    className={s.control}
                                    disabled={
                                        fetching ||
                                        currentPage + 1 >= totalPages
                                    }
                                    onClick={() => setPage(currentPage + 1)}
                                >
                                    다음
                                </button>
                            </>
                        )}
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
                저장한 답변이 있는 공고는 해당 조건·서류·일정과 공통 안내를
                비교합니다. 미답변·확인 필요 항목도 함께 보여요. 확인되지 않은
                날짜는 ‘확인 필요’로 표시하며, 최종 자격·제출 기준은 각 공고의
                공식 근거를 확인해 주세요.
            </p>
        </div>
    );
}
