import { afterEach, beforeEach, expect, it, vi } from 'vitest';
import { act, render, screen, waitFor } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { AttachmentChecks } from './AttachmentChecks';
import {
    attachmentChecksApi as api,
    type AttachmentCheckRun,
} from './attachmentChecksApi';

const completed: AttachmentCheckRun = {
    id: 'run-1',
    status: 'SUCCEEDED',
    startedAt: '2026-11-06T03:00:00Z',
    finishedAt: '2026-11-06T03:00:04Z',
    message: '첨부 1개를 확인했습니다.',
    results: [
        {
            label: '수정 모집공고문',
            url: 'https://apply.lh.or.kr/lhapply/lhFile.do?fileid=123',
            status: 'BASELINE',
            message: '첫 성공 응답을 비교 기준으로 등록했습니다.',
            previousHash: null,
            currentHash: 'abc',
            bytes: 500,
        },
    ],
};

beforeEach(() => {
    vi.stubGlobal(
        'ResizeObserver',
        class {
            observe() {}
            unobserve() {}
            disconnect() {}
        },
    );
    vi.spyOn(api, 'list').mockResolvedValue([]);
    vi.spyOn(api, 'run').mockResolvedValue(completed);
});
afterEach(() => {
    vi.restoreAllMocks();
    vi.unstubAllGlobals();
});

function setup({ draftDirty = false, active = true } = {}) {
    const client = new QueryClient({
        defaultOptions: {
            queries: { retry: false },
            mutations: { retry: false },
        },
    });
    client.setQueryData(['notice', 'notice-1'], { version: 1 });
    client.setQueryData(['preparation', 'member-1'], { version: 1 });
    const view = render(
        <QueryClientProvider client={client}>
            <AttachmentChecks
                noticeId="notice-1"
                draftDirty={draftDirty}
                active={active}
            />
        </QueryClientProvider>,
    );
    return { client, view, user: userEvent.setup() };
}

it('does not load the history of a closed review', () => {
    setup({ active: false });
    expect(api.list).not.toHaveBeenCalled();
    expect(
        screen.getByRole('button', { name: '첨부 변경 확인' }),
    ).toBeDisabled();
});

it('describes first-baseline limits and keeps file history collapsed', async () => {
    vi.mocked(api.list).mockResolvedValue([completed]);
    const { user } = setup();
    expect(screen.getByText(/그 이전의 변경 여부/)).toBeInTheDocument();
    const summary = await screen.findByText('최근 점검 이력 · 1건');
    expect(summary.closest('details')).not.toHaveAttribute('open');
    await user.click(summary);
    expect(summary.closest('details')).toHaveAttribute('open');
    expect(screen.getByText('최초 기준 등록')).toBeInTheDocument();
    expect(
        screen.getByRole('link', { name: '수정 모집공고문 ↗' }),
    ).toHaveAttribute('href', completed.results[0].url);
    expect(screen.queryByText('내용 동일')).not.toBeInTheDocument();
});

it('blocks checking a notice with unsaved draft changes', async () => {
    const { user } = setup({ draftDirty: true });
    await screen.findByText('최근 점검 이력 · 0건');
    const button = screen.getByRole('button', { name: '첨부 변경 확인' });
    expect(button).toBeDisabled();
    await user.click(button);
    expect(api.run).not.toHaveBeenCalled();
    expect(screen.getByRole('status')).toHaveTextContent(
        '원고를 먼저 저장하거나 닫은 뒤',
    );
});

it('prevents duplicate checks and invalidates public notice and private preparation caches', async () => {
    let finish!: (run: AttachmentCheckRun) => void;
    vi.mocked(api.run).mockImplementation(
        () => new Promise((resolve) => (finish = resolve)),
    );
    const { user, client } = setup();
    await screen.findByText('최근 점검 이력 · 0건');
    await user.click(screen.getByRole('button', { name: '첨부 변경 확인' }));
    const pending = screen.getByRole('button', { name: '첨부 확인 중…' });
    expect(pending).toBeDisabled();
    await user.click(pending);
    expect(api.run).toHaveBeenCalledExactlyOnceWith('notice-1');
    vi.mocked(api.list).mockResolvedValue([completed]);
    await act(async () => finish(completed));
    await waitFor(() =>
        expect(
            client.getQueryState(['notice', 'notice-1'])?.isInvalidated,
        ).toBe(true),
    );
    expect(
        client.getQueryState(['preparation', 'member-1'])?.isInvalidated,
    ).toBe(true);
    expect(await screen.findByText('최근 점검 이력 · 1건')).toBeInTheDocument();
    expect(screen.getByRole('status')).toHaveTextContent('점검 완료');
});

it('blocks a second request when the server reports a running check', async () => {
    vi.mocked(api.list).mockResolvedValue([
        { ...completed, status: 'RUNNING', results: [], finishedAt: null },
    ]);
    setup();
    expect(
        await screen.findByRole('button', { name: '첨부 확인 중…' }),
    ).toBeDisabled();
    expect(api.run).not.toHaveBeenCalled();
});

it('shows a failed check without claiming success and refreshes potentially changed data', async () => {
    vi.mocked(api.run).mockRejectedValue(
        new Error('첨부 응답을 확인하지 못했습니다.'),
    );
    const { user, client } = setup();
    await screen.findByText('최근 점검 이력 · 0건');
    await user.click(screen.getByRole('button', { name: '첨부 변경 확인' }));
    expect(await screen.findByRole('alert')).toHaveTextContent(
        '첨부 응답을 확인하지 못했습니다.',
    );
    await waitFor(() =>
        expect(
            client.getQueryState(['notice', 'notice-1'])?.isInvalidated,
        ).toBe(true),
    );
    expect(screen.queryByText(/점검 완료 ·/)).not.toBeInTheDocument();
    expect(
        screen.getByRole('button', { name: '첨부 변경 확인' }),
    ).toBeEnabled();
});

it('lets the reviewer retry history loading after a failure', async () => {
    vi.mocked(api.list).mockRejectedValueOnce(new Error('연결 오류'));
    const { user } = setup();
    expect(await screen.findByRole('alert')).toHaveTextContent(
        '이력을 불러오지 못했습니다',
    );
    await user.click(
        screen.getByRole('button', { name: '이력 다시 불러오기' }),
    );
    expect(await screen.findByText('최근 점검 이력 · 0건')).toBeInTheDocument();
    expect(screen.queryByRole('alert')).not.toBeInTheDocument();
});
