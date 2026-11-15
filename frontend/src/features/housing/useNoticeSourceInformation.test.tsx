import type { ReactNode } from 'react';
import { act, renderHook, waitFor } from '@testing-library/react';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { beforeEach, expect, it, vi } from 'vitest';
import {
    noticeSourceInformation,
    type NoticeSourceInformationData,
} from './noticeSourceInformationApi';
import { useNoticeSourceInformation } from './useNoticeSourceInformation';
import type { Notice } from './types';

vi.mock('./noticeSourceInformationApi', () => ({
    noticeSourceInformation: vi.fn(),
}));

beforeEach(() => vi.resetAllMocks());

const notice = {
    id: 'first',
    version: 1,
    guideState: 'REVIEWING',
    canStart: false,
} as Notice;

function response(noticeId = 'first'): NoticeSourceInformationData {
    return {
        noticeId,
        checkedAt: '2026-11-09T01:00:00Z',
        state: 'PENDING',
        sections: [],
        pendingAreas: ['CONDITION', 'DOCUMENT', 'SCHEDULE'],
    };
}

function deferred() {
    let resolve!: (value: NoticeSourceInformationData) => void;
    const promise = new Promise<NoticeSourceInformationData>((done) => {
        resolve = done;
    });
    return { promise, resolve };
}

function mount(current = notice) {
    const client = new QueryClient({
        defaultOptions: { queries: { retry: false } },
    });
    const wrapper = ({ children }: { children: ReactNode }) => (
        <QueryClientProvider client={client}>{children}</QueryClientProvider>
    );
    return renderHook((value) => useNoticeSourceInformation(value), {
        initialProps: current,
        wrapper,
    });
}

it.each(['AVAILABLE', 'UNSUPPORTED'] as const)(
    '%s 안내에서는 원문 미리보기를 요청하지 않는다',
    (guideState) => {
        const { result } = mount({ ...notice, guideState });
        expect(noticeSourceInformation).not.toHaveBeenCalled();
        expect(result.current.data).toBeUndefined();
        expect(result.current.loading).toBe(false);
    },
);

it('다른 공고로 이동한 뒤 도착한 이전 응답은 표시하지 않는다', async () => {
    const first = deferred();
    const second = deferred();
    vi.mocked(noticeSourceInformation).mockImplementation((id) =>
        id === 'first' ? first.promise : second.promise,
    );
    const { result, rerender } = mount();
    expect(result.current.loading).toBe(true);
    rerender({ ...notice, id: 'second' });
    expect(result.current.data).toBeUndefined();
    await act(async () => second.resolve(response('second')));
    await waitFor(() => expect(result.current.data?.noticeId).toBe('second'));
    await act(async () => first.resolve(response('first')));
    expect(result.current.data?.noticeId).toBe('second');
});

it('같은 공고를 재조회하는 동안 이전 발췌를 숨긴다', async () => {
    const next = deferred();
    vi.mocked(noticeSourceInformation)
        .mockResolvedValueOnce(response())
        .mockReturnValueOnce(next.promise);
    const { result } = mount();
    await waitFor(() => expect(result.current.data).toEqual(response()));
    act(() => result.current.retry());
    await waitFor(() => expect(result.current.loading).toBe(true));
    expect(result.current.data).toBeUndefined();
    const latest = { ...response(), checkedAt: '2026-11-09T02:00:00Z' };
    await act(async () => next.resolve(latest));
    await waitFor(() => expect(result.current.data).toEqual(latest));
});

it('버전이나 검수 상태 변경 시 이전 원문 캐시를 재사용하지 않는다', async () => {
    const next = deferred();
    vi.mocked(noticeSourceInformation)
        .mockResolvedValueOnce(response())
        .mockReturnValueOnce(next.promise);
    const { result, rerender } = mount();
    await waitFor(() => expect(result.current.data).toBeDefined());
    rerender({ ...notice, version: 2, guideState: 'CHANGE_REVIEW' });
    expect(result.current.data).toBeUndefined();
    await waitFor(() =>
        expect(noticeSourceInformation).toHaveBeenCalledTimes(2),
    );
    await act(async () => next.resolve(response()));
    await waitFor(() => expect(result.current.data).toBeDefined());
});

it('요청한 공고와 다른 원문 응답을 거절한다', async () => {
    vi.mocked(noticeSourceInformation).mockResolvedValue(response('other'));
    const { result } = mount();
    await waitFor(() => expect(result.current.failed).toBe(true));
    expect(result.current.data).toBeUndefined();
});
