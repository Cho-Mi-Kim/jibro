import { beforeEach, expect, it, vi } from 'vitest';
import { render, screen, waitFor } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { MemoryRouter, Route, Routes } from 'react-router';
import { NoticeCorrectionBanner } from './NoticeCorrectionBanner';
import { noticeCorrection } from './noticeCorrectionApi';
import type { Notice } from './types';

vi.mock('./noticeCorrectionApi', () => ({ noticeCorrection: vi.fn() }));
const notice = { id: 'original', guideState: 'REVIEWING' } as Notice;
beforeEach(() => vi.clearAllMocks());
function mount(currentNotice: Notice = notice) {
    const client = new QueryClient({
        defaultOptions: { queries: { retry: false } },
    });
    render(
        <QueryClientProvider client={client}>
            <MemoryRouter initialEntries={['/notices/original']}>
                <Routes>
                    <Route
                        path="/notices/original"
                        element={
                            <NoticeCorrectionBanner notice={currentNotice} />
                        }
                    />
                    <Route
                        path="/notices/corrected"
                        element={<h1>정정본 상세</h1>}
                    />
                </Routes>
            </MemoryRouter>
        </QueryClientProvider>,
    );
}
it('navigates to the confirmed correction only after the reader chooses it', async () => {
    vi.mocked(noticeCorrection).mockResolvedValue({
        noticeId: 'corrected',
        title: '정정 공고',
        reason: '서류 제출 장소가 변경되었습니다.',
        evidence: {
            label: 'LH 정정 원문',
            url: 'https://apply.lh.or.kr/correction',
            checkedAt: '2026-11-08',
        },
        checkedAt: '2026-11-08T06:00:00Z',
    });
    mount();
    const link = await screen.findByRole('link', { name: '정정 공고로 이동' });
    expect(screen.queryByText('정정본 상세')).not.toBeInTheDocument();
    await userEvent.click(link);
    expect(
        await screen.findByRole('heading', { name: '정정본 상세' }),
    ).toBeInTheDocument();
});
it('does not invent a correction when the server has no verified relationship', async () => {
    vi.mocked(noticeCorrection).mockResolvedValue(null);
    mount();
    await waitFor(() =>
        expect(noticeCorrection).toHaveBeenCalledWith('original'),
    );
    expect(
        screen.queryByRole('link', { name: '정정 공고로 이동' }),
    ).not.toBeInTheDocument();
});
it.each(['AVAILABLE', 'CHANGE_REVIEW'] as const)(
    'checks correction links even when the original guidance state is %s',
    async (guideState) => {
        vi.mocked(noticeCorrection).mockResolvedValue(null);
        mount({ ...notice, guideState });
        await waitFor(() =>
            expect(noticeCorrection).toHaveBeenCalledWith('original'),
        );
    },
);
it('offers retry when checking the verified relationship fails', async () => {
    vi.mocked(noticeCorrection)
        .mockRejectedValueOnce(new Error('offline'))
        .mockResolvedValue(null);
    mount();
    await userEvent.click(
        await screen.findByRole('button', { name: '다시 확인' }),
    );
    await waitFor(() => expect(noticeCorrection).toHaveBeenCalledTimes(2));
});
