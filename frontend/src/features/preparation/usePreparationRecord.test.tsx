import { afterEach, expect, it, vi } from 'vitest';
import { act, render, screen, waitFor } from '@testing-library/react';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { Session } from '../auth/useSession';
import { api } from '../housing/api';
import type { Preparation } from '../housing/types';
import { usePreparationRecord } from './usePreparationRecord';

const record: Preparation = {
    id: 'saved',
    noticeId: 'notice',
    title: '내 준비',
    version: 1,
    revision: 1,
    guideState: 'AVAILABLE',
    questions: [],
    answers: { method: 'VISIT' },
    documents: [],
    schedules: [],
    progress: 20,
    houseStage: 1,
    warnings: [],
    nextAction: { label: '', reason: '', path: '' },
};

afterEach(() => vi.restoreAllMocks());

function RecordView({ expectedRevision }: { expectedRevision?: number }) {
    const query = usePreparationRecord('saved', expectedRevision);
    return (
        <>
            <output aria-label="준비 답변">
                {query.data
                    ? `${query.data.revision}:${query.data.answers.method}`
                    : '없음'}
            </output>
            <output aria-label="조회 상태">
                {query.isFetching
                    ? '조회 중'
                    : query.isError
                      ? '조회 실패'
                      : '조회 완료'}
            </output>
        </>
    );
}

function view(
    client: QueryClient,
    userId: string | null,
    expectedRevision?: number,
) {
    return (
        <QueryClientProvider client={client}>
            <Session.Provider
                value={{
                    user: userId
                        ? {
                              id: userId,
                              name: '사용자',
                              email: '',
                              role: 'USER',
                          }
                        : null,
                    loading: false,
                    error: null,
                    refresh: async () => {},
                }}
            >
                <RecordView expectedRevision={expectedRevision} />
            </Session.Provider>
        </QueryClientProvider>
    );
}

function client() {
    return new QueryClient({
        defaultOptions: {
            queries: {
                retry: false,
                gcTime: Infinity,
                refetchOnWindowFocus: false,
            },
        },
    });
}

it('15초 내의 공유 캐시도 목록 revision이 더 높으면 갱신하고 같은 ID의 다음 revision도 가져온다', async () => {
    const cache = client();
    cache.setQueryData(['preparation', 'owner', 'saved'], record);
    const request = vi
        .spyOn(api, 'preparation')
        .mockResolvedValueOnce({
            ...record,
            revision: 2,
            answers: { method: 'ONLINE' },
        })
        .mockResolvedValueOnce({
            ...record,
            revision: 3,
            answers: { method: 'POSTAL' },
        });
    const page = render(view(cache, 'owner'));
    expect(screen.getByLabelText('준비 답변')).toHaveTextContent('1:VISIT');
    expect(request).not.toHaveBeenCalled();
    page.rerender(view(cache, 'owner', 2));
    await waitFor(() =>
        expect(screen.getByLabelText('준비 답변')).toHaveTextContent(
            '2:ONLINE',
        ),
    );
    page.rerender(view(cache, 'owner', 3));
    await waitFor(() =>
        expect(screen.getByLabelText('준비 답변')).toHaveTextContent(
            '3:POSTAL',
        ),
    );
    expect(cache.getQueryData(['preparation', 'owner', 'saved'])).toMatchObject(
        { revision: 3, answers: { method: 'POSTAL' } },
    );
    expect(
        cache
            .getQueryCache()
            .getAll()
            .map((query) => query.queryKey),
    ).toEqual([['preparation', 'owner', 'saved']]);
});

it.each(['error', 'old-response'] as const)(
    '재조회 %s 뒤에도 같은 요청을 무한 반복하지 않고 새 목록 revision이면 다시 확인한다',
    async (response) => {
        const cache = client();
        cache.setQueryData(['preparation', 'owner', 'saved'], record);
        const request = vi.spyOn(api, 'preparation');
        if (response === 'error')
            request.mockRejectedValue(new Error('연결 실패'));
        else request.mockResolvedValue(record);
        const page = render(view(cache, 'owner', 2));
        await waitFor(() =>
            expect(screen.getByLabelText('조회 상태')).toHaveTextContent(
                response === 'error' ? '조회 실패' : '조회 완료',
            ),
        );
        page.rerender(view(cache, 'owner', 2));
        await act(async () => {
            await Promise.resolve();
        });
        expect(request).toHaveBeenCalledTimes(1);
        expect(screen.getByLabelText('준비 답변')).toHaveTextContent('1:VISIT');
        request.mockResolvedValue({
            ...record,
            revision: 3,
            answers: { method: 'ONLINE' },
        });
        page.rerender(view(cache, 'owner', 3));
        await waitFor(() =>
            expect(screen.getByLabelText('준비 답변')).toHaveTextContent(
                '3:ONLINE',
            ),
        );
    },
);

it('같은 준비 ID라도 계정별 캐시와 재조회 시도를 분리하고 로그아웃 때 이전 답변을 노출하지 않는다', async () => {
    const cache = client();
    cache.setQueryData(['preparation', 'owner-a', 'saved'], {
        ...record,
        revision: 2,
        answers: { method: 'A' },
    });
    cache.setQueryData(['preparation', 'owner-b', 'saved'], {
        ...record,
        answers: { method: 'B' },
    });
    let complete!: (value: Preparation) => void;
    const request = vi.spyOn(api, 'preparation').mockReturnValue(
        new Promise((resolve) => {
            complete = resolve;
        }),
    );
    const page = render(view(cache, 'owner-a', 2));
    expect(screen.getByLabelText('준비 답변')).toHaveTextContent('2:A');
    expect(request).not.toHaveBeenCalled();
    page.rerender(view(cache, 'owner-b', 2));
    expect(screen.getByLabelText('준비 답변')).toHaveTextContent('1:B');
    await act(async () => {
        complete({ ...record, revision: 2, answers: { method: 'B2' } });
    });
    await waitFor(() =>
        expect(screen.getByLabelText('준비 답변')).toHaveTextContent('2:B2'),
    );
    expect(
        cache.getQueryData(['preparation', 'owner-a', 'saved']),
    ).toMatchObject({ answers: { method: 'A' } });
    page.rerender(view(cache, null, 2));
    expect(screen.getByLabelText('준비 답변')).toHaveTextContent('없음');
    expect(request).toHaveBeenCalledTimes(1);
});
