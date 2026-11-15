import { beforeEach, expect, it, vi } from 'vitest';
import { render, screen, waitFor } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { MemoryRouter } from 'react-router';
import { GuidePage } from './GuidePage';
import { api } from '../features/housing/api';
import type { Guide } from '../features/housing/types';

vi.mock('../features/housing/api', () => ({ api: { guides: vi.fn() } }));
const guide = (id: number): Guide => ({
    id: String(id),
    category: '조건과 용어',
    title: `안내 ${id}`,
    body: '확인한 내용',
    evidence: {
        label: '공고 원문',
        url: 'https://apply.lh.or.kr/',
        checkedAt: '2030-01-01',
    },
});
beforeEach(() => {
    vi.clearAllMocks();
    vi.stubGlobal('scrollTo', vi.fn());
    vi.mocked(api.guides).mockImplementation(async (query) => {
        const params = new URLSearchParams(query);
        const page = Number(params.get('page'));
        return {
            items: Array.from({ length: 20 }, (_, i) => guide(page * 20 + i)),
            page,
            hasMore: page === 0,
        };
    });
});
function mount(path = '/guide') {
    return render(
        <QueryClientProvider
            client={
                new QueryClient({
                    defaultOptions: { queries: { retry: false } },
                })
            }
        >
            <MemoryRouter initialEntries={[path]}>
                <GuidePage />
            </MemoryRouter>
        </QueryClientProvider>,
    );
}

it('keeps only the current page mounted and replaces it when navigating', async () => {
    const user = userEvent.setup();
    const { container } = mount();
    await screen.findByText('안내 0');
    expect(container.querySelectorAll('details')).toHaveLength(20);
    await user.click(screen.getByRole('button', { name: '다음 페이지' }));
    await screen.findByText('안내 20');
    expect(screen.queryByText('안내 0')).not.toBeInTheDocument();
    expect(container.querySelectorAll('details')).toHaveLength(20);
    expect(screen.getByRole('button', { name: '다음 페이지' })).toBeDisabled();
    await user.click(screen.getByRole('button', { name: '이전 페이지' }));
    await screen.findByText('안내 0');
});

it('searches on the server, resets pagination, and toggles the selected topic off', async () => {
    const user = userEvent.setup();
    mount('/guide?page=2');
    await screen.findByText('안내 40');
    await user.click(screen.getByRole('button', { name: '조건과 용어' }));
    await waitFor(() =>
        expect(api.guides).toHaveBeenLastCalledWith(
            new URLSearchParams({
                category: '조건과 용어',
                keyword: '',
                page: '0',
            }).toString(),
        ),
    );
    await user.click(screen.getByRole('button', { name: '조건과 용어' }));
    await user.type(screen.getByRole('searchbox'), '주민등록');
    await waitFor(() =>
        expect(api.guides).toHaveBeenLastCalledWith(
            new URLSearchParams({
                category: '',
                keyword: '주민등록',
                page: '0',
            }).toString(),
        ),
    );
});
