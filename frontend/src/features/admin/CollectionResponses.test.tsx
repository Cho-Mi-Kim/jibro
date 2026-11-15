import { afterEach, beforeEach, expect, it, vi } from 'vitest';
import { render, screen } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { CollectionResponses } from './CollectionResponses';
import { collectionResponsesApi } from './collectionResponsesApi';
import type { CollectionResponseReceipt } from './collectionResponsesApi';

beforeEach(() => {
    vi.stubGlobal(
        'ResizeObserver',
        class {
            observe() {}
            unobserve() {}
            disconnect() {}
        },
    );
});
afterEach(() => {
    vi.restoreAllMocks();
    vi.unstubAllGlobals();
});

const receipt: CollectionResponseReceipt = {
    id: 'response-1',
    runId: 'run-1',
    runAttempt: 2,
    queryDate: '2026-11-07',
    page: 3,
    requestedAt: '2026-11-07T00:00:00Z',
    receivedAt: '2026-11-07T00:00:01Z',
    httpStatus: 200,
    sha256: 'a'.repeat(64),
    redacted: true,
    bodyStatus: 'JSON',
    outcome: 'ACCEPTED',
};

function renderPanel() {
    render(
        <QueryClientProvider
            client={
                new QueryClient({
                    defaultOptions: { queries: { retry: false } },
                })
            }
        >
            <CollectionResponses runId="run-1" />
        </QueryClientProvider>,
    );
}

it('loads large JSON only when requested and keeps provider text inert', async () => {
    const list = vi
        .spyOn(collectionResponsesApi, 'list')
        .mockResolvedValue([receipt]);
    const get = vi.spyOn(collectionResponsesApi, 'get').mockResolvedValue({
        receipt,
        bodyJson: '{"provider":"<script>unsafe()</script>"}',
    });
    const user = userEvent.setup();
    renderPanel();
    expect(list).not.toHaveBeenCalled();
    await user.click(screen.getByText('수집 원본 응답'));
    expect(await screen.findByText('3페이지 · 실행 2회차')).toBeVisible();
    expect(screen.getByText(/조회 기준일 2026-11-07/)).toBeVisible();
    expect(get).not.toHaveBeenCalled();
    await user.click(screen.getByRole('button', { name: '원본 JSON 보기' }));
    const output = await screen.findByLabelText('인증 정보를 제거한 원본 JSON');
    expect(output).toHaveTextContent('<script>unsafe()</script>');
    expect(output.querySelector('script')).toBeNull();
    expect(get).toHaveBeenCalledWith('run-1', 'response-1');
});

it('shows missing or rejected response metadata without offering nonexistent JSON', async () => {
    vi.spyOn(collectionResponsesApi, 'list').mockResolvedValue([
        {
            ...receipt,
            sha256: null,
            redacted: false,
            bodyStatus: 'TOO_LARGE',
            outcome: 'REJECTED',
        },
    ]);
    const user = userEvent.setup();
    renderPanel();
    await user.click(screen.getByText('수집 원본 응답'));
    expect(
        await screen.findByText(
            '응답 크기 한도를 초과해 본문을 보관하지 않았습니다.',
        ),
    ).toBeVisible();
    expect(
        screen.queryByRole('button', { name: '원본 JSON 보기' }),
    ).not.toBeInTheDocument();
});
