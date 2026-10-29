import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { render, screen } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { describe, expect, it, vi } from 'vitest';
import { DevelopmentPage } from './DevelopmentPage';

function renderPage() {
    return render(
        <QueryClientProvider
            client={
                new QueryClient({
                    defaultOptions: { queries: { retry: false } },
                })
            }
        >
            <DevelopmentPage />
        </QueryClientProvider>,
    );
}

describe('개발 환경 연결 확인', () => {
    it('API 응답의 상태를 표시한다', async () => {
        const fetchMock = vi.fn();
        fetchMock.mockResolvedValue(
            new Response(JSON.stringify({ status: 'UP', database: 'UP' }), {
                status: 200,
            }),
        );
        vi.stubGlobal('fetch', fetchMock);
        renderPage();
        expect(
            await screen.findByText('API: UP / PostgreSQL: UP'),
        ).toBeInTheDocument();
        expect(fetchMock.mock.calls[0][0]).toBe('/api/v1/health');
    });

    it('오류 이후 다시 확인하여 복구된 응답을 표시한다', async () => {
        const fetchMock = vi.fn();
        fetchMock.mockResolvedValueOnce(new Response('{}', { status: 503 }));
        fetchMock.mockResolvedValueOnce(
            new Response(JSON.stringify({ status: 'UP', database: 'UP' }), {
                status: 200,
            }),
        );
        vi.stubGlobal('fetch', fetchMock);
        renderPage();
        expect(await screen.findByRole('alert')).toHaveTextContent(
            '연결 확인에 실패했습니다',
        );
        await userEvent.click(
            screen.getByRole('button', { name: '다시 확인' }),
        );
        expect(
            await screen.findByText('API: UP / PostgreSQL: UP'),
        ).toBeInTheDocument();
    });
});
