import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import {
    act,
    fireEvent,
    render,
    screen,
    waitFor,
} from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { call } from '../../shared/api/transport';
import { NoticeExtraction } from './NoticeExtraction';

vi.mock('../../shared/api/transport', () => ({ call: vi.fn() }));
const path = '/admin/reviews/notice-1/extractions';
const key = ['admin', 'extractions', 'notice-1'];
const source = {
    label: '추가 공고문',
    url: 'https://apply.lh.or.kr/lhapply/lhFile.do?fileid=100',
    status: 'DEFERRED',
    message: '첨부별 발췌로 확인해 주세요.',
    sha256: null as string | null,
    excerpts: [] as {
        category: string;
        text: string;
        location: string;
        url: string;
    }[],
};
const draft = {
    id: 'draft-1',
    revision: 4,
    status: 'PARTIAL',
    checkedAt: '2026-11-06T03:00:00Z',
    message: '자동 발췌는 원문 대조가 필요합니다.',
    sources: [source],
};
const clients: QueryClient[] = [];
beforeEach(() => {
    vi.stubGlobal(
        'ResizeObserver',
        class {
            observe = vi.fn();
            unobserve = vi.fn();
            disconnect = vi.fn();
        },
    );
    vi.mocked(call).mockReset();
    vi.mocked(call).mockResolvedValue([draft]);
    vi.spyOn(window, 'confirm').mockReturnValue(true);
});
afterEach(() => {
    clients.forEach((client) => client.clear());
    clients.length = 0;
    vi.restoreAllMocks();
    vi.unstubAllGlobals();
    vi.useRealTimers();
});
function setup(
    props: { active?: boolean; dirty?: boolean; revision?: number } = {},
    cached = false,
) {
    const client = new QueryClient({
        defaultOptions: {
            queries: { retry: false, gcTime: Infinity },
            mutations: { retry: false },
        },
    });
    clients.push(client);
    if (cached) client.setQueryData(key, [draft]);
    client.setQueryData(['notice', 'notice-1'], { version: 1 });
    const element = (next = props) => (
        <QueryClientProvider client={client}>
            <NoticeExtraction
                noticeId="notice-1"
                active={next.active ?? true}
                dirty={next.dirty ?? false}
                revision={next.revision ?? 4}
            />
        </QueryClientProvider>
    );
    const view = render(element());
    return {
        client,
        user: userEvent.setup(),
        rerender: (next: typeof props) => view.rerender(element(next)),
    };
}
async function openSource(user: ReturnType<typeof userEvent.setup>) {
    await user.click(await screen.findByText('추가 공고문 · 0개 발췌'));
}

describe('관리자 공식 원문 발췌', () => {
    it('접힌 검수는 조회하지 않고 캐시가 있어도 모든 변경 버튼을 비활성화한다', async () => {
        const { user } = setup({ active: false }, true);
        expect(call).not.toHaveBeenCalled();
        expect(
            screen.getByRole('button', { name: '본문·첨부 발췌' }),
        ).toBeDisabled();
        await openSource(user);
        expect(
            screen.getByRole('button', { name: '이 첨부 다시 발췌' }),
        ).toBeDisabled();
        expect(
            screen.getByRole('button', {
                name: '확인한 첨부 목록을 원고에 반영',
            }),
        ).toBeDisabled();
    });
    it('편집 중 원고가 있으면 발췌·개별 재발췌·원고 반영을 모두 막는다', async () => {
        const { user } = setup({ dirty: true });
        await openSource(user);
        for (const name of [
            '본문·첨부 발췌',
            '준비 안내 초안 검토',
            '이 첨부 다시 발췌',
            '확인한 첨부 목록을 원고에 반영',
        ]) {
            const button = screen.getByRole('button', { name });
            expect(button).toBeDisabled();
            await user.click(button);
        }
        expect(
            screen.getByText('편집 중인 원고를 먼저 저장하거나 닫아 주세요.'),
        ).toBeVisible();
        expect(call).toHaveBeenCalledExactlyOnceWith(path);
    });
    it.each(['QUEUED', 'RUNNING'])(
        '%s 상태는 재실행을 막고 3초마다 조회하다 완료되면 중단한다',
        async (status) => {
            vi.useFakeTimers();
            vi.mocked(call).mockResolvedValue([
                { ...draft, status, sources: [] },
            ]);
            const { rerender } = setup();
            await act(async () => {
                await vi.advanceTimersByTimeAsync(10);
            });
            expect(
                screen.getByRole('button', { name: '본문·첨부 발췌' }),
            ).toBeDisabled();
            expect(call).toHaveBeenCalledTimes(1);
            await act(async () => {
                await vi.advanceTimersByTimeAsync(3000);
            });
            expect(call).toHaveBeenCalledTimes(2);
            rerender({ active: false });
            await act(async () => {
                await vi.advanceTimersByTimeAsync(6000);
            });
            expect(call).toHaveBeenCalledTimes(2);
            vi.mocked(call).mockResolvedValue([{ ...draft, status: 'READY' }]);
            rerender({ active: true });
            await act(async () => {
                await vi.advanceTimersByTimeAsync(10);
            });
            expect(call).toHaveBeenCalledTimes(3);
            expect(
                screen.getByRole('button', { name: '본문·첨부 발췌' }),
            ).toBeEnabled();
            await act(async () => {
                await vi.advanceTimersByTimeAsync(6000);
            });
            expect(call).toHaveBeenCalledTimes(3);
        },
    );
    it('본문 발췌 요청 중에는 중복을 막고 서버의 대기 상태를 다시 조회한다', async () => {
        let finish!: (value: unknown) => void;
        vi.mocked(call).mockImplementation(async (_path, method) =>
            method === 'POST'
                ? new Promise((resolve) => {
                      finish = resolve;
                  })
                : [],
        );
        const { user } = setup();
        await waitFor(() => expect(call).toHaveBeenCalledWith(path));
        await user.click(
            screen.getByRole('button', { name: '본문·첨부 발췌' }),
        );
        expect(
            screen.getByRole('button', { name: '본문·첨부 발췌' }),
        ).toBeDisabled();
        await user.click(
            screen.getByRole('button', { name: '본문·첨부 발췌' }),
        );
        expect(
            vi
                .mocked(call)
                .mock.calls.filter(([, method]) => method === 'POST'),
        ).toHaveLength(1);
        vi.mocked(call).mockResolvedValue([{ ...draft, status: 'QUEUED' }]);
        await act(async () => {
            finish({ ...draft, status: 'QUEUED' });
        });
        expect(await screen.findByText('발췌 대기')).toBeVisible();
        expect(
            screen.getByRole('button', { name: '본문·첨부 발췌' }),
        ).toBeDisabled();
    });
    it('개별 첨부 발췌 후 저장된 목록을 다시 불러와 결과와 출처를 보여 준다', async () => {
        const updated = {
            ...source,
            status: 'EXTRACTED',
            sha256: 'a'.repeat(64),
            message: '원문 대조 필요',
            excerpts: [
                {
                    category: 'DOCUMENT',
                    text: '제출 서류 테스트',
                    location: '공고문 4쪽',
                    url: source.url,
                },
            ],
        };
        vi.mocked(call).mockImplementation(async (_path, method) => {
            if (method === 'POST') {
                vi.mocked(call).mockResolvedValue([
                    { ...draft, sources: [updated] },
                ]);
                return updated;
            }
            return [draft];
        });
        const { user } = setup();
        await openSource(user);
        await user.click(
            screen.getByRole('button', { name: '이 첨부 다시 발췌' }),
        );
        expect(call).toHaveBeenCalledWith(path + '/draft-1/file', 'POST', {
            url: source.url,
        });
        expect(await screen.findByText('추가 공고문 · 1개 발췌')).toBeVisible();
        expect(screen.getByText('제출 서류 테스트')).toBeVisible();
        expect(screen.getByText('서류 · 공고문 4쪽')).toBeVisible();
        expect(
            screen.getByRole('link', { name: '원문 직접 확인 ↗' }),
        ).toHaveAttribute('href', source.url);
        expect(
            screen.getByText(/자동 발췌는 공개 원고나 자격 판정이 아닙니다/),
        ).toBeVisible();
    });
    it('실패 사유를 보여 주고 조회 실패는 직접 재시도할 수 있다', async () => {
        vi.mocked(call).mockRejectedValueOnce(new Error('발췌 이력 연결 오류'));
        const { user } = setup();
        expect(await screen.findByRole('alert')).toHaveTextContent(
            '발췌 이력 연결 오류',
        );
        await user.click(
            screen.getByRole('button', { name: '발췌 이력 다시 불러오기' }),
        );
        await openSource(user);
        vi.mocked(call).mockRejectedValueOnce(new Error('첨부 다시 확인 필요'));
        await user.click(
            screen.getByRole('button', { name: '이 첨부 다시 발췌' }),
        );
        expect(await screen.findByRole('alert')).toHaveTextContent(
            '첨부 다시 확인 필요',
        );
        expect(screen.getByText('추가 공고문 · 0개 발췌')).toBeVisible();
        expect(
            screen.getByRole('button', { name: '이 첨부 다시 발췌' }),
        ).toBeEnabled();
    });
    it('이전 revision은 원고 반영과 파일 갱신을 막고 새 발췌만 허용한다', async () => {
        const { user } = setup({ revision: 5 });
        await openSource(user);
        expect(
            screen.getByRole('button', { name: '본문·첨부 발췌' }),
        ).toBeEnabled();
        expect(
            screen.getByRole('button', { name: '이 첨부 다시 발췌' }),
        ).toBeDisabled();
        expect(
            screen.getByRole('button', { name: '준비 안내 초안 검토' }),
        ).toBeDisabled();
        expect(
            screen.getByRole('button', {
                name: '확인한 첨부 목록을 원고에 반영',
            }),
        ).toBeDisabled();
    });
    it('첨부 반영 확인 후 현재 revision을 보내고 공고 캐시를 갱신한다', async () => {
        const { user, client } = setup();
        await screen.findByText('일부 확인 필요');
        const button = screen.getByRole('button', {
            name: '확인한 첨부 목록을 원고에 반영',
        });
        vi.mocked(window.confirm).mockReturnValueOnce(false);
        await user.click(button);
        expect(
            vi
                .mocked(call)
                .mock.calls.filter(([, method]) => method === 'POST'),
        ).toHaveLength(0);
        await user.click(button);
        expect(call).toHaveBeenCalledWith(
            path + '/draft-1/attachments',
            'POST',
            { revision: 4 },
        );
        await waitFor(() =>
            expect(
                client.getQueryState(['notice', 'notice-1'])?.isInvalidated,
            ).toBe(true),
        );
    });
    it('SUPERSEDED 발췌는 같은 revision으로 표시돼도 원고에 반영하지 못한다', async () => {
        vi.mocked(call).mockResolvedValue([{ ...draft, status: 'SUPERSEDED' }]);
        const { user } = setup();
        await openSource(user);
        expect(screen.getByText('이전 원고 기준')).toBeVisible();
        expect(
            screen.queryByRole('button', {
                name: '확인한 첨부 목록을 원고에 반영',
            }),
        ).not.toBeInTheDocument();
        expect(
            screen.getByRole('button', { name: '이 첨부 다시 발췌' }),
        ).toBeDisabled();
        fireEvent.click(
            screen.getByRole('button', { name: '이 첨부 다시 발췌' }),
        );
        expect(call).toHaveBeenCalledExactlyOnceWith(path);
    });
});
