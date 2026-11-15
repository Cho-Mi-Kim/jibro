import { afterEach, expect, it, vi } from 'vitest';
import { render, screen } from '@testing-library/react';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { CollectionSchedulePanel } from './CollectionSchedulePanel';
import { collectionScheduleApi } from './collectionScheduleApi';

afterEach(() => vi.restoreAllMocks());

function renderPanel() {
    render(
        <QueryClientProvider
            client={
                new QueryClient({
                    defaultOptions: { queries: { retry: false } },
                })
            }
        >
            <CollectionSchedulePanel />
        </QueryClientProvider>,
    );
}

it('shows the Korea schedule and next run without claiming an unconfigured collector is active', async () => {
    vi.spyOn(collectionScheduleApi, 'get').mockResolvedValue({
        enabled: true,
        configured: false,
        timezone: 'Asia/Seoul',
        times: ['00:00', '06:00', '09:00'],
        nextRunAt: null,
        providerResumeAt: null,
        pendingRuns: 0,
        message: 'LH 수집 키가 없어 예약 수집을 실행하지 않습니다.',
    });
    renderPanel();
    expect(await screen.findByText('수집 키 필요')).toBeVisible();
    expect(
        screen.getByText('매일 00:00 · 06:00 · 09:00~18:00 매시간'),
    ).toBeVisible();
    expect(screen.queryByText(/다음 예약/)).not.toBeInTheDocument();
});

it('shows the active schedule and persistent pending work', async () => {
    vi.spyOn(collectionScheduleApi, 'get').mockResolvedValue({
        enabled: true,
        configured: true,
        timezone: 'Asia/Seoul',
        times: ['00:00', '06:00', '09:00'],
        nextRunAt: '2026-11-07T00:00:00Z',
        providerResumeAt: '2026-11-08T00:00:00Z',
        pendingRuns: 2,
        message: '예약 수집이 실행 중입니다.',
    });
    renderPanel();
    expect(await screen.findByText('사용 중')).toBeVisible();
    expect(screen.getByText('실행·재시도 대기 2건')).toBeVisible();
    expect(screen.getByText(/다음 예약/)).toBeVisible();
    expect(screen.getByText(/공식 서버 요청 대기/)).toHaveTextContent(
        '새 수집도 기다립니다.',
    );
});
